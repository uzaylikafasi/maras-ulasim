package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FixReleaseTest {
    private val terminal = Stop(4852, "10196", "DİVAN SİTESİ", 37.59981, 36.82287)
    private val second = Stop(4853, "10874", "GÜZELYURT SİTESİ", 37.5998, 36.82699)
    private val third = Stop(4854, "10198", "DOĞA SİTESİ", 37.59984, 36.83)
    private val far = Stop(100, "1", "İleride", 37.60, 36.85)
    private val returning = Line(161, 2, "H/1", "ADLİYE - OTOGAR", "Dönüş")
    private val outbound = suggestedLine
    private val returnStops = listOf(terminal, second, third, far)
    private val incomingStops = listOf(far, terminal)
    private val waiting = Vehicle("TEST", Coordinate(37.59981, 36.82287), terminal.name, 90.0, 5642, terminal.id, 0.0)

    @Test fun `journey request uses internal stop IDs and chosen weekday and time`() {
        assertEquals("/v1/journeys?from_stop_id=4852&to_stop_id=4853&day=0&hour=8&minute=5",
            journeyPath(terminal, second, 0, 8, 5))
        assertTrue(journeyPath(second, terminal, 4, 16, 30).contains("from_stop_id=4853&to_stop_id=4852"))
        assertTrue(runCatching { journeyPath(terminal, terminal, 4, 16, 30) }.isFailure)
    }
    @Test fun `journey envelope keeps bus and walking legs and reports malformed responses`() {
        val raw = """{"data":{"data":[{"Lags":[{"Line":{"code":"Y"},"FS":{"name":"Başlangıç"},"TS":{"name":"Durak"}},
            {"Line":{"code":"H/1"},"FS":{"name":"Durak"},"TS":{"name":"Varış"},"DT":"2026-11-05T16:29:59.9999999+03:00","AT":"2026-11-05T16:50:00+03:00"}]}],"error":{"code":0}},"meta":{"fetched_at":"2026-11-05T13:20:00Z"}}"""
        val route = parseJourneys(decodeEnvelope(raw).objectData).single()
        assertTrue(route.legs.first().walking)
        assertEquals("16:30", route.legs.last().depart)
        assertEquals("Varış", route.legs.last().to)
        assertTrue(parseJourneys(JSONObject("""{"data":[],"error":null}""")).isEmpty())
        for (bad in listOf("{}", """{"data":null}""", """{"data":[{}]}""", """{"data":[],"error":{"code":1}}"""))
            assertTrue(runCatching { parseJourneys(JSONObject(bad)) }.exceptionOrNull() is DataException)
    }
    @Test fun `stop bus details retain source labels ETA distance and missing fields`() {
        val a = parseArrivals(JSONArray("""[{"busCode":"H/1","busName":"ADLİYE - OTOGAR","plate":"TEST","directionName":"Dönüş","nextStopName":"DOĞA SİTESİ","remaining_time":"3 dk 39 sn","remaining_distance":"1.8 km"}]""")).single()
        val details = arrivalDetails(second, a).joinToString("\n")
        for (value in listOf("H/1", "ADLİYE - OTOGAR", "TEST", "Dönüş", "GÜZELYURT SİTESİ durağına: 3 dk 39 sn", "1.8 km", "DOĞA SİTESİ"))
            assertTrue(details.contains(value))
        val missing = arrivalDetails(second, parseArrivals(JSONArray("[{}]")).single()).joinToString()
        assertTrue(missing.contains("Süre belirtilmedi")); assertFalse(missing.contains("null"))
    }
    @Test fun `finished outbound bus appears at the first return stops without inventing an ETA`() {
        for (stop in returnStops.take(3)) {
            val a = terminalArrivals(stop, returning, returnStops, incomingStops, listOf(waiting), emptyList()).single()
            assertEquals("TEST", a.plate); assertEquals("Dönüş", a.direction)
            assertEquals("Kalkış bekleniyor", a.remaining)
            assertTrue(a.state.contains("süresi henüz bildirilmedi"))
        }
    }
    @Test fun `terminal detection also handles a separate platform and a vehicle already assigned to return`() {
        val platform = terminal.copy(id = 9000, latitude = 37.5997)
        assertTrue(sameTerminal(terminal, platform))
        val incomingVehicle = waiting.copy(coordinate = Coordinate(platform.latitude!!, platform.longitude!!), nextStopId = platform.id)
        assertEquals(1, terminalArrivals(second, returning, returnStops, listOf(far, platform), listOf(incomingVehicle), emptyList()).size)
        val assigned = waiting.copy(previousStopId = terminal.id, nextStopId = second.id, nextStop = second.name)
        assertEquals(second.name, terminalArrivals(second, returning, returnStops, incomingStops, listOf(assigned), emptyList()).single().nextStop)
    }
    @Test fun `terminal detection rejects approaching moving distant unknown and unrelated vehicles`() {
        for (v in listOf(waiting.copy(speed = 20.0), waiting.copy(speed = 0.5), waiting.copy(speed = null),
            waiting.copy(coordinate = Coordinate(37.5995, 36.82287)),
            waiting.copy(coordinate = Coordinate(37.59, 36.82)), waiting.copy(nextStopId = 2, previousStopId = 1)))
            assertTrue(terminalArrivals(second, returning, returnStops, incomingStops, listOf(v), emptyList()).isEmpty())
        assertTrue(terminalArrivals(far, returning, returnStops, incomingStops, listOf(waiting), emptyList()).isEmpty())
        assertTrue(terminalArrivals(second, returning, returnStops, listOf(far), listOf(waiting), emptyList()).isEmpty())
        val source = Arrival("H/1", "TEST", "1 dk", "Durak")
        assertTrue(terminalArrivals(second, returning, returnStops, incomingStops, listOf(waiting), listOf(source)).isEmpty())
    }
    @Test fun `stop refresh merges terminal vehicles and drops them after a failed live request`() = runTest {
        MockWebServer().use { server ->
            var failed = false
            fun envelope(data: Any) = JSONObject().put("data", data).put("meta", JSONObject().put("fetched_at", java.time.Instant.now().toString())).toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val data: Any = when {
                        path == "/v1/stops/4853" -> JSONObject("""{"buses":[]}""")
                        path == "/v1/stops/4853/lines" -> JSONObject("""{"stopLines":[{"lineCode":"H/1","lineId":161}]}""")
                        path.startsWith("/v1/search?") -> JSONObject().put("busLines", JSONArray(listOf(returning.json(), outbound.json())))
                        path == "/v1/lines/161/stops?direction=2" -> JSONObject().put("lineStops", JSONArray(returnStops.map { it.json() }))
                        path == "/v1/lines/9576/stops?direction=1" -> JSONObject().put("lineStops", JSONArray(incomingStops.map { it.json() }))
                        path == "/v1/lines/161/vehicles?direction=2" -> JSONArray()
                        path == "/v1/lines/9576/vehicles?direction=1" -> {
                            if (failed) return MockResponse().setResponseCode(503)
                            JSONArray("""[{"plate":"TEST","location":{"latitude":37.59981,"longitude":36.82287},"speed":0,"nextStopId":"4852"}]""")
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(envelope(data))
                }
            }
            server.start()
            val cache = object : ResponseCache { override fun read(key: String): String? = null; override fun write(key: String, raw: String) {} }
            val loader = StopDetails(TransportRepository(TransportApi(), cache))
            val settings = Settings(server = server.url("/").toString().trimEnd('/'))
            var sourceShown = false
            val first = loader.fetch(second, settings) { sourceShown = true; assertEquals(0, it.objectData.getJSONArray("buses").length()) }
            assertTrue(sourceShown)
            assertEquals("TEST", parseArrivals(first.objectData.getJSONArray("buses")).single().plate)
            failed = true
            assertTrue(parseArrivals(loader.fetch(second, settings).objectData.getJSONArray("buses")).isEmpty())
            assertEquals(10, server.requestCount) // Static topology is reused, live data isn't.
        }
    }
    @Test fun `observed H1 stop spur returns to the road without changing the stop location`() {
        val road = Coordinate(37.56678, 36.85958)
        val stop = Stop(5667, "11254", "AVUKAT MEHMET AKİF KISAKÜREK CAD.", 37.56689, 36.8596)
        val next = Coordinate(37.56696, 36.85849)
        val p = Coordinate(stop.latitude!!, stop.longitude!!)
        assertEquals(listOf(road, next), cleanRoute(listOf(road, p, p, road, next), listOf(stop)))
        assertEquals(37.56689, stop.latitude!!, 0.0)
    }
    @Test fun `road geometry removes duplicate and short stop spurs while preserving bends and endpoints`() {
        val a = Coordinate(37.58, 36.92)
        val b = Coordinate(37.5805, 36.92)
        val spur = Coordinate(37.5805, 36.9201)
        val c = Coordinate(37.581, 36.92)
        val d = Coordinate(37.581, 36.921)
        val stop = Stop(1, "1", "Yol kenarı", spur.latitude, spur.longitude)
        val points = listOf(a, a, b, spur, spur, b, c, d)
        assertEquals(listOf(a, b, c, d), cleanRoute(points, listOf(stop)))
        assertEquals(listOf(a, b, spur, b, c, d), cleanRoute(points, emptyList()))
        val corner = Stop(2, "2", "Kavşak", c.latitude, c.longitude)
        assertEquals(listOf(a, c, d), cleanRoute(listOf(a, c, d), listOf(corner)))
        val shape = JSONObject().put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray(points.map { listOf(it.longitude, it.latitude) })))
        assertEquals(listOf(a, b, c, d), parseShape(renderedShape(shape, listOf(stop))))
        assertEquals(points.first(), parseShape(shape).first()) // Input is not mutated by markers/rendering.
    }
}
