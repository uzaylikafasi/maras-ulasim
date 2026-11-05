package com.uzaylikafasi.marasulasim

import android.app.Application
import com.uzaylikafasi.marasulasim.data.DiskCache
import com.uzaylikafasi.marasulasim.data.LocalStore
import com.uzaylikafasi.marasulasim.data.TransportApi
import com.uzaylikafasi.marasulasim.data.TransportRepository
import com.uzaylikafasi.marasulasim.data.TrafficLights

class TransportApplication : Application() {
    val store by lazy { LocalStore(this) }
    val lights by lazy { TrafficLights(this) }
    val repository by lazy { TransportRepository(TransportApi(), DiskCache(this)) }
    override fun onCreate() {
        super.onCreate()
        Reminders.channel(this)
        Reminders.list(this).forEach { Reminders.schedule(this, it) }
    }
}
