package com.aicode.quotasentry.core

/**
 * 应用内所有共享数据模型。UI、Provider 适配器、后台服务都以本文件为准。
 *
 * 说明：三个大模型服务都没有面向个人账号的公开「发消息 / 查额度」API，
 * 因此这里的数据全部来自用户自己浏览器会话中的登录凭据（Cookie / access token），
 * 属于本机自用工具，不对外提供服务。
 */

enum class ProviderId(val key: String, val label: String, val shortLabel: String) {
    CLAUDE("claude", "Claude", "Claude"),
    CHATGPT("chatgpt", "ChatGPT", "GPT"),
    GEMINI("gemini", "Gemini", "Gemini");

    companion object {
        fun fromKey(key: String?): ProviderId? = entries.firstOrNull { it.key == key }
        val ordered: List<ProviderId> = listOf(CLAUDE, CHATGPT, GEMINI)
    }
}

/** 用户从浏览器 / Chrome OAuth 授权里拿到的凭据。 */
data class Credentials(
    /** 完整 Cookie 请求头，例如 "sessionKey=...; __cf_bm=..." */
    val cookie: String = "",
    /** ChatGPT 的 access token（也可以由 session cookie 自动换取） */
    val accessToken: String = "",
    /** 适配器缓存的辅助信息：orgId、gemini at token、chatgpt account id 等 */
    val extra: Map<String, String> = emptyMap()
) {
    val present: Boolean get() = cookie.isNotBlank() || accessToken.isNotBlank()
}

data class Account(
    val provider: ProviderId,
    val credentials: Credentials = Credentials(),
    val linked: Boolean = false,
    /** 例如 "Pro" / "Plus" / "Free" / "Ultra" */
    val plan: String = "",
    /** 邮箱、组织名等可读标识，可能为空 */
    val label: String = "",
    val linkedAt: Long = 0L,
    val lastVerifiedAt: Long = 0L,
    val lastError: String = ""
) {
    companion object {
        fun empty(provider: ProviderId) = Account(provider = provider)
    }
}

enum class QuotaSource { NONE, ESTIMATED, OFFICIAL }

/** 单个账号的额度状态（官方数据 + 本地滚动窗口估算）。 */
data class QuotaState(
    val provider: ProviderId,
    /** 官方窗口已用比例 0..1，未知为 null */
    val officialUsedFraction: Float? = null,
    /** 官方窗口长度（毫秒） */
    val officialWindowMillis: Long? = null,
    /** 官方窗口重置时刻（epoch ms） */
    val officialResetAt: Long? = null,
    /** 官方细节描述，例如 “7 天窗口已用 42%” */
    val officialDetail: String = "",
    val source: QuotaSource = QuotaSource.NONE,
    /** 本地估算窗口起点 */
    val localWindowStart: Long = 0L,
    /** 本地估算窗口内已发送条数 */
    val localSent: Int = 0,
    /** 该账号窗口内的条数上限（由设置决定） */
    val localLimit: Int = 45,
    val lastSendAt: Long = 0L,
    val lastSendOk: Boolean = true,
    val lastSendMessage: String = "",
    val fetchedAt: Long = 0L
)

/** 网络请求返回的官方额度增量。 */
data class QuotaPatch(
    val officialUsedFraction: Float? = null,
    val officialWindowMillis: Long? = null,
    val officialResetAt: Long? = null,
    val officialDetail: String = "",
    val plan: String = ""
)

sealed interface ConnectResult {
    data class Ok(val plan: String = "", val label: String = "", val quota: QuotaPatch? = null) : ConnectResult
    data class Fail(val message: String) : ConnectResult
}

sealed interface QuotaFetch {
    data class Ok(val patch: QuotaPatch) : QuotaFetch
    /** 该服务没有可查的官方额度接口，走本地估算 */
    data class Unsupported(val message: String = "") : QuotaFetch
    data class Fail(val message: String, val unauthorized: Boolean = false) : QuotaFetch
}

data class SendOutcome(
    val ok: Boolean,
    val detail: String = "",
    val unauthorized: Boolean = false
)

/** 用户设置的「定时发送」规则。 */
enum class ScheduleMode(val key: String, val label: String, val hint: String) {
    FOLLOW_RESET("follow_reset", "跟随额度窗口", "额度窗口重置后自动发一条，把 5 小时周期续上"),
    INTERVAL("interval", "固定间隔", "每隔 N 分钟发一条"),
    DAILY("daily", "每天定时", "每天在固定时间发一条");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: FOLLOW_RESET
    }
}

