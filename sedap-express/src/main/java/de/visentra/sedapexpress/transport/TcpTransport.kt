package de.visentra.sedapexpress.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SEDAP-Express over TCP (ICD §4) with a blocking [Socket] on Dispatchers.IO.
 *
 * Every socket gets SO_KEEPALIVE and TCP_NODELAY, then [configurator] may tune it further (Android
 * sets the keepalive timings of FR-CON-06 there). The connect, including the name lookup, runs on
 * Dispatchers.IO; the connect itself is bounded by [connectTimeoutMillis] (FR-CON-03).
 */
public class TcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 10_000,
    private val maxLineBytes: Int = 1 shl 20,
    private val configurator: SocketConfigurator? = null,
) : SedapTransport {

    init {
        require(port in 1..65_535) { "port out of range" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(maxLineBytes > 0) { "maxLineBytes must be positive" }
    }

    override val isConnectionOriented: Boolean get() = true

    override suspend fun open(): SedapLink {
        val socket = Socket()
        try {
            socket.keepAlive = true
            socket.tcpNoDelay = true
            configurator?.configure(socket)
            closeOnCancel(socket) {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            }
            return TcpLink(socket, maxLineBytes)
        } catch (e: Throwable) {
            socket.closeQuietly()
            // A cancelled open reports cancellation, not the IOException its own socket close caused.
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    override fun toString(): String = "TcpTransport($host:$port)"
}

private class TcpLink(private val socket: Socket, maxLineBytes: Int) : BlockingLink() {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val buffer = LineBuffer(maxLineBytes)
    private val chunk = ByteArray(64 * 1024)
    private val writeLock = Mutex()
    private var overflow: LineTooLongException? = null

    override fun readLines(): List<String>? {
        // Deliver the good lines that preceded an over-long one, then fail on the next read.
        overflow?.let { throw it }
        val count = input.read(chunk)
        if (count < 0) return null
        return try {
            buffer.append(chunk, count)
        } catch (e: LineTooLongException) {
            if (e.linesBefore.isEmpty()) throw e
            overflow = e
            e.linesBefore
        }
    }

    override suspend fun send(line: String) {
        val bytes = encodeLine(line)
        // One writer at a time, so concurrent sends never interleave inside a line.
        writeLock.withLock {
            blockingSend {
                output.write(bytes)
                output.flush()
            }
        }
    }

    override fun closeResources() {
        socket.closeQuietly()
    }
}
