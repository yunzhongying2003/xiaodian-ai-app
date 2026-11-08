package com.xiaodian.ai

// Boss-only menu API: list / add / update / delete / AI generate, plus usage.
import org.json.JSONArray
import org.json.JSONObject

data class MenuItem(
    val id: String, val name: String, val price: String,
    val available: Boolean, val source: String,
)

data class Usage(
    val ordersTotal: Int,
    val ordersByStatus: Map<String, Int>,
    val completedAmount: Double,
    val menuTotal: Int,
    val menuAvailable: Int,
)

object MenuRepository {
    fun fetchMenu(): List<MenuItem> {
        val arr = ApiClient.getJson("/kf/menu/list", "拉取菜单").optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<MenuItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += MenuItem(
                o.optString("id"), o.optString("name"), o.optString("price"),
                o.optBoolean("available"), o.optString("source"),
            )
        }
        return out
    }

    fun add(name: String, price: Double, source: String = "manual") {
        val json = JSONObject().put("name", name).put("price", price).put("source", source)
        ApiClient.postJson("/kf/menu/add", json, "添加菜品")
    }

    fun delete(id: String) {
        ApiClient.postJson("/kf/menu/delete", JSONObject().put("id", id), "删除菜品")
    }

    fun update(id: String, price: Double? = null, available: Boolean? = null) {
        val json = JSONObject().put("id", id)
        if (price != null) json.put("price", price)
        if (available != null) json.put("available", available)
        ApiClient.postJson("/kf/menu/update", json, "修改菜品")
    }

    fun generate(description: String): List<MenuItem> {
        val json = JSONObject().put("description", description)
        val arr = ApiClient.postJson("/kf/menu/generate", json, "AI 生成菜单").optJSONArray("drafts") ?: JSONArray()
        val out = mutableListOf<MenuItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optDouble("price")
            out += MenuItem("", o.optString("name"), if (p.isNaN()) "" else formatPrice(p), true, "ai")
        }
        return out
    }

    fun fetchUsage(): Usage {
        val o = ApiClient.getJson("/kf/usage", "拉取用量")
        val byStatus = mutableMapOf<String, Int>()
        val obj: JSONObject? = o.optJSONObject("orders_by_status")
        if (obj != null) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                byStatus[k] = obj.optInt(k)
            }
        }
        val amount = o.optDouble("completed_amount")
        return Usage(
            ordersTotal = o.optInt("orders_total"),
            ordersByStatus = byStatus,
            completedAmount = if (amount.isNaN()) 0.0 else amount,
            menuTotal = o.optInt("menu_total"),
            menuAvailable = o.optInt("menu_available"),
        )
    }
}

/** 25.0 -> "25"，25.5 -> "25.5"，25.55 -> "25.55"。 */
fun formatPrice(value: Double): String {
    val s = String.format(java.util.Locale.US, "%.2f", value)
    return s.trimEnd('0').trimEnd('.')
}
