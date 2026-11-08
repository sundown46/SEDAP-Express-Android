package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.ACKNOWLEDGE
import de.bundeswehr.uniity.sedapexpress.messages.CONTACT
import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.METEO
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT
import de.bundeswehr.uniity.sedapexpress.messages.POINT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Acknowledgement
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.DeleteFlag
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.MessageType
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import de.bundeswehr.uniity.sedapexpress.messages.TIMESYNC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate rules beyond the vectors: type matching, ranges, altitude, text decoding. The tests with
 * sentence names are the ICD samples from SEDAP-iOXTests/MessageParsingTests.swift (L1.6).
 */
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

    // MessageParsingTests.swift: the literal samples of ICD v1.4.8 §6.

    private inline fun <reified T> parsed(line: String, references: ReferenceLookup = ReferenceLookup.NONE): Pair<T, GeoPosition?> {
        val inbound = SedapParser.parse(line, references) ?: throw AssertionError("rejected: $line")
        return (inbound.message as? T ?: throw AssertionError("wrong type: ${inbound.message.javaClass}")) to inbound.position
    }

    @Test
    fun `OWNUNIT sample with all fields`() {
        val (unit, position) = parsed<OWNUNIT>(
            "OWNUNIT;11;0191C643A8AF;22AA;U;FALSE;4389F10D;77.88;-10.12;5577.0;33.44;55.66;1.1;-2.2;3.3;Ownunit;SFGPIB----H----",
        )
        assertEquals(0x11.toByte(), unit.number)
        assertEquals(0x0191C643A8AFL, unit.time)
        assertEquals("22AA", unit.sender)
        assertEquals(Classification.Unclas, unit.classification)
        assertEquals(Acknowledgement.FALSE, unit.acknowledgement)
        assertEquals("4389F10D", unit.mac)
        assertEquals(77.88, unit.latitude, 0.0)
        assertEquals(-10.12, unit.longitude, 0.0)
        assertEquals(5577.0, unit.altitude, 0.0)
        assertEquals(33.44, unit.speed, 0.0)
        assertEquals(55.66, unit.course, 0.0)
        assertEquals(1.1, unit.heading, 0.0)
        assertEquals(-2.2, unit.roll, 0.0)
        assertEquals(3.3, unit.pitch, 0.0)
        assertEquals("Ownunit", unit.name)
        assertEquals("SFGPIB----H----", String(unit.sidc))
        assertEquals(GeoPosition(77.88, -10.12, 5577.0), position)
    }

    @Test
    fun `OWNUNIT sample with empty optional fields`() {
        val (unit, _) = parsed<OWNUNIT>("OWNUNIT;5E;0191C643A8AF;DRONEONE;R;;;53.32;8.11;0;5.5;21;22;;;FGS Bayern;SFSPCLFF-------")
        assertEquals(0.0, unit.altitude, 0.0)
        assertNull(unit.roll)
        assertNull(unit.pitch)
        assertEquals("FGS Bayern", unit.name)
    }

    @Test
    fun `OWNUNIT may be truncated right after the mandatory fields (§2)`() {
        val (unit, _) = parsed<OWNUNIT>("OWNUNIT;5E;;;;;;53.32;8.11")
        assertEquals(53.32, unit.latitude, 0.0)
        // Upstream leaves a missing name null where iOS has an empty string.
        assertTrue(unit.name.isNullOrEmpty())
    }

    @Test
    fun `OWNUNIT without a position is rejected`() {
        assertNull(SedapParser.parse("OWNUNIT;5E;0191C643A8AF;DRONEONE"))
    }

    @Test
    fun `CONTACT sample with comment`() {
        val (contact, _) = parsed<CONTACT>(
            "CONTACT;5E;0191C643A8AF;83C5;R;;;100;FALSE;53.32;8.11;0;;;;120;275;;;;;;;FGS Bayern;AR;SFSPCLFF-------;;;;VXNlIENIMjI=",
        )
        assertEquals("100", contact.contactID)
        assertEquals(DeleteFlag.FALSE, contact.deleteFlag)
        assertEquals(120.0, contact.speed, 0.0)
        assertEquals(275.0, contact.course, 0.0)
        assertEquals("FGS Bayern", contact.name)
        // Upstream keeps the sources as a sorted set, so only the members count.
        assertEquals(setOf(CONTACT.Source.AIS, CONTACT.Source.Radar), contact.source.toSet())
        assertEquals("SFSPCLFF-------", String(contact.sidc))
        assertEquals("Use CH22", contact.comment)
    }

    @Test
    fun `CONTACT sample with MMSI and an empty delete flag`() {
        val (contact, _) = parsed<CONTACT>(
            "CONTACT;5F;0191C643A8AF;83C5;U;;;101;;36.32;12.11;2000;;;;44;;;;;;;;Unknown;O;;221333201;;;UG9zcyBOZXRoZXJsYW5kcw==",
        )
        assertFalse(contact.deleteFlag == DeleteFlag.TRUE)
        assertEquals("221333201", contact.mmsi)
        assertEquals("Poss Netherlands", contact.comment)
    }

    @Test
    fun `CONTACT without any usable position is rejected`() {
        assertNull(SedapParser.parse("CONTACT;5E;0191C643A8AF;83C5;R;;;100;FALSE"))
    }

    @Test
    fun `Relative CONTACT resolves against the reference position`() {
        val reference = ReferenceLookup { ReferencePosition(53.0, 8.0, 100.0) }
        val (_, position) = parsed<CONTACT>("CONTACT;5E;;83C5;R;;;100;FALSE;;;;0;1000;10", reference)
        val resolved = position ?: throw AssertionError("no position")
        assertEquals(8.0, resolved.longitude, 0.0)
        // 1000 m north is about 0.009°.
        assertEquals(53.009, resolved.latitude, 0.0005)
        assertEquals(110.0, resolved.altitude ?: Double.NaN, 1e-9)
    }

    @Test
    fun `Relative CONTACT without a reference position is rejected`() {
        assertNull(SedapParser.parse("CONTACT;5E;;83C5;R;;;100;FALSE;;;;0;1000;10", ReferenceLookup.NONE))
    }

    @Test
    fun `POINT sample with absolute position and comment`() {
        val (point, _) = parsed<POINT>(
            "POINT;5E;0000661D4410;66A3;R;;;100;FALSE;53.32;8.11;0;;;;120;275;;;;;;;Target Alpha;gffppt---------;;VGFyZ2V0IHBvaW50",
        )
        assertEquals("100", point.pointID)
        assertEquals("Target Alpha", point.name)
        assertEquals("gffppt---------", String(point.sidc))
        assertEquals("Target point", point.comment)
    }

    @Test
    fun `POINT sample with relative position`() {
        val reference = ReferenceLookup { ReferencePosition(53.0, 8.0, 0.0) }
        val (point, position) = parsed<POINT>(
            "POINT;6A;0013E45956BE;59CE;U;;FFAA327B;1000;FALSE;;;;100;130;0;2;30;;;;;;;Person in water;gfopep---------",
            reference,
        )
        val resolved = position ?: throw AssertionError("no position")
        assertTrue(resolved.latitude > 53.0)
        assertTrue(resolved.longitude > 8.0)
        assertEquals(2.0, point.speed, 0.0)
        assertEquals(30.0, point.course, 0.0)
        assertEquals("Person in water", point.name)
    }

    @Test
    fun `TEXT sample with a plain alert`() {
        val (text, _) = parsed<TEXT>("TEXT;78;0195238E25AD;324E;S;TRUE;;;01;NONE;\"This is an alert!\";1000")
        assertEquals(Acknowledgement.TRUE, text.acknowledgement)
        assertTrue(text.recipient.isNullOrEmpty())
        assertEquals(TEXT.TextType.Alert, text.type)
        assertEquals("\"This is an alert!\"", text.textContent)
        assertEquals("1000", text.reference)
    }

    @Test
    fun `TEXT sample with a Base64 chat message`() {
        val (text, _) = parsed<TEXT>("TEXT;7B;0195238E285B;324E;U;;;ORKA;04;BASE64;IlRoaXMgaXMgYSBjaGF0IG1lc3NhZ2UhIg==")
        assertEquals("ORKA", text.recipient)
        assertEquals(TEXT.TextType.Chat, text.type)
        assertEquals("\"This is a chat message!\"", text.textContent)
    }

    @Test
    fun `TEXT without text is rejected`() {
        assertNull(SedapParser.parse("TEXT;78;0195238E25AD;324E;S;TRUE;;;01;NONE"))
    }

    @Test
    fun `HEARTBEAT samples, down to a bare type and number`() {
        for ((line, recipient) in listOf(
            "HEARTBEAT;42;0195238E25AD;89AD;U;;;ORKA" to "ORKA",
            "HEARTBEAT;43;;1022" to null,
            "HEARTBEAT;43;" to null,
        )) {
            val (heartbeat, _) = parsed<HEARTBEAT>(line)
            assertEquals(line, recipient, heartbeat.recipient?.takeIf { it.isNotEmpty() })
        }
    }

    @Test
    fun `ACKNOWLEDGE sample`() {
        val (ack, _) = parsed<ACKNOWLEDGE>("ACKNOWLEDGE;18;0195238E25AD;129E;R;;;LASSY;COMMAND;2B")
        assertEquals("LASSY", ack.recipient)
        assertEquals(MessageType.COMMAND, ack.typeOfTheMessage)
        assertEquals(0x2B.toByte(), ack.numberOfTheMessage)
    }

    @Test
    fun `TIMESYNC request has no timestamp, a reply has one`() {
        assertNull(parsed<TIMESYNC>("TIMESYNC;42;0191C643A8AF;89AD;U").first.timestamp)
        assertEquals(0x0191C643A8AFL, parsed<TIMESYNC>("TIMESYNC;12;;FE2A;U;;;0191C643A8AF").first.timestamp)
    }

    @Test
    fun `Factory picks the type from the first field, ignoring case`() {
        assertTrue(SedapParser.parse("ownunit;5E;;;;;;53.32;8.11")?.message is OWNUNIT)
        assertTrue(SedapParser.parse("HEARTBEAT;43;")?.message is HEARTBEAT)
        // iOS only builds the types it shows and returns nil for METEO. The library accepts all 15 ICD
        // types (FR-PRO-01) and leaves the choice to its consumers. Unknown types stay rejected.
        assertTrue(SedapParser.parse("METEO;1C")?.message is METEO)
        assertNull(SedapParser.parse("WEATHER;1C"))
    }
}
