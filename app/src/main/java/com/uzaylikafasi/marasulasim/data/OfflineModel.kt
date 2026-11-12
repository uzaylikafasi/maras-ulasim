package com.uzaylikafasi.marasulasim.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val SUPPORTED_MODEL_SCHEMA = 1
val ISTANBUL: ZoneId = ZoneId.of("Europe/Istanbul")

data class TripCell(val seconds: Int, val samples: Int)
data class ModelStop(val id: Long, val code: String, val name: String, val latitude: Double, val longitude: Double)
class ModelLine(val id: Long, val direction: Int, val code: String, val name: String, val directionName: String,
                val stopIds: List<Long>, val shape: JSONArray?, val routeType: Int?, val cum: DoubleArray,
                val trip: Map<String, List<TripCell?>?>) {
    /** Kaynak adı "(Gidiş)" gibi yön ekiyle verir; ek olmadan. */
    val plainName get() = name.replace(Regex("""\s*\((Gidiş|Dönüş)\)\s*$"""), "").replace(Regex("""\s{2,}"""), " ").trim()
}

/** Tek tahmin: hat yönü, durak sırası ve (varsa) saniye cinsinden kalan süre. */
data class OfflineEstimate(val line: ModelLine, val stopIndex: Int, val seconds: Int?, val departure: String, val kind: Kind) {
    enum class Kind { LEARNED, TIMETABLE_START, DEPARTURE_ONLY }
}

class OfflineModel(val root: JSONObject, val version: String, val builtAt: String, val lines: List<ModelLine>, val stops: Map<Long, ModelStop>) {
    private val byId = lines.associateBy { it.id }
    private val stopLines: Map<Long, List<Pair<ModelLine, Int>>> = HashMap<Long, MutableList<Pair<ModelLine, Int>>>().also { m ->
        lines.forEach { l -> l.stopIds.forEachIndexed { i, id -> m.getOrPut(id) { mutableListOf() }.add(l to i) } }
    }
    private val timetables: JSONObject = root.optJSONObject("timetables") ?: JSONObject()
    fun line(id: Long) = byId[id]
    fun stop(id: Long) = stops[id]
    fun linesAt(stopId: Long): List<Pair<ModelLine, Int>> = stopLines[stopId].orEmpty()
    fun timetable(code: String): JSONArray = timetables.optJSONArray(code) ?: JSONArray()

