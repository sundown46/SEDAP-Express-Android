package de.visentra.sedapexpress.connection

import java.io.IOException

/** The five connection states of FR-CON-02. App and plugin translate them into UI text. */
public sealed interface ConnectionState {
    /** Nothing happened yet. */
    public data object Ready : ConnectionState

    /** A link is being opened. */
    public data object Connecting : ConnectionState

    /** A link is open; sending is possible. */
    public data object Connected : ConnectionState

    /** The link failed; attempt number [attempt] starts [retryInMillis] after this state was entered. */
    public data class Disconnected(val attempt: Int, val retryInMillis: Long) : ConnectionState

    /** Disconnected by [SedapConnection.disconnect]; stays so until the next [SedapConnection.connect] (FR-CON-05). */
    public data object ManuallyDisconnected : ConnectionState
}

/** Things worth reporting that are not a state. Emitted without replay; slow collectors lose the oldest events. */
public sealed interface ConnectionEvent {
    /** A received line was dropped because its MAC did not verify. */
    public data class MacRejected(val line: String) : ConnectionEvent

    /** A message was not sent: not connected, not ISO-8859-1, or too long for one datagram. No line went out. */
    public data class SendRefused(val messageType: String) : ConnectionEvent

    /**
     * The link or the attempt to open it failed; a reconnect follows. [cause] is null for EOF (the server
     * closed the connection) and [NetworkLostException] when the network stayed away for the grace period.
     */
    public data class LinkFailed(val cause: Throwable?) : ConnectionEvent

    /** A received line exceeded the limit (NFR-REL-02). Reported instead of [LinkFailed]; a reconnect follows. */
    public data object LineTooLong : ConnectionEvent

    /** Attempt number [attempt] starts in [delayMillis]. */
    public data class ReconnectScheduled(val attempt: Int, val delayMillis: Long) : ConnectionEvent
}

/** Timings of a [SedapConnection]. The defaults are the values of FR-CON-04, -07 and -12 and PROTOCOL §7. */
public data class ConnectionPolicy(
    val backoffBaseMillis: Long = 2_000,
    val backoffMaxMillis: Long = 60_000,
    val heartbeatIntervalMillis: Long = 20_000,
    val timeSyncIntervalMillis: Long = 300_000,
    val networkGraceMillis: Long = 10_000,
) {
    init {
        require(backoffBaseMillis > 0) { "backoffBaseMillis must be positive" }
        require(backoffMaxMillis >= backoffBaseMillis) { "backoffMaxMillis must not be below backoffBaseMillis" }
        require(heartbeatIntervalMillis > 0) { "heartbeatIntervalMillis must be positive" }
        require(timeSyncIntervalMillis > 0) { "timeSyncIntervalMillis must be positive" }
        require(networkGraceMillis >= 0) { "networkGraceMillis must not be negative" }
    }

    /** min(base · 2^(attempt−1), max), so 2, 4, 8 … 60 s with the defaults. Never overflows. */
    internal fun backoffMillis(attempt: Int): Long {
        var delay = backoffBaseMillis
        repeat((attempt - 1).coerceAtLeast(0)) {
            if (delay > backoffMaxMillis / 2) return backoffMaxMillis
            delay *= 2
        }
        return delay.coerceAtMost(backoffMaxMillis)
    }
}

/** Outcome of [SedapConnection.send]. */
public enum class SendResult {
    /** Handed to the transport. */
    SENT,

    /** No open link; nothing was sent and no number was used. */
    NOT_CONNECTED,

    /** Not sendable as it is (not ISO-8859-1, or too long for one datagram); the link stays open. */
    REFUSED,

    /** The transport failed while writing; a reconnect follows. */
    FAILED,
}

/** The network stayed away longer than [graceMillis] (FR-CON-07), so the link was given up. */
public class NetworkLostException(public val graceMillis: Long) :
    IOException("Network lost for more than $graceMillis ms")
