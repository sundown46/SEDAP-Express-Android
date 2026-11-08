package de.visentra.sedapexpress.protocol

/** Device clock and monotonic clock that only move when a test says so. */
internal class FakeTimeSource(var wallMillis: Long = 1_726_000_000_000L, var monoNanos: Long = 5_000_000_000L) : TimeSource {
    override fun currentTimeMillis(): Long = wallMillis
    override fun monotonicNanos(): Long = monoNanos

    /** Both clocks move forward together, as they do without a clock jump. */
    fun advance(millis: Long) {
        wallMillis += millis
        monoNanos += millis * 1_000_000L
    }
}

internal class RecordingLog : MessageLogSink {
    val sent = ArrayList<String>()
    val received = ArrayList<String>()
    override fun record(direction: Direction, line: String) {
        if (direction == Direction.SENT) sent.add(line) else received.add(line)
    }
}

internal fun wireField(line: String, index: Int): String = line.split(';').getOrElse(index) { "" }
