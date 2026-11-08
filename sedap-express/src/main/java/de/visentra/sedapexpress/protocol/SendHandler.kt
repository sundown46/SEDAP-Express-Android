package de.visentra.sedapexpress.protocol

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.visentra.sedapexpress.wire.MessageAuthenticator
import java.nio.charset.StandardCharsets

/** Sender name and classification of the active profile, used for messages the library creates itself. */
public data class Identity(val sender: String, val classification: SEDAPExpressMessage.Classification)

/** Direction of a line in the message log. */
public enum class Direction { SENT, RECEIVED }

/** Receives every line once, as sent or as received (after decompression, before the MAC check). */
public fun interface MessageLogSink {
    public fun record(direction: Direction, line: String)
}

/**
 * Central outgoing pipeline. Assigns the header fields the library owns, Number (one 7-bit counter per
 * message type, PROTOCOL §2) and Time (from [SedapClock]), then the MAC if a password is set.
 * Callers fill in every business field and only call [prepare] when the line will really be sent,
 * because each successful call uses up a number. Not thread-safe: call it from the connection's
 * serial dispatcher only.
 */
public class SendHandler(
    private val clock: SedapClock,
    private val macPassword: () -> String?,
    private val log: MessageLogSink? = null,
) {

    /** Next number per message type. Survives reconnects; only a new SendHandler starts again at 00. */
    private val counters = HashMap<SEDAPExpressMessage.MessageType, Int>()

    /**
     * Number, time, MAC; returns the wire line without "\n", or null if not ISO-8859-1 (no number used).
     * The line is recorded in the log here, so the caller must hand it to the transport.
     */
    public fun prepare(message: SEDAPExpressMessage): String? {
        // The wire only allows ISO-8859-1 (PROTOCOL §1). Checked before a number is used.
        if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(message.toString())) return null

        message.number = nextNumber(message.messageType).toByte()
        message.time = clock.nowMillis()

        val password = macPassword()
        if (password.isNullOrEmpty()) {
            message.mac = null
        } else {
            message.mac = MAC_PLACEHOLDER
            message.mac = MessageAuthenticator.compute(message.toString(), password)
        }

        // Upstream's serialize() is toString() plus "\n"; the transport adds the terminator.
        val line = message.toString()
        log?.record(Direction.SENT, line)
        return line
    }

    private fun nextNumber(type: SEDAPExpressMessage.MessageType): Int {
        val current = counters[type] ?: 0
        counters[type] = (current + 1) % NUMBER_RANGE
        return current
    }

    private companion object {
        const val NUMBER_RANGE = 0x80
        const val MAC_PLACEHOLDER = "0000"
    }
}
