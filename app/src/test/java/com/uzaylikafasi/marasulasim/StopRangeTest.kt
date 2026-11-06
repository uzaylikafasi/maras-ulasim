package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class StopRangeTest {
    private val center = Coordinate(37.583,36.926)
    private val near = Stop(1,"1","Yakın",37.583,36.926)
    private val far = Stop(2,"2","Uzak",37.683,36.926)
    @Test fun selectsOnlyStopsInRangeAndLeavesCatalogIntact() {
        val all = listOf(near,far)
        assertEquals(listOf(near), stopsInRange(all,center,750.0))
        assertEquals(listOf(near,far),all)
        assertEquals(far,all.first { it.name == "Uzak" })
        assertEquals(far,Settings(stops=listOf(far)).stops.single())
        assertTrue(journeyPath(near,far,1,8,0).contains("to_stop_id=2"))
    }
    @Test fun emptyRangeExpandsAndMissingCoordinatesDoNotLoop() {
        assertEquals(listOf(far),stopsInRange(listOf(far),center,1.0))
        assertEquals(emptyList<Stop>(), stopsInRange(listOf(near.copy(latitude=null)),center,750.0))
        assertEquals(30_000L,LOCATION_INTERVAL_MS)
    }
    @Test fun stopRangeSettingHasASensibleDefaultAndClampsToTheAllowedPresets() {
        // Ayarlardan gelen menzil kullanıcı kararı: varsayılan eski sabit degerle (750 m) ayni, sinirlar disina tasmaz.
        assertEquals(DEFAULT_STOP_RANGE_METERS, Settings().stopRangeMeters)
        assertEquals(750, DEFAULT_STOP_RANGE_METERS)
        assertEquals(MIN_STOP_RANGE_METERS, clampStopRange(0))
        assertEquals(MAX_STOP_RANGE_METERS, clampStopRange(1_000_000))
        assertEquals(1500, clampStopRange(1500))
    }
    @Test fun invalidStopCoordinatesCannotCauseAnEndlessExpansion() {
        assertTrue(stopsInRange(listOf(near.copy(latitude=Double.NaN)),center,750.0).isEmpty())
        assertTrue(runCatching { stopsInRange(listOf(near),Coordinate(Double.NaN,36.9),750.0) }.isFailure)
    }
    @Test fun realCatalogRenderingReducesFeaturesWithoutLosingSearchOrFavorites() {
        val file = listOf(File("src/main/assets/all-stops.json"),File("app/src/main/assets/all-stops.json")).first { it.exists() }
        val all = JSONObject(file.readText()).getJSONArray("stops").objects().map { Stop(it.getLong("id"),it.optString("code"),it.optString("name"),it.getDouble("lat"),it.getDouble("lng")) }
        val shown = stopsInRange(all,center,750.0)
        assertTrue(shown.isNotEmpty()); assertTrue(shown.size < all.size / 2)
        val outside = all.first { it !in shown }
        val parsed = parseSearch(JSONObject().put("busStops",org.json.JSONArray(listOf(outside.json()))))
        assertEquals(outside.id,parsed.stops.single().id)
        assertEquals(outside.id,Settings(stops=listOf(outside)).stops.single().id)
        println("Menzil: ${shown.size}/${all.size}; arama/favori/yolculuk tüm dizine erişir")
    }
}
