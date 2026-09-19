package com.campusauth.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.campusauth.ffi.GuardianBridge
import com.campusauth.service.EventPoller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatusUiState(
    val netStatus: String = "unknown",
    val netDetail: String = "",
    val authStatus: String = "unknown",
    val authDetail: String = "",
    // 0=stopped, 1=monitoring, 2=authenticating, -1=unknown
    val guardianState: Int = -1,
    // user intent only, not shown in UI
    val guardianEnabled: Boolean = false,
    val isAuthing: Boolean = false,
    val operator: String = "campus",
    val snackbarMessage: String? = null,
)

class StatusViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    private var disconnectedSince = 0L
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        EventPoller.start(viewModelScope)

        viewModelScope.launch {
            // Read config for operator and enabled state
            withContext(Dispatchers.IO) {
                GuardianBridge.getConfig()?.let { cfg ->
                    val initState = if (cfg.guardianEnabled) 1 else 0
                    _state.update { s ->
                        s.copy(
                            guardianEnabled = cfg.guardianEnabled,
                            guardianState = initState,
                            operator = cfg.operator,
                        )
                    }
                }
                _state.update { s -> s.copy(guardianState = GuardianBridge.getGuardianState()) }
                GuardianBridge.provideLocalIp(context)
                // Trigger immediate probe so we get a fast "net" event
                GuardianBridge.probeNetwork()
            }

            // Collect from shared event flow (replay=1 gives last event immediately)
            EventPoller.events.collect { event ->
                processEvent(event)
            }
        }
    }

    private fun processEvent(event: GuardianBridge.GuardianEvent) {
        when (event.type) {
            "net" -> {
                val current = _state.value.netStatus
                val next = event.netStatus
                val now = System.currentTimeMillis()

                when {
                    next == "connected" || next == "captive" || next == "non_campus" -> {
                        disconnectedSince = 0L
                        _state.update { it.copy(netStatus = next, netDetail = event.netDetail) }
                    }
                    next == "disconnected" || next == "dns_pending" -> {
                        if (current == "connected" || current == "captive" || current == "non_campus") {
                            if (disconnectedSince == 0L) {
                                disconnectedSince = now
                            } else if (now - disconnectedSince > 8000) {
                                _state.update { it.copy(netStatus = next, netDetail = event.netDetail) }
                            }
                        } else {
                            _state.update { it.copy(netStatus = next, netDetail = event.netDetail) }
                        }
                    }
                }

                _state.update { s ->
                    when (s.netStatus) {
                        "connected" -> s.copy(authStatus = "authenticated", authDetail = "ePortal session active")
                        "captive" -> s.copy(authStatus = "not_authenticated", authDetail = "Portal detected, needs login")
                        "dns_pending" -> s.copy(authStatus = "in_progress", authDetail = "Auth activating, DNS not ready")
                        "non_campus" -> s.copy(authStatus = "not_applicable", authDetail = "Not on campus network")
                        else -> s.copy(authStatus = "unknown", authDetail = "Check network connection first")
                    }
                }
            }
            "auth" -> {
                _state.update {
                    if (event.authOk) {
                        it.copy(
                            authStatus = "authenticated",
                            authDetail = event.authDetail,
                            netStatus = "connected",
                            netDetail = "",
                            isAuthing = false,
                            snackbarMessage = "认证成功",
                        )
                    } else {
                        it.copy(
                            authStatus = "failed",
                            authDetail = event.authDetail,
                            isAuthing = false,
                            snackbarMessage = "认证失败: ${event.authDetail}",
                        )
                    }
                }
            }
            "state" -> {
                // "state" event is the single source of truth for guardian running state
                _state.update { it.copy(guardianState = event.state) }
            }
        }
    }

    fun authNow(context: Context) {
        _state.update { it.copy(isAuthing = true) }
        viewModelScope.launch {
            GuardianBridge.provideLocalIp(context)
            val result = withContext(Dispatchers.IO) { GuardianBridge.authNow() }
            _state.update {
                it.copy(
                    isAuthing = false,
                    snackbarMessage = if (result.ok) "认证成功" else "认证失败: ${result.msg}"
                )
            }
            withContext(Dispatchers.IO) { GuardianBridge.probeNetwork() }
        }
    }

    fun setGuardianEnabled(on: Boolean) {
        // Save user intent; "state" event from Rust will confirm the actual running state
        _state.update { it.copy(guardianEnabled = on) }
        viewModelScope.launch(Dispatchers.IO) {
            GuardianBridge.setGuardianEnabled(on)
        }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }
}