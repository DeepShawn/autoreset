package com.aicode.quotasentry.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.ui.theme.QsDims
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ────────────────────────────────────────────────────────────────────────────
// 背景
// ────────────────────────────────────────────────────────────────────────────

/**
 * 流动光斑背景。
 *
 * 性能要点：光斑只画一次在静态 Canvas 上，动画只改父层 [graphicsLayer] 的位移/缩放，
 * 不逐帧重绘画布、也不触发页面重组。整个背景最多两层 Canvas（光斑 + 边缘压暗）。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val p = QsTheme.palette
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 34_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
    ) {
        // 只动 graphicsLayer：光斑画布本身不重建。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val angle = phase * 2f * PI.toFloat()
                    translationX = sin(angle) * 26.dp.toPx()
                    translationY = cos(angle * 0.83f) * 20.dp.toPx()
                    scaleX = 1.22f
                    scaleY = 1.22f
                }
        ) {
            Canvas(Modifier.fillMaxSize()) { drawAuroraBlobs(p) }
        }
        // 静态压暗 + 顶部环境光，让玻璃卡片从背景里“浮”起来。
        Canvas(Modifier.fillMaxSize()) { drawAuroraVignette(p) }
    }
}

private fun DrawScope.drawAuroraBlobs(p: com.aicode.quotasentry.ui.theme.QsPalette) {
    val w = size.width
    val h = size.height
    fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
        if (color.alpha <= 0.001f) return
        drawRect(
            Brush.radialGradient(
                0f to color,
                0.55f to color.copy(alpha = color.alpha * 0.45f),
                1f to Color.Transparent,
                center = Offset(w * cx, h * cy),
                radius = radius
            )
        )
    }
    blob(p.blobA, 0.02f, 0.04f, w * 0.86f)
    blob(p.blobB, 1.02f, 0.38f, w * 0.76f)
    blob(p.blobC, 0.48f, 1.04f, w * 0.98f)
    blob(p.accent.copy(alpha = if (p.dark) p.intensity * 0.05f else p.intensity * 0.07f), 0.94f, 0.92f, w * 0.42f)
    blob(p.accent2.copy(alpha = if (p.dark) p.intensity * 0.045f else p.intensity * 0.06f), 0.04f, 0.66f, w * 0.36f)
}

private fun DrawScope.drawAuroraVignette(p: com.aicode.quotasentry.ui.theme.QsPalette) {
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.52f to Color.Transparent,
            1f to p.shadow.copy(alpha = if (p.dark) 0.30f else 0.10f),
            center = Offset(size.width * 0.5f, size.height * 0.36f),
            radius = size.maxDimension * 0.82f
        )
    )
    drawRect(
        Brush.verticalGradient(
            0.55f to Color.Transparent,
            1f to p.bgBottom.copy(alpha = if (p.dark) 0.62f else 0.30f)
        )
    )
    drawRect(
        Brush.verticalGradient(
            0f to p.accent.copy(alpha = if (p.dark) p.intensity * 0.05f else p.intensity * 0.06f),
            0.32f to Color.Transparent
        )
    )
}

// ────────────────────────────────────────────────────────────────────────────
// 玻璃材质
// ────────────────────────────────────────────────────────────────────────────

/** 玻璃材质 Modifier：半透明渐变填充 + 1dp 渐变描边 + 顶部镜面高光 + 柔和阴影。 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(QsDims.cardRadius),
    elevation: Dp = 12.dp,
    fillTop: Color = Color.Unspecified,
    fillBottom: Color = Color.Unspecified,
    glow: Color = Color.Transparent,
    stroke: Boolean = true
): Modifier {
    val p = QsTheme.palette
    var m = this
    if (elevation > 0.dp) {
        m = m.shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = p.shadow.copy(alpha = p.shadow.alpha * 0.55f),
            spotColor = p.shadow
        )
    }
    return m
        .clip(shape)
        .drawBehind {
            val fill = when {
                fillTop == Color.Unspecified -> p.glassBrush()
                fillBottom == Color.Unspecified -> Brush.verticalGradient(listOf(fillTop, fillTop))
                else -> Brush.verticalGradient(listOf(fillTop, fillBottom))
            }
            drawRect(fill)
            // 顶部内侧 1dp 镜面高光
            drawRect(
                Brush.verticalGradient(
                    0f to p.highlight,
                    0.30f to p.highlight.copy(alpha = p.highlight.alpha * 0.22f),
                    0.62f to Color.Transparent
                )
            )
            // 左上斜向反光，模拟玻璃折射
            drawRect(
                Brush.linearGradient(
                    0f to p.highlight.copy(alpha = p.highlight.alpha * 0.55f),
                    1f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 1.05f, size.height * 1.25f)
                )
            )
            if (glow != Color.Transparent) {
                drawRect(
                    Brush.radialGradient(
                        0f to glow,
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.92f, size.height * 1.04f),
                        radius = size.maxDimension * 0.85f
                    )
                )
            }
        }
        .then(
            if (stroke) {
                Modifier.border(1.dp, p.strokeBrush(), shape)
            } else {
                Modifier
            }
        )
}

/** 可点击的玻璃卡片，按压时 0.985 弹簧回弹。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(QsDims.cardRadius),
    elevation: Dp = 12.dp,
    padding: PaddingValues = PaddingValues(18.dp),
    glow: Color = Color.Transparent,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "cardScale"
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .glassSurface(shape = shape, elevation = elevation, glow = glow)
            .then(clickModifier)
            .padding(padding),
        content = content
    )
}

/** 圆形玻璃图标按钮（顶栏的历史/刷新/返回等）。 */
@Composable
fun GlassIconButton(
    icon: QsIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = QsTheme.palette.text,
    background: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "iconScale"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (background) Modifier.glassSurface(shape = CircleShape, elevation = 6.dp) else Modifier.clip(CircleShape))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        QsIcon(icon = icon, size = size * 0.46f, tint = tint)
    }
}

