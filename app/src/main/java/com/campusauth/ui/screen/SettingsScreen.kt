package com.campusauth.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.campusauth.ffi.GuardianBridge
import com.campusauth.update.UpdateChecker
import com.campusauth.util.detectOem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    var authUrl by remember { mutableStateOf("") }
    var checkUrl by remember { mutableStateOf("") }
    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fixedIp by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf("campus") }
    var checkInterval by remember { mutableStateOf("30") }
    var retryInterval by remember { mutableStateOf("10") }
    var maxRetries by remember { mutableStateOf("3") }
    var passwordVisible by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(false) }
    var updateCheckEnabled by remember { mutableStateOf(true) }
    var updateIntervalMs by remember { mutableStateOf(UpdateChecker.INTERVAL_6H) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.IO) { GuardianBridge.getConfig() }
        cfg?.let {
            authUrl = it.authUrl; checkUrl = it.checkUrl
            studentId = it.studentId; password = it.password
            fixedIp = it.fixedIp; operator = it.operator
            checkInterval = it.checkIntervalSec.toString()
            retryInterval = it.retryIntervalSec.toString()
            maxRetries = it.maxRetries.toString()
        }
        autoStart = withContext(Dispatchers.IO) {
            context.getSharedPreferences("guardian_prefs", 0).getBoolean("auto_start", false)
        }
        val updatePrefs = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("guardian_prefs", 0)
            Pair(
                prefs.getBoolean(UpdateChecker.PREF_ENABLED, true),
                prefs.getLong(UpdateChecker.PREF_INTERVAL_MS, UpdateChecker.INTERVAL_6H),
            )
        }
        updateCheckEnabled = updatePrefs.first
        updateIntervalMs = updatePrefs.second
        loaded = true
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置", fontWeight = FontWeight.Bold) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            AccountSection(
                studentId = studentId, onStudentIdChange = { studentId = it },
                password = password, onPasswordChange = { password = it },
                passwordVisible = passwordVisible, onPasswordVisibleToggle = { passwordVisible = !passwordVisible },
                operator = operator, onOperatorChange = { operator = it },
            )

            HorizontalDivider()

            NetworkSection(
                authUrl = authUrl, onAuthUrlChange = { authUrl = it },
                checkUrl = checkUrl, onCheckUrlChange = { checkUrl = it },
                fixedIp = fixedIp, onFixedIpChange = { fixedIp = it },
            )

            HorizontalDivider()

            GuardianParamsSection(
                checkInterval = checkInterval, onCheckIntervalChange = { checkInterval = it },
                retryInterval = retryInterval, onRetryIntervalChange = { retryInterval = it },
                maxRetries = maxRetries, onMaxRetriesChange = { maxRetries = it },
            )

            HorizontalDivider()

            AutoStartSection(autoStart = autoStart) { on ->
                autoStart = on
                context.getSharedPreferences("guardian_prefs", 0)
                    .edit().putBoolean("auto_start", on).apply()
            }

            HorizontalDivider()

            UpdateCheckSection(
                enabled = updateCheckEnabled,
                onEnabledChange = { on ->
                    updateCheckEnabled = on
                    context.getSharedPreferences("guardian_prefs", 0)
                        .edit().putBoolean(UpdateChecker.PREF_ENABLED, on).apply()
                },
                intervalMs = updateIntervalMs,
                onIntervalChange = { ms ->
                    updateIntervalMs = ms
                    context.getSharedPreferences("guardian_prefs", 0)
                        .edit().putLong(UpdateChecker.PREF_INTERVAL_MS, ms).apply()
                },
            )

            // MIUI auto-start guidance
            val isMiui = remember { try {
                Class.forName("miui.os.Build")
                true
            } catch (_: Exception) { false } }
            if (isMiui) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("⚠ MIUI 自启动设置", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "小米手机需要手动开启自启动权限：\n" +
                            "设置 → 应用 → 校园网认证守护 → 自启动 → 开启\n\n" +
                            "同时建议关闭该应用的电池优化，否则后台可能被系统杀掉。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        val existing = withContext(Dispatchers.IO) { GuardianBridge.getConfig() }
                        val cfg = GuardianBridge.GuardianConfig(
                            authUrl = authUrl, checkUrl = checkUrl,
                            checkIntervalSec = checkInterval.toLongOrNull() ?: 30,
                            studentId = studentId, operator = operator, password = password,
                            fixedIp = fixedIp,
                            guardianEnabled = existing?.guardianEnabled ?: false,
                            retryIntervalSec = retryInterval.toLongOrNull() ?: 10,
                            maxRetries = maxRetries.toIntOrNull() ?: 3,
                        )
                        val ok = withContext(Dispatchers.IO) { GuardianBridge.saveConfig(context, cfg) }
                        snackbarMessage = if (ok) "设置已保存" else "保存失败"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("保存设置") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSection(
    studentId: String, onStudentIdChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean, onPasswordVisibleToggle: () -> Unit,
    operator: String, onOperatorChange: (String) -> Unit,
) {
    Text("账号信息", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

    OutlinedTextField(
        value = studentId, onValueChange = onStudentIdChange,
        label = { Text("学号") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = password, onValueChange = onPasswordChange,
        label = { Text("密码") }, singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onPasswordVisibleToggle) {
                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )

    var expanded by remember { mutableStateOf(false) }
    val operators = listOf(
        "campus" to "校园网", "cmcc" to "中国移动",
        "unicom" to "中国联通", "telecom" to "中国电信",
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = operators.find { it.first == operator }?.second ?: operator,
            onValueChange = {}, readOnly = true, label = { Text("运营商") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            operators.forEach { (key, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onOperatorChange(key); expanded = false })
            }
        }
    }
}

@Composable
private fun NetworkSection(
    authUrl: String, onAuthUrlChange: (String) -> Unit,
    checkUrl: String, onCheckUrlChange: (String) -> Unit,
    fixedIp: String, onFixedIpChange: (String) -> Unit,
) {
    Text("网络", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

    OutlinedTextField(
        value = authUrl, onValueChange = onAuthUrlChange,
        label = { Text("认证服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = checkUrl, onValueChange = onCheckUrlChange,
        label = { Text("连通性检测地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = fixedIp, onValueChange = onFixedIpChange,
        label = { Text("固定 IP（留空自动检测）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GuardianParamsSection(
    checkInterval: String, onCheckIntervalChange: (String) -> Unit,
    retryInterval: String, onRetryIntervalChange: (String) -> Unit,
    maxRetries: String, onMaxRetriesChange: (String) -> Unit,
) {
    Text("守护参数", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = checkInterval,
            onValueChange = { onCheckIntervalChange(it.filter { c -> c.isDigit() }) },
            label = { Text("检测间隔(s)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = retryInterval,
            onValueChange = { onRetryIntervalChange(it.filter { c -> c.isDigit() }) },
            label = { Text("重试间隔(s)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = maxRetries,
        onValueChange = { onMaxRetriesChange(it.filter { c -> c.isDigit() }) },
        label = { Text("最大重试次数") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(0.5f),
    )
}

@Composable
private fun AutoStartSection(autoStart: Boolean, onToggle: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("开机自启", style = MaterialTheme.typography.titleSmall)
                Text("开机后自动启动守护", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = autoStart, onCheckedChange = onToggle)
        }

        // OEM-specific background keep-alive guidance
        val oem = remember { detectOem() }
        if (oem != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "后台运行设置（${oem.name}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        oem.steps,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateCheckSection(
    enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    intervalMs: Long, onIntervalChange: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("更新检查", style = MaterialTheme.typography.titleSmall)
                Text(
                    "启动时自动检查 GitHub Releases 新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        if (enabled) {
            Text(
                "检查频率", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = intervalMs == UpdateChecker.INTERVAL_EVERY_LAUNCH,
                    onClick = { onIntervalChange(UpdateChecker.INTERVAL_EVERY_LAUNCH) },
                    label = { Text("每次启动") },
                )
                FilterChip(
                    selected = intervalMs == UpdateChecker.INTERVAL_6H,
                    onClick = { onIntervalChange(UpdateChecker.INTERVAL_6H) },
                    label = { Text("每 6 小时") },
                )
                FilterChip(
                    selected = intervalMs == UpdateChecker.INTERVAL_24H,
                    onClick = { onIntervalChange(UpdateChecker.INTERVAL_24H) },
                    label = { Text("每 24 小时") },
                )
            }
        }
    }
}
