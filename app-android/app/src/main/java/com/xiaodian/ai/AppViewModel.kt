package com.xiaodian.ai

// 页面数据全部放在这里，用 StateFlow 暴露给界面。
// - 刷新时按 id 合并：没变的条目保留原对象，列表 key 不变，
//   所以正在输入的回复框不会被刷新打断、也不会丢焦点。
// - 回复草稿按 msgid 存在这里，刷新、切页都不会丢。
// - App 在前台时每 8 秒自动刷新一次订单和待人工；后台服务的 SSE 在线时降为每 60 秒兜底，
//   SSE 事件到达时立即刷新（AppEvents.refresh）。也保留手动刷新。
// - 老板模式顶部：优先 /app/stats/today（今日单数、营业额、热销前 3），404 时退回 /kf/usage。
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    init { ApiClient.init(app) }

    val settings: AppSettings = ApiClient.settings

    /** 登录状态；null = 未登录，显示登录页。 */
    val session: StateFlow<Session?> = ApiClient.session
    /** 被动退出（401）的原因，显示在登录页。 */
    val logoutReason: StateFlow<String> = ApiClient.logoutReason

    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy.asStateFlow()

    private val _loginError = MutableStateFlow("")
    val loginError: StateFlow<String> = _loginError.asStateFlow()

    private val _mode = MutableStateFlow(settings.mode)
    val mode: StateFlow<AppMode> = _mode.asStateFlow()

    private val _orders = MutableStateFlow<List<KitchenOrder>>(emptyList())
    val orders: StateFlow<List<KitchenOrder>> = _orders.asStateFlow()

    private val _handoffs = MutableStateFlow<List<HandoffItem>>(emptyList())
    val handoffs: StateFlow<List<HandoffItem>> = _handoffs.asStateFlow()

    private val _menu = MutableStateFlow<List<MenuItem>>(emptyList())
    val menu: StateFlow<List<MenuItem>> = _menu.asStateFlow()

    private val _drafts = MutableStateFlow<List<MenuItem>>(emptyList())
    val drafts: StateFlow<List<MenuItem>> = _drafts.asStateFlow()

    private val _usage = MutableStateFlow<Usage?>(null)
    val usage: StateFlow<Usage?> = _usage.asStateFlow()

    /** 今日统计；null 表示还没拿到或后端不支持（这时顶部用 usage）。 */
    private val _stats = MutableStateFlow<TodayStats?>(null)
    val stats: StateFlow<TodayStats?> = _stats.asStateFlow()

    /** 后端没有 /app/stats/today（404）时记下，10 分钟后再试。 */
    @Volatile private var statsUnsupportedUntil = 0L
    @Volatile private var lastLiveRefresh = 0L

    /** 回复草稿：msgid -> 文本。 */
    private val _replyDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val replyDrafts: StateFlow<Map<String, String>> = _replyDrafts.asStateFlow()

    /** 拉取失败的提示（显示在列表顶部，下一次成功后清空）。 */
    private val _loadError = MutableStateFlow("")
    val loadError: StateFlow<String> = _loadError.asStateFlow()

    /** 一次性提示（Snackbar）。 */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 正在提交的条目 id，按钮显示为不可点，防止连点。 */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private var autoJob: Job? = null

    init {
        // 后台服务收到 SSE 事件 → 立即刷新。
        viewModelScope.launch {
            AppEvents.refresh.collect {
                if (autoJob?.isActive == true) refreshLive()
            }
        }
    }

    fun consumeMessage() { _message.value = "" }

    // ---------- 登录 ----------

    fun login(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank()) { _loginError.value = "请填写服务器地址"; return }
        if (username.isBlank() || password.isEmpty()) { _loginError.value = "请填写用户名和密码"; return }
        if (_loginBusy.value) return
        _loginBusy.value = true
        _loginError.value = ""
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.login(serverUrl, username.trim(), password) }
                OrderAlertService.sync(getApplication())
                refreshAllInternal()
            } catch (e: Exception) {
                _loginError.value = e.message ?: "登录失败"
            } finally {
                _loginBusy.value = false
            }
        }
    }

    fun clearLoginError() { _loginError.value = "" }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ApiClient.logout() }
            clearData()
            OrderAlertService.sync(getApplication())
        }
    }

    /** 启动时校验一次 token，顺便刷新用户名和有效期。 */
    fun checkSession() {
        if (!ApiClient.isLoggedIn()) return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { ApiClient.refreshMe() } } catch (_: Exception) {}
        }
    }

    /** 退出登录后清掉页面上的业务数据。 */
    fun clearData() {
        _orders.value = emptyList()
        _handoffs.value = emptyList()
        _menu.value = emptyList()
        _drafts.value = emptyList()
        _usage.value = null
        _stats.value = null
        _replyDrafts.value = emptyMap()
        _loadError.value = ""
    }
    private fun toast(text: String) { _message.value = text }

    fun setMode(m: AppMode) {
        settings.mode = m
        _mode.value = m
        refreshAll()
    }

    fun setReplyDraft(msgid: String, text: String) {
        _replyDrafts.value = _replyDrafts.value + (msgid to text)
    }

    // ---------- 刷新 ----------

    fun startAutoRefresh() {
        if (autoJob?.isActive == true) return
        autoJob = viewModelScope.launch {
            refreshAllInternal()
            while (isActive) {
                delay(POLL_MS)
                // SSE 在线时只做 60 秒一次的兜底刷新。
                val interval = if (AppEvents.sseConnected.value) OrderAlertService.FALLBACK_POLL_MS else POLL_MS
                if (System.currentTimeMillis() - lastLiveRefresh >= interval - 500) refreshLive()
            }
        }
    }

    fun stopAutoRefresh() {
        autoJob?.cancel()
        autoJob = null
    }

    fun refreshAll() {
        viewModelScope.launch { refreshAllInternal() }
    }

    private suspend fun refreshAllInternal() {
        if (!ApiClient.isLoggedIn()) return
        _refreshing.value = true
        try {
            refreshLive()
            if (_mode.value == AppMode.BOSS) refreshMenu()
        } finally {
            _refreshing.value = false
        }
    }

    /** 订单 + 待人工（+ 老板模式下的用量）。 */
    private suspend fun refreshLive() {
        if (!ApiClient.isLoggedIn()) return
        lastLiveRefresh = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        try {
            val fresh = withContext(Dispatchers.IO) { OrderRepository.fetchOrders() }
                .distinctBy { it.id }
            _orders.value = mergeById(_orders.value, fresh) { it.id }
        } catch (e: Exception) {
            errors += (e.message ?: "拉取订单失败")
        }
        try {
            val fresh = withContext(Dispatchers.IO) { HandoffRepository.fetchHandoffs() }
                .distinctBy { it.key() }
            _handoffs.value = mergeById(_handoffs.value, fresh) { it.key() }
        } catch (e: Exception) {
            errors += (e.message ?: "拉取待处理失败")
        }
        if (_mode.value == AppMode.BOSS) refreshStats(errors)
        _loadError.value = if (ApiClient.isLoggedIn()) errors.distinct().joinToString("\n") else ""
        if (!ApiClient.isLoggedIn()) clearData()
    }

    /** 老板顶部统计：先试 /app/stats/today，404 退回 /kf/usage。 */
    private suspend fun refreshStats(errors: MutableList<String>) {
        if (System.currentTimeMillis() >= statsUnsupportedUntil) {
            try {
                _stats.value = withContext(Dispatchers.IO) { StatsRepository.fetchToday() }
                return
            } catch (e: ApiException) {
                if (e.code == 404) {
                    statsUnsupportedUntil = System.currentTimeMillis() + 10 * 60_000L
                    _stats.value = null
                } else {
                    errors += (e.message ?: "拉取今日统计失败")
                    return
                }
            } catch (e: UnauthorizedException) {
                return
            } catch (e: Exception) {
                errors += (e.message ?: "拉取今日统计失败")
                return
            }
        }
        try {
            _usage.value = withContext(Dispatchers.IO) { MenuRepository.fetchUsage() }
        } catch (e: Exception) {
            errors += (e.message ?: "拉取用量失败")
        }
    }

    private suspend fun refreshMenu() {
        if (!ApiClient.isLoggedIn()) return
        try {
            val fresh = withContext(Dispatchers.IO) { MenuRepository.fetchMenu() }
                .distinctBy { it.id }
            _menu.value = mergeById(_menu.value, fresh) { it.id }
        } catch (e: Exception) {
            if (ApiClient.isLoggedIn()) _loadError.value = e.message ?: "拉取菜单失败"
        }
    }

    private fun <T> mergeById(old: List<T>, fresh: List<T>, id: (T) -> String): List<T> {
        val oldById = old.associateBy(id)
        val merged = fresh.map { n ->
            val o = oldById[id(n)]
            if (o != null && o == n) o else n
        }
        return if (merged == old) old else merged
    }

    private fun runBusy(key: String, block: suspend () -> Unit) {
        if (key in _busy.value) return
        _busy.value = _busy.value + key
        viewModelScope.launch {
            try {
                block()
            } catch (e: UnauthorizedException) {
                clearData() // 回到登录页，原因显示在登录页上
            } catch (e: Exception) {
                toast(e.message ?: "操作失败")
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }

    // ---------- 订单 ----------

    /** 后厨推进状态（actor=kitchen）。 */
    fun advanceOrder(order: KitchenOrder, next: String) {
        runBusy("order:${order.id}") {
            withContext(Dispatchers.IO) { OrderRepository.updateStatus(order.id, next, "kitchen") }
            toast("取餐号 ${order.pickupNo}：${statusLabelOf(next)}")
            refreshLive()
        }
    }

    /** 老板确认收款：待付款 → 已下单（actor=boss）。 */
    fun confirmPayment(order: KitchenOrder) {
        runBusy("order:${order.id}") {
            withContext(Dispatchers.IO) { OrderRepository.updateStatus(order.id, "ordered", "boss") }
            toast("取餐号 ${order.pickupNo} 已确认收款，已进后厨队列")
            refreshLive()
        }
    }

    fun cancelOrder(order: KitchenOrder, actor: String) {
        runBusy("order:${order.id}") {
            withContext(Dispatchers.IO) { OrderRepository.updateStatus(order.id, "cancelled", actor) }
            toast("取餐号 ${order.pickupNo} 已取消")
            refreshLive()
        }
    }

    // ---------- 待人工 ----------

    fun reply(item: HandoffItem) {
        val text = (_replyDrafts.value[item.key()] ?: "").trim()
        if (text.isEmpty()) return
        runBusy("handoff:${item.key()}") {
            withContext(Dispatchers.IO) {
                HandoffRepository.reply(item.externalUserid, text, item.msgid)
            }
            _replyDrafts.value = _replyDrafts.value - item.key()
            toast("已发送给顾客")
            refreshLive()
        }
    }

    fun resolve(item: HandoffItem) {
        runBusy("handoff:${item.key()}") {
            withContext(Dispatchers.IO) { HandoffRepository.resolve(item.externalUserid, item.msgid) }
            _replyDrafts.value = _replyDrafts.value - item.key()
            toast("已标记处理")
            refreshLive()
        }
    }

    fun sendImage(item: HandoffItem, uri: Uri) {
        runBusy("handoff:${item.key()}") {
            val resolver = getApplication<Application>().contentResolver
            val bytes = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("读取图片失败")
            }
            if (bytes.size > 2 * 1024 * 1024) {
                toast("图片超过 2MB，微信会拒收，请换张小图")
                return@runBusy
            }
            val mime = resolver.getType(uri) ?: "image/jpeg"
            withContext(Dispatchers.IO) {
                HandoffRepository.sendImage(item.externalUserid, bytes, "send.jpg", mime)
            }
            toast("图片已发送")
        }
    }

    // ---------- 菜单 ----------

    fun refreshMenuNow() {
        if (!ApiClient.isLoggedIn()) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                refreshMenu()
                try {
                    _usage.value = withContext(Dispatchers.IO) { MenuRepository.fetchUsage() }
                } catch (e: Exception) {
                    _loadError.value = e.message ?: "拉取用量失败"
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** 返回 null 表示校验通过并已提交；否则返回错误提示。 */
    fun addMenuItem(name: String, priceText: String): String? {
        if (name.isBlank()) return "菜名不能为空"
        val price = priceText.trim().toDoubleOrNull()
        if (price == null || price <= 0) return "价格只能填数字，如 25，不要带「元」字"
        runBusy("menu:add") {
            try {
                withContext(Dispatchers.IO) { MenuRepository.add(name.trim(), price, "manual") }
            } catch (e: Exception) {
                throw IllegalStateException("${e.message}（重名菜品会被拒绝）")
            }
            toast("已添加：${name.trim()}")
            refreshMenu()
        }
        return null
    }

    fun changePrice(item: MenuItem, newPrice: Double) {
        runBusy("menu:${item.id}") {
            withContext(Dispatchers.IO) { MenuRepository.update(item.id, price = newPrice) }
            toast("已改价：${item.name} ${formatPrice(newPrice)} 元")
            refreshMenu()
        }
    }

    fun toggleAvailable(item: MenuItem) {
        runBusy("menu:${item.id}") {
            withContext(Dispatchers.IO) { MenuRepository.update(item.id, available = !item.available) }
            toast(if (item.available) "已停售：${item.name}" else "已恢复在售：${item.name}")
            refreshMenu()
        }
    }

    fun deleteMenuItem(item: MenuItem) {
        runBusy("menu:${item.id}") {
            try {
                withContext(Dispatchers.IO) { MenuRepository.delete(item.id) }
            } catch (e: Exception) {
                throw IllegalStateException("${e.message}（有订单的菜只能停售，不能删除）")
            }
            toast("已删除：${item.name}")
            refreshMenu()
        }
    }

    fun generateDrafts(description: String) {
        if (description.isBlank()) {
            toast("先写一句店铺描述，如：川菜小店，家常菜，20-30 元")
            return
        }
        runBusy("menu:generate") {
            try {
                val d = withContext(Dispatchers.IO) { MenuRepository.generate(description.trim()) }
                _drafts.value = d
                toast("AI 草稿 ${d.size} 道，勾选后加入菜单才生效")
            } catch (e: Exception) {
                throw IllegalStateException("${e.message}（没有模型密钥时请用手动添加）")
            }
        }
    }

    fun addDrafts(selected: List<MenuItem>) {
        if (selected.isEmpty()) return
        runBusy("menu:drafts") {
            val added = mutableListOf<MenuItem>()
            val failed = mutableListOf<String>()
            for (d in selected) {
                val p = d.price.toDoubleOrNull()
                if (p == null || p <= 0) { failed += "${d.name}（价格无效）"; continue }
                try {
                    withContext(Dispatchers.IO) { MenuRepository.add(d.name, p, "ai") }
                    added += d
                } catch (e: Exception) {
                    failed += d.name
                }
            }
            _drafts.value = _drafts.value.filterNot { d -> added.any { it.name == d.name } }
            toast(
                if (failed.isEmpty()) "已加入 ${added.size} 道菜"
                else "已加入 ${added.size} 道；失败：${failed.joinToString("、")}"
            )
            refreshMenu()
        }
    }

    fun clearDrafts() { _drafts.value = emptyList() }

    override fun onCleared() {
        stopAutoRefresh()
        super.onCleared()
    }

    companion object {
        const val POLL_MS = 8_000L
    }
}
