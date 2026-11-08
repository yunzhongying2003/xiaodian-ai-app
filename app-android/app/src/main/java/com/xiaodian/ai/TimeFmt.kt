package com.xiaodian.ai

// created_at 显示：先按 ISO 解析，成功显示相对时间（「3 分钟前」），
// 失败就原样显示。没有时区的时间按本机时区理解。
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFmt {
    private val shortFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun parseMillis(raw: String): Long? {
        val s = raw.trim().replace(' ', 'T')
        if (s.isEmpty()) return null
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return Instant.parse(s).toEpochMilli() } catch (_: Exception) {}
        try {
            return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        val num = raw.trim().toDoubleOrNull() ?: return null
        return if (num > 1e12) num.toLong() else (num * 1000).toLong()
    }

    fun relative(raw: String, now: Long = System.currentTimeMillis()): String {
        val t = parseMillis(raw) ?: return raw
        val sec = (now - t) / 1000
        return when {
            sec < -120 -> shortFmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
            sec < 60 -> "刚刚"
            sec < 3600 -> "${sec / 60} 分钟前"
            sec < 86_400 -> "${sec / 3600} 小时前"
            sec < 7 * 86_400 -> "${sec / 86_400} 天前"
            else -> shortFmt.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
        }
    }

    /** 已等多少分钟；解析失败返回 null。 */
    fun waitedMinutes(raw: String, now: Long = System.currentTimeMillis()): Long? {
        val t = parseMillis(raw) ?: return null
        val m = (now - t) / 60_000
        return if (m < 0) 0 else m
    }
}
