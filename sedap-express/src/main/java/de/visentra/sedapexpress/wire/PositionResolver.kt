package de.visentra.sedapexpress.wire

import kotlin.math.PI
import kotlin.math.cos

/** Flat-earth conversion of relative east/north/up distances in metres (PROTOCOL §6). */
public object PositionResolver {

    private const val EARTH_RADIUS = 6_371_000.0

    public fun resolve(x: Double, y: Double, z: Double?, reference: ReferencePosition): GeoPosition {
        val latRad = reference.latitude * PI / 180
        val deltaLat = (y / EARTH_RADIUS) * (180 / PI)
        val deltaLon = (x / (EARTH_RADIUS * cos(latRad))) * (180 / PI)
        val altitude = z?.let { (reference.altitude ?: 0.0) + it }
        return GeoPosition(reference.latitude + deltaLat, reference.longitude + deltaLon, altitude)
    }
}
