package com.xiaodian.ai

// 设置页：后端地址、设备密钥 / 操作 PIN（密码样式，等登录方案定了再改）、
// 新单提醒和语音播报开关、模式切换、退出 App。
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat

@Composable
fun SettingsPage(
    settings: AppSettings,
    onSwitchToKitchen: () -> Unit,
    onAlertsChanged: () -> Unit,
    onServerChanged: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var deviceKey by remember { mutableStateOf(settings.deviceKey) }
    var devicePin by remember { mutableStateOf(settings.devicePin) }
    var showSecrets by remember { mutableStateOf(false) }
    var alerts by remember { mutableStateOf(settings.alertsEnabled) }
    var tts by remember { mutableStateOf(settings.ttsEnabled) }
    val versionText = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "版本 ${info.versionName}（${PackageInfoCompat.getLongVersionCode(info)}）"
        } catch (_: Exception) {
            ""
        }
    }
    val secretTransform: VisualTransformation =
        if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "conn") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连接", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            settings.serverUrl = it
                        },
                        label = { Text("后端地址") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = deviceKey,
                        onValueChange = {
                            deviceKey = it
                            settings.deviceKey = it
                        },
                        label = { Text("设备密钥") },
                        singleLine = true,
                        visualTransformation = secretTransform,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = devicePin,
                        onValueChange = {
                            devicePin = it
                            settings.devicePin = it
                        },
                        label = { Text("操作 PIN") },
                        singleLine = true,
                        visualTransformation = secretTransform,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showSecrets = !showSecrets }) {
                            Text(if (showSecrets) "隐藏密钥" else "显示密钥")
                        }
                        TextButton(onClick = onServerChanged) { Text("保存并重新连接") }
                    }
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
                    Text(
                        "提醒开着时，退出 App 后仍在后台监听新单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "app") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSwitchToKitchen,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("切换到后厨模式") }
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("退出 App", color = MaterialTheme.colorScheme.error) }
                    if (versionText.isNotBlank()) {
                        Text(versionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
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
