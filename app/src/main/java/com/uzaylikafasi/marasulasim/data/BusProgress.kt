package com.uzaylikafasi.marasulasim.data

/** Kaynağın sıradaki durak kimliğine göre hat sırası; fiziksel geçiş ölçümü değildir. */
data class BusProgress(val previous: List<String>, val upcoming: List<String>)

fun busProgress(stops: List<Stop>, nextStopId: Long, targetId: Long): BusProgress? {
    if (nextStopId <= 0 || stops.count { it.id == nextStopId } != 1 || stops.count { it.id == targetId } != 1) return null
    val next = stops.indexOfFirst { it.id == nextStopId }
    val target = stops.indexOfFirst { it.id == targetId }
    if (next > target) return null // Karşı yön/terminal belirsizliğinde geçiş uydurma.
    return BusProgress(stops.take(next).takeLast(3).map { it.name }, stops.drop(next).take(5).map { it.name })
}

/** Statik hat sırası hizmet oturumu boyunca saklanır; canlı araç başına yeni sorgu yok. */
class BusProgressLoader(private val repository: TransportRepository) {
    private val routes = mutableMapOf<String, List<Stop>>()
    private val attempts = mutableMapOf<String, Long>()
    suspend fun load(server: String, key: String, arrival: Arrival, targetId: Long): BusProgress? {
        if (arrival.nextStopId <= 0) return null
        val cacheKey = "$server|$key|${arrival.lineId}|${arrival.direction}"
        var stops = routes[cacheKey]
        if (stops == null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (attempts[cacheKey]?.let { now - it < 120_000 } == true) return null
            attempts[cacheKey] = now
            val candidates = parseSearch(repository.fetch(server,key,searchPath(arrival.code)).objectData).lines
                .filter { it.id.toString() == arrival.lineId && it.code == arrival.code }.distinctBy { it.key }
            val line = candidates.singleOrNull() ?: candidates.singleOrNull { it.directionName == arrival.direction && arrival.direction.isNotBlank() }
                ?: return null
            stops = repository.fetch(server,key,"/v1/lines/${line.id}/stops?direction=${line.direction}")
                .objectData.requiredArray("lineStops").objects().map(::parseStop)
            if (stops.isEmpty()) return null
            routes[cacheKey] = stops
        }
        return busProgress(stops,arrival.nextStopId,targetId)
    }
}
