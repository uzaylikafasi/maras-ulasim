package com.uzaylikafasi.marasulasim

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.uzaylikafasi.marasulasim.LegacyTls.withLegacyRoots
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val RELEASE_API = "https://api.github.com/repos/uzaylikafasi/maras-ulasim/releases/latest"
private const val DOWNLOAD_PREFIX = "https://github.com/uzaylikafasi/maras-ulasim/releases/download/"
const val UPDATE_CHECK_INTERVAL_MS = 21_600_000L

data class AppRelease(val version: String, val notes: String, val url: String, val size: Long, val digest: String?)
fun versionParts(raw: String): List<Int>? = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(raw.removeSuffix("-dev"))
    ?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }
fun newerVersion(candidate: String, installed: String): Boolean {
    val a = versionParts(candidate) ?: return false; val b = versionParts(installed) ?: return false
    for(i in a.indices) { if(a[i]!=b[i]) return a[i]>b[i] }
    return false
}
fun updateCheckDue(last: Long, now: Long) = last==0L || now<last || now-last>=UPDATE_CHECK_INTERVAL_MS
sealed class UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    object Failed : UpdateCheckResult()
    object NotChecked : UpdateCheckResult()
}

fun parseRelease(o: JSONObject, installed: String, abis: List<String>, skipped: String?, force: Boolean = false, legacy: Boolean = BuildConfig.ESKI_ANDROID): AppRelease? {
    if(o.optBoolean("draft") || o.optBoolean("prerelease")) return null
    val tag = o.optString("tag_name"); val version = tag.removePrefix("v")
    if(!newerVersion(version,installed) || (!force && version==skipped)) return null
    val assets = o.optJSONArray("assets") ?: return null
    // Android 6 sürümü ayrı APK'dır; normal APK Android 8+ ister ve eski telefona kurulmaz.
    val preferred = if(legacy) "maras-ulasim-v$version-android6.apk" else if("arm64-v8a" in abis) "maras-ulasim-v$version-arm64.apk" else "maras-ulasim-v$version.apk"
    val all = (0 until assets.length()).map { assets.getJSONObject(it) }
    val asset = all.firstOrNull { it.optString("name")==preferred }
        ?: (if(legacy) null else all.firstOrNull { it.optString("name")=="maras-ulasim-v$version.apk" }) ?: return null
    val url = asset.optString("browser_download_url")
    if(url != "$DOWNLOAD_PREFIX$tag/${asset.optString("name")}") return null
    val size = asset.optLong("size")
    if(size !in 1..100_000_000L) return null
    return AppRelease(version,o.optString("body").take(12_000),url,size,asset.optString("digest").takeIf { it.matches(Regex("sha256:[0-9a-f]{64}")) })
}

