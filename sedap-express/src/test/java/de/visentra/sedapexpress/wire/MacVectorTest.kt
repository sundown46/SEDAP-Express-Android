package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/** mac vectors (compute, verify incl. tampered lines) and the cross-check against upstream's MACUtils. */
class MacVectorTest {

    private val section = Vectors.root.getJSONObject("mac")

    @Test
    fun computeVectors() {
        val cases = section.getJSONArray("compute")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("compute[$i]", c.getString("expected"),
                MessageAuthenticator.compute(c.getString("line"), c.getString("password")))
        }
    }

    @Test
    fun verifyVectors() {
        val cases = section.getJSONArray("verify")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            var line = c.getString("line")
            // Some vectors carry a label before the colon, see their note.
            if (c.optString("note").contains("Präfix")) line = line.substringAfter(':')
            assertEquals("verify[$i] $line", c.getBoolean("valid"),
                MessageAuthenticator.verify(line, c.getString("password")))
        }
    }

    @Test
    fun signProducesVerifiableLine() {
        val signed = MessageAuthenticator.sign("HEARTBEAT;00;01A19BD5FCD0;MESE;U", "geheim")
        assertEquals("HEARTBEAT;00;01A19BD5FCD0;MESE;U;;CBD70E18", signed)
        assertTrue(MessageAuthenticator.verify(signed, "geheim"))
        assertTrue(MessageAuthenticator.verify("$signed;;\n", "geheim"))
        assertFalse(MessageAuthenticator.verify(signed.replace("MESE", "MESF"), "geheim"))
    }

    @Test
    fun emptyPasswordWorks() {
        val signed = MessageAuthenticator.sign("HEARTBEAT;00;01A19BD5FCD0;MESE;U", "")
        assertTrue(MessageAuthenticator.verify(signed, ""))
        assertFalse(MessageAuthenticator.verify(signed, "geheim"))
    }

    @Test
    fun matchesUpstreamWhenItReserializesUnchanged() {
        val line = "HEARTBEAT;00;01A19BD5FCD0;MESE;U;;0000"
        val message = SEDAPExpressMessage.deserialize(line)
        assertEquals("precondition: upstream keeps the text", line, message.toString())
        val key = "geheim".toByteArray(StandardCharsets.ISO_8859_1)
        assertEquals(MACUtils.calc32BitHMAC(key, message), MessageAuthenticator.compute(line, "geheim"))
    }

    @Test
    fun onlyTheRawLineVariantVerifiesReformattedNumbers() {
        // L0 finding 2: upstream signs its own toString(), which writes 5577.0 differently.
        val unsigned = "OWNUNIT;5E;0195236D151A;66A3;R;;0000;53.32;8.11;5577.0;5.5;21;22"
        val signed = MessageAuthenticator.sign(unsigned, "geheim")
        assertTrue(MessageAuthenticator.verify(signed, "geheim"))

        val message = SEDAPExpressMessage.deserialize(signed)
        val reserialized = message.toString()
        assertNotEquals("precondition: upstream changes the text", signed, reserialized)
        val key = "geheim".toByteArray(StandardCharsets.ISO_8859_1)
        assertFalse(MACUtils.isMessageAuthentic(key, message))
    }
}