enum class GlassButtonStyle { PRIMARY, SECONDARY, DANGER }

/** 胶囊玻璃按钮：主按钮是强调色渐变 + 深色文字，次按钮是玻璃 + 强调色文字。 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassButtonStyle = GlassButtonStyle.PRIMARY,
    icon: QsIcon? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = QsDims.buttonHeight
) {
    val p = QsTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "buttonScale"
    )
    val shape = RoundedCornerShape(height / 2)
    val contentColor = when (style) {
        GlassButtonStyle.PRIMARY -> p.onAccent
        GlassButtonStyle.SECONDARY -> p.accent
        GlassButtonStyle.DANGER -> p.danger
    }.let { if (enabled) it else it.copy(alpha = 0.45f) }

    val baseModifier = modifier
        .height(height)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            when (style) {
                GlassButtonStyle.PRIMARY -> Modifier
                    .shadow(
                        elevation = if (enabled) 14.dp else 0.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = p.accent.copy(alpha = 0.28f),
                        spotColor = p.accent.copy(alpha = 0.42f)
                    )
                    .clip(shape)
                    .drawBehind {
                        drawRect(if (enabled) p.accentBrush() else Brush.linearGradient(listOf(p.accent.copy(alpha = 0.30f), p.accent2.copy(alpha = 0.30f))))
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.28f),
                                0.55f to Color.Transparent
                            )
                        )
                    }
                GlassButtonStyle.SECONDARY, GlassButtonStyle.DANGER -> Modifier
                    .glassSurface(shape = shape, elevation = 6.dp)
            }
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled && !loading,
            onClick = onClick
        )

    Row(
        modifier = baseModifier.padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            LoadingRing(ringSize = 16.dp, color = contentColor)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            QsIcon(icon = icon, size = 17.dp, tint = contentColor)
            Spacer(Modifier.width(8.dp))
        }
        MText(
            text = text,
            color = contentColor,
            style = QsType.bodyMedium,
            maxLines = 1
        )
    }
}

/** 小胶囊 chip，用于快捷词、模式选择、来源徽标。 */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: QsIcon? = null,
    height: Dp = 34.dp
) {
    val p = QsTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "chipScale"
    )
    val shape = RoundedCornerShape(height / 2)
    val contentColor = if (selected) p.onAccent else p.dim
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (selected) {
                    Modifier
                        .clip(shape)
                        .drawBehind { drawRect(p.accentBrush()) }
                } else {
                    Modifier.glassSurface(shape = shape, elevation = 3.dp)
                }
            )
            .then(clickModifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            QsIcon(icon = icon, size = 13.dp, tint = contentColor)
            Spacer(Modifier.width(6.dp))
        }
        MText(text = text, color = contentColor, style = QsType.caption, maxLines = 1)
    }
}

