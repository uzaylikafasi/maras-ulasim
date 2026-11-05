package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class IntegrationTest {
    @Test fun terminalWithoutEtaRemainsUnknown() {
        val a = parseArrivals(JSONArray("""[{"busCode":"H/1","plate":"46 H 1","remaining_time":"Kalkış bekleniyor","lineId":"9576"}]""")).single()
        assertNull(secondsOf(a))
        assertEquals("9576", a.lineId)
    }
    @Test fun arrivalPreservesDetailsAndPaddingInputs() {
        val a = parseArrivals(JSONArray("""[{"busCode":"H/1","plate":"46 H 1","busName":"Otogar","directionName":"Dönüş","timeToStop":90,"lineId":"9576","nextStopId":2}]""")).single()
        assertEquals(90, secondsOf(a)); assertEquals("Dönüş", a.direction)
        assertEquals(1, stopsBetween(listOf(1,2,3), a.nextStopId, 3))
        assertEquals(35, padSeconds(1,true,1,true))
    }
    @Test fun vehiclesKeepTerminalFieldsAndSpeed() {
        val v = parseVehicles(JSONArray("""[{"plate":"46 H 1","location":{"latitude":37.5,"longitude":36.9},"previousStopId":1,"nextStopId":2,"speed":0}]""")).single()
        assertEquals(1L,v.previousStopId); assertEquals(2L,v.nextStopId); assertEquals(0.0,v.speed!!,0.0)
        assertFalse(Settings().trackOnTap)
    }
}
