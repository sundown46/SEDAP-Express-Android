package de.visentra.sedapexpress.wire

import de.visentra.sedapexpress.compat.Base64
import de.visentra.sedapexpress.compat.DecoderException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Compressed lines (PROTOCOL §3): Base64 of a raw deflate stream. Replaces upstream's CompressUtils,
 * which shares one static Inflater across the process and returns a parsed message instead of the
 * plaintext line that the MAC check needs.
 */
public object Decompressor {

    public const val MAX_OUTPUT_BYTES: Int = 1 shl 20

    private const val CHUNK = 8 * 1024

    /** Base64 + raw deflate (Inflater(true)), must reach finished(), output as ISO-8859-1. Anything else -> null. */
    public fun decompress(candidate: String): String? {
        if (candidate.isEmpty()) return null
        val compressed = try {
            Base64.decode(candidate)
        } catch (e: DecoderException) {
            return null
        }
        // A decoder that skips foreign characters yields nothing for "%%%" (PROTOCOL §8).
        if (compressed.isEmpty()) return null

        val inflater = Inflater(true)
        try {
            // No dummy byte after the stream, although the Inflater docs mention one for nowrap: in fixed
            // Huffman blocks end-of-block is seven zero bits, so a zero byte could complete a truncated stream.
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                if (out.size() + count > MAX_OUTPUT_BYTES) return null
                out.write(buffer, 0, count)
            }
            return String(out.toByteArray(), StandardCharsets.ISO_8859_1)
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
