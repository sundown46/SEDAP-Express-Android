package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** sidc_normalize, relative_position, base64_text and the counter formatting. */
class HelperVectorTest {

    @Test
    fun sidcNormalize() {
        val cases = Vectors.root.getJSONObject("sidc_normalize").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals("sidc[${c.getString("input")}]", expected, SidcNormalizer.normalize(c.getString("input")))
        }
        assertNull(SidcNormalizer.normalize(null))
    }

    @Test
    fun relativePosition() {
        val section = Vectors.root.getJSONObject("relative_position")
        // The expected values carry 8 decimals but are only exact to the tolerance the vector states.
        val tolerance = section.getDouble("tolerance_degrees")
        val cases = section.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val ref = c.getJSONObject("reference")
            val reference = ReferencePosition(
                ref.getDouble("lat"), ref.getDouble("lon"), if (ref.isNull("alt")) null else ref.getDouble("alt"),
            )
            val z = if (c.isNull("z")) null else c.getDouble("z")
            val result = PositionResolver.resolve(c.getDouble("x"), c.getDouble("y"), z, reference)
            val expected = c.getJSONObject("expected")
            assertEquals("lat[$i]", expected.getDouble("lat"), result.latitude, tolerance)
            assertEquals("lon[$i]", expected.getDouble("lon"), result.longitude, tolerance)
            if (expected.isNull("alt")) {
                assertNull("alt[$i]", result.altitude)
            } else {
                assertEquals("alt[$i]", expected.getDouble("alt"), result.altitude ?: Double.NaN, 1e-9)
            }
        }
    }

    @Test
    fun base64Text() {
        val section = Vectors.root.getJSONObject("base64_text")
        val decode = section.getJSONArray("decode")
        for (i in 0 until decode.length()) {
            val c = decode.getJSONObject(i)
            val input = c.getString("input")
            // A null vector means invalid Base64, which falls back to showing the raw value.
            val expected = if (c.isNull("expected")) input else c.getString("expected")
            assertEquals("decode[$i]", expected, TextCodec.decodeBase64Text(input))
        }
        val encode = section.getJSONArray("encode")
        for (i in 0 until encode.length()) {
            val c = encode.getJSONObject(i)
            assertEquals("encode[$i]", c.getString("expected"), TextCodec.encodeBase64Text(c.getString("text")))
        }
    }

    @Test
    fun counterFormatting() {
        // Only the wire form here. Counting itself belongs to the send handler (L1.3).
        val tail = Vectors.root.getJSONObject("counter").getJSONObject("sequence_tail")
        for (key in tail.keys()) {
            val index = key.removePrefix("index_").toInt()
            assertEquals(key, tail.getString(key), SEDAPExpressMessage.HexFormater.toHexDigits((index % 128).toByte()))
        }
        assertEquals("00", SEDAPExpressMessage.HexFormater.toHexDigits(0.toByte()))
        assertEquals("0A", SEDAPExpressMessage.HexFormater.toHexDigits(10.toByte()))
    }
}
