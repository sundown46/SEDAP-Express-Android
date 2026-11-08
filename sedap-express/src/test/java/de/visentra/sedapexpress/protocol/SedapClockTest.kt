package de.visentra.sedapexpress.protocol

import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SedapClockTest {

    private val time = FakeTimeSource()

    @Test
    fun `Disabled, the clock is the system clock and ignores sync results`() {
        val clock = SedapClock(time)
        clock.applySync(3_600_000)
        assertEquals(time.wallMillis, clock.nowMillis())
    }

    @Test
    fun `Enabled, the clock applies the learned offset, disabling resets it`() {
        val clock = SedapClock(time)
        clock.isEnabled = true
        clock.applySync(3_600_000)
        assertEquals(time.wallMillis + 3_600_000, clock.nowMillis())

        clock.isEnabled = false
        assertEquals(time.wallMillis, clock.nowMillis())
        clock.isEnabled = true
        assertEquals(time.wallMillis, clock.nowMillis())
    }

    @Test
    fun `Time on the wire is hex epoch milliseconds`() {
        val clock = SedapClock(time)
        val handler = SendHandler(clock, { null })
        val line = handler.prepare(HEARTBEAT(null, null, "MESE", Classification.Unclas, null, null)) ?: error("refused")
        val wire = wireField(line, 2)
        assertEquals(12, wire.length)
        assertEquals(String.format(Locale.ROOT, "%012X", time.wallMillis), wire)
    }

    @Test
    fun systemTimeSourceIsCurrent() {
        val now = System.currentTimeMillis()
        assertEquals(now.toDouble(), TimeSource.SYSTEM.currentTimeMillis().toDouble(), 2_000.0)
    }
}
