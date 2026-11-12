package com.uzaylikafasi.marasulasim.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

// Android 8–10's Instant.parse does not accept every offset accepted by modern JVMs.
fun fetchedInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

data class Line(val id: Long, val direction: Int, val code: String, val name: String, val directionName: String) {
    val key get() = "$id:$direction"
    fun json() = JSONObject().put("id", id).put("direction", direction).put("code", code)
        .put("name", name).put("directionName", directionName)
}
data class Stop(val id: Long, val code: String, val name: String, val latitude: Double?, val longitude: Double?) {
    fun json() = JSONObject().put("id", id).put("stopCode", code).put("name", name)
        .put("latitude", latitude).put("longitude", longitude)
}
data class Coordinate(val latitude: Double, val longitude: Double)
data class Vehicle(val plate: String, val coordinate: Coordinate, val nextStop: String, val angle: Double? = null,
                   val previousStopId: Long? = null, val nextStopId: Long? = null, val speed: Double? = null)
data class Arrival(val code: String, val plate: String, val remaining: String, val nextStop: String, val distance: String = "",
                   val name: String = "", val direction: String = "", val state: String = "", val seconds: Int? = null, val lineId: String = "", val nextStopId: Long = 0)
/** Ölçülmemiş durak payı; kullanıcı seçerse kaynak tahmine eklenir. */
const val SECONDS_PER_STOP = 15
/** Kaynak süreye eklenecek pay (sn): ara durak başına [SECONDS_PER_STOP], yoldaki ışık başına [LIGHT_WAIT_SECONDS]. */
fun padSeconds(stopsBetween: Int?, stopPad: Boolean, lights: Int, lightPad: Boolean): Int =
    (if (stopPad) (stopsBetween ?: 0) * SECONDS_PER_STOP else 0) + (if (lightPad) lights * LIGHT_WAIT_SECONDS else 0)

/**
 * Varış kartının küçük alt satırı: üç bağımsız ayar (kaynak süresi, durak payı, ışık payı) kendi parçasını
 * ekler ya da eklemez. Hiçbiri açık değilse (ya da kaynak süresi henüz okunamadıysa) boş liste döner.
 */
fun arrivalSourceParts(remaining: String, hasBase: Boolean, showSourceTime: Boolean, stopsBetween: Int?, stopPad: Boolean, lightsAhead: Int, lightPad: Boolean): List<String> {
    if (!hasBase) return emptyList() // Sayisal sure yoksa ("Kalkis bekleniyor" gibi) pay bilgisi anlamsiz.
    return listOfNotNull(
        "Kaynak $remaining".takeIf { showSourceTime },
        stopsBetween?.takeIf { stopPad && it > 0 }?.let { "$it durak × $SECONDS_PER_STOP sn" },
        lightsAhead.takeIf { lightPad && it > 0 }?.let { "$it ışık × $LIGHT_WAIT_SECONDS sn" })
}
fun secondsOf(a: Arrival): Int? = a.seconds?.takeIf { it >= 0 } ?: Regex("""(?:(\d+)\s*(?:dk|dakika))?\s*(?:(\d+)\s*(?:sn|saniye))?""").matchEntire(a.remaining.trim())?.let { m ->
    val dk = m.groupValues[1].ifBlank { "0" }.toLongOrNull() ?: return@let null
    val sn = m.groupValues[2].ifBlank { "0" }.toLongOrNull() ?: return@let null
    if (m.groupValues[1].isBlank() && m.groupValues[2].isBlank()) null
    else if (dk > Int.MAX_VALUE / 60L || sn > Int.MAX_VALUE) null
    else (dk * 60 + sn).takeIf { it <= Int.MAX_VALUE }?.toInt()
}
/** Aracın sıradaki durağından (dahil) bakılan durağa (hariç) kadar durup yolcu alacağı durak sayısı; sıra bulunamazsa null. */
fun stopsBetween(lineStopIds: List<Long>?, nextStopId: Long, targetId: Long): Int? {
    val ids = lineStopIds ?: return null
    val t = ids.indexOf(targetId); val n = ids.indexOf(nextStopId)
    return if (t >= 0 && n in 0..t) t - n else null
}
fun formatDuration(total: Int): String { val dk = total / 60; val sn = total % 60; return if (dk == 0) "$sn sn" else if (sn == 0) "$dk dk" else "$dk dk $sn sn" }

