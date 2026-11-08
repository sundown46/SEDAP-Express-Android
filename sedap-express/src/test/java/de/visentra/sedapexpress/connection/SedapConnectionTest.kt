package de.visentra.sedapexpress.connection

import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.Classification
import de.visentra.sedapexpress.connection.ConnectionEvent.LineTooLong
import de.visentra.sedapexpress.connection.ConnectionEvent.LinkFailed
import de.visentra.sedapexpress.connection.ConnectionEvent.MacRejected
import de.visentra.sedapexpress.connection.ConnectionEvent.ReconnectScheduled
import de.visentra.sedapexpress.connection.ConnectionEvent.SendRefused
import de.visentra.sedapexpress.connection.ConnectionState.Connected
import de.visentra.sedapexpress.connection.ConnectionState.Connecting
import de.visentra.sedapexpress.connection.ConnectionState.Disconnected
import de.visentra.sedapexpress.connection.ConnectionState.ManuallyDisconnected
import de.visentra.sedapexpress.connection.ConnectionState.Ready
import de.visentra.sedapexpress.protocol.Identity
import de.visentra.sedapexpress.protocol.wireField
import de.visentra.sedapexpress.transport.DatagramTooLargeException
import de.visentra.sedapexpress.transport.LineTooLongException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class SedapConnectionTest {

    private val me = Identity("MESE", Classification.Unclas)

    private class Harness(
        val connection: SedapConnection,
        val states: List<ConnectionState>,
        val events: List<ConnectionEvent>,
        val lines: List<String>,
    )

    /** A connection in virtual time that records every state, event and delivered line. */
    private fun TestScope.harness(password: String? = null, identity: Identity? = me): Harness {
        val connection = SedapConnection(backgroundScope, { identity }, { password }, SchedulerTimeSource(testScheduler))
        val states = ArrayList<ConnectionState>()
        val events = ArrayList<ConnectionEvent>()
        val lines = ArrayList<String>()
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { connection.state.toList(states) }
        backgroundScope.launch(eager) { connection.events.toList(events) }
        connection.subscribe { lines.add(it) }
        return Harness(connection, states, events, lines)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun heartbeat(sender: String = "MESE") = HEARTBEAT(null, null, sender, Classification.Unclas, null, null)

    @Test
    fun `Backoff is base times 2 to the attempt minus one, capped and without overflow`() {
        val policy = ConnectionPolicy()
        assertEquals(
            listOf(2_000L, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000),
            (1..7).map(policy::backoffMillis),
        )
        assertEquals(60_000L, policy.backoffMillis(Int.MAX_VALUE))
        val odd = ConnectionPolicy(backoffBaseMillis = 2, backoffMaxMillis = 5)
        assertEquals(listOf(2L, 4, 5, 5), (1..4).map(odd::backoffMillis))
    }

    @Test
    fun `States run from ready over connecting to connected and manually disconnected (FR-CON-02)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        h.connection.disconnect()
        runCurrent()

        assertEquals(listOf(Ready, Connecting, Connected, ManuallyDisconnected), h.states)
        assertTrue(transport.links.single().isClosed)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `Backoff doubles up to 60 s and resets only after a successful connect (FR-CON-04)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        repeat(8) { transport.openFailures.add(ConnectException("Connection refused")) }
        h.connection.connect(transport)
        runCurrent()

        val delays = listOf(2_000L, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000)
        delays.forEachIndexed { index, delayMillis ->
            assertEquals(Disconnected(index + 1, delayMillis), h.connection.state.value)
            val opens = transport.opens
            advance(delayMillis - 1)
            assertEquals("no retry before the delay", opens, transport.opens)
            advance(1)
            assertEquals(opens + 1, transport.opens)
        }
        assertEquals(Connected, h.connection.state.value)

        transport.links.single().endOfStream()
        runCurrent()
        assertEquals(Disconnected(1, 2_000), h.connection.state.value)
        assertEquals(
            delays.mapIndexed { index, delayMillis -> ReconnectScheduled(index + 1, delayMillis) } + ReconnectScheduled(1, 2_000),
            h.events.filterIsInstance<ReconnectScheduled>(),
        )
    }

    @Test
    fun `A manual connect resets the backoff`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        repeat(4) { transport.openFailures.add(ConnectException("Connection refused")) }
        h.connection.connect(transport)
        runCurrent()
        advance(2_000)
        advance(4_000)
        assertEquals(Disconnected(3, 8_000), h.connection.state.value)

        h.connection.connect(transport)
        runCurrent()
        assertEquals(Disconnected(1, 2_000), h.connection.state.value)
    }

    @Test
    fun `Disconnect cancels a pending reconnect (FR-CON-05, IOS-20)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        transport.openFailures.add(ConnectException("Connection refused"))
        h.connection.connect(transport)
        runCurrent()
        assertEquals(Disconnected(1, 2_000), h.connection.state.value)

        h.connection.disconnect()
        advance(600_000)
        assertEquals(1, transport.opens)
        assertEquals(ManuallyDisconnected, h.connection.state.value)
    }

    @Test
    fun `Disconnect aborts a connection attempt in progress`() = runTest {
        val h = harness()
        val transport = FakeTransport().apply { openDelayMillis = 5_000 }
        h.connection.connect(transport)
        advance(1_000)
        assertEquals(Connecting, h.connection.state.value)

        h.connection.disconnect()
        advance(600_000)
        assertTrue(transport.links.isEmpty())
        assertEquals(1, transport.opens)
        assertEquals(ManuallyDisconnected, h.connection.state.value)
    }

    @Test
    fun `A new connect replaces the link, never two at once (FR-CON-01)`() = runTest {
        val h = harness()
        val registry = LinkRegistry()
        val first = FakeTransport(registry = registry)
        val second = FakeTransport(registry = registry).apply { openDelayMillis = 5_000 }
        val third = FakeTransport(registry = registry)

        h.connection.connect(first)
        runCurrent()
        h.connection.connect(second)
        advance(1_000)
        assertTrue(first.links.single().isClosed)
        assertEquals(Connecting, h.connection.state.value)

        // A connect during a running attempt cancels that attempt.
        h.connection.connect(third)
        advance(10_000)
        assertTrue(second.links.isEmpty())
        assertFalse(third.links.single().isClosed)
        assertEquals(Connected, h.connection.state.value)
        assertFalse(registry.overlapSeen)
    }

    @Test
    fun `EOF reports a failed link and reconnects to the same generation (FR-CON-06)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        transport.links.single().endOfStream()
        runCurrent()
        assertEquals(listOf(LinkFailed(null), ReconnectScheduled(1, 2_000)), h.events)

        advance(2_000)
        assertEquals(listOf(Ready, Connecting, Connected, Disconnected(1, 2_000), Connecting, Connected), h.states)
        assertEquals(1L, h.connection.generation.value)
        transport.links[1].receive("CONTACT;01;0195238E25AD;FE2A;U;;;C1")
        runCurrent()
        assertEquals(listOf("CONTACT;01;0195238E25AD;FE2A;U;;;C1"), h.lines)
    }

    @Test
    fun `The generation grows with connect and disconnect, not with automatic reconnects`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        assertEquals(0L, h.connection.generation.value)

        h.connection.connect(transport)
        assertEquals("bumped before connect returns", 1L, h.connection.generation.value)
        runCurrent()
        transport.links.single().endOfStream()
        advance(2_000)
        assertEquals(Connected, h.connection.state.value)
        assertEquals(1L, h.connection.generation.value)

        h.connection.connect(transport)
        assertEquals(2L, h.connection.generation.value)
        h.connection.disconnect()
        assertEquals(3L, h.connection.generation.value)
    }

    @Test
    fun `Lines of a replaced link are dropped, even when already queued (FR-CON-09, FR-CON-11)`() = runTest {
        val h = harness()
        val first = FakeTransport()
        val second = FakeTransport()
        h.connection.connect(first)
        runCurrent()
        val old = first.links.single()
        old.receive("CONTACT;01;0195238E25AD;FE2A;U;;;C1")
        runCurrent()

        // Read from the socket but not yet handled when the user switches servers.
        old.receive("CONTACT;02;0195238E25AD;FE2A;U;TRUE;;C2")
        h.connection.connect(second)
        runCurrent()
        assertEquals(listOf("CONTACT;01;0195238E25AD;FE2A;U;;;C1"), h.lines)
        assertTrue("no ACK for a dropped line", old.sentOfType("ACKNOWLEDGE").isEmpty())

        second.links.single().receive("CONTACT;01;0195238E25AD;B0B0;U;;;X1")
        runCurrent()
        assertEquals("CONTACT;01;0195238E25AD;B0B0;U;;;X1", h.lines.last())
    }

    @Test
    fun `A late failure of a replaced link changes nothing (FR-CON-11)`() = runTest {
        val h = harness()
        val first = FakeTransport()
        val second = FakeTransport()
        h.connection.connect(first)
        runCurrent()
        first.links.single().sendDelayMillis = 1_000
        val result = async { h.connection.send(heartbeat()) }
        runCurrent()

        h.connection.connect(second)
        advance(1_000)
        assertEquals(SendResult.FAILED, result.await())
        assertEquals(Connected, h.connection.state.value)
        assertFalse(second.links.single().isClosed)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `HEARTBEAT every 20 s without recipient, stops on disconnect (FR-CON-12)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        val link = transport.links.single()

        advance(19_999)
        assertTrue(link.sentOfType("HEARTBEAT").isEmpty())
        advance(1)
        advance(40_000)
        val heartbeats = link.sentOfType("HEARTBEAT")
        assertEquals(listOf("00", "01", "02"), heartbeats.map { wireField(it, 1) })
        heartbeats.forEach { assertTrue(it, it.matches(Regex("HEARTBEAT;[0-7][0-9A-F];[0-9A-F]+;MESE;U"))) }

        h.connection.disconnect()
        advance(100_000)
        assertEquals(3, link.sentOfType("HEARTBEAT").size)
    }

    @Test
    fun `No HEARTBEAT without an identity`() = runTest {
        val h = harness(identity = null)
        val transport = FakeTransport()
        h.connection.connect(transport)
        advance(60_000)
        assertEquals(Connected, h.connection.state.value)
        assertTrue(transport.links.single().sent.isEmpty())
    }

    @Test
    fun `A failed HEARTBEAT rebuilds a half-open link (FR-CON-06)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        val broken = IOException("Broken pipe")
        transport.links.single().sendFailure = broken

        advance(20_000)
        assertTrue(transport.links.single().isClosed)
        assertEquals(Disconnected(1, 2_000), h.connection.state.value)
        assertEquals(listOf(LinkFailed(broken), ReconnectScheduled(1, 2_000)), h.events)

        advance(2_000)
        assertEquals(Connected, h.connection.state.value)
        assertEquals(2, transport.links.size)
    }

    @Test
    fun `Sending needs a link, and a refused message uses no number`() = runTest {
        val h = harness()
        assertEquals(SendResult.NOT_CONNECTED, h.connection.send(heartbeat()))
        assertEquals(SendRefused("HEARTBEAT"), h.events.last())

        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        assertEquals(SendResult.REFUSED, h.connection.send(heartbeat(sender = "M€SE")))
        assertEquals(SendRefused("HEARTBEAT"), h.events.last())
        assertEquals(SendResult.SENT, h.connection.send(heartbeat()))
        assertEquals("00", wireField(transport.links.single().sent.single(), 1))
    }

    @Test
    fun `UDP is connected right after open, a write error rebuilds it, an oversized datagram does not`() = runTest {
        val h = harness()
        val transport = FakeTransport(isConnectionOriented = false)
        h.connection.connect(transport)
        runCurrent()
        assertEquals(Connected, h.connection.state.value)
        val link = transport.links.single()

        link.sendFailure = DatagramTooLargeException(70_000, 65_507)
        assertEquals(SendResult.REFUSED, h.connection.send(heartbeat()))
        assertEquals(Connected, h.connection.state.value)
        assertEquals(listOf(SendRefused("HEARTBEAT")), h.events)

        val unreachable = IOException("Network is unreachable")
        link.sendFailure = unreachable
        assertEquals(SendResult.FAILED, h.connection.send(heartbeat()))
        assertEquals(Disconnected(1, 2_000), h.connection.state.value)
        assertEquals(LinkFailed(unreachable), h.events[1])
    }

    @Test
    fun `An over-long line is reported and the link rebuilt (NFR-REL-02)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        transport.links.single().fail(LineTooLongException(1 shl 20))
        runCurrent()
        assertEquals(listOf(LineTooLong, ReconnectScheduled(1, 2_000)), h.events)
        assertEquals(Disconnected(1, 2_000), h.connection.state.value)
    }

    @Test
    fun `A request for an ACK is answered to the original sender (PROTOCOL 6)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        val link = transport.links.single()
        link.receive("CONTACT;2B;0195238E25AD;FE2A;U;TRUE;;C1")
        runCurrent()

        val ack = link.sentOfType("ACKNOWLEDGE").single()
        assertTrue(ack, ack.matches(Regex("ACKNOWLEDGE;00;[0-9A-F]+;MESE;U;;;FE2A;CONTACT;2B")))
        assertEquals(1, h.lines.size)
    }

    @Test
    fun `A wrong MAC is reported and the line dropped`() = runTest {
        val h = harness(password = "geheim")
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        val link = transport.links.single()
        link.receive("HEARTBEAT;00;01A19BD5FCD0;ORKA;U;;CBD70E18")
        link.receive("HEARTBEAT;00;01A19BD5FCD0;MESE;U;;CBD70E18")
        runCurrent()

        assertEquals(listOf(MacRejected("HEARTBEAT;00;01A19BD5FCD0;ORKA;U;;CBD70E18")), h.events)
        assertEquals(listOf("HEARTBEAT;00;01A19BD5FCD0;MESE;U;;CBD70E18"), h.lines)
    }

    @Test
    fun `TIMESYNC is requested on connect and every 300 s, only with the clock enabled`() = runTest {
        val h = harness()
        h.connection.clock.isEnabled = true
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        val link = transport.links.single()
        val request = link.sentOfType("TIMESYNC").single()
        assertEquals("00", wireField(request, 1))
        assertEquals("request without timestamp", "", wireField(request, 7))

        advance(300_000)
        assertEquals(2, link.sentOfType("TIMESYNC").size)
        h.connection.clock.isEnabled = false
        advance(600_000)
        assertEquals(2, link.sentOfType("TIMESYNC").size)
    }

    @Test
    fun `The network gets 10 s of grace before the link is rebuilt (FR-CON-07)`() = runTest {
        val h = harness()
        val transport = FakeTransport()
        h.connection.connect(transport)
        runCurrent()
        val link = transport.links.single()

        h.connection.networkLost()
        runCurrent()
        advance(9_999)
        h.connection.networkAvailable()
        advance(60_000)
        assertEquals(Connected, h.connection.state.value)
        assertFalse(link.isClosed)
        assertEquals(1, transport.opens)

        h.connection.networkLost()
        runCurrent()
        advance(10_000)
        assertTrue(link.isClosed)
        assertEquals(Disconnected(1, 2_000), h.connection.state.value)
        val cause = (h.events.first() as LinkFailed).cause
        assertTrue(cause is NetworkLostException)
    }
}
