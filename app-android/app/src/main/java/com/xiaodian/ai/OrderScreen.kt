package com.xiaodian.ai

// 后厨看板 + 老板订单页。
// 后厨：按状态分三组（待制作 / 制作中 / 待取·配送），大字取餐号、已等时长
// （超过 15 分钟标红）、一个大主按钮推进到下一状态，其它状态在「更多」里。
// 待付款的单在后厨灰显并注明「等待收款」，没有任何按钮；
// 确认收款只在老板模式的订单页（actor=boss）。
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val LATE_MINUTES = 15L

private data class KitchenGroup(val title: String, val statuses: Set<String>)

private val KITCHEN_GROUPS = listOf(
    KitchenGroup("待制作", setOf("ordered")),
    KitchenGroup("制作中", setOf("making")),
    KitchenGroup("待取 / 配送", setOf("ready", "awaiting_pickup", "delivering")),
)

/** 每 30 秒走一次的「现在」，用来刷新「已等 N 分钟」。 */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun KitchenBoard(
    orders: List<KitchenOrder>,
    busy: Set<String>,
    loadError: String,
    onAdvance: (KitchenOrder, String) -> Unit,
    onCancel: (KitchenOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val waiting = orders.filter { it.status == "pending_payment" }
    val grouped = KITCHEN_GROUPS.map { g -> g to orders.filter { it.status in g.statuses }.sortedBy { it.createdAt } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            // 平板 / 横屏：三栏看板
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                grouped.forEachIndexed { index, (group, list) ->
                    LazyColumn(
                        Modifier.weight(1f).fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (index == 0 && loadError.isNotBlank()) item(key = "err") { ErrorText(loadError) }
                        item(key = "h-${group.title}") { GroupHeader(group.title, list.size) }
                        items(list, key = { "o-" + it.id }) { o ->
                            KitchenOrderCard(o, now, "order:${o.id}" in busy, onAdvance, onCancel)
                        }
                        if (index == KITCHEN_GROUPS.size - 1) waitingSection(waiting, now)
                    }
                }
            }
        } else {
            // 手机竖屏：一列，按组分段
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loadError.isNotBlank()) item(key = "err") { ErrorText(loadError) }
                if (orders.none { it.isActive() }) {
                    item(key = "empty") {
                        Text(
                            "暂无订单",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                }
                for ((group, list) in grouped) {
                    if (list.isEmpty()) continue
                    item(key = "h-${group.title}") { GroupHeader(group.title, list.size) }
                    items(list, key = { "o-" + it.id }) { o ->
                        KitchenOrderCard(o, now, "order:${o.id}" in busy, onAdvance, onCancel)
                    }
                }
                waitingSection(waiting, now)
            }
        }
    }
}

private fun LazyListScope.waitingSection(waiting: List<KitchenOrder>, now: Long) {
    if (waiting.isEmpty()) return
    item(key = "h-waiting") { GroupHeader("等待收款", waiting.size) }
    items(waiting, key = { "w-" + it.id }) { o -> WaitingPaymentCard(o, now) }
}

