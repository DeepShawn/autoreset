package com.aicode.quotasentry.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 全应用共享的唯一数据仓库：UI 与前台服务同进程，直接读写同一份 StateFlow，
 * 落盘用 SharedPreferences + org.json（不引入 Room / 序列化库，减小体积）。
 */
object Store {

    private const val PREFS_NAME = "quota_sentry_store"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_QUOTAS = "quotas"
    private const val KEY_RULES = "rules"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_EVENTS = "events"
    private const val KEY_SERVICE_RUNNING = "service_running"

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var initialized = false

    private val _accounts = MutableStateFlow<Map<ProviderId, Account>>(emptyMap())
    private val _quotas = MutableStateFlow<Map<ProviderId, QuotaState>>(emptyMap())
    private val _rules = MutableStateFlow<List<ScheduleRule>>(emptyList())
    private val _settings = MutableStateFlow(Settings())
    private val _events = MutableStateFlow<List<SendEvent>>(emptyList())
    private val _serviceRunning = MutableStateFlow(false)
    private val _initializedFlow = MutableStateFlow(false)

    val accounts: StateFlow<Map<ProviderId, Account>> = _accounts.asStateFlow()
    val quotas: StateFlow<Map<ProviderId, QuotaState>> = _quotas.asStateFlow()
    val rules: StateFlow<List<ScheduleRule>> = _rules.asStateFlow()
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val events: StateFlow<List<SendEvent>> = _events.asStateFlow()
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()
    val ready: StateFlow<Boolean> = _initializedFlow.asStateFlow()

    /** 必须在 Application.onCreate 里调用一次。 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadLocked()
            initialized = true
            _initializedFlow.value = true
        }
    }

    fun account(provider: ProviderId): Account =
        _accounts.value[provider] ?: Account.empty(provider)

    fun quota(provider: ProviderId): QuotaState =
        _quotas.value[provider] ?: defaultQuota(provider, _settings.value)

    fun quotaFor(provider: ProviderId): QuotaState = quota(provider)

    fun settingsNow(): Settings = _settings.value

    fun rulesFor(provider: ProviderId): List<ScheduleRule> = _rules.value.filter { it.provider == provider }

    fun activeRuleFor(provider: ProviderId): ScheduleRule? =
        _rules.value.firstOrNull { it.provider == provider && it.enabled }

    fun viewFor(provider: ProviderId, now: Long = System.currentTimeMillis()): QuotaView =
        QuotaMath.view(quota(provider), _settings.value, now)

    fun updateAccount(provider: ProviderId, transform: (Account) -> Account): Account =
        synchronized(lock) {
            val current = account(provider)
            val next = transform(current).copy(provider = provider)
            _accounts.value = _accounts.value + (provider to next)
            persistAccountsLocked()
            next
        }

    fun setAccount(account: Account) {
        updateAccount(account.provider) { account }
    }

    fun updateQuota(provider: ProviderId, transform: (QuotaState) -> QuotaState): QuotaState =
        synchronized(lock) {
            val current = quota(provider)
            val next = transform(current).copy(provider = provider)
            _quotas.value = _quotas.value + (provider to next)
            persistQuotasLocked()
            next
        }

    fun setQuota(state: QuotaState) {
        synchronized(lock) {
            _quotas.value = _quotas.value + (state.provider to state)
            persistQuotasLocked()
        }
    }

    fun upsertRule(rule: ScheduleRule) {
        synchronized(lock) {
            val list = _rules.value.toMutableList()
            val index = list.indexOfFirst { it.id == rule.id }
            if (index >= 0) list[index] = rule else list.add(rule)
            _rules.value = list
            persistRulesLocked()
        }
    }

    fun updateRule(id: String, transform: (ScheduleRule) -> ScheduleRule) {
        synchronized(lock) {
            val list = _rules.value.map { if (it.id == id) transform(it) else it }
            _rules.value = list
            persistRulesLocked()
        }
    }

    fun deleteRule(id: String) {
        synchronized(lock) {
            _rules.value = _rules.value.filterNot { it.id == id }
            persistRulesLocked()
        }
    }

    fun ensureDefaultRule(provider: ProviderId): ScheduleRule {
        val existing = activeRuleFor(provider)
        if (existing != null) return existing
        val rule = ScheduleRule(
            id = UUID.randomUUID().toString(),
            provider = provider,
            mode = ScheduleMode.FOLLOW_RESET
        )
        upsertRule(rule)
        return rule
    }

    fun updateSettings(transform: (Settings) -> Settings): Settings =
        synchronized(lock) {
            val next = transform(_settings.value)
            _settings.value = next
            // 同步各账号的本地条数上限
            _quotas.value = _quotas.value.mapValues { (provider, quota) ->
                quota.copy(localLimit = next.limitFor(provider))
            }
            persistSettingsLocked()
            persistQuotasLocked()
            next
        }

    fun addEvent(event: SendEvent) {
        val next = (_events.value + event).takeLast(200)
        _events.value = next
        persistEventsLocked()
    }

    fun clearEvents() {
        _events.value = emptyList()
        persistEventsLocked()
    }

    fun markServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        prefs?.edit()?.putBoolean(KEY_SERVICE_RUNNING, running)?.apply()
    }

    /** 发一条操作记录（后台服务与 UI 共用）。 */
    fun record(
        provider: ProviderId,
        ok: Boolean,
        text: String,
        detail: String,
        kind: EventKind,
        at: Long = System.currentTimeMillis()
    ): SendEvent {
        val event = SendEvent(
            id = UUID.randomUUID().toString(),
            provider = provider,
            at = at,
            ok = ok,
            text = text,
            detail = detail,
            kind = kind
        )
        addEvent(event)
        return event
    }

