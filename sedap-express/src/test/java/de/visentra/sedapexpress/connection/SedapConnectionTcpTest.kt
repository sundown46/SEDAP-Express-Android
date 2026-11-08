package de.visentra.sedapexpress.connection

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.visentra.sedapexpress.protocol.Identity
import de.visentra.sedapexpress.transport.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.nio.charset.StandardCharsets.ISO_8859_1

/**
 * The connection against real server sockets on loopback, in real time. The tests with a 5 s limit are
 * ported from SEDAP-iOXTests/TCPClientTests.swift (L1.6).
 */
class SedapConnectionTcpTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanup = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        scope.cancel()
        cleanup.forEach { runCatching { it.close() } }
    }

    private fun server(port: Int = 0): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        soTimeout = TIMEOUT.toInt()
        bind(InetSocketAddress(loopback, port))
        cleanup.add(this)
    }

    private suspend fun ServerSocket.acceptPeer(): Socket = withContext(Dispatchers.IO) { accept() }.also { cleanup.add(it) }

    private suspend fun Socket.writeLine(line: String) = withContext(Dispatchers.IO) {
        getOutputStream().write("$line\n".toByteArray(ISO_8859_1))
        getOutputStream().flush()
    }

    /** Reads the first line; whatever came after it is lost, so only call it once per socket. */
    private suspend fun Socket.readFirstLine(): String? = withContext(Dispatchers.IO) {
        getInputStream().bufferedReader(ISO_8859_1).readLine()
    }

    /** Reads until the other side closes; a read timeout fails the test. */
    private suspend fun Socket.readToEnd(): String = withContext(Dispatchers.IO) {
        soTimeout = TIMEOUT.toInt()
        String(getInputStream().readBytes(), ISO_8859_1)
    }

    @Test
    fun `Server closes, the connection waits and comes back to the restarted server`() = runBlocking {
        withTimeout(TIMEOUT) {
            var server = server()
            val port = server.localPort
            val connection = SedapConnection(
                scope,
                { Identity("MESE", Classification.Unclas) },
                { null },
                policy = ConnectionPolicy(backoffBaseMillis = 50, backoffMaxMillis = 200, heartbeatIntervalMillis = 100),
            )
            val lines = Channel<String>(Channel.UNLIMITED)
            connection.subscribe { lines.trySend(it) }

            connection.connect(TcpTransport("127.0.0.1", port, connectTimeoutMillis = 2_000))
            var peer = server.acceptPeer()
            connection.state.first { it == ConnectionState.Connected }
            peer.writeLine("CONTACT;01;0195238E25AD;FE2A;U;;;C1")
            assertEquals("CONTACT;01;0195238E25AD;FE2A;U;;;C1", lines.receive())
            assertEquals("HEARTBEAT", peer.readFirstLine()?.substringBefore(';'))

            // The server goes away: EOF, then refused connects with growing delays.
            peer.close()
            server.close()
            connection.state.first { it is ConnectionState.Disconnected && it.attempt >= 3 }

            server = server(port)
            peer = server.acceptPeer()
            connection.state.first { it == ConnectionState.Connected }
            peer.writeLine("CONTACT;02;0195238E25AD;FE2A;U;;;C1")
            assertEquals("CONTACT;02;0195238E25AD;FE2A;U;;;C1", lines.receive())
            assertEquals(1L, connection.generation.value)

            connection.disconnect()
            connection.state.first { it == ConnectionState.ManuallyDisconnected }
            // Reaches the end only because the client closed its socket.
            assertTrue(peer.readToEnd().lines().filter { it.isNotEmpty() }.all { it.startsWith("HEARTBEAT;") })
        }
    }

    // TCPClientTests.swift

    /** A connection with short delays whose plaintext lines land in [lines]. */
    private inner class Client {
        val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
        val connection = SedapConnection(
            scope,
            { null },
            { null },
            policy = ConnectionPolicy(backoffBaseMillis = 50, backoffMaxMillis = 200),
        ).also { it.subscribe { line -> lines.add(line) } }

        /** Connects and accepts the server side of exactly this new link. */
        suspend fun connect(server: ServerSocket): Socket {
            connection.connect(TcpTransport("127.0.0.1", server.localPort, connectTimeoutMillis = 2_000))
            val peer = server.acceptPeer()
            connection.state.first { it == ConnectionState.Connected }
            return peer
        }

        suspend fun receives(line: String, from: Socket): Boolean {
            from.writeLine(line)
            while (line !in snapshot()) delay(10)
            return true
        }

        fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    }

    @Test
    fun `Switching servers closes the old connection`() = runBlocking {
        withTimeout(PORTED_TIMEOUT) {
            val client = Client()
            val a = client.connect(server())
            client.connect(server())
            // Returns only once the client closed the old socket.
            a.readToEnd()
            assertEquals(ConnectionState.Connected, client.connection.state.value)
        }
    }

    @Test
    fun `After a switch the old server can't feed the new track picture`() = runBlocking {
        withTimeout(PORTED_TIMEOUT) {
            val client = Client()
            val a = client.connect(server())
            val b = client.connect(server())
            // The client has closed this socket already, so the write may fail or go nowhere.
            runCatching { a.writeLine("VOM-ALTEN-SERVER") }
            delay(300)
            assertTrue(client.receives("B1", from = b))
            assertTrue("VOM-ALTEN-SERVER" !in client.snapshot())
        }
    }

    @Test
    fun `The old server going away doesn't disturb the new connection`() = runBlocking {
        withTimeout(PORTED_TIMEOUT) {
            val client = Client()
            val a = client.connect(server())
            val b = client.connect(server())
            a.close()
            delay(500)
            assertEquals(ConnectionState.Connected, client.connection.state.value)
            assertTrue(client.receives("B1", from = b))
        }
    }

    @Test
    fun `Connecting to the same server again leaves one connection`() = runBlocking {
        withTimeout(PORTED_TIMEOUT) {
            val client = Client()
            val server = server()
            val first = client.connect(server)
            val second = client.connect(server)
            first.readToEnd()
            assertTrue(client.receives("NOCH-DA", from = second))
            // No third link was opened behind our back.
            server.soTimeout = 300
            try {
                withContext(Dispatchers.IO) { server.accept() }.also { cleanup.add(it) }
                fail("unexpected third connection")
            } catch (expected: SocketTimeoutException) {
            }
        }
    }

    /** Lines already read from the old socket when the user switches must not reach a listener afterwards. */
    @Test
    fun `Lines in flight at the moment of a switch are dropped`() = runBlocking {
        withTimeout(PORTED_TIMEOUT) {
            val client = Client()
            // A slow listener, so read lines pile up behind it like the blocked main thread on iOS.
            client.connection.subscribe { if (it.startsWith("FLOOD")) Thread.sleep(1) }
            val a = client.connect(server())
            val bServer = server()
            launch(Dispatchers.IO) { runCatching { a.writeLine((0 until FLOOD_LINES).joinToString("\n") { "FLOOD$it" }) } }
            while (client.snapshot().none { it.startsWith("FLOOD") }) delay(5)

            client.connection.connect(TcpTransport("127.0.0.1", bServer.localPort, connectTimeoutMillis = 2_000))
            val countAtSwitch = client.snapshot().size
            val b = bServer.acceptPeer()
            client.connection.state.first { it == ConnectionState.Connected }
            assertTrue(client.receives("B1", from = b))
            delay(300)

            val after = client.snapshot().drop(countAtSwitch)
            assertTrue("$countAtSwitch FLOOD lines before the switch", countAtSwitch in 1 until FLOOD_LINES)
            assertTrue(after.toString(), after.none { it.startsWith("FLOOD") })
        }
    }

    @Test
    fun `A manual disconnect stays disconnected until connect() is called`() = runBlocking {
        withTimeout(PORTED_TIMEOUT) {
            val client = Client()
            val server = server()
            val peer = client.connect(server)

            client.connection.disconnect()
            client.connection.state.first { it == ConnectionState.ManuallyDisconnected }
            peer.readToEnd()
            // Far longer than the first reconnect delay of 50 ms.
            server.soTimeout = 500
            try {
                withContext(Dispatchers.IO) { server.accept() }.also { cleanup.add(it) }
                fail("reconnected after a manual disconnect")
            } catch (expected: SocketTimeoutException) {
            }
            assertEquals(ConnectionState.ManuallyDisconnected, client.connection.state.value)

            server.soTimeout = TIMEOUT.toInt()
            val again = client.connect(server)
            assertTrue(client.receives("WIEDER-DA", from = again))
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val PORTED_TIMEOUT = 5_000L
        const val FLOOD_LINES = 1000
    }
}
