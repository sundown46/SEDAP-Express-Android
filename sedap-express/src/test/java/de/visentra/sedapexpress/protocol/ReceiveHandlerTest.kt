package de.visentra.sedapexpress.protocol

import de.visentra.sedapexpress.wire.MessageAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveHandlerTest {

    private val log = RecordingLog()
    private var password: String? = null
    private var own: String? = "MESE"
    private val handler = ReceiveHandler({ password }, { own }, log)
    private val received = ArrayList<String>()
    private val acks = ArrayList<AckRequest>()

    init {
        handler.subscribe { received.add(it) }
        handler.onAcknowledgementRequested = { acks.add(it) }
    }

    @Test
    fun `Every subscriber gets a line, the log records it once`() {
        val second = ArrayList<String>()
        handler.subscribe { second.add(it) }

        handler.handle("HEARTBEAT;43;")

        assertEquals(listOf("HEARTBEAT;43;"), received)
        assertEquals(listOf("HEARTBEAT;43;"), second)
        assertEquals(listOf("HEARTBEAT;43;"), log.received)
    }

    @Test
    fun `Compressed lines reach subscribers as plain text (ICD §3·3)`() {
        handler.handle("C3GNCLE2NbY2MLQ0NTK2MHQ1MnSyNjYycbUOtg4JCnW1BgJDaz9/P1drpZCMzGIFIErMU0jMSS0qUVQCAA==")
        assertTrue(received.single().startsWith("TEXT;53;"))
        assertEquals(received, log.received)
    }

    @Test
    fun undecodableLinesPassUnchanged() {
        handler.handle("not base64!")
        assertEquals(listOf("not base64!"), received)
    }

    @Test
    fun `Acknowledgement TRUE reports sender, type and number (ICD §6·10)`() {
        handler.handle("TEXT;78;0195238E25AD;324E;S;TRUE;;;01;NONE;Hallo")
        handler.handle("TEXT;79;0195238E25AD;324E;S;;;;01;NONE;Hallo")
        handler.handle("TEXT;7A;0195238E25AD;324E;S;FALSE;;;01;NONE;Hallo")
        handler.handle("ownunit;7B;0195238E25AD;324E;S;true;;53.32;8.11")

        assertEquals(listOf(AckRequest("324E", "TEXT", "78"), AckRequest("324E", "OWNUNIT", "7B")), acks)
        assertEquals(4, received.size)
    }

    @Test
    fun noAcknowledgementForExceptions() {
        // IOS-05: own echoes, ACKNOWLEDGE, HEARTBEAT, lines without number, TEXT to someone else.
        handler.handle("OWNUNIT;01;0195238E25AD;MESE;S;TRUE;;53.32;8.11")
        handler.handle("ACKNOWLEDGE;02;0195238E25AD;324E;S;TRUE;;MESE;TEXT;01")
        handler.handle("HEARTBEAT;03;0195238E25AD;324E;S;TRUE")
        handler.handle("OWNUNIT;;0195238E25AD;324E;S;TRUE;;53.32;8.11")
        handler.handle("TEXT;04;0195238E25AD;324E;S;TRUE;;ORKA;01;NONE;Hallo")
        handler.handle("FOO;05;0195238E25AD;324E;S;TRUE")
        assertTrue(acks.isEmpty())
        assertEquals(6, received.size)
    }

    @Test
    fun textToUsOrToEveryoneIsAcknowledged() {
        handler.handle("TEXT;04;0195238E25AD;324E;S;TRUE;;MESE;01;NONE;Hallo")
        handler.handle("TEXT;05;0195238E25AD;324E;S;TRUE;;;01;NONE;Hallo")
        assertEquals(listOf("04", "05"), acks.map { it.messageNumber })
    }

    @Test
    fun withoutOwnSenderOnlyBroadcastTextIsAcknowledged() {
        own = null
        handler.handle("TEXT;04;0195238E25AD;324E;S;TRUE;;MESE;01;NONE;Hallo")
        handler.handle("TEXT;05;0195238E25AD;324E;S;TRUE;;;01;NONE;Hallo")
        handler.handle("OWNUNIT;06;0195238E25AD;MESE;S;TRUE;;53.32;8.11")
        assertEquals(listOf("05", "06"), acks.map { it.messageNumber })
    }

    @Test
    fun `With a password, only correctly authenticated lines get through`() {
        password = "geheim"
        val rejected = ArrayList<String>()
        handler.onMacRejected = { rejected.add(it) }

        val signed = MessageAuthenticator.sign("TEXT;78;0195238E25AD;324E;S;TRUE;0000;;01;NONE;Hallo", "geheim")
        val tampered = signed.replace("Hallo", "Falsch")
        val unsigned = "TEXT;78;0195238E25AD;324E;S;TRUE;;;01;NONE;Hallo"
        handler.handle(signed)
        handler.handle(tampered)
        handler.handle(unsigned)

        assertEquals(listOf(signed), received)
        assertEquals(1, acks.size)
        assertEquals(3, log.received.size)
        assertEquals(2, handler.macRejectedCount)
        assertEquals(listOf(tampered, unsigned), rejected)
    }

    @Test
    fun macIsCheckedOnTheDecompressedLine() {
        password = "geheim"
        handler.handle("C3GNCLE2NbY2MLQ0NTK2MHQ1MnSyNjYycbUOtg4JCnW1BgJDaz9/P1drpZCMzGIFIErMU0jMSS0qUVQCAA==")
        assertTrue(received.isEmpty())
        assertEquals(1, handler.macRejectedCount)
        assertTrue(log.received.single().startsWith("TEXT;53;"))
    }

    @Test
    fun passwordIsReadPerLine() {
        password = "geheim"
        handler.handle("HEARTBEAT;00;01A19BD5FCD0;MESE;U;;CBD70E18")
        password = null
        handler.handle("HEARTBEAT;01;01A19BD5FCD0;MESE;U")
        password = "falsch"
        handler.handle("HEARTBEAT;00;01A19BD5FCD0;MESE;U;;CBD70E18")
        assertEquals(2, received.size)
        assertEquals(1, handler.macRejectedCount)
    }
}
