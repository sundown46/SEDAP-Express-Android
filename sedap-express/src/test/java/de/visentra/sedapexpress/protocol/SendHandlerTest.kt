package de.visentra.sedapexpress.protocol

import de.bundeswehr.uniity.sedapexpress.messages.ACKNOWLEDGE
import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.MessageType
import de.visentra.sedapexpress.wire.MessageAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendHandlerTest {

    private val time = FakeTimeSource()
    private val log = RecordingLog()
    private var password: String? = null
    private val handler = SendHandler(SedapClock(time), { password }, log)

    private fun heartbeat(sender: String = "MESE") = HEARTBEAT(null, null, sender, Classification.Unclas, null, null)

    private fun acknowledge() = ACKNOWLEDGE(
        null, null, "MESE", Classification.Unclas, null, null, "ORKA", MessageType.TEXT, 1.toByte(),
    )

    private fun send(message: de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage): String =
        handler.prepare(message) ?: error("refused")

    @Test
    fun `Every message type has its own counter (ICD §5)`() {
        val lines = listOf(send(heartbeat()), send(heartbeat()), send(acknowledge()))
        assertEquals(listOf("00", "01", "00"), lines.map { wireField(it, 1) })
    }

    @Test
    fun `The counter wraps to 00 after 7F (ICD §5)`() {
        val lines = (0 until 129).map { send(heartbeat()) }
        assertEquals("0A", wireField(lines[10], 1))
        assertEquals("7F", wireField(lines[127], 1))
        assertEquals("00", wireField(lines[128], 1))
    }

    @Test
    fun `Sent lines have no terminator and are logged`() {
        val line = send(heartbeat())
        assertFalse(line.endsWith("\n"))
        assertEquals("HEARTBEAT;00;${wireField(line, 2)};MESE;U", line)
        assertEquals(listOf(line), log.sent)
    }

    @Test
    fun `Characters outside ISO-8859-1 are refused without using a number`() {
        assertNull(handler.prepare(heartbeat(sender = "M€SE")))
        assertTrue(log.sent.isEmpty())
        assertEquals("00", wireField(send(heartbeat(sender = "MÜLLER")), 1))
    }

    @Test
    fun `No MAC field without a password, one that verifies with a password`() {
        assertEquals("", wireField(send(heartbeat()), 6))

        password = "geheim"
        val line = send(heartbeat())
        assertEquals(8, wireField(line, 6).length)
        assertTrue(MessageAuthenticator.verify(line, "geheim"))
        assertFalse(MessageAuthenticator.verify(line, "falsch"))
    }

    @Test
    fun emptyPasswordMeansNoMac() {
        password = ""
        assertEquals("", wireField(send(heartbeat()), 6))
    }

    @Test
    fun reusedMessageGetsFreshHeader() {
        val message = heartbeat()
        password = "geheim"
        send(message)
        password = null
        time.advance(1_000)
        val line = send(message)
        assertEquals("01", wireField(line, 1))
        assertEquals("", wireField(line, 6))
        assertEquals(String.format(java.util.Locale.ROOT, "%012X", time.wallMillis), wireField(line, 2))
    }

    @Test
    fun timeComesFromTheSedapClock() {
        val clock = SedapClock(time).apply { isEnabled = true; applySync(60_000) }
        val line = SendHandler(clock, { null }).prepare(heartbeat()) ?: error("refused")
        assertEquals(time.wallMillis + 60_000, wireField(line, 2).toLong(16))
    }
}
