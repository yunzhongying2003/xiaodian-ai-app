package com.xiaodian.ai

// 0.3.2 店铺资料（老板设置页 →「店铺资料」）：
//   GET /app/shop-profile（Bearer）
//     -> {shop_name, hours, address, delivery_range, prep_minutes (int|null), notice}
//   PUT /app/shop-profile（Bearer，和其他写接口同一个 AuthProvider / 同一套 401、403 处理）
//     请求体只放改动过的字段，返回更新后的完整资料。
//     清空文字字段传 ""，清空出餐时长传 null。
// 0.3.3：加「客服人设」persona（后端 v3.3.2 起）。GET 还会返回 persona_default（默认人设全文）。
//   persona 为 "" 表示用默认人设；App 里显示的是「实际生效的人设」，保存时如果内容和默认一样就传 ""。
//   老后端（v3.3 / v3.3.1）不返回 persona_default，人设一栏显示「需要后端 v3.3.2」。
// 老后端（v3.3 之前）没有这个接口，返回 404：提示「后端版本过旧，请先部署 v3.3」。
import org.json.JSONObject

data class ShopProfile(
    val shopName: String = "",
    val hours: String = "",
    val address: String = "",
    val deliveryRange: String = "",
    /** 平均出餐时长（分钟）；null = 没填。 */
    val prepMinutes: Int? = null,
    val notice: String = "",
    /** 老板写的人设；"" = 用默认人设。 */
    val persona: String = "",
    /** 后端给的默认人设；null = 后端太旧，不支持人设。 */
    val personaDefault: String? = null,
) {
    /** 实际生效的人设（空就是默认）。 */
    val effectivePersona: String get() = persona.ifBlank { personaDefault ?: "" }
}

/** 后端没有 /app/shop-profile（404）。 */
class ShopProfileUnsupportedException : Exception(ShopProfileRepository.OLD_BACKEND)

object ShopProfileRepository {
    const val OLD_BACKEND = "后端版本过旧，请先部署 v3.3"
    private const val PATH = "/app/shop-profile"

    fun fetch(): ShopProfile = wrap404 { parse(ApiClient.getJson(PATH, "读取店铺资料")) }

    /** 只提交和 [old] 不同的字段；没有改动时直接返回 [old]。返回服务器上的完整资料。 */
    fun save(old: ShopProfile, new: ShopProfile): ShopProfile {
        val body = diff(old, new)
        if (body.length() == 0) return old
        return wrap404 { parse(ApiClient.putJson(PATH, body, "保存店铺资料")) }
    }

    fun diff(old: ShopProfile, new: ShopProfile): JSONObject {
        val o = JSONObject()
        if (old.shopName != new.shopName) o.put("shop_name", new.shopName)
        if (old.hours != new.hours) o.put("hours", new.hours)
        if (old.address != new.address) o.put("address", new.address)
        if (old.deliveryRange != new.deliveryRange) o.put("delivery_range", new.deliveryRange)
        if (old.prepMinutes != new.prepMinutes) o.put("prep_minutes", new.prepMinutes ?: JSONObject.NULL)
        if (old.notice != new.notice) o.put("notice", new.notice)
        if (new.personaDefault != null && old.persona != new.persona) o.put("persona", new.persona)
        return o
    }

    private fun parse(o: JSONObject): ShopProfile = ShopProfile(
        shopName = str(o, "shop_name"),
        hours = str(o, "hours"),
        address = str(o, "address"),
        deliveryRange = str(o, "delivery_range"),
        prepMinutes = intOrNull(o, "prep_minutes"),
        notice = str(o, "notice"),
        persona = str(o, "persona"),
        personaDefault = if (!o.has("persona_default") || o.isNull("persona_default")) null
            else o.optString("persona_default"),
    )

    /** null / 缺字段都当空串（optString 会把 JSON null 变成 "null"，这里避开）。 */
    private fun str(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key)

    /** 兼容 25 / 25.0 / "25"；null、空串、非数字都当没填。 */
    private fun intOrNull(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key)
        if (!d.isNaN()) return d.toInt()
        return o.optString(key).trim().toIntOrNull()
    }

    private inline fun <T> wrap404(block: () -> T): T =
        try {
            block()
        } catch (e: ApiException) {
            if (e.code == 404) throw ShopProfileUnsupportedException() else throw e
        }
}