enum class PillTone { OK, WARN, DANGER, NEUTRAL, ACCENT }

/** 状态胶囊：小圆点 + 文字，可选点击（顶部守护状态）。 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    dotColor: Color? = null
) {
    val p = QsTheme.palette
    val tint = dotColor ?: when (tone) {
        PillTone.OK -> p.ok
        PillTone.WARN -> p.warn
        PillTone.DANGER -> p.danger
        PillTone.NEUTRAL -> p.dim
        PillTone.ACCENT -> p.accent
    }
    val textColor = if (tone == PillTone.NEUTRAL) p.dim else p.text
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "pillScale"
    )
    val shape = RoundedCornerShape(20.dp)
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .height(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glassSurface(shape = shape, elevation = 4.dp)
            .then(clickModifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 光晕 + 实心点
        Box(
            Modifier
                .size(14.dp)
                .drawBehind {
                    drawCircle(tint.copy(alpha = 0.22f), radius = size.minDimension / 2f)
                    drawCircle(tint, radius = size.minDimension * 0.24f)
                }
        )
        Spacer(Modifier.width(7.dp))
        MText(text = text, color = textColor, style = QsType.tiny, maxLines = 1)
    }
}

/** 细进度条（额度卡片下方）。 */
@Composable
fun ThinProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    brush: Brush? = null,
    trackColor: Color = QsTheme.palette.text.copy(alpha = 0.08f)
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 220f),
        label = "progress"
    )
    val fill = brush ?: QsTheme.palette.accentBrush()
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .drawBehind {
                drawRect(trackColor)
                if (animated > 0.001f) {
                    drawRect(fill, size = Size(size.width * animated, size.height))
                }
            }
    )
}

// ────────────────────────────────────────────────────────────────────────────
// 额度环
// ────────────────────────────────────────────────────────────────────────────

/**
 * Canvas 绘制的额度环：轨道 + 渐变进度弧 + 端点高光，中心放数值。
 * @param progress 剩余比例 0..1
 */
