package com.campusauth.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusauth.ffi.GuardianBridge
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    var logs by remember { mutableStateOf(listOf<GuardianBridge.LogEntry>()) }
    var autoRefresh by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    fun refresh() {
        logs = GuardianBridge.getRecentLogs()
    }

    // Auto-refresh logs every 2s
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            refresh()
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志", fontWeight = FontWeight.Bold) },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动刷新", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = autoRefresh,
                            onCheckedChange = { autoRefresh = it },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = logs.isEmpty(),
            transitionSpec = {
                fadeIn(spring()) togetherWith fadeOut(spring())
            },
            label = "logsContent"
        ) { isEmpty ->
            if (isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("暂无日志", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Auto-scroll to bottom on new entries
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        listState.animateScrollToItem(logs.size - 1)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(logs, key = { "${it.ts}-${it.text.hashCode()}" }) { entry ->
                        LogLineRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(entry: GuardianBridge.LogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val prefix = when (entry.level) {
        "WARN" -> "⚠"
        "ERROR" -> "✗"
        else -> "·"
    }
    val color = when (entry.level) {
        "WARN" -> MaterialTheme.colorScheme.error
        "ERROR" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = "$prefix ${timeFormat.format(Date(entry.ts * 1000))} ${entry.text}",
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        color = color,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
