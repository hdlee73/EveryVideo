package com.everyvideo.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.everyvideo.player.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORD, getString(R.string.record_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_RECORD = "record"
        lateinit var instance: App
            private set
    }
}
