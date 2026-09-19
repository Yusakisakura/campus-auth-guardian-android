package com.campusauth

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CampusAuthApp : Application() {

    companion object {
        const val CHANNEL_GUARDIAN = "guardian_service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_GUARDIAN,
                "校园网守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "守护服务运行状态及认证结果通知"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC  // 锁屏可见
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
