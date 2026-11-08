package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.CONTACT
import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT
import de.bundeswehr.uniity.sedapexpress.messages.POINT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.DataEncoding
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import de.bundeswehr.uniity.sedapexpress.messages.TIMESYNC
import de.visentra.sedapexpress.compat.Base64
import de.visentra.sedapexpress.protocol.FakeTimeSource
import de.visentra.sedapexpress.protocol.ReceiveHandler
import de.visentra.sedapexpress.protocol.SedapClock
import de.visentra.sedapexpress.protocol.SendHandler
import de.visentra.sedapexpress.protocol.TimeSource
import de.visentra.sedapexpress.protocol.wireField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Pins behaviour to the reference implementation (Java, `UNIITY-Team/SEDAP-Express`), which differs
 * from the ICD text in places. Ported from SEDAP-iOXTests/ReferenceInteropTests.swift (L1.6). Here the
 * reference's own classes serialize, so these tests also guard against an upstream update changing
 * the wire form. The live exchange with the reference follows in L1.7.
 */
class ReferenceRulesTest {

    /** Latin-1 Base64 vector from the reference's own WireEncodingTest. */
    private val latin1Text = "München £ÿ"
    private val latin1Base64 = "TfxuY2hlbiCj/w=="

    // MAC

    @Test
    fun `32-bit HMAC matches the reference - Adler32 over the hex digest`() {
        for ((password, line, expected) in listOf(
            Triple("expressexpressex", "OWNUNIT;5E;0195236D151A;66A3;R;;0000;53.32;8.11;0;5.5;21;22;;;FGS Bayern", "E2560ED7"),
            Triple("geheim", "HEARTBEAT;00;01A19BD5FCD0;MESE;U;;0000", "CBD70E18"),
            Triple("geheim", "TEXT;01;01A19BD5FCD0;MESE;S;;0000;ORKA;4;BASE64;TfxuY2hlbiCj/w==", "F3420F21"),
            Triple("pässwort", "ACKNOWLEDGE;18;0195238E25AD;Müller;R;;0000;LASSY;COMMAND;2B", "DB980E92"),
        )) {
            assertEquals(line, expected, MessageAuthenticator.compute(line, password))
        }
    }

    @Test
    fun `The MAC ignores the terminator and trailing semicolons, which the reference strips`() {
        val line = "HEARTBEAT;00;01A19BD5FCD0;MESE;U;;0000"
        assertEquals("CBD70E18", MessageAuthenticator.compute("$line;\n", "geheim"))
        assertEquals("CBD70E18", MessageAuthenticator.compute("$line;;;", "geheim"))
    }

    @Test
    fun `A line signed the reference's way verifies, and a change breaks it`() {
        val signed = "HEARTBEAT;00;01A19BD5FCD0;MESE;U;;CBD70E18"
        assertTrue(MessageAuthenticator.verify(signed, "geheim"))
        assertFalse(MessageAuthenticator.verify(signed, "falsch"))
        assertFalse(MessageAuthenticator.verify(signed.replace("MESE", "ORKA"), "geheim"))
    }

    // Canonical wire form. The reference verifies a MAC by re-serializing the parsed message, so numbers
    // and times have to be in the exact form it would produce.

    private fun ownUnitLine(speed: Double): String =
        OWNUNIT(0.toByte(), 0L, "MESE", Classification.Unclas, null, null, 53.32, 8.11, null, speed, null, null, null, null, null, null)
            .toString()

    @Test
    fun `Numbers are formatted like the reference's DecimalFormat`() {
        for ((value, expected) in listOf(
            0.0 to "0", 1.0 to "1", 21.0 to "21", -10.12 to "-10.12", 53.32 to "53.32",
            0.5 to "0.5", -0.5 to "-0.5", 0.00003 to "0.00003", 0.00001 to "0.00001",
            5577.0 to "5577", 120.5 to "120.5", 12_345_678.0 to "12345678",
            53.123456789012345 to "53.123456789012", 0.1234567890125 to "0.123456789012",
            0.1234567890135 to "0.123456789014", 1e16 to "10000000000000000", 2.5e-13 to "0",
        )) {
            assertEquals("$value", expected, wireField(ownUnitLine(value), 10))
        }
    }

    @Test
    fun `Time on the wire has the reference's 12 hex digits`() {
        // Includes an early time, which needs the leading zeros.
        for (millis in listOf(0L, 1_000L, 1_798_761_600_123L)) {
            val clock = SedapClock(object : TimeSource {
                override fun currentTimeMillis(): Long = millis
                override fun monotonicNanos(): Long = 0
            })
            val line = SendHandler(clock, { null }).prepare(HEARTBEAT(null, null, "MESE", Classification.Unclas, null, null))
                ?: error("refused")
            assertEquals(12, wireField(line, 2).length)
            assertEquals(millis, wireField(line, 2).toLong(16))
        }
    }

    @Test
    fun `TIMESYNC timestamps have 12 hex digits as well`() {
        val message = TIMESYNC(0.toByte(), 0L, "MESE", Classification.Unclas, null, null, 1_798_761_600_123L)
        assertEquals("01A2CE8BD47B", wireField(message.toString(), 7))
    }

