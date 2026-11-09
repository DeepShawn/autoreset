package com.aicode.quotasentry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.aicode.quotasentry.core.AccentColor
import com.aicode.quotasentry.core.Settings
import com.aicode.quotasentry.core.ThemeMode

/**
 * Liquid Glass + MIUIX 的整套视觉 token。
 *
 * 这里刻意不依赖 Material / Material3：全部颜色、字级、玻璃材质参数都在本文件里手写，
 * 页面只通过 [LocalQsPalette] 取值，保证深色 / 浅色 / 跟随系统三套外观完全一致。
 */
@Immutable
data class QsPalette(
    /** 是否深色外观 */
    val dark: Boolean,
    /** 背景竖向渐变 */
    val bgTop: Color,
    val bgBottom: Color,
    /** 背景缓慢流动的光斑 */
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    /** 文字层级 */
    val text: Color,
    val dim: Color,
    val faint: Color,
    /** 强调色（来自 Settings.accent） */
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    /** 语义色 */
    val danger: Color,
    val warn: Color,
    val ok: Color,
    /** 玻璃材质：半透明渐变填充 */
    val glassTop: Color,
    val glassBottom: Color,
    /** 1dp 渐变描边 */
    val strokeTop: Color,
    val strokeBottom: Color,
    /** 顶部内侧镜面高光 */
    val highlight: Color,
    val shadow: Color,
    val divider: Color,
    /** BottomSheet / 悬浮层底色 */
    val sheetFill: Color,
    val scrim: Color,
    /** 0.4..1.0，来自 Settings.glassIntensity */
    val intensity: Float
) {
    val isLight: Boolean get() = !dark

    /** 强调色到第二强调色的渐变，用于主按钮 / 额度环 / 选中态。 */
    fun accentBrush(): Brush = Brush.linearGradient(listOf(accent, accent2))

    /** 玻璃卡片的竖向填充。 */
    fun glassBrush(): Brush = Brush.verticalGradient(listOf(glassTop, glassBottom))

    /** 玻璃卡片 1dp 斜向描边。 */
    fun strokeBrush(): Brush = Brush.linearGradient(listOf(strokeTop, strokeBottom))
}

/** 手写字级（无 Material 可用）。 */
object QsType {
    val hugeTitle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val largeTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val cardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp)
    val bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontSize = 13.sp)
    val caption = TextStyle(fontSize = 12.sp)
    val tiny = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val ringValue = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val ringUnit = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    val sectionTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** 主题兜底值（CompositionLocal 未提供时使用，保证单独预览也不崩）。 */
private val fallbackPalette: QsPalette = buildPalette(dark = true, accent = Color(0xFF57E6C0), intensity = 1f)

val LocalQsPalette = compositionLocalOf { fallbackPalette }

/** 便捷访问：`QsTheme.palette`。 */
object QsTheme {
    val palette: QsPalette
        @Composable get() = LocalQsPalette.current
}

/** 把 0xFF57E6C0 这类 ARGB 长整型转成 Compose Color。 */
fun accentColorOf(accent: AccentColor): Color = Color(accent.argb)

private fun secondAccent(accent: Color): Color {
    // 第二强调色取「强调色偏向天青」的混合色，让渐变有方向感但保持同一色系。
    return lerp(accent, Color(0xFF7FB4FF), 0.55f)
}

