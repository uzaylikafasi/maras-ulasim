package com.uzaylikafasi.marasulasim.data

/** Harita için yerel dizinden menzil seçimi. Arama/favori/yolculuk verisine dokunmaz. */
fun stopsInRange(stops: List<Stop>, center: Coordinate, radiusMeters: Double): List<Stop> {
    require(radiusMeters > 0 && radiusMeters.isFinite())
    require(center.latitude.isFinite() && center.longitude.isFinite())
    val located = stops.mapNotNull { s -> s.latitude?.let { lat -> s.longitude?.let { s to distanceMeters(center, Coordinate(lat, it)) } } }
    val valid=located.filter { it.second.isFinite() }
    if (valid.isEmpty()) return emptyList()
    var radius = radiusMeters
    while (true) {
        val found = valid.filter { it.second <= radius }.map { it.first }
        if (found.isNotEmpty()) return found
        radius *= 2
    }
}
const val LOCATION_INTERVAL_MS = 30_000L

// Haritada durak yüklenecek alan (kullanıcı Ayarlar'dan seçer): boş bölgede en yakın durak bulunana kadar otomatik büyür.
const val MIN_STOP_RANGE_METERS = 300
const val DEFAULT_STOP_RANGE_METERS = 750
const val MAX_STOP_RANGE_METERS = 3000
fun clampStopRange(value: Int): Int = value.coerceIn(MIN_STOP_RANGE_METERS, MAX_STOP_RANGE_METERS)
