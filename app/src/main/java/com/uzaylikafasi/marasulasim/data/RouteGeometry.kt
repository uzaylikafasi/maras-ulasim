package com.uzaylikafasi.marasulasim.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

fun distanceMeters(a: Coordinate, b: Coordinate): Double {
    val y = Math.toRadians(b.latitude - a.latitude)
    val x = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
    return hypot(x, y) * 6_371_000
}

/** Kaynağın yol çizgisine eklediği kısa durak gidiş-gelişlerini temizler; gerçek dönüşler korunur. */
fun cleanRoute(points: List<Coordinate>, stops: List<Stop>): List<Coordinate> {
    val unique = points.fold(mutableListOf<Coordinate>()) { result, p ->
        if (result.lastOrNull()?.let { distanceMeters(it, p) < 0.5 } != true) result.add(p)
        result
    }
    val stopPoints = stops.mapNotNull { s -> s.latitude?.let { lat -> s.longitude?.let { Coordinate(lat, it) } } }
    val result = mutableListOf<Coordinate>()
    unique.forEachIndexed { index, p ->
        val before = result.lastOrNull()
        val after = unique.getOrNull(index + 1)
        if (before != null && after != null && stopPoints.any { distanceMeters(it, p) < 2 }) {
            // Only a short spur at a known stop: never simplify arbitrary corners or long loops.
            val scale = cos(Math.toRadians(p.latitude))
            val ax = (before.longitude - p.longitude) * scale; val ay = before.latitude - p.latitude
            val bx = (after.longitude - p.longitude) * scale; val by = after.latitude - p.latitude
            val dot = ax * bx + ay * by
            val dx = bx - ax; val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared > 0) -(ax * dx + ay * dy) / lengthSquared else -1.0
            val deviation = if (lengthSquared > 0) abs(ax * by - ay * bx) / sqrt(lengthSquared) * 111_195 else 0.0
            val nearStraight = dot < -0.75 * hypot(ax, ay) * hypot(bx, by)
            if ((dot > 0 && min(distanceMeters(before, p), distanceMeters(p, after)) < 35) ||
                (nearStraight && t in 0.0..1.0 && deviation < 15)) return@forEachIndexed
        }
        if (result.lastOrNull()?.let { distanceMeters(it, p) < 0.5 } != true) result.add(p)
    }
    return result
}

fun renderedShape(shape: JSONObject, stops: List<Stop>): JSONObject {
    val points = cleanRoute(parseShape(shape), stops)
    return JSONObject().put("type", "Feature").put("properties", shape.optJSONObject("properties") ?: JSONObject())
        .put("geometry", if (points.size < 2) JSONObject.NULL else JSONObject().put("type", "LineString")
            .put("coordinates", JSONArray(points.map { listOf(it.longitude, it.latitude) })))
}

/** Aynı terminalin iki peronu farklı durak kimlikleri taşıyabilir. */
fun sameTerminal(a: Stop, b: Stop): Boolean = a.id == b.id ||
    (a.latitude != null && a.longitude != null && b.latitude != null && b.longitude != null &&
        distanceMeters(Coordinate(a.latitude, a.longitude), Coordinate(b.latitude, b.longitude)) <= 100)

/** Kaynak ETA'sı daima öncelikli. Yalnız terminalde olduğu doğrulanan araç için ek durum. */
fun terminalArrivals(stop: Stop, line: Line, stops: List<Stop>, incomingStops: List<Stop>, vehicles: List<Vehicle>, existing: List<Arrival>): List<Arrival> {
    val index = stops.indexOfFirst { it.id == stop.id }
    val terminal = stops.firstOrNull() ?: return emptyList()
    val end = incomingStops.lastOrNull() ?: return emptyList()
    if (index !in 0..2 || !sameTerminal(terminal, end) || terminal.latitude == null || terminal.longitude == null) return emptyList()
    if (end.latitude == null || end.longitude == null) return emptyList()
    val at = Coordinate(terminal.latitude, terminal.longitude)
    val endAt = Coordinate(end.latitude, end.longitude)
    val terminalIds = setOf(terminal.id, end.id)
    return vehicles.filter { v ->
        v.plate.isNotBlank() && existing.none { it.code == line.code && it.plate == v.plate } &&
            v.speed == 0.0 && min(distanceMeters(v.coordinate, at), distanceMeters(v.coordinate, endAt)) <= 60 &&
            (v.previousStopId in terminalIds || (v.nextStopId in terminalIds &&
                min(distanceMeters(v.coordinate, at), distanceMeters(v.coordinate, endAt)) <= 25))
    }.distinctBy { it.plate }.map { v ->
        Arrival(line.code, v.plate, "Kalkış bekleniyor", v.nextStop.ifBlank { terminal.name }, name = line.name,
            direction = line.directionName, state = "${line.directionName} başlangıcında · Bu durağa varış süresi henüz bildirilmedi")
    }
}
