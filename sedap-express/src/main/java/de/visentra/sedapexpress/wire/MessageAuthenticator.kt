package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.utils.MACUtils
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 32-bit HMAC-SHA256 as the Java reference computes it (PROTOCOL §4), but over the line as received.
 * Upstream's MACUtils signs its own re-serialization, which differs from the sender's text for values
 * like "04" or "5577.0", so a correctly signed line would fail there.
 */
public object MessageAuthenticator {

    private const val MAC_INDEX = 6
    private const val MAC_PLACEHOLDER = "0000"

    /** MAC field (index 6) set to "0000", no terminator, no trailing semicolons; HMAC-SHA256 hex upper; Adler32 -> %08X. */
    public fun compute(line: String, password: String): String {
        val fields = fields(line)
        while (fields.size <= MAC_INDEX) fields.add("")
        fields[MAC_INDEX] = MAC_PLACEHOLDER
        return MACUtils.calcAdler32Checksum(hmacHex(content(fields), password))
    }

    /** Fewer than 7 fields -> false. Case-insensitive, constant-time compare. */
    public fun verify(line: String, password: String): Boolean {
        val fields = fields(line)
        if (fields.size <= MAC_INDEX) return false
        val received = fields[MAC_INDEX].uppercase(Locale.ROOT)
        val expected = compute(line, password)
        return MessageDigest.isEqual(
            received.toByteArray(StandardCharsets.ISO_8859_1),
            expected.toByteArray(StandardCharsets.ISO_8859_1),
        )
    }

    /** Returns the line with field 6 set, without terminator and trailing semicolons. Missing fields up to 6 are added empty. */
    public fun sign(line: String, password: String): String {
        val mac = compute(line, password)
        val fields = fields(line)
        while (fields.size <= MAC_INDEX) fields.add("")
        fields[MAC_INDEX] = mac
        return content(fields)
    }

    private fun fields(line: String): MutableList<String> =
        line.trimEnd { it == ';' || it == '\n' || it == '\r' }.split(';').toMutableList()

    private fun content(fields: List<String>): String = fields.joinToString(";").trimEnd(';')

    private fun hmacHex(content: String, password: String): String {
        val key = password.toByteArray(StandardCharsets.ISO_8859_1)
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec rejects an empty key. HMAC pads keys with zero bytes, so one zero byte is equivalent.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return MACUtils.hexFormatter.formatHex(mac.doFinal(content.toByteArray(StandardCharsets.ISO_8859_1)))
    }
}