    private fun defaultQuota(provider: ProviderId, settings: Settings): QuotaState =
        QuotaState(provider = provider, localLimit = settings.limitFor(provider), source = QuotaSource.NONE)

    // ---------------------------------------------------------------- 持久化

    private fun loadLocked() {
        val p = prefs ?: return
        val settings = runCatching { p.getString(KEY_SETTINGS, null)?.let(::decodeSettings) }.getOrNull()
            ?: Settings()
        _settings.value = settings

        val accounts = mutableMapOf<ProviderId, Account>()
        runCatching { p.getString(KEY_ACCOUNTS, null)?.let(::decodeAccounts) }.getOrNull()
            ?.forEach { (k, v) -> accounts[k] = v }
        ProviderId.ordered.forEach { provider ->
            val existing = accounts[provider]
            accounts[provider] = if (existing == null) Account.empty(provider) else existing
        }
        _accounts.value = accounts

        val quotas = mutableMapOf<ProviderId, QuotaState>()
        runCatching { p.getString(KEY_QUOTAS, null)?.let(::decodeQuotas) }.getOrNull()
            ?.forEach { (k, v) -> quotas[k] = v }
        ProviderId.ordered.forEach { provider ->
            val existing = quotas[provider]
            quotas[provider] = (existing ?: defaultQuota(provider, settings)).copy(
                provider = provider,
                localLimit = settings.limitFor(provider)
            )
        }
        _quotas.value = quotas

        _rules.value = runCatching { p.getString(KEY_RULES, null)?.let(::decodeRules) }.getOrNull()
            ?: emptyList()
        _events.value = runCatching { p.getString(KEY_EVENTS, null)?.let(::decodeEvents) }.getOrNull()
            ?: emptyList()
        _serviceRunning.value = p.getBoolean(KEY_SERVICE_RUNNING, false)
    }

    private fun persistAccountsLocked() {
        prefs?.edit()?.putString(KEY_ACCOUNTS, encodeAccounts(_accounts.value))?.apply()
    }

    private fun persistQuotasLocked() {
        prefs?.edit()?.putString(KEY_QUOTAS, encodeQuotas(_quotas.value))?.apply()
    }

    private fun persistRulesLocked() {
        prefs?.edit()?.putString(KEY_RULES, encodeRules(_rules.value))?.apply()
    }

    private fun persistSettingsLocked() {
        prefs?.edit()?.putString(KEY_SETTINGS, encodeSettings(_settings.value))?.apply()
    }

    private fun persistEventsLocked() {
        prefs?.edit()?.putString(KEY_EVENTS, encodeEvents(_events.value))?.apply()
    }

    // ---------------------------------------------------------------- 编码

    private fun encodeAccounts(map: Map<ProviderId, Account>): String {
        val root = JSONObject()
        map.forEach { (provider, account) ->
            root.put(provider.key, JSONObject().apply {
                put("linked", account.linked)
                put("plan", account.plan)
                put("label", account.label)
                put("linkedAt", account.linkedAt)
                put("lastVerifiedAt", account.lastVerifiedAt)
                put("lastError", account.lastError)
                put("cookie", account.credentials.cookie)
                put("accessToken", account.credentials.accessToken)
                put("extra", JSONObject(account.credentials.extra))
            })
        }
        return root.toString()
    }

