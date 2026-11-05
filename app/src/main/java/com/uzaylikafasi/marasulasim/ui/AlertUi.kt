@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.uzaylikafasi.marasulasim.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uzaylikafasi.marasulasim.*
import com.uzaylikafasi.marasulasim.data.Arrival
import com.uzaylikafasi.marasulasim.data.Stop

@Composable
fun ArrivalAlertDialog(stop: Stop, arrival: Arrival, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var minutes by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("10") }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        if(!ArrivalAlerts.enabled(context)) { error="Bildirimler kapalı. Telefon ayarlarından varış ve takip bildirimlerini aç."; return }
        runCatching {
            val item = ArrivalAlert(stop=stop,arrival=arrival,minutes=minutes.toIntOrNull() ?: 0)
            ArrivalAlerts.add(context,item)
            try { ArrivalAlerts.start(context) } catch(e:Exception) { ArrivalAlerts.remove(context,item.id); throw e }
        }.onSuccess { onDismiss() }.onFailure { error=it.message ?: "Uyarı kurulamadı. Yeniden dene." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok) save() else error="Uyarı için bildirim izni gerekiyor. İzin vermeden takip uyarısı kurulmaz."
    }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("Varış uyarısı") },text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("${arrival.code} · ${arrival.plate}\n${stop.name}")
        OutlinedTextField(value=minutes,onValueChange={ minutes=it.filter(Char::isDigit).take(2) },label={ Text("Kaç dakika kala? (1–60)") },singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
        Text("Kaynak tahmini bu süreye indiğinde bir kez bildirilir. Süre bilinmiyorsa beklenir. Takip en çok 2 saat sürer; Bildirimler > Takip'ten kaldırabilirsin.")
        error?.let { Text(it,color=MaterialTheme.colorScheme.error)
            TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }) { Text("Bildirim ayarları") }
        }
    } },confirmButton={ TextButton(enabled=(minutes.toIntOrNull() ?: 0) in 1..60 && arrival.plate.isNotBlank() && arrival.lineId.toLongOrNull()!=null,onClick={
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS) else save()
    }) { Text("Uyarı kur") } },dismissButton={ TextButton(onClick=onDismiss) { Text("Vazgeç") } })
}

@Composable
fun TrackingSheet(onDismiss: () -> Unit, onView: (ArrivalAlert) -> Unit, onReminders: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    val revision by ArrivalAlerts.revision.collectAsStateWithLifecycle()
    val items = remember(revision) { ArrivalAlerts.list(context) }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.padding(20.dp).padding(bottom=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Bildirimler",style=MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("Takip",style=MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick=onAdd) { Text("Otobüs ekle") }
            }
            if(items.isEmpty()) Text("Takip edilen otobüs yok. Otobüs ekle ile durak ve otobüs seçebilirsin.")
            items.forEach { item ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${item.arrival.code} · ${item.arrival.plate}")
                    Text("${item.stop.name} · ${item.minutes} dk kala")
                    Text(item.status + if(item.notified) " · Uyarı gönderildi" else "")
                    if(item.progressAt>0 && item.upcomingStops.isNotEmpty()) {
                        Text("Kaynağa göre son geçilen: "+item.previousStops.joinToString(" → ").ifBlank { "Hat başlangıcı" },style=MaterialTheme.typography.bodySmall)
                        Text("Sıradaki duraklar: "+item.upcomingStops.joinToString(" → "),style=MaterialTheme.typography.bodySmall)
                    } else Text("Durak ilerlemesi şu an doğrulanamıyor",style=MaterialTheme.typography.bodySmall)
                    if(item.checkedAt>0) Text("Son kontrol: "+java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale("tr","TR")).format(java.util.Date(item.checkedAt)),style=MaterialTheme.typography.labelSmall)
                    Row { TextButton(onClick={ onView(item) }) { Text("Haritada izle") }
                        TextButton(onClick={ ArrivalAlerts.remove(context,item.id) }) { Text("Kaldır") } }
                } }
            }
            HorizontalDivider()
            TextButton(onClick=onReminders) { Text("Saatli hatırlatmalar") }
        }
    }
}
