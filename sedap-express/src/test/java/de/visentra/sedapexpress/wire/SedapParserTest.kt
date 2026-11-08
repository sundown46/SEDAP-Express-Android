package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.CONTACT
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate rules beyond the vectors: type matching, ranges, altitude, text decoding. */
class SedapParserTest {

    private val reference = ReferencePosition(53.0, 8.0, 100.0)

    @Test
    fun typeMatchesWholeFieldIgnoringCase() {
        assertTrue(SedapParser.parse("ownunit;01;;;;;;53.32;8.11")?.message is OWNUNIT)
        assertTrue(SedapParser.parse("Heartbeat") != null)
        // IOS-11: a prefix must not match.
        assertNull(SedapParser.parse("OWNUNITX;01;;;;;;53.32;8.11"))
        assertNull(SedapParser.parse("HEARTBEATS"))
        assertNull(SedapParser.parse(""))
        assertNull(SedapParser.parse("garbage;;;"))
        assertEquals(15, SedapParser.KNOWN_TYPES.size)
    }

    @Test
    fun outOfRangeOrInfiniteCoordinatesAreMissing() {
        assertNull(SedapParser.parse("OWNUNIT;01;;;;;;91;8.11"))
        assertNull(SedapParser.parse("OWNUNIT;01;;;;;;53.32;-180.5"))
        assertNull(SedapParser.parse("OWNUNIT;01;;;;;;1${"0".repeat(400)};8.11"))
        assertNotNull(SedapParser.parse("OWNUNIT;01;;;;;;-90;180"))
    }

    @Test
    fun relativeAltitudeOnlyWithRelZAndNoAbsoluteAltitude() {
        val withZ = SedapParser.parse("CONTACT;01;;S1;U;;;7;FALSE;;;;0;1000;10") { reference }
        assertEquals(110.0, withZ?.position?.altitude ?: Double.NaN, 1e-9)
        val withoutZ = SedapParser.parse("CONTACT;01;;S1;U;;;7;FALSE;;;;0;1000") { reference }
        assertNull(withoutZ?.position?.altitude)
        assertNotNull(withoutZ?.position)
        val absoluteAltitude = SedapParser.parse("CONTACT;01;;S1;U;;;7;FALSE;;;50;0;1000;10") { reference }
        assertEquals(50.0, absoluteAltitude?.position?.altitude ?: Double.NaN, 1e-9)
    }

    @Test
    fun referenceIsLookedUpBySender() {
        var asked: String? = null
        SedapParser.parse("POINT;01;;S1;U;;;7;FALSE;;;;0;1000") { asked = it; reference }
        assertEquals("S1", asked)
    }

    @Test
    fun deleteFlagIgnoresCase() {
        val inbound = SedapParser.parse("CONTACT;01;;S1;U;;;7;true")
        assertNotNull(inbound)
        assertNull(inbound?.position)
    }

    @Test
    fun absolutePositionWinsOverRelative() {
        val inbound = SedapParser.parse("CONTACT;01;;S1;U;;;7;FALSE;10;20;;0;1000") { reference }
        assertEquals(GeoPosition(10.0, 20.0, null), inbound?.position)
    }

    @Test
    fun base64TextPrefersUtf8AndKeepsInvalidRaw() {
        val utf8 = SedapParser.parse("TEXT;01;;S1;U;;;;4;BASE64;w6Tigqzwn5iA")?.message as TEXT
        assertEquals("ä€😀", utf8.textContent)
        val invalid = SedapParser.parse("TEXT;01;;S1;U;;;;4;BASE64;%%%")?.message as TEXT
        assertEquals("%%%", invalid.textContent)
        val plain = SedapParser.parse("TEXT;01;;S1;U;;;;4;NONE;w6Tigqzwn5iA")?.message as TEXT
        assertEquals("w6Tigqzwn5iA", plain.textContent)
    }

    @Test
    fun contactCommentPrefersUtf8() {
        val line = "CONTACT;01;;S1;U;;;7;FALSE;53;8;;;;;;;;;;;;;;;;;;;w6Tigqzwn5iA"
        assertEquals("ä€😀", (SedapParser.parse(line)?.message as CONTACT).comment)
    }

    @Test
    fun brokenNumbersDoNotEscape() {
        // Upstream's number pattern lets "5x5" through to Double.valueOf, which throws.
        assertNull(SedapParser.parse("OWNUNIT;01;;;;;;5x5;8.11"))
        assertNull(SedapParser.parse("OWNUNIT;;;;;;;;"))
    }
}
