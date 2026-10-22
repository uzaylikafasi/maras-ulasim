package com.uzaylikafasi.marasulasim

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.uzaylikafasi.marasulasim.data.suggestedLine
import com.uzaylikafasi.marasulasim.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun clickText(text: String) {
        val view = device.wait(Until.findObject(By.text(text)), 8_000)
        assertNotNull("Expected visible control: $text", view)
        view!!.click()
    }
    @Test fun sourceTimestampOnAndroid() {
        val sample = decodeEnvelope("""{"data":[],"meta":{"fetched_at":"2026-10-22T04:31:46.809358+00:00"}}""")
        assertFalse(sample.isOld(OffsetDateTime.parse("2026-10-22T04:32:00+00:00").toInstant()))
        assertTrue(sample.isOld(OffsetDateTime.parse("2026-10-22T04:33:00+00:00").toInstant()))
    }
    @Test fun optionalUsbApiIntegration() = runBlocking {
        val base = InstrumentationRegistry.getArguments().getString("live_server")
        org.junit.Assume.assumeTrue("USB API integration is opt-in", base != null)
        val result = decodeEnvelope(TransportApi().get(base!!, "", "/v1/lines/9576/vehicles?direction=1"))
        assertNotNull(result.fetchedAt)
        assertFalse("Freshly fetched source data must be usable on Android", result.isOld())
        assertNotNull(parseVehicles(result.arrayData))
    }
    @Test fun navigationFavoritesAndRecreation() {
        val app = context.applicationContext as TransportApplication
        val wasFavorite = runBlocking { app.store.settings.first().lines.any { it.key == suggestedLine.key } }
        if (wasFavorite) runBlocking { app.store.toggleLine(suggestedLine) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("Maraş Ulaşım")), 12_000))
                clickText("Ara")
                assertTrue(device.wait(Until.hasObject(By.text("Bir hat veya durak ara")), 5_000))
                device.findObject(By.desc("H/1 favori seçimini değiştir")).click()
                clickText("Favoriler")
                assertTrue(device.wait(Until.hasObject(By.text("H/1")), 5_000))
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("H/1")), 5_000))
                clickText("Yolculuk")
                assertTrue(device.wait(Until.hasObject(By.text("Nasıl giderim?")), 5_000))
                clickText("Diğer")
                assertTrue(device.wait(Until.hasObject(By.text("Hatırlatmalar")), 5_000))
                assertTrue(device.wait(Until.hasObject(By.text("Uygulamanın kaynak kodu")), 5_000))
                assertFalse(device.hasObject(By.text("Bağlantı ayarları")))
                clickText("Harita")
                assertTrue(device.wait(Until.hasObject(By.text("Maraş Ulaşım")), 5_000))
            }
        } finally {
            val favorite = runBlocking { app.store.settings.first().lines.any { it.key == suggestedLine.key } }
            if (favorite != wasFavorite) runBlocking { app.store.toggleLine(suggestedLine) }
        }
    }
    @Test fun widgetIsRegisteredAndItsLayoutInflates() {
        val component = ComponentName(context, FavoritesWidget::class.java)
        val providers = AppWidgetManager.getInstance(context).installedProviders
        assertTrue(providers.any { it.provider == component })
        instrumentation.runOnMainSync {
            val root = RemoteViews(context.packageName, R.layout.favorites_widget).apply(context, FrameLayout(context))
            assertNotNull(root.findViewById<android.view.View>(R.id.widget_open))
        }
    }
    @Test fun reminderPersistsAndItsReceiverPostsNotification() {
        if (!Reminders.enabled(context)) return
        val item = Reminder(id = "device-smoke-test", title = "Ulaşım test hatırlatması", hour = 23, minute = 59, days = (1..7).toSet())
        try {
            Reminders.save(context, item)
            assertTrue(Reminders.list(context).any { it.id == item.id })
            instrumentation.runOnMainSync {
                ReminderReceiver().onReceive(context, Intent(context, ReminderReceiver::class.java).putExtra("id", item.id))
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            assertTrue(manager.activeNotifications.any { it.id == item.id.hashCode() })
        } finally {
            Reminders.delete(context, item)
            context.getSystemService(NotificationManager::class.java).cancel(item.id.hashCode())
        }
    }
}
