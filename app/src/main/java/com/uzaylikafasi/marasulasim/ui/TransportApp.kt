@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.uzaylikafasi.marasulasim.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import com.uzaylikafasi.marasulasim.*
import com.uzaylikafasi.marasulasim.data.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val teal = Color(0xFF2563EB)
private fun hex(value: String) = Color(android.graphics.Color.parseColor(value))
private val mapCardShape = RoundedCornerShape(24.dp)
private val stopSaver = Saver<Stop?, String>(save = { it?.json()?.toString() ?: "" }, restore = {
    if (it.isBlank()) null else runCatching { parseStop(JSONObject(it)) }.getOrNull()
})

@Composable
fun TransportApp(vm: TransportViewModel, onNearby: () -> Unit, onLocate: ((Double, Double) -> Unit) -> Unit) {
    // Açık ve renkli tema (Ömer'in seçimi): ulaşım mavisi, beyaz yüzeyler, parlak hat renkleri
    val colors = lightColorScheme(primary = teal, onPrimary = Color.White, primaryContainer = Color(0xFFDBEAFE), onPrimaryContainer = Color(0xFF1E3A8A),
        secondary = Color(0xFF0891B2), secondaryContainer = Color(0xFFCFFAFE), onSecondaryContainer = Color(0xFF164E63),
        tertiary = Color(0xFFEA580C), background = Color(0xFFF8FAFC), onBackground = Color(0xFF0F172A),
        surface = Color.White, onSurface = Color(0xFF0F172A), surfaceVariant = Color(0xFFF1F5F9), onSurfaceVariant = Color(0xFF475569),
        surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF8FAFC), surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFF1F5F9),
        outline = Color(0xFFCBD5E1), outlineVariant = Color(0xFFE2E8F0))
    MaterialTheme(colorScheme = colors) {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val snapshots by vm.snapshots.collectAsStateWithLifecycle()
        val search by vm.search.collectAsStateWithLifecycle()
        val detail by vm.detail.collectAsStateWithLifecycle()
        val extra by vm.extra.collectAsStateWithLifecycle()
        val journey by vm.journey.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val lineStops by vm.lineStops.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var focusedStop by rememberSaveable(stateSaver = stopSaver) { mutableStateOf<Stop?>(null) }
        var openedStop by remember { mutableStateOf<Stop?>(null) }
        var openedLine by remember { mutableStateOf<Line?>(null) }
        var openedVehicle by remember { mutableStateOf<Pair<Line, Vehicle>?>(null) }
        var origin by rememberSaveable(stateSaver = stopSaver) { mutableStateOf<Stop?>(null) }
        var destination by rememberSaveable(stateSaver = stopSaver) { mutableStateOf<Stop?>(null) }
        var sheet by rememberSaveable { mutableStateOf<String?>(null) }
        var nearbyTitle by rememberSaveable { mutableStateOf<String?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val appContext = LocalContext.current.applicationContext
        val stopLines = remember { stopLineCodes(appContext) }
        LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.monitor() } }
        LaunchedEffect(lifecycle, openedStop) {
            openedStop?.let { stop -> lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watchStop(stop) } }
        }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
        val openStop: (Stop) -> Unit = { openedStop = it; vm.stopDetails(it) }
        // Geri tuşu diğer sekmelerde önce haritaya döner; uygulama yalnız haritadayken kapanır
        BackHandler(enabled = tab != 0) { tab = 0 }
        LaunchedEffect(openedLine) { openedLine?.let { if (snapshots[it.key]?.stops == null) vm.lineStops(it) } }
        val names = listOf("Harita", "Ara", "Yolculuk", "Favoriler", "Diğer")
        val icons = listOf(Icons.Outlined.Map, Icons.Outlined.Search, Icons.Outlined.Route, Icons.Outlined.StarOutline, Icons.Outlined.MoreHoriz)
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            if (tab != 0) TopAppBar(title = { Column {
                Text(if (tab == 0) "Maraş Ulaşım" else names[tab], fontWeight = FontWeight.SemiBold)
                if (tab == 0) Text("Hatların, tek haritada", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            } }, actions = {
                if (tab == 0) IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Haritayı yenile") }
            })
        }, bottomBar = {
            NavigationBar {
                names.forEachIndexed { index, name -> NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Icon(icons[index], name) }, label = { Text(name) }) }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> MapScreen(settings, snapshots.values.toList(), focusedStop, openStop,
                        onSearch = { tab = 1 }, onLine = { openedLine = it }, onSelect = vm::select, onRemove = vm::removeFromMap,
                        onVehicle = { line, vehicle -> openedVehicle = line to vehicle }, onShowVehicles = vm::showVehicles, onRefresh = vm::refresh,
                        onNearby = { nearbyTitle = null; sheet = "nearby"; vm.prepareNearby(); onNearby() }, onLocate = onLocate,
                        onPlace = { name, at -> nearbyTitle = name; sheet = "nearby"; vm.nearby(at.latitude, at.longitude) })
                    1 -> SearchScreen(query, onQuery = { query = it; vm.search(it) }, search, settings, vm::favorite, vm::favorite,
                        openStop, onSelect = vm::select, onLine = { openedLine = it }, onNearby = { nearbyTitle = null; sheet = "nearby"; vm.prepareNearby(); onNearby() })
                    2 -> JourneyScreen(origin, destination, journey,
                        onChoose = { tab = 1; vm.notify("Bir durak ara; durak ayrıntısından Buradan veya Buraya seç.") },
                        onSwap = { val old = origin; origin = destination; destination = old },
                        onPlan = { path -> vm.plan(path) })
                    3 -> FavoritesScreen(settings, vm::favorite, vm::favorite, vm::favoritePlate, vm::select, openStop, onLine = { openedLine = it },
                        onSearch = { tab = 1 })
                    else -> MoreScreen(onSheet = { sheet = it; if (it == "announcements") vm.extra("/v1/announcements") })
                }
            }
        }
        if (openedStop != null) {
            val stop = openedStop!!
            // Tek dokunuşta tam açılır; kaydırarak, geri tuşuyla ya da Kapat ile kapanır
            ModalBottomSheet(onDismissRequest = { openedStop = null; vm.clearDetail() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.DirectionsBus, null, Modifier.padding(12.dp).size(26.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(stop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Durak ${stop.code}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { openedStop = null; vm.clearDetail() }) { Icon(Icons.Outlined.Close, "Kapat") }
                    }
                    // Hızlı işlemler: simgeli dört düğme
                    val fav = settings.stops.any { it.id == stop.id }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAction(if (fav) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (fav) "Favoride" else "Favori", Modifier.weight(1f), fav) { vm.favorite(stop) }
                        QuickAction(Icons.Outlined.Map, "Haritada", Modifier.weight(1f)) { focusedStop = stop; tab = 0; openedStop = null }
                        QuickAction(Icons.Outlined.TripOrigin, "Buradan", Modifier.weight(1f)) { origin = stop; tab = 2; openedStop = null }
                        QuickAction(Icons.Outlined.Flag, "Buraya", Modifier.weight(1f)) { destination = stop; tab = 2; openedStop = null }
                    }
                    stopLines[stop.id]?.let { codes ->
                        Text("Bu durağa uğrayan hatlar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            codes.forEach { code -> LineBadgeButton(code, code in settings.favoriteCodes, onLongClick = { vm.favoriteCode(code) }) {
                                query = code; vm.search(code); tab = 1; openedStop = null; vm.clearDetail() } }
                        }
                        Text("Hat numarasına basılı tutarak favorilere ekleyebilirsin.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Yaklaşan otobüsler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (detail.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { vm.stopDetails(stop) }) { Icon(Icons.Outlined.Refresh, "Yenile") }
                    }
                    // Sadece favorilerim: yalnız favori hat numaralarının otobüsleri (seçim hatırlanır)
                    val onlyFavorites = settings.favoritesOnly && settings.favoriteCodes.isNotEmpty()
                    FilterChip(selected = settings.favoritesOnly, onClick = { vm.favoritesOnly(!settings.favoritesOnly) },
                        label = { Text("Sadece favorilerim") },
                        leadingIcon = { Icon(if (settings.favoritesOnly) Icons.Outlined.Star else Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) })
                    if (settings.favoritesOnly && settings.favoriteCodes.isEmpty()) Text("Henüz favori hattın yok. Yukarıdaki hat numarasına basılı tut.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StateNotice(detail)
                    detail.result?.let { result ->
                        val buses = runCatching { result.objectData.requiredArray("buses") }.getOrNull()
                        val all = runCatching { buses?.let(::parseArrivals) }.getOrNull().orEmpty()
                        val arrivals = if (onlyFavorites) all.filter { it.code in settings.favoriteCodes } else all
                        if (buses == null) Text("Yaklaşan araç bilgisi okunamadı.")
                        else if (arrivals.isEmpty() && all.isNotEmpty()) EmptyHint(Icons.Outlined.StarOutline, "Favori hatlarından yaklaşan otobüs yok", "${all.size} başka otobüs geliyor. Hepsini görmek için Sadece favorilerim'i kapat.")
                        else if (arrivals.isEmpty()) EmptyHint(Icons.Outlined.Schedule, "Şu an yaklaşan otobüs görünmüyor", "Biraz sonra yenile.")
                        arrivals.forEachIndexed { index, arrival -> AnimatedListItem(index) { ArrivalCard(arrival) } }
                        Freshness(result)
                        if (arrivals.isNotEmpty()) Text("Süreler kaynağın tahminidir.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        openedVehicle?.let { (line, vehicle) ->
            AlertDialog(onDismissRequest = { openedVehicle = null }, title = { Text("${line.code} · ${vehicle.plate}") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(line.directionName)
                    Text("Sonraki durak: ${vehicle.nextStop.ifBlank { "Bildirilmedi" }}")
                    OutlinedButton(onClick = { vm.favoritePlate(vehicle.plate) }) {
                        Icon(if (vehicle.plate in settings.plates) Icons.Outlined.Star else Icons.Outlined.StarOutline, null)
                        Text(if (vehicle.plate in settings.plates) "Favorilerden çıkar" else "Plakayı favorile", Modifier.padding(start = 8.dp))
                    }
                } }, confirmButton = { TextButton(onClick = { openedVehicle = null; openedLine = line }) { Text("Hattın durakları") } },
                dismissButton = { TextButton(onClick = { openedVehicle = null }) { Text("Kapat") } })
        }
        openedLine?.let { line ->
            ModalBottomSheet(onDismissRequest = { openedLine = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                val snapshot = snapshots[line.key]
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("${line.code} · ${line.directionName}", style = MaterialTheme.typography.titleLarge)
                        Text(line.name, Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (!settings.isOnMap(line.code)) vm.select(line); tab = 0; openedLine = null }) { Text("Haritada izle") }
                            IconButton(onClick = { vm.favorite(line) }) {
                                Icon(if (settings.lines.any { it.key == line.key }) Icons.Outlined.Star else Icons.Outlined.StarOutline, "${line.code} favori")
                            }
                            OutlinedButton(onClick = {
                                sheet = "timetable"; vm.extra("/v1/timetables?line_code=${java.net.URLEncoder.encode(line.code, "UTF-8")}")
                                openedLine = null
                            }) { Text("Sefer saatleri") }
                        }
                        val source = snapshot?.stops ?: lineStops.result
                        if (snapshot?.stops == null) StateNotice(lineStops)
                        source?.let { Freshness(it) }
                    }
                    val stops = runCatching { (snapshot?.stops ?: lineStops.result)?.objectData?.requiredArray("lineStops")?.objects()?.map(::parseStop) }.getOrNull().orEmpty()
                    items(stops, key = { it.id }) { stop -> StopRow(stop, settings.stops.any { it.id == stop.id },
                        onClick = { openedLine = null; openStop(stop) }, onFavorite = { vm.favorite(stop) }) }
                    item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
                }
            }
        }
        when (sheet) {
            "reminders" -> ReminderDialog(onDismiss = { sheet = null })
            "announcements", "timetable", "nearby" -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(when (sheet) { "announcements" -> "Duyurular"; "timetable" -> "Sefer saatleri"; else -> nearbyTitle?.let { "$it · yakındaki duraklar" } ?: "Yakınımdaki duraklar" }, style = MaterialTheme.typography.titleLarge)
                        if (sheet == "nearby") Text("Durağa dokun, yaklaşan otobüsler açılsın.", color = MaterialTheme.colorScheme.secondary)
                        StateNotice(extra) }
                    extra.result?.let { result ->
                        item { Freshness(result) }
                        when (sheet) {
                            "announcements" -> {
                                val announcements = runCatching { result.objectData.requiredArray("Data").objects() }.getOrDefault(emptyList())
                                if (announcements.isEmpty()) item { Text("Gösterilecek duyuru yok.") }
                                items(announcements) { o ->
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(o.optString("Title"), fontWeight = FontWeight.SemiBold)
                                        Text(o.optString("Desc")); HorizontalDivider(Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                            "timetable" -> item { Timetables(result) }
                            else -> {
                                val stops = runCatching { parseNearby(result.objectData) }.getOrDefault(emptyList())
                                val distances = runCatching { parseNearbyDistances(result.objectData) }.getOrDefault(emptyMap())
                                if (stops.isEmpty()) item { Text("Bu bölgede durak bulunamadı.") }
                                // Yakından geçen hatlar: en yakın duraktan başlayarak, her hat bir kez
                                val nearLines = stops.flatMap { stopLines[it.id].orEmpty() }.distinct()
                                if (nearLines.isNotEmpty()) item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Yakından geçen hatlar · ${nearLines.size}", style = MaterialTheme.typography.titleMedium)
                                        Text("Dokununca hat haritaya eklenir.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            nearLines.forEach { code ->
                                                val on = settings.isOnMap(code)
                                                FilterChip(selected = on, onClick = { if (on) { tab = 0; sheet = null } else { vm.showCode(code); tab = 0; sheet = null } },
                                                    label = { Text(code, fontWeight = FontWeight.SemiBold) },
                                                    leadingIcon = { Icon(if (on) Icons.Outlined.Map else Icons.Outlined.Add, null, Modifier.size(18.dp)) })
                                            }
                                        }
                                        HorizontalDivider(Modifier.padding(top = 4.dp))
                                        Text("Duraklar", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                items(stops, key = { it.id }) { stop -> NearbyStopCard(stop, distances[stop.id], stopLines[stop.id].orEmpty(),
                                    settings.stops.any { it.id == stop.id }, onClick = { sheet = null; openStop(stop) }, onFavorite = { vm.favorite(stop) }) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
                }
            }
        }
    }
}

@Composable
private fun MapScreen(settings: Settings, snapshots: List<LineSnapshot>, focus: Stop?, onStop: (Stop) -> Unit,
                      onSearch: () -> Unit, onLine: (Line) -> Unit, onSelect: (Line) -> Unit, onRemove: (Line) -> Unit,
                      onVehicle: (Line, Vehicle) -> Unit, onShowVehicles: (Boolean) -> Unit, onRefresh: () -> Unit,
                      onNearby: () -> Unit, onLocate: ((Double, Double) -> Unit) -> Unit, onPlace: (String, Coordinate) -> Unit) {
    var locate by remember { mutableStateOf<Pair<Long, Coordinate>?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var mapAttempt by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        key(mapAttempt) {
            TransportMap(snapshots, focus, settings.plates, settings.showVehicles, locate, Modifier.fillMaxSize(), onStop, onVehicle, onPlace, onError = { mapError = it })
        }
        Column(Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = onSearch, modifier = Modifier.weight(1f), shape = mapCardShape, color = Color.White, shadowElevation = 6.dp) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Hat veya durak ara", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                MapRoundButton(if (settings.showVehicles) Icons.Outlined.DirectionsBus else Icons.Outlined.BusAlert,
                    if (settings.showVehicles) "Otobüsleri gizle" else "Otobüsleri göster", active = settings.showVehicles) { onShowVehicles(!settings.showVehicles) }
                MapRoundButton(Icons.Outlined.Refresh, "Haritayı yenile") { onRefresh() }
            }
            // Hat başına tek düğme: gidiş ve dönüş birlikte açılıp kapanır
            val groups = settings.mapLines.groupBy { it.code }
            if (groups.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups.keys.toList(), key = { it }) { code ->
                    val line = groups.getValue(code).first()
                    val on = settings.isOnMap(code)
                    val color = if (on) hex(lineColorFor(code, settings.visibleCodes())) else Color(0xFF94A3B8)
                    Surface(onClick = { onSelect(line) }, shape = RoundedCornerShape(50), color = if (on) color else Color.White, shadowElevation = 4.dp,
                        modifier = Modifier.animateItem()) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(16.dp), tint = if (on) Color.White else color)
                            Text(code, Modifier.padding(start = 6.dp), fontWeight = FontWeight.Bold, color = if (on) Color.White else Color(0xFF334155))
                            IconButton(onClick = { onRemove(line) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Close, "$code hattını haritadan kaldır", Modifier.size(16.dp), tint = if (on) Color.White else Color(0xFF64748B))
                            }
                        }
                    }
                }
            }
            mapError?.let {
                ElevatedCard {
                    Column(Modifier.padding(12.dp)) {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { mapAttempt++ }) { Text("Haritayı yeniden yükle") }
                    }
                }
            }
        }
        // Konumum: izin istenir, mavi nokta açılır ve harita konuma gelir
        SmallFloatingActionButton(onClick = { onLocate { lat, lng -> locate = System.currentTimeMillis() to Coordinate(lat, lng) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 148.dp),
            containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary, shape = CircleShape) {
            Icon(Icons.Outlined.GpsFixed, "Konumuma git")
        }
        // Yakınımdaki duraklar: konumdan en yakın duraklar ve yaklaşan otobüsler
        ExtendedFloatingActionButton(onClick = onNearby, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).padding(bottom = 72.dp),
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = { Icon(Icons.Outlined.NearMe, null) }, text = { Text("Yakınımdaki") })
        androidx.compose.animation.AnimatedVisibility(visible = snapshots.isEmpty(), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).widthIn(max = 420.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 }) {
            Surface(shape = mapCardShape, color = Color.White, shadowElevation = 6.dp) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                        Icon(Icons.Outlined.TipsAndUpdates, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("Bir hat ara ya da haritada durağa, önemli bir yere dokun.", Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFF334155))
                }
            }
        }
        // Küçük özet: yalnız görünen hat varken; dokununca ayrıntılar açılır
        if (snapshots.isNotEmpty()) Surface(Modifier.align(Alignment.BottomStart).padding(12.dp).widthIn(max = 440.dp), shape = mapCardShape, color = Color.White, shadowElevation = 6.dp) {
            Column(Modifier.padding(14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    val codes = snapshots.map { it.line.code }.distinct()
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        codes.forEach { code ->
                            // İki yönün araçları toplanır
                            val counts = snapshots.filter { it.line.code == code }.map { snap -> runCatching { snap.vehicles?.takeUnless { it.isOld() }?.arrayData?.length() }.getOrNull() }
                            val total = counts.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
                            SmallBadge("$code${if (settings.showVehicles && total != null) " · $total araç" else ""}",
                                Color(android.graphics.Color.parseColor(lineColorFor(code, codes))))
                        }
                    }
                    Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (expanded) "Özeti küçült" else "Ayrıntıları aç")
                }
                if (expanded) snapshots.forEach { snapshot ->
                    Row(Modifier.fillMaxWidth().clickable { onLine(snapshot.line) }, verticalAlignment = Alignment.CenterVertically) {
                        CodeBadge(snapshot.line.code, Color(android.graphics.Color.parseColor(lineColorFor(snapshot.line.code, snapshots.map { it.line.code }.distinct()))))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("${snapshot.line.directionName} · ${if (snapshot.line.direction == 1) "düz çizgi" else "kesikli çizgi"}", fontWeight = FontWeight.Medium)
                            val live = snapshot.vehicles
                            val vehicles = runCatching { live?.arrayData?.length() }.getOrNull()
                            Text(when {
                                !settings.showVehicles -> "Otobüsler gizli"
                                live == null -> if (snapshot.error != null) "Canlı veriye ulaşılamıyor" else "Canlı veri alınıyor…"
                                live.isOld() -> "Araç verisi eski; yenilenmesi bekleniyor"
                                vehicles == 0 -> "Kaynak şu an araç bildirmiyor"
                                vehicles != null -> "$vehicles araç"
                                else -> "Araç bilgisi okunamadı"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        Icon(Icons.Outlined.ChevronRight, "Hattın durakları")
                    }
                }
                if (expanded) {
                    val recorded = snapshots.firstOrNull { it.shape?.stale == true || it.shape?.bundled == true }?.shape
                    if (recorded != null) Freshness(recorded) else snapshots.firstOrNull()?.vehicles?.let { Freshness(it) }
                }
            }
        }
    }
}

