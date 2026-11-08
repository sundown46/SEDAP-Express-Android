package de.visentra.sedapexpress.transport

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.ISO_8859_1

private const val LINE_FEED: Byte = 0x0A

/**
 * Splits a byte stream into lines (PROTOCOL §1). It works on bytes and splits only at 0x0A, so a
 * chunk boundary can never break a character, and a "\r" stays part of the line (PROTOCOL §8).
 * Each complete line is decoded as ISO-8859-1, which maps every byte.
 *
 * Bytes without a "\n" are kept for the next [append], at most [maxLineBytes] of them. Not
 * thread-safe: one buffer per link, fed by its single reader.
 */
public class LineBuffer(private val maxLineBytes: Int) {

    init {
        require(maxLineBytes > 0) { "maxLineBytes must be positive" }
    }

    private var pending = ByteArray(minOf(maxLineBytes, 8 * 1024))
    private var pendingLength = 0

    /**
     * Appends the first [length] bytes of [bytes] and returns the lines completed by them, in order.
     * Throws [LineTooLongException] as soon as a line exceeds [maxLineBytes] (the "\n" not counted);
     * the complete lines before it are in [LineTooLongException.linesBefore].
     */
    public fun append(bytes: ByteArray, length: Int): List<String> {
        require(length in 0..bytes.size) { "length out of range" }
        var lines: ArrayList<String>? = null
        var start = 0
        var index = 0
        while (index < length) {
            if (bytes[index] == LINE_FEED) {
                val segment = index - start
                checkLength(pendingLength + segment, lines)
                val line = if (pendingLength == 0) {
                    String(bytes, start, segment, ISO_8859_1)
                } else {
                    keep(bytes, start, segment)
                    String(pending, 0, pendingLength, ISO_8859_1).also { pendingLength = 0 }
                }
                (lines ?: ArrayList<String>().also { lines = it }).add(line)
                start = index + 1
            }
            index++
        }
        val rest = length - start
        if (rest > 0) {
            checkLength(pendingLength + rest, lines)
            keep(bytes, start, rest)
        }
        return lines ?: emptyList()
    }

    private fun checkLength(lineBytes: Int, linesBefore: List<String>?) {
        if (lineBytes > maxLineBytes) {
            throw LineTooLongException(maxLineBytes).also { it.linesBefore = linesBefore.orEmpty() }
        }
    }

    private fun keep(bytes: ByteArray, start: Int, count: Int) {
        val needed = pendingLength + count
        if (needed > pending.size) {
            var size = pending.size
            while (size < needed) size = if (size > maxLineBytes / 2) maxLineBytes else size * 2
            pending = pending.copyOf(size)
        }
        System.arraycopy(bytes, start, pending, pendingLength, count)
        pendingLength = needed
    }
}

/**
 * Splits one datagram into lines. A datagram may carry several lines (ICD §4), and a rest without a
 * trailing "\n" counts as a complete line, because nothing follows it.
 */
internal fun splitDatagram(bytes: ByteArray, length: Int): List<String> {
    val lines = ArrayList<String>()
    var start = 0
    for (index in 0 until length) {
        if (bytes[index] == LINE_FEED) {
            lines.add(String(bytes, start, index - start, ISO_8859_1))
            start = index + 1
        }
    }
    if (start < length) lines.add(String(bytes, start, length - start, ISO_8859_1))
    return lines
}

/**
 * Encodes [line] plus "\n" as ISO-8859-1. The line is checked before it gets a number, so this only
 * guards against misuse: it throws [java.nio.charset.CharacterCodingException] (an IOException)
 * instead of silently writing "?" like [String.toByteArray] would (PROTOCOL §8).
 */
internal fun encodeLine(line: String): ByteArray {
    val buffer = ISO_8859_1.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(line))
    val bytes = ByteArray(buffer.remaining() + 1)
    buffer.get(bytes, 0, buffer.remaining())
    bytes[bytes.size - 1] = LINE_FEED
    return bytes
}
