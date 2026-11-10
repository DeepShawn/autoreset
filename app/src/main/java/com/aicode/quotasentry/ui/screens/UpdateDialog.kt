package com.aicode.quotasentry.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aicode.quotasentry.ui.components.GlassButton
import com.aicode.quotasentry.ui.components.GlassButtonStyle
import com.aicode.quotasentry.ui.components.GlassCard
import com.aicode.quotasentry.ui.components.GlassChip
import com.aicode.quotasentry.ui.components.GlassSheet
import com.aicode.quotasentry.ui.components.LoadingRing
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.components.QsIcon
import com.aicode.quotasentry.ui.components.ThinProgressBar
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import com.aicode.quotasentry.update.UpdateCenter
import com.aicode.quotasentry.update.UpdateInfo
import com.aicode.quotasentry.update.UpdateParser
import com.aicode.quotasentry.update.UpdateState
import kotlin.math.roundToInt

/**
 * 自动更新弹窗宿主（挂在 [com.aicode.quotasentry.ui.QuotaSentryRoot] 上）。
 *
 * - Available：显示新版本说明 + 下载并安装 / 查看 Release / 稍后
 * - Downloading：进度条 + 百分比，下载中不可取消（由 UpdateCenter 负责实际下载）
 * - Error：错误原因 + 重试 / 手动下载 / 稍后
 * - 关闭动画期间用 lastVisible 保留上一帧内容，避免弹窗内容闪空。
 */
@Composable
fun UpdateDialogHost() {
    val p = QsTheme.palette
    val context = LocalContext.current
    val state by UpdateCenter.state.collectAsState()

    var lastVisible by remember { mutableStateOf<UpdateState?>(null) }
    LaunchedEffect(state) {
        if (state !is UpdateState.Idle) lastVisible = state
    }

    val open = state is UpdateState.Available ||
        state is UpdateState.Downloading ||
        state is UpdateState.Error
    val display: UpdateState? = if (open) state else lastVisible
    val info: UpdateInfo? = when (display) {
        is UpdateState.Available -> display.info
        is UpdateState.Downloading -> display.info
        else -> null
    }

    GlassSheet(
        open = open,
        onDismiss = { UpdateCenter.dismiss() },
        title = if (info != null) "发现新版本 v${info.versionName}" else "检查更新",
        subtitle = when (display) {
            is UpdateState.Available -> "当前 v${UpdateCenter.currentVersion} → 最新 v${info?.versionName.orEmpty()}"
            is UpdateState.Downloading -> "正在下载更新包，请保持网络畅通"
            is UpdateState.Error -> "更新没有完成"
            else -> ""
        }
    ) {
        when (display) {
            is UpdateState.Available -> AvailableContent(info = display.info)
            is UpdateState.Downloading -> DownloadingContent(info = display.info, progress = display.progress)
            is UpdateState.Error -> ErrorContent(message = display.message)
            else -> Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AvailableContent(info: UpdateInfo) {
    val p = QsTheme.palette
    val context = LocalContext.current
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MText(text = "更新内容", color = p.text, style = QsType.sectionTitle, modifier = Modifier.weight(1f))
        MText(
            text = UpdateParser.humanSize(info.sizeBytes),
            color = p.dim,
            style = QsType.caption,
            maxLines = 1
        )
    }
    Spacer(Modifier.height(8.dp))
    GlassCard(
        shape = RoundedCornerShape(18.dp),
        elevation = 0.dp,
        padding = PaddingValues(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
        ) {
            MText(
                text = info.notes.ifBlank { "本次更新没有附更新说明。" },
                color = p.dim,
                style = QsType.caption
            )
        }
    }
    if (info.publishedAt.isNotBlank()) {
        Spacer(Modifier.height(7.dp))
        MText(
            text = "发布时间：" + info.publishedAt.take(10),
            color = p.faint,
            style = QsType.tiny
        )
    }
    Spacer(Modifier.height(16.dp))
    GlassButton(
        text = "下载并安装",
        icon = QsIcon.DOWNLOAD,
        onClick = { UpdateCenter.downloadAndInstall(context) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassButton(
            text = "Release 页面",
            icon = QsIcon.GLOBE,
            style = GlassButtonStyle.SECONDARY,
            onClick = { UpdateCenter.openReleasesPage(context) },
            modifier = Modifier.weight(1f),
            height = 46.dp
        )
        GlassButton(
            text = "稍后",
            style = GlassButtonStyle.SECONDARY,
            onClick = { UpdateCenter.dismiss() },
            modifier = Modifier.weight(1f),
            height = 46.dp
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DownloadingContent(info: UpdateInfo, progress: Float) {
    val p = QsTheme.palette
    Spacer(Modifier.height(12.dp))
    MText(
        text = "正在下载 v${info.versionName} · " + UpdateParser.humanSize(info.sizeBytes),
        color = p.text,
        style = QsType.bodyMedium
    )
    Spacer(Modifier.height(10.dp))
    if (progress >= 0f) {
        ThinProgressBar(progress = progress, height = 8.dp)
        Spacer(Modifier.height(7.dp))
        MText(
            text = "${(progress * 100).roundToInt()}% · 下载完成后会自动打开系统安装器",
            color = p.dim,
            style = QsType.caption
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingRing(ringSize = 16.dp, color = p.accent)
            Spacer(Modifier.width(8.dp))
            MText(text = "正在下载，服务端没有返回总大小…", color = p.dim, style = QsType.caption)
        }
    }
    Spacer(Modifier.height(12.dp))
    MText(
        text = "下载完成后会自动打开系统安装器，请稍等片刻。",
        color = p.faint,
        style = QsType.tiny
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ErrorContent(message: String) {
    val p = QsTheme.palette
    val context = LocalContext.current
    Spacer(Modifier.height(6.dp))
    GlassCard(
        shape = RoundedCornerShape(18.dp),
        elevation = 0.dp,
        glow = p.danger.copy(alpha = 0.10f)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            QsIcon(QsIcon.WARN, size = 16.dp, tint = p.danger)
            Spacer(Modifier.width(8.dp))
            MText(text = message, color = p.danger, style = QsType.caption)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassChip(
            text = "安装权限设置",
            icon = QsIcon.LOCK,
            onClick = { UpdateCenter.openInstallPermissionSettings(context) },
            height = 32.dp
        )
        GlassChip(
            text = "打开 Release 页面",
            icon = QsIcon.GLOBE,
            onClick = { UpdateCenter.openReleasesPage(context) },
            height = 32.dp
        )
    }
    Spacer(Modifier.height(16.dp))
    GlassButton(
        text = "重试",
        icon = QsIcon.REFRESH,
        onClick = { UpdateCenter.check(context) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    GlassButton(
        text = "稍后",
        style = GlassButtonStyle.SECONDARY,
        onClick = { UpdateCenter.dismiss() },
        modifier = Modifier.fillMaxWidth(),
        height = 46.dp
    )
    Spacer(Modifier.height(12.dp))
}
