package de.visentra.sedapexpress.connection

import de.visentra.sedapexpress.protocol.TimeSource
import de.visentra.sedapexpress.transport.SedapLink
import de.visentra.sedapexpress.transport.SedapTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException

/** Links in channels instead of sockets. [registry] is shared between transports to catch overlapping links. */
internal class FakeTransport(
    override val isConnectionOriented: Boolean = true,
    private val registry: LinkRegistry = LinkRegistry(),
) : SedapTransport {

    /** Thrown by the next open() calls in order; when empty, open() succeeds. */
    val openFailures = ArrayDeque<Exception>()

    /** Virtual time one open() takes. */
    var openDelayMillis = 0L

    var opens = 0
        private set

    val links = ArrayList<FakeLink>()

    override suspend fun open(): SedapLink {
        opens++
        if (openDelayMillis > 0) delay(openDelayMillis)
        openFailures.removeFirstOrNull()?.let { throw it }
        if (registry.links.any { !it.isClosed }) registry.overlapSeen = true
        return FakeLink().also {
            links.add(it)
            registry.links.add(it)
        }
    }
}

internal class LinkRegistry {
    val links = ArrayList<FakeLink>()

    /** True if a link was opened while another one was still open (FR-CON-01). */
    var overlapSeen = false
}

internal class FakeLink : SedapLink {
    private val incoming = Channel<String>(Channel.UNLIMITED)

    val sent = ArrayList<String>()

    /** Thrown by the next send() calls. */
    var sendFailure: IOException? = null

    /** Virtual time one send() takes before it writes. */
    var sendDelayMillis = 0L

    var isClosed = false
        private set

    override fun lines(): Flow<String> = incoming.receiveAsFlow()

    override suspend fun send(line: String) {
        if (sendDelayMillis > 0) delay(sendDelayMillis)
        if (isClosed) throw IOException("Link closed")
        sendFailure?.let { throw it }
        sent.add(line)
    }

    /** Like the real links: closing ends lines() normally. */
    override fun close() {
        isClosed = true
        incoming.close()
    }

    /** The peer sends a line. */
    fun receive(line: String) {
        check(incoming.trySend(line).isSuccess) { "link closed" }
    }

    /** The peer closes the connection. */
    fun endOfStream() {
        incoming.close()
    }

    /** The link breaks with [cause]. */
    fun fail(cause: Throwable) {
        incoming.close(cause)
    }

    fun sentOfType(type: String): List<String> = sent.filter { it.substringBefore(';') == type }
}

/** Device clock and monotonic clock driven by the test scheduler's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SchedulerTimeSource(
    private val scheduler: TestCoroutineScheduler,
    private val epochMillis: Long = 1_726_000_000_000L,
) : TimeSource {
    override fun currentTimeMillis(): Long = epochMillis + scheduler.currentTime
    override fun monotonicNanos(): Long = scheduler.currentTime * 1_000_000L
}
