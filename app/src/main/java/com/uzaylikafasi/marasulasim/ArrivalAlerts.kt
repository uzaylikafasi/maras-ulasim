package com.uzaylikafasi.marasulasim

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import com.uzaylikafasi.marasulasim.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val ALERT_LIFETIME_MS = 2 * 60 * 60 * 1000L
const val ALERT_POLL_MS = 30_000L

data class ArrivalAlert(val id: String = UUID.randomUUID().toString(), val stop: Stop, val arrival: Arrival,
    val minutes: Int, val createdAt: Long = System.currentTimeMillis(), val notified: Boolean = false,
    val active: Boolean = true, val missing: Int = 0, val status: String = "Süre bekleniyor",
    val previousStops: List<String> = emptyList(), val upcomingStops: List<String> = emptyList(), val progressAt: Long = 0, val checkedAt: Long = 0) {
    fun json(): JSONObject = JSONObject().put("id",id).put("stop",stop.json()).put("code",arrival.code)
        .put("plate",arrival.plate).put("lineId",arrival.lineId).put("minutes",minutes).put("createdAt",createdAt)
        .put("notified",notified).put("active",active).put("missing",missing).put("status",status)
        .put("previousStops",JSONArray(previousStops)).put("upcomingStops",JSONArray(upcomingStops)).put("progressAt",progressAt).put("checkedAt",checkedAt)
}
fun parseAlert(o: JSONObject) = ArrivalAlert(o.getString("id"),parseStop(o.getJSONObject("stop")),
    Arrival(o.getString("code"),o.getString("plate"),"Süre belirtilmedi","",lineId=o.getString("lineId")),
    o.getInt("minutes"),o.getLong("createdAt"),o.optBoolean("notified"),o.optBoolean("active"),o.optInt("missing"),o.optString("status"),
    o.array("previousStops").texts(),o.array("upcomingStops").texts(),o.optLong("progressAt"),o.optLong("checkedAt"))

data class AlertDecision(val alert: ArrivalAlert, val notify: Boolean = false)
/** Yalnız taze kaynak ETA'sı eşik aşımını tetikler; bilinmeyen süre sıfır sayılmaz. */
fun evaluateAlert(alert: ArrivalAlert, arrivals: List<Arrival>?, now: Long): AlertDecision {
    if (!alert.active) return AlertDecision(alert)
    if (now - alert.createdAt >= ALERT_LIFETIME_MS || now < alert.createdAt) return AlertDecision(alert.copy(active=false,status="Takip süresi doldu"))
    if (arrivals == null) {
        val count = alert.missing + 1
        return AlertDecision(alert.copy(missing=count,active=count<3,status=if(count<3) "Güncel veri alınamadı" else "Veri alınamadığı için takip durdu"))
    }
    val found = arrivals.firstOrNull { it.plate == alert.arrival.plate && it.lineId == alert.arrival.lineId }
    if (found == null) {
        val count = alert.missing + 1
        return AlertDecision(alert.copy(missing=count,active=count<3,status=if(count<3) "Otobüs verisi bekleniyor" else "Otobüs geçti veya artık veri bildirmiyor"))
    }
    val seconds = secondsOf(found)
    if (seconds != null && seconds <= 0) return AlertDecision(alert.copy(active=false,status="Otobüs durağa ulaştı"))
    val notify = seconds != null && seconds <= alert.minutes * 60 && !alert.notified
    return AlertDecision(alert.copy(missing=0,notified=alert.notified || notify,
        status=seconds?.let { "Kaynak tahmini: ${formatDuration(it)}" } ?: "Varış süresi bilinmiyor"),notify)
}

fun trackingText(item: ArrivalAlert): String = buildString {
    append("${item.arrival.code} · ${item.arrival.plate} · ${item.stop.name}\n${item.status}")
    if(item.progressAt > 0 && item.upcomingStops.isNotEmpty()) {
        append("\nKaynağa göre son geçilen: "+item.previousStops.joinToString(" → ").ifBlank { "Hat başlangıcı" })
        append("\nSıradaki duraklar: "+item.upcomingStops.joinToString(" → "))
    } else append("\nDurak ilerlemesi şu an doğrulanamıyor")
    if(item.checkedAt > 0) append("\nSon kontrol: "+java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale("tr","TR")).format(java.util.Date(item.checkedAt)))
}

