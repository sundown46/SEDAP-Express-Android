package de.visentra.sedapexpress.interop

import de.bundeswehr.uniity.sedapexpress.messages.CONTACT
import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.DataEncoding
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import de.bundeswehr.uniity.sedapexpress.messages.TIMESYNC
import de.visentra.sedapexpress.connection.ConnectionEvent
import de.visentra.sedapexpress.connection.ConnectionState
import de.visentra.sedapexpress.connection.SedapConnection
import de.visentra.sedapexpress.connection.SendResult
import de.visentra.sedapexpress.protocol.Direction
import de.visentra.sedapexpress.protocol.Identity
import de.visentra.sedapexpress.transport.TcpTransport
import de.visentra.sedapexpress.transport.UdpMode
import de.visentra.sedapexpress.transport.UdpTransport
import de.visentra.sedapexpress.wire.SedapParser
import de.visentra.sedapexpress.wire.TextCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStreamWriter
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun fail(message: String): Nothing = throw AssertionError(message)

/**
 * The library against the unmodified Java reference (D-30), which runs as its own process built by
 * :interop-jvm (see ReferencePeer.java there). TCP with upstream's server, UDP with a socket that
 * codes like upstream's UDP client, both on loopback, with and without MAC.
 *
 * Skipped when Gradle did not pass the class path of the reference (e.g. when run from the IDE).
 */
class ReferenceInteropProcessTest {

    private val classpath: String? = System.getProperty("sedap.interop.classpath")
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanup = ArrayList<AutoCloseable>()

    @Before
    fun requireReference() {
        assumeTrue("sedap.interop.classpath not set, run through Gradle", !classpath.isNullOrEmpty())
    }

    @After
    fun tearDown() {
        scope.cancel()
        cleanup.asReversed().forEach { runCatching { it.close() } }
    }

    // Our messages to the reference

    @Test
    fun `TCP - OWNUNIT, TEXT with umlauts and HEARTBEAT reach the reference unchanged`() = runBlocking {
        val session = tcpSession(password = null)
        session.sendOursToReference(signed = false)
    }

    @Test
    fun `TCP with MAC - the reference sees our MAC as valid, but its isMessageAuthentic says no`() = runBlocking {
        val session = tcpSession(password = AES_LENGTH_PASSWORD)
        session.sendOursToReference(signed = true)
    }

    @Test
    fun `UDP - OWNUNIT, TEXT with umlauts and HEARTBEAT reach the reference unchanged`() = runBlocking {
        val session = udpSession(password = null)
        session.sendOursToReference(signed = false)
    }

    @Test
    fun `UDP with MAC - the reference sees our MAC as valid, but its isMessageAuthentic says no`() = runBlocking {
        val session = udpSession(password = AES_LENGTH_PASSWORD)
        session.sendOursToReference(signed = true)
    }

    /**
     * L0 finding 1, second part: with a password that is no AES key length (16, 24 or 32 bytes),
     * upstream's isMessageAuthentic does not even return false, its CMAC check throws.
     */
    @Test
    fun `TCP with MAC - isMessageAuthentic of the reference throws for other password lengths`() = runBlocking {
        val session = tcpSession(password = OTHER_PASSWORD)
        assertEquals(SendResult.SENT, session.connection.send(heartbeat()))
        val received = session.peer.awaitReceived("HEARTBEAT")
        assertEquals(session.lastSent(), received.line)
        assertEquals("error:IllegalArgumentException", received.authentic)
        assertEquals("true", received.macOverReserialization)
    }

    // The reference's messages to us

    @Test
    fun `TCP - CONTACT, TEXT and TIMESYNC of the reference reach us`() = runBlocking {
        val session = tcpSession(password = null)
        session.sendReferenceToUs(password = null)
    }

    @Test
    fun `TCP with MAC - signed lines of the reference pass our check`() = runBlocking {
        val session = tcpSession(password = AES_LENGTH_PASSWORD)
        session.sendReferenceToUs(password = AES_LENGTH_PASSWORD)
    }

    @Test
    fun `TCP with MAC - a line the reference signed with another password is rejected`() = runBlocking {
        val session = tcpSession(password = AES_LENGTH_PASSWORD)
        val sent = session.peer.send(CONTACT_LINE, "another-password")
        val event = session.awaitEvent { it is ConnectionEvent.MacRejected } as ConnectionEvent.MacRejected
        assertEquals(sent, event.line)
        // The line after it passes, so the rejected one was really dropped and not just late.
        val next = session.peer.send(CONTACT_LINE, AES_LENGTH_PASSWORD)
        assertEquals(next, session.awaitLine())
    }

    @Test
    fun `UDP - CONTACT, TEXT and TIMESYNC of the reference reach us`() = runBlocking {
        val session = udpSession(password = null)
        session.sendReferenceToUs(password = null)
    }

    @Test
    fun `UDP with MAC - signed lines of the reference pass our check`() = runBlocking {
        val session = udpSession(password = AES_LENGTH_PASSWORD)
        session.sendReferenceToUs(password = AES_LENGTH_PASSWORD)
    }

