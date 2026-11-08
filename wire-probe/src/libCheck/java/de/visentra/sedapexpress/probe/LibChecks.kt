package de.visentra.sedapexpress.probe

import android.util.Log
import de.bundeswehr.uniity.sedapexpress.messages.CONTACT
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.DataEncoding
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import de.bundeswehr.uniity.sedapexpress.utils.CompressUtils
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils
import de.visentra.sedapexpress.connection.ConnectionState
import de.visentra.sedapexpress.connection.SedapConnection
import de.visentra.sedapexpress.connection.SendResult
import de.visentra.sedapexpress.protocol.Identity
import de.visentra.sedapexpress.protocol.SedapClock
import de.visentra.sedapexpress.protocol.SendHandler
import de.visentra.sedapexpress.transport.TcpTransport
import de.visentra.sedapexpress.wire.Decompressor
import de.visentra.sedapexpress.wire.MessageAuthenticator
import de.visentra.sedapexpress.wire.SedapParser
import de.visentra.sedapexpress.wire.TextCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Calls the Kotlin layer of sedap-express the way app and plugin will: parser, send handler, MAC,
 * decompression and a whole SedapConnection over TcpTransport against a server socket on loopback
 * inside this app. Each check returns "ok <name>" or "FAIL <name>: <detail>".
 */
object LibChecks {

    private const val TAG = "SEDAPProbe"
    private const val PASSWORD = "probe-secret"
    private const val CONTACT_LINE = "CONTACT;1A;0195238E25AD;C3D4;U;;;C1;FALSE;53.6;8.2;0;;;;12.5;90;;;;;;;Kutter;S;SFSPCLFF-------"
    private const val TIMEOUT_MILLIS = 10_000L

    @JvmStatic
    fun run(): List<String> = listOf(
        check("parser", ::parser),
        check("sendHandler", ::sendHandler),
        check("authenticator", ::authenticator),
        check("decompressor", ::decompressor),
        check("connection", ::connection),
    )

    /** Runs all checks and writes them to logcat as section "lib", like the wire checks. */
    @JvmStatic
    fun runAndLog(): List<String> {
        val results = run()
        results.forEach { Log.i(TAG, "lib $it") }
        Log.i(TAG, "lib total=${results.size} fail=${results.count { it.startsWith("FAIL") }}")
        return results
    }

    private fun check(name: String, block: () -> Unit): String = try {
        block()
        "ok $name"
    } catch (t: Throwable) {
        "FAIL $name: $t"
    }

    private fun expect(condition: Boolean, detail: () -> String) {
        if (!condition) throw AssertionError(detail())
    }

    private fun parser() {
        val contact = SedapParser.parse(CONTACT_LINE)?.message as? CONTACT
        expect(contact?.latitude == 53.6 && contact.name == "Kutter") { "CONTACT: $contact" }
        expect(SedapParser.parse(CONTACT_LINE.replace(";53.6;", ";200;")) == null) { "latitude 200 accepted" }
        val text = SedapParser.parse("TEXT;05;0195238E25AD;C3D4;U;;;;04;NONE;Grüße")?.message as? TEXT
        expect(text?.type == TEXT.TextType.Chat && text.textContent == "Grüße") { "TEXT: $text" }
    }

    private fun sendHandler() {
        val handler = SendHandler(SedapClock(), { PASSWORD })
        val first = handler.prepare(ownUnit())
        val second = handler.prepare(ownUnit())
        expect(first != null && first.startsWith("OWNUNIT;00;")) { "first: $first" }
        expect(second != null && second.startsWith("OWNUNIT;01;")) { "second: $second" }
        expect(MessageAuthenticator.verify(checkNotNull(first), PASSWORD)) { "own MAC rejected: $first" }
        expect(handler.prepare(chat("Preis 5 €", DataEncoding.NONE)) == null) { "non ISO-8859-1 line not refused" }
    }

    private fun authenticator() {
        val signed = MessageAuthenticator.sign(CONTACT_LINE, PASSWORD)
        expect(MessageAuthenticator.verify(signed, PASSWORD)) { "signed line rejected: $signed" }
        expect(!MessageAuthenticator.verify(signed.replace(";Kutter;", ";Fischer;"), PASSWORD)) { "changed line accepted" }
        expect(!MessageAuthenticator.verify(signed, "other")) { "wrong password accepted" }
        // Same result as upstream's MAC over its own serialization. Upstream signs its re-serialization,
        // so the line must be upstream's own text (CONTACT_LINE is not: upstream drops DeleteFlag "FALSE").
        val canonical = checkNotNull(SEDAPExpressMessage.deserialize(CONTACT_LINE)).toString()
        val ours = MessageAuthenticator.compute(canonical, PASSWORD)
        val upstream = MACUtils.calc32BitHMAC(
            PASSWORD.toByteArray(StandardCharsets.ISO_8859_1),
            checkNotNull(SEDAPExpressMessage.deserialize(canonical)),
        )
        expect(ours == upstream) { "upstream MAC $upstream, ours $ours over $canonical" }
    }

