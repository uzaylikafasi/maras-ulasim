package com.uzaylikafasi.marasulasim.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.preferences by preferencesDataStore("transport", produceMigrations = { listOf(PilotHttpsMigration()) })
/** lines: favori hatlar. mapLines: haritaya eklenmiş hatlar (favoriden bağımsız); selected: bunlardan görünenler. */
data class Settings(val server: String = DEFAULT_SERVER, val apiKey: String = "",
                    val lines: List<Line> = emptyList(), val stops: List<Stop> = emptyList(),
                    val selected: Set<String> = emptySet(), val plates: Set<String> = emptySet(),
                    val mapLines: List<Line> = emptyList(), val showVehicles: Boolean = true,
                    val favoritesOnly: Boolean = false) {
    val favoriteCodes: Set<String> get() = lines.map { it.code }.toSet()
}

class LocalStore(private val context: Context) {
    private val lines = stringPreferencesKey("lines")
    private val stops = stringPreferencesKey("stops")
    private val selected = stringPreferencesKey("selected")
    private val plates = stringPreferencesKey("plates")
    private val mapLines = stringPreferencesKey("map_lines")
    private val showVehicles = androidx.datastore.preferences.core.booleanPreferencesKey("show_vehicles")
    private val favoritesOnly = androidx.datastore.preferences.core.booleanPreferencesKey("stop_favorites_only")
    val settings = context.preferences.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.map { p ->
        // 1.0: sunucu sabit (Ömer: "adres sabit kalsın"); eski özel adres ve anahtar artık kullanılmaz
        Settings(DEFAULT_SERVER, "",
            decodeLines(p[lines]), decodeStops(p[stops]),
            runCatching { JSONArray(p[selected] ?: "[]").texts().toSet() }.getOrDefault(emptySet()),
            runCatching { JSONArray(p[plates] ?: "[]").texts().toSet() }.getOrDefault(emptySet()),
            // 0.1.2 ve öncesinde harita hatları favorilerde tutuluyordu: ilk açılışta oradan alınır
            p[mapLines]?.let(::decodeLines) ?: decodeLines(p[lines]),
            p[showVehicles] ?: true, p[favoritesOnly] ?: false)
    }
    suspend fun toggleLine(line: Line) { context.preferences.edit { p ->
        val values = decodeLines(p[lines]).toMutableList()
        // Eski sürümde harita listesi favorilerdi: ayrılmadan önce harita listesi bir kez kopyalanır
        if (p[mapLines] == null) p[mapLines] = JSONArray(values.map { it.json() }).toString()
        if (values.any { it.key == line.key }) values.removeAll { it.key == line.key } else values.add(line)
        p[lines] = JSONArray(values.map { it.json() }).toString()
    } }
    /** Hattı haritada göster/gizle. Haritaya eklemek hattı favorilere EKLEMEZ. */
    suspend fun toggleSelected(line: Line) { context.preferences.edit { p ->
        val values = JSONArray(p[selected] ?: "[]").texts().toMutableSet()
        if (!values.remove(line.key)) {
            if (values.size >= 4) throw DataException("Aynı anda en fazla 4 hat seçebilirsin.")
            values.add(line.key)
            val onMap = p[mapLines]?.let(::decodeLines) ?: decodeLines(p[lines])
            if (onMap.none { it.key == line.key }) p[mapLines] = JSONArray((onMap + line).map { it.json() }).toString()
        }
        p[selected] = JSONArray(values.toList()).toString()
    } }
    /**
     * Hattı (bütün yönleriyle) haritada aç/kapat. Gidiş ve dönüş birlikte görünür; böylece son durakta
     * dönüşe geçen otobüs haritadan kaybolmaz. En fazla 4 hat. Favorilere dokunmaz.
     */
    suspend fun toggleLineOnMap(code: String, directions: List<Line>) { context.preferences.edit { p ->
        val onMap = (p[mapLines]?.let(::decodeLines) ?: decodeLines(p[lines])).toMutableList()
        val values = JSONArray(p[selected] ?: "[]").texts().toMutableSet()
        val codeOf = (onMap + directions).associate { it.key to it.code }
        val open = values.any { codeOf[it] == code }
        if (open) values.removeAll { codeOf[it] == code }
        else {
            if (values.mapNotNull { codeOf[it] }.toSet().size >= 4) throw DataException("Aynı anda en fazla 4 hat gösterebilirsin.")
            directions.forEach { d -> if (onMap.none { it.key == d.key }) onMap.add(d); values.add(d.key) }
        }
        p[mapLines] = JSONArray(onMap.map { it.json() }).toString()
        p[selected] = JSONArray(values.toList()).toString()
    } }
    /** Hattı (bütün yönleriyle) haritadaki listeden kaldırır; favoriye dokunmaz. */
    suspend fun removeFromMap(line: Line) { context.preferences.edit { p ->
        val all = p[mapLines]?.let(::decodeLines) ?: decodeLines(p[lines])
        val gone = all.filter { it.code == line.code }.map { it.key }.toSet() + line.key
        p[mapLines] = JSONArray(all.filter { it.key !in gone }.map { it.json() }).toString()
        p[selected] = JSONArray(JSONArray(p[selected] ?: "[]").texts().filter { it !in gone }).toString()
    } }
    suspend fun setShowVehicles(value: Boolean) { context.preferences.edit { it[showVehicles] = value } }
    suspend fun setFavoritesOnly(value: Boolean) { context.preferences.edit { it[favoritesOnly] = value } }
    /** Hat numarasını (bütün yönleriyle) favorilerden çıkarır. */
    suspend fun removeLineCode(code: String) { context.preferences.edit { p ->
        val values = decodeLines(p[lines])
        if (p[mapLines] == null) p[mapLines] = JSONArray(values.map { it.json() }).toString()
        p[lines] = JSONArray(values.filter { it.code != code }.map { it.json() }).toString()
    } }
    suspend fun toggleStop(stop: Stop) { context.preferences.edit { p ->
        val values = decodeStops(p[stops]).toMutableList()
        if (!values.removeAll { it.id == stop.id }) values.add(stop)
        p[stops] = JSONArray(values.map { it.json() }).toString()
    } }
    suspend fun togglePlate(plate: String) { context.preferences.edit { p ->
        val values = JSONArray(p[plates] ?: "[]").texts().toMutableSet()
        if (!values.remove(plate)) values.add(plate)
        p[plates] = JSONArray(values.toList()).toString()
    } }
    private fun decodeLines(s: String?): List<Line> = runCatching { JSONArray(s ?: "[]").objects().map(::parseLine) }.getOrDefault(emptyList())
    private fun decodeStops(s: String?) = runCatching { JSONArray(s ?: "[]").objects().map(::parseStop) }.getOrDefault(emptyList())
}

class DiskCache(context: Context) : SQLiteOpenHelper(context, "transport-cache.db", null, 1), ResponseCache {
    init {
        writableDatabase
        val p = context.getSharedPreferences("cache-import", Context.MODE_PRIVATE)
        if (!p.getBoolean("seed-v2-https", false)) {
            val samples = JSONObject(context.assets.open("recorded-routes.json").bufferedReader().use { it.readText() })
            samples.keys().forEach { key ->
                val cacheKey = "$DEFAULT_SERVER|$key"
                if (read(cacheKey) == null) write(cacheKey, samples.getJSONObject(key).toString())
            }
            p.edit().putBoolean("seed-v2-https", true).apply()
        }
    }
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE responses (key TEXT PRIMARY KEY, raw TEXT NOT NULL, written INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized override fun read(key: String): String? = readableDatabase.rawQuery("SELECT raw FROM responses WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    @Synchronized override fun write(key: String, raw: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO responses(key,raw,written) VALUES(?,?,?)", arrayOf<Any>(key, raw, System.currentTimeMillis()))
        writableDatabase.execSQL("DELETE FROM responses WHERE key NOT IN (SELECT key FROM responses ORDER BY written DESC LIMIT 160)")
    }
}