    @Test
    fun `OWNUNIT sends its SIDC in upper case, as the reference re-serializes it`() {
        val unit = OWNUNIT(
            0.toByte(), 0L, "MESE", Classification.Unclas, null, null, 53.32, 8.11,
            null, null, null, null, null, null, null, "sfspclff-------".toCharArray(),
        )
        assertEquals("SFSPCLFF-------", wireField(unit.toString(), 16))
    }

    // TEXT type

    @Test
    fun `The TEXT type is read with or without a leading zero`() {
        for ((wire, expected) in listOf(
            "0" to TEXT.TextType.Undefined, "1" to TEXT.TextType.Alert, "2" to TEXT.TextType.Warning,
            "3" to TEXT.TextType.Notice, "4" to TEXT.TextType.Chat,
            "00" to TEXT.TextType.Undefined, "01" to TEXT.TextType.Alert, "02" to TEXT.TextType.Warning,
            "03" to TEXT.TextType.Notice, "04" to TEXT.TextType.Chat,
            "" to TEXT.TextType.Undefined, "9" to TEXT.TextType.Undefined, "x" to TEXT.TextType.Undefined,
        )) {
            val text = SedapParser.parse("TEXT;01;;324E;U;;;;$wire;NONE;Hallo")?.message as? TEXT
            assertEquals("'$wire'", expected, text?.type)
        }
    }

    @Test
    fun `The TEXT type is sent as the one digit the reference's parser accepts`() {
        val text = TEXT(0x01.toByte(), 0L, "MESE", Classification.Unclas, null, null, "ORKA", TEXT.TextType.Chat, DataEncoding.NONE, "Hallo", null)
        assertEquals("4", wireField(text.toString(), 8))
    }

    // Base64 text

    @Test
    fun `Base64 text from the reference decodes as ISO-8859-1`() {
        val text = SedapParser.parse("TEXT;;;;;;;;;BASE64;$latin1Base64")?.message as? TEXT
        assertEquals(latin1Text, text?.textContent)
    }

    @Test
    fun `Comments from the reference decode as ISO-8859-1`() {
        val contact = SedapParser.parse("CONTACT;5E;;83C5;R;;;100;FALSE;53.32;8.11;;;;;;;;;;;;;;;;;;;$latin1Base64")?.message as? CONTACT
        assertEquals(latin1Text, contact?.comment)
        // Fields 0-10, then 15 empty ones, so the comment lands on index 26.
        val pointFields = listOf("POINT", "5E", "", "66A3", "R", "", "", "100", "FALSE", "53.32", "8.11") +
            List(15) { "" } + latin1Base64
        val point = SedapParser.parse(pointFields.joinToString(";"))?.message as? POINT
        assertEquals(latin1Text, point?.comment)
    }

    @Test
    fun `UTF-8 Base64 text still decodes, for clients that send it`() {
        val base64 = Base64.toBase64String("ä€😀".toByteArray(StandardCharsets.UTF_8))
        val text = SedapParser.parse("TEXT;;;;;;;;;BASE64;$base64")?.message as? TEXT
        assertEquals("ä€😀", text?.textContent)
    }

    @Test
    fun `Malformed Base64 falls back to the raw value`() {
        val text = SedapParser.parse("TEXT;;;;;;;;;BASE64;%%%")?.message as? TEXT
        assertEquals("%%%", text?.textContent)
    }

    // Chat end to end. On iOS this runs through the app's chat store; the library stops at the line
    // and the parsed TEXT, so that is what these check.

    private fun sendHandler() = SendHandler(SedapClock(FakeTimeSource()), { null })

    private fun chat(text: String) = TEXT(
        null, null, "MESE", Classification.Unclas, null, null, "ORKA", TEXT.TextType.Chat, DataEncoding.BASE64,
        TextCodec.forBase64Field(text), null,
    )

    @Test
    fun `A chat message in the reference's format reaches the chat`() {
        val received = ArrayList<TEXT>()
        val handler = ReceiveHandler({ null }, { "MESE" })
        handler.subscribe { line -> (SedapParser.parse(line)?.message as? TEXT)?.let(received::add) }

        handler.handle("TEXT;01;01A19BD5FCD0;ORKA;U;;;MESE;4;BASE64;$latin1Base64")

        val text = received.single()
        assertEquals("ORKA", text.sender)
        assertEquals("MESE", text.recipient)
        assertEquals(TEXT.TextType.Chat, text.type)
        assertEquals(latin1Text, text.textContent)
    }

    @Test
    fun `Outgoing chat text uses ISO-8859-1 bytes whenever it can`() {
        val line = sendHandler().prepare(chat(latin1Text)) ?: error("refused")
        assertEquals("4", wireField(line, 8))
        assertEquals("BASE64", wireField(line, 9))
        assertEquals(latin1Base64, wireField(line, 10))
    }

    @Test
    fun `Text outside ISO-8859-1 falls back to UTF-8 and survives between our apps`() {
        val line = sendHandler().prepare(chat("Lage 😀")) ?: error("refused")
        assertEquals(Base64.toBase64String("Lage 😀".toByteArray(StandardCharsets.UTF_8)), wireField(line, 10))
        assertEquals("Lage 😀", (SedapParser.parse(line)?.message as? TEXT)?.textContent)
    }
}
