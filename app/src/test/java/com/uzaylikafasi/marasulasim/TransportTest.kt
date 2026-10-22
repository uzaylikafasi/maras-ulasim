package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.Instant
import java.util.concurrent.TimeUnit

class TransportTest {
    @Test fun `new installs use HTTPS without a USB tunnel`() {
        assertEquals(DEFAULT_SERVER, Settings().server)
        assertEquals(DEFAULT_SERVER, validatedServer(DEFAULT_SERVER, false))
    }
    @Test fun `old keyless pilot migrates once and preserves favorites`() = runTest {
        val server = androidx.datastore.preferences.core.stringPreferencesKey("server")
        val favorites = androidx.datastore.preferences.core.stringPreferencesKey("lines")
        val old = androidx.datastore.preferences.core.mutablePreferencesOf(server to LEGACY_USB_SERVER, favorites to "saved-favorites")
        val migration = PilotHttpsMigration()
        assertTrue(migration.shouldMigrate(old))
        val upgraded = migration.migrate(old)
        assertEquals(DEFAULT_SERVER, upgraded[server])
        assertEquals("saved-favorites", upgraded[favorites])
        assertFalse(migration.shouldMigrate(upgraded))
        // An explicit USB setting after the upgrade is not overridden on next launch.
        val manual = upgraded.toMutablePreferences().apply { this[server] = LEGACY_USB_SERVER }
        assertFalse(migration.shouldMigrate(manual))
    }
    @Test fun `migration keeps custom servers and never sends an existing key to a new host`() = runTest {
        val server = androidx.datastore.preferences.core.stringPreferencesKey("server")
        val key = androidx.datastore.preferences.core.stringPreferencesKey("api_key")
        val migration = PilotHttpsMigration()
        for ((host, token) in listOf(LEGACY_USB_SERVER to "old-key", "https://custom.example" to "", "https://custom.example" to "key")) {
            val old = androidx.datastore.preferences.core.mutablePreferencesOf(server to host, key to token)
            val upgraded = migration.migrate(old)
            assertEquals(host, upgraded[server]); assertEquals(token, upgraded[key])
        }
    }
    @Test fun `API keeps the public server path prefix`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(envelope))
            TransportApi().get(server.url("/ulasim-api").toString(), "", "/v1/lines/9576/vehicles?direction=1")
            assertEquals("/ulasim-api/v1/lines/9576/vehicles?direction=1", server.takeRequest().path)
        }
    }
    private val envelope = """{"data":{"lineStops":[]},"meta":{"fetched_at":"2026-10-21T19:00:00Z","cached":false}}"""
    private class MemoryCache : ResponseCache {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, raw: String) { values[key] = raw }
    }
    @Test fun `line identity keeps direction and does not use visible code as ID`() {
        val line = parseLine(JSONObject("""{"id":161,"direction":2,"code":"H/1","name":"Dönüş","directionName":"Dönüş"}"""))
        assertEquals("161:2", line.key)
        assertNotEquals(suggestedLine.key, line.key)
    }
    @Test fun `stop ID and stop code remain separate`() {
        val stop = parseStop(JSONObject("""{"id":6968,"stopCode":"10111","name":"Meydan","latitude":37.58303,"longitude":36.92657}"""))
        assertEquals(6968L, stop.id); assertEquals("10111", stop.code)
        assertEquals(stop, parseStop(stop.json()))
    }
    @Test fun `invalid stop coordinates are not zero or map markers`() {
        val stop = parseStop(JSONObject("""{"id":1,"latitude":200,"longitude":36}"""))
        assertNull(stop.latitude); assertNull(stop.longitude)
    }
    @Test fun `source null search collections are accepted`() {
        val result = parseSearch(JSONObject("""{"busLines":null,"busStops":[]}"""))
        assertTrue(result.lines.isEmpty()); assertTrue(result.stops.isEmpty())
    }
    @Test fun `shape coordinates are longitude then latitude`() {
        val shape = parseShape(JSONObject("""{"geometry":{"type":"LineString","coordinates":[[36.92,37.58],[36.93,37.59]]}}"""))
        assertEquals(Coordinate(37.58, 36.92), shape.first())
    }
    @Test fun `vehicles only use the observed location schema and valid coordinates`() {
        val raw = org.json.JSONArray("""[{"plate":"TEST 01","location":{"latitude":37.58,"longitude":36.92},"nextStopName":"Meydan"},{"plate":"TEST 02"},{"plate":"TEST 03","location":{"latitude":200,"longitude":0}},{"plate":"TEST 04","location":{"latitude":0,"longitude":0}}]""")
        val vehicles = parseVehicles(raw)
        assertEquals(1, vehicles.size); assertEquals("TEST 01", vehicles.single().plate)
        assertEquals(Coordinate(37.58, 36.92), vehicles.single().coordinate)
    }
    @Test fun `arrival time uses source text without guessing distance units`() {
        val arrivals = parseArrivals(org.json.JSONArray("""[{"busCode":"H/1","plate":"TEST 01","remaining_time":"1 dk 21 sn","timeToStop":81,"distanceToStop":682000}]"""))
        assertEquals("1 dk 21 sn", arrivals.single().remaining)
    }
    @Test(expected = DataException::class) fun `invalid shape is rejected`() {
        parseShape(JSONObject("""{"geometry":{"type":"LineString","coordinates":[[36,200]]}}"""))
    }
    @Test(expected = DataException::class) fun `malformed envelope does not become an empty successful response`() {
        decodeEnvelope("""{"error":{"code":"failure"}}""")
    }
    @Test fun `freshness uses fetched time and preserves cached metadata`() {
        val result = decodeEnvelope(envelope)
        assertFalse(result.isOld(Instant.parse("2026-10-21T19:00:20Z")))
        assertTrue(result.isOld(Instant.parse("2026-10-21T19:01:00Z")))
        assertTrue(result.copy(stale = true).isOld(Instant.parse("2026-10-21T19:00:20Z")))
        assertTrue(result.copy(fetchedAt = null, bundled = true).isOld())
    }
    @Test fun `debug permits only local HTTP and never credentials in URL`() {
        assertEquals("http://127.0.0.1:8000", validatedServer("http://127.0.0.1:8000/", true))
        assertEquals("https://example.org/api", validatedServer("https://example.org/api/", false))
        for ((url, debug) in listOf("http://127.0.0.1:8000" to false, "http://example.org" to true,
            "https://user:password@example.org" to true, "https://example.org?key=secret" to false)) {
            assertTrue(runCatching { validatedServer(url, debug) }.exceptionOrNull() is DataException)
        }
    }
    @Test fun `search cannot turn query into another path`() {
        val path = searchPath("H/1 & stops=false")
        assertTrue(path.startsWith("/v1/search?q=")); assertTrue(path.contains("%26")); assertFalse(path.contains("&stops="))
    }
    @Test fun `network failure returns static cache with its original timestamp`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(envelope)); server.enqueue(MockResponse().setResponseCode(503))
            val cache = MemoryCache(); val repo = TransportRepository(TransportApi(), cache)
            val base = server.url("/").toString().trimEnd('/')
            val first = repo.fetch(base, "local-secret", "/v1/test")
            val second = repo.fetch(base, "local-secret", "/v1/test")
            assertFalse(first.stale); assertTrue(second.stale)
            assertEquals(first.fetchedAt, second.fetchedAt)
            assertNotNull(second.error)
            assertEquals("local-secret", server.takeRequest().getHeader("X-API-Key"))
        }
    }
    @Test fun `live results are neither persisted nor replaced by disk cache`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(envelope)); server.enqueue(MockResponse().setResponseCode(504))
            val cache = MemoryCache(); val repo = TransportRepository(TransportApi(), cache)
            val base = server.url("/").toString().trimEnd('/')
            repo.fetch(base, "", "/v1/vehicles", live = true)
            assertTrue(cache.values.isEmpty())
            cache.write("$base|/v1/vehicles", envelope)
            assertTrue(runCatching { repo.fetch(base, "", "/v1/vehicles", live = true) }.isFailure)
        }
    }
    @Test fun `server changes cannot reuse another server cache`() {
        val cache = MemoryCache(); val repo = TransportRepository(TransportApi(), cache)
        cache.write("https://one.example|/v1/test", envelope)
        assertNull(repo.cached("https://two.example", "/v1/test"))
    }
    @Test fun `invalid access keys never appear in error messages`() {
        val privateValue = "example-private-key"
        val error = runCatching { validatedApiKey(privateValue + "\n") }.exceptionOrNull()
        assertTrue(error is DataException)
        assertFalse(error!!.message.orEmpty().contains(privateValue))
    }
    @Test fun `disk cache failure does not discard a fresh network response`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(envelope))
            val broken = object : ResponseCache {
                override fun read(key: String): String? = throw java.io.IOException("disk unavailable")
                override fun write(key: String, raw: String) { throw java.io.IOException("disk full") }
            }
            val result = TransportRepository(TransportApi(), broken).fetch(server.url("/").toString().trimEnd('/'), "", "/v1/test")
            assertFalse(result.stale)
            assertEquals("2026-10-21T19:00:00Z", result.fetchedAt)
        }
    }
    @Test fun `access key changes cannot reuse the previous access scope cache`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(envelope))
            val repo = TransportRepository(TransportApi(), MemoryCache())
            val base = server.url("/").toString().trimEnd('/')
            repo.fetch(base, "first-key", "/v1/test")
            assertNotNull(repo.cached(base, "/v1/test", "first-key"))
            assertNull(repo.cached(base, "/v1/test", "second-key"))
        }
    }
    @Test fun `redirects cannot carry an API key to a second host`() = runTest {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/stolen")))
            val result = runCatching { TransportApi().get(first.url("/").toString().trimEnd('/'), "private", "/v1/test") }
            assertTrue(result.isFailure); assertEquals(0, second.requestCount)
        } }
    }
    @Test fun `cancelled requests do not write cache`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(envelope).setBodyDelay(2, TimeUnit.SECONDS))
            val cache = MemoryCache(); val repo = TransportRepository(TransportApi(), cache)
            val work = launch { repo.fetch(server.url("/").toString().trimEnd('/'), "", "/v1/test") }
            yield(); work.cancelAndJoin()
            assertTrue(cache.values.isEmpty())
        }
    }
    @Test fun `weekday reminder moves to next valid day`() {
        val friday = ZonedDateTime.parse("2026-10-23T08:00:00+03:00[Europe/Istanbul]")
        assertEquals("2026-10-26T07:30+03:00[Europe/Istanbul]", nextOccurrence(7, 30, setOf(1, 2, 3, 4, 5), friday).toString())
    }
    @Test fun `reminder at exactly current time schedules next occurrence`() {
        val now = ZonedDateTime.parse("2026-10-22T07:30:00+03:00[Europe/Istanbul]")
        assertEquals(now.plusDays(7), nextOccurrence(7, 30, setOf(4), now))
    }
    @Test(expected = IllegalArgumentException::class) fun `reminder without weekdays is rejected`() {
        nextOccurrence(7, 30, emptySet(), ZonedDateTime.now())
    }

    // 21 Ekim gerçek yolculuk yanıtından (Otogar → Demokrasi Meydanı) kısaltılmış örnek
    private val walkingRoute = JSONObject("""{"Distance":4101,"Duration":17,"Lags":[
        {"AT":"2026-10-21T08:45:55+03:00","DT":"2026-10-21T08:41:28+03:00","Distance":148,"Duration":3,
         "FS":{"name":"Konum"},"TS":{"name":"DEVLET HASTANESİ"},"Line":{"code":"Y"}},
        {"AT":"2026-10-21T08:58:30+03:00","DT":"2026-10-21T08:45:55+03:00","Distance":3953,"Duration":13,
         "FS":{"name":"DEVLET HASTANESİ"},"TS":{"name":"DEMOKRASİ MEYDANI"},"Line":{"code":"B/77"}}]}""")

    @Test fun `walking legs are not shown as a bus line`() {
        val route = parseJourneyRoute(walkingRoute)
        assertTrue(route.legs[0].walking)
        assertFalse(route.legs[1].walking)
        assertEquals("B/77", route.legs[1].code)
        assertEquals("DEMOKRASİ MEYDANI", route.legs[1].to)
        assertEquals(0, route.transfers)
        assertEquals(148, route.walkingMeters)
    }
    @Test fun `planned times round to the nearest minute`() {
        assertEquals("08:45", plannedMinute("2026-10-21T08:44:59.9999999+03:00"))
        assertEquals("08:41", plannedMinute("2026-10-21T08:41:28+03:00"))
        assertNull(plannedMinute("bozuk"))
    }
    @Test fun `vehicle direction and arrival distance are read when present`() {
        val v = parseVehicles(org.json.JSONArray("""[{"plate":"46 A 1","location":{"latitude":37.5,"longitude":36.9},"directionAngle":270},
            {"plate":"46 A 2","location":{"latitude":37.5,"longitude":36.9},"directionAngle":"x"}]"""))
        assertEquals(270.0, v[0].angle!!, 0.0)
        assertNull(v[1].angle)
        val a = parseArrivals(org.json.JSONArray("""[{"busCode":"H/1","plate":"P","remaining_time":"1 dk 21 sn","remaining_distance":"0.7 km"}]"""))
        assertEquals("0.7 km", a[0].distance)
    }
    @Test fun `shape bounds cover the whole route`() {
        val shape = JSONObject("""{"geometry":{"type":"LineString","coordinates":[[36.90,37.55],[36.95,37.60],[36.92,37.57]]}}""")
        val (sw, ne) = shapeBounds(shape)!!
        assertEquals(37.55, sw.latitude, 1e-9); assertEquals(36.90, sw.longitude, 1e-9)
        assertEquals(37.60, ne.latitude, 1e-9); assertEquals(36.95, ne.longitude, 1e-9)
        assertNull(shapeBounds(JSONObject("{}")))
    }
    @Test fun `nearby stops are read from the nearestStops list`() {
        val stops = parseNearby(JSONObject("""{"nearestStops":[{"busStopCode":"12010","busStopId":"9099","busStopName":"KAHRAMANMARAŞ HAVALİMANI","distance":578,"latitude":37.53613,"longitude":36.95905}]}"""))
        assertEquals(1, stops.size)
        assertEquals(9099L, stops[0].id); assertEquals("KAHRAMANMARAŞ HAVALİMANI", stops[0].name); assertEquals(37.53613, stops[0].latitude!!, 1e-9)
        assertEquals(1, parseNearby(JSONObject("""{"busStops":[{"busStopId":"1","name":"A"}]}""")).size)
    }
    @Test fun `bus destination comes from the line name`() {
        assertEquals("BİNEVLER", com.uzaylikafasi.marasulasim.ui.destinationOf(Line(910, 1, "H/10", "K. SANAYİ - BİNEVLER (Gidiş)", "Gidiş")))
        assertEquals("Dönüş", com.uzaylikafasi.marasulasim.ui.destinationOf(Line(1, 2, "H/1", "", "Dönüş")))
    }
}
