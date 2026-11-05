package com.uzaylikafasi.marasulasim.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

/** Kırmızı ışıkta ortalama bekleme; yoldaki her ışık için varış süresine eklenir. */
const val LIGHT_WAIT_SECONDS = 20
/** Durak dışında bu kadar ayrı duruş görülen yer ışık sayılır. */
const val LIGHT_MIN_STOPS = 5
/** Ana yolların kesiştiği kavşakta daha az duruş yeter. */
const val LIGHT_MIN_STOPS_AT_JUNCTION = 2
/** Bu kadar süre yerinden kıpırdamayan otobüs "durdu" sayılır; daha kısası yol verme/kavşak geçişi. */
const val STILL_MILLIS = 5_000L
private const val NEAR_STOP_M = 40.0
private const val STILL_M = 12.0
private const val SAME_SPOT_M = 25.0
private const val NEAR_JUNCTION_M = 35.0
private const val CORRIDOR_M = 30.0
private const val SLOW_KMH = 3.0

fun meters(a: Coordinate, b: Coordinate): Double {
    val k = 111_320.0
    val x = (b.longitude - a.longitude) * k * cos(Math.toRadians((a.latitude + b.latitude) / 2))
    val y = (b.latitude - a.latitude) * k
    return sqrt(x * x + y * y)
}
fun distanceToSegment(p: Coordinate, a: Coordinate, b: Coordinate): Double {
    val k = 111_320.0; val c = cos(Math.toRadians(p.latitude))
    val ax = (a.longitude - p.longitude) * k * c; val ay = (a.latitude - p.latitude) * k
    val bx = (b.longitude - p.longitude) * k * c; val by = (b.latitude - p.latitude) * k
    val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
    val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
    val x = ax + t * dx; val y = ay + t * dy
    return sqrt(x * x + y * y)
}
/** Birbirine [radius] metreden yakın noktaları tek noktada toplar (bir kavşağın her kolundaki ışık bir sayılır). */
fun cluster(points: List<Coordinate>, radius: Double = 30.0): List<Coordinate> {
    val out = mutableListOf<Coordinate>()
    points.forEach { p -> if (out.none { meters(it, p) < radius }) out += p }
    return out
}

/** Otobüsün durak dışında durduğu yer ve orada görülen ayrı duruş sayısı. */
data class Spot(val at: Coordinate, val stops: Int)

/**
 * Trafik ışıklarını otobüslerden öğrenir. Açık ışık verisi çok eksik olduğu için: otobüs durak olmayan bir yerde
 * [STILL_MILLIS] boyunca kıpırdamazsa orası aday olur; [LIGHT_MIN_STOPS] ayrı duruşta (ana kavşakta
 * [LIGHT_MIN_STOPS_AT_JUNCTION]) ışık sayılır. Bilinen OSM ışıkları baştan ışıktır. Işıklar haritada gösterilmez.
 */
class LightLearner(signals: List<Coordinate>, private val junctions: List<Coordinate>, private val stops: List<Coordinate>,
                   spots: List<Spot> = emptyList()) {
    private val signals = cluster(signals)
    private class Sample(val at: Coordinate, val since: Long, var seen: Long = since, var counted: Boolean = false)
    private val samples = mutableMapOf<String, Sample>()
    var spots: List<Spot> = spots; private set
    private var cachedLights: List<Coordinate>? = null

    private fun nearStop(at: Coordinate) = stops.any { meters(it, at) < NEAR_STOP_M }
    private fun nearJunction(at: Coordinate) = junctions.any { meters(it, at) < NEAR_JUNCTION_M }

    /** Canlı konum örneği. Yeni bir duruş sayıldıysa true (kaydetmek için). */
    @Synchronized fun observe(plate: String, at: Coordinate, speedKmh: Double?, now: Long): Boolean {
        val last = samples[plate]
        if (last == null || meters(last.at, at) >= STILL_M || (speedKmh != null && speedKmh > SLOW_KMH) || now - last.seen > 120_000) {
            samples[plate] = Sample(at, now); return false
        }
        last.seen = now
        if (last.counted || now - last.since < STILL_MILLIS || nearStop(at)) return false
        last.counted = true
        val i = spots.indexOfFirst { meters(it.at, at) < SAME_SPOT_M }
        spots = if (i < 0) spots + Spot(at, 1) else spots.toMutableList().also { list ->
            val s = list[i]; val n = s.stops + 1
            list[i] = Spot(Coordinate((s.at.latitude * s.stops + at.latitude) / n, (s.at.longitude * s.stops + at.longitude) / n), n)
        }
        cachedLights = null
        return true
    }
    @Synchronized fun lights(): List<Coordinate> = cachedLights ?: run {
        val learned = spots.filter { it.stops >= LIGHT_MIN_STOPS || (it.stops >= LIGHT_MIN_STOPS_AT_JUNCTION && nearJunction(it.at)) }
            .map { it.at }.filter { p -> signals.none { meters(it, p) < 30.0 } }
        (signals + learned).also { cachedLights = it }
    }
    /** Yol (otobüs → sıradaki duraklar → bakılan durak) üzerindeki ışık sayısı; her ışık bir kez. */
    fun lightsAlong(path: List<Coordinate>): Int {
        if (path.size < 2) return 0
        return lights().count { light -> path.zipWithNext().any { (a, b) -> distanceToSegment(light, a, b) < CORRIDOR_M } }
    }
    /** Otobüs şu an bir ışıkta [STILL_MILLIS]'den uzun süredir duruyorsa true. */
    @Synchronized fun waitingAtLight(plate: String, now: Long): Boolean {
        val s = samples[plate] ?: return false
        return now - s.since >= STILL_MILLIS && now - s.seen < 45_000 && lights().any { meters(it, s.at) < CORRIDOR_M }
    }
}

/** Uygulamaya gömülü OSM verisi (isiklar.json) ve cihazda öğrenilen duruşlar. */
class TrafficLights(private val context: Context) {
    private val prefs = context.getSharedPreferences("isiklar", Context.MODE_PRIVATE)
    val learner: LightLearner by lazy {
        val bundled = runCatching { JSONObject(context.assets.open("isiklar.json").bufferedReader().use { it.readText() }) }.getOrNull()
        fun points(key: String) = runCatching { bundled!!.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> Coordinate(p.getDouble(0), p.getDouble(1)) } } } }.getOrDefault(emptyList())
        val stops = runCatching { JSONObject(context.assets.open("all-stops.json").bufferedReader().use { it.readText() }).getJSONArray("stops").objects()
            .map { Coordinate(it.getDouble("lat"), it.getDouble("lng")) } }.getOrDefault(emptyList())
        val spots = runCatching { JSONArray(prefs.getString("spots", "[]")).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> Spot(Coordinate(p.getDouble(0), p.getDouble(1)), p.getInt(2)) } } } }.getOrDefault(emptyList())
        LightLearner(points("signals"), points("junctions"), stops, spots)
    }
    fun observe(vehicles: List<Vehicle>, now: Long = System.currentTimeMillis()) {
        if (vehicles.map { learner.observe(it.plate, it.coordinate, it.speed, now) }.any { it })
            prefs.edit().putString("spots", JSONArray(learner.spots.map { JSONArray(listOf(it.at.latitude, it.at.longitude, it.stops)) }).toString()).apply()
    }
}
