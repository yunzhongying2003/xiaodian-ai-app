package com.xiaodian.ai

// App 入口。老板模式 + 后厨模式同属一个 App，一键切换（模式隔离 / 老板 PIN
// 用户决定暂缓）。当前模式存在 SharedPreferences，重启后保持。
// - 后厨：全屏看板。
// - 老板：底部导航四页（待处理 / 订单 / 菜单 / 设置），顶部显示今日已完成金额。
// 数据都在 AppViewModel；前台时每 8 秒自动刷新，后台由 OrderAlertService 提醒。
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var vm: AppViewModel

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            OrderAlertService.sync(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(applicationContext)
        vm = ViewModelProvider(this)[AppViewModel::class.java]

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            OrderAlertService.sync(this)
        }

        setContent {
            XiaodianTheme {
                AppRoot(
                    vm = vm,
                    onAlertsChanged = { OrderAlertService.sync(this) },
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.startAutoRefresh()
    }

    override fun onStop() {
        vm.stopAutoRefresh()
        super.onStop()
    }
}

private enum class BossTab(val label: String) { HANDOFF("待处理"), ORDERS("订单"), MENU("菜单"), SETTINGS("设置") }

@Composable
fun AppRoot(vm: AppViewModel, onAlertsChanged: () -> Unit, onExit: () -> Unit) {
    val mode by vm.mode.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            val text = message
            vm.consumeMessage()
            snackbar.showSnackbar(text)
        }
    }

    when (mode) {
        AppMode.KITCHEN -> KitchenRoot(vm, snackbar)
        AppMode.BOSS -> BossRoot(vm, snackbar, onAlertsChanged, onExit)
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, refreshing: Boolean, onRefresh: () -> Unit, switchLabel: String, onSwitch: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = onSwitch, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(switchLabel)
            }
        }
    }
}

@Composable
private fun KitchenRoot(vm: AppViewModel, snackbar: SnackbarHostState) {
    val orders by vm.orders.collectAsState()
    val busy by vm.busy.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val toMake = orders.count { it.status == "ordered" }
    val making = orders.count { it.status == "making" }

    Scaffold(
        topBar = {
            TopBar(
                title = "后厨看板",
                subtitle = "待制作 $toMake · 制作中 $making",
                refreshing = refreshing,
                onRefresh = { vm.refreshAll() },
                switchLabel = "老板模式",
                onSwitch = { vm.setMode(AppMode.BOSS) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        KitchenBoard(
            orders = orders,
            busy = busy,
            loadError = loadError,
            onAdvance = { o, next -> vm.advanceOrder(o, next) },
            onCancel = { o -> vm.cancelOrder(o, "kitchen") },
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BossRoot(
    vm: AppViewModel,
    snackbar: SnackbarHostState,
    onAlertsChanged: () -> Unit,
    onExit: () -> Unit,
) {
    val orders by vm.orders.collectAsState()
    val handoffs by vm.handoffs.collectAsState()
    val menu by vm.menu.collectAsState()
    val drafts by vm.drafts.collectAsState()
    val usage by vm.usage.collectAsState()
    val replyDrafts by vm.replyDrafts.collectAsState()
    val busy by vm.busy.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    var tabName by rememberSaveable { mutableStateOf(BossTab.HANDOFF.name) }
    val tab = BossTab.valueOf(tabName)
    val pendingPay = orders.count { it.status == "pending_payment" }

    LaunchedEffect(Unit) { vm.refreshMenuNow() }

    val amount = usage?.let { "今日已完成 ¥" + formatPrice(it.completedAmount) } ?: "今日已完成 —"

    Scaffold(
        topBar = {
            TopBar(
                title = amount,
                subtitle = "待处理 ${handoffs.size} · 待收款 $pendingPay",
                refreshing = refreshing,
                onRefresh = { if (tab == BossTab.MENU) vm.refreshMenuNow() else vm.refreshAll() },
                switchLabel = "后厨模式",
                onSwitch = { vm.setMode(AppMode.KITCHEN) },
            )
        },
        bottomBar = {
            NavigationBar {
                for (t in BossTab.values()) {
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tabName = t.name },
                        label = { Text(t.label) },
                        icon = {
                            val count = when (t) {
                                BossTab.HANDOFF -> handoffs.size
                                BossTab.ORDERS -> pendingPay
                                else -> 0
                            }
                            if (count > 0) {
                                BadgedBox(badge = { Badge { Text(if (count > 99) "99+" else "$count") } }) {
                                    Icon(iconFor(t), contentDescription = t.label)
                                }
                            } else {
                                Icon(iconFor(t), contentDescription = t.label)
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            BossTab.HANDOFF -> HandoffPage(
                items = handoffs,
                drafts = replyDrafts,
                busy = busy,
                loadError = loadError,
                onDraftChange = { item, text -> vm.setReplyDraft(item.key(), text) },
                onReply = { vm.reply(it) },
                onResolve = { vm.resolve(it) },
                onSendImage = { item, uri -> vm.sendImage(item, uri) },
                modifier = m,
            )
            BossTab.ORDERS -> BossOrdersPage(
                orders = orders,
                busy = busy,
                loadError = loadError,
                onConfirmPayment = { vm.confirmPayment(it) },
                onCancel = { vm.cancelOrder(it, "boss") },
                modifier = m,
            )
            BossTab.MENU -> MenuPage(
                items = menu,
                drafts = drafts,
                usage = usage,
                busy = busy,
                loadError = loadError,
                vm = vm,
                modifier = m,
            )
            BossTab.SETTINGS -> SettingsPage(
                settings = vm.settings,
                onSwitchToKitchen = { vm.setMode(AppMode.KITCHEN) },
                onAlertsChanged = onAlertsChanged,
                onServerChanged = { vm.refreshAll(); vm.refreshMenuNow() },
                onExit = onExit,
                modifier = m,
            )
        }
    }
}

private fun iconFor(t: BossTab) = when (t) {
    BossTab.HANDOFF -> Icons.Filled.Notifications
    BossTab.ORDERS -> Icons.Filled.ShoppingCart
    BossTab.MENU -> Icons.Filled.Edit
    BossTab.SETTINGS -> Icons.Filled.Settings
}