class AppUpdates(private val context: Context) {
    private val prefs = context.getSharedPreferences("app-updates",Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().withLegacyRoots().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(5,TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            require(url.isHttps && (url.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")))
            chain.proceed(chain.request())
        }.build()
    private companion object { val checkLock = Mutex() }
    suspend fun check(now: Long = System.currentTimeMillis(), force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        if(!checkLock.tryLock()) return@withContext UpdateCheckResult.NotChecked
        try {
            if(!force && !updateCheckDue(prefs.getLong("last",0),now)) return@withContext UpdateCheckResult.NotChecked
            client.newCall(Request.Builder().url(RELEASE_API).header("Accept","application/vnd.github+json").build()).execute().use { r ->
                check(r.isSuccessful)
                val body = r.body ?: error("Yanıt alınamadı.")
                check(body.contentLength()<=1_000_000)
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer=ByteArray(8192)
                    while(output.size()<=1_000_000) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count=input.read(buffer)
                        if(count<0) break
                        output.write(buffer,0,count)
                    }
                }
                val raw = output.toByteArray()
                check(raw.size<=1_000_000)
                val json = JSONObject(String(raw,Charsets.UTF_8))
                val version = json.getString("tag_name").removePrefix("v")
                check(versionParts(version)!=null)
                val skipped = prefs.getString("skipped",null)
                val release = parseRelease(json,BuildConfig.VERSION_NAME,Build.SUPPORTED_ABIS.toList(),skipped,force)
                // Yeni sürümün APK bilgileri geçersizse bunu "güncel" sayma.
                if(newerVersion(version,BuildConfig.VERSION_NAME) && (force || version!=skipped) &&
                    !json.optBoolean("draft") && !json.optBoolean("prerelease")) check(release!=null)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                prefs.edit().putLong("last",now).commit()
                if(release!=null) UpdateCheckResult.Available(release) else UpdateCheckResult.UpToDate
            }
        } catch(e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch(e: Exception) {
            UpdateCheckResult.Failed
        } finally {
            checkLock.unlock()
        }
    }
    fun skip(version: String) { prefs.edit().putString("skipped",version).apply() }
    suspend fun download(release: AppRelease): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir,"updates").apply { mkdirs() }
        val part = File(folder,"update.part.apk"); val target = File(folder,"update.apk")
        part.delete(); target.delete()
        try {
            client.newCall(Request.Builder().url(release.url).build()).execute().use { r ->
                check(r.isSuccessful) { "Dosya indirilemedi. Yeniden dene." }
                val body = r.body ?: error("Dosya alınamadı.")
                var count = 0L
                val hash = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(32_768)
                    while(true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if(n<0) break
                        count+=n; check(count<=release.size) { "Dosya boyutu doğrulanamadı." }
                        hash.update(buffer,0,n);output.write(buffer,0,n)
                    }
                } }
                check(count==release.size) { "İndirme tamamlanamadı." }
                val actual = "sha256:"+hash.digest().joinToString("") { "%02x".format(it) }
                check(release.digest==null || release.digest==actual) { "Dosya özeti doğrulanamadı." }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            verifyPackage(part,release.version)
            check(part.renameTo(target)) { "Dosya kaydedilemedi." }
            target
        } catch(e:Exception) { part.delete(); throw e }
    }
    @Suppress("DEPRECATION")
    internal fun verifyPackage(file: File, version: String) {
        val pm = context.packageManager
        // Bazı Android 9/10 cihazlar arşivde signingInfo alanını doldurmuyor.
        val flags = PackageManager.GET_SIGNATURES or (if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val apk = pm.getPackageArchiveInfo(file.absolutePath,flags) ?: error("APK okunamadı.")
        val installed = pm.getPackageInfo(context.packageName,flags)
        fun signatures(info: android.content.pm.PackageInfo): Set<String> =
            (if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners ?: info.signatures else info.signatures).orEmpty().map { it.toCharsString() }.toSet()
        check(apk.packageName==context.packageName && apk.versionName==version && signatures(apk).isNotEmpty()) { "APK imzası veya sürümü uygulamayla eşleşmiyor." }
        // Aynı uygulama ve sürüm ama başka anahtarla imzalıysa Android üstüne kurmaz; nedeni kullanıcıya açık söylenir.
        check(signatures(apk)==signatures(installed)) { "Bu sürüm, kurulu uygulamadan farklı bir anahtarla imzalı; Android üstüne kurmaz. Uygulamayı kaldırıp yeniden kurman gerekir (favoriler silinir)." }
    }
    fun install(file: File) {
        require(file.canonicalFile.parentFile==File(context.cacheDir,"updates").canonicalFile && file.name=="update.apk")
        check(file.isFile && file.length()>0) { "İndirilen APK bulunamadı. Yeniden indir." }
        val uri = FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** Kontrol, indirme ve hazır APK ekran dönüşlerinde korunur. */
class UpdateViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    val updates = AppUpdates(application)
    val state = kotlinx.coroutines.flow.MutableStateFlow(UpdateState())
    fun checkAutomatically() = checkForUpdates(force=false)
    fun checkNow() = checkForUpdates(force=true)
    private fun checkForUpdates(force: Boolean) {
        if(state.value.checking || state.value.downloading || state.value.release!=null) return
        state.value = state.value.copy(checking=true,message=null)
        viewModelScope.launch {
            try {
                when(val result = updates.check(force=force)) {
                    is UpdateCheckResult.Available -> state.value = state.value.copy(release=result.release,
                        message=if(force) "Yeni sürüm: ${result.release.version}." else null)
                    UpdateCheckResult.UpToDate -> if(force) state.value = state.value.copy(message="Uygulama güncel (${BuildConfig.VERSION_NAME}).")
                    UpdateCheckResult.Failed -> if(force) state.value = state.value.copy(message="Güncelleme kontrol edilemedi. Bağlantını kontrol et.")
                    UpdateCheckResult.NotChecked -> if(force) state.value = state.value.copy(message="Güncelleme kontrolü sürüyor. Biraz sonra tekrar dene.")
                }
            } finally {
                state.value = state.value.copy(checking=false)
            }
        }
    }
    fun dismiss(skip: Boolean = false) {
        if(state.value.downloading) return
        if(skip) state.value.release?.let { updates.skip(it.version) }
        state.value = state.value.copy(release=null,file=null,error=null)
    }
    fun download() {
        val release = state.value.release ?: return
        if(state.value.downloading) return
        state.value = state.value.copy(downloading=true,error=null)
        viewModelScope.launch {
            try { state.value = state.value.copy(file=updates.download(release)) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { state.value = state.value.copy(error=(e as? IllegalStateException)?.message
                ?: "İndirme tamamlanamadı veya APK doğrulanamadı. Bağlantını kontrol edip tekrar dene.") }
            finally { state.value = state.value.copy(downloading=false) }
        }
    }
    fun installFailed() {
        state.value=state.value.copy(file=null,error="Yükleyici açılamadı. APK'yı yeniden indirip dene.")
    }
    fun error(message: String) { state.value=state.value.copy(error=message) }
}
data class UpdateState(val release: AppRelease? = null, val file: File? = null, val downloading: Boolean = false, val error: String? = null, val checking: Boolean = false, val message: String? = null)
