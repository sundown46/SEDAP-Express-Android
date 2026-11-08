package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.CONTACT
import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.DataEncoding
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import de.visentra.sedapexpress.compat.Base64
import de.visentra.sedapexpress.compat.DecoderException
import de.visentra.sedapexpress.protocol.FakeTimeSource
import de.visentra.sedapexpress.protocol.SedapClock
import de.visentra.sedapexpress.protocol.SendHandler
import de.visentra.sedapexpress.protocol.wireField
import de.visentra.sedapexpress.transport.LineBuffer
import de.visentra.sedapexpress.transport.encodeLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * One test per row of PROTOCOL §8 that concerns the library (L1.6). Each shows the trap with plain
 * JDK calls and then that the library does not fall into it. The rows about Location and LatLng belong
 * to the app and the plugin. The whole suite also runs under a German default locale, see the
 * sedap.testLocale property in the build.
 */
class PlatformPitfallsTest {

    private fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val original = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun sendHandler() = SendHandler(SedapClock(FakeTimeSource()), { null })

    @Test
    fun `Reports the default locale of this run`() {
        // Shows up in the test report, so a run with -Psedap.testLocale can be checked.
        println("Default locale: ${Locale.getDefault().toLanguageTag()}")
    }

    @Test
    fun `Numbers ignore the default locale`() {
        withDefaultLocale(Locale.GERMANY) {
            // The trap.
            assertEquals("53,32", String.format("%.2f", 53.32))
            assertEquals("53,32", java.text.NumberFormat.getInstance().format(53.32))

            // Upstream formats with fixed symbols, and so does everything that goes out.
            val unit = OWNUNIT(null, null, "MESE", Classification.Unclas, null, null, 53.32, -8.5, 1234.5, null, null, null, null, null, null, null)
            val line = sendHandler().prepare(unit) ?: error("refused")
            assertEquals(listOf("53.32", "-8.5", "1234.5"), listOf(wireField(line, 7), wireField(line, 8), wireField(line, 9)))
            assertEquals(53.32, SedapParser.parse(line)?.position?.latitude ?: Double.NaN, 0.0)
        }
    }

    @Test
    fun `toByteArray with ISO-8859-1 silently writes a question mark`() {
        // The trap.
        assertArrayEquals("M?SE".toByteArray(ISO_8859_1), "M€SE".toByteArray(ISO_8859_1))

        // The send handler refuses the message, the line buffer refuses the line.
        assertNull(sendHandler().prepare(HEARTBEAT(null, null, "M€SE", Classification.Unclas, null, null)))
        try {
            encodeLine("HEARTBEAT;00;;M€SE")
            fail("encoded a line outside ISO-8859-1")
        } catch (expected: CharacterCodingException) {
        }
    }

    @Test
    fun `Upstream's Base64 fields write a question mark too, unless filled through TextCodec`() {
        // Upstream encodes TEXT content and comments as ISO-8859-1, after which the send handler's
        // check only sees Base64 and cannot notice.
        val raw = TEXT(null, null, "MESE", Classification.Unclas, null, null, "ORKA", TEXT.TextType.Chat, DataEncoding.BASE64, "Lage 😀", null)
        val lossy = sendHandler().prepare(raw) ?: error("refused")
        // One '?' for the whole surrogate pair.
        assertEquals("Lage ?", String(Base64.decode(wireField(lossy, 10)), ISO_8859_1))

        raw.textContent = TextCodec.forBase64Field("Lage 😀")
        val line = sendHandler().prepare(raw) ?: error("refused")
        assertEquals("Lage 😀", TextCodec.decodeBase64Text(wireField(line, 10)))

        val contact = SedapParser.parse("CONTACT;01;;MESE;U;;;C1;FALSE;53;8")?.message as? CONTACT ?: error("rejected")
        contact.comment = TextCodec.forBase64Field("Grüße 😀")
        val parsed = SedapParser.parse(sendHandler().prepare(contact) ?: error("refused"))?.message as? CONTACT
        assertEquals("Grüße 😀", parsed?.comment)
    }

    @Test
    fun `String with UTF-8 silently replaces invalid bytes`() {
        val latin1 = "München".toByteArray(ISO_8859_1)
        // The trap: the result is never invalid, so a fallback that waits for an error never runs.
        assertEquals("M�nchen", String(latin1, UTF_8))

        assertEquals("München", TextCodec.decodeBase64Text(Base64.toBase64String(latin1)))
    }

