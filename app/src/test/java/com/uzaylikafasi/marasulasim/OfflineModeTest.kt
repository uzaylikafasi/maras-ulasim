package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.util.zip.GZIPOutputStream

private fun gz(raw: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw.toByteArray()) } }.toByteArray()

/** 3 duraklı tek hat yönü; hafta içi saat 10 hücresi 20 dk, durak 11 kümülatif %50; cumartesi verisi yok. */
fun testModelJson(version: String = "v1", builtAt: String = "2026-11-13T02:00:00Z", schema: Int = 1) = """
{"schema":$schema,"model_version":"$version","built_at":"$builtAt","tz":"Europe/Istanbul",
 "stops":[{"id":10,"code":"A","name":"BAŞLANGIÇ DURAĞI","lat":37.58,"lon":36.92},{"id":11,"code":"B","name":"ORTA DURAK","lat":37.59,"lon":36.93},{"id":12,"code":"C","name":"SON DURAK","lat":37.60,"lon":36.94}],
 "timetables":{"T/1":[{"routeCode":"T/1","routeName":"X","routeType":1,"lineSchedules":{"WORKDAY":["10:00","10:30"],"SATURDAY":["10:00"],"SUNDAY":[]}}]},
 "lines":[{"id":500,"dir":1,"code":"T/1","name":"BAŞLANGIÇ - SON (Gidiş)","dirName":"Gidiş","stops":[10,11,12],
  "shape":[[36.92,37.58],[36.93,37.59],[36.94,37.60]],"route_type":1,"route_type_basis":"direction",
  "est":{"cum":[0.0,0.5,1.0],"profile_n":50,"profile_basis":"learned",
   "trip":{"WORKDAY":[null,null,null,null,null,null,null,null,null,null,{"sec":1200,"n":20},null,null,null,null,null,null,null,null,null,null,null,null,null],"SATURDAY":null,"SUNDAY":null}}}]}
""".trimIndent()

class OfflineModeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val wed1005: ZonedDateTime = ZonedDateTime.parse("2026-11-11T10:05:00+03:00[Europe/Istanbul]")
    private val sat1005: ZonedDateTime = ZonedDateTime.parse("2026-11-14T10:05:00+03:00[Europe/Istanbul]")
    private fun model() = OfflineModel.parse(testModelJson())
    private fun provider(now: ZonedDateTime = wed1005) = OfflineProvider({ model() }, { now })

    // ---- karar mantığı
    @Test fun `policy serves before network only in button mode when switched on`() {
        assertTrue(OfflinePolicy.serveBeforeNetwork(OfflineConfig(OfflineMode.BUTTON, true)))
        assertFalse(OfflinePolicy.serveBeforeNetwork(OfflineConfig(OfflineMode.BUTTON, false)))
        assertFalse(OfflinePolicy.serveBeforeNetwork(OfflineConfig(OfflineMode.AUTO, true)))
        assertFalse(OfflinePolicy.serveBeforeNetwork(OfflineConfig(OfflineMode.OFF, true)))
    }
    @Test fun `policy serves after failure only in auto mode and never for auth or rate limit`() {
        for (kind in FailureKind.entries) {
            assertEquals(kind.offlineEligible, OfflinePolicy.serveAfterFailure(OfflineConfig(OfflineMode.AUTO), kind))
            assertFalse(OfflinePolicy.serveAfterFailure(OfflineConfig(OfflineMode.BUTTON, true), kind))
            assertFalse(OfflinePolicy.serveAfterFailure(OfflineConfig(OfflineMode.OFF), kind))
        }
        assertFalse(FailureKind.AUTH.offlineEligible); assertFalse(FailureKind.RATE_LIMIT.offlineEligible)
        assertTrue(FailureKind.NETWORK.offlineEligible)
    }
    @Test fun `button state matrix`() {
        val off = OfflinePolicy.button(OfflineConfig(OfflineMode.OFF, true), connectionBad = true, autoServing = true)
        assertFalse(off.visible); assertFalse(off.enlarged)
        // Düğme kipi: kötü bağlantı + kapalı => büyük; açıkken büyümez; bağlantı iyiyken normal
        assertTrue(OfflinePolicy.button(OfflineConfig(OfflineMode.BUTTON, false), true).enlarged)
        assertFalse(OfflinePolicy.button(OfflineConfig(OfflineMode.BUTTON, true), true).enlarged)
        assertFalse(OfflinePolicy.button(OfflineConfig(OfflineMode.BUTTON, false), false).enlarged)
        assertTrue(OfflinePolicy.button(OfflineConfig(OfflineMode.BUTTON, true), false).on)
        // Otomatik kip: yalnız gösterge, büyümez
        val auto = OfflinePolicy.button(OfflineConfig(OfflineMode.AUTO), connectionBad = true, autoServing = true)
        assertTrue(auto.visible); assertTrue(auto.on); assertFalse(auto.enlarged)
        assertFalse(OfflinePolicy.button(OfflineConfig(OfflineMode.AUTO), false, false).on)
        assertEquals(OfflineMode.BUTTON, OfflineMode.fromKey(null)); assertEquals(OfflineMode.AUTO, OfflineMode.fromKey("auto"))
        assertFalse(OfflinePolicy.enabled(OfflineConfig(OfflineMode.OFF)))
    }

    // ---- model ve tahmin
    @Test fun `learned estimate uses departure plus trip time times cumulative share`() {
        val e = model().arrivalsAt(11, wed1005)
        assertEquals(2, e.size)
        assertEquals(300, e[0].seconds); assertEquals("10:00", e[0].departure)   // 10:00 + 20dk*0.5 = 10:10; şimdi 10:05
        assertEquals(2100, e[1].seconds)
        assertTrue(e.all { it.kind == OfflineEstimate.Kind.LEARNED })
    }
    @Test fun `last stop yields no boarding estimate and unknown stop yields none`() {
        assertTrue(model().arrivalsAt(12, wed1005).isEmpty())
        assertTrue(model().arrivalsAt(999, wed1005).isEmpty())
    }
    @Test fun `insufficient cell falls back to timetable start or departure only and never invents seconds`() {
        // Cumartesi: hücre yok. İlk durak tarife kalkışı verir; orta durak yalnız kalkış saati (süre yok)
        val first = model().arrivalsAt(10, sat1005)
        assertTrue(first.isEmpty()) // 10:00 kalkışı geçti (5 dk önce), ufukta başka yok
        val mid = model().arrivalsAt(11, ZonedDateTime.parse("2026-11-14T09:00:00+03:00[Europe/Istanbul]")).single()
        assertEquals(OfflineEstimate.Kind.DEPARTURE_ONLY, mid.kind); assertNull(mid.seconds); assertEquals("10:00", mid.departure)
        val start = model().arrivalsAt(10, ZonedDateTime.parse("2026-11-14T09:50:00+03:00[Europe/Istanbul]")).single()
        assertEquals(OfflineEstimate.Kind.TIMETABLE_START, start.kind); assertEquals(600, start.seconds)
    }
    @Test fun `day type follows calendar day`() {
        assertEquals("WORKDAY", dayTypeOf(wed1005)); assertEquals("SATURDAY", dayTypeOf(sat1005))
        assertEquals("SUNDAY", dayTypeOf(ZonedDateTime.parse("2026-11-15T10:00:00+03:00[Europe/Istanbul]")))
    }
    @Test fun `model parsing rejects unsupported schema or empty model`() {
        assertTrue(runCatching { OfflineModel.parse(testModelJson(schema = 2)) }.isFailure)
        assertTrue(runCatching { OfflineModel.parse("""{"schema":1,"model_version":"x","built_at":"y","stops":[],"lines":[]}""") }.isFailure)
        assertTrue(runCatching { OfflineModel.parse("bozuk") }.isFailure)
    }

    // ---- sağlayıcı zarfı
    @Test fun `answers carry offline flag and same envelope shape`() {
        val raw = provider().answer("/v1/stops/11")!!
        val result = decodeEnvelope(raw)
        assertTrue(result.offline); assertTrue(result.isOld())
        val o = JSONObject(raw)
        assertTrue(o.getJSONObject("meta").getBoolean("offline"))
        val bus = parseArrivals(result.objectData.requiredArray("buses")).first()
        assertEquals("T/1", bus.code); assertEquals("", bus.plate); assertEquals(300, secondsOf(bus)); assertEquals("Gidiş", bus.direction)
        assertEquals("ORTA DURAK", result.objectData.getString("name"))
    }
    @Test fun `vehicles are always empty and never invented`() {
        val r = decodeEnvelope(provider().answer("/v1/lines/500/vehicles?direction=1")!!)
        assertEquals(0, r.arrayData.length()); assertTrue(r.offline)
    }
    @Test fun `static endpoints are served from the model`() {
        val p = provider()
        val stops = decodeEnvelope(p.answer("/v1/lines/500/stops?direction=1")!!).objectData.requiredArray("lineStops").objects().map(::parseStop)
        assertEquals(listOf(10L, 11L, 12L), stops.map { it.id })
        assertEquals(3, parseShape(decodeEnvelope(p.answer("/v1/lines/500/shape?direction=1&format=geojson")!!).objectData).size)
        val lines = decodeEnvelope(p.answer("/v1/stops/11/lines")!!).objectData.requiredArray("stopLines").objects()
        assertEquals(500L, lines.single().getLong("lineId"))
        assertEquals(1, decodeEnvelope(p.answer("/v1/timetables?line_code=T%2F1")!!).objectData.requiredArray("timeTable").length())
        val nearby = parseNearby(decodeEnvelope(p.answer("/v1/stops/nearby?latitude=37.58&longitude=36.92")!!).objectData)
        assertEquals(10L, nearby.first().id)
    }
    @Test fun `search ignores case and turkish letters and keeps both directions of exact code`() {
        val p = provider()
        assertEquals(listOf("T/1"), parseSearch(decodeEnvelope(p.answer(searchPath("t/1"))!!).objectData).lines.map { it.code })
        val stops = parseSearch(decodeEnvelope(p.answer(searchPath("baslangic"))!!).objectData).stops
        assertEquals(10L, stops.first().id)
        assertEquals(500L, parseSearch(decodeEnvelope(p.answer(searchPath("T/1"))!!).objectData).lines.single().id)
    }
    @Test fun `unsupported or unknown paths are not answered`() {
        val p = provider()
        assertNull(p.answer("/v1/announcements")); assertNull(p.answer("/v1/journeys?from_stop_id=1&to_stop_id=2"))
        assertNull(p.answer("/v1/stops/999")); assertNull(p.answer("/v1/lines/1/vehicles"))
        assertNull(OfflineProvider({ null }).answer("/v1/stops/11"))
    }

    // ---- depo seçimi ve güncelleme doğrulaması
    private fun meta(bytes: ByteArray, version: String = "v1", builtAt: String = "2026-11-13T02:00:00Z", schema: Int = 1) =
        ModelMeta(schema, version, sha256Hex(bytes), builtAt)

    @Test fun `source choice prefers downloaded unless invalid or older than seed`() {
        val seed = ModelMeta(1, "s", "a".repeat(64), "2026-11-13T02:00:00Z")
        assertEquals(ModelSource.DOWNLOADED, ModelRules.choose(ModelMeta(1, "d", "b".repeat(64), "2026-11-14T02:00:00Z"), seed))
        assertEquals(ModelSource.BUNDLED, ModelRules.choose(ModelMeta(1, "d", "b".repeat(64), "2026-11-12T02:00:00Z"), seed))
        assertEquals(ModelSource.BUNDLED, ModelRules.choose(ModelMeta(2, "d", "b".repeat(64), "2026-11-20T02:00:00Z"), seed))
        assertEquals(ModelSource.BUNDLED, ModelRules.choose(null, seed))
        assertEquals(ModelSource.DOWNLOADED, ModelRules.choose(ModelMeta(1, "d", "b".repeat(64), "2026-11-12T02:00:00Z"), null))
        assertNull(ModelRules.choose(null, null))
    }
    @Test fun `download decision needs new sha supported schema and not older build`() {
        val cur = ModelMeta(1, "v1", "a".repeat(64), "2026-11-13T02:00:00Z")
        assertFalse(ModelRules.shouldDownload(cur.copy(builtAt = "2026-11-15T02:00:00Z", sha256 = "a".repeat(64)), cur)) // aynı sha
        assertTrue(ModelRules.shouldDownload(cur.copy(sha256 = "b".repeat(64), builtAt = "2026-11-15T02:00:00Z"), cur))
        assertFalse(ModelRules.shouldDownload(cur.copy(sha256 = "b".repeat(64), builtAt = "2026-11-10T02:00:00Z"), cur)) // geri gitmez
        assertFalse(ModelRules.shouldDownload(cur.copy(sha256 = "b".repeat(64), schema = 2), cur))
        assertTrue(ModelRules.shouldDownload(cur, null))
    }
    @Test fun `meta parses envelope and plain body and rejects garbage`() {
        val body = """{"schema":1,"model_version":"v9","sha256":"${"c".repeat(64)}","built_at":"2026-11-13T02:00:00Z"}"""
        assertEquals("v9", ModelMeta.parse("""{"data":$body,"meta":{}}""")!!.version)
        assertEquals("v9", ModelMeta.parse(body)!!.version)
        assertNull(ModelMeta.parse("""{"error":{"code":"NOT_FOUND"}}""")); assertNull(ModelMeta.parse("<html>"))
        assertNull(ModelMeta.parse("""{"schema":1,"model_version":"v","sha256":"kisa","built_at":"x"}"""))
    }
    @Test fun `verify accepts matching file and rejects tampering schema and version mismatch`() {
        val bytes = gz(testModelJson())
        assertTrue(ModelRules.verify(bytes, meta(bytes)).isSuccess)
        assertTrue(ModelRules.verify(bytes, meta(bytes).copy(sha256 = "0".repeat(64))).isFailure)
        assertTrue(ModelRules.verify(bytes.copyOf(bytes.size - 5), meta(bytes)).isFailure)
        assertTrue(ModelRules.verify(bytes, meta(bytes, version = "baska")).isFailure)
        val v2 = gz(testModelJson(schema = 2)); assertTrue(ModelRules.verify(v2, meta(v2, schema = 2)).isFailure)
        val notGz = "düz".toByteArray(); assertTrue(ModelRules.verify(notGz, meta(notGz)).isFailure)
    }
    private fun store(seed: ByteArray?, seedMeta: ModelMeta?, dir: java.io.File = tmp.newFolder()) = ModelStore(dir,
        { seed?.let { ByteArrayInputStream(it) } },
        { seedMeta?.let { ByteArrayInputStream("""{"schema":${it.schema},"model_version":"${it.version}","sha256":"${it.sha256}","built_at":"${it.builtAt}"}""".toByteArray()) } })

    @Test fun `store uses bundled seed then installed download then falls back if download breaks`() {
        val seedBytes = gz(testModelJson("seed", "2026-11-13T02:00:00Z")); val seedMeta = meta(seedBytes, "seed")
        val dir = tmp.newFolder(); val s = store(seedBytes, seedMeta, dir)
        assertEquals(ModelSource.BUNDLED, s.current()!!.source); assertEquals("seed", s.model()!!.version)
        val newBytes = gz(testModelJson("yeni", "2026-11-15T02:00:00Z")); val newMeta = meta(newBytes, "yeni", "2026-11-15T02:00:00Z")
        s.install(newBytes, newMeta)
        assertEquals(ModelSource.DOWNLOADED, s.current()!!.source); assertEquals("yeni", s.model()!!.version)
        // bozulan indirilmiş dosya: tohuma dönülür
        java.io.File(dir, "offline-model.json.gz").writeBytes(byteArrayOf(1, 2, 3)); s.invalidate()
        assertEquals(ModelSource.BUNDLED, s.current()!!.source)
        assertNull(store(null, null).current())
    }

    // ---- depo + yineleyici: ağ olmadan sessiz davranış
    @Test fun `updater keeps seed silently when endpoints are missing`() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND"}}"""))
            val seedBytes = gz(testModelJson()); val s = store(seedBytes, meta(seedBytes))
            val out = ModelUpdater(TransportApi(), s).run(server.url("/").toString().trimEnd('/'), "")
            assertEquals(ModelUpdater.Outcome.SKIPPED, out); assertEquals(ModelSource.BUNDLED, s.current()!!.source)
        }
    }
    @Test fun `updater downloads verifies and installs a newer model`() = runTest {
        MockWebServer().use { server ->
            val newBytes = gz(testModelJson("yeni", "2026-11-15T02:00:00Z")); val m = meta(newBytes, "yeni", "2026-11-15T02:00:00Z")
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":{"schema":1,"model_version":"yeni","sha256":"${m.sha256}","built_at":"${m.builtAt}"},"meta":{}}"""))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(newBytes)))
            val seedBytes = gz(testModelJson()); val s = store(seedBytes, meta(seedBytes))
            val out = ModelUpdater(TransportApi(), s).run(server.url("/").toString().trimEnd('/'), "")
            assertEquals(ModelUpdater.Outcome.UPDATED, out); assertEquals("yeni", s.model()!!.version)
        }
    }
    @Test fun `updater rejects a download whose sha256 does not match and keeps old model`() = runTest {
        MockWebServer().use { server ->
            val newBytes = gz(testModelJson("yeni", "2026-11-15T02:00:00Z"))
            server.start()
            server.enqueue(MockResponse().setBody("""{"schema":1,"model_version":"yeni","sha256":"${"0".repeat(64)}","built_at":"2026-11-15T02:00:00Z"}"""))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(newBytes)))
            val seedBytes = gz(testModelJson()); val s = store(seedBytes, meta(seedBytes))
            assertEquals(ModelUpdater.Outcome.SKIPPED, ModelUpdater(TransportApi(), s).run(server.url("/").toString().trimEnd('/'), ""))
            assertEquals("v1", s.model()!!.version)
        }
    }

    // ---- depo + ağ + çevrimdışı: kip davranışı
    private class MemCache : ResponseCache {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, raw: String) { values[key] = raw }
    }
    private fun repo(config: OfflineConfig, cache: MemCache = MemCache()) = TransportRepository(TransportApi(), cache, provider(), { config }) to cache

    @Test fun `auto mode answers offline on connection failure but not on auth or rate limit`() = runTest {
        val (repo, cache) = repo(OfflineConfig(OfflineMode.AUTO))
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { repo.fetch(base, "", "/v1/stops/11", true) }.exceptionOrNull() is ApiException)
            server.enqueue(MockResponse().setResponseCode(429))
            assertEquals(FailureKind.RATE_LIMIT, (runCatching { repo.fetch(base, "", "/v1/stops/11", true) }.exceptionOrNull() as ApiException).kind)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UPSTREAM_AUTH_FAILED"}}"""))
            assertEquals(FailureKind.AUTH, (runCatching { repo.fetch(base, "", "/v1/stops/11", true) }.exceptionOrNull() as ApiException).kind)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UPSTREAM_RATE_LIMITED"}}"""))
            assertTrue(runCatching { repo.fetch(base, "", "/v1/stops/11", true) }.isFailure)
            // Gerçek kesinti: 504 ve bağlantı kopması çevrimdışı cevap verir
            server.enqueue(MockResponse().setResponseCode(504))
            assertTrue(repo.fetch(base, "", "/v1/stops/11", true).offline)
            server.shutdown()
            assertTrue(repo.fetch(base, "", "/v1/stops/11", true).offline)
            assertTrue(cache.values.isEmpty()) // çevrimdışı cevap önbelleğe yazılmaz
        }
    }
    @Test fun `button mode serves offline without touching the network only when switched on`() = runTest {
        MockWebServer().use { server ->
            server.start(); val base = server.url("/").toString().trimEnd('/')
            val (on, cacheOn) = repo(OfflineConfig(OfflineMode.BUTTON, true))
            assertTrue(on.fetch(base, "", "/v1/stops/11", true).offline)
            assertEquals(0, server.requestCount); assertTrue(cacheOn.values.isEmpty())
            val (off, _) = repo(OfflineConfig(OfflineMode.BUTTON, false))
            server.enqueue(MockResponse().setResponseCode(504))
            assertTrue(runCatching { off.fetch(base, "", "/v1/stops/11", true) }.isFailure) // düğme kapalıyken hata gizlenmez
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `off mode behaves like before`() = runTest {
        MockWebServer().use { server ->
            server.start(); val base = server.url("/").toString().trimEnd('/')
            val (r, _) = repo(OfflineConfig(OfflineMode.OFF, true))
            server.enqueue(MockResponse().setResponseCode(504))
            assertTrue(runCatching { r.fetch(base, "", "/v1/stops/11", true) }.isFailure)
        }
    }
    @Test fun `unsupported path in button mode still goes to the network`() = runTest {
        MockWebServer().use { server ->
            server.start(); val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"data":{"Data":[]},"meta":{"fetched_at":"2026-11-13T02:00:00+00:00","cached":false}}"""))
            val (r, _) = repo(OfflineConfig(OfflineMode.BUTTON, true))
            val res = r.fetch(base, "", "/v1/announcements")
            assertFalse(res.offline); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `bundled seed model is valid matches its meta and answers a real stop`() {
        val dir = java.io.File("src/main/assets")
        val bytes = java.io.File(dir, "offline-model.json.gz").readBytes()
        val meta = ModelMeta.parse(java.io.File(dir, "offline-model-meta.json").readText())!!
        val model = ModelRules.verify(bytes, meta).getOrThrow()
        assertEquals(117, model.lines.size)
        assertTrue(model.stops.size > 1500)
        val wed = ZonedDateTime.parse("2026-11-11T14:00:00+03:00[Europe/Istanbul]")
        val busy = model.stops.keys.first { id -> model.arrivalsAt(id, wed).any { it.kind == OfflineEstimate.Kind.LEARNED } }
        val answer = decodeEnvelope(OfflineProvider({ model }, { wed }).answer("/v1/stops/$busy")!!)
        assertTrue(answer.offline); assertTrue(parseArrivals(answer.objectData.requiredArray("buses")).isNotEmpty())
        assertEquals(0, decodeEnvelope(OfflineProvider({ model }, { wed }).answer("/v1/lines/${model.lines.first().id}/vehicles")!!).arrayData.length())
    }
}
