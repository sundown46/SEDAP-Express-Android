package de.visentra.sedapexpress.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.charset.StandardCharsets.ISO_8859_1

class UdpTransportTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val peer = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 5_000 }
    private val cleanup = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        cleanup.forEach { runCatching { it.close() } }
        peer.close()
    }

    private suspend fun openUnicast(): SedapLink =
        UdpTransport(UdpMode.Unicast("127.0.0.1", peer.localPort, 0)).open().also { cleanup.add(it) }

    private suspend fun receiveAtPeer(): DatagramPacket = withContext(Dispatchers.IO) {
        DatagramPacket(ByteArray(70_000), 70_000).also { peer.receive(it) }
    }

    private suspend fun sendFromPeer(text: String, to: SocketAddress) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(ISO_8859_1)
        peer.send(DatagramPacket(bytes, bytes.size, to))
    }

    private fun DatagramPacket.text() = String(data, 0, length, ISO_8859_1)

    @Test
    fun `Unicast sends one datagram per line and receives several lines per datagram`() = runBlocking {
        withTimeout(5_000) {
            val link = openUnicast()
            link.send("HEARTBEAT;01;")
            val packet = receiveAtPeer()
            assertEquals("HEARTBEAT;01;\n", packet.text())

            val received = async { link.lines().take(4).toList() }
            delay(100)
            // A rest without LF at the end of a datagram is a whole line.
            sendFromPeer("CONTACT;01\nTEXT;02;;Müller\nHEARTBEAT;03", packet.socketAddress)
            sendFromPeer("STATUS;04\n", packet.socketAddress)
            assertEquals(listOf("CONTACT;01", "TEXT;02;;Müller", "HEARTBEAT;03", "STATUS;04"), received.await())
        }
    }

    @Test
    fun `A UDP link is not connection-oriented and ends only when closed`() = runBlocking {
        withTimeout(5_000) {
            val transport = UdpTransport(UdpMode.Unicast("127.0.0.1", peer.localPort, 0))
            assertFalse(transport.isConnectionOriented)
            val link = transport.open()
            val received = async { link.lines().toList() }
            delay(100)
            link.close()
            assertEquals(emptyList<String>(), received.await())
        }
    }

    @Test
    fun `A line that does not fit in a datagram is refused before sending`() = runBlocking {
        withTimeout(5_000) {
            val link = openUnicast()
            link.send("X".repeat(65_506)) // 65 507 bytes with LF, the IPv4 maximum
            assertEquals(65_507, receiveAtPeer().length)
            try {
                link.send("X".repeat(65_507))
                fail("expected DatagramTooLargeException")
            } catch (e: DatagramTooLargeException) {
                assertEquals(65_508, e.size)
            }
            // The link stays usable.
            link.send("HEARTBEAT;01;")
            assertEquals("HEARTBEAT;01;\n", receiveAtPeer().text())
        }
    }

    @Test
    fun `Sending on a closed UDP link throws IOException`() = runBlocking {
        val link = openUnicast()
        link.close()
        try {
            link.send("HEARTBEAT;01;")
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    private class CountingLock : MulticastLockHook {
        var acquired = 0
        var released = 0
        override fun acquire() {
            acquired++
        }
        override fun release() {
            released++
        }
    }

    @Test
    fun `Unicast does not take the multicast lock`() = runBlocking {
        val lock = CountingLock()
        UdpTransport(UdpMode.Unicast("127.0.0.1", peer.localPort, 0), lock).open().close()
        assertEquals(0, lock.acquired)
    }

    @Test
    fun `Broadcast opens, holds the lock while open and releases it once`() = runBlocking {
        val lock = CountingLock()
        val port = DatagramSocket(0).use { it.localPort }
        val link = UdpTransport(UdpMode.Broadcast(port), lock).open()
        assertEquals(1 to 0, lock.acquired to lock.released)
        link.close()
        link.close()
        assertEquals(1 to 1, lock.acquired to lock.released)
    }

    @Test
    fun `Multicast opens on the ICD group and releases the lock`() = runBlocking {
        val lock = CountingLock()
        val port = DatagramSocket(0).use { it.localPort }
        val link = UdpTransport(UdpMode.Multicast("228.2.19.80", port), lock).open()
        link.close()
        assertEquals(1 to 1, lock.acquired to lock.released)
    }

    @Test
    fun `A failed open releases the lock`() = runBlocking {
        val lock = CountingLock()
        try {
            UdpTransport(UdpMode.Multicast("10.0.0.1", 50_000), lock).open()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(1 to 1, lock.acquired to lock.released)
    }

    @Test
    fun `The echo filter drops only our own address on our own port`() {
        val own = InetAddress.getByName("192.0.2.10")
        val other = InetAddress.getByName("192.0.2.20")
        val filter = OwnEchoFilter(50_000, nanoTime = { 0 }, lookup = { setOf(own) })
        assertTrue(filter.isOwnEcho(own, 50_000))
        assertFalse(filter.isOwnEcho(other, 50_000))
        assertFalse(filter.isOwnEcho(own, 50_001))
        assertFalse(filter.isOwnEcho(null, 50_000))
    }

    @Test
    fun `The echo filter looks up a new own address at most every 10 s`() {
        val first = InetAddress.getByName("192.0.2.10")
        val second = InetAddress.getByName("192.0.2.11")
        var now = 0L
        var addresses = setOf(first)
        var lookups = 0
        val filter = OwnEchoFilter(50_000, nanoTime = { now }, lookup = { lookups++; addresses })
        addresses = setOf(first, second)
        now = 9_999_999_999L
        assertFalse(filter.isOwnEcho(second, 50_000))
        assertEquals(1, lookups)
        now = 10_000_000_000L
        assertTrue(filter.isOwnEcho(second, 50_000))
        assertEquals(2, lookups)
    }

    @Test
    fun `The local address lookup finds loopback`() {
        assertTrue(findLocalAddresses().contains(loopback))
    }
}
