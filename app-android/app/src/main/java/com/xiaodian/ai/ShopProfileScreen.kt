package com.xiaodian.ai

// 0.3.2 店铺资料表单页（设置 →「店铺资料」）。
// 店名、营业时间、地址、配送范围、平均出餐时长（分钟）、店铺公告；每项下方小字说明用途。
// 保存只提交改过的字段；成功/失败用 Snackbar 提示，失败原因也显示在按钮上方。
// 后端没有 /app/shop-profile（404）时显示「后端版本过旧，请先部署 v3.3」。
// 有未保存的改动时，返回会先确认。
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private const val MAX_PREP_MINUTES = 600

@Composable
fun ShopProfilePage(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val profile by vm.shopProfile.collectAsState()
    val loading by vm.shopProfileLoading.collectAsState()
    val loadError by vm.shopProfileError.collectAsState()
    val saving by vm.shopProfileSaving.collectAsState()

    LaunchedEffect(Unit) { vm.loadShopProfile() }

    // 表单内容；服务器资料到达（或保存后更新）时，用服务器值重新填一遍。
    var loadedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var shopName by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var delivery by rememberSaveable { mutableStateOf("") }
    var prep by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf("") }
    var formError by rememberSaveable { mutableStateOf("") }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    val p = profile
    LaunchedEffect(p) {
        if (p != null && p.toString() != loadedFrom) {
            loadedFrom = p.toString()
            shopName = p.shopName
            hours = p.hours
            address = p.address
            delivery = p.deliveryRange
            prep = p.prepMinutes?.toString() ?: ""
            notice = p.notice
        }
    }

    fun edited(): ShopProfile? {
        val t = prep.trim()
        val minutes = if (t.isEmpty()) null else t.toIntOrNull()
        if (t.isNotEmpty() && (minutes == null || minutes < 0 || minutes > MAX_PREP_MINUTES)) {
            formError = "平均出餐时长请填 0–$MAX_PREP_MINUTES 之间的整数（分钟），不填就留空"
            return null
        }
        formError = ""
        return ShopProfile(
            shopName = shopName.trim(),
            hours = hours.trim(),
            address = address.trim(),
            deliveryRange = delivery.trim(),
            prepMinutes = minutes,
            notice = notice.trim(),
        )
    }

    val dirty = p != null && run {
        val t = prep.trim()
        shopName.trim() != p.shopName || hours.trim() != p.hours || address.trim() != p.address ||
            delivery.trim() != p.deliveryRange || notice.trim() != p.notice ||
            t != (p.prepMinutes?.toString() ?: "")
    }

    val back: () -> Unit = { if (dirty && !saving) confirmLeave = true else onBack() }
    BackHandler(onBack = back)

    val canEdit = p != null && !saving

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back) { Text("‹ 返回设置") }
                    Text("店铺资料", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                }
                Text(
                    "AI 客服回答顾客问题时会用这些资料。哪项不填，顾客问到时就转给您回答。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        if (loadError.isNotBlank()) {
            item(key = "error") {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(loadError, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(
                            onClick = { vm.loadShopProfile() },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("重新读取") }
                    }
                }
            }
        }

        if (p == null && loading) {
            item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (p != null) {
            item(key = "basic") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProfileField(
                            label = "店名",
                            value = shopName,
                            onChange = { shopName = it },
                            hint = "客服自我介绍、顾客问「你们是哪家店」时用。",
                            enabled = canEdit,
                        )
                        ProfileField(
                            label = "营业时间",
                            value = hours,
                            onChange = { hours = it },
                            hint = "客服回答顾客「几点开门」「今天开不开」时用；不填则转给您回答。",
                            placeholder = "例如：每天 10:00–21:00，周一休息",
                            multiline = true,
                            enabled = canEdit,
                        )
                        ProfileField(
                            label = "地址",
                            value = address,
                            onChange = { address = it },
                            hint = "顾客问「店在哪」「到店自取怎么走」时用；不填则转给您回答。",
                            enabled = canEdit,
                        )
                        ProfileField(
                            label = "配送范围",
                            value = delivery,
                            onChange = { delivery = it },
                            hint = "顾客问「能不能送到某某地方」「送不送」时用；不填则转给您回答。",
                            placeholder = "例如：3 公里内免费送；XX 小区、XX 大厦可送",
                            multiline = true,
                            enabled = canEdit,
                        )
                        ProfileField(
                            label = "平均出餐时长（分钟）",
                            value = prep,
                            onChange = { v -> prep = v.filter { it.isDigit() }.take(3) },
                            hint = "顾客问「要等多久」时，客服按这个时长估计；不填则转给您回答。",
                            placeholder = "例如：15",
                            number = true,
                            enabled = canEdit,
                        )
                        ProfileField(
                            label = "店铺公告 / 补充说明",
                            value = notice,
                            onChange = { notice = it },
                            hint = "临时休息、节假日安排、优惠活动、忌口说明等；顾客问到相关问题时客服会告诉对方。",
                            multiline = true,
                            enabled = canEdit,
                        )
                    }
                }
            }
            item(key = "save") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (formError.isNotBlank()) {
                        Text(formError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { edited()?.let { vm.saveShopProfile(it) } },
                        enabled = canEdit && dirty,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (saving) "正在保存…" else if (dirty) "保存" else "没有改动") }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "放弃未保存的修改？",
            message = "店铺资料改了还没保存，返回后这些修改会丢掉。",
            confirmText = "放弃修改",
            onConfirm = onBack,
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    placeholder: String = "",
    multiline: Boolean = false,
    number: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
            singleLine = !multiline,
            minLines = if (multiline) 2 else 1,
            maxLines = if (multiline) 6 else 1,
            enabled = enabled,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