    @Test
    fun `TCP - a CONTACT of the reference that asks for it gets our ACKNOWLEDGE`() = runBlocking {
        val session = tcpSession(password = null)
        val sent = session.peer.send(CONTACT_LINE.replace(";U;;;C1;", ";U;TRUE;;C1;"))
        assertEquals(sent, session.awaitLine())
        val ack = session.peer.awaitReceived("ACKNOWLEDGE")
        assertEquals("CONTACT:1A", ack.detail)
        assertTrue(ack.line, ack.line.startsWith("ACKNOWLEDGE;00;"))
        assertTrue(ack.line, ack.line.contains(";$OWN_SENDER;U;;;$PEER_SENDER;CONTACT;1A"))
    }

    // Scenarios

    private suspend fun Session.sendOursToReference(signed: Boolean) {
        val expectedAuthentic = if (signed) "false" else "-"
        val expectedMac = if (signed) "true" else "-"
        for ((message, expectedText) in ourMessages()) {
            assertEquals(SendResult.SENT, connection.send(message))
            val line = lastSent()
            val received = peer.awaitReceived(message.messageType.name)
            // The reference serializes the parsed message back to exactly our line.
            assertEquals(line, received.line)
            assertEquals(line, expectedAuthentic, received.authentic)
            assertEquals(line, expectedMac, received.macOverReserialization)
            if (expectedText != null) assertEquals(line, expectedText, received.detail)
        }
    }

    private fun Session.sendReferenceToUs(password: String?) {
        val contact = peer.send(CONTACT_LINE, password)
        assertEquals(contact, awaitLine())
        val parsedContact = SedapParser.parse(contact)?.message as CONTACT
        assertEquals(53.6, parsedContact.latitude, 0.0)
        assertEquals("Kutter", parsedContact.name)

        for (text in listOf(plainText("Grüße aus Kiel"), base64Text("Grüße aus Kiel"))) {
            val sent = peer.send(text.toString(), password)
            assertEquals(sent, awaitLine())
            assertEquals("Grüße aus Kiel", (SedapParser.parse(sent)?.message as TEXT).textContent)
        }

        val timeSync = TIMESYNC(3.toByte(), TIME, PEER_SENDER, Classification.Unclas, null, null, TIME + 5)
        val sentSync = peer.send(timeSync.toString(), password)
        assertEquals(sentSync, awaitLine())
        assertEquals(TIME + 5, (SedapParser.parse(sentSync)?.message as TIMESYNC).timestamp)
        assertNull(events.poll())
    }

    /** Each message with the text the reference must decode, if it is a TEXT. */
    private fun ourMessages(): List<Pair<SEDAPExpressMessage, String?>> = listOf(
        OWNUNIT(
            null, null, OWN_SENDER, Classification.Unclas, null, null,
            53.5432, 8.1234, 12.5, 4.2, 270.0, null, null, null, "Lotse", "SFGPUCI--------".toCharArray(),
        ) to null,
        plainText("Grüße an Bord", OWN_SENDER) to "Grüße an Bord",
        base64Text("Grüße an Bord", OWN_SENDER) to "Grüße an Bord",
        // Not ISO-8859-1, so we send UTF-8 bytes (PROTOCOL §5). Upstream decodes Base64 as ISO-8859-1 and
        // shows them as such; the line itself still arrives unchanged. Same on iOS.
        base64Text("Preis 5 €", OWN_SENDER) to String("Preis 5 €".toByteArray(UTF_8), ISO_8859_1),
        heartbeat() to null,
    )

    private fun heartbeat() = HEARTBEAT(null, null, OWN_SENDER, Classification.Unclas, null, null)

    private fun plainText(text: String, sender: String = PEER_SENDER) = TEXT(
        5.toByte(), TIME, sender, Classification.Unclas, null, null, null, TEXT.TextType.Chat, DataEncoding.NONE, text, null,
    )

    private fun base64Text(text: String, sender: String = PEER_SENDER) = TEXT(
        6.toByte(), TIME, sender, Classification.Unclas, null, null, null, TEXT.TextType.Chat, DataEncoding.BASE64,
        TextCodec.forBase64Field(text), null,
    )

    // Setup

    private suspend fun tcpSession(password: String?): Session {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val peer = startPeer(listOf("tcp-server", port.toString()), password)
        peer.await("READY")
        val session = Session(peer, password)
        session.connection.connect(TcpTransport("127.0.0.1", port, connectTimeoutMillis = 2_000))
        peer.await("LOG") { it.startsWith("Added new client") }
        session.awaitConnected()
        return session
    }

    private suspend fun udpSession(password: String?): Session {
        val ourPort = DatagramSocket(0, loopback).use { it.localPort }
        val peer = startPeer(listOf("udp", "0", ourPort.toString()), password)
        val peerPort = peer.await("READY").toInt()
        val session = Session(peer, password)
        session.connection.connect(UdpTransport(UdpMode.Unicast("127.0.0.1", peerPort, ourPort)))
        session.awaitConnected()
        return session
    }

