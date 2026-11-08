package de.visentra.sedapexpress.wire

import de.visentra.sedapexpress.compat.Base64
import de.visentra.sedapexpress.compat.DecoderException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Base64 text fields (TEXT with Encoding=BASE64, CONTACT and POINT comments), PROTOCOL §5. */
public object TextCodec {

    /** Strict UTF-8 first, then ISO-8859-1. Invalid Base64 returns the raw value, so it can still be shown. */
    public fun decodeBase64Text(value: String): String {
        val bytes = try {
            Base64.decode(value)
        } catch (e: DecoderException) {
            return value
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    /** ISO-8859-1 bytes if the text fits, else UTF-8, which only this library's readers are sure to understand. */
    public fun encodeBase64Text(text: String): String {
        val charset = if (StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) {
            StandardCharsets.ISO_8859_1
        } else {
            StandardCharsets.UTF_8
        }
        return Base64.toBase64String(text.toByteArray(charset))
    }
}
