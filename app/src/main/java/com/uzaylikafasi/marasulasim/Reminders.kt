package com.uzaylikafasi.marasulasim

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.uzaylikafasi.marasulasim.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID

data class Reminder(val id: String = UUID.randomUUID().toString(), val title: String,
                    val hour: Int, val minute: Int, val days: Set<Int>) {
    fun json() = JSONObject().put("id", id).put("title", title).put("hour", hour).put("minute", minute)
        .put("days", JSONArray(days.sorted()))
}

fun nextOccurrence(hour: Int, minute: Int, days: Set<Int>, now: ZonedDateTime): ZonedDateTime {
    require(hour in 0..23 && minute in 0..59 && days.isNotEmpty() && days.all { it in 1..7 })
    return (0..7).map { now.toLocalDate().plusDays(it.toLong()).atTime(hour, minute).atZone(now.zone) }
        .first { it.dayOfWeek.value in days && it.isAfter(now) }
}

object Reminders {
    private const val CHANNEL = "trip-reminders"
    private fun preferences(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    fun list(context: Context): List<Reminder> = runCatching {
        JSONArray(preferences(context).getString("items", "[]")).objects().map { o ->
            val days = o.getJSONArray("days")
            Reminder(o.getString("id"), o.getString("title"), o.getInt("hour"), o.getInt("minute"),
                (0 until days.length()).map { days.getInt(it) }.toSet())
        }
    }.getOrDefault(emptyList())
    fun save(context: Context, reminder: Reminder) {
        require(reminder.title.isNotBlank() && reminder.title.length <= 80)
        nextOccurrence(reminder.hour, reminder.minute, reminder.days, ZonedDateTime.now())
        val values = list(context).filter { it.id != reminder.id } + reminder
        preferences(context).edit().putString("items", JSONArray(values.map { it.json() }).toString()).apply()
        schedule(context, reminder)
    }
    fun delete(context: Context, reminder: Reminder) {
        val values = list(context).filter { it.id != reminder.id }
        preferences(context).edit().putString("items", JSONArray(values.map { it.json() }).toString()).apply()
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, reminder.id))
    }
    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction("trip.$id").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, reminder: Reminder) {
        val next = nextOccurrence(reminder.hour, reminder.minute, reminder.days, ZonedDateTime.now())
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending(context, reminder.id))
    }
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Yolculuk hatırlatmaları", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun enabled(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun show(context: Context, reminder: Reminder) {
        if (!enabled(context)) return
        channel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_bus).setColor(0xFF007B83.toInt())
            .setContentTitle(reminder.title).setContentText("Çıkmadan önce hat ve sefer bilgilerini kontrol et.")
            .setContentIntent(open).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(reminder.id.hashCode(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val item = Reminders.list(context).firstOrNull { it.id == intent.getStringExtra("id") } ?: return
        Reminders.show(context, item)
        Reminders.schedule(context, item)
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        Reminders.list(context).forEach { Reminders.schedule(context, it) }
    }
}
