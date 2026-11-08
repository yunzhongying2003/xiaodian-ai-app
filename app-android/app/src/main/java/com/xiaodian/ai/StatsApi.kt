package com.xiaodian.ai

// 今日统计（老板模式顶部）：
//   GET /app/stats/today（Bearer）
//   -> {date, orders, paid_orders, revenue, completed_orders, top_items: [{name, qty}]}
// 旧后端没有这个接口（404）时，界面退回用 /kf/usage 的「今日已完成金额」。
import org.json.JSONArray
import org.json.JSONObject

data class TopItem(val name: String, val qty: Int)

data class TodayStats(
    val date: String,
    val orders: Int,
    val paidOrders: Int,
    val revenue: Double,
    val completedOrders: Int,
    val topItems: List<TopItem>,
)

object StatsRepository {
    fun fetchToday(): TodayStats {
        val o: JSONObject = ApiClient.getJson("/app/stats/today", "拉取今日统计")
        val arr: JSONArray = o.optJSONArray("top_items") ?: JSONArray()
        val top = mutableListOf<TopItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val name = it.optString("name")
            if (name.isBlank()) continue
            top += TopItem(name, numberOf(it, "qty").toInt())
        }
        return TodayStats(
            date = o.optString("date"),
            orders = numberOf(o, "orders").toInt(),
            paidOrders = numberOf(o, "paid_orders").toInt(),
            revenue = numberOf(o, "revenue"),
            completedOrders = numberOf(o, "completed_orders").toInt(),
            topItems = top.sortedByDescending { it.qty }.take(3),
        )
    }

    /** 数字字段兼容 12 / 12.5 / "12.50"。 */
    private fun numberOf(o: JSONObject, key: String): Double {
        val d = o.optDouble(key)
        if (!d.isNaN()) return d
        return o.optString(key).trim().toDoubleOrNull() ?: 0.0
    }
}
