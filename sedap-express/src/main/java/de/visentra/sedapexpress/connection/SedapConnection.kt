package de.visentra.sedapexpress.connection

import de.bundeswehr.uniity.sedapexpress.messages.ACKNOWLEDGE
import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.visentra.sedapexpress.protocol.AckRequest
import de.visentra.sedapexpress.protocol.Identity
import de.visentra.sedapexpress.protocol.MessageLogSink
import de.visentra.sedapexpress.protocol.ReceiveHandler
import de.visentra.sedapexpress.protocol.SedapClock
import de.visentra.sedapexpress.protocol.SendHandler
import de.visentra.sedapexpress.protocol.TimeSource
import de.visentra.sedapexpress.protocol.TimeSyncCoordinator
import de.visentra.sedapexpress.transport.DatagramTooLargeException
import de.visentra.sedapexpress.transport.LineTooLongException
import de.visentra.sedapexpress.transport.SedapLink
import de.visentra.sedapexpress.transport.SedapTransport
import de.visentra.sedapexpress.transport.closeQuietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor

/**
 * One SEDAP-Express connection with everything that keeps it alive: the state machine of FR-CON-02,
 * reconnects with backoff (FR-CON-04), HEARTBEAT every 20 s (FR-CON-12), TIMESYNC on connect and every
 * 300 s while the clock is enabled, ACKNOWLEDGE replies and the network grace period (FR-CON-07).
 * It owns the [SendHandler], [ReceiveHandler] and [TimeSyncCoordinator]; there is never more than one
 * link at a time (FR-CON-01).
 *
 * **Threading:** all state lives on one serial dispatcher, the dispatcher of [scope] limited to one
 * (Dispatchers.Default if [scope] has none). Blocking socket calls run on Dispatchers.IO inside the
 * transport. Every public function may be called from any thread; [connect], [disconnect],
 * [networkLost] and [networkAvailable] return at once and take effect on the serial dispatcher in call
 * order. Cancelling [scope] closes the link and stops everything.
 *
 * **Generations:** [generation] grows with every [connect] and [disconnect], never with an automatic
 * reconnect. Lines carry the generation their link was opened with; a line of an older generation is
 * dropped before it reaches the receive pipeline (FR-CON-09, FR-CON-11).
 */
