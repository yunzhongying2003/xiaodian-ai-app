package com.xiaodian.ai

// 老板模式「菜单」页：用量小卡片、手动添加、AI 草稿（勾选后批量加入）、
// 菜品列表（每行菜名 + 价格 + 在售开关，点击弹出编辑框：改价 / 删除，均需确认）。
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun MenuPage(
    items: List<MenuItem>,
    drafts: List<MenuItem>,
    usage: Usage?,
    busy: Set<String>,
    loadError: String,
    vm: AppViewModel,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }
    var newPrice by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }
    var aiDesc by remember { mutableStateOf("") }
    var selectedDrafts by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<MenuItem?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loadError.isNotBlank()) item(key = "err") { ErrorText(loadError) }

        // 用量小卡片
        item(key = "usage") { UsageCards(usage) }

        // 手动添加
        item(key = "add") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("添加菜品", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it; addError = "" },
                            label = { Text("菜名") },
                            singleLine = true,
                            modifier = Modifier.weight(1.6f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = newPrice,
                            onValueChange = { newPrice = it; addError = "" },
                            label = { Text("价格") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (addError.isNotBlank()) ErrorText(addError)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val err = vm.addMenuItem(newName, newPrice)
                            if (err == null) { newName = ""; newPrice = "" } else addError = err
                        },
                        enabled = "menu:add" !in busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("添加") }
                }
            }
        }

        // AI 草稿
        item(key = "ai") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("AI 生成菜单草稿", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = aiDesc,
                        onValueChange = { aiDesc = it },
                        label = { Text("如：川菜小店，家常菜，20-30 元") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { selectedDrafts = emptySet(); vm.generateDrafts(aiDesc) },
                        enabled = "menu:generate" !in busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if ("menu:generate" in busy) "生成中…" else "生成草稿") }
                }
            }
        }
        if (drafts.isNotEmpty()) {
            item(key = "drafts-head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("草稿 ${drafts.size} 道（未生效）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        selectedDrafts = if (selectedDrafts.size == drafts.size) emptySet()
                        else drafts.map { draftKey(it) }.toSet()
                    }) { Text(if (selectedDrafts.size == drafts.size) "全不选" else "全选") }
                    TextButton(onClick = { vm.clearDrafts(); selectedDrafts = emptySet() }) { Text("清空") }
                }
            }
            items(drafts, key = { "d-" + draftKey(it) }) { d ->
                val checked = draftKey(d) in selectedDrafts
                Row(
                    Modifier.fillMaxWidth().clickable {
                        selectedDrafts = if (checked) selectedDrafts - draftKey(d) else selectedDrafts + draftKey(d)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { c ->
                        selectedDrafts = if (c) selectedDrafts + draftKey(d) else selectedDrafts - draftKey(d)
                    })
                    Text(d.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${d.price} 元", style = MaterialTheme.typography.bodyLarge)
                }
            }
            item(key = "drafts-add") {
                Button(
                    onClick = {
                        val chosen = drafts.filter { draftKey(it) in selectedDrafts }
                        vm.addDrafts(chosen)
                        selectedDrafts = emptySet()
                    },
                    enabled = selectedDrafts.isNotEmpty() && "menu:drafts" !in busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("加入菜单（${selectedDrafts.size} 道）") }
            }
        }

        // 菜品列表
        item(key = "menu-head") {
            Text("菜单（${items.size} 道）", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        }
        if (items.isEmpty()) {
            item(key = "menu-empty") { Text("还没有菜品", style = MaterialTheme.typography.bodyLarge) }
        }
        items(items, key = { "m-" + it.id }) { item ->
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { editing = item }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (item.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${item.price} 元", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = item.available,
                        onCheckedChange = { vm.toggleAvailable(item) },
                        enabled = "menu:${item.id}" !in busy,
                    )
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { item ->
        MenuEditDialog(
            item = item,
            onDismiss = { editing = null },
            onChangePrice = { p -> vm.changePrice(item, p) },
            onDelete = { vm.deleteMenuItem(item) },
        )
    }
}

private fun draftKey(d: MenuItem): String = d.name + "|" + d.price

@Composable
private fun UsageCards(usage: Usage?) {
    if (usage == null) {
        Text("用量加载中…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("已完成金额", "¥" + formatPrice(usage.completedAmount), Modifier.weight(1f))
            StatCard("订单总数", "${usage.ordersTotal}", Modifier.weight(1f))
            StatCard("在售 / 菜品", "${usage.menuAvailable} / ${usage.menuTotal}", Modifier.weight(1f))
        }
        if (usage.ordersByStatus.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((status, count) in usage.ordersByStatus) {
                    Tag(
                        "${statusLabelOf(status)} $count",
                        statusContainerColor(status),
                        statusContentColor(status),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier.widthIn(min = 90.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun MenuEditDialog(
    item: MenuItem,
    onDismiss: () -> Unit,
    onChangePrice: (Double) -> Unit,
    onDelete: () -> Unit,
) {
    var priceText by remember(item.id) { mutableStateOf(item.price) }
    var error by remember { mutableStateOf("") }
    var confirmPrice by remember { mutableStateOf<Double?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column {
                Text("现价 ${item.price} 元 · ${if (item.available) "在售" else "已停售"}", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it; error = "" },
                    label = { Text("新价格（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (error.isNotBlank()) ErrorText(error)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删除这道菜", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = priceText.trim().toDoubleOrNull()
                if (p == null || p <= 0) {
                    error = "价格只能填数字，如 25"
                } else if (formatPrice(p) == (item.price.toDoubleOrNull()?.let { formatPrice(it) } ?: item.price)) {
                    error = "价格没有变化"
                } else {
                    confirmPrice = p
                }
            }) { Text("改价") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    confirmPrice?.let { p ->
        ConfirmDialog(
            title = "确认改价？",
            message = "${item.name}：${item.price} 元 → ${formatPrice(p)} 元",
            confirmText = "确认改价",
            onConfirm = { onChangePrice(p); onDismiss() },
            onDismiss = { confirmPrice = null },
            destructive = false,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "删除菜品？",
            message = "确定删除「${item.name}」吗？有订单的菜只能停售，不能删除。",
            confirmText = "确定删除",
            onConfirm = { onDelete(); onDismiss() },
            onDismiss = { confirmDelete = false },
        )
    }
}
