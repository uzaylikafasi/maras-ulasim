package com.uzaylikafasi.marasulasim

import android.content.Context
import androidx.work.*
import com.uzaylikafasi.marasulasim.data.OfflineMode
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Günde bir çevrimdışı model kontrolü. Sunucu uçları yoksa (404) sessizce eski/gömülü modeli korur. */
class OfflineModelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TransportApplication
        val settings = app.store.settings.first()
        if (settings.offlineMode == OfflineMode.OFF) return Result.success()
        runCatching { app.modelUpdater.run(settings.server, settings.apiKey) }
        return Result.success()
    }
    companion object {
        private const val NAME = "offline-model-update"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflineModelWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
