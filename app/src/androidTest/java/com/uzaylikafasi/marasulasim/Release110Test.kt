package com.uzaylikafasi.marasulasim

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.uzaylikafasi.marasulasim.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Release110Test {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Test fun rangeAndCatalogKeepDistantFavoritesAndJourneyInputs() {
        val all=org.json.JSONObject(context.assets.open("all-stops.json").bufferedReader().use { it.readText() }).getJSONArray("stops").objects()
            .map { Stop(it.getLong("id"),it.optString("code"),it.optString("name"),it.getDouble("lat"),it.getDouble("lng")) }
        val shown=stopsInRange(all,Coordinate(37.583,36.926),750.0)
        assertEquals(27,shown.size); assertEquals(1467,all.size)
        val far=all.first { it !in shown }
        assertEquals(far.id,Settings(stops=listOf(far)).stops.single().id)
        assertTrue(journeyPath(shown.first(),far,1,8,0).contains("to_stop_id=${far.id}"))
        assertEquals(30_000L,LOCATION_INTERVAL_MS)
    }
    @Test fun trackingNotificationOpensListAndRemovalCancelsIt() {
        val item=ArrivalAlert(id="device-110",stop=Stop(6968,"10111","DEMOKRASİ MEYDANI",37.583,36.926),
            arrival=Arrival("H/1","TEST","Süre belirtilmedi","",lineId="9576"),minutes=10,active=false,status="Test kaydı")
        try {
            ArrivalAlerts.add(context,item)
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("show_tracking",true)).use {
                assertTrue(device.wait(Until.hasObject(By.text("Takip")),10_000))
                assertTrue(device.wait(Until.hasObject(By.text("H/1 · TEST")),5_000))
                device.findObject(By.text("Kaldır")).click()
                assertTrue(device.wait(Until.gone(By.text("H/1 · TEST")),5_000))
                assertFalse(ArrivalAlerts.list(context).any { it.id==item.id })
            }
        } finally { ArrivalAlerts.remove(context,item.id) }
    }
    @Test fun liveArrivalAlertNotifiesOnceWhileAppIsInBackground() = kotlinx.coroutines.runBlocking {
        assumeTrue("Bildirim izni gerekli",ArrivalAlerts.enabled(context))
        val app=context.applicationContext as TransportApplication
        val result=app.repository.fetch(DEFAULT_SERVER,"","/v1/stops/6968",true)
        assumeTrue("Taze kaynak verisi gerekli",!result.isOld())
        val arrival=parseArrivals(result.objectData.requiredArray("buses")).firstOrNull {
            secondsOf(it) in 60..1200 && it.lineId.toLongOrNull()!=null && it.plate.isNotBlank()
        }
        assumeTrue("Canlı yaklaşan otobüs gerekli",arrival!=null)
        val item=ArrivalAlert(id="device-110-live",stop=Stop(6968,"10111","DEMOKRASİ MEYDANI",37.583,36.926),arrival=arrival!!,minutes=60)
        val manager=context.getSystemService(NotificationManager::class.java)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ArrivalAlerts.add(context,item)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { ArrivalAlerts.start(context) }
                device.pressHome()
                val deadline=System.currentTimeMillis()+25_000
                while(System.currentTimeMillis()<deadline && !ArrivalAlerts.list(context).first { it.id==item.id }.notified) Thread.sleep(200)
                assertTrue(ArrivalAlerts.list(context).first { it.id==item.id }.notified)
                val first=manager.activeNotifications.first { it.tag==item.id }
                Thread.sleep(35_000)
                val after=manager.activeNotifications.firstOrNull { it.tag==item.id }
                // Araç geçmişse bildirim kaldırılabilir; duruyorsa tekrar gönderilmemeli.
                if(after!=null) assertEquals(first.postTime,after.postTime)
                assertTrue(ArrivalAlerts.list(context).first { it.id==item.id }.notified)
            }
        } finally { ArrivalAlerts.remove(context,item.id); context.stopService(Intent(context,ArrivalTrackingService::class.java)) }
    }
    @Test fun foregroundServiceExpiresAlertWithoutUsingPhoneGps() {
        assumeTrue("Bildirim izni gerekli",ArrivalAlerts.enabled(context))
        val item=ArrivalAlert(id="device-110-service",stop=Stop(6968,"10111","Durak",37.583,36.926),
            arrival=Arrival("H/1","TEST","Süre belirtilmedi","",lineId="9576"),minutes=10,createdAt=System.currentTimeMillis()-ALERT_LIFETIME_MS-1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ArrivalAlerts.add(context,item)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { ArrivalAlerts.start(context) }
                val deadline=System.currentTimeMillis()+8_000
                while(System.currentTimeMillis()<deadline && ArrivalAlerts.list(context).first { it.id==item.id }.active) Thread.sleep(100)
                val saved=ArrivalAlerts.list(context).first { it.id==item.id }
                assertFalse(saved.active); assertEquals("Takip süresi doldu",saved.status)
                assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag==item.id })
            }
        } finally { ArrivalAlerts.remove(context,item.id); context.stopService(Intent(context,ArrivalTrackingService::class.java)) }
    }
}
