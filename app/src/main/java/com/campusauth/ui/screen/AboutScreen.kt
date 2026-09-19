package com.campusauth.ui.screen

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusauth.BuildConfig
import com.campusauth.R

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── App name ────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "校园网认证守护",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Campus Auth Guardian",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Version ─────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "v${BuildConfig.VERSION_NAME}  (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )

        // ── Related repos ───────────────────────────────────────────────
        Spacer(modifier = Modifier.height(24.dp))
        InfoSection("相关仓库") {
            RepoRow(
                name = "campus-auth-guardian-android",
                author = "Yusakisakura",
                avatarRes = R.drawable.avatar_yusakisakura,
                onClick = { uriHandler.openUri("https://github.com/Yusakisakura/campus-auth-guardian-android") },
            )
            RepoRow(
                name = "campus-auth-guardian-openwrt",
                author = "Yusakisakura",
                avatarRes = R.drawable.avatar_yusakisakura,
                onClick = { uriHandler.openUri("https://github.com/Yusakisakura/campus-auth-guardian-openwrt") },
            )
            RepoRow(
                name = "campus-auth-guardian",
                author = "NekoMirra",
                avatarRes = R.drawable.avatar_nekomirra,
                onClick = { uriHandler.openUri("https://github.com/NekoMirra/campus-auth-guardian") },
            )
        }

        // ── Build info ──────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(16.dp))
        InfoSection("构建信息") {
            InfoRow("提交记录", BuildConfig.GIT_HASH)
            if (BuildConfig.GIT_DATE.isNotEmpty()) InfoRow("提交时间", BuildConfig.GIT_DATE)
            if (BuildConfig.GIT_BRANCH.isNotEmpty()) InfoRow("分支", BuildConfig.GIT_BRANCH)
            if (BuildConfig.BUILD_TIME.isNotEmpty()) InfoRow("构建时间", BuildConfig.BUILD_TIME)
            InfoRow("包名", BuildConfig.APPLICATION_ID)
        }

        Spacer(modifier = Modifier.height(16.dp))
        InfoSection("设备信息") {
            InfoRow("系统版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        }

        Spacer(modifier = Modifier.height(16.dp))
        InfoSection("功能说明") {
            Text(
                text = "校园网 ePortal 自动认证与网络守护\n" +
                       "支持多运营商 · 后台常驻 · 开机自启\n" +
                       "Rust 内核 + Jetpack Compose (Material 3)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ── Footer ──────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "开源项目 · MIT License",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Reusable composables ───────────────────────────────────────────────

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun RepoRow(name: String, author: String, avatarRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(avatarRes),
            contentDescription = author,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}