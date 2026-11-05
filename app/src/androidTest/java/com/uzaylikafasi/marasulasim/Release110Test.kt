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
import kotlinx.coroutines.flow.first
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
    @Test fun updateApkSignatureAndFileProviderAreCheckedOnAndroid() {
        val updater=AppUpdates(context)
        val folder=java.io.File(context.cacheDir,"updates").apply { mkdirs() }
        val apk=java.io.File(folder,"update.apk")
        try {
            apk.delete()
            assertTrue("Eksik APK yükleyiciye gönderilmemeli",runCatching { updater.install(apk) }.isFailure)
            java.io.File(context.applicationInfo.sourceDir).copyTo(apk,overwrite=true)
            val flags=android.content.pm.PackageManager.GET_SIGNATURES or (if(android.os.Build.VERSION.SDK_INT>=28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else 0)
            val archive=context.packageManager.getPackageArchiveInfo(apk.absolutePath,flags)!!
            val installed=context.packageManager.getPackageInfo(context.packageName,flags)
            assertEquals("Arşiv paket adı",context.packageName,archive.packageName)
            assertEquals("Arşiv sürümü",BuildConfig.VERSION_NAME,archive.versionName)
            @Suppress("DEPRECATION")
            fun certificates(info:android.content.pm.PackageInfo)=
                if(android.os.Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners ?: info.signatures else info.signatures
            assertFalse(certificates(archive).isNullOrEmpty())
            assertArrayEquals("İmza sertifikaları",certificates(installed),certificates(archive))
            updater.verifyPackage(apk,BuildConfig.VERSION_NAME)
            assertTrue(runCatching { updater.verifyPackage(apk,"99.0.0") }.isFailure)
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",apk)
            assertEquals("content",uri.scheme)
            context.contentResolver.openInputStream(uri).use { assertNotNull(it); assertTrue(it!!.read()>=0) }
            val outside=java.io.File(context.cacheDir,"outside.apk").apply { writeText("test") }
            try { assertTrue(runCatching { androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",outside) }.isFailure) }
            finally { outside.delete() }
            val install=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertNotNull(context.packageManager.resolveActivity(install,0))
            val appOp=device.executeShellCommand("cmd appops get ${context.packageName} REQUEST_INSTALL_PACKAGES")
            val originalMode=Regex("REQUEST_INSTALL_PACKAGES: (\\w+)").find(appOp)?.groupValues?.get(1) ?: "default"
            try {
                device.executeShellCommand("cmd appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
                assertTrue(context.packageManager.canRequestPackageInstalls())
                ActivityScenario.launch(MainActivity::class.java).use {
                    updater.install(apk)
                    val chooser=device.wait(Until.findObject(By.text("Paket yükleyici")),2_000)
                    if(chooser!=null) {
                        chooser.click()
                        device.wait(Until.findObject(By.text("Yalnızca bir defa")),2_000)?.click()
                    }
                    val shown=device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("(?iu)(yükle|kur|install|güncelle)"))),10_000)
                    device.takeScreenshot(java.io.File(context.filesDir,"android-yukleyici.png"))
                    device.dumpWindowHierarchy(java.io.File(context.filesDir,"android-yukleyici.xml"))
                    assertTrue("Android yükleme onayı",shown)
                    device.pressBack() // Sistem yükleyicisini onaylamadan kapat.
                }
            } finally { device.executeShellCommand("cmd appops set ${context.packageName} REQUEST_INSTALL_PACKAGES $originalMode") }

        } finally { apk.delete() }
    }
    @Test fun updatePromptAndReadyApkSurviveRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var original: UpdateViewModel?=null
            scenario.onActivity { activity ->
                original=androidx.lifecycle.ViewModelProvider(activity)[UpdateViewModel::class.java]
            }
            // Günlük kontrolün tamamlanması için ana kuyruğu boşalt.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                original!!.state.value=UpdateState(release=AppRelease("99.0.0","Deneme sürüm notları","",1,null),file=java.io.File(context.cacheDir,"updates/update.apk"))
            }
            assertTrue(device.wait(Until.hasObject(By.text("Yeni sürüm: 99.0.0")),5_000))
            scenario.recreate()
            scenario.onActivity { assertSame(original,androidx.lifecycle.ViewModelProvider(it)[UpdateViewModel::class.java]) }
            assertTrue(device.wait(Until.hasObject(By.text("Yeni sürüm: 99.0.0")),5_000))
            assertTrue(device.hasObject(By.text("Kur")))
            device.findObject(By.text("Sonra")).click()
        }
    }
    @Test fun replacingAndRemovingAlertCannotLeaveAnOrphanNotification() {
        val manager=context.getSystemService(NotificationManager::class.java)
        val first=ArrivalAlert(id="device-110-first",stop=Stop(6968,"10111","Durak",37.583,36.926),arrival=Arrival("H/1","TEST","2 dk","",lineId="9576"),minutes=10)
        val second=first.copy(id="device-110-second")
        try {
            ArrivalAlerts.add(context,first)
            ArrivalAlerts.applyDecision(context,evaluateAlert(first,listOf(first.arrival),System.currentTimeMillis()))
            ArrivalAlerts.add(context,second)
            assertFalse(manager.activeNotifications.any { it.tag==first.id })
            ArrivalAlerts.remove(context,second.id)
            ArrivalAlerts.applyDecision(context,evaluateAlert(second,listOf(second.arrival),System.currentTimeMillis()))
            assertFalse(manager.activeNotifications.any { it.tag==second.id })
            assertFalse(ArrivalAlerts.list(context).any { it.id in setOf(first.id,second.id) })
        } finally { ArrivalAlerts.remove(context,first.id);ArrivalAlerts.remove(context,second.id) }
    }
    @Test fun githubApkDownloadRejectsDifferentApplicationAndCleansPartialFile() = kotlinx.coroutines.runBlocking {
        val client=okhttp3.OkHttpClient()
        val release=client.newCall(okhttp3.Request.Builder().url("https://api.github.com/repos/uzaylikafasi/maras-ulasim/releases/tags/v1.0.1").build()).execute().use {
            assertTrue(it.isSuccessful)
            parseRelease(org.json.JSONObject(it.body!!.string()),"1.0.0",listOf("arm64-v8a"),null)!!
        }
        val failure=runCatching { AppUpdates(context).download(release) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("APK imzası veya sürümü uygulamayla eşleşmiyor.",failure!!.message)
        assertFalse(java.io.File(context.cacheDir,"updates/update.part.apk").exists())
        assertFalse(java.io.File(context.cacheDir,"updates/update.apk").exists())
    }
    @Test fun distantStopSearchFavoritesAndJourneyWorkAfterMapRangeChanges() = kotlinx.coroutines.runBlocking {
        val app=context.applicationContext as TransportApplication
        val stop=Stop(9304,"12141","OTOGAR HAREKET AMİRLİĞİ",37.55122,36.92099)
        val original=app.store.settings.first().stops.any { it.id==stop.id }
        fun click(text:String) { val node=device.wait(Until.findObject(By.text(text)),15_000);assertNotNull(text,node);node!!.click() }
        fun search(code:String,name:String) {
            val input=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5_000)!!
            input.click();input.text=code
            assertTrue(device.wait(Until.hasObject(By.text(name)),20_000))
            device.pressBack()
            click(name)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click("Ara");search("10111","DEMOKRASİ MEYDANI");click("Haritada")
                Thread.sleep(1500) // Harita kamerası ve menzil kaynağı güncellensin.
                assertTrue(distanceMeters(Coordinate(37.583,36.926),Coordinate(stop.latitude!!,stop.longitude!!))>750)
                click("Ara");search("12141",stop.name)
                if(!original) click("Favori")
                device.findObject(By.desc("Kapat")).click();click("Favoriler");click(stop.name);click("Buradan")
                assertTrue(device.wait(Until.hasObject(By.text(stop.name)),5_000))
                click("Varış durağı seç");search("10111","DEMOKRASİ MEYDANI")
                click("Rota bul")
                var vm:com.uzaylikafasi.marasulasim.ui.TransportViewModel?=null
                scenario.onActivity { vm=androidx.lifecycle.ViewModelProvider(it)[com.uzaylikafasi.marasulasim.ui.TransportViewModel::class.java] }
                val deadline=System.currentTimeMillis()+25_000
                while(System.currentTimeMillis()<deadline && vm!!.journey.value.loading) Thread.sleep(100)
                assertNull(vm!!.journey.value.error)
                val result=vm!!.journey.value.result
                assertNotNull("Canlı yolculuk sonucu",result)
                assertTrue(parseJourneys(result!!.objectData).isNotEmpty())
            }
        } finally {
            val current=app.store.settings.first().stops.any { it.id==stop.id }
            if(current!=original) app.store.toggleStop(stop)
        }
    }
    @Test fun arrivalCardTrackingAndMinutePickerWorkTogether() = kotlinx.coroutines.runBlocking {
        val app=context.applicationContext as TransportApplication
        val settings=app.store.settings.first()
        val before=ArrivalAlerts.list(context).map { it.id }.toSet()
        val created=System.currentTimeMillis()
        fun click(text:String) {
            val node=device.wait(Until.findObject(By.text(text)),15_000)
            if(node==null) {
                device.dumpWindowHierarchy(java.io.File(context.filesDir,"ui-failure.xml"))
                device.takeScreenshot(java.io.File(context.filesDir,"ui-failure.png"))
                println("Dokunma başarısız: $text, paket=${device.currentPackageName}")
            }
            assertNotNull(text,node);node!!.click()
        }
        try {
            app.store.setTrackOnTap(false);app.store.setFavoritesOnly(false)
            ActivityScenario.launch(MainActivity::class.java).use {
                click("Ara")
                val search=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5_000)!!
                search.click();search.text="10111"
                assertTrue(device.wait(Until.hasObject(By.text("DEMOKRASİ MEYDANI")),20_000))
                device.pressBack();click("DEMOKRASİ MEYDANI")
                var row:androidx.test.uiautomator.UiObject2?=null
                for(attempt in 0..12) {
                    row=device.findObject(By.textContains(" · 46"))
                    if(row!=null && row.visibleBounds.height()>30 && row.visibleCenter.y in 400..1600) break
                    row=null
                    device.swipe(500,1650,500,650,20);Thread.sleep(300)
                }
                device.waitForIdle();Thread.sleep(750)
                row=device.findObjects(By.textContains(" · 46")).firstOrNull { it.visibleBounds.height()>30 && it.visibleCenter.y in 400..1800 }
                assertNotNull("Yaklaşan otobüs kartı",row);row!!.parent.click()
                click("Haritada izle")
                val info=device.wait(Until.findObject(By.desc("Otobüs bilgileri")),20_000)
                assertNotNull("Takip bilgi düğmesi",info);info!!.click()
                click("Varış uyarısı")
                val minutes=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5_000)!!
                assertEquals("10",minutes.text);minutes.click();minutes.text="7";device.pressBack();device.waitForIdle()
                click("Uyarı kur")
                val savedBy=System.currentTimeMillis()+5_000
                while(System.currentTimeMillis()<savedBy && ArrivalAlerts.list(context).none { it.createdAt>=created && it.minutes==7 }) Thread.sleep(100)
                val saved=ArrivalAlerts.list(context).any { it.createdAt>=created && it.minutes==7 }
                if(!saved) {
                    device.dumpWindowHierarchy(java.io.File(context.filesDir,"ui-failure.xml"))
                    device.takeScreenshot(java.io.File(context.filesDir,"ui-failure.png"))
                }
                assertTrue("Seçilen 7 dakika kaydedilmeli",saved)
                device.pressBack();click("Diğer");click("Bildirimler")
                assertTrue(device.wait(Until.hasObject(By.textContains("7 dk kala")),5_000))
            }
        } finally {
            ArrivalAlerts.list(context).filter { it.id !in before }.forEach { ArrivalAlerts.remove(context,it.id) }
            app.store.setTrackOnTap(settings.trackOnTap);app.store.setFavoritesOnly(settings.favoritesOnly)
            context.stopService(Intent(context,ArrivalTrackingService::class.java))
        }
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
