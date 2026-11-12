package com.uzaylikafasi.marasulasim

import android.app.Application
import com.uzaylikafasi.marasulasim.data.DiskCache
import com.uzaylikafasi.marasulasim.data.LocalStore
import com.uzaylikafasi.marasulasim.data.ModelStore
import com.uzaylikafasi.marasulasim.data.ModelUpdater
import com.uzaylikafasi.marasulasim.data.OfflineConfig
import com.uzaylikafasi.marasulasim.data.OfflineMode
import com.uzaylikafasi.marasulasim.data.OfflineProvider
import com.uzaylikafasi.marasulasim.data.TransportApi
import com.uzaylikafasi.marasulasim.data.TransportRepository
import com.uzaylikafasi.marasulasim.data.TrafficLights
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class TransportApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Depodaki ayarın bellekteki kopyası: repository ve arka plan hizmetleri okur. */
    @Volatile var offlineConfig = OfflineConfig()
        private set
    val store by lazy { LocalStore(this) }
    val lights by lazy { TrafficLights(this) }
    val api by lazy { TransportApi() }
    val modelStore by lazy {
        ModelStore(File(filesDir, "offline"), { assets.open("offline-model.json.gz") }, { assets.open("offline-model-meta.json") })
    }
    val modelUpdater by lazy { ModelUpdater(api, modelStore) }
    val repository by lazy { TransportRepository(api, DiskCache(this), OfflineProvider({ modelStore.model() }), { offlineConfig }) }
    override fun onCreate() {
        super.onCreate()
        Reminders.channel(this)
        Reminders.list(this).forEach { Reminders.schedule(this, it) }
        appScope.launch {
            store.settings.map { it.offlineConfig }.distinctUntilChanged().collect {
                offlineConfig = it
                // Kapalıyken model indirmesi de çalışmaz
                if (it.mode == OfflineMode.OFF) OfflineModelWorker.cancel(this@TransportApplication) else OfflineModelWorker.schedule(this@TransportApplication)
            }
        }
    }
}
