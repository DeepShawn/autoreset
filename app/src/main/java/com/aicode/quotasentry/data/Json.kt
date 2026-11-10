package com.aicode.quotasentry.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 三家服务返回的 JSON 结构五花八门且随时会变，这里放一组容错工具：
 * - 宽松 JSON 解析（去掉 `)]}'` 前缀 / 前后杂字符）；
 * - 时间解析：ISO8601 / RFC1123 / epoch 秒 / epoch 毫秒；
 * - 递归遍历对象树，按字段名猜「已用比例 / 窗口长度 / 重置时刻」；
 * - 量纲归一：大于 1.5 的数字按百分数除以 100，否则按 0..1 比例（Lead 统一约定）。
 *
 * 全部是纯函数，可直接写 JVM 单测。
 */
internal object TimeUtil {

    /** 解析成 epoch 毫秒；无法识别返回 null。数字优先按 epoch（秒或毫秒自动判断）。 */
    fun parseMillis(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return fromEpochNumber(it) }
        text.toDoubleOrNull()?.let { return fromEpochNumber(it.toLong()) }
        val iso = normalizeIso(text)
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        // 不带时区的 ISO 时间（如 "2026-11-09T15:00:00"）按 UTC 处理
        runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()?.let { return it }
        // HTTP 日期，如 "Sun, 09 Nov 2026 14:53:02 GMT"
        runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }

    /** 自动区分秒/毫秒：< 1e11 视为秒（1e11 秒 ≈ 公元 5138 年），否则视为毫秒。 */
    fun fromEpochNumber(value: Long): Long? {
        if (value <= 0L) return null
        return when {
            value < 1_000_000_000L -> null // 太小，不像时间
            value < 100_000_000_000L -> value * 1000L
            else -> value
        }
    }

    /** JSON 里的任意数字/字符串 → epoch 毫秒。 */
    fun parseJsonEpoch(value: Any?): Long? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> fromEpochNumber(value.toLong())
        is String -> parseMillis(value)
        else -> null
    }

    /** 解析「距离现在还有多少秒」的字段（reset_after / resets_in）。 */
    fun parseRelativeMillis(now: Long, value: Any?): Long? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> now + (value.toDouble() * 1000.0).toLong()
        is String -> value.trim().toLongOrNull()?.let { now + it * 1000L }
            ?: parseDurationMillis(value)?.let { now + it }
        else -> null
    }

    /** "5h" / "5 hours" / "18000s" / "7d" / "30m" / "18000"（无单位按秒） → 毫秒。 */
    fun parseDurationMillis(raw: String?): Long? {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return null
        val match = DURATION_RE.matchEntire(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]
        val factorMs = when (unit) {
            "", "s", "sec", "secs", "second", "seconds" -> 1_000.0
            "m", "min", "mins", "minute", "minutes" -> 60_000.0
            "h", "hr", "hrs", "hour", "hours" -> 3_600_000.0
            "d", "day", "days" -> 86_400_000.0
            else -> return null
        }
        val millis = (amount * factorMs).toLong()
        return millis.takeIf { it > 0L }
    }

    private fun normalizeIso(text: String): String {
        var value = text.trim()
        if (value.endsWith(" UTC", ignoreCase = true)) {
            value = value.dropLast(4).trim() + "Z"
        }
        value = value.replace(' ', 'T')
        // +0000 → +00:00（部分服务返回无冒号偏移）
        if (OFFSET_NO_COLON_RE.containsMatchIn(value)) {
            value = value.dropLast(5) + value.takeLast(5).let { it.substring(0, 3) + ":" + it.substring(3) }
        }
        return value
    }

    private val DURATION_RE = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([a-z]*)$")
    private val OFFSET_NO_COLON_RE = Regex("[+-]\\d{4}$")
}

/** 宽松 JSON 解析：容忍 `)]}'` 前缀、前后垃圾字符、BOM。 */
internal fun parseJsonLenient(raw: String?): Any? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val cleaned = text
        .removePrefix("\uFEFF")
        .removePrefix(")]}'")
        .trim()
    runCatching { return JSONObject(cleaned) }
    runCatching { return JSONArray(cleaned) }
    // 前后有杂字符时，从第一个 { 或 [ 开始截取到最后一个 } 或 ]
    val start = cleaned.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    val end = cleaned.indexOfLast { it == '}' || it == ']' }
    if (end <= start) return null
    val slice = cleaned.substring(start, end + 1)
    runCatching { return JSONObject(slice) }
    runCatching { return JSONArray(slice) }
    return null
}

internal fun parseJsonObjectLenient(raw: String?): JSONObject? = parseJsonLenient(raw) as? JSONObject

internal fun parseJsonArrayLenient(raw: String?): JSONArray? = parseJsonLenient(raw) as? JSONArray

/** 对象树遍历结果：hint 是该对象在 JSON 中挂载路径/数组条目的关键词（小写）。 */
internal data class WalkedObject(val hint: String, val value: JSONObject)

