package com.xiaodian.ai

// created_at 显示：先按 ISO 解析，成功显示相对时间（「3 分钟前」），
// 失败就原样显示。
// - 带偏移的时间（2026-11-08T12:30:00+08:00、...Z、+0800、+08）按偏移换算成绝对时间；
// - 不带偏移的时间按本机时区理解（和旧版一样）；
// - 纯数字按 Unix 时间戳（秒或毫秒）理解。
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFmt {
    private val shortFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    // 末尾的时区偏移：Z / z / +08:00 / +0800 / +08（前面可能有空格）。
    private val offsetTail = Regex("""\s*([Zz]|[+-]\d{2}(:?\d{2})?)$""")

    /** 把常见写法规整成 ISO：日期和时间之间的空格换成 T，偏移统一成 +HH:MM。 */
    internal fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.length > 10 && s[10] == ' ') s = s.substring(0, 10) + "T" + s.substring(11).trimStart()
        val m = offsetTail.find(s) ?: return s
        // 只有时间部分（T 之后）才可能带偏移，避免把日期里的 "-08" 当成偏移。
        val tIdx = s.indexOf('T')
        if (tIdx < 0 || m.range.first <= tIdx) return s
        val body = s.substring(0, m.range.first)
        val off = m.groupValues[1]
        val norm = when {
            off.equals("z", ignoreCase = true) -> "Z"
            off.length == 3 -> "$off:00"                          // +08
            off.length == 5 -> off.substring(0, 3) + ":" + off.substring(3) // +0800
            else -> off                                              // +08:00
        }
        return body + norm
    }

    fun parseMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val s = normalize(trimmed)
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return Instant.parse(s).toEpochMilli() } catch (_: Exception) {}
        try {
            return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        val num = trimmed.toDoubleOrNull() ?: return null
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