object ArrivalAlerts {
    const val CHANNEL = "arrival-alerts"
    const val SERVICE_CHANNEL = "bus-tracking"
    const val SERVICE_ID = 1100
    val revision = MutableStateFlow(0)
    private fun prefs(c: Context) = c.getSharedPreferences("arrival-alerts",Context.MODE_PRIVATE)
    @Synchronized fun list(c: Context): List<ArrivalAlert> = runCatching {
        JSONArray(prefs(c).getString("items","[]")).objects().map(::parseAlert)
    }.getOrDefault(emptyList())
    private fun write(c: Context, items: List<ArrivalAlert>) {
        prefs(c).edit().putString("items",JSONArray(items.map { it.json() }).toString()).commit()
        revision.value++
    }
    @Synchronized fun add(c: Context, alert: ArrivalAlert) {
        require(alert.minutes in 1..60 && alert.arrival.plate.isNotBlank() && alert.arrival.lineId.toLongOrNull() != null)
        val previous=list(c)
        val replaced=previous.filter { it.stop.id == alert.stop.id && it.arrival.plate == alert.arrival.plate && it.arrival.lineId == alert.arrival.lineId }
        val old=previous-replaced.toSet()
        require(old.count { it.active } < 4) { "Aynı anda en fazla 4 otobüs takip edebilirsin." }
        write(c,old.filter { !it.active }.takeLast(19) + old.filter { it.active } + alert)
        replaced.forEach { c.getSystemService(NotificationManager::class.java).cancel(it.id,0) }
    }
    @Synchronized fun update(c: Context, alert: ArrivalAlert): Boolean {
        val old = list(c)
        if (old.none { it.id == alert.id && it.active }) return false
        if(old.first { it.id==alert.id } != alert) write(c,old.map { if(it.id==alert.id) alert else it })
        return true
    }
    @Synchronized fun applyDecision(c: Context, decision: AlertDecision) {
        if(!update(c,decision.alert)) return
        val item=decision.alert
        val manager=c.getSystemService(NotificationManager::class.java)
        // Eşik uyarısı bir kez ses çıkarır; açık bildirimdeki tahmin sonraki sorgularda yenilenir.
        // Kullanıcı kapattığı bildirimi her sorguda yeniden oluşturma.
        val visible = manager.activeNotifications.any { it.tag == item.id && it.id == 0 }
        if((decision.notify || (item.notified && item.active && visible)) && enabled(c)) {
            runCatching { manager.notify(item.id,0,notification(c,"${item.arrival.code} · ${item.arrival.plate}",trackingText(item))) }
                .onFailure { update(c,item.copy(active=false,notified=false,status="Bildirim gönderilemedi; telefonun bildirim ayarlarını kontrol et")) }
        }
        if(!item.active) manager.cancel(item.id,0)
    }
    @Synchronized fun remove(c: Context, id: String) {
        write(c,list(c).filterNot { it.id==id })
        c.getSystemService(NotificationManager::class.java).cancel(id,0)
    }
    fun channels(c: Context) {
        c.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL,"Otobüs varış uyarıları",NotificationManager.IMPORTANCE_DEFAULT))
            createNotificationChannel(NotificationChannel(SERVICE_CHANNEL,"Otobüs takibi",NotificationManager.IMPORTANCE_LOW))
        }
    }
    fun enabled(c: Context): Boolean {
        channels(c)
        return (Build.VERSION.SDK_INT<33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) &&
            c.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            c.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE &&
            c.getSystemService(NotificationManager::class.java).getNotificationChannel(SERVICE_CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    }
    fun start(c: Context) { c.startForegroundService(Intent(c,ArrivalTrackingService::class.java)) }
    fun open(c: Context): PendingIntent = PendingIntent.getActivity(c,1100,
        Intent(c,MainActivity::class.java).putExtra("show_tracking",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun notification(c: Context, title: String, text: String, ongoing: Boolean = false): Notification =
        Notification.Builder(c,if(ongoing) SERVICE_CHANNEL else CHANNEL).setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(c)).setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing).build()
}