    private fun decodeAccounts(raw: String): Map<ProviderId, Account> {
        val root = JSONObject(raw)
        val result = mutableMapOf<ProviderId, Account>()
        root.keys().forEach { key ->
            val provider = ProviderId.fromKey(key) ?: return@forEach
            val obj = root.optJSONObject(key) ?: return@forEach
            val extra = mutableMapOf<String, String>()
            obj.optJSONObject("extra")?.let { e ->
                e.keys().forEach { k -> extra[k] = e.optString(k) }
            }
            result[provider] = Account(
                provider = provider,
                credentials = Credentials(
                    cookie = obj.optString("cookie"),
                    accessToken = obj.optString("accessToken"),
                    extra = extra
                ),
                linked = obj.optBoolean("linked"),
                plan = obj.optString("plan"),
                label = obj.optString("label"),
                linkedAt = obj.optLong("linkedAt"),
                lastVerifiedAt = obj.optLong("lastVerifiedAt"),
                lastError = obj.optString("lastError")
            )
        }
        return result
    }

    private fun encodeQuotas(map: Map<ProviderId, QuotaState>): String {
        val root = JSONObject()
        map.forEach { (provider, quota) ->
            root.put(provider.key, JSONObject().apply {
                put("officialUsedFraction", quota.officialUsedFraction?.toDouble() ?: JSONObject.NULL)
                put("officialWindowMillis", quota.officialWindowMillis ?: JSONObject.NULL)
                put("officialResetAt", quota.officialResetAt ?: JSONObject.NULL)
                put("officialDetail", quota.officialDetail)
                put("source", quota.source.name)
                put("localWindowStart", quota.localWindowStart)
                put("localSent", quota.localSent)
                put("localLimit", quota.localLimit)
                put("lastSendAt", quota.lastSendAt)
                put("lastSendOk", quota.lastSendOk)
                put("lastSendMessage", quota.lastSendMessage)
                put("fetchedAt", quota.fetchedAt)
            })
        }
        return root.toString()
    }

    private fun decodeQuotas(raw: String): Map<ProviderId, QuotaState> {
        val root = JSONObject(raw)
        val result = mutableMapOf<ProviderId, QuotaState>()
        root.keys().forEach { key ->
            val provider = ProviderId.fromKey(key) ?: return@forEach
            val obj = root.optJSONObject(key) ?: return@forEach
            result[provider] = QuotaState(
                provider = provider,
                officialUsedFraction = if (obj.isNull("officialUsedFraction")) null else obj.optDouble("officialUsedFraction").toFloat(),
                officialWindowMillis = if (obj.isNull("officialWindowMillis")) null else obj.optLong("officialWindowMillis"),
                officialResetAt = if (obj.isNull("officialResetAt")) null else obj.optLong("officialResetAt"),
                officialDetail = obj.optString("officialDetail"),
                source = runCatching { QuotaSource.valueOf(obj.optString("source")) }.getOrDefault(QuotaSource.NONE),
                localWindowStart = obj.optLong("localWindowStart"),
                localSent = obj.optInt("localSent"),
                localLimit = obj.optInt("localLimit"),
                lastSendAt = obj.optLong("lastSendAt"),
                lastSendOk = obj.optBoolean("lastSendOk", true),
                lastSendMessage = obj.optString("lastSendMessage"),
                fetchedAt = obj.optLong("fetchedAt")
            )
        }
        return result
    }

