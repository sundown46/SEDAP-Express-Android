package de.visentra.sedapexpress.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/** How a [UdpTransport] addresses its peers (ICD §4). The ICD's default port is 50000. */
public sealed interface UdpMode {
    /** Sends to one peer at [host]:[port] and receives on [localPort] (0 picks a free port). */
    public data class Unicast(val host: String, val port: Int, val localPort: Int) : UdpMode {
        init {
            require(port in 1..65_535) { "port out of range" }
            require(localPort in 0..65_535) { "localPort out of range" }
        }
    }

    /** Sends to 255.255.255.255:[port] and receives everything on [port]. */
    public data class Broadcast(val port: Int) : UdpMode {
        init {
            require(port in 1..65_535) { "port out of range" }
        }
    }

    /** Joins [group] (ICD: 228.2.19.80 or ff02:8:2:19:80::1), sends to it and receives on [port]. */
    public data class Multicast(val group: String, val port: Int) : UdpMode {
        init {
            require(port in 1..65_535) { "port out of range" }
        }
    }
}

/**
 * SEDAP-Express over UDP (ICD §4). One datagram per sent line; a received datagram may carry several
 * lines. There is no connection: [open] binds the socket and the link only ends on an error or when
 * it is closed. Delivery is not guaranteed, so the ICD recommends message numbers and acknowledgements.
 *
 * In broadcast and multicast mode [lock] is acquired before the socket opens and released when the
 * link closes, and datagrams this socket sent itself are dropped when they come back (like the
 * reference's UDP client), so the own position never shows up as a track.
 */
public class UdpTransport(
    private val mode: UdpMode,
    private val lock: MulticastLockHook? = null,
) : SedapTransport {

    override val isConnectionOriented: Boolean get() = false

    override suspend fun open(): SedapLink {
        val groupMode = mode !is UdpMode.Unicast
        val heldLock = if (groupMode) lock else null
        heldLock?.acquire()
        val created = AtomicReference<UdpLink?>(null)
        try {
            withContext(Dispatchers.IO) { created.set(createLink(heldLock, filterOwnEcho = groupMode)) }
            return created.get() ?: throw IOException("UDP link not created")
        } catch (e: Throwable) {
            val link = created.get()
            if (link != null) link.close() else heldLock?.release()
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    /** Opens and binds the socket; closes it again if any step fails. */
    private fun createLink(heldLock: MulticastLockHook?, filterOwnEcho: Boolean): UdpLink {
        var socket: DatagramSocket? = null
        try {
            val target: InetSocketAddress
            when (mode) {
                is UdpMode.Unicast -> {
                    target = InetSocketAddress(InetAddress.getByName(mode.host), mode.port)
                    socket = DatagramSocket(null)
                    socket.bind(InetSocketAddress(mode.localPort))
                }
                is UdpMode.Broadcast -> {
                    target = InetSocketAddress(InetAddress.getByName("255.255.255.255"), mode.port)
                    socket = DatagramSocket(null)
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(InetSocketAddress(mode.port))
                }
                is UdpMode.Multicast -> {
                    val group = InetAddress.getByName(mode.group)
                    if (!group.isMulticastAddress) throw IOException("${mode.group} is no multicast address")
                    target = InetSocketAddress(group, mode.port)
                    val multicast = MulticastSocket(null)
                    socket = multicast
                    multicast.reuseAddress = true
                    multicast.bind(InetSocketAddress(mode.port))
                    // The interface-less join picks the system default, like joinGroup(InetAddress).
                    multicast.joinGroup(InetSocketAddress(group, 0), null)
                }
            }
            return UdpLink(socket, target, filterOwnEcho, heldLock)
        } catch (e: Throwable) {
            socket?.closeQuietly()
            throw e
        }
    }

    override fun toString(): String = "UdpTransport($mode)"

    internal companion object {
        /** Largest UDP payload over IPv4. */
        const val MAX_DATAGRAM_BYTES: Int = 65_507
    }
}

private class UdpLink(
    private val socket: DatagramSocket,
    private val target: InetSocketAddress,
    private val filterOwnEcho: Boolean,
    private val lock: MulticastLockHook?,
) : BlockingLink() {

    // 65535 also covers IPv6, whose payload may be slightly larger than IPv4's.
    private val packet = DatagramPacket(ByteArray(65_535), 65_535)
    private val echoFilter = if (filterOwnEcho) OwnEchoFilter(socket.localPort) else null

    override fun readLines(): List<String> {
        // receive() shrinks the length to the last datagram; reset it, or the next one is truncated.
        packet.length = packet.data.size
        socket.receive(packet)
        if (echoFilter?.isOwnEcho(packet.address, packet.port) == true) return emptyList()
        return splitDatagram(packet.data, packet.length)
    }

    override suspend fun send(line: String) {
        val bytes = encodeLine(line)
        if (bytes.size > UdpTransport.MAX_DATAGRAM_BYTES) {
            throw DatagramTooLargeException(bytes.size, UdpTransport.MAX_DATAGRAM_BYTES)
        }
        blockingSend { socket.send(DatagramPacket(bytes, bytes.size, target)) }
    }

    override fun closeResources() {
        // Closing a MulticastSocket also leaves its group.
        socket.closeQuietly()
        lock?.release()
    }
}

/**
 * Recognises datagrams this socket sent itself and got back through broadcast or multicast: sent
 * from our port by one of our addresses. Other peers usually use the same port, so the address
 * decides. The address list is looked up again when an unknown address uses our port, at most every
 * 10 s, because the device may have got a new address since.
 */
internal class OwnEchoFilter(
    private val localPort: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val lookup: () -> Set<InetAddress> = ::findLocalAddresses,
) {
    private var localAddresses: Set<InetAddress> = lookup()
    private var lookedUpAtNanos: Long = nanoTime()

    fun isOwnEcho(address: InetAddress?, port: Int): Boolean {
        if (port != localPort || address == null) return false
        if (address in localAddresses) return true
        val now = nanoTime()
        if (now - lookedUpAtNanos < REFRESH_NANOS) return false
        localAddresses = lookup()
        lookedUpAtNanos = now
        return address in localAddresses
    }

    private companion object {
        const val REFRESH_NANOS: Long = 10_000_000_000L
    }
}

internal fun findLocalAddresses(): Set<InetAddress> = try {
    val addresses = HashSet<InetAddress>()
    NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
        addresses.addAll(networkInterface.inetAddresses.toList())
    }
    addresses
} catch (_: Exception) {
    // Some Android versions fail here without a network; then no echo is filtered.
    emptySet()
}