    @Test
    fun `A MIME Base64 decoder skips foreign characters and returns nothing`() {
        // The trap: no error, just no bytes.
        assertEquals(0, java.util.Base64.getMimeDecoder().decode("%%%").size)

        try {
            Base64.decode("%%%")
            fail("decoded foreign characters")
        } catch (expected: DecoderException) {
        }
        assertEquals("%%%", TextCodec.decodeBase64Text("%%%"))
        assertNull(Decompressor.decompress("%%%"))
    }

    @Test
    fun `Inflater(false) expects a zlib header, a truncated raw stream needs finished()`() {
        val plain = "HEARTBEAT;00;01A19BD5FCD0;MESE;U".toByteArray(ISO_8859_1)
        val raw = deflate(plain)

        // The trap, part one: the reference sends raw deflate, a zlib inflater refuses it.
        val zlib = Inflater(false)
        try {
            zlib.setInput(raw)
            zlib.inflate(ByteArray(256))
            fail("zlib inflater read raw deflate")
        } catch (expected: DataFormatException) {
        } finally {
            zlib.end()
        }

        // Part two: a truncated stream inflates without an error, only finished() tells.
        val truncated = raw.copyOf(raw.size - 2)
        val inflater = Inflater(true)
        try {
            inflater.setInput(truncated)
            inflater.inflate(ByteArray(256))
            assertFalse(inflater.finished())
        } finally {
            inflater.end()
        }

        assertEquals(String(plain, ISO_8859_1), Decompressor.decompress(Base64.toBase64String(raw)))
        assertNull(Decompressor.decompress(Base64.toBase64String(truncated)))
    }

    @Test
    fun `toDouble accepts NaN, Infinity and type suffixes`() {
        // The trap, in Kotlin and in upstream's own number pattern, whose dot is unescaped.
        assertTrue("NaN".toDouble().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, "Infinity".toDouble(), 0.0)
        assertEquals(1.5, "1.5f".toDouble(), 0.0)
        assertEquals(1.0, "1d".toDouble(), 0.0)
        assertTrue(SEDAPExpressMessage.DOUBLE_MATCHER.matcher("53d").matches())
        assertEquals(53.0, (SEDAPExpressMessage.deserialize("OWNUNIT;5E;;;;;;53d;8.11") as OWNUNIT).latitude, 0.0)

        for (value in listOf("NaN", "Infinity", "-Infinity", "1.5f", "53d", "53D", "53F", "0x35", "53,32", "5x5")) {
            assertNull(value, SedapParser.parse("OWNUNIT;5E;;;;;;$value;8.11"))
            val unit = SedapParser.parse("OWNUNIT;5E;;;;;;53.32;8.11;;$value")?.message as? OWNUNIT
            assertNull(value, unit?.speed)
        }
        assertEquals(53.5, (SedapParser.parse("OWNUNIT;5E;;;;;;53.5;8.11")?.message as OWNUNIT).latitude, 0.0)
    }

    @Test
    fun `Hex with toString(16) is lower case and loses leading zeros`() {
        // The trap.
        assertEquals("191c643a8af", 0x0191C643A8AFL.toString(16))
        assertEquals("a", 10L.toString(16))

        val clock = SedapClock(FakeTimeSource(wallMillis = 0x0191C643A8AFL))
        val handler = SendHandler(clock, { null })
        val lines = (0..10).map { handler.prepare(HEARTBEAT(null, null, "MESE", Classification.Unclas, null, null)) ?: error("refused") }
        assertEquals("0191C643A8AF", wireField(lines[0], 2))
        assertEquals("0A", wireField(lines[10], 1))
    }

    @Test
    fun `readLine also ends a line at CR`() {
        val bytes = "HEARTBEAT;00;;A\rB\nTEXT;01;;Müller\n".toByteArray(ISO_8859_1)
        // The trap: three lines instead of two.
        assertEquals(3, ByteArrayInputStream(bytes).bufferedReader(ISO_8859_1).readLines().size)

        assertEquals(listOf("HEARTBEAT;00;;A\rB", "TEXT;01;;Müller"), LineBuffer(1 shl 20).append(bytes, bytes.size))
    }

    @Test
    fun `Locale-dependent upper case would turn an i into a dotted capital I`() {
        // Not in PROTOCOL §8, but the same class of trap: upstream upper-cases SIDCs with the default
        // locale. The library's own code always passes Locale.ROOT.
        withDefaultLocale(Locale.forLanguageTag("tr-TR")) {
            assertEquals("SFGPİB", "sfgpib".uppercase(Locale.getDefault()))
            assertEquals("SFGPIB---------", SidcNormalizer.normalize("sfgpib"))
            assertTrue(SedapParser.parse("ownunit;5E;;;;;;53.32;8.11")?.message is OWNUNIT)
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
