package com.uzaylikafasi.marasulasim

import android.app.Notification
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
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Release111Test {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun wakeDevice() { device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard") }
    private val stop=Stop(6968,"10111","DEMOKRASİ MEYDANI",37.583,36.926)
    @Test fun mapStopsAreSymbolsAndDisappearWhenZoomedOut() {
        fun find(view:android.view.View):org.maplibre.android.maps.MapView? {
            if(view is org.maplibre.android.maps.MapView) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var map:org.maplibre.android.maps.MapLibreMap?=null
            val ready=java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity -> find(activity.window.decorView)!!.getMapAsync { map=it; ready.countDown() } }
            assertTrue(ready.await(15,java.util.concurrent.TimeUnit.SECONDS))
            var loaded=false
            val deadline=System.currentTimeMillis()+20_000
            while(!loaded && System.currentTimeMillis()<deadline) {
                scenario.onActivity { loaded=map?.style?.getLayer("all-stops")!=null }
                Thread.sleep(200)
            }
            assertTrue("Harita stili yüklenmeli",loaded)
            scenario.onActivity {
                val layer=map!!.style!!.getLayer("all-stops")
                assertTrue(layer is org.maplibre.android.style.layers.SymbolLayer)
                assertEquals(15f,layer!!.minZoom,0f)
                map!!.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(37.583,36.926),11.5))
            }
            Thread.sleep(1000)
            scenario.onActivity { activity ->
                val view=find(activity.window.decorView)!!
                assertTrue(map!!.queryRenderedFeatures(android.graphics.RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"all-stops").isEmpty())
                map!!.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(37.583,36.926),16.0))
            }
            var visible=false
            val renderDeadline=System.currentTimeMillis()+10_000
            while(!visible && System.currentTimeMillis()<renderDeadline) {
                scenario.onActivity { activity ->
                    val view=find(activity.window.decorView)!!
                    if(map!!.cameraPosition.zoom<15) map!!.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(37.583,36.926),16.0))
                    visible=map!!.queryRenderedFeatures(android.graphics.RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"all-stops").isNotEmpty()
                }
                Thread.sleep(200)
            }
            assertTrue("Yakın görünümde durak simgeleri çizilmeli",visible)
            device.takeScreenshot(java.io.File(context.filesDir,"release111-map.png"))
        }
    }
    @Test fun visibleArrivalNotificationRefreshesEtaAndProgressWithoutRecreatingDismissedNotice() {
        ArrivalAlerts.channels(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        val item=ArrivalAlert(id="device-111-refresh",stop=stop,arrival=Arrival("H/1","TEST","12 dk","",lineId="9576"),minutes=10)
        try {
            ArrivalAlerts.add(context,item)
            val first=evaluateAlert(item,listOf(item.arrival.copy(seconds=480)),System.currentTimeMillis())
            assertTrue("Eşik uyarısı bekleniyor",first.notify)
            assertTrue("Test uygulamasının bildirimleri açık olmalı",ArrivalAlerts.enabled(context))
            ArrivalAlerts.applyDecision(context,first)
            fun awaitText(expected:String):Notification {
                val deadline=System.currentTimeMillis()+5000
                while(System.currentTimeMillis()<deadline) {
                    val notice=manager.activeNotifications.firstOrNull { it.tag==item.id }?.notification
                    if(notice?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.contains(expected)==true) return notice
                    Thread.sleep(100)
                }
                throw AssertionError("Bildirim metni güncellenmedi: $expected")
            }
            val old=awaitText("8 dk")
            assertTrue(old.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("8 dk"))
            val next=evaluateAlert(first.alert,listOf(item.arrival.copy(seconds=240)),System.currentTimeMillis())
            assertFalse(next.notify)
            ArrivalAlerts.applyDecision(context,next.copy(alert=next.alert.copy(previousStops=listOf("A"),upcomingStops=listOf("B","C"),progressAt=1)))
            val updated=awaitText("4 dk")
            val text=updated.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            assertTrue(text.contains("4 dk")); assertTrue(text.contains("B → C")); assertFalse(text.contains("8 dk"))
            assertTrue(updated.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            manager.cancel(item.id,0)
            val canceledDeadline=System.currentTimeMillis()+5000
            while(manager.activeNotifications.any { it.tag==item.id } && System.currentTimeMillis()<canceledDeadline) Thread.sleep(100)
            ArrivalAlerts.applyDecision(context,next)
            Thread.sleep(300)
            assertFalse(manager.activeNotifications.any { it.tag==item.id })
        } finally { ArrivalAlerts.remove(context,item.id) }
    }
    @Test fun trackingSheetOffersStopSearchAndShowsSavedProgress() {
        val item=ArrivalAlert(id="device-111-ui",stop=stop,arrival=Arrival("H/1","TEST-111","8 dk","",lineId="9576"),minutes=10,
            active=false,status="Kaynak tahmini: 8 dk",previousStops=listOf("GEÇİLEN TEST"),upcomingStops=listOf("SIRADAKİ TEST"),progressAt=1)
        try {
            ArrivalAlerts.add(context,item)
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("show_tracking",true)).use {
                assertTrue(device.wait(Until.hasObject(By.text("Otobüs ekle")),10_000))
                assertTrue(device.wait(Until.hasObject(By.textContains("SIRADAKİ TEST")),5_000))
                device.findObject(By.text("Otobüs ekle")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Durak adı veya kodu")),5_000))
                val input=device.findObject(By.clazz("android.widget.EditText"))
                input.text="DEMOKRASİ"
                assertTrue(device.wait(Until.hasObject(By.text("DEMOKRASİ MEYDANI")),5_000))
                device.pressBack()
                device.pressBack()
            }
        } finally { ArrivalAlerts.remove(context,item.id) }
    }
}