fun parseVehicles(a: JSONArray): List<Vehicle> = a.objects().mapNotNull { o ->
    val loc = o.optJSONObject("location") ?: return@mapNotNull null
    val lat = loc.optDouble("latitude", Double.NaN); val lon = loc.optDouble("longitude", Double.NaN)
    val plate = o.optString("plate").ifBlank { o.optString("busNo") }
    if (plate.isBlank() || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) null
    else Vehicle(plate, Coordinate(lat, lon), o.optString("nextStopName"),
        o.optDouble("directionAngle", Double.NaN).takeIf { it.isFinite() && it in 0.0..360.0 },
        o.optLong("previousStopId").takeIf { it > 0 }, o.optLong("nextStopId").takeIf { it > 0 },
        o.optDouble("speed", Double.NaN).takeIf { it.isFinite() && it >= 0 })
}
fun parseArrivals(a: JSONArray): List<Arrival> = a.objects().map { o ->
    Arrival(o.optString("busCode"), o.optString("plate"),
        o.optString("remaining_time").takeIf { it.isNotBlank() && it != "null" } ?: "Süre belirtilmedi",
        o.optString("nextStopName"), o.optString("remaining_distance").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
        o.optString("busName"), o.optString("directionName"), o.optString("state"),
        o.optInt("timeToStop", -1).takeIf { !o.isNull("timeToStop") && it >= 0 }, o.optString("lineId"), o.optLong("nextStopId", 0))
}
data class SearchResults(val lines: List<Line>, val stops: List<Stop>)
data class ApiResult(val data: Any, val fetchedAt: String?, val stale: Boolean = false,
                     val error: String? = null, val bundled: Boolean = false, val offline: Boolean = false) {
    val objectData get() = data as? JSONObject ?: throw DataException("Verinin biçimi değişmiş.")
    val arrayData get() = data as? JSONArray ?: throw DataException("Verinin biçimi değişmiş.")
    fun isOld(now: Instant = Instant.now(), maxAgeSeconds: Long = 45): Boolean =
        stale || offline || fetchedAt == null || runCatching {
            java.time.Duration.between(fetchedInstant(fetchedAt ?: ""), now).seconds !in -30..maxAgeSeconds
        }.getOrDefault(true)
}
open class DataException(message: String, cause: Throwable? = null) : Exception(message, cause)
/** Hatanın türü: çevrimdışı tahmin yalnız bağlantı/servis kesintisinde devreye girer, anahtar ve hız sınırı hatalarında asla. */
enum class FailureKind(val offlineEligible: Boolean) { NETWORK(true), SERVICE_DOWN(true), AUTH(false), RATE_LIMIT(false), OTHER(false) }
class ApiException(message: String, val kind: FailureKind, cause: Throwable? = null) : DataException(message, cause)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
    optJSONObject(index) ?: throw DataException("Verinin biçimi değişmiş.")
}
fun JSONArray.texts(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.array(name: String): JSONArray = when (val value = opt(name)) {
    null, JSONObject.NULL -> JSONArray()
    is JSONArray -> value
    else -> throw DataException("Verinin biçimi değişmiş.")
}
fun JSONObject.requiredArray(name: String) = optJSONArray(name) ?: throw DataException("Verinin biçimi değişmiş.")
fun parseLine(o: JSONObject): Line {
    val id = o.optLong("id", 0)
    val direction = o.optInt("direction", 0)
    if (id <= 0 || direction !in 1..3 || o.optString("code").isBlank()) throw DataException("Hat bilgisi eksik.")
    return Line(id, direction, o.getString("code"), o.optString("name"), o.optString("directionName"))
}
fun parseStop(o: JSONObject): Stop {
    val id = o.optLong("id", o.optLong("busStopId", 0))
    if (id <= 0) throw DataException("Durak bilgisi eksik.")
    val location = o.optJSONObject("location") ?: o
    val lat = location.optDouble("latitude", Double.NaN)
    val lon = location.optDouble("longitude", Double.NaN)
    val valid = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    return Stop(id, o.optString("stopCode", o.optString("code", o.optString("busStopCode"))),
        o.optString("name").ifBlank { o.optString("busStopName") }, if (valid) lat else null, if (valid) lon else null)
}
/** Yakın duraklar yanıtı: kaynak listeyi "nearestStops" adıyla verir. */
fun parseNearby(o: JSONObject): List<Stop> = (o.optJSONArray("nearestStops") ?: o.array("busStops")).objects().map(::parseStop)
/** Yakın durakların uzaklığı (metre), durak kimliğine göre. */
fun parseNearbyDistances(o: JSONObject): Map<Long, Int> = (o.optJSONArray("nearestStops") ?: org.json.JSONArray()).objects()
    .mapNotNull { s -> s.optLong("busStopId", 0).takeIf { it > 0 }?.let { id -> s.optInt("distance", -1).takeIf { it >= 0 }?.let { id to it } } }.toMap()
fun parseSearch(o: JSONObject) = SearchResults(o.array("busLines").objects().map(::parseLine),
    o.array("busStops").objects().map(::parseStop))

fun parseShape(o: JSONObject): List<Coordinate> {
    val geometry = o.optJSONObject("geometry") ?: return emptyList()
    if (geometry.optString("type") != "LineString") throw DataException("Güzergâh bilgisi değişmiş.")
    val a = geometry.requiredArray("coordinates")
    return (0 until a.length()).map {
        val p = a.getJSONArray(it)
        val lon = p.getDouble(0); val lat = p.getDouble(1)
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            throw DataException("Güzergâh koordinatları geçersiz.")
        Coordinate(lat, lon)
    }
}

fun decodeEnvelope(raw: String, stale: Boolean = false, error: String? = null): ApiResult {
    try {
        val o = JSONObject(raw)
        val data = o.get("data")
        if (data !is JSONObject && data !is JSONArray) throw DataException("Verinin biçimi değişmiş.")
        val meta = o.getJSONObject("meta")
        return ApiResult(data, meta.optString("fetched_at").takeIf { it.isNotBlank() && it != "null" },
            stale, error, meta.optBoolean("bundled"), meta.optBoolean("offline"))
    } catch (e: DataException) { throw e }
    catch (_: Exception) { throw DataException("Verinin biçimi değişmiş.") }
}

val suggestedLine = Line(9576, 1, "H/1", "OTOGAR · ORMAN · BOĞAZİÇİ · ÜNGÜT · ADLİYE", "Gidiş")

// ── Yolculuk planı ──
/** Kaynak yürüme adımlarını "Y" hat koduyla verir. */
fun isWalkingLeg(code: String?): Boolean = code.isNullOrBlank() || code == "Y"

/** Kaynak saatleri "08:44:59.9999999" gibi gelir: en yakın dakikaya yuvarlanır. */
fun plannedMinute(value: String): String? = runCatching {
    OffsetDateTime.parse(value).plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}.getOrNull()

data class JourneyLeg(val walking: Boolean, val code: String, val from: String, val to: String,
                      val depart: String?, val arrive: String?, val minutes: Int, val meters: Int)
data class JourneyRoute(val legs: List<JourneyLeg>) {
    val transfers get() = (legs.count { !it.walking } - 1).coerceAtLeast(0)
    val walkingMeters get() = legs.filter { it.walking }.sumOf { it.meters }
    val depart get() = legs.firstOrNull()?.depart
    val arrive get() = legs.lastOrNull()?.arrive
}

fun parseJourneyRoute(o: JSONObject): JourneyRoute = JourneyRoute(o.array("Lags").objects().map { leg ->
    val code = leg.optJSONObject("Line")?.optString("code")
    JourneyLeg(isWalkingLeg(code), code.orEmpty(), leg.optJSONObject("FS")?.optString("name").orEmpty(),
        leg.optJSONObject("TS")?.optString("name").orEmpty(), plannedMinute(leg.optString("DT")),
        plannedMinute(leg.optString("AT")), leg.optInt("Duration"), leg.optInt("Distance"))
})

/** Hat güzergâhının sınırları (harita kamerasını hatta getirmek için). */
fun shapeBounds(o: JSONObject): Pair<Coordinate, Coordinate>? = runCatching {
    val points = parseShape(o)
    if (points.size < 2) null
    else Coordinate(points.minOf { it.latitude }, points.minOf { it.longitude }) to
        Coordinate(points.maxOf { it.latitude }, points.maxOf { it.longitude })
}.getOrNull()

/** Haritada hat rengi koda göre: aynı hattın gidiş ve dönüşü aynı renkte. */
val lineColors = listOf("#2563EB", "#EA580C", "#9333EA", "#DB2777", "#16A34A", "#0891B2")
/** Haritadan bağımsız sabit hat rengi (rozetler için): aynı kod her ekranda aynı renkte. */
fun badgeColorFor(code: String): String = lineColors[Math.floorMod(code.hashCode(), lineColors.size)]
fun lineColorFor(code: String, codes: List<String>): String = lineColors[(codes.indexOf(code).coerceAtLeast(0)) % lineColors.size]

/** Haritada açık olan hat kodları (sırasıyla). */
fun Settings.visibleCodes(): List<String> = mapLines.filter { it.key in selected }.map { it.code }.distinct()
fun Settings.isOnMap(code: String): Boolean = mapLines.any { it.code == code && it.key in selected }


/** A missing collection is a schema error, not a successful empty journey. */
fun parseJourneys(o: JSONObject): List<JourneyRoute> {
    val error = o.optJSONObject("error")
    if (error != null && error.optInt("code", 0) != 0) throw DataException("Rota servisi sorguyu tamamlayamadı. Yeniden dene.")
    return o.requiredArray("data").objects().map { route ->
        if (route.requiredArray("Lags").length() == 0) throw DataException("Rota adımları okunamadı.")
        parseJourneyRoute(route)
    }
}

fun journeyPath(origin: Stop, destination: Stop, day: Int, hour: Int, minute: Int): String {
    require(origin.id != destination.id && day in 0..6 && hour in 0..23 && minute in 0..59)
    return "/v1/journeys?from_stop_id=${origin.id}&to_stop_id=${destination.id}&day=$day&hour=$hour&minute=$minute"
}

fun arrivalDetails(stop: Stop, arrival: Arrival): List<String> = listOf(
    "Hat: ${arrival.code} · ${arrival.name.ifBlank { "Adı bildirilmedi" }}",
    "Yön: ${arrival.direction.ifBlank { "Bildirilmedi" }}",
    "Plaka: ${arrival.plate.ifBlank { "Bildirilmedi" }}",
    "${stop.name} durağına: ${arrival.remaining}",
    "Uzaklık: ${arrival.distance.ifBlank { "Bildirilmedi" }}",
    "Sonraki durak: ${arrival.nextStop.ifBlank { "Bildirilmedi" }}"
) + listOfNotNull(arrival.state.takeIf { it.isNotBlank() })
