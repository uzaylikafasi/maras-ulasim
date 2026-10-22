package com.uzaylikafasi.marasulasim.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

    suspend fun get(base: String, key: String, path: String): String {
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
                    if (continuation.isActive) continuation.resumeWithException(DataException("Sunucuya bağlanılamadı. Bağlantıyı kontrol edip yenile.", e))
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
                            response.code == 401 -> Result.failure(DataException("Sunucu erişim anahtarını kontrol et."))
                            response.code == 429 -> Result.failure(DataException("Sunucu şu an yoğun. Biraz sonra yenile."))
                            response.code == 503 || response.code == 502 || response.code == 504 ->
                                Result.failure(DataException("Ulaşım servisine şu anda ulaşılamıyor. Biraz sonra yenile."))
                            !response.isSuccessful -> Result.failure(DataException("Veri alınamadı. Daha sonra tekrar dene."))
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

fun searchPath(query: String): String = HttpUrl.Builder().scheme("https").host("local.invalid")
    .addPathSegments("v1/search").addQueryParameter("q", query).build().let { it.encodedPath + "?" + it.encodedQuery }

interface ResponseCache { fun read(key: String): String?; fun write(key: String, raw: String) }

class TransportRepository(private val api: TransportApi, private val cache: ResponseCache) {
    private fun cacheKey(base: String, key: String, path: String): String {
        val scope = if (key.isEmpty()) "" else java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + "|"
        return "$base|$scope$path"
    }
    suspend fun fetch(base: String, key: String, path: String, live: Boolean = false): ApiResult {
        val cacheKey = cacheKey(base, key, path)
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
            throw e
        }
    }
    fun cached(base: String, path: String, key: String = ""): ApiResult? = runCatching {
        cache.read(cacheKey(base, key, path))?.let { decodeEnvelope(it, true) }
    }.getOrNull()
}
