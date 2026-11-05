package com.uzaylikafasi.marasulasim.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Statik yön/durak eşleşmeleri bellekte tutulur; araçlar her yenilemede yeniden alınır. */
class StopDetails(private val repository: TransportRepository) {
    private data class Terminal(val line: Line, val stops: List<Stop>, val incoming: Line, val incomingStops: List<Stop>)
    private val lineDirections = java.util.concurrent.ConcurrentHashMap<Triple<String, String, String>, List<Line>>()
    private val terminals = java.util.concurrent.ConcurrentHashMap<Triple<String, String, Long>, List<Terminal>>()
    suspend fun fetch(stop: Stop, settings: Settings, onSource: (ApiResult) -> Unit = {}): ApiResult {
        suspend fun get(path: String, live: Boolean = false) = repository.fetch(settings.server, settings.apiKey, path, live)
        val result = get("/v1/stops/${stop.id}", true)
        onSource(result)
        val enriched = JSONObject(result.objectData.toString())
        val buses = enriched.requiredArray("buses")
        // An optional terminal lookup must never hide a valid source arrival list.
        try {
            val cacheKey = Triple(settings.server, settings.apiKey, stop.id)
            val candidates = terminals[cacheKey] ?: run {
                val entries = get("/v1/stops/${stop.id}/lines").objectData.requiredArray("stopLines").objects()
                val found = mutableListOf<Terminal>()
                for (entry in entries) {
                    val code = entry.getString("lineCode")
                    val lineKey = Triple(settings.server, settings.apiKey, code)
                    val directions = lineDirections[lineKey] ?: parseSearch(get(searchPath(code)).objectData).lines
                        .filter { it.code == code }.also { lineDirections[lineKey] = it }
                    // StopLines carries the direction-specific line ID, not the visible code.
                    for (line in directions.filter { it.id == entry.optLong("lineId") }) {
                        suspend fun stops(l: Line) = get("/v1/lines/${l.id}/stops?direction=${l.direction}")
                            .objectData.requiredArray("lineStops").objects().sortedBy { it.optInt("sequenceNo") }.map(::parseStop)
                        val lineStops = stops(line)
                        if (lineStops.indexOfFirst { it.id == stop.id } !in 0..2) continue
                        for (incoming in directions.filter { it.direction != line.direction }) {
                            val incomingStops = stops(incoming)
                            if (incomingStops.lastOrNull()?.let { end -> lineStops.firstOrNull()?.let { sameTerminal(it, end) } } == true)
                                found.add(Terminal(line, lineStops, incoming, incomingStops))
                        }
                    }
                }
                found.toList().also { terminals[cacheKey] = it }
            }
            buses.objects().forEach { bus ->
                lineDirections[Triple(settings.server, settings.apiKey, bus.optString("busCode"))]
                    ?.firstOrNull { it.id == bus.optLong("lineId") }?.let { bus.put("directionName", it.directionName) }
            }
            for (candidate in candidates) {
                for (direction in listOf(candidate.incoming, candidate.line)) {
                    val live = try { get("/v1/lines/${direction.id}/vehicles?direction=${direction.direction}", true) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { continue }
                    if (live.isOld()) continue
                    terminalArrivals(stop, candidate.line, candidate.stops, candidate.incomingStops, parseVehicles(live.arrayData), parseArrivals(buses)).forEach { a ->
                        buses.put(JSONObject().put("busCode", a.code).put("plate", a.plate).put("remaining_time", a.remaining)
                            .put("nextStopName", a.nextStop).put("busName", a.name).put("directionName", a.direction).put("state", a.state))
                    }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Source arrivals remain usable when the additional lookup fails. */ }
        return result.copy(data = enriched)
    }
}
