package com.uzaylikafasi.marasulasim.data

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream

enum class ModelSource { DOWNLOADED, BUNDLED }

/** Modelin kimliği: şema, sürüm, üretim zamanı ve gzip baytlarının sha256'sı (model-meta.json). */
data class ModelMeta(val schema: Int, val version: String, val sha256: String, val builtAt: String) {
    companion object {
        /** Zarflı (`{"data": {...}}`) ya da doğrudan meta gövdesi; eksik/bozuksa null. */
        fun parse(raw: String): ModelMeta? = runCatching {
            val root = JSONObject(raw)
            val o = root.optJSONObject("data")?.takeIf { it.has("sha256") } ?: root
            val m = ModelMeta(o.getInt("schema"), o.getString("model_version"), o.getString("sha256").lowercase(), o.getString("built_at"))
            m.takeIf { it.sha256.length == 64 && it.version.isNotBlank() }
        }.getOrNull()
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun gunzip(bytes: ByteArray, limit: Int = 64_000_000): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
    while (true) { val n = input.read(buf); if (n < 0) break; if (out.size() + n > limit) throw DataException("Model beklenenden büyük."); out.write(buf, 0, n) }
    out.toByteArray()
}
private fun instantOrNull(s: String) = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()

/** Saf kararlar: hangi kaynak kullanılır, güncelleme gerekli mi, indirilen dosya geçerli mi. */
object ModelRules {
    /** İndirilen model tercih edilir; geçersizse ya da tohumdan eskiyse (uygulama daha yeni tohumla geldiyse) tohum. */
    fun choose(downloaded: ModelMeta?, bundled: ModelMeta?): ModelSource? {
        val d = downloaded?.takeIf { it.schema == SUPPORTED_MODEL_SCHEMA }
        val b = bundled?.takeIf { it.schema == SUPPORTED_MODEL_SCHEMA }
        if (d == null) return if (b != null) ModelSource.BUNDLED else null
        if (b == null) return ModelSource.DOWNLOADED
        val di = instantOrNull(d.builtAt); val bi = instantOrNull(b.builtAt)
        return if (di != null && bi != null && di < bi) ModelSource.BUNDLED else ModelSource.DOWNLOADED
    }

    /** Sunucu özeti yeni bir model gösteriyor mu: şema desteklenir, sha256 farklı, üretim zamanı geriye gitmez. */
    fun shouldDownload(remote: ModelMeta, current: ModelMeta?): Boolean {
        if (remote.schema != SUPPORTED_MODEL_SCHEMA) return false
        if (current == null) return true
        if (remote.sha256 == current.sha256) return false
        val r = instantOrNull(remote.builtAt) ?: return false
        val c = instantOrNull(current.builtAt)
        return c == null || r > c
    }

    /** İndirilen baytları doğrular: sha256, gzip + JSON, şema, sürüm ve üretim zamanı özetle aynı olmalı. */
    fun verify(bytes: ByteArray, meta: ModelMeta): Result<OfflineModel> = runCatching {
        if (sha256Hex(bytes) != meta.sha256) throw DataException("Model özeti (sha256) uyuşmuyor.")
        val model = OfflineModel.parse(String(gunzip(bytes), Charsets.UTF_8))
        if (meta.schema != SUPPORTED_MODEL_SCHEMA || model.root.optInt("schema") != meta.schema) throw DataException("Model şeması uyuşmuyor.")
        if (model.version != meta.version || model.builtAt != meta.builtAt) throw DataException("Model sürümü uyuşmuyor.")
        model
    }
}

/**
 * Model kaynağı: filesDir/offline/offline-model.json.gz (indirilen) varsa ve geçerliyse o, yoksa gömülü tohum.
 * Yazma: geçici dosya + fsync + atomik taşıma; herhangi bir hatada eski dosya olduğu gibi kalır.
 */
class ModelStore(private val dir: File, private val openSeed: () -> InputStream?, private val openSeedMeta: () -> InputStream?) {
    data class Loaded(val model: OfflineModel, val source: ModelSource, val meta: ModelMeta)
    private val modelFile get() = File(dir, "offline-model.json.gz")
    private val metaFile get() = File(dir, "model-meta.json")
    @Volatile private var loaded: Loaded? = null
    @Volatile private var attempted = false

    @Synchronized fun current(): Loaded? {
        if (loaded == null && !attempted) { loaded = load(); attempted = true }
        return loaded
    }
    fun model(): OfflineModel? = current()?.model
    @Synchronized fun invalidate() { loaded = null; attempted = false }

    private fun seedMeta(): ModelMeta? = runCatching { openSeedMeta()?.use { ModelMeta.parse(it.readBytes().toString(Charsets.UTF_8)) } }.getOrNull()
    private fun downloadedMeta(): ModelMeta? = runCatching { metaFile.takeIf { it.isFile }?.readText()?.let(ModelMeta::parse) }.getOrNull()

    private fun load(): Loaded? {
        val dMeta = downloadedMeta(); val bMeta = seedMeta()
        val choice = ModelRules.choose(dMeta, bMeta)
        if (choice == ModelSource.DOWNLOADED && dMeta != null) {
            val r = runCatching { ModelRules.verify(modelFile.readBytes(), dMeta).getOrThrow() }.getOrNull()
            if (r != null) return Loaded(r, ModelSource.DOWNLOADED, dMeta)
        }
        return runCatching {
            val bytes = openSeed()?.use { it.readBytes() } ?: return null
            val model = OfflineModel.parse(String(gunzip(bytes), Charsets.UTF_8))
            Loaded(model, ModelSource.BUNDLED, bMeta ?: ModelMeta(SUPPORTED_MODEL_SCHEMA, model.version, sha256Hex(bytes), model.builtAt))
        }.getOrNull()
    }

    /** Doğrulanmış baytları atomik olarak yerleştirir. Hata olursa yürürlükteki model değişmez. */
    @Synchronized fun install(bytes: ByteArray, meta: ModelMeta) {
        dir.mkdirs()
        writeAtomic(modelFile, bytes)
        writeAtomic(metaFile, JSONObject().put("schema", meta.schema).put("model_version", meta.version)
            .put("sha256", meta.sha256).put("built_at", meta.builtAt).toString().toByteArray(Charsets.UTF_8))
        loaded = null; attempted = false
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.flush(); it.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (tmp.exists()) tmp.delete() }
    }
}

/** Günlük güncelleme: meta'yı çek, farklıysa indir, doğrula, yerleştir. Her hata sessizce eski modeli korur. */
class ModelUpdater(private val api: TransportApi, private val store: ModelStore) {
    enum class Outcome { UPDATED, UP_TO_DATE, SKIPPED }
    suspend fun run(server: String, key: String): Outcome {
        val remote = try { ModelMeta.parse(api.get(server, key, "/v1/offline-model/meta")) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            ?: return Outcome.SKIPPED
        val current = store.current()?.meta
        if (!ModelRules.shouldDownload(remote, current)) return Outcome.UP_TO_DATE
        val bytes = try { api.download(server, key, "/offline-model", 30_000_000) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return Outcome.SKIPPED }
        if (ModelRules.verify(bytes, remote).isFailure) return Outcome.SKIPPED
        return try { store.install(bytes, remote); Outcome.UPDATED } catch (_: Exception) { Outcome.SKIPPED }
    }
}
