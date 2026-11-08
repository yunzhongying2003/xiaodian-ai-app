package com.xiaodian.ai

import org.json.JSONArray
import org.json.JSONObject

// Backend contract (see backend app/main.py):
//   GET  /kf/orders/list            -> {"orders": [...]}
//   POST /kf/orders/{id}/status     -> {"order": {...}} or 400/404
//
// Orders are REAL only: the backend records one only when an AI
// confirmation was actually sent to a customer (status
// pending_payment). The UI must say "暂无订单", never invent rows.
// 「待付款 → 已下单」（确认收款）只在老板模式的订单页做，actor 传 "boss"。
data class OrderLine(val name: String, val qty: Int, val notes: String)

data class KitchenOrder(
    val id: String,
    val pickupNo: String,
    val externalUserid: String,
    val items: List<OrderLine>,
    val subtotal: String,
    val totalAmount: String,
    val payableTailAmount: String,
    val fulfillment: String,
    val status: String,
    val createdAt: String,
) {
    fun statusLabel(): String = statusLabelOf(status)

    fun fulfillmentLabel(): String = fulfillmentLabelOf(fulfillment)

    /** Only the legal next statuses from orders.py. */
    fun nextStatuses(): List<String> = when (status) {
        "pending_payment" -> listOf("ordered", "cancelled")
        "ordered" -> listOf("making", "cancelled")
        "making" -> listOf("ready")
        "ready" -> listOf("delivering", "awaiting_pickup", "completed")
        "delivering" -> listOf("completed")
        "awaiting_pickup" -> listOf("completed")
        else -> emptyList()
    }

    /** 后厨能做的下一步（不含「确认收款」，那一步只给老板）。 */
    fun kitchenNextStatuses(): List<String> =
        if (status == "pending_payment") emptyList() else nextStatuses()

    /** 后厨卡片上的大按钮：最常用的下一步。 */
    fun primaryKitchenNext(): String? = when (status) {
        "ordered" -> "making"
        "making" -> "ready"
        "ready" -> when (fulfillment) {
            "delivery" -> "delivering"
            "pickup" -> "awaiting_pickup"
            else -> "completed"
        }
        "delivering", "awaiting_pickup" -> "completed"
        else -> null
    }

    fun isActive(): Boolean = status !in setOf("completed", "cancelled")
}

fun fulfillmentLabelOf(fulfillment: String): String = when (fulfillment) {
    "pickup" -> "自取"
    "delivery" -> "配送"
    "dine_in" -> "堂食"
    else -> fulfillment
}

fun statusLabelOf(status: String): String = when (status) {
    "pending_payment" -> "待付款"
    "ordered" -> "已下单"
    "making" -> "制作中"
    "ready" -> "已出餐"
    "delivering" -> "配送中"
    "awaiting_pickup" -> "待自取"
    "completed" -> "已完成"
    "handoff" -> "待人工"
    "cancelled" -> "已取消"
    else -> status
}

fun actionLabelOf(next: String): String = when (next) {
    "ordered" -> "确认已收款"
    "making" -> "开始制作"
    "ready" -> "已出餐"
    "delivering" -> "开始配送"
    "awaiting_pickup" -> "通知待取"
    "completed" -> "完成"
    "cancelled" -> "取消订单"
    else -> next
}

object OrderRepository {
    fun fetchOrders(): List<KitchenOrder> {
        val root = ApiClient.getJson("/kf/orders/list", "拉取订单")
        val arr: JSONArray = root.optJSONArray("orders") ?: JSONArray()
        val out = mutableListOf<KitchenOrder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            val lines = mutableListOf<OrderLine>()
            for (j in 0 until itemsArr.length()) {
                val line = itemsArr.optJSONObject(j) ?: continue
                lines += OrderLine(
                    name = line.optString("name"),
                    qty = line.optInt("qty", 1),
                    notes = line.optString("notes"),
                )
            }
            out += KitchenOrder(
                id = o.optString("id"),
                pickupNo = o.optString("pickup_no"),
                externalUserid = o.optString("external_userid"),
                items = lines,
                subtotal = o.optString("subtotal"),
                totalAmount = o.optString("total_amount"),
                payableTailAmount = o.optString("payable_tail_amount"),
                fulfillment = o.optString("fulfillment"),
                status = o.optString("status"),
                createdAt = o.optString("created_at"),
            )
        }
        return out
    }

    /** actor: "kitchen"（后厨推进状态）或 "boss"（确认收款、老板取消）。 */
    fun updateStatus(orderId: String, newStatus: String, actor: String) {
        val json = JSONObject().apply {
            put("status", newStatus)
            put("actor", actor)
        }
        ApiClient.postJson("/kf/orders/$orderId/status", json, "更新订单状态")
    }
}