data class ScheduleRule(
    val id: String,
    val provider: ProviderId,
    val enabled: Boolean = true,
    val mode: ScheduleMode = ScheduleMode.FOLLOW_RESET,
    val intervalMinutes: Int = 270,
    val atHour: Int = 9,
    val atMinute: Int = 0,
    /** 保活消息内容；为空时使用 Provider 的默认问候 */
    val message: String = "",
    val syncQuota: Boolean = true,
    val lastRunAt: Long = 0L,
    val nextRunAt: Long = 0L,
    val lastResult: String = ""
)

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    DARK("dark", "深色"),
    LIGHT("light", "浅色");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class AccentColor(val key: String, val label: String, val argb: Long) {
    MINT("mint", "薄荷", 0xFF57E6C0),
    VIOLET("violet", "紫罗兰", 0xFF9E8CFF),
    AZURE("azure", "天青", 0xFF6CB8FF),
    ROSE("rose", "玫瑰", 0xFFFF8AA8),
    AMBER("amber", "琥珀", 0xFFFFC46B);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: MINT
    }
}

enum class ProxyType(val key: String, val label: String) {
    HTTP("http", "HTTP"),
    SOCKS("socks", "SOCKS5")
}

/** 国内直连 claude.ai / chatgpt.com 通常需要代理，这里允许用户填自己的代理。 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 7890,
    val username: String = "",
    val password: String = ""
) {
    val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
}

data class Settings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: AccentColor = AccentColor.MINT,
    /** 1.0 为标准玻璃；0.4 为轻玻璃（低端机） */
    val glassIntensity: Float = 1f,
    /** 后台常驻总开关（用户希望关掉时保留配置） */
    val backgroundEnabled: Boolean = true,
    /** 常驻通知（Android 13+ 需要通知权限） */
    val ongoingNotification: Boolean = true,
    /** 发送结果提醒 */
    val resultNotification: Boolean = true,
    /** 额度窗口小时数，默认 5 */
    val quotaWindowHours: Int = 5,
    val claudeLimit: Int = 45,
    val chatgptLimit: Int = 80,
    val geminiLimit: Int = 100,
    /** 自动刷新官方额度的间隔（分钟，0 = 只手动） */
    val autoRefreshMinutes: Int = 15,
    val proxy: ProxyConfig = ProxyConfig(),
    /** 只在 Wi-Fi 下发送（省流量，但后台可靠性略降） */
    val wifiOnly: Boolean = false,
    /** 首次进入后台说明是否已读 */
    val onboardingDone: Boolean = false,
    /** 已引导过电池优化 / 自启动 */
    val batteryGuideDone: Boolean = false,
    val exactAlarmGuideDone: Boolean = false
) {
    fun limitFor(provider: ProviderId): Int = when (provider) {
        ProviderId.CLAUDE -> claudeLimit
        ProviderId.CHATGPT -> chatgptLimit
        ProviderId.GEMINI -> geminiLimit
    }

    fun windowMillis(): Long = quotaWindowHours.coerceIn(1, 24) * 60L * 60L * 1000L

    fun refreshMillis(): Long = autoRefreshMinutes.coerceIn(0, 240) * 60L * 1000L
}

/** 一次发送/刷新记录，用于时间线与通知。 */
data class SendEvent(
    val id: String,
    val provider: ProviderId,
    val at: Long,
    val ok: Boolean,
    val text: String,
    val detail: String = "",
    val kind: EventKind = EventKind.SEND
)

enum class EventKind(val key: String, val label: String) {
    SEND("send", "保活发送"),
    REFRESH("refresh", "额度刷新"),
    LINK("link", "账号连接"),
    ERROR("error", "异常");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SEND
    }
}

/** UI 侧展示额度用的派生视图。 */
data class QuotaView(
    val usedFraction: Float,
    val remainingFraction: Float,
    val remainingCount: Int?,
    val totalCount: Int?,
    val resetAt: Long?,
    val source: QuotaSource,
    val sourceLabel: String,
    val windowMillis: Long,
    val low: Boolean,
    val detail: String
)

/** 每个 Provider 的默认保活消息（尽量短、不打扰会话）。 */
object DefaultMessages {
    fun forProvider(provider: ProviderId): String = when (provider) {
        ProviderId.CLAUDE -> "Hi"
        ProviderId.CHATGPT -> "Hi"
        ProviderId.GEMINI -> "Hi"
    }
}