@Composable
fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Text(
        "$title（$count）",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun WaitedText(order: KitchenOrder, now: Long) {
    val minutes = TimeFmt.waitedMinutes(order.createdAt, now)
    if (minutes == null) {
        Text(order.createdAt, style = MaterialTheme.typography.bodyMedium)
    } else {
        val late = minutes > LATE_MINUTES && order.isActive()
        Text(
            "已等 $minutes 分钟",
            style = MaterialTheme.typography.titleMedium,
            color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OrderLines(order: KitchenOrder) {
    for (line in order.items) {
        Text("${line.name} × ${line.qty}", style = MaterialTheme.typography.titleMedium)
        if (line.notes.isNotBlank()) {
            Text(
                "备注：${line.notes}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun KitchenOrderCard(
    order: KitchenOrder,
    now: Long,
    isBusy: Boolean,
    onAdvance: (KitchenOrder, String) -> Unit,
    onCancel: (KitchenOrder) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val primary = order.primaryKitchenNext()
    val others = order.kitchenNextStatuses().filter { it != primary }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = statusContainerColor(order.status),
            contentColor = statusContentColor(order.status),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(order.pickupNo.ifBlank { "—" }, style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${order.statusLabel()} · ${order.fulfillmentLabel()}", style = MaterialTheme.typography.titleMedium)
                    WaitedText(order, now)
                }
                if (others.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !isBusy) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            for (next in others) {
                                DropdownMenuItem(
                                    text = { Text(actionLabelOf(next), style = MaterialTheme.typography.titleMedium) },
                                    onClick = {
                                        menuOpen = false
                                        if (next == "cancelled") confirmCancel = true else onAdvance(order, next)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OrderLines(order)
            if (primary != null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onAdvance(order, primary) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Text(if (isBusy) "提交中…" else actionLabelOf(primary), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "取消订单？",
            message = "确定取消取餐号 ${order.pickupNo} 的订单吗？取消后不能恢复。",
            confirmText = "确定取消",
            onConfirm = { onCancel(order) },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun WaitingPaymentCard(order: KitchenOrder, now: Long) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(order.pickupNo.ifBlank { "—" }, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Text("等待收款 · ${order.fulfillmentLabel()}", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                order.items.joinToString("，") { "${it.name}×${it.qty}" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(TimeFmt.relative(order.createdAt, now), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- 老板：订单页 ----------------

@Composable
fun BossOrdersPage(
    orders: List<KitchenOrder>,
    busy: Set<String>,
    loadError: String,
    onConfirmPayment: (KitchenOrder) -> Unit,
    onCancel: (KitchenOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    var showAll by remember { mutableStateOf(false) }
    val pending = orders.filter { it.status == "pending_payment" }.sortedBy { it.createdAt }
    val rest = orders.filter { it.status != "pending_payment" && (showAll || it.isActive()) }
        .sortedByDescending { it.createdAt }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loadError.isNotBlank()) item(key = "err") { ErrorText(loadError) }
        item(key = "h-pending") { GroupHeader("待收款", pending.size) }
        if (pending.isEmpty()) {
            item(key = "pending-empty") { Text("没有待收款的单", style = MaterialTheme.typography.bodyLarge) }
        }
        items(pending, key = { "p-" + it.id }) { o ->
            BossOrderCard(o, now, "order:${o.id}" in busy, onConfirmPayment, onCancel)
        }
        item(key = "h-rest") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showAll) "全部订单（${rest.size}）" else "进行中（${rest.size}）",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "只看进行中" else "看全部")
                }
            }
        }
        if (rest.isEmpty()) {
            item(key = "rest-empty") { Text("暂无订单", style = MaterialTheme.typography.bodyLarge) }
        }
        items(rest, key = { "r-" + it.id }) { o ->
            BossOrderCard(o, now, "order:${o.id}" in busy, onConfirmPayment, onCancel)
        }
    }
}

@Composable
private fun BossOrderCard(
    order: KitchenOrder,
    now: Long,
    isBusy: Boolean,
    onConfirmPayment: (KitchenOrder) -> Unit,
    onCancel: (KitchenOrder) -> Unit,
) {
    var confirmPay by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val canCancel = "cancelled" in order.nextStatuses()

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = statusContainerColor(order.status),
            contentColor = statusContentColor(order.status),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(order.pickupNo.ifBlank { "—" }, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${order.statusLabel()} · ${order.fulfillmentLabel()}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${TimeFmt.relative(order.createdAt, now)} · 顾客 ${shortCustomer(order.externalUserid)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OrderLines(order)
            Spacer(Modifier.height(6.dp))
            Text("合计 ${order.totalAmount} 元 · 应付 ${order.payableTailAmount} 元", style = MaterialTheme.typography.titleMedium)
            if (order.status == "pending_payment") {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { confirmPay = true },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) {
                    Text(if (isBusy) "提交中…" else "确认已收款", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (canCancel) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { confirmCancel = true },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("取消订单") }
            }
        }
    }

    if (confirmPay) {
        ConfirmDialog(
            title = "确认已收款？",
            message = "取餐号 ${order.pickupNo}，应付 ${order.payableTailAmount} 元。确认后订单进入后厨队列。",
            confirmText = "确认已收款",
            onConfirm = { onConfirmPayment(order) },
            onDismiss = { confirmPay = false },
            destructive = false,
        )
    }
    if (confirmCancel) {
        ConfirmDialog(
            title = "取消订单？",
            message = "确定取消取餐号 ${order.pickupNo} 的订单吗？取消后不能恢复。",
            confirmText = "确定取消",
            onConfirm = { onCancel(order) },
            onDismiss = { confirmCancel = false },
        )
    }
}
