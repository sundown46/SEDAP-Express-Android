package de.visentra.sedapexpress.transport

import kotlinx.coroutines.flow.Flow
import java.io.Closeable
import java.io.IOException
import java.net.Socket

/**
 * Opens one link per connection attempt. The transport itself never retries: retries, backoff and
 * generations belong to the connection that owns it.
 */
public interface SedapTransport {
    /**
     * True for transports with a lasting connection (TCP), whose link ends on EOF. False for
     * datagram transports (UDP), whose link only ends on an error or when it is closed.
     */
    public val isConnectionOriented: Boolean

    /** Opens a new link. Throws [IOException], honours the connect timeout and closes everything again when cancelled. */
    public suspend fun open(): SedapLink
}

/** One open link. Closing it ends a running [lines] flow normally and makes [send] fail. */
public interface SedapLink : Closeable {
    /**
     * Received lines without terminator, decoded as ISO-8859-1. Completes on EOF and when the link
     * is closed, throws on errors ([LineTooLongException] for an over-long line). Single collector:
     * a second collection throws [IllegalStateException]. Cancelling the collection closes the link.
     */
    public fun lines(): Flow<String>

    /** Sends [line] plus "\n" as ISO-8859-1. Throws [IOException] on a write error or a character outside ISO-8859-1. */
    public suspend fun send(line: String)
}

/** Android hook to tune a TCP socket before it connects, e.g. keepalive timings (FR-CON-06). */
public fun interface SocketConfigurator {
    public fun configure(socket: Socket)
}

/** Android hook for the Wi-Fi multicast lock, without which many devices drop broadcast and multicast datagrams. */
public interface MulticastLockHook {
    public fun acquire()
    public fun release()
}

/** Diagnostic output of the library; the platform decides where it goes. */
public fun interface SedapLogger {
    public fun log(message: String)
}

/** A received line exceeded the limit without a "\n" (NFR-REL-02). The link is unusable afterwards. */
public class LineTooLongException(public val maxLineBytes: Int) :
    IOException("Line longer than $maxLineBytes bytes without line feed") {
    /** Complete lines that preceded the over-long one in the same [LineBuffer.append] call. */
    public var linesBefore: List<String> = emptyList()
        internal set
}

/** A datagram transport cannot send a line this long in one datagram. Nothing was sent; the link stays usable. */
public class DatagramTooLargeException(public val size: Int, public val maxSize: Int) :
    IOException("Datagram of $size bytes exceeds $maxSize bytes")
