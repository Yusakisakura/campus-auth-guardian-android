package com.campusauth.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.campusauth.CampusAuthApp
import com.campusauth.MainActivity
import com.campusauth.R
import com.campusauth.ffi.GuardianBridge
import kotlinx.coroutines.*

class GuardianService : Service() {

    companion object {
        private const val TAG = "GuardianService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.campusauth.STOP_GUARDIAN"
        private const val ACTION_AUTH_NOW = "com.campusauth.AUTH_NOW"

        fun start(context: Context) {
            val intent = Intent(context, GuardianService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardianService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var lastStatus: String = "未连接"
    @Volatile private var authenticated: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Guardian service created")

        if (!GuardianBridge.isInitialized()) {
            GuardianBridge.initialize(applicationContext)
        }

        // Keep CPU alive when screen off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "campusauth:guardian").apply {
            acquire()
        }

        // Keep WiFi alive
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "campusauth:wifi").apply {
            acquire()
        }

        Log.i(TAG, "WakeLock + WifiLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                GuardianBridge.setGuardianEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_AUTH_NOW -> {
                scope.launch {
                    GuardianBridge.provideLocalIp(applicationContext)
                    GuardianBridge.authNow()
                }
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(lastStatus))

        GuardianBridge.setGuardianEnabled(true)

        // Ensure shared EventPoller is running, then collect from it
        EventPoller.start(scope)
        startEventCollecting()

        // Trigger immediate network probe so notification updates quickly
        GuardianBridge.probeNetwork()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Guardian service destroyed")
        GuardianBridge.setGuardianEnabled(false)
        collectJob?.cancel()
        scope.cancel()

        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }

        super.onDestroy()
    }

    private fun startEventCollecting() {
        collectJob?.cancel()
        collectJob = scope.launch {
            EventPoller.events.collect { event ->
                when (event.type) {
                    "net" -> {
                        // "connected" means the external check URL succeeded — portal must be passed
                        if (event.netStatus == "connected") authenticated = true
                        lastStatus = formatStatus(event.netStatus, authenticated)
                        updateNotification(lastStatus)
                    }
                    "auth" -> {
                        if (event.authOk) authenticated = true
                        lastStatus = formatStatus("connected", authenticated)
                        updateNotification(lastStatus)
                    }
                }
            }
        }
    }

    private fun formatStatus(status: String, authed: Boolean): String = when (status) {
        "connected" -> if (authed) "已认证" else "已连接，未认证"
        "captive"   -> "已连接，未认证"
        "non_campus" -> "非校园网"
        else        -> "未连接"
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, GuardianService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val authIntent = PendingIntent.getForegroundService(
            this, 2,
            Intent(this, GuardianService::class.java).apply { action = ACTION_AUTH_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CampusAuthApp.CHANNEL_GUARDIAN)
            .setContentTitle("校园网守护")
            .setContentText(text)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.ic_notification_guardian)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "立即认证", authIntent)
            .addAction(0, "停止守护", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}