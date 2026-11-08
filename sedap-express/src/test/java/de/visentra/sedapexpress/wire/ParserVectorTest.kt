package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.ACKNOWLEDGE
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** All parse and text_kind vectors, without the deviations the upstream suite has to allow. */
class ParserVectorTest {

    @Test
    fun parseVectors() {
        val cases = Vectors.root.getJSONArray("parse")
        val failures = mutableListOf<String>()
        var rejects = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = "parse[$i] ${c.getString("type")}"
            val lookup = c.optJSONObject("reference")?.let { ref ->
                val position = ReferencePosition(ref.getDouble("lat"), ref.getDouble("lon"), ref.optNullableDouble("alt"))
                ReferenceLookup { position }
            } ?: ReferenceLookup.NONE
            val inbound = SedapParser.parse(c.getString("line"), lookup)
            if (c.optBoolean("reject")) {
                rejects++
                if (inbound != null) failures += "$id: accepted, should reject"
                continue
            }
            if (inbound == null) {
                failures += "$id: rejected, should parse"
                continue
            }
            val expect = c.getJSONObject("expect")
            for (field in expect.keys()) {
                val wanted = normalize(expect.get(field))
                val actual = try {
                    normalize(field(inbound.message, field))
                } catch (e: Exception) {
                    "threw $e"
                }
                if (wanted != actual) failures += "$id .$field: expected <$wanted> but got <$actual>"
            }
        }
        assertEquals(7, rejects)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun relativePointVectorGetsResolvedPosition() {
        val c = (0 until Vectors.root.getJSONArray("parse").length())
            .map { Vectors.root.getJSONArray("parse").getJSONObject(it) }
            .single { it.has("reference") }
        val reference = ReferencePosition(53.0, 8.0, 0.0)
        val inbound = SedapParser.parse(c.getString("line")) { reference }
        assertEquals(PositionResolver.resolve(100.0, 130.0, 0.0, reference), inbound?.position)
    }

    @Test
    fun textKindVectors() {
        val read = Vectors.root.getJSONObject("text_kind").getJSONArray("read")
        for (i in 0 until read.length()) {
            val c = read.getJSONObject(i)
            val wire = c.getString("wire")
            val inbound = SedapParser.parse("TEXT;01;01A19BD5FCD0;MESE;U;;;;$wire;NONE;x")
            val message = inbound?.message as? TEXT
            assertEquals("text_kind.read[$wire]", c.getString("kind"), message?.type?.name?.lowercase(Locale.ROOT))
        }
        val write = Vectors.root.getJSONObject("text_kind").getJSONArray("write")
        for (i in 0 until write.length()) {
            val c = write.getJSONObject(i)
            val type = TEXT.TextType.entries.single { it.name.lowercase(Locale.ROOT) == c.getString("kind") }
            assertEquals(c.getString("wire"), type.type.toString())
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)

    // Upstream keeps empty text fields as null (L0 report, "Darstellung"); on the wire both are empty.
    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL, "" -> null
        is CharArray -> String(value)
        is Number -> value.toDouble()
        else -> value
    }

    private fun field(message: SEDAPExpressMessage, name: String): Any? = when (name) {
        "number" -> message.number?.let { SEDAPExpressMessage.HexFormater.toHexDigits(it) }
        "classification" -> message.classification?.classification?.toString()
        "acknowledgement" -> message.acknowledgement?.name
        "mac" -> message.mac
        "comment_decoded" -> getter(message, "getComment")
        "text_decoded" -> (message as TEXT).textContent
        "kind" -> (message as TEXT).type.name.lowercase(Locale.ROOT)
        "typeOfMessage" -> (message as ACKNOWLEDGE).typeOfTheMessage.name
        "numberOfMessage" -> SEDAPExpressMessage.HexFormater.toHexDigits((message as ACKNOWLEDGE).numberOfTheMessage)
        "source" -> (getter(message, "getSource") as Collection<*>).map { it.toString() }.sorted().joinToString("")
        "deleteFlag" -> getter(message, "getDeleteFlag").toString() == "TRUE"
        else -> getter(message, "get" + name.replaceFirstChar { it.uppercaseChar() })
    }

    private fun getter(target: Any, name: String): Any? =
        target.javaClass.methods.single { it.name.equals(name, ignoreCase = true) && it.parameterCount == 0 }.invoke(target)
}
