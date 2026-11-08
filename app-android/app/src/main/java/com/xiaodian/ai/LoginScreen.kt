package com.xiaodian.ai

// 登录页：首次启动、没有 token、或 token 失效（401）时显示。
// 服务器地址（默认 https://kf.golook.eu.org，可修改）、用户名、密码、「登录」按钮，错误显示在页面上。
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    initialServer: String,
    initialUsername: String,
    busy: Boolean,
    error: String,
    notice: String,
    onLogin: (server: String, username: String, password: String) -> Unit,
    onEdited: () -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(initialServer.ifBlank { AppSettings.DEFAULT_SERVER }) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    fun submit() { if (!busy) onLogin(server, username, password) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 登录页内容固定、很短，整页滚动只为小屏 + 键盘弹出时不被挡住（不含 LazyColumn，不会嵌套滚动）。
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("小店AI助手", style = MaterialTheme.typography.headlineSmall)
                Text("请登录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (notice.isNotBlank()) {
                    Text(notice, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; onEdited() },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; onEdited() },
                    label = { Text("用户名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onEdited() },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "隐藏密码" else "显示密码")
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { submit() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(if (busy) "登录中…" else "登录", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}
