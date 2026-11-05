package com.uzaylikafasi.marasulasim.data

/** Harita için yerel dizinden menzil seçimi. Arama/favori/yolculuk verisine dokunmaz. */
fun stopsInRange(stops: List<Stop>, center: Coordinate, radiusMeters: Double): List<Stop> {
    require(radiusMeters > 0 && radiusMeters.isFinite())
    val located = stops.mapNotNull { s -> s.latitude?.let { lat -> s.longitude?.let { s to distanceMeters(center, Coordinate(lat, it)) } } }
    if (located.isEmpty()) return emptyList()
    var radius = radiusMeters
    while (true) {
        val found = located.filter { it.second <= radius }.map { it.first }
        if (found.isNotEmpty()) return found
        radius *= 2
    }
}
const val LOCATION_INTERVAL_MS = 30_000L