private fun buildPalette(dark: Boolean, accent: Color, intensity: Float): QsPalette {
    val i = intensity.coerceIn(0.4f, 1f)
    val accent2 = secondAccent(accent)
    return if (dark) {
        QsPalette(
            dark = true,
            bgTop = Color(0xFF0A0E16),
            bgBottom = Color(0xFF07090F),
            blobA = accent.copy(alpha = 0.16f * i),
            blobB = Color(0xFF4A7DFF).copy(alpha = 0.13f * i),
            blobC = Color(0xFF9E5CFF).copy(alpha = 0.10f * i),
            text = Color(0xFFF2F6FA),
            dim = Color(0xFF8B9AB0),
            faint = Color(0xFF5D6A7E),
            accent = accent,
            accent2 = accent2,
            onAccent = Color(0xFF04120E),
            danger = Color(0xFFFF6B7A),
            warn = Color(0xFFFFC46B),
            ok = Color(0xFF57E6C0),
            glassTop = Color.White.copy(alpha = 0.105f * i).compositeOver(accent.copy(alpha = 0.05f * i)),
            glassBottom = Color.White.copy(alpha = 0.04f * i).compositeOver(accent.copy(alpha = 0.028f * i)),
            strokeTop = Color.White.copy(alpha = 0.26f * i),
            strokeBottom = Color.White.copy(alpha = 0.04f),
            highlight = Color.White.copy(alpha = 0.18f * i),
            shadow = Color.Black.copy(alpha = 0.34f),
            divider = Color.White.copy(alpha = 0.06f),
            sheetFill = Color(0xFF0E131C).copy(alpha = 0.94f),
            scrim = Color.Black.copy(alpha = 0.52f),
            intensity = i
        )
    } else {
        QsPalette(
            dark = false,
            bgTop = Color(0xFFF4F7FB),
            bgBottom = Color(0xFFE9EEF5),
            blobA = accent.copy(alpha = 0.22f * i),
            blobB = Color(0xFF7FB4FF).copy(alpha = 0.20f * i),
            blobC = Color(0xFFC79CFF).copy(alpha = 0.16f * i),
            text = Color(0xFF101722),
            dim = Color(0xFF5C6B80),
            faint = Color(0xFF8593A6),
            accent = accent,
            accent2 = accent2,
            onAccent = Color(0xFFFFFFFF),
            danger = Color(0xFFD93A4E),
            warn = Color(0xFFB7791F),
            ok = Color(0xFF0F9D7A),
            glassTop = Color.White.copy(alpha = 0.60f * i).compositeOver(accent.copy(alpha = 0.045f)),
            glassBottom = Color.White.copy(alpha = 0.36f * i),
            strokeTop = Color.White.copy(alpha = 0.92f),
            strokeBottom = Color.White.copy(alpha = 0.30f),
            highlight = Color.White.copy(alpha = 0.75f),
            shadow = Color(0xFF2A3A52).copy(alpha = 0.16f),
            divider = Color(0xFF101722).copy(alpha = 0.06f),
            sheetFill = Color(0xFFF8FAFD).copy(alpha = 0.97f),
            scrim = Color(0xFF101722).copy(alpha = 0.38f),
            intensity = i
        )
    }
}

/**
 * 应用主题：读取 [Settings] 的 themeMode / accent / glassIntensity。
 *
 * UI 只在 [com.aicode.quotasentry.ui.QuotaSentryRoot] 里包一层，所有子页面通过
 * [LocalQsPalette] 取色，切换主题时整棵树一起换色。
 */
@Composable
fun QuotaSentryTheme(settings: Settings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val accent = accentColorOf(settings.accent)
    val palette = remember(dark, accent, settings.glassIntensity) {
        buildPalette(dark, accent, settings.glassIntensity)
    }
    CompositionLocalProvider(LocalQsPalette provides palette) {
        SyncSystemBars(dark)
        content()
    }
}

/**
 * 同步状态栏 / 导航栏图标的明暗，深色外观用浅色图标，浅色外观用深色图标。
 * MainActivity 已 enableEdgeToEdge()，这里只改图标外观，不改布局。
 */
@Composable
private fun SyncSystemBars(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        runCatching {
            val activity = view.context.findActivity() ?: return@runCatching
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

// ────────────────────────────────────────────────────────────────────────────
// 一些跨文件复用的文字样式 / 颜色工具
// ────────────────────────────────────────────────────────────────────────────

/** 卡片内的次级说明文字样式。 */
val TextStyle.dimCopy: TextStyle
    @Composable get() = copy(color = QsTheme.palette.dim)

/** 按剩余比例挑一个语义色：低额度变红，中间变黄。 */
@Composable
fun quotaTone(remaining: Float): Color {
    val p = QsTheme.palette
    return when {
        remaining <= 0.15f -> p.danger
        remaining <= 0.35f -> p.warn
        else -> p.accent
    }
}

/** 从强调色到语义色的渐变，进度条 / 环用。 */
@Composable
fun quotaBrush(remaining: Float): Brush {
    val p = QsTheme.palette
    val end = when {
        remaining <= 0.15f -> p.danger
        remaining <= 0.35f -> p.warn
        else -> p.accent2
    }
    return Brush.linearGradient(listOf(p.accent, end))
}

/** 统一间距常量，避免各页面各写一套。 */
object QsDims {
    val screenPadding = 20.dp
    val cardRadius = 28.dp
    val sectionRadius = 24.dp
    val tabBarHeight = 64.dp
    val contentBottom = 128.dp
    val rowHeight = 60.dp
    val leadingIcon = 36.dp
    val buttonHeight = 52.dp
}

/** 文本对齐便捷写法（BasicText 没有 textAlign 参数，走 TextStyle）。 */
fun TextStyle.aligned(align: TextAlign): TextStyle = copy(textAlign = align)