@Composable
fun QuotaRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 82.dp,
    strokeWidth: Dp = 7.dp,
    low: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val p = QsTheme.palette
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 190f),
        label = "ringProgress"
    )
    val endColor = when {
        low || animated <= 0.15f -> p.danger
        animated <= 0.35f -> p.warn
        else -> p.accent2
    }
    Box(modifier = modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val inset = sw / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            // 轨道
            drawArc(
                color = p.text.copy(alpha = 0.07f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
            val sweep = (animated * 360f).coerceAtLeast(2f)
            val brush = Brush.linearGradient(
                colors = listOf(p.accent, endColor),
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset)
            )
            drawArc(
                brush = brush,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
            // 端点高光：沿弧线终点放一颗亮珠 + 外发光
            val angle = Math.toRadians((-90f + sweep).toDouble())
            val radius = arcSize.width / 2f
            val cx = size.width / 2f + radius * cos(angle).toFloat()
            val cy = size.height / 2f + radius * sin(angle).toFloat()
            val dot = Offset(cx, cy)
            drawCircle(endColor.copy(alpha = 0.28f), radius = sw * 1.15f, center = dot)
            drawCircle(Color.White.copy(alpha = 0.92f), radius = sw * 0.34f, center = dot)
        }
        content()
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Provider Logo（全部 Path 自绘）
// ────────────────────────────────────────────────────────────────────────────

private val claudeColor = Color(0xFFD97757)
private val chatgptColor = Color(0xFF10A37F)
private val geminiA = Color(0xFF4C8DF6)
private val geminiB = Color(0xFF9B72F5)

/** 三家服务的品牌标记，全部用 Path / Canvas 手绘，不引入图标库。 */
@Composable
fun ProviderLogo(
    provider: ProviderId,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    monochrome: Boolean = false
) {
    val p = QsTheme.palette
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        when (provider) {
            ProviderId.CLAUDE -> {
                val tint = if (monochrome) p.text else claudeColor
                val rays = 12
                val inner = side * 0.10f
                for (i in 0 until rays) {
                    val angle = Math.toRadians((i * 360.0 / rays) - 90.0)
                    val long = i % 3 == 0
                    val outer = side * if (long) 0.46f else 0.38f
                    val start = Offset(
                        center.x + cos(angle).toFloat() * inner,
                        center.y + sin(angle).toFloat() * inner
                    )
                    val end = Offset(
                        center.x + cos(angle).toFloat() * outer,
                        center.y + sin(angle).toFloat() * outer
                    )
                    drawLine(
                        color = tint,
                        start = start,
                        end = end,
                        strokeWidth = side * if (long) 0.060f else 0.052f,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(tint, radius = side * 0.075f, center = center)
            }

            ProviderId.CHATGPT -> {
                val tint = if (monochrome) p.text else chatgptColor
                val petalLength = side * 0.40f
                val petalWidth = side * 0.235f
                for (i in 0 until 6) {
                    rotate(degrees = i * 60f, pivot = center) {
                        drawOval(
                            color = tint,
                            topLeft = Offset(
                                center.x - petalWidth / 2f,
                                center.y - petalLength - side * 0.035f
                            ),
                            size = Size(petalWidth, petalLength),
                            style = Stroke(width = side * 0.075f, cap = StrokeCap.Round)
                        )
                    }
                }
                drawCircle(tint.copy(alpha = 0.9f), radius = side * 0.085f, center = center)
            }

            ProviderId.GEMINI -> {
                val path = Path().apply {
                    val r = side * 0.47f
                    moveTo(center.x, center.y - r)
                    cubicTo(
                        center.x + r * 0.16f, center.y - r * 0.34f,
                        center.x + r * 0.34f, center.y - r * 0.16f,
                        center.x + r, center.y
                    )
                    cubicTo(
                        center.x + r * 0.34f, center.y + r * 0.16f,
                        center.x + r * 0.16f, center.y + r * 0.34f,
                        center.x, center.y + r
                    )
                    cubicTo(
                        center.x - r * 0.16f, center.y + r * 0.34f,
                        center.x - r * 0.34f, center.y + r * 0.16f,
                        center.x - r, center.y
                    )
                    cubicTo(
                        center.x - r * 0.34f, center.y - r * 0.16f,
                        center.x - r * 0.16f, center.y - r * 0.34f,
                        center.x, center.y - r
                    )
                    close()
                }
                if (monochrome) {
                    drawPath(path, color = p.text)
                } else {
                    drawPath(
                        path,
                        brush = Brush.linearGradient(
                            listOf(geminiA, geminiB),
                            start = Offset(center.x - side * 0.4f, center.y - side * 0.4f),
                            end = Offset(center.x + side * 0.4f, center.y + side * 0.4f)
                        )
                    )
                }
            }
        }
    }
}

/** 来源徽标：官方 / 估算 / 未知。 */
@Composable
fun SourceBadge(sourceLabel: String, official: Boolean, modifier: Modifier = Modifier) {
    val p = QsTheme.palette
    val tint = if (official) p.accent else p.dim
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(5.dp))
        MText(text = sourceLabel, color = tint, style = QsType.tiny, maxLines = 1)
    }
}
