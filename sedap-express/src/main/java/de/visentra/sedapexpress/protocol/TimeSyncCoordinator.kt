package de.visentra.sedapexpress.protocol

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.messages.TIMESYNC
import de.visentra.sedapexpress.wire.SedapParser

/**
 * TIMESYNC exchanges (PROTOCOL §7): answers peers' requests with the current SEDAP time and, on a reply
 * to its own request, hands the round-trip-corrected offset to [SedapClock]. Never touches the device
 * clock. Completely silent while the clock is disabled: no requests, no answers.
 *
 * The ICD has no correlation ID, so only one request is outstanding and the next reply counts as its
 * answer. When to request (on connect, every 300 s) is decided by the connection.
 * Not thread-safe: call it from the connection's serial dispatcher only.
 */
public class TimeSyncCoordinator(
    private val clock: SedapClock,
    private val time: TimeSource,
    private val identity: () -> Identity?,
    private val send: (SEDAPExpressMessage) -> Unit,
    receiveHandler: ReceiveHandler,
) {

    /** Monotonic send time of the outstanding request, null if none. */
    private var pendingSentAtNanos: Long? = null

    init {
        receiveHandler.subscribe(::handle)
    }

    /** Sends a request ("what time do you have?"). Replaces an unanswered earlier one. */
    public fun requestSync() {
        if (!clock.isEnabled) return
        val id = identity() ?: return
        pendingSentAtNanos = time.monotonicNanos()
        send(TIMESYNC(null, null, id.sender, id.classification, null, null))
    }

    private fun handle(line: String) {
        if (!clock.isEnabled) return
        if (!line.substringBefore(';').equals(TYPE, ignoreCase = true)) return
        val message = SedapParser.parse(line)?.message as? TIMESYNC ?: return
        val id = identity()
        // An echo of our own TIMESYNC would otherwise be answered or adopted (IOS-04).
        if (id != null && message.sender == id.sender) return

        val hasTimestampField = line.split(';').getOrElse(TIMESTAMP_INDEX) { "" }.isNotEmpty()
        val timestamp = message.timestamp
        when {
            !hasTimestampField -> answerRequest(id)
            // A timestamp upstream could not read is neither a request nor a usable reply.
            timestamp != null -> adoptReply(timestamp)
        }
    }

    private fun adoptReply(peerMillis: Long) {
        val sentAt = pendingSentAtNanos ?: return
        pendingSentAtNanos = null
        val roundTripMillis = (time.monotonicNanos() - sentAt) / NANOS_PER_MILLI
        clock.applySync(peerMillis + roundTripMillis / 2 - time.currentTimeMillis())
    }

    private fun answerRequest(id: Identity?) {
        if (id == null) return
        send(TIMESYNC(null, null, id.sender, id.classification, null, null, clock.nowMillis()))
    }

    private companion object {
        const val TYPE = "TIMESYNC"
        const val TIMESTAMP_INDEX = 7
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
