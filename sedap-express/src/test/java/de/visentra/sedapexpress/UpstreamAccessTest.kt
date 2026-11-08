package de.visentra.sedapexpress

import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamAccessTest {

    @Test
    fun parsesUpstreamMessageFromKotlin() {
        val message = SEDAPExpressMessage.deserialize("HEARTBEAT;07;01A19BD5FCD0;MESE;U")
        assertTrue(message is HEARTBEAT)
        assertEquals("MESE", message.sender)
    }

    @Test
    fun goldenVectorsAreOnTheTestClasspath() {
        assertTrue(javaClass.classLoader?.getResource("golden-vectors.json") != null)
    }
}
