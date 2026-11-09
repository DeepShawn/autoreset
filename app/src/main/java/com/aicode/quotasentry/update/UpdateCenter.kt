package com.aicode.quotasentry.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aicode.quotasentry.BuildConfig
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.data.HttpEngine
import com.aicode.quotasentry.data.HttpResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** 一个可用的新版本。 */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val htmlUrl: String,
    val publishedAt: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * 从本仓库的 GitHub Release 检查并安装新版本。
 *
 * - 启动时自动检查（6 小时内最多一次），设置页也可手动检查；
 * - 下载走用户配置的代理，APK 存到 cacheDir/updates/，经 FileProvider 交给系统安装器；
 * - 安装前会检查「安装未知应用」权限，未授权时跳到系统设置，不静默失败。
 */
object UpdateCenter {

    const val REPO = "DeepShawn/autoreset"
    const val RELEASES_PAGE = "https://github.com/DeepShawn/autoreset/releases/latest"
    private const val API_URL =
        "https://api.github.com/repos/DeepShawn/autoreset/releases/latest"
    private const val PREFS = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val AUTO_CHECK_INTERVAL = 6L * 60L * 60L * 1000L

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    /** App 冷启动时调用：距离上次检查超过 6 小时才真正联网。 */
    fun maybeAutoCheck(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL) return
        checkInternal(app, manual = false)
    }

    /** 用户手动点「检查更新」。 */
    fun check(context: Context) {
        checkInternal(context.applicationContext, manual = true)
    }

    fun downloadAndInstall(context: Context) {
        val app = context.applicationContext
        val info = (_state.value as? UpdateState.Available)?.info
            ?: (_state.value as? UpdateState.Downloading)?.info
            ?: return
        if (!canInstall(app)) {
            openInstallPermissionSettings(app)
            _state.value = UpdateState.Error("请先允许「安装未知应用」，允许后回来再点一次")
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val engine = HttpEngine(Store.settingsNow().proxy)
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            // 清掉上一次下载残留
            runCatching { dir.listFiles()?.forEach { it.delete() } }
            val dest = File(dir, "QuotaSentry-${info.versionName}.apk")
            _state.value = UpdateState.Downloading(info, 0f)
            val result = engine.downloadTo(
                url = info.downloadUrl,
                dest = dest,
                headers = mapOf(
                    "Accept" to "application/octet-stream",
                    "User-Agent" to "QuotaSentry-Updater"
                )
            ) { downloaded, total ->
                val progress = if (total > 0) {
                    (downloaded.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    -1f
                }
                _state.value = UpdateState.Downloading(info, progress)
            }
            when (result) {
                is HttpResult.Ok -> {
                    if (dest.length() <= 0L) {
                        _state.value = UpdateState.Error("下载到的文件为空，请重试")
                    } else {
                        launchInstaller(app, dest)
                    }
                }

                is HttpResult.Fail -> {
                    runCatching { dest.delete() }
                    _state.value = UpdateState.Error(result.message)
                }
            }
        }
    }

    /** 用户关掉更新弹窗。 */
    fun dismiss() {
        when (_state.value) {
            is UpdateState.Available, is UpdateState.Error, is UpdateState.UpToDate -> {
                _state.value = UpdateState.Idle
            }

            else -> Unit
        }
    }

    fun openReleasesPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)

    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun checkInternal(context: Context, manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val engine = HttpEngine(Store.settingsNow().proxy)
            val result = engine.get(
                API_URL,
                mapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to "QuotaSentry-Updater"
                )
            )
            when (result) {
                is HttpResult.Fail -> {
                    _state.value = if (manual) {
                        UpdateState.Error(result.message)
                    } else {
                        UpdateState.Idle
                    }
                }

                is HttpResult.Ok -> {
                    val response = result.response
                    if (!response.ok) {
                        _state.value = if (manual) {
                            UpdateState.Error(
                                if (response.code == 403 || response.code == 429) {
                                    "GitHub 接口访问受限（HTTP ${response.code}），请稍后再试"
                                } else {
                                    "检查更新失败（HTTP ${response.code}）"
                                }
                            )
                        } else {
                            UpdateState.Idle
                        }
                        return@launch
                    }
                    val info = runCatching { UpdateParser.parseRelease(response.body) }.getOrNull()
                    if (info == null) {
                        _state.value = if (manual) UpdateState.Error("更新信息解析失败，请到 Releases 页面手动下载") else UpdateState.Idle
                        return@launch
                    }
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    _state.value = if (UpdateParser.isNewer(info.versionName, currentVersion)) {
                        UpdateState.Available(info)
                    } else {
                        UpdateState.UpToDate(currentVersion)
                    }
                }
            }
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            _state.value = UpdateState.Error("请先允许「安装未知应用」，允许后回来再点一次")
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            _state.value = UpdateState.Idle
        }.onFailure {
            _state.value = UpdateState.Error("无法启动安装器：${it.message ?: "未知原因"}")
        }
    }
}

/** GitHub Release JSON 的解析与版本比较（纯函数，方便单测）。 */
object UpdateParser {

    fun parseRelease(json: String): UpdateInfo? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = root.optString("tag_name").trim().ifBlank { return null }
        val version = normalizeVersion(tag)
        if (version.isBlank()) return null
        val assets = root.optJSONArray("assets")
        var downloadUrl = ""
        var size = 0L
        if (assets != null) {
            val apks = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                .sortedByDescending { it.optString("name").contains("arm64", ignoreCase = true) }
            val first = apks.firstOrNull() ?: return null
            downloadUrl = first.optString("browser_download_url")
            size = first.optLong("size")
        }
        if (downloadUrl.isBlank()) return null
        return UpdateInfo(
            versionName = version,
            notes = root.optString("body").trim(),
            sizeBytes = size,
            downloadUrl = downloadUrl,
            htmlUrl = root.optString("html_url"),
            publishedAt = root.optString("published_at")
        )
    }

    fun normalizeVersion(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").trim()

    fun versionParts(version: String): List<Int> =
        normalizeVersion(version)
            .split('.', '-', '+', '_')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** remote 比 current 新才返回 true；支持 1.2 / 1.2.3 / v1.2.3 形式。 */
    fun isNewer(remote: String, current: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(current)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 把 content-length 转成可读大小。 */
    fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> "未知大小"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
