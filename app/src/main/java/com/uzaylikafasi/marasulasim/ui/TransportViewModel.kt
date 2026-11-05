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
/** Duraktan seçilen tek otobüs: haritada yalnız o görünür ve kamera onu izler. */
data class TrackedBus(val line: Line, val plate: String, val stop: Stop, val vehicles: ApiResult? = null, val error: String? = null, val fallbackLine: Line? = null) {
    val key get() = "${line.id}:$plate"
    /** Haritaya verilen tek hatlık görünüm: güzergâh ve hat durakları yok, yalnız bu plaka. */
    fun snapshot(): LineSnapshot = LineSnapshot(line, vehicles = vehicles?.let { r ->
        runCatching { r.copy(data = org.json.JSONArray(r.arrayData.objects().filter { it.optString("plate") == plate })) }.getOrNull()
    })
    fun vehicle(): Vehicle? = runCatching { vehicles?.takeUnless { it.isOld() }?.arrayData?.let(::parseVehicles)?.firstOrNull { it.plate == plate } }.getOrNull()
}
data class LoadState(val loading: Boolean = false, val result: ApiResult? = null, val error: String? = null)

class TransportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TransportApplication
    private val repository get() = app.repository
    private val stopLoader by lazy { StopDetails(repository) }
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
    fun stopPad(value: Boolean) = action { app.store.setStopPad(value) }
    fun lightPad(value: Boolean) = action { app.store.setLightPad(value) }
    val lightLearner get() = app.lights.learner
    /** Canlı konumlardan ışık öğrenme (durak dışında duran otobüsler); disk işi IO'da. */
    private val learnedAt = mutableMapOf<String, String>()
    private suspend fun learn(path: String, result: ApiResult?) {
        // Sunucu önbelleğinden gelen aynı ölçüm, otobüsü yanlışlıkla "duruyor" gösterir: aynı zaman damgası bir kez sayılır
        val at = result?.fetchedAt ?: return
        if (result.stale || learnedAt.put(path, at) == at) return
        val vehicles = runCatching { result.arrayData.let(::parseVehicles) }.getOrNull() ?: return
        app.lights.observe(vehicles)
    }
    fun trackOnTap(value: Boolean) = action { app.store.setTrackOnTap(value) }
    private val _tracked = MutableStateFlow<TrackedBus?>(null)
    val tracked = _tracked.asStateFlow()
    /** Yaklaşan otobüse dokununca: hat kodu ve gidilen durakla o plaka izlenir. */
    fun track(arrival: Arrival, stop: Stop) = action {
        val id = arrival.lineId.toLongOrNull() ?: return@action notify("Bu otobüsün hattı bildirilmedi, izlenemiyor.")
        val s = settings.value
        val known = (s.mapLines + s.lines).filter { it.code == arrival.code }
        val found = load(searchPath(arrival.code),s).result?.objectData?.let(::parseSearch)?.lines
            ?.filter { it.code == arrival.code }.orEmpty()
        val directions = (found+known).distinctBy { it.key }
        val line = directions.firstOrNull { it.id == id }
        if(line==null) return@action notify("Otobüsün yönü doğrulanamadı. Yeniden dene.")
        _tracked.value = TrackedBus(line, arrival.plate, stop, fallbackLine=directions.firstOrNull { it.direction!=line.direction })
    }
    fun stopTracking() { _tracked.value = null }
    suspend fun watchTracked() {
        while (currentCoroutineContext().isActive) {
            val t = _tracked.value ?: return
            val s = settings.value
            val path = "/v1/lines/${t.line.id}/vehicles?direction=${t.line.direction}"
            var r = load(path, s, live = true)
            learn(path, r.result)
            // Terminalde araç hâlâ biten yöne atanmış olabilir: aynı plakayı karşı yönde de ara.
            if(t.copy(vehicles=r.result).vehicle()==null) t.fallbackLine?.let { opposite ->
                val fallbackPath="/v1/lines/${opposite.id}/vehicles?direction=${opposite.direction}"
                val fallback=load(fallbackPath,s,live=true)
                learn(fallbackPath,fallback.result)
                if(t.copy(vehicles=fallback.result).vehicle()!=null) r=fallback
            }
            _tracked.update { if (it?.key != t.key) it else it.copy(vehicles = r.result, error = r.error
                ?: if (r.result != null && TrackedBus(t.line, t.plate, t.stop, r.result).vehicle() == null) "Otobüs şu an konum bildirmiyor." else null) }
            delay(10_000)
        }
    }
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
    private val _lineStopIds = MutableStateFlow<Map<String, List<Stop>>>(emptyMap())
    /** Hat kimliği (lineId) -> hattın durakları, sıralı. Yenileme sırasında kesilmesin diye hat hat, kademeli dolar. */
    val lineStopIds = _lineStopIds.asStateFlow()
    private val _stopVehicles = MutableStateFlow<Map<String, Vehicle>>(emptyMap())
    /** Açık duraktaki yaklaşan otobüslerin canlı konumu (plaka -> araç): yol üstündeki ışıklar ve "ışıkta bekliyor". */
    val stopVehicles = _stopVehicles.asStateFlow()
    private val lineStopsLoading = mutableSetOf<String>()
    fun prefetchLineStops(lineIds: Collection<String>) {
        val todo = lineIds.filter { it.isNotBlank() && it !in _lineStopIds.value && lineStopsLoading.add(it) }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            val s = settings.value
            todo.chunked(2).forEach { batch -> coroutineScope { batch.map { id -> async {
                try {
                    val raw = withContext(Dispatchers.IO) { repository.fetch(s.server, s.apiKey, "/v1/lines/$id/stops?direction=1") }.objectData
                    val ids = raw.requiredArray("lineStops").objects().sortedBy { it.optInt("sequenceNo", 0) }.mapNotNull { runCatching { parseStop(it) }.getOrNull() }
                    _lineStopIds.update { it + (id to ids) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { /* sonraki yenilemede yeniden denenir */ }
                finally { lineStopsLoading.remove(id) }
            } }.awaitAll() } }
        }
    }
    fun stopDetails(stop: Stop) {
        detailJob?.cancel(); _detail.value = LoadState(true)
        detailJob = viewModelScope.launch { _detail.value = loadStop(stop); stopVehicles() }
    }
    private suspend fun loadStop(stop: Stop): LoadState = try {
        LoadState(result = withContext(Dispatchers.IO) { stopLoader.fetch(stop, settings.value) { _detail.value = LoadState(result = it) } })
    } catch (cancel: CancellationException) { throw cancel }
    catch (e: Exception) { LoadState(error = e.message ?: "Durak bilgisi alınamadı.") }
    fun clearJourney() { journeyJob?.cancel(); _journey.value = LoadState() }
    fun clearDetail() { detailJob?.cancel(); _detail.value = LoadState(); _stopVehicles.value = emptyMap() }
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
        journeyJob = viewModelScope.launch {
            val loaded = load(path, live = true)
            _journey.value = try { loaded.result?.objectData?.let(::parseJourneys); loaded }
            catch (e: Exception) { LoadState(error = e.message ?: "Rota okunamadı.") }
        }
    }
    fun nearby(latitude: Double, longitude: Double) = extra("/v1/stops/nearby?latitude=$latitude&longitude=$longitude")
    fun prepareNearby() { extraJob?.cancel(); _extra.value = LoadState(true) }
    fun nearbyFailure(message: String) { _extra.value = LoadState(error = message) }
    suspend fun watchStop(stop: Stop) {
        delay(30_000)
        while (currentCoroutineContext().isActive) {
            _detail.value = loadStop(stop)
            stopVehicles()
            delay(30_000)
        }
    }
    /** Yaklaşan otobüslerin hatlarının canlı konumları (en çok 6 hat, ikişer ikişer). */
    private suspend fun stopVehicles() {
        if(!settings.value.lightPad) { _stopVehicles.value=emptyMap(); return }
        val arrivals = runCatching { _detail.value.result?.objectData?.requiredArray("buses")?.let(::parseArrivals) }.getOrNull().orEmpty()
        val plates = arrivals.map { it.plate }.toSet()
        val s = settings.value
        val found = mutableMapOf<String, Vehicle>()
        arrivals.map { it.lineId }.filter { it.isNotBlank() }.distinct().take(3).chunked(2).forEach { batch -> coroutineScope { batch.map { id -> async {
            val path = "/v1/lines/$id/vehicles?direction=1"
            val r = load(path, s, live = true)
            learn(path, r.result)
            runCatching { r.result?.arrayData?.let(::parseVehicles) }.getOrNull().orEmpty().filter { it.plate in plates }
        } }.awaitAll().flatten().forEach { found[it.plate] = it } } }
        _stopVehicles.value = found
    }
    fun refresh() {
        if (manualRefresh?.isActive == true || refreshMutex.isLocked) return
        manualRefresh = viewModelScope.launch { refreshLines(force = true) }
    }

    suspend fun monitor() {
        settings.map { Triple(it.server, it.apiKey, it.mapLines.filter { line -> line.key in it.selected } to it.showVehicles) }
            .distinctUntilChanged().collectLatest {
                while (currentCoroutineContext().isActive) {
                    if(_tracked.value==null) refreshLines()
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
            val vehiclesPath = "/v1/lines/${line.id}/vehicles?direction=${line.direction}"
            val vehicles = if (s.showVehicles) load(vehiclesPath, s, live = true) else LoadState()
            // Never keep the previous live result after a failed refresh.
            learn(vehiclesPath, vehicles.result)
            current = current.copy(vehicles = vehicles.result, error = vehicles.error ?: current.error)
            _snapshots.update { it + (line.key to current) }
        } }.awaitAll() } }
        FavoritesWidget.refreshAll(app)
    }
}
