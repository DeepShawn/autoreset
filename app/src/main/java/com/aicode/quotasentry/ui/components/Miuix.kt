package com.aicode.quotasentry.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicode.quotasentry.ui.theme.QsDims
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import kotlin.math.abs
import kotlin.math.roundToInt

// ────────────────────────────────────────────────────────────────────────────
// 文本（没有 Material 的 Text，统一走 BasicText）
// ────────────────────────────────────────────────────────────────────────────

/** 全局文字组件：只依赖 foundation 的 BasicText，样式来自手写的 [QsType]。 */
@Composable
fun MText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = QsTheme.palette.text,
    style: TextStyle = QsType.body,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit? = null
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = color,
            fontWeight = fontWeight ?: style.fontWeight,
            fontFamily = fontFamily ?: style.fontFamily,
            letterSpacing = letterSpacing ?: style.letterSpacing,
            textAlign = textAlign ?: style.textAlign
        ),
        maxLines = maxLines,
        overflow = overflow
    )
}

// ────────────────────────────────────────────────────────────────────────────
// 顶部标题
// ────────────────────────────────────────────────────────────────────────────

/** 大标题头部（MIUIX 的大标题 + 副标题），右侧放操作按钮。 */
@Composable
fun QsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    large: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val p = QsTheme.palette
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MText(
                    text = title,
                    style = if (large) QsType.largeTitle else QsType.title,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    MText(
                        text = subtitle,
                        style = QsType.caption,
                        color = p.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Switch（照 MIUIX 视觉参数重写）
// ────────────────────────────────────────────────────────────────────────────

/** MIUIX 风格开关：49×28dp 胶囊，滑块 20dp，弹簧 0.7/987 + 按下放大 1.127。 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val p = QsTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    val offset by animateDpAsState(
        targetValue = if (checked) 25.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 987f),
        label = "thumbOffset"
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (enabled && pressed) 1.127f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 987f),
        label = "thumbScale"
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> p.text.copy(alpha = 0.05f)
            checked -> p.accent
            else -> p.text.copy(alpha = 0.12f)
        },
        animationSpec = spring(dampingRatio = 0.99f, stiffness = 438f),
        label = "trackColor"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) p.onAccent else if (p.dark) Color(0xFFCBD5E1) else Color.White,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 700f),
        label = "thumbColor"
    )
    Box(
        modifier = modifier
            .size(49.dp, 28.dp)
            .clip(CircleShape)
            .drawBehind { drawCircle(trackColor, radius = size.minDimension / 2f) }
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = {
                            onCheckedChange(!checked)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(start = 0.dp)
                .offset(x = offset)
                .size(20.dp)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .shadow(2.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Slider
// ────────────────────────────────────────────────────────────────────────────

/** MIUIX 风格滑块：6dp 轨道 + 弹簧滑块，支持点按定位和拖动跟手。 */
@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val p = QsTheme.palette
    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { abs(it) > 0.0001f } ?: 1f
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val currentOnChange by rememberUpdatedState<(Float) -> Unit>(onValueChange)
    val currentOnFinished by rememberUpdatedState<(() -> Unit)?>(onValueChangeFinished)

    fun snap(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        if (steps <= 0) return f
        val segments = steps + 1
        return (f * segments).roundToInt().toFloat() / segments
    }

    fun emit(fraction: Float) {
        currentOnChange((start + snap(fraction) * span).coerceIn(start, end))
    }

    val targetFraction = ((value - start) / span).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 900f),
        label = "sliderFraction"
    )
    val shown = if (dragging) dragFraction else animatedFraction
    val thumbPx = with(LocalDensity.current) { 20.dp.toPx() }
    val trackHeight = 6.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(enabled, widthPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val slop = viewConfiguration.touchSlop
                    // 按下立即跟手定位（与 iOS / MIUIX 手感一致）
                    dragFraction = snap(startX / widthPx)
                    dragging = true
                    emit(dragFraction)
                    val pointer = down.id
                    var lastX = startX
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val dx = change.position.x - lastX
                        if (!moved && abs(change.position.x - startX) > slop) moved = true
                        if (moved) {
                            if (dx != 0f) {
                                dragFraction = snap(dragFraction + dx / widthPx)
                                emit(dragFraction)
                            }
                            change.consume()
                        }
                        lastX = change.position.x
                    }
                    dragging = false
                    currentOnFinished?.invoke()
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val cy = size.height / 2f
            val th = if (enabled) trackHeight.toPx() else trackHeight.toPx() * 0.7f
            val trackColor = p.text.copy(alpha = if (enabled) 0.10f else 0.05f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, cy - th / 2f),
                size = androidx.compose.ui.geometry.Size(size.width, th),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(th / 2f)
            )
            val fillW = (size.width * shown).coerceAtLeast(0f)
            if (fillW > 1f) {
                drawRoundRect(
                    brush = if (enabled) p.accentBrush() else Brush.linearGradient(listOf(p.text.copy(alpha = 0.3f), p.text.copy(alpha = 0.3f))),
                    topLeft = Offset(0f, cy - th / 2f),
                    size = androidx.compose.ui.geometry.Size(fillW, th),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(th / 2f)
                )
            }
        }
        // 滑块：白色胶囊 + 强调色内圈 + 阴影
        Box(
            Modifier
                .size(20.dp)
                .graphicsLayer {
                    translationX = shown * (widthPx - thumbPx)
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .shadow(3.dp, CircleShape, clip = false, ambientColor = p.shadow, spotColor = p.shadow)
                .clip(CircleShape)
                .background(if (enabled) Color.White else p.text.copy(alpha = 0.35f))
                .drawBehind {
                    drawCircle(
                        if (enabled) p.accent.copy(alpha = 0.85f) else p.text.copy(alpha = 0.25f),
                        radius = size.minDimension * 0.20f
                    )
                }
        )
    }
}

