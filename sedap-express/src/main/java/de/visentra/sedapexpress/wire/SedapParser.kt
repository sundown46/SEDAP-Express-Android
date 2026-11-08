package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.CONTACT
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT
import de.bundeswehr.uniity.sedapexpress.messages.POINT
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.DataEncoding
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.DeleteFlag
import de.bundeswehr.uniity.sedapexpress.messages.TEXT
import java.util.Locale

/**
 * Gate for received plaintext lines: accepts what PROTOCOL §5 allows and hands it on as upstream message.
 *
 * The line is rewritten internally only where upstream is stricter than the ICD. The MAC check runs
 * before this, over the unchanged line.
 */
public object SedapParser {

    /** The 15 message names of the ICD (PROTOCOL §3). */
    public val KNOWN_TYPES: Set<String> = setOf(
        "OWNUNIT", "CONTACT", "POINT", "EMISSION", "METEO", "TEXT", "GRAPHIC", "COMMAND",
        "STATUS", "ACKNOWLEDGE", "RESEND", "GENERIC", "HEARTBEAT", "TIMESYNC", "KEYEXCHANGE",
    )

    private const val TEXT_TYPE = 8
    private const val TEXT_CONTENT = 10
    private const val CONTACT_COMMENT = 29
    private const val POINT_COMMENT = 26

    /** Returns null for unknown types, lines upstream rejects, and lines missing mandatory fields. */
    public fun parse(line: String, references: ReferenceLookup = ReferenceLookup.NONE): Inbound? {
        // Same split as upstream's splitMessage: empty fields kept, also at the end.
        val fields = line.split(';').toMutableList()
        val name = fields[0].uppercase(Locale.ROOT)
        if (name !in KNOWN_TYPES) return null
        fields[0] = name
        if (name == "TEXT" && fields.size > TEXT_TYPE) {
            // The ICD examples write the text type with two digits, upstream accepts only one.
            val type = fields[TEXT_TYPE]
            if (type.length == 2 && type[0] == '0' && type[1] in '0'..'4') fields[TEXT_TYPE] = type.substring(1)
        }

        val message = try {
            SEDAPExpressMessage.deserialize(fields.joinToString(";"))
        } catch (e: Exception) {
            null
        } ?: return null

        return when (message) {
            is OWNUNIT -> {
                val position = absolute(message.latitude, message.longitude, message.altitude) ?: return null
                Inbound(message, position)
            }
            is CONTACT -> {
                fields.getOrNull(CONTACT_COMMENT)?.takeIf { it.isNotEmpty() }
                    ?.let { message.comment = TextCodec.decodeBase64Text(it) }
                track(message, message.contactID, message.deleteFlag, message.latitude, message.longitude,
                    message.altitude, message.relativeXDistance, message.relativeYDistance,
                    message.relativeZDistance, references)
            }
            is POINT -> {
                fields.getOrNull(POINT_COMMENT)?.takeIf { it.isNotEmpty() }
                    ?.let { message.comment = TextCodec.decodeBase64Text(it) }
                track(message, message.pointID, message.deleteFlag, message.latitude, message.longitude,
                    message.altitude, message.relativeXDistance, message.relativeYDistance,
                    message.relativeZDistance, references)
            }
            is TEXT -> {
                val raw = fields.getOrNull(TEXT_CONTENT)
                if (message.encoding == DataEncoding.BASE64 && !raw.isNullOrBlank()) {
                    // Upstream reads the bytes as ISO-8859-1 only and drops invalid Base64.
                    message.textContent = TextCodec.decodeBase64Text(raw)
                }
                if (message.textContent.isNullOrEmpty()) null else Inbound(message, null)
            }
            else -> Inbound(message, null)
        }
    }

    private fun track(
        message: SEDAPExpressMessage,
        id: String?,
        deleteFlag: DeleteFlag?,
        latitude: Double?,
        longitude: Double?,
        altitude: Double?,
        relX: Double?,
        relY: Double?,
        relZ: Double?,
        references: ReferenceLookup,
    ): Inbound? {
        if (id.isNullOrBlank()) return null
        val position = absolute(latitude, longitude, altitude)
            ?: relative(message.sender.orEmpty(), altitude, relX, relY, relZ, references)
        return when {
            position != null -> Inbound(message, position)
            deleteFlag == DeleteFlag.TRUE -> Inbound(message, null)
            else -> null
        }
    }

    private fun absolute(latitude: Double?, longitude: Double?, altitude: Double?): GeoPosition? {
        val lat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        return GeoPosition(lat, lon, altitude?.takeIf { it.isFinite() })
    }

    private fun relative(
        sender: String,
        altitude: Double?,
        relX: Double?,
        relY: Double?,
        relZ: Double?,
        references: ReferenceLookup,
    ): GeoPosition? {
        val x = relX?.takeIf { it.isFinite() } ?: return null
        val y = relY?.takeIf { it.isFinite() } ?: return null
        val reference = references.referenceFor(sender) ?: return null
        val resolved = PositionResolver.resolve(x, y, relZ?.takeIf { it.isFinite() }, reference)
        // An absolute altitude wins over relZ. A result off the globe counts as no position.
        return absolute(resolved.latitude, resolved.longitude, altitude?.takeIf { it.isFinite() } ?: resolved.altitude)
    }
}