    /** Hat yönünün tarifesinde gün tipine ait kalkış saatleri (gün başından dakika). */
    fun departures(line: ModelLine, dayType: String): List<Int> {
        val rt = line.routeType ?: return emptyList()
        val entries = timetable(line.code)
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (e.optInt("routeType", -1) != rt) continue
            val a = e.optJSONObject("lineSchedules")?.optJSONArray(dayType) ?: return emptyList()
            return (0 until a.length()).mapNotNull { parseClock(a.optString(it)) }.sorted()
        }
        return emptyList()
    }

    /**
     * Bir durağa yaklaşan tahminler. Öğrenilmiş hücre varsa: kalkış + sefer süresi × kümülatif pay.
     * Hücre yetersizse: ilk duraktaysa tarife kalkışı, değilse yalnız sıradaki kalkış saati (süre yok).
     * Araç konumu ya da plaka üretilmez.
     */
    fun arrivalsAt(stopId: Long, now: ZonedDateTime, horizonMinutes: Int = 90, perLine: Int = 2): List<OfflineEstimate> {
        val day = dayTypeOf(now)
        val nowSec = now.hour * 3600 + now.minute * 60 + now.second
        val out = mutableListOf<OfflineEstimate>()
        for ((line, index) in linesAt(stopId)) {
            if (index == line.stopIds.lastIndex && line.stopIds.size > 1) continue // son durakta yolcu alınmaz
            val deps = departures(line, day)
            val mine = mutableListOf<OfflineEstimate>()
            for (d in deps) {
                val depSec = d * 60
                val pass = passSeconds(line, day, depSec, index)
                if (pass != null) {
                    val remaining = pass - nowSec
                    if (remaining >= -30 && remaining <= horizonMinutes * 60) mine.add(OfflineEstimate(line, index, maxOf(0, remaining), clock(d), OfflineEstimate.Kind.LEARNED))
                } else if (index == 0) {
                    val remaining = depSec - nowSec
                    if (remaining >= -30 && remaining <= horizonMinutes * 60) mine.add(OfflineEstimate(line, index, maxOf(0, remaining), clock(d), OfflineEstimate.Kind.TIMETABLE_START))
                }
            }
            if (mine.isEmpty() && index > 0 && line.trip[day]?.any { it != null } != true) {
                deps.firstOrNull { it * 60 >= nowSec }?.let { mine.add(OfflineEstimate(line, index, null, clock(it), OfflineEstimate.Kind.DEPARTURE_ONLY)) }
            }
            out.addAll(mine.sortedBy { it.seconds ?: Int.MAX_VALUE }.take(perLine))
        }
        return out.sortedBy { it.seconds ?: Int.MAX_VALUE }
    }

    /** Duraktan geçiş saati (gün başından saniye) ya da hücre yetersizse null. Hücre önce kalkış, sonra tahmini geçiş saatine göre seçilir. */
    fun passSeconds(line: ModelLine, day: String, depSec: Int, index: Int): Int? {
        val cells = line.trip[day] ?: return null
        val first = cells.getOrNull((depSec / 3600).coerceIn(0, 23)) ?: return null
        var pass = depSec + (first.seconds * line.cum[index]).roundToInt()
        val second = cells.getOrNull((pass / 3600).coerceIn(0, 23))
        if (second != null && second !== first) pass = depSec + (second.seconds * line.cum[index]).roundToInt()
        return pass
    }

    companion object {
        fun parse(raw: String): OfflineModel = fromJson(JSONObject(raw))

        fun fromJson(o: JSONObject): OfflineModel {
            val schema = o.optInt("schema", -1)
            if (schema != SUPPORTED_MODEL_SCHEMA) throw DataException("Model şeması desteklenmiyor.")
            val stopsArr = o.optJSONArray("stops") ?: throw DataException("Model eksik.")
            val stops = (0 until stopsArr.length()).associate { i ->
                val s = stopsArr.getJSONObject(i)
                s.getLong("id") to ModelStop(s.getLong("id"), s.optString("code"), s.optString("name"), s.optDouble("lat"), s.optDouble("lon"))
            }
            val arr = o.optJSONArray("lines") ?: throw DataException("Model eksik.")
            val lines = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                val ids = l.getJSONArray("stops").let { a -> (0 until a.length()).map { a.getLong(it) } }
                val est = l.optJSONObject("est")
                val cum = est?.optJSONArray("cum")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
                    ?.takeIf { it.size == ids.size } ?: DoubleArray(ids.size) { if (ids.size > 1) it.toDouble() / (ids.size - 1) else 0.0 }
                val trip = HashMap<String, List<TripCell?>?>()
                val t = est?.optJSONObject("trip")
                for (day in listOf("WORKDAY", "SATURDAY", "SUNDAY")) {
                    val a = t?.optJSONArray(day)
                    trip[day] = if (a == null) null else (0 until a.length()).map { h ->
                        a.optJSONObject(h)?.let { c -> TripCell(c.optInt("sec"), c.optInt("n")) }?.takeIf { it.seconds > 0 }
                    }
                }
                ModelLine(l.getLong("id"), l.getInt("dir"), l.getString("code"), l.optString("name"), l.optString("dirName"), ids,
                    l.optJSONArray("shape"), if (l.isNull("route_type")) null else l.optInt("route_type"), cum, trip)
            }
            if (lines.isEmpty()) throw DataException("Model boş.")
            val version = o.optString("model_version")
            val built = o.optString("built_at")
            if (version.isBlank() || built.isBlank()) throw DataException("Model sürümü eksik.")
            return OfflineModel(o, version, built, lines, stops)
        }
    }
}

