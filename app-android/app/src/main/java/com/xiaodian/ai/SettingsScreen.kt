package com.xiaodian.ai

// 设置页：当前用户、本机登录有效期、退出登录；新单提醒和语音播报开关；
// 切换模式、退出 App。服务器地址在登录页修改（这里只显示）。
// 「使用服务器人声」（默认开）；「保持后台提醒」（电池优化白名单）和「自启动设置」（国产系统）。
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsPage(
    settings: AppSettings,
    session: Session?,
    onSwitchToKitchen: () -> Unit,
    onAlertsChanged: () -> Unit,
    onLogout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var alerts by remember { mutableStateOf(settings.alertsEnabled) }
    var tts by remember { mutableStateOf(settings.ttsEnabled) }
    var serverTts by remember { mutableStateOf(settings.serverTtsEnabled) }
    var batteryOk by remember { mutableStateOf(BackgroundGuide.isIgnoringBatteryOptimizations(context)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryOk = BackgroundGuide.isIgnoringBatteryOptimizations(context)
    }
    val launchBattery: () -> Unit = {
        for (i: Intent in BackgroundGuide.batteryIntents(context)) {
            val ok = try { batteryLauncher.launch(i); true } catch (_: Exception) { false }
            if (ok) break
        }
    }
    var confirmLogout by remember { mutableStateOf(false) }
    val versionText = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "版本 ${info.versionName}（${PackageInfoCompat.getLongVersionCode(info)}）"
        } catch (_: Exception) {
            ""
        }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "account") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("账号", style = MaterialTheme.typography.titleMedium)
                    Text("当前用户 ${session?.username?.ifBlank { "—" } ?: "—"}", style = MaterialTheme.typography.bodyLarge)
                    Text("本机登录有效期至 ${formatExpiry(session?.expiresAt ?: "")}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "服务器 ${settings.serverUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { confirmLogout = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item(key = "alerts") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("提醒", style = MaterialTheme.typography.titleMedium)
                    SwitchRow("新单 / 待人工提醒", alerts) {
                        alerts = it
                        settings.alertsEnabled = it
                        onAlertsChanged()
                    }
                    SwitchRow("语音播报", tts, enabled = alerts) {
                        tts = it
                        settings.ttsEnabled = it
                    }
                    SwitchRow("使用服务器人声", serverTts, enabled = alerts && tts) {
                        serverTts = it
                        settings.serverTtsEnabled = it
                    }
                    Text(
                        "提醒开着时，退出 App 后仍在后台监听新单。服务器人声不可用时自动改用手机语音。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "background") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("后台保活", style = MaterialTheme.typography.titleMedium)
                    if (batteryOk) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("保持后台提醒：已开启") }
                    } else {
                        Button(
                            onClick = launchBattery,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("保持后台提醒") }
                    }
                    OutlinedButton(
                        onClick = { BackgroundGuide.openAutoStart(context) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("自启动设置（小米 / 华为 / OPPO / vivo）") }
                    Text(
                        "手机省电策略可能在锁屏后关掉提醒。点「保持后台提醒」选「允许」；国产手机再到「自启动设置」里打开小店AI助手。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "app") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSwitchToKitchen,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("切换到后厨模式") }
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("退出 App") }
                    if (versionText.isNotBlank()) {
                        Text(versionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录？",
            message = "退出后本机不再接收新单提醒，需要重新输入用户名和密码。",
            confirmText = "退出登录",
            onConfirm = onLogout,
            onDismiss = { confirmLogout = false },
        )
    }
}

/** expires_at 按 ISO 解析成本地时间「yyyy-MM-dd HH:mm」，解析失败显示原文。 */
private fun formatExpiry(raw: String): String {
    if (raw.isBlank()) return "—"
    val ms = TimeFmt.parseMillis(raw) ?: return raw
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