public class SedapConnection(
    private val scope: CoroutineScope,
    private val identity: () -> Identity?,
    macPassword: () -> String?,
    time: TimeSource = TimeSource.SYSTEM,
    private val policy: ConnectionPolicy = ConnectionPolicy(),
    log: MessageLogSink? = null,
) {

    private val serial: CoroutineDispatcher = serialDispatcher(scope)

    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Ready)

    /** The current state, for the main view (FR-CON-02). */
    public val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Reports that are not a state, e.g. a rejected MAC or a refused send. No replay. */
    public val events: SharedFlow<ConnectionEvent> = mutableEvents.asSharedFlow()

    private val mutableGeneration = MutableStateFlow(0L)

    /**
     * Bumped by [connect] and [disconnect], not by automatic reconnects. Consumers drop their picture
     * when it changes for a different server. Bumped before [connect] or [disconnect] returns.
     */
    public val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    /** Stamps every outgoing message. Disabled by default; enabling it turns on TIMESYNC. */
    public val clock: SedapClock = SedapClock(time)

    private val sendHandler = SendHandler(clock, macPassword, log)
    private val receiveHandler = ReceiveHandler(macPassword, { identity()?.sender }, log)
    private val timeSync: TimeSyncCoordinator

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Held while [generation] is bumped and while a line is handed to [listeners]. Once [connect] or
     * [disconnect] has returned, no line of an earlier generation reaches a listener.
     */
    private val deliveryLock = Any()

    // Everything below is confined to [serial].
    private var transport: SedapTransport? = null
    private var sessionGeneration = 0L
    private var current: Attempt? = null
    private var reconnectJob: Job? = null
    private var graceJob: Job? = null

    /** Failed attempts since the last success or manual action; drives the backoff. */
    private var failedAttempts = 0

    /** Generation of the line [receiveHandler] is handling right now. */
    private var lineGeneration = -1L

    init {
        receiveHandler.subscribe(::deliver)
        receiveHandler.onMacRejected = { line -> emit(ConnectionEvent.MacRejected(line)) }
        receiveHandler.onAcknowledgementRequested = ::acknowledge
        timeSync = TimeSyncCoordinator(clock, time, identity, ::sendDetached, receiveHandler)
    }

    /**
     * Connects through [transport]. Replaces any link and any pending reconnect, so two links never
     * exist at once, and resets the backoff (FR-CON-04).
     */
    public fun connect(transport: SedapTransport) {
        val generation = bumpGeneration()
        onSerial {
            this.transport = transport
            sessionGeneration = generation
            failedAttempts = 0
            cancelReconnect()
            establish()
        }
    }

    /** Closes the link and cancels a pending reconnect, also during the backoff (FR-CON-05, IOS-20). */
    public fun disconnect() {
        bumpGeneration()
        onSerial {
            transport = null
            failedAttempts = 0
            cancelReconnect()
            teardown()
            setState(ConnectionState.ManuallyDisconnected)
        }
    }

    /**
     * Sends [message] over the open link. Number, Time and MAC are set only here, so a message that is
     * not sent uses no number. A write error starts a reconnect (FR-CON-06).
     */
    public suspend fun send(message: SEDAPExpressMessage): SendResult = withContext(serial) { sendNow(message) }

    /**
     * Adds [listener] for every received plaintext line (decompressed, MAC checked) of the current
     * generation. Listeners run on the connection's dispatcher while delivery is locked: keep them short,
     * never block and never throw, an exception escapes into the scope.
     */
    public fun subscribe(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Android hook: the network is gone. If it does not come back within the grace period, the link is
     * given up and rebuilt (FR-CON-07). Ignored while no link is open.
     */
    public fun networkLost() {
        onSerial {
            val attempt = current
            if (attempt?.link == null || graceJob != null) return@onSerial
            graceJob = scope.launch(serial) {
                delay(policy.networkGraceMillis)
                graceJob = null
                linkFailed(attempt, NetworkLostException(policy.networkGraceMillis))
            }
        }
    }

    /** Android hook: the network is back. Ends a running grace period; the link stays. */
    public fun networkAvailable() {
        onSerial {
            graceJob?.cancel()
            graceJob = null
        }
    }

    /** One try to open a link and everything that lives as long as that link. */
    private class Attempt(val generation: Long) {
        var job: Job? = null
        var link: SedapLink? = null
    }

    private fun onSerial(block: () -> Unit) {
        scope.launch(serial) { block() }
    }

    private fun bumpGeneration(): Long = synchronized(deliveryLock) {
        val next = mutableGeneration.value + 1
        mutableGeneration.value = next
        next
    }

    private fun establish() {
        val transport = transport ?: return
        teardown()
        val attempt = Attempt(sessionGeneration)
        current = attempt
        setState(ConnectionState.Connecting)
        attempt.job = scope.launch(serial) { runAttempt(attempt, transport) }
    }

    private suspend fun CoroutineScope.runAttempt(attempt: Attempt, transport: SedapTransport) {
        val link = try {
            transport.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            linkFailed(attempt, e)
            return
        }
        if (current !== attempt) {
            link.closeQuietly()
            return
        }
        attempt.link = link
        failedAttempts = 0
        setState(ConnectionState.Connected)
        // Both end with the link, because they are children of this attempt's job.
        launch { heartbeats() }
        launch { timeSyncRequests() }

        // Only errors of the link count as link failure; a listener's exception propagates.
        var failure: Throwable? = null
        link.lines()
            .catch { failure = it }
            .collect { line -> onLine(attempt, line) }
        linkFailed(attempt, failure)
    }

    private fun onLine(attempt: Attempt, line: String) {
        // A line of a replaced link or an earlier generation must not touch anything (FR-CON-09, -11).
        if (current !== attempt || attempt.generation != mutableGeneration.value) return
        lineGeneration = attempt.generation
        receiveHandler.handle(line)
    }

    private fun deliver(line: String) {
        synchronized(deliveryLock) {
            if (lineGeneration != mutableGeneration.value) return
            for (listener in listeners) listener(line)
        }
    }

    /** The link of [attempt] failed or could not be opened. A late report from a replaced attempt changes nothing. */
    private fun linkFailed(attempt: Attempt, cause: Throwable?) {
        if (current !== attempt) return
        teardown()
        emit(if (cause is LineTooLongException) ConnectionEvent.LineTooLong else ConnectionEvent.LinkFailed(cause))
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        failedAttempts++
        val delayMillis = policy.backoffMillis(failedAttempts)
        setState(ConnectionState.Disconnected(failedAttempts, delayMillis))
        emit(ConnectionEvent.ReconnectScheduled(failedAttempts, delayMillis))
        reconnectJob = scope.launch(serial) {
            delay(delayMillis)
            reconnectJob = null
            establish()
        }
    }

    /**
     * Cancels a pending reconnect WITHOUT resetting [failedAttempts]: on iOS that reset made every retry
     * start at 2 s again (ARCHITECTURE §7).
     */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Stops the current attempt: cancels its jobs and closes its link. Safe without an attempt. */
    private fun teardown() {
        graceJob?.cancel()
        graceJob = null
        val attempt = current ?: return
        current = null
        attempt.job?.cancel()
        attempt.link?.closeQuietly()
    }

    private suspend fun sendNow(message: SEDAPExpressMessage): SendResult {
        val attempt = current
        val link = attempt?.link
        if (attempt == null || link == null) return refuse(message, SendResult.NOT_CONNECTED)
        val line = sendHandler.prepare(message) ?: return refuse(message, SendResult.REFUSED)
        return try {
            link.send(line)
            SendResult.SENT
        } catch (_: DatagramTooLargeException) {
            // Only this line is too long for UDP; the link itself is fine.
            refuse(message, SendResult.REFUSED)
        } catch (e: IOException) {
            linkFailed(attempt, e)
            SendResult.FAILED
        }
    }

    private fun refuse(message: SEDAPExpressMessage, result: SendResult): SendResult {
        emit(ConnectionEvent.SendRefused(message.messageType.name))
        return result
    }

    /** For messages sent from a callback on [serial] (ACK, TIMESYNC); starts right away, in call order. */
    private fun sendDetached(message: SEDAPExpressMessage) {
        scope.launch(serial, start = CoroutineStart.UNDISPATCHED) { sendNow(message) }
    }

    /** HEARTBEAT without recipient every 20 s; a failed send detects a half-open link (FR-CON-06, -12). */
    private suspend fun heartbeats() {
        while (true) {
            delay(policy.heartbeatIntervalMillis)
            // Without an identity there is no sender; App and plugin only connect with an active profile.
            val id = identity() ?: continue
            sendNow(HEARTBEAT(null, null, id.sender, id.classification, null, null))
        }
    }

    /** TIMESYNC request on connect and then periodically; the coordinator stays silent while the clock is disabled. */
    private suspend fun timeSyncRequests() {
        while (true) {
            timeSync.requestSync()
            delay(policy.timeSyncIntervalMillis)
        }
    }

    /** PROTOCOL §6: recipient is the original sender, plus its type and number. Only sent while connected. */
    private fun acknowledge(request: AckRequest) {
        val id = identity() ?: return
        val type = runCatching { SEDAPExpressMessage.MessageType.valueOf(request.messageType) }.getOrNull() ?: return
        val number = request.messageNumber.toIntOrNull(HEX)?.takeIf { it in 0..MAX_NUMBER } ?: return
        sendDetached(
            ACKNOWLEDGE(null, null, id.sender, id.classification, null, null, request.recipient, type, number.toByte()),
        )
    }

    private fun setState(state: ConnectionState) {
        mutableState.value = state
    }

    private fun emit(event: ConnectionEvent) {
        mutableEvents.tryEmit(event)
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val HEX = 16

        /** The number field holds one byte as two hex digits. */
        const val MAX_NUMBER = 0xFF

        /**
         * The scope's dispatcher limited to one, so tests with a test dispatcher run in virtual time.
         * Still experimental in coroutines 1.8.1, the version ATAK 5.6 ships (stable from 1.9).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun serialDispatcher(scope: CoroutineScope): CoroutineDispatcher {
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            val base = if (dispatcher == null || dispatcher === Dispatchers.Unconfined) Dispatchers.Default else dispatcher
            return base.limitedParallelism(1)
        }
    }
}
