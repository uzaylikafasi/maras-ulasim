package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import com.uzaylikafasi.marasulasim.ui.TrackedBus
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BugRegressionTest {
    @Test fun staleVehicleCannotMoveTrackingCamera() {
        val vehicles = JSONArray("""[{"plate":"46 H 1","location":{"latitude":37.5,"longitude":36.9}}]""")
        val stop = Stop(1,"1","Durak",37.5,36.9)
        val t = TrackedBus(suggestedLine,"46 H 1",stop,ApiResult(vehicles,Instant.now().toString()))
        assertNotNull(t.vehicle())
        assertNull(t.copy(vehicles=t.vehicles!!.copy(stale=true)).vehicle())
        assertNull(t.copy(vehicles=null).vehicle())
    }
    @Test fun slowTerminalLookupDoesNotAgeOrHideSourceArrivals() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val raw = """{"data":{"buses":[{"plate":"TEST","timeToStop":120}]},"meta":{"fetched_at":"${Instant.now()}"}}"""
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(raw))
            val cache = object : ResponseCache { override fun read(key:String):String?=null; override fun write(key:String,raw:String) {} }
            val loader = StopDetails(TransportRepository(TransportApi(),cache),100)
            val result = loader.fetch(Stop(1,"1","Durak",37.5,36.9),Settings(server=server.url("/").toString().trimEnd('/')))
            assertFalse(result.isOld())
            assertEquals("TEST",parseArrivals(result.objectData.requiredArray("buses")).single().plate)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun terminalTrackingKeepsTargetDirectionWhileUsingIncomingPosition() {
        val incoming=ApiResult(JSONArray("""[{"plate":"46 H 1","location":{"latitude":37.5,"longitude":36.9},"speed":0}]"""),Instant.now().toString())
        val returning=Line(161,2,"H/1","Dönüş","Dönüş")
        val tracked=TrackedBus(returning,"46 H 1",Stop(1,"1","Durak",37.5,36.9),incoming,fallbackLine=suggestedLine)
        assertNotNull(tracked.vehicle()); assertEquals(returning,tracked.snapshot().line)
        assertEquals(0.0,tracked.vehicle()!!.speed!!,0.0)
        assertEquals(suggestedLine,tracked.fallbackLine)
    }
    @Test fun malformedEtaCannotOverflowIntoAnImmediateAlert() {
        fun eta(text: String) = secondsOf(Arrival("H/1","46 H 1",text,""))
        assertNull(eta("999999999999999 dk"))
        assertNull(eta("35791394 dk 59 sn"))
        assertNull(eta("-1 dk")); assertNull(eta(""));assertNull(eta("Süre belirtilmedi"))
        assertEquals(90,eta("1 dk 30 sn"))
    }
}
