package com.campusauth.ffi

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Kotlin bridge to the Rust guardian-core via UniFFI.
 *
 * UniFFI auto-generates the native JNI bindings (from guardian.udl).
 * This class provides idiomatic Kotlin wrappers + JSON parsing helpers.
 *
 * UniFFI function name mapping (snake_case → camelCase):
 *   guardian_init(config_ini: String) throws → guardianInit(configIni: String)
 *   guardian_config_ini() → guardianConfigIni(): String
 *   guardian_poll_event() → guardianPollEvent(): String?
 *   guardian_set_enabled(on: bool) → guardianSetEnabled(on: Boolean)
 *   etc.
 */
object GuardianBridge {

    private const val TAG = "GuardianBridge"
    private const val CONFIG_FILENAME = "config.ini"

    private var initialized = false

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        return try {
            val configIni = loadOrCreateConfig(context)
            // guardianInit throws GuardianError on failure
            uniffi.guardian_core_android.guardianInit(configIni)
            initialized = true
            // Provide local IP from ConnectivityManager
            provideLocalIp(context)
            Log.i(TAG, "Guardian initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize guardian", e)
            false
        }
    }

    fun isInitialized(): Boolean = initialized

    // ── Configuration ──────────────────────────────────────────────────────

    data class GuardianConfig(
        val authUrl: String,
        val checkUrl: String,
        val checkIntervalSec: Long,
        val studentId: String,
        val operator: String,
        val password: String,
        val fixedIp: String,
        val guardianEnabled: Boolean,
        val retryIntervalSec: Long,
        val maxRetries: Int,
    )

    fun getConfig(): GuardianConfig? {
        if (!initialized) return null
        return try {
            val ini = uniffi.guardian_core_android.guardianConfigIni()
            parseConfigIni(ini)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get config", e)
            null
        }
    }

    fun saveConfig(context: Context, config: GuardianConfig): Boolean {
        if (!initialized) return false
        return try {
            val ini = buildConfigIni(config)
            uniffi.guardian_core_android.guardianConfigApply(ini)
            // Also persist to disk
            getConfigFile(context).writeText(ini)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
            false
        }
    }

    // ── Authentication ─────────────────────────────────────────────────────

    data class AuthResult(
        val ok: Boolean,
        val status: String,
        val msg: String = "",
    )

    fun authNow(): AuthResult {
        if (!initialized) return AuthResult(false, "not_initialized")
        return try {
            val json = uniffi.guardian_core_android.guardianAuthNow()
            parseAuthResult(json)
        } catch (e: Exception) {
            AuthResult(false, "error", e.message ?: "Unknown error")
        }
    }

    // ── Guardian control ───────────────────────────────────────────────────

    fun setGuardianEnabled(on: Boolean) {
        // UniFFI binding now throws GuardianError if not initialized
        try {
            uniffi.guardian_core_android.guardianSetEnabled(on)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set guardian enabled", e)
        }
    }

    fun getGuardianState(): Int {
        if (!initialized) return -1
        return try {
            uniffi.guardian_core_android.guardianState()
        } catch (e: Exception) {
            -1
        }
    }

    fun setOperator(op: String): Boolean {
        return try {
            uniffi.guardian_core_android.guardianSetOperator(op)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set operator", e)
            false
        }
    }

    // ── Events ─────────────────────────────────────────────────────────────

    data class GuardianEvent(
        val type: String,      // "state", "net", "auth", "config"
        val state: Int = -1,
        val netStatus: String = "",
        val netDetail: String = "",
        val authOk: Boolean = false,
        val authDetail: String = "",
    )

    fun pollEvent(): GuardianEvent? {
        if (!initialized) return null
        return try {
            // UniFFI maps Option<String> → String? (nullable)
            val json = uniffi.guardian_core_android.guardianPollEvent() ?: return null
            if (json.isEmpty()) null else parseEvent(json)
        } catch (e: Exception) {
            null
        }
    }

    // ── Logs ───────────────────────────────────────────────────────────────

    data class LogEntry(val ts: Long, val level: String, val text: String)

    fun getRecentLogs(): List<LogEntry> {
        if (!initialized) return emptyList()
        return try {
            val json = uniffi.guardian_core_android.guardianRecentLogs()
            parseLogs(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Network ────────────────────────────────────────────────────────────

    /**
     * Provide the device's current IPv4 address to Rust from Android's ConnectivityManager.
     * Safe to call even before guardian_init (writes to a separate static in ipdetect.rs).
     */
    fun provideLocalIp(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProps = cm.getLinkProperties(cm.activeNetwork ?: return) ?: return
            val ipv4 = linkProps.linkAddresses
                .map { it.address }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
            if (ipv4 != null) {
                val ip = ipv4.hostAddress ?: return
                uniffi.guardian_core_android.guardianSetLocalIp(ip)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not provide local IP", e)
        }
    }

    // ── Network probe (triggers immediate recheck) ───────────────────────

    fun probeNetwork() {
        try {
            uniffi.guardian_core_android.guardianProbeNetwork()
        } catch (_: Exception) {}
    }

    // ── Server probe (does NOT require guardian_init) ──────────────────────

    fun probeServer(authUrl: String): String {
        return try {
            uniffi.guardian_core_android.guardianProbeServer(authUrl)
        } catch (e: Exception) {
            "{\"reachable\":false,\"issues\":[\"${e.message}\"]}"
        }
    }

    // ── JSON parsing helpers ───────────────────────────────────────────────

    private fun parseAuthResult(json: String): AuthResult {
        return try {
            val obj = JSONObject(json)
            AuthResult(
                ok = obj.optBoolean("ok", false),
                status = obj.optString("status", "unknown"),
                msg = obj.optString("msg", ""),
            )
        } catch (e: Exception) {
            AuthResult(false, "parse_error", json)
        }
    }

    private fun parseEvent(json: String): GuardianEvent? {
        return try {
            val obj = JSONObject(json)
            val type = obj.optString("type", "")
            when (type) {
                "state" -> GuardianEvent(type = "state", state = obj.optInt("state", 0))
                "net" -> GuardianEvent(
                    type = "net",
                    netStatus = obj.optString("status", ""),
                    netDetail = obj.optString("detail", ""),
                )
                "auth" -> GuardianEvent(
                    type = "auth",
                    authOk = obj.optBoolean("ok", false),
                    authDetail = obj.optString("detail", ""),
                )
                "config" -> GuardianEvent(type = "config")
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLogs(json: String): List<LogEntry> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                LogEntry(
                    ts = obj.optLong("ts", 0),
                    level = obj.optString("level", ""),
                    text = obj.optString("text", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Config INI helpers ─────────────────────────────────────────────────

    private fun loadOrCreateConfig(context: Context): String {
        val file = getConfigFile(context)
        return if (file.exists()) {
            file.readText()
        } else {
            val default = buildConfigIni(GuardianConfig(
                authUrl = "http://10.10.102.50:801/eportal/portal/login",
                checkUrl = "http://www.baidu.com",
                checkIntervalSec = 30,
                studentId = "",
                operator = "campus",
                password = "",
                fixedIp = "",
                guardianEnabled = false,
                retryIntervalSec = 10,
                maxRetries = 3,
            ))
            file.writeText(default)
            default
        }
    }

    private fun parseConfigIni(ini: String): GuardianConfig {
        val map = mutableMapOf<String, String>()
        var section = ""
        for (line in ini.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length - 1)
                continue
            }
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                map["$section.$key"] = value
            }
        }
        return GuardianConfig(
            authUrl = map["network.auth_url"] ?: "http://10.10.102.50:801/eportal/portal/login",
            checkUrl = map["network.check_url"] ?: "http://www.baidu.com",
            checkIntervalSec = map["network.check_interval"]?.toLongOrNull() ?: 30,
            studentId = map["account.student_id"] ?: "",
            operator = map["account.operator_type"] ?: "campus",
            password = map["account.user_password"] ?: "",
            fixedIp = map["account.fixed_ip"] ?: "",
            guardianEnabled = map["guardian.enabled"] == "1",
            retryIntervalSec = map["guardian.retry_interval"]?.toLongOrNull() ?: 10,
            maxRetries = map["guardian.max_retries"]?.toIntOrNull() ?: 3,
        )
    }

    private fun buildConfigIni(c: GuardianConfig): String = buildString {
        appendLine("# Campus Auth Guardian 配置文件 (UTF-8)")
        appendLine()
        appendLine("[network]")
        appendLine("auth_url = ${c.authUrl}")
        appendLine("check_url = ${c.checkUrl}")
        appendLine("check_interval = ${c.checkIntervalSec}")
        appendLine()
        appendLine("[account]")
        appendLine("student_id = ${c.studentId}")
        appendLine("operator_type = ${c.operator}")
        appendLine("user_password = ${c.password}")
        appendLine("fixed_ip = ${c.fixedIp}")
        appendLine()
        appendLine("[guardian]")
        appendLine("enabled = ${if (c.guardianEnabled) 1 else 0}")
        appendLine("retry_interval = ${c.retryIntervalSec}")
        appendLine("max_retries = ${c.maxRetries}")
    }

    private fun getConfigFile(context: Context): File {
        return File(context.filesDir, CONFIG_FILENAME)
    }
}
