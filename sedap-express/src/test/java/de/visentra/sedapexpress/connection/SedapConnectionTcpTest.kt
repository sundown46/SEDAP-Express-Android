package de.visentra.sedapexpress.connection

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.visentra.sedapexpress.protocol.Identity
import de.visentra.sedapexpress.transport.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.ISO_8859_1

/** The connection against a real server socket on loopback, in real time. */
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

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
