package com.uzaylikafasi.marasulasim

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uzaylikafasi.marasulasim.ui.TransportApp
import com.uzaylikafasi.marasulasim.ui.TransportViewModel
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var locationGranted: ((Boolean) -> Unit)? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationGranted?.invoke(result.values.any { it }); locationGranted = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Açık tema: durum çubuğu ve gezinme çubuğu simgeleri koyu
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE))
        setContent {
            val vm: TransportViewModel = viewModel()
            TransportApp(vm, onLocate = { granted ->
                lifecycleScope.launch {
                    val ok = hasLocation() || suspendCancellableCoroutine { continuation ->
                        locationGranted = { if (continuation.isActive) continuation.resume(it) }
                        continuation.invokeOnCancellation { locationGranted = null }
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                    when {
                        !ok -> vm.notify("Konum izni verilmedi.")
                        !locationOn() -> vm.notify("Telefonun konumu kapalı. Konumu açıp tekrar dene.")
                        else -> currentLocation()?.let { granted(it.latitude, it.longitude) }
                            ?: vm.notify("Konum alınamadı. Açık bir alanda tekrar dene.")
                    }
                }
            }, onNearby = {
                lifecycleScope.launch {
                    val granted = hasLocation() || suspendCancellableCoroutine { continuation ->
                        locationGranted = { if (continuation.isActive) continuation.resume(it) }
                        continuation.invokeOnCancellation { locationGranted = null }
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                    if (!granted) vm.nearbyFailure("Konum izni verilmedi. Durak adıyla arama yapabilirsin.")
                    else {
                        val location = currentLocation()
                        if (!locationOn()) vm.nearbyFailure("Telefonun konumu kapalı. Konumu açıp tekrar dene.")
                        else if (location == null) vm.nearbyFailure("Konum alınamadı. Konumu açıp tekrar dene.")
                        else vm.nearby(location.latitude, location.longitude)
                    }
                }
            })
        }
    }
    private fun locationOn(): Boolean {
        val manager = getSystemService(LocationManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.getProviders(true).any { it != LocationManager.PASSIVE_PROVIDER }
    }
    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission", "DEPRECATION")
    private suspend fun currentLocation(): Location? {
        val manager = getSystemService(LocationManager::class.java)
        if (!hasLocation()) return null
        return try {
            val precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val providers = manager.getProviders(true).filter {
                it != LocationManager.PASSIVE_PROVIDER && (precise || it == LocationManager.NETWORK_PROVIDER)
            }
            providers.mapNotNull { manager.getLastKnownLocation(it) }.filter {
                android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 120_000_000_000L
            }.minByOrNull { it.accuracy } ?: withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(location)
                        }
                        override fun onProviderDisabled(provider: String) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    if (providers.isEmpty()) continuation.resume(null)
                    else providers.forEach { manager.requestSingleUpdate(it, listener, mainLooper) }
                }
            }
        } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
    }
}
