package com.uzaylikafasi.marasulasim.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun validatedServer(raw: String, debug: Boolean): String {
    val url = raw.trim().toHttpUrlOrNull() ?: throw DataException("Geçerli bir sunucu adresi yaz.")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
        throw DataException("Sunucu adresine kullanıcı bilgisi veya sorgu ekleme.")
    val local = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
    if (!url.isHttps && !(debug && local)) throw DataException("Sunucu adresi HTTPS ile başlamalı.")
    return url.toString().trimEnd('/')
}
fun validatedApiKey(raw: String): String {
    if (raw.any { it.code !in 32..126 }) throw DataException("Erişim anahtarı geçersiz.")
    return raw.trim()
}

class TransportApi(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).build()) {

    // Uygulamanın tüm ön plan/arka plan sorguları aynı kapıdan geçer: en çok 60 başlangıç/dk.
    private val requestGate = kotlinx.coroutines.sync.Mutex()
    private var nextRequest = 0L
    /** İkili dosya indirir (çevrimdışı model); en çok [maxBytes] bayt. Hata durumunda DataException. */
    suspend fun download(base: String, key: String, path: String, maxBytes: Int): ByteArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = (base + path).toHttpUrlOrNull() ?: throw DataException("Sunucu adresi geçersiz.")
        val accessKey = validatedApiKey(key)
        val request = Request.Builder().url(url)
            .header("User-Agent", "MarasUlasimAndroid/1.0 (+https://github.com/uzaylikafasi/maras-ulasim)")
            .apply { if (accessKey.isNotBlank()) header("X-API-Key", accessKey) }.build()
        val slow = client.newBuilder().callTimeout(120, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        try {
            slow.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DataException("Dosya alınamadı (${response.code}).")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                response.body?.byteStream()?.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBytes) throw DataException("Dosya beklenenden büyük.")
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
        } catch (e: IOException) { throw ApiException("Sunucuya bağlanılamadı.", FailureKind.NETWORK, e) }
    }
    suspend fun get(base: String, key: String, path: String): String {
        requestGate.lock()
        try {
            val wait = nextRequest - System.nanoTime() / 1_000_000
            if (wait > 0) kotlinx.coroutines.delay(wait)
            nextRequest = System.nanoTime() / 1_000_000 + 1_000
        } finally { requestGate.unlock() }
        val accessKey = validatedApiKey(key)
        val url = (base + path).toHttpUrlOrNull() ?: throw DataException("Sunucu adresi geçersiz.")
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "MarasUlasimAndroid/1.0 (+https://github.com/uzaylikafasi/maras-ulasim)")
            .apply { if (accessKey.isNotBlank()) header("X-API-Key", accessKey) }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiException("Sunucuya bağlanılamadı. Bağlantıyı kontrol edip yenile.", FailureKind.NETWORK, e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use {
                        val stream = response.body?.byteStream()
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        stream?.use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 4_000_000) throw DataException("Sunucu yanıtı beklenenden büyük.")
                                output.write(buffer, 0, count)
                            }
                        }
                        val body = output.toString(Charsets.UTF_8.name())
                        val result = when {
                            response.code == 401 -> Result.failure(ApiException("Sunucu erişim anahtarını kontrol et.", FailureKind.AUTH))
                            response.code == 429 -> Result.failure(ApiException("Sunucu şu an yoğun. Biraz sonra yenile.", FailureKind.RATE_LIMIT))
                            response.code == 503 || response.code == 502 || response.code == 504 ->
                                Result.failure(ApiException("Ulaşım servisine şu anda ulaşılamıyor. Biraz sonra yenile.", serviceFailureKind(body)))
                            !response.isSuccessful -> Result.failure(ApiException("Veri alınamadı. Daha sonra tekrar dene.", FailureKind.OTHER))
                            else -> Result.success(body)
                        }
                        if (continuation.isActive) continuation.resumeWith(result)
                    } } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(DataException("Sunucu yanıtı alınamadı. Yeniden dene."))
                    }
                }
            })
        }
    }
}

/** 502/503/504 gövdesindeki hata kodundan: kaynak kimlik ya da hız sınırı hatası kesinti sayılmaz (çevrimdışıyla gizlenmez). */
fun serviceFailureKind(body: String): FailureKind {
    val code = runCatching { JSONObject(body).optJSONObject("error")?.optString("code").orEmpty() }.getOrDefault("")
    return when {
        code.contains("AUTH") || code == "INVALID_API_KEY" -> FailureKind.AUTH
        code.contains("RATE_LIMIT") -> FailureKind.RATE_LIMIT
        else -> FailureKind.SERVICE_DOWN
    }
}

fun searchPath(query: String): String = HttpUrl.Builder().scheme("https").host("local.invalid")
    .addPathSegments("v1/search").addQueryParameter("q", query).build().let { it.encodedPath + "?" + it.encodedQuery }

interface ResponseCache { fun read(key: String): String?; fun write(key: String, raw: String) }

class TransportRepository(private val api: TransportApi, private val cache: ResponseCache,
                          private val offline: OfflineProvider? = null, private val config: () -> OfflineConfig = { OfflineConfig(OfflineMode.OFF) }) {
    private fun cacheKey(base: String, key: String, path: String): String {
        val scope = if (key.isEmpty()) "" else java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + "|"
        return "$base|$scope$path"
    }
    suspend fun fetch(base: String, key: String, path: String, live: Boolean = false): ApiResult {
        val cacheKey = cacheKey(base, key, path)
        // Düğme kipinde kullanıcı çevrimdışıyı açtıysa ağa sorulmaz (cevap hiçbir yere kaydedilmez).
        if (OfflinePolicy.serveBeforeNetwork(config())) offlineAnswer(path)?.let { return it }
        try {
            val raw = api.get(base, key, path)
            val decoded = decodeEnvelope(raw)
            if (!live) runCatching { cache.write(cacheKey, raw) }
            return decoded
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (e: Exception) {
            if (!live) runCatching { cache.read(cacheKey) }.getOrNull()?.let {
                runCatching { decodeEnvelope(it, true, e.message) }.getOrNull()?.let { decoded -> return decoded }
            }
            // Otomatik kip: ağ ve kayıtlı kopya yok; yalnız bağlantı/servis kesintisinde (anahtar/hız sınırı hatası gizlenmez).
            val kind = (e as? ApiException)?.kind
            if (kind != null && OfflinePolicy.serveAfterFailure(config(), kind)) offlineAnswer(path)?.let { return it }
            throw e
        }
    }
    /** Çevrimdışı cevap önbelleğe yazılmaz ve öğrenmeye girmez. */
    private fun offlineAnswer(path: String): ApiResult? = runCatching { offline?.answer(path)?.let { decodeEnvelope(it) } }.getOrNull()
    fun cached(base: String, path: String, key: String = ""): ApiResult? = runCatching {
        cache.read(cacheKey(base, key, path))?.let { decodeEnvelope(it, true) }
    }.getOrNull()
}