/** 分段选择器：胶囊底 + 流动的强调色指示块，切换用弹簧。 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() }
) {
    if (options.isEmpty()) return
    val p = QsTheme.palette
    val index = options.indexOf(selected).coerceAtLeast(0)
    val shape = RoundedCornerShape(15.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .drawBehind { drawRect(p.text.copy(alpha = 0.055f)) }
            .border(1.dp, p.text.copy(alpha = 0.06f), shape)
            .padding(4.dp)
    ) {
        val itemWidth = maxWidth / options.size
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
            label = "segIndicator"
        )
        Box(
            Modifier
                .offset(x = indicatorX)
                .width(itemWidth)
                .fillMaxHeight()
                .shadow(4.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = p.shadow, spotColor = p.shadow)
                .clip(RoundedCornerShape(12.dp))
                .drawBehind { drawRect(p.accentBrush()) }
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val isSelected = option == selected
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) p.onAccent else p.dim,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
                    label = "segText"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(option) },
                    contentAlignment = Alignment.Center
                ) {
                    MText(
                        text = label(option),
                        color = textColor,
                        style = QsType.label,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 列表行 / 分组卡片
// ────────────────────────────────────────────────────────────────────────────

/** MIUIX 分组列表行：左侧 36dp 圆形图标底，右侧内容或箭头。 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: QsIcon? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    divider: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val p = QsTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 900f),
        label = "rowPress"
    )
    val titleColor = when {
        !enabled -> p.faint
        danger -> p.danger
        else -> p.text
    }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = QsDims.rowHeight)
                .drawBehind {
                    if (pressAlpha > 0.001f) {
                        drawRect(p.text.copy(alpha = 0.045f * pressAlpha))
                    }
                }
                .then(clickModifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            } else if (leadingIcon != null) {
                Box(
                    Modifier
                        .size(QsDims.leadingIcon)
                        .clip(CircleShape)
                        .background(if (danger) p.danger.copy(alpha = 0.12f) else p.accent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    QsIcon(
                        icon = leadingIcon,
                        size = 18.dp,
                        tint = if (danger) p.danger else p.accent
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                MText(
                    text = title,
                    color = titleColor,
                    style = QsType.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    MText(
                        text = subtitle,
                        color = p.dim,
                        style = QsType.caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                QsIcon(QsIcon.CHEVRON_RIGHT, size = 16.dp, tint = p.faint)
            }
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (leading != null || leadingIcon != null) 64.dp else 16.dp, end = 16.dp)
                    .height(0.5.dp)
                    .background(p.divider)
            )
        }
    }
}

/** 分组玻璃卡：可选小标题 + 圆角 24dp 的列表容器。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = QsTheme.palette
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            MText(
                text = title,
                color = p.dim,
                style = QsType.sectionTitle,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, top = 2.dp)
            )
        }
        GlassCard(
            shape = RoundedCornerShape(QsDims.sectionRadius),
            elevation = 10.dp,
            padding = PaddingValues(vertical = 4.dp),
            content = content
        )
    }
}

/** 一行小标题 + 右侧说明，用于卡片内部的次级信息。 */
@Composable
fun RowLabel(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = QsTheme.palette.text
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MText(text = label, color = QsTheme.palette.dim, style = QsType.caption, maxLines = 1)
        MText(
            text = value,
            color = valueColor,
            style = QsType.caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.End
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Stepper
// ────────────────────────────────────────────────────────────────────────────

/** 数字步进器（窗口小时数 / 分钟 / 条数上限）。 */
@Composable
fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() }
) {
    val p = QsTheme.palette
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StepButton(
            icon = QsIcon.MINUS,
            enabled = enabled && value - step >= range.first,
            onClick = { onValueChange((value - step).coerceIn(range.first, range.last)) }
        )
        Box(
            Modifier
                .width(64.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(p.text.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            MText(
                text = format(value),
                color = if (enabled) p.text else p.faint,
                style = QsType.bodyMedium,
                maxLines = 1
            )
        }
        StepButton(
            icon = QsIcon.PLUS,
            enabled = enabled && value + step <= range.last,
            onClick = { onValueChange((value + step).coerceIn(range.first, range.last)) }
        )
    }
}

@Composable
private fun StepButton(icon: QsIcon, enabled: Boolean, onClick: () -> Unit) {
    val p = QsTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 800f),
        label = "stepScale"
    )
    Box(
        Modifier
            .size(34.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(p.text.copy(alpha = if (enabled) 0.07f else 0.03f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        QsIcon(
            icon = icon,
            size = 16.dp,
            tint = if (enabled) p.text else p.faint,
            strokeFraction = 0.12f
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 输入框
// ────────────────────────────────────────────────────────────────────────────

/** 玻璃输入框（Cookie、代理地址、消息）。 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    monospace: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null
) {
    val p = QsTheme.palette
    val shape = RoundedCornerShape(14.dp)
    val style = (if (monospace) QsType.mono else QsType.body).copy(color = p.text)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = style,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(p.accent),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.text.copy(alpha = 0.05f))
                    .border(1.dp, p.strokeTop.copy(alpha = 0.16f), shape)
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    MText(text = placeholder, color = p.faint, style = style, maxLines = maxLines)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { innerTextField() }
                    if (trailing != null) {
                        Spacer(Modifier.width(8.dp))
                        trailing()
                    }
                }
            }
        }
    )
}

// ────────────────────────────────────────────────────────────────────────────
// BottomSheet
// ────────────────────────────────────────────────────────────────────────────

/**
 * 玻璃 BottomSheet。
 *
 * 用 Dialog + 全屏透明窗体实现，进场 spring（0.82/380），退场 180ms tween；
 * 内容区可滚动。遮罩透明度随进度变化，不额外叠加模糊层。
 */
@Composable
fun GlassSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    val p = QsTheme.palette
    val progress = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(open) }
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.90f).dp

    LaunchedEffect(open) {
        if (open) {
            mounted = true
            progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 380f))
        } else if (mounted) {
            progress.animateTo(0f, tween(durationMillis = 190))
            mounted = false
        }
    }
    if (!mounted) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize()) {
            // 遮罩
            Box(
                Modifier
                    .fillMaxSize()
                    .background(p.scrim.copy(alpha = p.scrim.alpha * progress.value))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .wrapContentHeight()
                    .heightIn(max = maxHeight)
                    .graphicsLayer {
                        translationY = (1f - progress.value) * 1200.dp.toPx()
                    }
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .drawBehind {
                        drawRect(p.sheetFill)
                        drawRect(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to p.highlight.copy(alpha = p.highlight.alpha * 0.7f),
                                0.18f to Color.Transparent
                            )
                        )
                    }
                    .border(
                        1.dp,
                        p.strokeBrush(),
                        RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // 拖拽提示条
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .width(38.dp)
                        .height(4.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(CircleShape)
                        .background(p.text.copy(alpha = 0.18f))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        MText(text = title, color = p.text, style = QsType.title, maxLines = 1)
                        if (subtitle.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            MText(text = subtitle, color = p.dim, style = QsType.caption, maxLines = 2)
                        }
                    }
                    GlassIconButton(
                        icon = QsIcon.CLOSE,
                        onClick = onDismiss,
                        size = 36.dp,
                        background = false,
                        tint = p.dim
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
                    content = content
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 小组件
// ────────────────────────────────────────────────────────────────────────────

/** 0.5dp 分隔线。 */
@Composable
fun QsDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    val p = QsTheme.palette
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(p.divider)
    )
}

/** 空状态提示（图标 + 一句话 + 可选按钮）。 */
@Composable
fun EmptyHint(
    icon: QsIcon,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val p = QsTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(p.accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            QsIcon(icon = icon, size = 24.dp, tint = p.accent)
        }
        Spacer(Modifier.height(12.dp))
        MText(
            text = text,
            color = p.dim,
            style = QsType.label,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** 圆形状态点。 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, halo: Boolean = true) {
    Box(
        modifier
            .size(size)
            .drawBehind {
                if (halo) drawCircle(color.copy(alpha = 0.20f), radius = this.size.minDimension)
                drawCircle(color, radius = this.size.minDimension * 0.34f)
            }
    )
}

/** 给 Box 用的简单背景玻璃（Tab 栏 / 悬浮工具条）。 */
@Composable
fun Modifier.floatingGlass(shape: androidx.compose.ui.graphics.Shape, elevation: Dp = 18.dp): Modifier =
    this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = QsTheme.palette.shadow,
            spotColor = QsTheme.palette.shadow
        )
        .glassSurface(shape = shape, elevation = 0.dp)