/** 递归收集对象树里的所有 JSONObject，hint 由 key + 数组条目的 kind/window/group/type 拼成。 */
internal fun walkObjects(root: Any?, maxDepth: Int = 7): List<WalkedObject> {
    val result = mutableListOf<WalkedObject>()
    fun visit(node: Any?, hint: String, depth: Int) {
        if (depth > maxDepth) return
        when (node) {
            is JSONObject -> {
                result.add(WalkedObject(hint, node))
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = node.opt(key) ?: continue
                    when (child) {
                        is JSONObject -> visit(child, key.lowercase(), depth + 1)
                        is JSONArray -> {
                            for (index in 0 until child.length()) {
                                val item = child.optJSONObject(index) ?: continue
                                val itemHint = (
                                    key + " " +
                                        item.optString("kind") + " " +
                                        item.optString("window") + " " +
                                        item.optString("group") + " " +
                                        item.optString("type") + " " +
                                        item.optString("name")
                                    ).lowercase()
                                visit(item, itemHint, depth + 1)
                            }
                        }
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val item = node.optJSONObject(index) ?: continue
                    visit(item, "array", depth + 1)
                }
            }
        }
    }
    visit(root, "", 0)
    return result
}

/** 读取「已用比例」原始数字（百分数或 0..1），带字段名。 */
internal fun readUsedRaw(obj: JSONObject): Pair<String, Double>? {
    for (key in USED_KEYS) {
        val value = obj.opt(key) ?: continue
        if (value == JSONObject.NULL) continue
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: continue
        if (!number.isFinite()) continue
        return key to number
    }
    return null
}

/**
 * 归一成 0..1 的已用比例。
 *
 * 量纲约定（与 Lead 对齐）：值 > 1.5 视为百分数（除以 100），否则视为 0..1。
 * 这能同时吃下 Claude 的 `utilization: 67` 与 `utilization: 0.42` 两种形态；
 * 无法区分「1 表示 1% 还是 100%」的极端场景按 100% 处理，并在注释里标注。
 */
internal fun normalizeUsedFraction(rawKey: String, value: Double): Float {
    if (!value.isFinite()) return 0f
    // 字段名明确写了 percent/pct 的，一定是百分数：ChatGPT wham/usage 返回 used_percent=1
    // 表示「已用 1%」，不能按 0..1 的小数当成 100%。
    val percentNamed = rawKey.contains("percent", ignoreCase = true) ||
        rawKey.contains("pct", ignoreCase = true)
    val fraction = when {
        percentNamed -> value / 100.0
        value > 1.5 -> value / 100.0
        else -> value
    }
    return fraction.coerceIn(0.0, 1.0).toFloat()
}

internal fun readUsedFraction(obj: JSONObject): Float? {
    val (key, value) = readUsedRaw(obj) ?: return null
    return normalizeUsedFraction(key, value)
}

/** 读取窗口长度（毫秒），支持秒/分钟/小时字段与 "5h"/"7d" 文本。 */
internal fun readWindowMillis(obj: JSONObject): Long? {
    for (key in listOf("limit_window_seconds", "window_seconds", "windowSeconds", "duration_seconds")) {
        val number = obj.optDouble(key, Double.NaN)
        if (number.isFinite() && number > 0) return (number * 1000).toLong()
    }
    for (key in listOf("limit_window_minutes", "window_minutes")) {
        val number = obj.optDouble(key, Double.NaN)
        if (number.isFinite() && number > 0) return (number * 60_000).toLong()
    }
    for (key in listOf("limit_window_hours", "window_hours")) {
        val number = obj.optDouble(key, Double.NaN)
        if (number.isFinite() && number > 0) return (number * 3_600_000).toLong()
    }
    for (key in listOf("window", "window_label", "duration", "limit_window")) {
        val text = obj.opt(key)
        if (text is String) TimeUtil.parseDurationMillis(text)?.let { return it }
    }
    return null
}

/** 读取重置时刻（epoch 毫秒），支持 resets_at / reset_at / reset_after 等。 */
internal fun readResetAt(obj: JSONObject, now: Long): Long? {
    for (key in listOf("resets_at", "reset_at", "resetsAt", "resetAt", "reset_time", "resets")) {
        val value = obj.opt(key) ?: continue
        TimeUtil.parseJsonEpoch(value)?.let { return it }
    }
    for (key in listOf("reset_after", "resets_in", "reset_in", "reset_after_seconds", "seconds_until_reset")) {
        val value = obj.opt(key) ?: continue
        TimeUtil.parseRelativeMillis(now, value)?.let { return it }
    }
    return null
}

/** "plus"/"claude_pro"/"chatgpt_pro" 之类的原始 plan 字段 → 展示用名称。 */
internal fun planLabel(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return ""
    val key = text.lowercase()
    return when {
        key.contains("max") -> "Max"
        key.contains("ultra") -> "Ultra"
        key.contains("enterprise") -> "Enterprise"
        key.contains("business") -> "Business"
        key.contains("team") -> "Team"
        key.contains("plus") -> "Plus"
        key.contains("pro") -> "Pro"
        key.contains("edu") -> "Edu"
        key.contains("free") || key == "basic" -> "Free"
        else -> text.replaceFirstChar { it.uppercase() }
    }
}

/** 从任意 JSON 值里找布尔/字符串形式的 plan 提示。 */
internal fun extractPlanHint(root: JSONObject): String {
    for (key in listOf("plan_type", "planType", "plan", "subscription_plan", "tier", "product")) {
        val value = root.opt(key)
        if (value is String && value.isNotBlank()) return planLabel(value)
    }
    return ""
}

internal val USED_KEYS = listOf(
    "utilization",
    "used_percent",
    "usedPercent",
    "used_percentage",
    "percent",
    "percentage",
    "used_fraction",
    "usedFraction",
    "used_ratio",
    "usedRatio"
)
