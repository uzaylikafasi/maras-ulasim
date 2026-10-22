package com.uzaylikafasi.marasulasim.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uzaylikafasi.marasulasim.*
import com.uzaylikafasi.marasulasim.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class LineSnapshot(val line: Line, val shape: ApiResult? = null, val stops: ApiResult? = null,
                        val vehicles: ApiResult? = null, val error: String? = null)
data class LoadState(val loading: Boolean = false, val result: ApiResult? = null, val error: String? = null)

class TransportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TransportApplication
    private val repository get() = app.repository
    val settings = app.store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    private val _snapshots = MutableStateFlow<Map<String, LineSnapshot>>(emptyMap())
    val snapshots = _snapshots.asStateFlow()
    private val _search = MutableStateFlow(LoadState())
    val search = _search.asStateFlow()
    private val _detail = MutableStateFlow(LoadState())
    val detail = _detail.asStateFlow()
    private val _extra = MutableStateFlow(LoadState())
    val extra = _extra.asStateFlow()
    private val _journey = MutableStateFlow(LoadState())
    val journey = _journey.asStateFlow()
    private val _lineStops = MutableStateFlow(LoadState())
    val lineStops = _lineStops.asStateFlow()
    private var lineStopsJob: Job? = null
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var extraJob: Job? = null
    private var journeyJob: Job? = null
    private val refreshMutex = Mutex()
    private var manualRefresh: Job? = null
    private val lastStatic = mutableMapOf<String, Long>()

    fun dismissMessage() { _message.value = null }
    fun notify(message: String) { _message.value = message }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notify(e.message ?: "İşlem tamamlanamadı. Tekrar dene.") }
    } }
    fun favorite(line: Line) = action { app.store.toggleLine(line); FavoritesWidget.refreshAll(app) }
    fun favorite(stop: Stop) = action { app.store.toggleStop(stop); FavoritesWidget.refreshAll(app) }
    fun favoritePlate(plate: String) = action { app.store.togglePlate(plate) }
    /** Hattı gidiş ve dönüşüyle birlikte haritada aç/kapat. Diğer yön kaynak aramasından bulunur. */
    fun select(line: Line) = action {
        val s = settings.value
        val alreadyOn = s.isOnMap(line.code)
        val directions = if (alreadyOn) listOf(line) else {
            val known = s.mapLines.filter { it.code == line.code }
            val found = runCatching {
                parseSearch(withContext(Dispatchers.IO) { repository.fetch(s.server, s.apiKey, searchPath(line.code)) }.objectData)
                    .lines.filter { it.code == line.code }
            }.getOrDefault(emptyList())
            (listOf(line) + known + found).distinctBy { it.key }
        }
        app.store.toggleLineOnMap(line.code, directions)
    }
    /** Hat kodundan haritaya ekle (yakındaki hatlar): iki yön aramadan bulunur. Zaten açıksa dokunulmaz. */
    fun showCode(code: String) = action {
        val s = settings.value
        if (s.isOnMap(code)) return@action
        val found = parseSearch(withContext(Dispatchers.IO) { repository.fetch(s.server, s.apiKey, searchPath(code)) }.objectData)
            .lines.filter { it.code == code }.distinctBy { it.key }
        if (found.isEmpty()) notify("$code hattı bulunamadı.") else app.store.toggleLineOnMap(code, found)
    }
    fun removeFromMap(line: Line) = action { app.store.removeFromMap(line) }
    fun showVehicles(show: Boolean) = action { app.store.setShowVehicles(show) }
    fun favoritesOnly(value: Boolean) = action { app.store.setFavoritesOnly(value) }
    /** Hat numarasını favorile/çıkar (durak menüsünde rozete uzun basınca). Hat aramadan bulunur. */
    fun favoriteCode(code: String) = action {
        val s = settings.value
        if (code in s.favoriteCodes) { app.store.removeLineCode(code); notify("$code favorilerden çıkarıldı."); FavoritesWidget.refreshAll(app); return@action }
        val line = parseSearch(withContext(Dispatchers.IO) { repository.fetch(s.server, s.apiKey, searchPath(code)) }.objectData)
            .lines.firstOrNull { it.code == code }
        if (line == null) notify("$code hattı bulunamadı.")
        else { app.store.toggleLine(line); notify("$code favorilere eklendi."); FavoritesWidget.refreshAll(app) }
    }
    fun search(query: String) {
        searchJob?.cancel()
        if (query.trim().isEmpty()) { _search.value = LoadState(); return }
        val connection = settings.value
        searchJob = viewModelScope.launch {
            delay(350)
            val path = searchPath(query.trim().take(100))
            _search.value = LoadState(true, withContext(Dispatchers.IO) { repository.cached(connection.server, path, connection.apiKey) })
            _search.value = load(path, connection)
        }
    }
    private suspend fun load(path: String, s: Settings = settings.value, live: Boolean = false): LoadState {
        return try { LoadState(result = withContext(Dispatchers.IO) { repository.fetch(s.server, s.apiKey, path, live) }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { LoadState(error = e.message ?: "Veri alınamadı. Yeniden dene.") }
    }
    fun stopDetails(stop: Stop) {
        detailJob?.cancel(); _detail.value = LoadState(true)
        detailJob = viewModelScope.launch { _detail.value = load("/v1/stops/${stop.id}", live = true) }
    }
    fun clearDetail() { detailJob?.cancel(); _detail.value = LoadState() }
    /** Haritada olmayan bir hattın durakları (aramadan açılan hat sayfası için). Önce kayıtlı kopya gösterilir. */
    fun lineStops(line: Line) {
        lineStopsJob?.cancel()
        val s = settings.value
        val path = "/v1/lines/${line.id}/stops?direction=${line.direction}"
        lineStopsJob = viewModelScope.launch {
            _lineStops.value = LoadState(true, withContext(Dispatchers.IO) { repository.cached(s.server, path, s.apiKey) })
            _lineStops.value = load(path, s)
        }
    }
    fun extra(path: String) {
        extraJob?.cancel(); _extra.value = LoadState(true)
        extraJob = viewModelScope.launch { _extra.value = load(path) }
    }
    fun plan(path: String) {
        journeyJob?.cancel(); _journey.value = LoadState(true)
        journeyJob = viewModelScope.launch { _journey.value = load(path) }
    }
    fun nearby(latitude: Double, longitude: Double) = extra("/v1/stops/nearby?latitude=$latitude&longitude=$longitude")
    fun prepareNearby() { extraJob?.cancel(); _extra.value = LoadState(true) }
    fun nearbyFailure(message: String) { _extra.value = LoadState(error = message) }
    suspend fun watchStop(stop: Stop) {
        delay(15_000)
        while (currentCoroutineContext().isActive) {
            _detail.value = load("/v1/stops/${stop.id}", live = true)
            delay(15_000)
        }
    }
    fun refresh() {
        if (manualRefresh?.isActive == true || refreshMutex.isLocked) return
        manualRefresh = viewModelScope.launch { refreshLines(force = true) }
    }

    suspend fun monitor() {
        settings.map { Triple(it.server, it.apiKey, it.mapLines.filter { line -> line.key in it.selected } to it.showVehicles) }
            .distinctUntilChanged().collectLatest {
                while (currentCoroutineContext().isActive) {
                    refreshLines()
                    delay(15_000)
                }
            }
    }
    private suspend fun refreshLines(force: Boolean = false) = refreshMutex.withLock {
        val s = settings.value
        // Seçili hat yoksa haritada yalnız duraklar görünür; kendiliğinden hat açılmaz
        val selected = s.mapLines.filter { it.key in s.selected }
        _snapshots.update { it.filterKeys { key -> selected.any { line -> line.key == key } } }
        // Two lines at a time: avoid making the upstream a request storm on resume.
        selected.chunked(2).forEach { batch -> coroutineScope { batch.map { line -> async {
            var current = _snapshots.value[line.key] ?: LineSnapshot(line)
            val shapePath = "/v1/lines/${line.id}/shape?direction=${line.direction}&format=geojson"
            val stopsPath = "/v1/lines/${line.id}/stops?direction=${line.direction}"
            if (current.shape == null) {
                current = withContext(Dispatchers.IO) { current.copy(shape = repository.cached(s.server, shapePath, s.apiKey), stops = repository.cached(s.server, stopsPath, s.apiKey)) }
                _snapshots.update { it + (line.key to current) }
            }
            if (force || System.currentTimeMillis() - (lastStatic[line.key] ?: 0) > 300_000) {
                val shape = load(shapePath, s)
                val stops = load(stopsPath, s)
                current = current.copy(shape = shape.result ?: current.shape, stops = stops.result ?: current.stops,
                    error = shape.error ?: stops.error ?: shape.result?.error ?: stops.result?.error)
                lastStatic[line.key] = System.currentTimeMillis()
                _snapshots.update { it + (line.key to current) }
            }
            // Araçlar gizliyse canlı sorgu yapılmaz (kaynağı ve pili boşuna yormasın)
            val vehicles = if (s.showVehicles) load("/v1/lines/${line.id}/vehicles?direction=${line.direction}", s, live = true) else LoadState()
            // Never keep the previous live result after a failed refresh.
            current = current.copy(vehicles = vehicles.result, error = vehicles.error ?: current.error)
            _snapshots.update { it + (line.key to current) }
        } }.awaitAll() } }
        FavoritesWidget.refreshAll(app)
    }
}