/** Gömülü durak listesindeki hat kodları (durak kimliğine göre). */
private fun stopLineCodes(context: android.content.Context): Map<Long, List<String>> = runCatching {
    val stops = JSONObject(context.assets.open("all-stops.json").bufferedReader().use { it.readText() }).getJSONArray("stops")
    (0 until stops.length()).mapNotNull { i ->
        val o = stops.getJSONObject(i)
        o.optJSONArray("lines")?.let { a -> o.getLong("id") to (0 until a.length()).map { a.getString(it) } }
    }.toMap()
}.getOrDefault(emptyMap())

@Composable
private fun MapRoundButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = if (active) MaterialTheme.colorScheme.primary else Color.White, shadowElevation = 6.dp,
        contentColor = if (active) Color.White else MaterialTheme.colorScheme.primary) {
        Icon(icon, label, Modifier.padding(13.dp).size(24.dp))
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(0xFFFEF3C7) else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (selected) Color(0xFFB45309) else MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun LineBadgeButton(code: String, favorite: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = hex(badgeColorFor(code)), contentColor = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "$code favori")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (favorite) Icon(Icons.Filled.Star, "Favori", Modifier.size(14.dp).padding(end = 2.dp))
            Text(code, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ArrivalCard(arrival: Arrival) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = hex(badgeColorFor(arrival.code)), contentColor = Color.White) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(16.dp))
                    Text(arrival.code, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(arrival.remaining, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(arrival.distance.takeIf { it.isNotBlank() }, arrival.plate.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (arrival.nextStop.isNotBlank()) Text("Sonraki durak: ${arrival.nextStop}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Liste öğeleri sırayla, hafif kayarak belirir. */
@Composable
private fun AnimatedListItem(index: Int, content: @Composable () -> Unit) {
    val visible = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(visibleState = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250, delayMillis = index * 50)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(250, delayMillis = index * 50)) { it / 3 }) { content() }
}

@Composable
private fun NearbyStopCard(stop: Stop, meters: Int?, lines: List<String>, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stop.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(meters?.let { if (it < 1000) "$it m" else "%.1f km".format(it / 1000.0) },
                    meters?.let { "yürüyerek ${(it / 80).coerceAtLeast(1)} dk" }, "durak ${stop.code}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                if (lines.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.take(8).forEach { SmallBadge(it, hex(badgeColorFor(it))) }
                    if (lines.size > 8) Text("+${lines.size - 8}", style = MaterialTheme.typography.labelMedium)
                }
                Text("Yaklaşan otobüsler için dokun", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, "${stop.name} favori") }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Kapat") }
    }
}

