package com.campusauth

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.campusauth.ffi.GuardianBridge
import com.campusauth.update.UpdateChecker
import com.campusauth.ui.navigation.AppNavigation
import com.campusauth.ui.theme.CampusAuthTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* Notification permission result — non-critical */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Rust guardian core
        GuardianBridge.initialize(applicationContext)

        // Silent update check (honors settings switch + interval)
        UpdateChecker.autoCheck(applicationContext)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Request battery optimization exemption for reliable background monitoring
        requestBatteryOptimizationExemption()

        setContent {
            CampusAuthTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val needsWizard = remember {
                        GuardianBridge.getConfig()?.studentId.isNullOrEmpty()
                    }
                    AppNavigation(needsWizard = needsWizard)
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val prefs = getSharedPreferences("guardian_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_dialog_shown", false)) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_dialog_shown", true).apply()
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Some devices don't support this
            }
        }
    }
}