fun dayTypeOf(now: ZonedDateTime): String = when (now.dayOfWeek.value) { 6 -> "SATURDAY"; 7 -> "SUNDAY"; else -> "WORKDAY" }
fun parseClock(s: String): Int? = Regex("""(\d{1,2}):(\d{2})""").find(s)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
private fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** Türkçe harf ve büyük/küçük harf farkını yok sayan arama anahtarı. */
fun foldText(s: String): String = buildString {
    for (c in s) append(when (c) {
        'İ', 'I', 'ı' -> 'i'; 'Ş', 'ş' -> 's'; 'Ğ', 'ğ' -> 'g'; 'Ü', 'ü' -> 'u'; 'Ö', 'ö' -> 'o'; 'Ç', 'ç' -> 'c'
        else -> c.lowercaseChar()
    })
}

private fun distanceMeters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val dy = (la2 - la1) * 111_320.0
    val dx = (lo2 - lo1) * 111_320.0 * cos(Math.toRadians((la1 + la2) / 2))
    return sqrt(dx * dx + dy * dy)
}

/**
 * Ağ olmadan, kaynak API ile aynı zarf biçiminde (`meta.offline=true`) cevap üretir. Desteklenmeyen yol için null.
 * Araç konumu üretmez: araç listesi her zaman boştur.
 */
class OfflineProvider(private val model: () -> OfflineModel?, private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ISTANBUL) }) {
    fun supports(path: String): Boolean = route(path.substringBefore('?')) != null

