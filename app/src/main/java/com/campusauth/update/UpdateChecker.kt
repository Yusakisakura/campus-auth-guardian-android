package com.campusauth.update

import android.content.Context
import android.content.SharedPreferences
import com.campusauth.BuildConfig
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Remote release discovered on GitHub. */
data class UpdateInfo(
    val tag: String,        // "v1.2.0"
    val version: String,    // "1.2.0"
    val notes: String,      // plain text, may be empty
    val releaseUrl: String, // browser download page
)

data class UpdateUiState(
    val checking: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val upToDate: Boolean = false,
    val errorMessage: String? = null,
    val lastCheckAt: Long = 0L,
    /** Banner is hidden for the session once its tag has been dismissed. */
    val dismissedTag: String? = null,
) {
    /** Drives both the status banner and the About tab badge. */
    val showIndicator: Boolean
        get() = updateInfo != null && updateInfo.tag != dismissedTag
}

/**
 * Auto-update detection against GitHub Releases (plain github.com, no API).
 *
 * - [autoCheck]:   silent, honors the settings switch and check interval
 * - [manualCheck]: user-initiated, surfaces errors, ignores switch/interval
 * - Download always hands off to the browser via [UpdateInfo.releaseUrl].
 */
object UpdateChecker {

    private const val PREFS = "guardian_prefs"
    const val PREF_ENABLED = "update_check_enabled"
    const val PREF_INTERVAL_MS = "update_check_interval_ms"
    const val PREF_LAST_CHECK_AT = "update_last_check_at"

    const val INTERVAL_EVERY_LAUNCH = 0L
    const val INTERVAL_6H = 6 * 60 * 60 * 1000L
    const val INTERVAL_24H = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    // ── Entry points ─────────────────────────────────────────────────────

    /** Silent startup check. Honors the settings switch and the check interval. */
    fun autoCheck(context: Context) {
        val prefs = prefs(context)
        val lastAt = prefs.getLong(PREF_LAST_CHECK_AT, 0L)
        if (lastAt > 0) _state.update { it.copy(lastCheckAt = lastAt) }

        if (!prefs.getBoolean(PREF_ENABLED, true)) return
        val intervalMs = prefs.getLong(PREF_INTERVAL_MS, INTERVAL_6H)
        if (intervalMs > 0 && lastAt > 0 && System.currentTimeMillis() - lastAt < intervalMs) return

        runCheck(prefs, isManual = false)
    }

    /** Explicit user-initiated check. Ignores switch + interval, surfaces errors. */
    fun manualCheck(context: Context) {
        runCheck(prefs(context), isManual = true)
    }

    /** Hide banner + nav badge until this app session ends. */
    fun dismissBanner() {
        _state.update { it.copy(dismissedTag = it.updateInfo?.tag) }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun runCheck(prefs: SharedPreferences, isManual: Boolean) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, errorMessage = null) }

        scope.launch {
            try {
                val tag = fetchLatestTag()
                val now = System.currentTimeMillis()
                prefs.edit().putLong(PREF_LAST_CHECK_AT, now).apply()

                when {
                    tag == null -> _state.update {
                        it.copy(
                            checking = false,
                            updateInfo = null,
                            upToDate = false,
                            lastCheckAt = now,
                            errorMessage = if (isManual) "暂无发布版本" else null,
                        )
                    }
                    isRemoteNewer(tag, BuildConfig.VERSION_NAME) -> {
                        val info = UpdateInfo(
                            tag = tag,
                            version = tag.removePrefix("v").removePrefix("V"),
                            notes = fetchNotes(tag),
                            releaseUrl = releaseUrl(tag),
                        )
                        _state.update {
                            it.copy(
                                checking = false,
                                updateInfo = info,
                                upToDate = false,
                                lastCheckAt = now,
                                errorMessage = null,
                            )
                        }
                    }
                    else -> _state.update {
                        it.copy(
                            checking = false,
                            updateInfo = null,
                            upToDate = true,
                            lastCheckAt = now,
                            errorMessage = null,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        checking = false,
                        errorMessage = if (isManual) friendlyError(e) else null,
                    )
                }
            }
        }
    }

    /** Map low-level network exceptions to actionable Chinese messages. */
    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            e is UnknownHostException || msg.contains("resolve host", ignoreCase = true) ->
                "无法解析 github.com：请确认设备已联网（校园网需先完成认证），或当前网络屏蔽了 GitHub"
            e is SocketTimeoutException || msg.contains("timeout", ignoreCase = true) ->
                "连接 GitHub 超时，请稍后重试"
            e is SSLException ->
                "安全连接失败：网络可能拦截了 GitHub"
            e is ConnectException ->
                "无法连接 github.com：网络可能屏蔽了 GitHub"
            msg.contains("认证") -> msg
            else -> "检查失败: $msg"
        }
    }
}
