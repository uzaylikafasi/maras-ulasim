package com.uzaylikafasi.marasulasim

import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Test

class ArrivalAlertTest {
    private val now=1_000_000L
    private val bus=Arrival("H/1","46 H 1","12 dk","",lineId="9576")
    private val alert=ArrivalAlert(id="test",stop=Stop(6968,"1","Durak",37.5,36.9),arrival=bus,minutes=10,createdAt=now)
    @Test fun oneNotificationAtThresholdAndNoneForUnknownEta() {
        assertFalse(evaluateAlert(alert,listOf(bus),now).notify)
        val unknown=bus.copy(remaining="Kalkış bekleniyor")
        assertFalse(evaluateAlert(alert,listOf(unknown),now).notify)
        val first=evaluateAlert(alert,listOf(bus.copy(seconds=600)),now)
        assertTrue(first.notify); assertTrue(first.alert.notified)
        assertFalse(evaluateAlert(first.alert,listOf(bus.copy(seconds=300)),now+30_000).notify)
        assertTrue(parseAlert(first.alert.json()).notified); assertEquals(first.alert.id,parseAlert(first.alert.json()).id)
    }
    @Test fun disappearanceRequiresThreeSamplesAndRecoveryResetsCounter() {
        val missing=evaluateAlert(alert,emptyList(),now).alert
        assertTrue(missing.active);assertEquals(1,missing.missing)
        assertEquals(0,evaluateAlert(missing,listOf(bus),now+30_000).alert.missing)
        val second=evaluateAlert(missing,emptyList(),now+30_000).alert
        assertFalse(evaluateAlert(second,emptyList(),now+60_000).alert.active)
        assertFalse(evaluateAlert(second,null,now+60_000).alert.active)
    }
    @Test fun wrongDirectionCannotFireAndExpiredOrArrivedBusEndsAlert() {
        assertFalse(evaluateAlert(alert,listOf(bus.copy(lineId="161",seconds=300)),now).notify)
        val arrived=evaluateAlert(alert,listOf(bus.copy(seconds=0)),now)
        assertFalse(arrived.notify); assertFalse(arrived.alert.active)
        assertFalse(evaluateAlert(alert,listOf(bus),now+ALERT_LIFETIME_MS).alert.active)
        assertFalse(evaluateAlert(alert,listOf(bus),now-1).alert.active)
    }
    @Test fun missingDataCannotFireOrRepeatAfterRestart() {
        val result=evaluateAlert(alert,null,now)
        assertFalse(result.notify); assertTrue(result.alert.active)
        val done=alert.copy(notified=true)
        assertFalse(evaluateAlert(parseAlert(done.json()),listOf(bus.copy(seconds=60)),now).notify)
    }
}
