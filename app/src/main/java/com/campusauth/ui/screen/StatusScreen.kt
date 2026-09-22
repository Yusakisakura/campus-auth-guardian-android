package com.campusauth.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campusauth.service.GuardianService
import com.campusauth.ui.component.StatusCard
import com.campusauth.ui.component.UpdateBanner
import com.campusauth.ui.component.StatusInfo
import com.campusauth.ui.component.StatusLevel
import com.campusauth.update.UpdateChecker
import com.campusauth.viewmodel.StatusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(vm: StatusViewModel = viewModel()) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val state by vm.state.collectAsState()
    val updateState by UpdateChecker.state.collectAsState()

    // Initialize once
    LaunchedEffect(Unit) { vm.initialize(context) }

    // Show snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("校园网认证守护", fontWeight = FontWeight.Bold) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Update available banner ──────────────────────────────────
            val updateInfo = updateState.updateInfo
            if (updateInfo != null && updateState.showIndicator) {
                UpdateBanner(
                    info = updateInfo,
                    onOpen = { uriHandler.openUri(updateInfo.releaseUrl) },
                    onDismiss = { UpdateChecker.dismissBanner() },
                )
            }

            // ── Summary banner ─────────────────────────────────────────
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = state.netStatus == "connected" || state.netStatus == "non_campus",
                        transitionSpec = { fadeIn(spring()) togetherWith fadeOut(spring()) },
                        label = "wifiIcon"
                    ) { connected ->
                        Icon(
                            imageVector = if (connected) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        AnimatedContent(
                            targetState = state.netStatus,
                            transitionSpec = { fadeIn(spring()) togetherWith fadeOut(spring()) },
                            label = "bannerTitle"
                        ) { status ->
                            Text(
                                text = when (status) {
                                    "connected" -> "网络已连接"
                                    "captive" -> "需要认证"
                                    "dns_pending" -> "认证生效中"
                                    "non_campus" -> "非校园网络"
                                    else -> "网络不可达"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        AnimatedContent(
                            targetState = state.netStatus,
                            transitionSpec = { fadeIn(spring()) togetherWith fadeOut(spring()) },
                            label = "bannerSubtitle"
                        ) { status ->
                            Text(
                                text = when (status) {
                                    "connected" -> "认证有效，网络畅通"
                                    "captive" -> "检测到认证门户，需要 Portal 登录"
                                    "dns_pending" -> "DNS 尚未就绪，正在自动复检…"
                                    "non_campus" -> "当前网络非校园网，无需认证"
                                    else -> "无法连接检测服务器"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // ── Status cards ───────────────────────────────────────────
            StatusCard(
                title = "Internet 连通性",
                status = when (state.netStatus) {
                    "connected" -> StatusInfo(StatusLevel.Good, "已连通", "可直接访问互联网")
                    "captive" -> StatusInfo(StatusLevel.Warning, "已连校园网·未认证",
                        if (state.netDetail.isNotEmpty()) "门户: ${state.netDetail.take(60)}" else "点击「立即认证」完成登录")
                    "dns_pending" -> StatusInfo(StatusLevel.Warning, "认证生效中", "DNS 尚未就绪，正在自动复检…")
                    "non_campus" -> StatusInfo(StatusLevel.Info, "已连通·非校园网", "当前网络非校园网，无需认证")
                    else -> StatusInfo(StatusLevel.Error, "不可达", "无法连接检测服务器")
                },
            )

            StatusCard(
                title = "Portal 认证",
                status = when (state.authStatus) {
                    "authenticated" -> StatusInfo(StatusLevel.Good, "已认证", "ePortal 会话有效")
                    "not_authenticated" -> StatusInfo(StatusLevel.Error, "未认证", "点击「立即认证」完成登录")
                    "in_progress" -> StatusInfo(StatusLevel.Warning, "生效中", "认证已通过，网络正在生效")
                    "failed" -> StatusInfo(StatusLevel.Error, "认证失败", state.authDetail)
                    "not_applicable" -> StatusInfo(StatusLevel.Info, "不适用", "非校园网，无需 Portal 认证")
                    else -> StatusInfo(StatusLevel.Unknown, "无法判断", "请先检查网络连接")
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Guardian toggle ────────────────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("守护模式", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = when (state.guardianState) {
                                1 -> "监控中，断网将自动重连"
                                2 -> "正在认证…"
                                else -> "未启动"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.guardianState > 0,
                        onCheckedChange = { on ->
                            vm.setGuardianEnabled(on)
                            if (on) GuardianService.start(context) else GuardianService.stop(context)
                        }
                    )
                }
            }

            // ── Auth button ────────────────────────────────────────────
            Button(
                onClick = { vm.authNow(context) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.isAuthing,
            ) {
                AnimatedVisibility(visible = state.isAuthing, enter = fadeIn(), exit = fadeOut()) {
                    Row {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                AnimatedContent(targetState = state.isAuthing, transitionSpec = {
                    fadeIn(spring()) togetherWith fadeOut(spring())
                }, label = "authBtn") { authing -> Text(if (authing) "正在认证…" else "立即认证") }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
