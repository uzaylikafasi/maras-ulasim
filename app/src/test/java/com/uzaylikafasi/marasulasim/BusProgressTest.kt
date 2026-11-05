package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Test

class BusProgressTest {
    private val stops=(1L..12L).map { Stop(it,"$it","Durak $it",37.5,36.9) }
    @Test fun sourceOrderSeparatesPassedStopsFromNextFive() {
        val progress=busProgress(stops,5,9)!!
        assertEquals(listOf("Durak 2","Durak 3","Durak 4"),progress.previous)
        assertEquals(listOf("Durak 5","Durak 6","Durak 7","Durak 8","Durak 9"),progress.upcoming)
        assertTrue(busProgress(stops,1,9)!!.previous.isEmpty())
        assertEquals(listOf("Durak 12"),busProgress(stops,12,12)!!.upcoming)
    }
    @Test fun unknownOppositeOrLoopedIdsCannotInventProgress() {
        assertNull(busProgress(stops,0,9))
        assertNull(busProgress(stops,99,9))
        assertNull(busProgress(stops,5,99))
        assertNull(busProgress(stops,10,9))
        assertNull(busProgress(stops+stops.first(),1,9))
        assertNull(busProgress(stops+stops[8],5,9))
    }
    @Test fun oldAlertsMigrateWithoutDeletingTheirThresholdOrNotificationState() {
        val alert=ArrivalAlert(stop=stops[8],arrival=Arrival("H/1","TEST","10 dk","",lineId="9576"),minutes=7,notified=true)
        val json=alert.json()
        listOf("previousStops","upcomingStops","progressAt","checkedAt").forEach { json.remove(it) }
        val restored=parseAlert(json)
        assertEquals(7,restored.minutes); assertTrue(restored.notified)
        assertTrue(restored.upcomingStops.isEmpty()); assertEquals(0L,restored.checkedAt)
        val enriched=restored.copy(previousStops=listOf("A"),upcomingStops=listOf("B","C"),progressAt=123,checkedAt=124)
        assertEquals(enriched,parseAlert(enriched.json()))
        assertTrue(trackingText(enriched).contains("Sıradaki duraklar: B → C"))
        assertFalse(trackingText(restored).contains("Sıradaki duraklar:"))
    }
}
