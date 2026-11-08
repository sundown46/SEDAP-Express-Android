package de.visentra.sedapexpress.wire

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage

/** Position a relative CONTACT or POINT is measured from. */
public data class ReferencePosition(val latitude: Double, val longitude: Double, val altitude: Double?)

/** Absolute WGS84 position in degrees, altitude in metres. */
public data class GeoPosition(val latitude: Double, val longitude: Double, val altitude: Double?)

/** Who a relative position refers to: last OWNUNIT of that sender, else the own position. Consumer decides. */
public fun interface ReferenceLookup {
    public fun referenceFor(sender: String): ReferencePosition?

    public companion object {
        public val NONE: ReferenceLookup = ReferenceLookup { null }
    }
}

/** A message that passed the gate. position is absolute or resolved, null for TEXT etc. and for deletes without position. */
public class Inbound(public val message: SEDAPExpressMessage, public val position: GeoPosition?)