    fun answer(path: String): String? {
        val m = model() ?: return null
        val route = route(path.substringBefore('?')) ?: return null
        val query = parseQuery(path.substringAfter('?', ""))
        val now = clock()
        val data: Any = when (route.first) {
            "search" -> search(m, query)
            "nearby" -> nearby(m, query) ?: return null
            "stop" -> stop(m, route.second, now) ?: return null
            "stopLines" -> stopLines(m, route.second) ?: return null
            "lineStops" -> lineStops(m, route.second) ?: return null
            "shape" -> shape(m, route.second) ?: return null
            "vehicles" -> { m.line(route.second) ?: return null; JSONArray() }
            "timetable" -> JSONObject().put("processInfo", 1).put("timeTable", m.timetable(query["line_code"] ?: return null))
            else -> return null
        }
        val meta = JSONObject().put("source", "Çevrimdışı model").put("fetched_at", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("cached", false).put("offline", true).put("model_version", m.version)
        return JSONObject().put("data", data).put("meta", meta).toString()
    }

    private fun route(p: String): Pair<String, Long>? {
        Regex("""/v1/(search|stops/nearby)""").matchEntire(p)?.let { return (if (it.groupValues[1] == "search") "search" else "nearby") to 0L }
        Regex("""/v1/stops/(\d+)""").matchEntire(p)?.let { return "stop" to it.groupValues[1].toLong() }
        Regex("""/v1/stops/(\d+)/lines""").matchEntire(p)?.let { return "stopLines" to it.groupValues[1].toLong() }
        Regex("""/v1/lines/(\d+)/(stops|shape|vehicles)""").matchEntire(p)?.let {
            return (when (it.groupValues[2]) { "stops" -> "lineStops"; "shape" -> "shape"; else -> "vehicles" }) to it.groupValues[1].toLong()
        }
        if (p == "/v1/timetables") return "timetable" to 0L
        return null
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun stopJson(s: ModelStop) = JSONObject().put("address", "").put("id", s.id).put("latitude", s.latitude).put("longitude", s.longitude)
        .put("name", s.name).put("stopCode", s.code)

    private fun search(m: OfflineModel, q: Map<String, String>): JSONObject {
        val text = foldText(q["q"].orEmpty().trim())
        val wantLines = q["lines"] != "false"; val wantStops = q["stops"] != "false"
        val lines = JSONArray(); val stops = JSONArray()
        if (text.isNotEmpty() && wantLines) m.lines.filter { foldText(it.code) == text || foldText(it.code).startsWith(text) || foldText(it.name).contains(text) }
            .sortedWith(compareBy({ foldText(it.code) != text }, { it.code }, { it.direction })).take(10).forEach {
                lines.put(JSONObject().put("code", it.code).put("direction", it.direction).put("directionName", it.directionName)
                    .put("id", it.id).put("lineType", "1").put("name", it.name))
            }
        if (text.isNotEmpty() && wantStops) m.stops.values.filter { foldText(it.name).contains(text) || it.code == text }
            .sortedWith(compareBy({ !foldText(it.name).startsWith(text) }, { it.name })).take(10).forEach { stops.put(stopJson(it)) }
        return JSONObject().put("adress", JSONObject.NULL).put("busLines", lines).put("busStops", stops).put("processInfo", 1)
    }

    private fun nearby(m: OfflineModel, q: Map<String, String>): JSONObject? {
        val la = q["latitude"]?.toDoubleOrNull() ?: return null; val lo = q["longitude"]?.toDoubleOrNull() ?: return null
        val arr = JSONArray()
        m.stops.values.map { it to distanceMeters(la, lo, it.latitude, it.longitude) }.filter { it.second <= 2000 }.sortedBy { it.second }.take(15).forEach { (s, d) ->
            arr.put(JSONObject().put("busStopCode", s.code).put("busStopId", s.id.toString()).put("busStopName", s.name)
                .put("distance", d.roundToInt()).put("latitude", s.latitude).put("longitude", s.longitude))
        }
        return JSONObject().put("nearestStops", arr)
    }

    private fun stop(m: OfflineModel, id: Long, now: ZonedDateTime): JSONObject? {
        val s = m.stop(id) ?: return null
        val buses = JSONArray()
        m.arrivalsAt(id, now).forEach { e ->
            val remaining = e.seconds
            val label = when (e.kind) {
                OfflineEstimate.Kind.LEARNED -> "Çevrimdışı tahmin"
                OfflineEstimate.Kind.TIMETABLE_START -> "Çevrimdışı · tarife kalkışı"
                OfflineEstimate.Kind.DEPARTURE_ONLY -> "Çevrimdışı · bu durağa süre öğrenilmedi, kalkış ${e.departure}"
            }
            buses.put(JSONObject().put("busCode", e.line.code).put("plate", "")
                .put("remaining_time", remaining?.let { if (it < 60) "~1 dk" else "~" + formatDuration((it / 60) * 60) } ?: "Süre belirtilmedi")
                .put("nextStopName", "").put("remaining_distance", label).put("busName", e.line.plainName)
                .put("directionName", e.line.directionName).put("state", label)
                .put("timeToStop", remaining ?: JSONObject.NULL).put("lineId", e.line.id.toString()).put("nextStopId", 0))
        }
        return JSONObject().put("buses", buses).put("code", s.code).put("county", "KAHRAMANMARAS")
            .put("location", JSONObject().put("latitude", s.latitude).put("longitude", s.longitude)).put("name", s.name).put("processInfo", "1")
    }

    private fun stopLines(m: OfflineModel, id: Long): JSONObject? {
        if (m.stop(id) == null) return null
        val arr = JSONArray()
        m.linesAt(id).map { it.first }.distinctBy { it.id }.sortedWith(compareBy({ it.code }, { it.direction })).forEach {
            arr.put(JSONObject().put("lineCode", it.code).put("lineId", it.id).put("name", it.plainName))
        }
        return JSONObject().put("processInfo", "1").put("stopLines", arr)
    }

    private fun lineStops(m: OfflineModel, id: Long): JSONObject? {
        val l = m.line(id) ?: return null
        val arr = JSONArray()
        l.stopIds.forEachIndexed { i, sid ->
            val s = m.stop(sid) ?: return@forEachIndexed
            arr.put(JSONObject().put("code", s.code).put("directionDescription", JSONObject.NULL).put("id", s.id)
                .put("location", JSONObject().put("latitude", s.latitude).put("longitude", s.longitude)).put("name", s.name).put("sequenceNo", i + 1))
        }
        return JSONObject().put("lineStops", arr)
    }

    private fun shape(m: OfflineModel, id: Long): JSONObject? {
        val l = m.line(id) ?: return null
        val shape = l.shape ?: return null
        return JSONObject().put("type", "Feature").put("properties", JSONObject().put("line_id", l.id).put("direction", l.direction))
            .put("geometry", if (shape.length() >= 2) JSONObject().put("type", "LineString").put("coordinates", shape) else JSONObject.NULL)
    }
}
