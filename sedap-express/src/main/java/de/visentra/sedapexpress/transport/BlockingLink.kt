package de.visentra.sedapexpress.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A link over a blocking java.net socket. One reader coroutine on [Dispatchers.IO] blocks in
 * [readLines]; cancelling the collection closes the socket, which is the only way to wake a blocked
 * read.
 */
internal abstract class BlockingLink : SedapLink {

    private val collecting = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    protected val isClosed: Boolean get() = closed.get()

    /** Blocks until lines arrive; returns null on EOF. May return an empty list. */
    protected abstract fun readLines(): List<String>?

    /** Closes the socket and anything tied to it. Called at most once. */
    protected abstract fun closeResources()

    final override fun close() {
        if (closed.compareAndSet(false, true)) closeResources()
    }

    final override fun lines(): Flow<String> = channelFlow {
        check(collecting.compareAndSet(false, true)) { "lines() supports a single collector" }
        launch(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                while (true) {
                    val lines = readLines() ?: break
                    for (line in lines) channel.send(line) // the collector, not SedapLink.send
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A read failing because the link was closed on purpose is a normal end.
                if (!isClosed) failure = e
            }
            channel.close(failure)
        }
        awaitClose { this@BlockingLink.close() }
    }

    /** Runs a blocking send; a failure caused by cancellation is reported as cancellation. */
    protected suspend fun <T> blockingSend(block: () -> T): T {
        if (isClosed) throw java.io.IOException("Link closed")
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            throw e
        }
    }
}

/**
 * Runs the blocking [block] on [Dispatchers.IO] and closes [resource] if the caller is cancelled
 * meanwhile, because a blocked java.net call ignores thread interrupts. The caller still has to turn
 * the resulting IOException into cancellation (see [TcpTransport.open]).
 */
internal suspend fun <T> closeOnCancel(resource: Closeable, block: () -> T): T = coroutineScope {
    val done = AtomicBoolean(false)
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!done.get()) resource.closeQuietly()
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } finally {
        done.set(true)
        closer.cancel()
    }
}

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Nothing useful to do when closing fails.
    }
}
