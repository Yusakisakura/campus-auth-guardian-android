package com.campusauth.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.campusauth.ffi.GuardianBridge
import com.campusauth.service.GuardianService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class WizardStep(
    val title: String,
    val icon: @Composable () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var authUrl by remember { mutableStateOf("10.10.102.50") }
    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf("campus") }
    var enableGuardian by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var verifyResult by remember { mutableStateOf<String?>(null) }

    val steps = listOf(
        WizardStep("服务器",  { Icon(Icons.Default.Language, null) }),
        WizardStep("账号",    { Icon(Icons.Default.Person, null) }),
        WizardStep("验证",    { Icon(Icons.Default.Security, null) }),
        WizardStep("完成",    { Icon(Icons.Default.CheckCircle, null) }),
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("首次配置向导", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { (step + 1).toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            // Step indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                steps.forEachIndexed { i, s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(
                            targetState = i < step,
                            transitionSpec = {
                                fadeIn(spring()) togetherWith fadeOut(spring())
                            },
                            label = "stepIcon$i"
                        ) { completed ->
                            Icon(
                                imageVector = if (completed) Icons.Default.CheckCircle
                                              else Icons.Default.Language,
                                contentDescription = null,
                                tint = if (i <= step) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            s.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (i <= step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Step content
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                },
                label = "wizardStep",
            ) { currentStep ->
                when (currentStep) {
                    // Step 0: Server
                    0 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("认证服务器", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("输入校园网认证服务器地址（通常是 10.x.x.x）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        OutlinedTextField(
                            value = authUrl,
                            onValueChange = { authUrl = it },
                            label = { Text("服务器地址") },
                            placeholder = { Text("10.10.102.50") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Button(
                            onClick = {
                                probing = true
                                probeResult = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        GuardianBridge.provideLocalIp(context)
                                        GuardianBridge.probeServer(
                                            "http://$authUrl:801/eportal/portal/login"
                                        )
                                    }
                                    probing = false
                                    probeResult = result
                                    probeOk = result.contains("\"reachable\":true")
                                }
                            },
                            enabled = !probing && authUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AnimatedVisibility(visible = probing, enter = fadeIn(), exit = fadeOut()) {
                                Row {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            AnimatedContent(targetState = probing, transitionSpec = {
                                fadeIn(spring()) togetherWith fadeOut(spring())
                            }, label = "probeBtn") { p ->
                                Text(if (p) "探测中…" else "探测服务器")
                            }
                        }

                        AnimatedVisibility(
                            visible = probeResult != null,
                            enter = fadeIn(spring()) + expandVertically(spring()),
                            exit = fadeOut(spring()) + shrinkVertically(spring()),
                        ) {
                            probeResult?.let {
                                Text(
                                    text = if (probeOk) "✓ 服务器可达" else "✗ 服务器不可达",
                                    color = if (probeOk) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick = { step = 1 },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("下一步") }
                    }

                    // Step 1: Account
                    1 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("账号信息", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("输入你的校园网账号",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        OutlinedTextField(
                            value = studentId,
                            onValueChange = { studentId = it },
                            label = { Text("学号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Operator
                        var expanded by remember { mutableStateOf(false) }
                        val operators = listOf(
                            "campus" to "校园网", "cmcc" to "中国移动",
                            "unicom" to "中国联通", "telecom" to "中国电信",
                        )
                        ExposedDropdownMenuBox(expanded = expanded,
                            onExpandedChange = { expanded = !expanded }) {
                            OutlinedTextField(
                                value = operators.find { it.first == operator }?.second ?: operator,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("运营商") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                operators.forEach { (key, name) ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = {
                                        operator = key; expanded = false
                                    })
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { step = 0 }, modifier = Modifier.weight(1f)) {
                                Text("上一步")
                            }
                            Button(
                                onClick = { step = 2 },
                                modifier = Modifier.weight(1f),
                                enabled = studentId.isNotBlank() && password.isNotBlank(),
                            ) { Text("下一步") }
                        }
                    }

                    // Step 2: Verify
                    2 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("验证连接", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("尝试使用你的账号进行一次认证",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Button(
                            onClick = {
                                verifying = true
                                verifyResult = null
                                scope.launch {
                                    // Save config first
                                    val cfg = GuardianBridge.GuardianConfig(
                                        authUrl = "http://$authUrl:801/eportal/portal/login",
                                        checkUrl = "http://www.baidu.com",
                                        checkIntervalSec = 30,
                                        studentId = studentId,
                                        operator = operator,
                                        password = password,
                                        fixedIp = "",
                                        guardianEnabled = false,
                                        retryIntervalSec = 10,
                                        maxRetries = 3,
                                    )
                                    GuardianBridge.saveConfig(context, cfg)
                                    GuardianBridge.provideLocalIp(context)
                                    val result = withContext(Dispatchers.IO) {
                                        GuardianBridge.authNow()
                                    }
                                    verifying = false
                                    verifyResult = if (result.ok) "认证成功!" else "认证失败: ${result.msg}"
                                }
                            },
                            enabled = !verifying,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            AnimatedVisibility(visible = verifying, enter = fadeIn(), exit = fadeOut()) {
                                Row {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            AnimatedContent(targetState = verifying, transitionSpec = {
                                fadeIn(spring()) togetherWith fadeOut(spring())
                            }, label = "verifyBtn") { v ->
                                Text(if (v) "正在验证…" else "测试认证")
                            }
                        }

                        AnimatedVisibility(
                            visible = verifyResult != null,
                            enter = fadeIn(spring()) + expandVertically(spring()),
                            exit = fadeOut(spring()) + shrinkVertically(spring()),
                        ) {
                            verifyResult?.let {
                                Text(it, style = MaterialTheme.typography.bodyLarge,
                                    color = if (it.startsWith("认证成功")) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error)
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { step = 1 }, modifier = Modifier.weight(1f)) {
                                Text("上一步")
                            }
                            Button(onClick = { step = 3 }, modifier = Modifier.weight(1f)) {
                                Text("跳过验证")
                            }
                        }
                    }

                    // Step 3: Done
                    3 -> Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(32.dp))
                        Icon(
                            Icons.Default.CheckCircle, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("配置完成！", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)

                        // Guardian toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("启用守护模式", style = MaterialTheme.typography.titleSmall)
                                Text("断网时自动重连",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = enableGuardian, onCheckedChange = { enableGuardian = it })
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick = {
                                // Save final config with guardian setting
                                val cfg = GuardianBridge.GuardianConfig(
                                    authUrl = "http://$authUrl:801/eportal/portal/login",
                                    checkUrl = "http://www.baidu.com",
                                    checkIntervalSec = 30,
                                    studentId = studentId,
                                    operator = operator,
                                    password = password,
                                    fixedIp = "",
                                    guardianEnabled = enableGuardian,
                                    retryIntervalSec = 10,
                                    maxRetries = 3,
                                )
                                GuardianBridge.saveConfig(context, cfg)
                                if (enableGuardian) {
                                    context.getSharedPreferences("guardian_prefs", 0)
                                        .edit().putBoolean("auto_start", true).apply()
                                    GuardianService.start(context)
                                }
                                onComplete()
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text("开始使用")
                        }
                    }
                }
            }
        }
    }
}
