package de.visentra.sedapexpress.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.ISO_8859_1

class TcpTransportTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val server = ServerSocket(0, 50, loopback)
    private val cleanup = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        cleanup.forEach { runCatching { it.close() } }
        server.close()
    }

    private fun transport(
        port: Int = server.localPort,
        timeout: Int = 2_000,
        maxLineBytes: Int = 1 shl 20,
        configurator: SocketConfigurator? = null,
    ) = TcpTransport("127.0.0.1", port, timeout, maxLineBytes, configurator)

    /** Opens a link and accepts its server side. */
    private suspend fun openPair(transport: TcpTransport = transport()): Pair<SedapLink, Socket> {
        val link = transport.open().also { cleanup.add(it) }
        val peer = withContext(Dispatchers.IO) { server.accept() }.also { cleanup.add(it) }
        return link to peer
    }

    private suspend fun Socket.write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        getOutputStream().write(bytes)
        getOutputStream().flush()
    }

    private suspend fun Socket.readExactly(count: Int): ByteArray = withContext(Dispatchers.IO) {
        val result = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = getInputStream().read(result, read, count - read)
            if (n < 0) break
            read += n
        }
        result.copyOf(read)
    }

    @Test
    fun `Connects and receives lines, also split over several writes`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair()
            val received = async { link.lines().toList() }
            peer.write("HEARTBEAT;01;\nCONT".toByteArray(ISO_8859_1))
            delay(50)
            peer.write("ACT;02\n".toByteArray(ISO_8859_1))
            peer.close()
            assertEquals(listOf("HEARTBEAT;01;", "CONTACT;02"), received.await())
        }
    }

    @Test
    fun `Umlauts arrive intact, decoded as ISO-8859-1`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair()
            peer.write("TEXT;01;;Müller\n".toByteArray(ISO_8859_1))
            assertEquals("TEXT;01;;Müller", link.lines().first())
        }
    }

    @Test
    fun `Sends the line with LF as ISO-8859-1`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair()
            link.send("TEXT;01;;Grüße")
            val expected = "TEXT;01;;Grüße\n".toByteArray(ISO_8859_1)
            assertArrayEquals(expected, peer.readExactly(expected.size))
        }
    }

    @Test
    fun `EOF from the server completes the flow`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair()
            peer.write("A\nB".toByteArray(ISO_8859_1))
            peer.shutdownOutput()
            // An unterminated rest at EOF is no complete line and is dropped.
            assertEquals(listOf("A"), link.lines().toList())
        }
    }

    @Test
    fun `Closing the link ends the flow normally`() = runBlocking {
        withTimeout(5_000) {
            val (link, _) = openPair()
            val received = async { link.lines().toList() }
            delay(100)
            link.close()
            assertEquals(emptyList<String>(), received.await())
        }
    }

    @Test
    fun `Cancelling the collector closes the socket`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair()
            val collector = launch { link.lines().collect() }
            delay(100)
            collector.cancel()
            collector.join()
            assertEquals(-1, withContext(Dispatchers.IO) { peer.getInputStream().read() })
        }
    }

    @Test
    fun `An over-long line fails the flow with LineTooLongException`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair(transport(maxLineBytes = 8))
            peer.write("OK\n123456789".toByteArray(ISO_8859_1))
            val received = ArrayList<String>()
            try {
                link.lines().collect { received.add(it) }
                fail("expected LineTooLongException")
            } catch (e: LineTooLongException) {
                assertEquals(8, e.maxLineBytes)
            }
            assertEquals(listOf("OK"), received)
        }
    }

    @Test
    fun `lines() has a single collector`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair()
            val first = launch(start = CoroutineStart.UNDISPATCHED) { link.lines().collect() }
            delay(50)
            try {
                link.lines().collect()
                fail("expected IllegalStateException")
            } catch (_: IllegalStateException) {
            }
            peer.close()
            first.join()
        }
    }

    @Test
    fun `Sending after the server closed throws IOException`() = runBlocking {
        withTimeout(5_000) {
            val (link, peer) = openPair()
            peer.close()
            delay(100)
            // The first write may still succeed and only provoke the RST; a later one fails.
            try {
                repeat(50) {
                    link.send("HEARTBEAT;01;")
                    delay(20)
                }
                fail("expected IOException")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun `Sending on a closed link throws IOException`() = runBlocking {
        withTimeout(5_000) {
            val (link, _) = openPair()
            link.close()
            try {
                link.send("HEARTBEAT;01;")
                fail("expected IOException")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun `A refused connection throws IOException`() = runBlocking {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        try {
            transport(port = port).open()
            fail("expected ConnectException")
        } catch (_: ConnectException) {
        }
    }

    @Test
    fun `Sockets get keepalive and no-delay before the configurator runs`() = runBlocking {
        withTimeout(5_000) {
            var seen: Pair<Boolean, Boolean>? = null
            openPair(transport(configurator = { socket -> seen = socket.keepAlive to socket.tcpNoDelay }))
            assertEquals(true to true, seen)
        }
    }

    /**
     * A port whose SYNs go unanswered, so a connect hangs until its timeout. Loopback can't do this
     * on macOS (a full backlog answers with RST), so this uses TEST-NET-1 (RFC 5737), which is never
     * routed. Returns null without a network, where the connect fails at once instead.
     */
    private fun blackHole(): InetSocketAddress? {
        val address = InetSocketAddress("192.0.2.1", 50_000)
        return try {
            Socket().use { it.connect(address, 200) }
            null
        } catch (_: SocketTimeoutException) {
            address
        } catch (_: IOException) {
            null
        }
    }

    @Test
    fun `The connect gives up after the timeout`() = runBlocking {
        val hole = blackHole()
        assumeTrue("no unanswered address reachable", hole != null)
        val port = hole?.port ?: return@runBlocking
        val started = System.nanoTime()
        try {
            TcpTransport(hole.hostString, port, connectTimeoutMillis = 300).open()
            fail("expected SocketTimeoutException")
        } catch (_: SocketTimeoutException) {
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMillis ms", elapsedMillis in 250..3_000)
    }

    @Test
    fun `Cancelling a hanging connect returns at once`() = runBlocking {
        val hole = blackHole()
        assumeTrue("no unanswered address reachable", hole != null)
        val port = hole?.port ?: return@runBlocking
        val attempt = launch(Dispatchers.Default) { TcpTransport(hole.hostString, port).open() }
        delay(200)
        val started = System.nanoTime()
        attempt.cancel()
        withTimeout(2_000) { attempt.join() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMillis ms", elapsedMillis < 1_000)
        assertTrue(attempt.isCancelled)
    }
}
