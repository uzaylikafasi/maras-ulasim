package com.uzaylikafasi.marasulasim.ui

import android.content.Intent
import android.net.Uri
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

@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val vm: com.uzaylikafasi.marasulasim.UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by vm.state.collectAsState()
    val updates=vm.updates
    val release=state.release
    val file=state.file
    val downloading=state.downloading
    val error=state.error
    fun install() { file?.let { runCatching { updates.install(it) }.onFailure { vm.installFailed() } } }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls()) install() else vm.error("Kurulum izni verilmedi. İndirilen dosya bekliyor.")
    }
    release?.let { item -> AlertDialog(onDismissRequest={ vm.dismiss() },title={ Text("Yeni sürüm: ${item.version}") },text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(item.notes.ifBlank { "Yeni sürüm yayımlandı." })
            Text("APK bu cihaz için seçilir. İndirdikten sonra Kur'a dokun; Android yükleyicisinde kurulumu onaylayabilirsin. Uygulama seçimi çıkarsa Paket yükleyici'yi seç.")
            if(downloading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("İndiriliyor…") }
            if(file!=null) Text("İndirme ve imza kontrolü tamamlandı.")
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={ TextButton(enabled=!downloading,onClick={
        if(file==null) vm.download() else if(context.packageManager.canRequestPackageInstalls()) install()
        else runCatching { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))) }
            .onFailure { vm.error("Kurulum izni ekranı açılamadı.") }
    }) { Text(if(file==null) "İndir" else "Kur") } },dismissButton={ Row {
        TextButton(enabled=!downloading,onClick={ vm.dismiss(skip=true) }) { Text("Bu sürümü atla") }
        TextButton(enabled=!downloading,onClick={ vm.dismiss() }) { Text("Sonra") }
    } }) }
}
