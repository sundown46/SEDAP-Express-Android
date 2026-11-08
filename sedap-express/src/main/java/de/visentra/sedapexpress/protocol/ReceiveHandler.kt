package de.visentra.sedapexpress.protocol

import de.visentra.sedapexpress.wire.Decompressor
import de.visentra.sedapexpress.wire.MessageAuthenticator
import de.visentra.sedapexpress.wire.SedapParser
import java.util.Locale

/** A received line asked for an ACKNOWLEDGE (PROTOCOL §6): recipient is the original sender. */
public data class AckRequest(val recipient: String, val messageType: String, val messageNumber: String)

/**
 * Central incoming pipeline (PROTOCOL §3): decompresses lines whose first field is no known type,
 * records each line once, checks the MAC if a password is set, reports acknowledgement requests and
 * hands the plaintext line to every subscriber.
 *
 * It deliberately does not parse the line for its subscribers: relative positions need context only
 * the consumer has. Not thread-safe: call it from the connection's serial dispatcher only.
 */
public class ReceiveHandler(
    private val macPassword: () -> String?,
    private val ownSender: () -> String?,
    private val log: MessageLogSink? = null,
) {

    private val subscribers = ArrayList<(String) -> Unit>()

    /** Called for lines that need an ACKNOWLEDGE. Whether one is sent (only while connected) is up to the caller. */
    public var onAcknowledgementRequested: ((AckRequest) -> Unit)? = null

    /** Called with each line dropped because its MAC did not verify. */
    public var onMacRejected: ((line: String) -> Unit)? = null

    /** Number of lines dropped because of a wrong or missing MAC. */
    public var macRejectedCount: Int = 0
        private set

    /** Adds a listener for every plaintext line from now on. No unsubscribe: consumers live as long as the handler. */
    public fun subscribe(listener: (String) -> Unit) {
        subscribers.add(listener)
    }

    /** Feeds one raw line without terminator from the transport into the pipeline. */
    public fun handle(rawLine: String) {
        val known = isKnownType(rawLine)
        // A line that is neither a known type nor compressed data is passed on as it is; parsers drop it.
        val line = if (known) rawLine else Decompressor.decompress(rawLine) ?: rawLine

        log?.record(Direction.RECEIVED, line)

        val password = macPassword()
        if (!password.isNullOrEmpty() && !MessageAuthenticator.verify(line, password)) {
            macRejectedCount++
            onMacRejected?.invoke(line)
            return
        }

        ackRequest(line)?.let { request -> onAcknowledgementRequested?.invoke(request) }

        subscribers.forEach { it(line) }
    }

    /** PROTOCOL §6 with the exceptions from IOS-05. */
    private fun ackRequest(line: String): AckRequest? {
        val fields = line.split(';')
        fun field(index: Int) = fields.getOrElse(index) { "" }

        if (!field(ACK_INDEX).equals("TRUE", ignoreCase = true)) return null
        val type = field(0).uppercase(Locale.ROOT)
        if (type !in SedapParser.KNOWN_TYPES) return null
        if (type == "ACKNOWLEDGE" || type == "HEARTBEAT") return null
        val number = field(1)
        if (number.isEmpty()) return null
        val sender = field(SENDER_INDEX)
        val own = ownSender()
        if (own != null && sender == own) return null
        if (type == "TEXT") {
            // An empty recipient addresses everyone.
            val recipient = field(TEXT_RECIPIENT_INDEX)
            if (recipient.isNotEmpty() && recipient != own) return null
        }
        return AckRequest(recipient = sender, messageType = type, messageNumber = number)
    }

    private fun isKnownType(line: String): Boolean =
        line.substringBefore(';').uppercase(Locale.ROOT) in SedapParser.KNOWN_TYPES

    private companion object {
        const val SENDER_INDEX = 3
        const val ACK_INDEX = 5
        const val TEXT_RECIPIENT_INDEX = 7
    }
}