    private fun encodeRules(rules: List<ScheduleRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().apply {
                put("id", rule.id)
                put("provider", rule.provider.key)
                put("enabled", rule.enabled)
                put("mode", rule.mode.key)
                put("intervalMinutes", rule.intervalMinutes)
                put("atHour", rule.atHour)
                put("atMinute", rule.atMinute)
                put("message", rule.message)
                put("syncQuota", rule.syncQuota)
                put("lastRunAt", rule.lastRunAt)
                put("nextRunAt", rule.nextRunAt)
                put("lastResult", rule.lastResult)
            })
        }
        return array.toString()
    }

    private fun decodeRules(raw: String): List<ScheduleRule> {
        val array = JSONArray(raw)
        val rules = mutableListOf<ScheduleRule>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val provider = ProviderId.fromKey(obj.optString("provider")) ?: continue
            rules.add(
                ScheduleRule(
                    id = obj.optString("id"),
                    provider = provider,
                    enabled = obj.optBoolean("enabled", true),
                    mode = ScheduleMode.fromKey(obj.optString("mode")),
                    intervalMinutes = obj.optInt("intervalMinutes", 270),
                    atHour = obj.optInt("atHour", 9),
                    atMinute = obj.optInt("atMinute", 0),
                    message = obj.optString("message"),
                    syncQuota = obj.optBoolean("syncQuota", true),
                    lastRunAt = obj.optLong("lastRunAt"),
                    nextRunAt = obj.optLong("nextRunAt"),
                    lastResult = obj.optString("lastResult")
                )
            )
        }
        return rules
    }

    private fun encodeSettings(s: Settings): String = JSONObject().apply {
        put("themeMode", s.themeMode.key)
        put("accent", s.accent.key)
        put("glassIntensity", s.glassIntensity.toDouble())
        put("backgroundEnabled", s.backgroundEnabled)
        put("ongoingNotification", s.ongoingNotification)
        put("resultNotification", s.resultNotification)
        put("quotaWindowHours", s.quotaWindowHours)
        put("claudeLimit", s.claudeLimit)
        put("chatgptLimit", s.chatgptLimit)
        put("geminiLimit", s.geminiLimit)
        put("autoRefreshMinutes", s.autoRefreshMinutes)
        put("wifiOnly", s.wifiOnly)
        put("onboardingDone", s.onboardingDone)
        put("batteryGuideDone", s.batteryGuideDone)
        put("exactAlarmGuideDone", s.exactAlarmGuideDone)
        put("proxy", JSONObject().apply {
            put("enabled", s.proxy.enabled)
            put("type", s.proxy.type.key)
            put("host", s.proxy.host)
            put("port", s.proxy.port)
            put("username", s.proxy.username)
            put("password", s.proxy.password)
        })
    }.toString()

    private fun decodeSettings(raw: String): Settings {
        val obj = JSONObject(raw)
        val proxyObj = obj.optJSONObject("proxy")
        return Settings(
            themeMode = ThemeMode.fromKey(obj.optString("themeMode")),
            accent = AccentColor.fromKey(obj.optString("accent")),
            glassIntensity = obj.optDouble("glassIntensity", 1.0).toFloat(),
            backgroundEnabled = obj.optBoolean("backgroundEnabled", true),
            ongoingNotification = obj.optBoolean("ongoingNotification", true),
            resultNotification = obj.optBoolean("resultNotification", true),
            quotaWindowHours = obj.optInt("quotaWindowHours", 5),
            claudeLimit = obj.optInt("claudeLimit", 45),
            chatgptLimit = obj.optInt("chatgptLimit", 80),
            geminiLimit = obj.optInt("geminiLimit", 100),
            autoRefreshMinutes = obj.optInt("autoRefreshMinutes", 15),
            wifiOnly = obj.optBoolean("wifiOnly", false),
            onboardingDone = obj.optBoolean("onboardingDone", false),
            batteryGuideDone = obj.optBoolean("batteryGuideDone", false),
            exactAlarmGuideDone = obj.optBoolean("exactAlarmGuideDone", false),
            proxy = ProxyConfig(
                enabled = proxyObj?.optBoolean("enabled") ?: false,
                type = if (proxyObj?.optString("type") == ProxyType.SOCKS.key) ProxyType.SOCKS else ProxyType.HTTP,
                host = proxyObj?.optString("host").orEmpty(),
                port = proxyObj?.optInt("port", 7890) ?: 7890,
                username = proxyObj?.optString("username").orEmpty(),
                password = proxyObj?.optString("password").orEmpty()
            )
        )
    }

    private fun encodeEvents(events: List<SendEvent>): String {
        val array = JSONArray()
        events.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id)
                put("provider", e.provider.key)
                put("at", e.at)
                put("ok", e.ok)
                put("text", e.text)
                put("detail", e.detail)
                put("kind", e.kind.key)
            })
        }
        return array.toString()
    }

    private fun decodeEvents(raw: String): List<SendEvent> {
        val array = JSONArray(raw)
        val events = mutableListOf<SendEvent>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val provider = ProviderId.fromKey(obj.optString("provider")) ?: continue
            events.add(
                SendEvent(
                    id = obj.optString("id"),
                    provider = provider,
                    at = obj.optLong("at"),
                    ok = obj.optBoolean("ok"),
                    text = obj.optString("text"),
                    detail = obj.optString("detail"),
                    kind = EventKind.fromKey(obj.optString("kind"))
                )
            )
        }
        return events
    }
}
