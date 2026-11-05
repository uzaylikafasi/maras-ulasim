@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.uzaylikafasi.marasulasim.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.uzaylikafasi.marasulasim.TransportApplication
import com.uzaylikafasi.marasulasim.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale

@Composable
fun AddTrackingSheet(onDismiss: () -> Unit, onChoose: (Stop, Arrival) -> Unit) {
    val app = LocalContext.current.applicationContext as TransportApplication
    var query by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var selected by remember { mutableStateOf<Stop?>(null) }
    var arrivals by remember { mutableStateOf<List<Arrival>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        try {
            catalog = withContext(Dispatchers.IO) {
                JSONObject(app.assets.open("all-stops.json").bufferedReader().use { it.readText() }).getJSONArray("stops").objects()
                    .map { Stop(it.getLong("id"),it.optString("code"),it.getString("name"),it.getDouble("lat"),it.getDouble("lng")) }
            }
        } catch(e:CancellationException) { throw e } catch(_:Exception) { error="Durak listesi açılamadı. Geri dönüp yeniden dene." }
        finally { loading=false }
    }
    LaunchedEffect(selected,refresh) {
        val stop=selected ?: return@LaunchedEffect
        arrivals=emptyList(); error=null; loading=true
        try {
            val settings=app.store.settings.first()
            val result=withContext(Dispatchers.IO) { app.repository.fetch(settings.server,settings.apiKey,"/v1/stops/${stop.id}",true) }
            if(result.isOld()) error="Kaynak verisi eski. Güncel otobüsler için yenile."
            else arrivals=parseArrivals(result.objectData.requiredArray("buses"))
                .filter { it.plate.isNotBlank() && it.lineId.toLongOrNull()!=null }.distinctBy { it.lineId to it.plate }
        } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "Otobüsler alınamadı. Yeniden dene." }
        finally { loading=false }
    }
    val matches=remember(query,catalog) {
        val normalized=query.trim().lowercase(Locale("tr","TR"))
        if(normalized.isBlank()) emptyList() else catalog.filter {
            it.name.lowercase(Locale("tr","TR")).contains(normalized) || it.code.contains(normalized)
        }.take(60)
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=600.dp).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { Text("Otobüs ekle",style=MaterialTheme.typography.titleLarge) }
            if(selected==null) {
                item { OutlinedTextField(query,{ query=it },label={ Text("Durak adı veya kodu") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
                item { Text(if(query.isBlank()) "Uyarının verileceği durağı ara, ardından yaklaşan otobüsü seç." else if(!loading && matches.isEmpty()) "Eşleşen durak yok." else "Bir durak seç.") }
                items(matches,key={ it.id }) { stop ->
                    OutlinedCard(onClick={ selected=stop; error=null; loading=true },modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) { Text(stop.name); Text(stop.code,style=MaterialTheme.typography.bodySmall) }
                    }
                }
            } else {
                item {
                    Text(selected!!.name,style=MaterialTheme.typography.titleMedium)
                    Row { TextButton(onClick={ selected=null; arrivals=emptyList(); error=null; loading=false }) { Text("Durağı değiştir") }
                        TextButton(enabled=!loading,onClick={ refresh++ }) { Text("Yenile") } }
                    if(!loading && arrivals.isEmpty() && error==null) Text("Bu durağa yaklaşan otobüs bildirilmedi. Biraz sonra yenileyebilirsin.")
                }
                items(arrivals,key={ "${it.lineId}:${it.plate}" }) { bus ->
                    OutlinedCard(onClick={ selected?.let { onChoose(it,bus) } },modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${bus.code} · ${bus.plate}",style=MaterialTheme.typography.titleSmall)
                            Text(bus.direction.ifBlank { bus.name }); Text("Kaynak tahmini: ${bus.remaining}")
                        }
                    }
                }
            }
            item { if(loading) LinearProgressIndicator(Modifier.fillMaxWidth()); error?.let { Text(it,color=MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}