/** Kullanıcının ekranda başlattığı, en çok iki saatlik dataSync ön plan hizmeti. Telefon GPS'i gerekmez. */
class ArrivalTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ArrivalAlerts.channels(this)
        val notice = ArrivalAlerts.notification(this,"Otobüs takibi açık","Takip listesini görmek veya otobüs kaldırmak için dokun.",true)
        try { if(Build.VERSION.SDK_INT>=29) startForeground(ArrivalAlerts.SERVICE_ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ArrivalAlerts.SERVICE_ID,notice)
        } catch(_:RuntimeException) {
            ArrivalAlerts.list(this).filter { it.active }.forEach { ArrivalAlerts.update(this,it.copy(active=false,status="Telefon takip hizmetini başlatamadı; yeniden uyarı kurabilirsin")) }
            stopSelf(); return START_NOT_STICKY
        }
        if(job?.isActive != true) job = scope.launch { poll() }
        return START_REDELIVER_INTENT
    }
    private suspend fun poll() {
        val app = application as TransportApplication
        val progressLoader = BusProgressLoader(app.repository)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"maras:arrival").apply { acquire(ALERT_LIFETIME_MS+60_000) }
        val sessionStarted = SystemClock.elapsedRealtime()
        try {
            while(currentCoroutineContext().isActive) {
                val items = ArrivalAlerts.list(this).filter { it.active }
                if(items.isEmpty() || !ArrivalAlerts.enabled(this) || SystemClock.elapsedRealtime()-sessionStarted>=ALERT_LIFETIME_MS) break
                val settings = app.store.settings.first()
                for((stop, group) in items.groupBy { it.stop.id }) {
                    val now = System.currentTimeMillis()
                    val arrivals = if(group.all { now-it.createdAt>=ALERT_LIFETIME_MS }) null else try {
                        val r = app.repository.fetch(settings.server,settings.apiKey,"/v1/stops/$stop",true)
                        if(r.isOld()) null else parseArrivals(r.objectData.requiredArray("buses"))
                    } catch(e: CancellationException) { throw e } catch(_:Exception) { null }
                    for(item in group) {
                        var decision = evaluateAlert(item,arrivals,System.currentTimeMillis())
                        val found = arrivals?.firstOrNull { it.plate == item.arrival.plate && it.lineId == item.arrival.lineId }
                        val progress = if (found != null && decision.alert.active) try {
                            withTimeoutOrNull(12_000) { progressLoader.load(settings.server,settings.apiKey,found,item.stop.id) }
                        } catch(e: CancellationException) { throw e } catch(_:Exception) { null } else null
                        decision = decision.copy(alert=decision.alert.copy(
                            previousStops=progress?.previous.orEmpty(), upcomingStops=progress?.upcoming.orEmpty(),
                            progressAt=if(progress!=null) System.currentTimeMillis() else 0, checkedAt=System.currentTimeMillis()))
                        // Önce kalıcı olarak işaretle: hizmet yeniden başlarsa aynı uyarı tekrarlanmaz.
                        ArrivalAlerts.applyDecision(this,decision)
                    }
                }
                val active = ArrivalAlerts.list(this).filter { it.active }
                if(active.isEmpty()) break
                val summary = active.joinToString("\n\n") { item -> trackingText(item) }
                getSystemService(NotificationManager::class.java).notify(ArrivalAlerts.SERVICE_ID,
                    ArrivalAlerts.notification(this,"Otobüs takibi · ${active.size} otobüs",summary,true))
                delay(ALERT_POLL_MS)
            }
        } finally {
            if(wake?.isHeld==true) wake?.release()
            ArrivalAlerts.list(this).filter { it.active }.forEach { ArrivalAlerts.update(this,it.copy(active=false,status="Takip durdu; yeniden uyarı kurabilirsin")) }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); if(wake?.isHeld==true) wake?.release(); super.onDestroy() }
}