    private fun startPeer(args: List<String>, password: String?): Peer {
        val macArgs = if (password == null) emptyList() else listOf("--mac", password)
        return Peer(checkNotNull(classpath), args + macArgs).also { cleanup.add(it) }
    }

    /** Our side: one SedapConnection that records what it sends and receives. */
    private inner class Session(val peer: Peer, password: String?) {
        private val sent = LinkedBlockingQueue<String>()
        private val lines = LinkedBlockingQueue<String>()
        val events = LinkedBlockingQueue<ConnectionEvent>()

        val connection = SedapConnection(
            scope,
            { Identity(OWN_SENDER, Classification.Unclas) },
            { password },
            log = { direction, line -> if (direction == Direction.SENT) sent.put(line) },
        )

        init {
            connection.subscribe { lines.put(it) }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.events.collect { if (it !is ConnectionEvent.ReconnectScheduled) events.put(it) }
            }
        }

        suspend fun awaitConnected() {
            withTimeout(TIMEOUT_MILLIS) { connection.state.first { it == ConnectionState.Connected } }
        }

        /** The line our last send put on the wire. */
        fun lastSent(): String = sent.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: fail("nothing sent")

        fun awaitLine(): String = lines.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            ?: fail("no line from the reference; events: $events\n${peer.diagnostics()}")

        fun awaitEvent(predicate: (ConnectionEvent) -> Boolean): ConnectionEvent {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
            while (true) {
                val left = deadline - System.nanoTime()
                val event = events.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: fail("no matching event")
                if (predicate(event)) return event
            }
        }
    }

    /** One RECV line of the peer. */
    private class Received(
        val authentic: String,
        val macOverReserialization: String,
        val detail: String,
        val line: String,
    )

    /** The reference in its own JVM, see ReferencePeer.java for the line protocol. */
    private class Peer(classpath: String, args: List<String>) : AutoCloseable {
        private val process: Process
        private val output = LinkedBlockingQueue<List<String>>()
        private val pending = ArrayList<List<String>>()
        private val stderr = StringBuffer()
        private val stdin: OutputStreamWriter

        init {
            val java = File(System.getProperty("java.home"), "bin/java").path
            process = ProcessBuilder(listOf(java, "-cp", classpath, MAIN_CLASS) + args).start()
            stdin = OutputStreamWriter(process.outputStream, UTF_8)
            thread(isDaemon = true) {
                process.inputStream.bufferedReader(UTF_8).forEachLine { output.put(it.split('\t')) }
            }
            thread(isDaemon = true) {
                process.errorStream.bufferedReader(UTF_8).forEachLine { stderr.appendLine(it) }
            }
        }

        /** Lets the reference parse [line], sign it with [password] if given, and send it. Returns what it sent. */
        fun send(line: String, password: String? = null): String {
            stdin.write("SEND\t${password.orEmpty()}\t$line\n")
            stdin.flush()
            return await("SENT")
        }

        fun awaitReceived(type: String): Received {
            val fields = awaitFields("RECV") { it[1] == type }
            return Received(authentic = fields[2], macOverReserialization = fields[3], detail = fields[4], line = fields[5])
        }

        /** Waits for the next [event] whose text matches; returns the text. Other events stay for later. */
        fun await(event: String, matches: (String) -> Boolean = { true }): String =
            awaitFields(event) { matches(it[1]) }[1]

        private fun awaitFields(event: String, matches: (List<String>) -> Boolean): List<String> {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
            while (true) {
                pending.firstOrNull { it[0] == "ERROR" }?.let { fail("reference reported: $it\n${diagnostics()}") }
                pending.firstOrNull { it[0] == event && matches(it) }?.let {
                    pending.remove(it)
                    return it
                }
                val left = deadline - System.nanoTime()
                val next = if (left > 0) output.poll(left, TimeUnit.NANOSECONDS) else null
                next ?: fail("reference did not report $event\n${diagnostics()}")
                pending.add(next)
            }
        }

        fun diagnostics(): String = "unclaimed output: $pending\nstderr:\n$stderr"

        override fun close() {
            runCatching {
                stdin.write("QUIT\n")
                stdin.flush()
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private companion object {
        const val MAIN_CLASS = "de.visentra.sedapexpress.interop.ReferencePeer"
        const val TIMEOUT_MILLIS = 5_000L
        const val OWN_SENDER = "A1B2"
        const val PEER_SENDER = "C3D4"
        const val TIME = 0x0195238E25ADL

        /** 16 bytes, so upstream's CMAC check, which needs an AES key, does not throw. */
        const val AES_LENGTH_PASSWORD = "interop-secret16"
        const val OTHER_PASSWORD = "interop-secret"

        const val CONTACT_LINE = "CONTACT;1A;0195238E25AD;C3D4;U;;;C1;FALSE;53.6;8.2;0;;;;12.5;90;;;;;;;Kutter;S;SFSPCLFF-------"
    }
}
