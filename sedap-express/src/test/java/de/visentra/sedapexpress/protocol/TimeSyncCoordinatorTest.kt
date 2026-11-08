package de.visentra.sedapexpress.protocol

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.bundeswehr.uniity.sedapexpress.messages.TIMESYNC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TimeSyncCoordinatorTest {

    private val time = FakeTimeSource()
    private val clock = SedapClock(time)
    private val receive = ReceiveHandler({ null }, { "MESE" })
    private val sent = ArrayList<SEDAPExpressMessage>()
    private var identity: Identity? = Identity("MESE", Classification.Unclas)
    private val coordinator = TimeSyncCoordinator(clock, time, { identity }, { sent.add(it) }, receive)

    private fun reply(peerMillis: Long, sender: String = "FE2A") =
        "TIMESYNC;12;;$sender;U;;;" + String.format(Locale.ROOT, "%012X", peerMillis)

    @Test
    fun `Disabled, it neither requests nor answers`() {
        coordinator.requestSync()
        receive.handle("TIMESYNC;42;0191C643A8AF;89AD;U")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `Enabled, it answers a peer's request with a timestamp`() {
        clock.isEnabled = true
        clock.applySync(5_000)
        receive.handle("TIMESYNC;42;0191C643A8AF;89AD;U")
        val answer = sent.single() as TIMESYNC
        assertEquals(time.wallMillis + 5_000, answer.timestamp)
        assertEquals("MESE", answer.sender)
        assertEquals(Classification.Unclas, answer.classification)
    }

    @Test
    fun `A reply adjusts the clock, but only after our own request`() {
        clock.isEnabled = true
        val hourAhead = time.wallMillis + 3_600_000

        receive.handle(reply(hourAhead))
        assertEquals(time.wallMillis, clock.nowMillis())

        coordinator.requestSync()
        val request = sent.single() as TIMESYNC
        assertNull(request.timestamp)
        receive.handle(reply(hourAhead))
        assertEquals(hourAhead, clock.nowMillis())
    }

    @Test
    fun roundTripIsHalvedAndMeasuredMonotonically() {
        clock.isEnabled = true
        coordinator.requestSync()
        val peerAtReply = time.wallMillis + 10_000
        time.advance(400)
        // A jump of the device clock while waiting must not enter the round trip.
        time.wallMillis -= 60_000
        receive.handle(reply(peerAtReply))
        // offset = peer + rtt/2 - device now
        assertEquals(peerAtReply + 200 - time.wallMillis, clock.nowMillis() - time.wallMillis)
    }

    @Test
    fun onlyTheFirstReplyCounts() {
        clock.isEnabled = true
        coordinator.requestSync()
        receive.handle(reply(time.wallMillis + 1_000))
        receive.handle(reply(time.wallMillis + 9_000))
        assertEquals(time.wallMillis + 1_000, clock.nowMillis())
    }

    @Test
    fun ownTimesyncIsIgnored() {
        // IOS-04: an echo of our own request must not be answered, our own reply not adopted.
        clock.isEnabled = true
        coordinator.requestSync()
        receive.handle("TIMESYNC;00;0191C643A8AF;MESE;U")
        receive.handle(reply(time.wallMillis + 3_600_000, sender = "MESE"))
        assertEquals(1, sent.size)
        assertEquals(time.wallMillis, clock.nowMillis())
    }

    @Test
    fun unreadableTimestampIsNeitherRequestNorReply() {
        clock.isEnabled = true
        coordinator.requestSync()
        receive.handle("TIMESYNC;12;;FE2A;U;;;XYZ")
        assertEquals(1, sent.size)
        assertEquals(time.wallMillis, clock.nowMillis())
    }

    @Test
    fun noIdentityMeansNoTraffic() {
        identity = null
        clock.isEnabled = true
        coordinator.requestSync()
        receive.handle("TIMESYNC;42;0191C643A8AF;89AD;U")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun disablingAfterRequestIgnoresTheReply() {
        clock.isEnabled = true
        coordinator.requestSync()
        clock.isEnabled = false
        receive.handle(reply(time.wallMillis + 3_600_000))
        clock.isEnabled = true
        assertEquals(time.wallMillis, clock.nowMillis())
    }

    @Test
    fun otherTypesAreIgnored() {
        clock.isEnabled = true
        receive.handle("TIMESYNCX;42;0191C643A8AF;89AD;U")
        receive.handle("HEARTBEAT;42;0191C643A8AF;89AD;U")
        assertTrue(sent.isEmpty())
    }
}
