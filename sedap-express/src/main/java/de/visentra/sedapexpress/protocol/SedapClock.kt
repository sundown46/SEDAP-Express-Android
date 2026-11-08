package de.visentra.sedapexpress.protocol

/** Wall clock and monotonic clock, replaceable in tests. */
public interface TimeSource {
    /** Unix epoch milliseconds of the device clock. */
    public fun currentTimeMillis(): Long

    /** Monotonic nanoseconds for measuring durations; unaffected by changes of the device clock. */
    public fun monotonicNanos(): Long

    public companion object {
        public val SYSTEM: TimeSource = object : TimeSource {
            override fun currentTimeMillis(): Long = System.currentTimeMillis()
            override fun monotonicNanos(): Long = System.nanoTime()
        }
    }
}

/**
 * The clock that stamps every outgoing message's Time field (PROTOCOL §7). It never touches the
 * device clock; only an internal offset learned through TIMESYNC is adjusted.
 *
 * - Disabled (default): [nowMillis] is the device clock and no TIMESYNC traffic happens at all.
 * - Enabled: [nowMillis] is the device clock plus the learned offset.
 */
public class SedapClock(private val time: TimeSource = TimeSource.SYSTEM) {

    private var offsetMillis: Long = 0

    /** Disabling resets the learned offset to 0. */
    public var isEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) offsetMillis = 0
        }

    /** The time this library treats as "now", in Unix epoch milliseconds. */
    public fun nowMillis(): Long = time.currentTimeMillis() + if (isEnabled) offsetMillis else 0

    /** Adopts a newly learned offset. A no-op while disabled, so a stray reply cannot change the clock. */
    public fun applySync(offsetMillis: Long) {
        if (!isEnabled) return
        this.offsetMillis = offsetMillis
    }
}
