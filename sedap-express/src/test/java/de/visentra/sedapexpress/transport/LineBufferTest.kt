package de.visentra.sedapexpress.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.ISO_8859_1

class LineBufferTest {

    private fun bytes(text: String) = text.toByteArray(ISO_8859_1)

    private fun LineBuffer.feed(text: String) = bytes(text).let { append(it, it.size) }

    @Test
    fun `complete lines come out in order`() {
        val buffer = LineBuffer(1024)
        assertEquals(listOf("HEARTBEAT;01", "TEXT;02"), buffer.feed("HEARTBEAT;01\nTEXT;02\n"))
    }

    @Test
    fun `a line split over two packets is joined`() {
        val buffer = LineBuffer(1024)
        assertEquals(emptyList<String>(), buffer.feed("CONTACT;0"))
        assertEquals(listOf("CONTACT;01;abc", "NEXT"), buffer.feed("1;abc\nNEXT\nREST"))
        assertEquals(listOf("REST"), buffer.feed("\n"))
    }

    @Test
    fun `an umlaut byte at the packet boundary stays intact`() {
        val buffer = LineBuffer(1024)
        val line = byteArrayOf('M'.code.toByte(), 0xE4.toByte(), 'r'.code.toByte(), 0x0A)
        assertEquals(emptyList<String>(), buffer.append(line.copyOfRange(0, 2), 2))
        assertEquals(listOf("Mär"), buffer.append(line.copyOfRange(2, 4), 2))
    }

    @Test
    fun `every byte decodes as ISO-8859-1`() {
        val all = ByteArray(256) { it.toByte() }.filter { it != 0x0A.toByte() }.toByteArray() + 0x0A.toByte()
        val lines = LineBuffer(1024).append(all, all.size)
        assertEquals(1, lines.size)
        assertArrayEquals(all.copyOf(all.size - 1), lines[0].toByteArray(ISO_8859_1))
    }

    @Test
    fun `CR stays part of the line`() {
        val buffer = LineBuffer(1024)
        assertEquals(listOf("A;1\r", "B\rC"), buffer.feed("A;1\r\nB\rC\n"))
    }

    @Test
    fun `empty lines are passed through`() {
        val buffer = LineBuffer(1024)
        assertEquals(listOf("", "A", ""), buffer.feed("\nA\n\n"))
    }

    @Test
    fun `only the given length is read`() {
        val buffer = LineBuffer(1024)
        val data = bytes("A\nB\nGARBAGE")
        assertEquals(listOf("A"), buffer.append(data, 2))
        assertEquals(emptyList<String>(), buffer.append(data, 0))
    }

    @Test
    fun `a line of exactly the limit passes, in one piece and in many`() {
        val max = 1 shl 20
        val line = ByteArray(max) { 'X'.code.toByte() }
        assertEquals(listOf(String(line, ISO_8859_1)), LineBuffer(max).append(line + 0x0A.toByte(), max + 1))

        val buffer = LineBuffer(max)
        val chunk = 64 * 1024
        for (start in 0 until max step chunk) {
            assertEquals(emptyList<String>(), buffer.append(line.copyOfRange(start, start + chunk), chunk))
        }
        assertEquals(listOf(String(line, ISO_8859_1)), buffer.append(byteArrayOf(0x0A), 1))
    }

    @Test
    fun `one byte over the limit throws without waiting for the line feed`() {
        val max = 1 shl 20
        val buffer = LineBuffer(max)
        val line = ByteArray(max) { 'X'.code.toByte() }
        assertEquals(emptyList<String>(), buffer.append(line, max))
        val error = assertThrows(LineTooLongException::class.java) { buffer.append(byteArrayOf('X'.code.toByte()), 1) }
        assertEquals(max, error.maxLineBytes)
    }

    @Test
    fun `an over-long line inside one packet throws and keeps the lines before it`() {
        assertThrows(LineTooLongException::class.java) { LineBuffer(4).feed("ABCDE\n") }
        val error = assertThrows(LineTooLongException::class.java) { LineBuffer(4).feed("AB\nCD\nABCDE") }
        assertEquals(listOf("AB", "CD"), error.linesBefore)
    }

    @Test
    fun `the limit counts the pending part too`() {
        val buffer = LineBuffer(4)
        assertEquals(emptyList<String>(), buffer.feed("AB"))
        assertEquals(listOf("ABCD"), buffer.feed("CD\n"))
        buffer.feed("ABC")
        assertThrows(LineTooLongException::class.java) { buffer.feed("DE\n") }
    }

    @Test
    fun `a datagram may carry several lines and an unterminated rest`() {
        val data = bytes("A;1\nB;2\r\nC;3")
        assertEquals(listOf("A;1", "B;2\r", "C;3"), splitDatagram(data, data.size))
        assertEquals(listOf("A;1", "B;2\r"), splitDatagram(data, 9))
        assertEquals(emptyList<String>(), splitDatagram(data, 0))
        val terminated = bytes("X\n")
        assertEquals(listOf("X"), splitDatagram(terminated, terminated.size))
    }

    @Test
    fun `encoding appends LF and refuses non-Latin-1 instead of writing a question mark`() {
        assertArrayEquals(byteArrayOf('M'.code.toByte(), 0xFC.toByte(), 0x0A), encodeLine("Mü"))
        assertThrows(CharacterCodingException::class.java) { encodeLine("Ł") }
    }
}
