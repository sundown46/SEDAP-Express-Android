package de.visentra.sedapexpress.wire

import de.visentra.sedapexpress.compat.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/** compression vectors plus truncated streams, zlib headers and a decompression bomb. */
class DecompressorTest {

    private val section = Vectors.root.getJSONObject("compression")

    @Test
    fun decompressVectors() {
        val cases = section.getJSONArray("decompress")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("decompress[$i]", c.getString("expected"), Decompressor.decompress(c.getString("input")))
        }
    }

    @Test
    fun invalidVectors() {
        val cases = section.getJSONArray("invalid")
        for (i in 0 until cases.length()) {
            assertNull("invalid[$i]", Decompressor.decompress(cases.getString(i)))
        }
    }

    @Test
    fun foreignCharactersAndWhitespaceOnlyAreRejected() {
        assertNull(Decompressor.decompress("%%%"))
        assertNull(Decompressor.decompress("    "))
        assertNull(Decompressor.decompress("HEARTBEAT;00;01A19BD5FCD0;MESE;U"))
    }

    @Test
    fun roundTripKeepsIso88591() {
        val line = "TEXT;01;01A19BD5FCD0;MESE;S;;;;1;NONE;Grüße ÄÖÜ ß"
        assertEquals(line, Decompressor.decompress(compress(line.toByteArray(StandardCharsets.ISO_8859_1))))
    }

    @Test
    fun everyTruncationIsRejected() {
        val bytes = deflate("TEXT;01;01A19BD5FCD0;MESE;S;;;;1;NONE;Hallo".toByteArray(StandardCharsets.ISO_8859_1))
        for (length in 1 until bytes.size) {
            assertNull("truncated to $length", Decompressor.decompress(Base64.toBase64String(bytes.copyOf(length))))
        }
    }

    @Test
    fun zlibHeaderIsRejected() {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        val zlib = run(deflater, "HEARTBEAT;00;01A19BD5FCD0;MESE;U".toByteArray(StandardCharsets.ISO_8859_1))
        assertNull(Decompressor.decompress(Base64.toBase64String(zlib)))
    }

    @Test
    fun outputLimit() {
        assertEquals(Decompressor.MAX_OUTPUT_BYTES,
            Decompressor.decompress(compress(ByteArray(Decompressor.MAX_OUTPUT_BYTES) { 'A'.code.toByte() }))?.length)
        assertNull(Decompressor.decompress(compress(ByteArray(Decompressor.MAX_OUTPUT_BYTES + 1))))
        assertNull(Decompressor.decompress(compress(ByteArray(16 shl 20))))
    }

    private fun compress(data: ByteArray): String = Base64.toBase64String(deflate(data))

    private fun deflate(data: ByteArray): ByteArray = run(Deflater(Deflater.BEST_COMPRESSION, true), data)

    private fun run(deflater: Deflater, data: ByteArray): ByteArray {
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
