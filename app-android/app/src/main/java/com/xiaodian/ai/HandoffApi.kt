package com.xiaodian.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Backend contract (see backend app/main.py):
//   GET  /kf/handoff/list    -> {"handoffs": [...], "pending_customers": [...]}
//   POST /kf/handoff/reply   -> {"status": "replied" | "blocked_by_48h_limit", ...}
//   POST /kf/handoff/resolve -> {"resolved": true, ...}
//   GET  /kf/media/{id}      -> raw media bytes（需要鉴权头，所以必须先下载再打开）
//   POST /kf/send-image      -> multipart, counts as one 48h/5 message
//
// A handoff never blocks text orders: the AI keeps answering text,
// the boss only handles the media message here.
data class HandoffItem(
    val externalUserid: String,
    val msgid: String,
    val msgtype: String,
    val mediaId: String,
    val createdAt: String,
) {
    fun typeLabel(): String = when (msgtype) {
        "voice" -> "语音"
        "image" -> "图片"
        "video" -> "视频"
        "file" -> "文件"
        else -> msgtype
    }

    /** 列表 key / 已见 id：优先用 msgid。 */
    fun key(): String = msgid.ifBlank { "$externalUserid|$mediaId|$createdAt" }
}

object HandoffRepository {
    fun fetchHandoffs(): List<HandoffItem> {
        val root = ApiClient.getJson("/kf/handoff/list", "拉取待处理")
        val arr: JSONArray = root.optJSONArray("handoffs") ?: JSONArray()
        val out = mutableListOf<HandoffItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += HandoffItem(
                externalUserid = o.optString("external_userid"),
                msgid = o.optString("msgid"),
                msgtype = o.optString("msgtype"),
                mediaId = o.optString("media_id"),
                createdAt = o.optString("created_at"),
            )
        }
        return out
    }

    /** Returns "replied", or throws with the blocked detail. */
    fun reply(externalUserid: String, text: String, msgid: String?): String {
        val json = JSONObject().apply {
            put("external_userid", externalUserid)
            put("text", text)
            if (!msgid.isNullOrBlank()) put("msgid", msgid)
        }
        val res = ApiClient.postJson("/kf/handoff/reply", json, "回复顾客")
        val status = res.optString("status")
        if (status == "blocked_by_48h_limit") {
            throw IllegalStateException("48 小时 5 条已用完，未发送；等顾客再发消息后可再回")
        }
        return status
    }

    fun resolve(externalUserid: String, msgid: String?) {
        val json = JSONObject().apply {
            put("external_userid", externalUserid)
            if (!msgid.isNullOrBlank()) put("msgid", msgid)
        }
        ApiClient.postJson("/kf/handoff/resolve", json, "标记已处理")
    }

    /** 带鉴权头把媒体下载到文件，返回 Content-Type。 */
    fun downloadMedia(mediaId: String, target: File): String =
        ApiClient.downloadTo("/kf/media/$mediaId", target, "媒体下载")

    /** Send an image to a customer via KF. Counts as one 48h/5 message. */
    fun sendImage(externalUserid: String, bytes: ByteArray, filename: String, mimeType: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("external_userid", externalUserid)
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val res = ApiClient.postBody("/kf/send-image", body, "发送图片")
        val status = res.optString("status")
        if (status == "blocked_by_48h_limit") {
            throw IllegalStateException("48 小时 5 条已用完，未发送图片；等顾客再发消息后可再发")
        }
        return status
    }
}