@Composable
private fun SmallBadge(text: String, color: Color) {
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, state: LoadState, settings: Settings,
                         onFavoriteLine: (Line) -> Unit, onFavoriteStop: (Stop) -> Unit, onStop: (Stop) -> Unit,
                         onSelect: (Line) -> Unit, onLine: (Line) -> Unit, onNearby: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val results = runCatching { state.result?.objectData?.let(::parseSearch) }.getOrNull()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Hat, durak adı veya numarası") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Aramayı temizle") } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tümü", "Hatlar", "Duraklar").forEachIndexed { index, name -> FilterChip(filter == index, { filter = index }, { Text(name) }) }
            }
            TextButton(onClick = onNearby) { Icon(Icons.Outlined.MyLocation, null); Text("Yakınımdaki duraklar", Modifier.padding(start = 8.dp)) }
            StateNotice(state)
            state.result?.let { Freshness(it) }
        }
        if (query.isBlank()) {
            item { Text("Bir hat veya durak ara", style = MaterialTheme.typography.titleMedium); Text("Örneğin H/1 veya Demokrasi Meydanı.", color = MaterialTheme.colorScheme.secondary) }
            item { LineRow(suggestedLine, settings, { onFavoriteLine(suggestedLine) }, { onSelect(suggestedLine) }, { onLine(suggestedLine) }) }
        } else {
            if (filter != 2) items(results?.lines.orEmpty(), key = { "line-${it.key}" }) { line -> LineRow(line, settings,
                { onFavoriteLine(line) }, { onSelect(line) }, { onLine(line) }) }
            if (filter != 1) items(results?.stops.orEmpty(), key = { "stop-${it.id}" }) { stop -> StopRow(stop,
                settings.stops.any { it.id == stop.id }, { onStop(stop) }, { onFavoriteStop(stop) }) }
            if (results != null && results.lines.isEmpty() && results.stops.isEmpty()) item { Text("Sonuç bulunamadı. Başka bir ad veya numara dene.") }
            if (state.result != null && results == null) item { Notice("Arama yanıtı okunamadı. Tekrar dene.") }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FavoritesScreen(s: Settings, onFavoriteLine: (Line) -> Unit, onFavoriteStop: (Stop) -> Unit, onFavoritePlate: (String) -> Unit,
                            onSelect: (Line) -> Unit, onStop: (Stop) -> Unit, onLine: (Line) -> Unit, onSearch: () -> Unit) {
    val lineGroups = s.lines.groupBy { it.code }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.lines.isEmpty() && s.stops.isEmpty() && s.plates.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.StarOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Henüz favorin yok", style = MaterialTheme.typography.titleLarge)
                Text("Bir hat ya da durak açıp yıldıza dokun. Haritaya eklemek favori yapmaz; favoriler yalnız senin seçtiklerin.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                Button(onClick = onSearch) { Icon(Icons.Outlined.Search, null); Text("Hat ve durak ara", Modifier.padding(start = 8.dp)) }
            }
        }
        if (lineGroups.isNotEmpty()) {
            item { SectionTitle(Icons.Outlined.DirectionsBus, "Hatlar") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lineGroups.forEach { (code, directions) ->
                        val on = s.isOnMap(code)
                        val color = Color(android.graphics.Color.parseColor(lineColorFor(code, s.visibleCodes().ifEmpty { lineGroups.keys.toList() })))
                        ElevatedCard(onClick = { onLine(directions.first()) }, modifier = Modifier.widthIn(min = 160.dp, max = 260.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CodeBadge(code, color)
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { directions.forEach(onFavoriteLine) }) { Icon(Icons.Outlined.Star, "$code favorisini kaldır", tint = Color(0xFFFFB020)) }
                                }
                                Text(directions.first().name.substringBefore(" (").ifBlank { directions.joinToString(" · ") { it.directionName } },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                FilledTonalButton(onClick = { onSelect(directions.first()) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    Icon(if (on) Icons.Outlined.VisibilityOff else Icons.Outlined.Map, null, Modifier.size(18.dp))
                                    Text(if (on) "Haritadan kaldır" else "Haritada göster", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (s.stops.isNotEmpty()) {
            item { SectionTitle(Icons.Outlined.LocationOn, "Duraklar") }
            items(s.stops, key = { "stop-${it.id}" }) { stop ->
                ElevatedCard(onClick = { onStop(stop) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Outlined.LocationOn, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(stop.name, fontWeight = FontWeight.Medium)
                            Text("Durak ${stop.code} · dokun, yaklaşan otobüsleri gör", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = { onFavoriteStop(stop) }) { Icon(Icons.Outlined.Star, "${stop.name} favorisini kaldır", tint = Color(0xFFFFB020)) }
                    }
                }
            }
        }
        if (s.plates.isNotEmpty()) {
            item {
                SectionTitle(Icons.Outlined.DirectionsBus, "Otobüsler")
                Text("Haritadaki hatlarda bu plaka görünürse sarı çerçeveyle işaretlenir.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.plates.sorted().forEach { plate ->
                        InputChip(selected = true, onClick = {}, label = { Text(plate) },
                            leadingIcon = { Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, "$plate favorisini kaldır", Modifier.size(18.dp).clickable { onFavoritePlate(plate) }) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LineRow(line: Line, settings: Settings, favorite: () -> Unit, select: () -> Unit, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodeBadge(line.code)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(line.directionName, style = MaterialTheme.typography.labelLarge)
                    Text(line.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = favorite) { Icon(if (settings.lines.any { it.key == line.key }) Icons.Outlined.Star else Icons.Outlined.StarOutline, "${line.code} favori seçimini değiştir") }
            }
            TextButton(onClick = select) { Icon(Icons.Outlined.Map, null); Text(if (settings.isOnMap(line.code)) "Haritadan kaldır" else "Haritaya ekle (gidiş + dönüş)", Modifier.padding(start = 8.dp)) }
        }
    }
}
@Composable
private fun StopRow(stop: Stop, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stop.name, style = MaterialTheme.typography.bodyMedium)
            Text("Durak ${stop.code}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, "${stop.name} favori seçimini değiştir") }
    }
}
@Composable
private fun CodeBadge(code: String, color: Color = teal) {
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(10.dp)) {
        Text(code, Modifier.padding(horizontal = 10.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun StateNotice(state: LoadState) {
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
    state.error?.let { Notice(it) }
}
@Composable
private fun Freshness(result: ApiResult) {
    val time = runCatching {
        fetchedInstant(result.fetchedAt ?: "").atZone(ZoneId.of("Europe/Istanbul")).format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("tr")))
    }.getOrNull()
    Text(when {
        result.bundled -> "21 Ekim örnek kaydı · güncel veri alınamadı"
        result.stale -> "Son kayıt${time?.let { " · $it" }.orEmpty()} · güncel veri alınamadı"
        time != null -> "Veri alınma zamanı · $time"
        else -> "Verinin alınma zamanı bilinmiyor"
    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun JourneyScreen(origin: Stop?, destination: Stop?, state: LoadState, onChoose: () -> Unit,
                          onSwap: () -> Unit, onPlan: (String) -> Unit) {
    val context = LocalContext.current
    val now = ZonedDateTime.now(ZoneId.of("Europe/Istanbul"))
    var hour by rememberSaveable { mutableIntStateOf(now.hour) }
    var minute by rememberSaveable { mutableIntStateOf(now.minute) }
    var day by rememberSaveable { mutableIntStateOf(now.dayOfWeek.value % 7) }
    LazyColumn(Modifier.padding(horizontal = 16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Nasıl giderim?", style = MaterialTheme.typography.headlineSmall)
            OutlinedCard(onClick = onChoose, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp)) { Text("Başlangıç", style = MaterialTheme.typography.labelMedium); Text(origin?.name ?: "Başlangıç durağı seç") }
            }
            Box(Modifier.fillMaxWidth()) { IconButton(onClick = onSwap, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Outlined.SwapVert, "Başlangıç ve varışı değiştir") } }
            OutlinedCard(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) { Text("Varış", style = MaterialTheme.typography.labelMedium); Text(destination?.name ?: "Varış durağı seç") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val days = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
                items(7) { index -> val value = (index + 1) % 7; FilterChip(day == value, { day = value }, { Text(days[index]) }) }
            }
            OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show() }) {
                Icon(Icons.Outlined.Schedule, null); Text(String.format(Locale.ROOT, " %02d:%02d", hour, minute))
            }
            Text("Seçilen gün ve saate ait tarifeyle planlanır. Canlı varış garantisi vermez.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        item {
            Button(onClick = { if (origin != null && destination != null) onPlan("/v1/journeys?from_stop_id=${origin.id}&to_stop_id=${destination.id}&day=$day&hour=$hour&minute=$minute") },
                enabled = origin != null && destination != null && origin.id != destination.id && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Rota bul") }
            StateNotice(state)
        }
        state.result?.let { result ->
            item { Freshness(result) }
            val routes = runCatching { result.objectData.requiredArray("data").objects().map(::parseJourneyRoute) }.getOrDefault(emptyList())
            if (routes.isEmpty()) item { Text("Bu seçim için rota bulunamadı.") }
            items(routes) { route -> RouteCard(route) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
@Composable
private fun RouteCard(route: JourneyRoute) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${route.depart ?: "?"} → ${route.arrive ?: "?"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${route.legs.sumOf { it.minutes }} dk", fontWeight = FontWeight.SemiBold)
            }
            Text(listOfNotNull(
                if (route.transfers == 0) "Aktarmasız" else "${route.transfers} aktarma",
                route.walkingMeters.takeIf { it > 0 }?.let { "$it m yürüme" },
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            route.legs.forEach { leg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leg.walking) Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, "Yürüme", Modifier.padding(8.dp))
                    } else CodeBadge(leg.code)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (leg.walking) "Yürü · ${leg.meters} m" else "${leg.from} → ${leg.to}", style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(leg.depart?.let { d -> leg.arrive?.let { "$d → $it" } ?: d }, if (leg.walking) leg.to.takeIf { it.isNotBlank() }?.let { "$it durağına" } else null)
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Timetables(result: ApiResult) {
    var day by rememberSaveable { mutableStateOf("WORKDAY") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Kalkış tarifesi", fontWeight = FontWeight.SemiBold)
        Text("Saatler hattın kalkış noktasına aittir. Ara duraktaki varış saati değildir.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("WORKDAY" to "Hafta içi", "SATURDAY" to "Cumartesi", "SUNDAY" to "Pazar").forEach { (key, name) -> FilterChip(day == key, { day = key }, { Text(name) }) }
        }
        val tables = runCatching { result.objectData.requiredArray("timeTable").objects() }.getOrDefault(emptyList())
        tables.forEach { table ->
            Text("${table.optString("routeCode")} · Güzergâh ${table.optInt("routeType")}", style = MaterialTheme.typography.titleMedium)
            Text(table.optString("routeName"), style = MaterialTheme.typography.bodySmall)
            val times = runCatching { table.optJSONObject("lineSchedules")?.array(day)?.texts().orEmpty() }.getOrDefault(emptyList())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                times.forEach { time -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) { Text(time, Modifier.padding(8.dp)) } }
            }
            if (times.isEmpty()) Text("Bu gün için sefer saati bildirilmemiş.")
        }
    }
}

@Composable
private fun MoreScreen(onSheet: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuRow(Icons.Outlined.Campaign, "Duyurular", "Yol ve güzergâh değişiklikleri") { onSheet("announcements") }
        MenuRow(Icons.Outlined.NotificationsActive, "Hatırlatmalar", "Okul, iş ve dershane saatlerin") { onSheet("reminders") }
        val uri = LocalUriHandler.current
        MenuRow(Icons.Outlined.Code, "Uygulamanın kaynak kodu", "GitHub · maras-ulasim") { uri.openUri(REPO_URL) }
        MenuRow(Icons.Outlined.Api, "Ulaşım API'si", "Kendi uygulaman için ücretsiz kullan") { uri.openUri(API_DOCS_URL) }
        MenuRow(Icons.Outlined.Terminal, "API'nin kaynak kodu", "GitHub · kahramankart-ulasim-api") { uri.openUri(API_REPO_URL) }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Maraş Ulaşım · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
        Text("Bağımsız bir ulaşım uygulamasıdır. Belediye veya Kahramankart’ın resmî uygulaması değildir.", style = MaterialTheme.typography.bodySmall)
        Text("Harita: MapLibre · OpenFreeMap · OpenStreetMap\nHat ve sefer verileri: Pallium / Kahramankart", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, click: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, modifier = Modifier.clickable(onClick = click))
}
@Composable
private fun ReminderDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reminders by remember { mutableStateOf(Reminders.list(context)) }
    var title by rememberSaveable { mutableStateOf("") }
    var hour by rememberSaveable { mutableIntStateOf(7) }
    var minute by rememberSaveable { mutableIntStateOf(30) }
    var days by rememberSaveable { mutableStateOf(listOf(1, 2, 3, 4, 5)) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Reminder?>(null) }
    fun save(item: Reminder) { Reminders.save(context, item); reminders = Reminders.list(context); title = ""; error = null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.let(::save) else error = "Bildirim izni verilmedi. Hatırlatma kaydedilmedi."
        pending = null
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Hatırlatmalar", style = MaterialTheme.typography.titleLarge)
            Text("Android, pil tasarrufu için hatırlatmayı birkaç dakika geciktirebilir.", style = MaterialTheme.typography.bodySmall)
            reminders.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Medium)
                        Text(String.format(Locale.ROOT, "%02d:%02d", item.hour, item.minute) + " · " + item.days.sorted().joinToString(" ") { listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")[it - 1] }, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { Reminders.delete(context, item); reminders = Reminders.list(context) }) { Icon(Icons.Outlined.DeleteOutline, "${item.title} hatırlatmasını sil") }
                }
            }
            OutlinedTextField(title, { title = it.take(80) }, modifier = Modifier.fillMaxWidth(), label = { Text("Hatırlatma adı") }, placeholder = { Text("Örneğin okula çıkış") }, singleLine = true)
            OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show() }) { Text(String.format(Locale.ROOT, "%02d:%02d", hour, minute)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz").forEachIndexed { index, label ->
                    val day = index + 1
                    FilterChip(day in days, { days = if (day in days) days - day else days + day }, { Text(label) })
                }
            }
            error?.let { Notice(it) }
            Button(onClick = {
                val item = Reminder(title = title.trim(), hour = hour, minute = minute, days = days.toSet())
                when {
                    Build.VERSION.SDK_INT >= 33 && !Reminders.enabled(context) -> { pending = item; permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    !Reminders.enabled(context) -> error = "Bildirimler kapalı. Telefon ayarlarından Maraş Ulaşım bildirimlerini aç."
                    else -> save(item)
                }
            }, enabled = title.isNotBlank() && days.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Hatırlatma ekle") }
            TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Bildirim ayarlarını aç") }
        }
    }
}