    private fun decompressor() {
        val message = checkNotNull(SEDAPExpressMessage.deserialize(CONTACT_LINE))
        val compressed = CompressUtils.compressMessage(message)
        expect(Decompressor.decompress(compressed) == message.toString()) { "round trip of $compressed" }
        expect(Decompressor.decompress("%%%") == null) { "invalid Base64 not rejected" }
    }

    /** One SedapConnection against a server socket in this app: lines both ways, MAC, compression, ACK. */
    private fun connection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = TIMEOUT_MILLIS.toInt()
        var peer: Socket? = null
        try {
            val received = LinkedBlockingQueue<String>()
            val connection = SedapConnection(scope, { Identity("A1B2", Classification.Unclas) }, { PASSWORD })
            connection.subscribe { received.put(it) }
            connection.connect(TcpTransport("127.0.0.1", server.localPort, connectTimeoutMillis = TIMEOUT_MILLIS.toInt()))
            val socket = withContext(Dispatchers.IO) { server.accept() }
            peer = socket
            socket.soTimeout = TIMEOUT_MILLIS.toInt()
            withTimeout(TIMEOUT_MILLIS) { connection.state.first { it == ConnectionState.Connected } }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val writer = socket.getOutputStream()

            // Server to us: a signed line, a compressed signed line, a signed line that asks for an ACK.
            val plain = MessageAuthenticator.sign(CONTACT_LINE, PASSWORD)
            val textLine = MessageAuthenticator.sign(chat("Grüße", DataEncoding.BASE64).toString(), PASSWORD)
            val compressed = CompressUtils.compressMessage(checkNotNull(SEDAPExpressMessage.deserialize(textLine)))
            val wantsAck = MessageAuthenticator.sign(CONTACT_LINE.replace(";U;;;C1;", ";U;TRUE;;C1;"), PASSWORD)
            withContext(Dispatchers.IO) {
                writer.write("$plain\n$compressed\n$wantsAck\n".toByteArray(StandardCharsets.ISO_8859_1))
                writer.flush()
            }
            for (expected in listOf(plain, textLine, wantsAck)) {
                val line = withContext(Dispatchers.IO) { received.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
                expect(line == expected) { "expected $expected, got $line" }
            }
            val decoded = SedapParser.parse(textLine)?.message as? TEXT
            expect(decoded?.textContent == "Grüße") { "TEXT decoded as ${decoded?.textContent}" }

            val ack = withContext(Dispatchers.IO) { reader.readLine() }
            expect(ack != null && ack.startsWith("ACKNOWLEDGE;00;") && ack.contains(";C3D4;CONTACT;1A")) { "ACK: $ack" }
            expect(MessageAuthenticator.verify(checkNotNull(ack), PASSWORD)) { "ACK MAC: $ack" }

            // Us to server.
            expect(connection.send(ownUnit()) == SendResult.SENT) { "OWNUNIT not sent" }
            val own = withContext(Dispatchers.IO) { reader.readLine() }
            expect(own != null && own.startsWith("OWNUNIT;00;") && MessageAuthenticator.verify(own, PASSWORD)) { "OWNUNIT: $own" }

            connection.disconnect()
            withTimeout(TIMEOUT_MILLIS) { connection.state.first { it == ConnectionState.ManuallyDisconnected } }
        } finally {
            scope.cancel()
            peer?.close()
            server.close()
        }
    }

    private fun ownUnit() = OWNUNIT(
        null, null, "A1B2", Classification.Unclas, null, null,
        53.5432, 8.1234, 12.5, 4.2, 270.0, null, null, null, "Lotse", "SFGPUCI--------".toCharArray(),
    )

    private fun chat(text: String, encoding: DataEncoding) = TEXT(
        5.toByte(), 0x0195238E25ADL, "C3D4", Classification.Unclas, null, null, null, TEXT.TextType.Chat, encoding,
        if (encoding == DataEncoding.BASE64) TextCodec.forBase64Field(text) else text, null,
    )
}
