package com.aicode.quotasentry.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.quotasentry.ui.theme.QsTheme

/** 全部图标都用 Canvas / Path 自绘，项目里没有 material-icons。 */
enum class QsIcon {
    HOME, CALENDAR, SETTINGS, HISTORY, REFRESH, PLUS, MINUS,
    CHEVRON_RIGHT, CHEVRON_DOWN, BACK, CHECK, CLOSE, MORE,
    SEND, BOLT, TRASH, CLOCK, LINK, SHIELD, BATTERY, BELL, WIFI,
    PALETTE, INFO, COPY, PASTE, GLOBE, EYE, EYE_OFF, WARN,
    PLAY, STOP, AUTO_START, LOCK, CODE, POWER, RESET
}

/**
 * 统一入口：24×24 设计网格上手绘，按目标尺寸等比缩放，线宽随尺寸缩放。
 */
@Composable
fun QsIcon(
    icon: QsIcon,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = QsTheme.palette.text,
    strokeFraction: Float = 0.085f
) {
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) {
        size.toPx() * strokeFraction
    }
    Canvas(modifier = modifier.size(size)) {
        drawQsIcon(icon, tint, strokePx)
    }
}

/** 供 Canvas 内部直接调用（比如状态点、Tab 图标）。 */
fun DrawScope.drawQsIcon(icon: QsIcon, tint: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val s = size.minDimension
    val sw = stroke.coerceAtLeast(1f)
    val strokeStyle = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(x / 24f * w, y / 24f * h)
    fun l(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, o(x1, y1), o(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)

    when (icon) {
        QsIcon.HOME -> {
            val path = Path().apply {
                moveTo(o(3.2f, 11f).x, o(3.2f, 11f).y)
                lineTo(o(12f, 3.4f).x, o(12f, 3.4f).y)
                lineTo(o(20.8f, 11f).x, o(20.8f, 11f).y)
                moveTo(o(5.4f, 10.2f).x, o(5.4f, 10.2f).y)
                lineTo(o(5.4f, 20f).x, o(5.4f, 20f).y)
                lineTo(o(18.6f, 20f).x, o(18.6f, 20f).y)
                lineTo(o(18.6f, 10.2f).x, o(18.6f, 10.2f).y)
                moveTo(o(9.8f, 20f).x, o(9.8f, 20f).y)
                lineTo(o(9.8f, 14.6f).x, o(9.8f, 14.6f).y)
                lineTo(o(14.2f, 14.6f).x, o(14.2f, 14.6f).y)
                lineTo(o(14.2f, 20f).x, o(14.2f, 20f).y)
            }
            drawPath(path, tint, style = strokeStyle)
        }

        QsIcon.CALENDAR -> {
            drawRoundRect(
                tint,
                topLeft = o(3.6f, 5.2f),
                size = Size(o(16.8f, 15.4f).x, o(16.8f, 15.4f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
                style = strokeStyle
            )
            l(3.6f, 9.6f, 20.4f, 9.6f)
            l(8.2f, 2.8f, 8.2f, 6.6f)
            l(15.8f, 2.8f, 15.8f, 6.6f)
            drawCircle(tint, s * 0.045f, o(8.4f, 13.4f))
            drawCircle(tint, s * 0.045f, o(12f, 13.4f))
            drawCircle(tint, s * 0.045f, o(15.6f, 13.4f))
            drawCircle(tint, s * 0.045f, o(8.4f, 17f))
            drawCircle(tint, s * 0.045f, o(12f, 17f))
        }

        QsIcon.SETTINGS -> {
            l(4f, 7.4f, 20f, 7.4f)
            l(4f, 12f, 20f, 12f)
            l(4f, 16.6f, 20f, 16.6f)
            drawCircle(tint, s * 0.085f, o(8.8f, 7.4f), style = Stroke(sw * 0.9f))
            drawCircle(tint, s * 0.085f, o(15.2f, 12f), style = Stroke(sw * 0.9f))
            drawCircle(tint, s * 0.085f, o(10.4f, 16.6f), style = Stroke(sw * 0.9f))
        }

        QsIcon.HISTORY -> {
            drawArc(
                tint,
                startAngle = -40f,
                sweepAngle = 320f,
                useCenter = false,
                topLeft = o(4f, 4f),
                size = Size(o(16f, 16f).x, o(16f, 16f).y),
                style = strokeStyle
            )
            val head = Path().apply {
                moveTo(o(3.2f, 5.2f).x, o(3.2f, 5.2f).y)
                lineTo(o(8.6f, 4.4f).x, o(8.6f, 4.4f).y)
                moveTo(o(3.2f, 5.2f).x, o(3.2f, 5.2f).y)
                lineTo(o(5.2f, 9.6f).x, o(5.2f, 9.6f).y)
            }
            drawPath(head, tint, style = strokeStyle)
            l(12f, 8.4f, 12f, 12.2f)
            l(12f, 12.2f, 14.8f, 14f)
        }

        QsIcon.REFRESH, QsIcon.RESET -> {
            drawArc(
                tint,
                startAngle = 28f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = o(4.2f, 4.2f),
                size = Size(o(15.6f, 15.6f).x, o(15.6f, 15.6f).y),
                style = strokeStyle
            )
            val head = Path().apply {
                moveTo(o(15.2f, 2.6f).x, o(15.2f, 2.6f).y)
                lineTo(o(19.8f, 6.2f).x, o(19.8f, 6.2f).y)
                lineTo(o(14.8f, 8.8f).x, o(14.8f, 8.8f).y)
            }
            drawPath(head, tint, style = strokeStyle)
        }

        QsIcon.PLUS -> {
            l(12f, 5f, 12f, 19f)
            l(5f, 12f, 19f, 12f)
        }

        QsIcon.MINUS -> l(5f, 12f, 19f, 12f)

        QsIcon.CHEVRON_RIGHT -> {
            l(9.4f, 5.6f, 15.8f, 12f)
            l(15.8f, 12f, 9.4f, 18.4f)
        }

        QsIcon.CHEVRON_DOWN -> {
            l(5.6f, 9.4f, 12f, 15.8f)
            l(12f, 15.8f, 18.4f, 9.4f)
        }

        QsIcon.BACK -> {
            l(19.5f, 12f, 5f, 12f)
            l(11.5f, 5.5f, 5f, 12f)
            l(5f, 12f, 11.5f, 18.5f)
        }

        QsIcon.CHECK -> {
            val path = Path().apply {
                moveTo(o(4.6f, 12.6f).x, o(4.6f, 12.6f).y)
                lineTo(o(9.8f, 17.6f).x, o(9.8f, 17.6f).y)
                lineTo(o(19.4f, 6.6f).x, o(19.4f, 6.6f).y)
            }
            drawPath(path, tint, style = strokeStyle)
        }

        QsIcon.CLOSE -> {
            l(6f, 6f, 18f, 18f)
            l(18f, 6f, 6f, 18f)
        }

        QsIcon.MORE -> {
            drawCircle(tint, s * 0.075f, o(6.2f, 12f))
            drawCircle(tint, s * 0.075f, o(12f, 12f))
            drawCircle(tint, s * 0.075f, o(17.8f, 12f))
        }

        QsIcon.SEND -> {
            val path = Path().apply {
                moveTo(o(3.4f, 11.2f).x, o(3.4f, 11.2f).y)
                lineTo(o(20.6f, 3.6f).x, o(20.6f, 3.6f).y)
                lineTo(o(13.4f, 20.6f).x, o(13.4f, 20.6f).y)
                lineTo(o(10.6f, 13.4f).x, o(10.6f, 13.4f).y)
                close()
                moveTo(o(10.6f, 13.4f).x, o(10.6f, 13.4f).y)
                lineTo(o(20.6f, 3.6f).x, o(20.6f, 3.6f).y)
            }
            drawPath(path, tint, style = strokeStyle)
        }

        QsIcon.BOLT -> {
            val path = Path().apply {
                moveTo(o(13.4f, 2.6f).x, o(13.4f, 2.6f).y)
                lineTo(o(5.2f, 13.8f).x, o(5.2f, 13.8f).y)
                lineTo(o(11.4f, 13.8f).x, o(11.4f, 13.8f).y)
                lineTo(o(10.2f, 21.4f).x, o(10.2f, 21.4f).y)
                lineTo(o(18.8f, 10.2f).x, o(18.8f, 10.2f).y)
                lineTo(o(12.6f, 10.2f).x, o(12.6f, 10.2f).y)
                close()
            }
            drawPath(path, tint, style = strokeStyle)
        }

        QsIcon.TRASH -> {
            l(4f, 6.6f, 20f, 6.6f)
            l(9.4f, 6.6f, 9.4f, 3.8f)
            l(9.4f, 3.8f, 14.6f, 3.8f)
            l(14.6f, 3.8f, 14.6f, 6.6f)
            val body = Path().apply {
                moveTo(o(6f, 6.6f).x, o(6f, 6.6f).y)
                lineTo(o(7.2f, 20.4f).x, o(7.2f, 20.4f).y)
                lineTo(o(16.8f, 20.4f).x, o(16.8f, 20.4f).y)
                lineTo(o(18f, 6.6f).x, o(18f, 6.6f).y)
            }
            drawPath(body, tint, style = strokeStyle)
            l(10.2f, 10.2f, 10.2f, 16.8f)
            l(13.8f, 10.2f, 13.8f, 16.8f)
        }

        QsIcon.CLOCK -> {
            drawCircle(tint, s * 0.42f, o(12f, 12f), style = Stroke(sw * 0.95f))
            l(12f, 6.8f, 12f, 12.2f)
            l(12f, 12.2f, 15.6f, 14.2f)
        }

        QsIcon.LINK -> {
            drawArc(
                tint,
                startAngle = 120f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = o(2.8f, 8.2f),
                size = Size(o(11.4f, 7.6f).x, o(11.4f, 7.6f).y),
                style = strokeStyle
            )
            drawArc(
                tint,
                startAngle = -60f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = o(9.8f, 8.2f),
                size = Size(o(11.4f, 7.6f).x, o(11.4f, 7.6f).y),
                style = strokeStyle
            )
            l(7.8f, 12f, 16.2f, 12f)
        }

        QsIcon.SHIELD -> {
            val path = Path().apply {
                moveTo(o(12f, 2.8f).x, o(12f, 2.8f).y)
                lineTo(o(20f, 6.2f).x, o(20f, 6.2f).y)
                lineTo(o(20f, 11.6f).x, o(20f, 11.6f).y)
                cubicTo(
                    o(20f, 20.6f).x, o(20f, 20.6f).y,
                    o(12f, 21.8f).x, o(12f, 21.8f).y,
                    o(12f, 21.8f).x, o(12f, 21.8f).y
                )
                cubicTo(
                    o(12f, 21.8f).x, o(12f, 21.8f).y,
                    o(4f, 20.6f).x, o(4f, 20.6f).y,
                    o(4f, 11.6f).x, o(4f, 11.6f).y
                )
                lineTo(o(4f, 6.2f).x, o(4f, 6.2f).y)
                close()
            }
            drawPath(path, tint, style = strokeStyle)
            val check = Path().apply {
                moveTo(o(8.4f, 11.8f).x, o(8.4f, 11.8f).y)
                lineTo(o(11.2f, 14.6f).x, o(11.2f, 14.6f).y)
                lineTo(o(16f, 9.4f).x, o(16f, 9.4f).y)
            }
            drawPath(check, tint, style = strokeStyle)
        }

        QsIcon.BATTERY -> {
            drawRoundRect(
                tint,
                topLeft = o(2.8f, 7.2f),
                size = Size(o(15.8f, 9.6f).x, o(15.8f, 9.6f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.09f),
                style = strokeStyle
            )
            drawRoundRect(
                tint,
                topLeft = o(19.6f, 10f),
                size = Size(o(1.8f, 4f).x, o(1.8f, 4f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.05f),
                style = Stroke(sw * 0.9f)
            )
            drawRoundRect(
                tint,
                topLeft = o(4.8f, 9.2f),
                size = Size(o(9f, 5.6f).x, o(9f, 5.6f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.04f)
            )
        }

        QsIcon.BELL -> {
            val path = Path().apply {
                moveTo(o(5.4f, 17.4f).x, o(5.4f, 17.4f).y)
                lineTo(o(6.8f, 15.4f).x, o(6.8f, 15.4f).y)
                lineTo(o(6.8f, 10.6f).x, o(6.8f, 10.6f).y)
                cubicTo(
                    o(6.8f, 4.2f).x, o(6.8f, 4.2f).y,
                    o(17.2f, 4.2f).x, o(17.2f, 4.2f).y,
                    o(17.2f, 10.6f).x, o(17.2f, 10.6f).y
                )
                lineTo(o(17.2f, 15.4f).x, o(17.2f, 15.4f).y)
                lineTo(o(18.6f, 17.4f).x, o(18.6f, 17.4f).y)
                close()
            }
            drawPath(path, tint, style = strokeStyle)
            l(10f, 20f, 14f, 20f)
        }

        QsIcon.WIFI -> {
            drawArc(
                tint, -140f, 100f, false,
                topLeft = o(2.6f, 4.2f), size = Size(o(18.8f, 18.8f).x, o(18.8f, 18.8f).y),
                style = strokeStyle
            )
            drawArc(
                tint, -140f, 100f, false,
                topLeft = o(6.4f, 8f), size = Size(o(11.2f, 11.2f).x, o(11.2f, 11.2f).y),
                style = strokeStyle
            )
            drawArc(
                tint, -140f, 100f, false,
                topLeft = o(9.8f, 11.6f), size = Size(o(4.4f, 4.4f).x, o(4.4f, 4.4f).y),
                style = strokeStyle
            )
            drawCircle(tint, s * 0.055f, o(12f, 18.6f))
        }

        QsIcon.PALETTE -> {
            drawCircle(tint, s * 0.40f, o(12f, 12f), style = Stroke(sw * 0.95f))
            drawCircle(tint, s * 0.055f, o(8.2f, 8.6f))
            drawCircle(tint, s * 0.055f, o(15.8f, 8.6f))
            drawCircle(tint, s * 0.055f, o(8.4f, 15.4f))
            drawCircle(tint, s * 0.055f, o(15f, 15.8f))
        }

        QsIcon.INFO -> {
            drawCircle(tint, s * 0.40f, o(12f, 12f), style = Stroke(sw * 0.95f))
            drawCircle(tint, s * 0.060f, o(12f, 7.8f))
            l(12f, 11f, 12f, 17.2f)
        }

        QsIcon.COPY -> {
            drawRoundRect(
                tint,
                topLeft = o(8f, 3.6f),
                size = Size(o(12.4f, 12.4f).x, o(12.4f, 12.4f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.10f),
                style = strokeStyle
            )
            val path = Path().apply {
                moveTo(o(16f, 20.4f).x, o(16f, 20.4f).y)
                lineTo(o(5.4f, 20.4f).x, o(5.4f, 20.4f).y)
                lineTo(o(5.4f, 9.4f).x, o(5.4f, 9.4f).y)
            }
            drawPath(path, tint, style = strokeStyle)
        }

        QsIcon.PASTE -> {
            drawRoundRect(
                tint,
                topLeft = o(4.4f, 5.2f),
                size = Size(o(15.2f, 16.2f).x, o(15.2f, 16.2f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.10f),
                style = strokeStyle
            )
            drawRoundRect(
                tint,
                topLeft = o(9.2f, 3f),
                size = Size(o(5.6f, 4.2f).x, o(5.6f, 4.2f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.06f),
                style = Stroke(sw * 0.9f)
            )
            l(8f, 12f, 16f, 12f)
            l(8f, 15.8f, 13.4f, 15.8f)
        }

        QsIcon.GLOBE -> {
            drawCircle(tint, s * 0.40f, o(12f, 12f), style = Stroke(sw * 0.95f))
            drawArc(
                tint, 0f, 360f, false,
                topLeft = o(8.2f, 4.4f), size = Size(o(7.6f, 15.2f).x, o(7.6f, 15.2f).y),
                style = strokeStyle
            )
            l(4f, 12f, 20f, 12f)
        }

        QsIcon.EYE -> {
            val path = Path().apply {
                moveTo(o(2.8f, 12f).x, o(2.8f, 12f).y)
                cubicTo(
                    o(6.4f, 6.2f).x, o(6.4f, 6.2f).y,
                    o(17.6f, 6.2f).x, o(17.6f, 6.2f).y,
                    o(21.2f, 12f).x, o(21.2f, 12f).y
                )
                cubicTo(
                    o(17.6f, 17.8f).x, o(17.6f, 17.8f).y,
                    o(6.4f, 17.8f).x, o(6.4f, 17.8f).y,
                    o(2.8f, 12f).x, o(2.8f, 12f).y
                )
                close()
            }
            drawPath(path, tint, style = strokeStyle)
            drawCircle(tint, s * 0.115f, o(12f, 12f), style = Stroke(sw * 0.9f))
        }

        QsIcon.EYE_OFF -> {
            val path = Path().apply {
                moveTo(o(3.6f, 9.2f).x, o(3.6f, 9.2f).y)
                cubicTo(
                    o(7.2f, 6.4f).x, o(7.2f, 6.4f).y,
                    o(16.8f, 6.4f).x, o(16.8f, 6.4f).y,
                    o(20.4f, 11.4f).x, o(20.4f, 11.4f).y
                )
                moveTo(o(4.4f, 15.8f).x, o(4.4f, 15.8f).y)
                cubicTo(
                    o(8f, 13.4f).x, o(8f, 13.4f).y,
                    o(16f, 13.4f).x, o(16f, 13.4f).y,
                    o(19.6f, 17.2f).x, o(19.6f, 17.2f).y
                )
            }
            drawPath(path, tint, style = strokeStyle)
            l(4.4f, 4.4f, 19.6f, 19.6f)
        }

        QsIcon.WARN -> {
            val path = Path().apply {
                moveTo(o(12f, 3.2f).x, o(12f, 3.2f).y)
                lineTo(o(21.6f, 20f).x, o(21.6f, 20f).y)
                lineTo(o(2.4f, 20f).x, o(2.4f, 20f).y)
                close()
            }
            drawPath(path, tint, style = strokeStyle)
            l(12f, 9f, 12f, 14.2f)
            drawCircle(tint, s * 0.055f, o(12f, 17f))
        }

        QsIcon.PLAY -> {
            val path = Path().apply {
                moveTo(o(7.4f, 4.6f).x, o(7.4f, 4.6f).y)
                lineTo(o(19f, 12f).x, o(19f, 12f).y)
                lineTo(o(7.4f, 19.4f).x, o(7.4f, 19.4f).y)
                close()
            }
            drawPath(path, tint, style = strokeStyle)
        }

        QsIcon.STOP -> drawRoundRect(
            tint,
            topLeft = o(6.4f, 6.4f),
            size = Size(o(11.2f, 11.2f).x, o(11.2f, 11.2f).y),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f),
            style = strokeStyle
        )

        QsIcon.AUTO_START -> {
            drawArc(
                tint, 40f, 280f, false,
                topLeft = o(4.4f, 4.4f), size = Size(o(15.2f, 15.2f).x, o(15.2f, 15.2f).y),
                style = strokeStyle
            )
            val head = Path().apply {
                moveTo(o(12f, 1.8f).x, o(12f, 1.8f).y)
                lineTo(o(16.6f, 5.2f).x, o(16.6f, 5.2f).y)
                lineTo(o(11.4f, 7.8f).x, o(11.4f, 7.8f).y)
            }
            drawPath(head, tint, style = strokeStyle)
            l(12f, 14.4f, 12f, 9.2f)
            l(9.6f, 11.6f, 12f, 9.2f)
            l(14.4f, 11.6f, 12f, 9.2f)
        }

        QsIcon.LOCK -> {
            drawRoundRect(
                tint,
                topLeft = o(5.2f, 10f),
                size = Size(o(13.6f, 10.4f).x, o(13.6f, 10.4f).y),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
                style = strokeStyle
            )
            drawArc(
                tint, 180f, 180f, false,
                topLeft = o(8f, 3.8f), size = Size(o(8f, 10f).x, o(8f, 10f).y),
                style = strokeStyle
            )
            drawCircle(tint, s * 0.05f, o(12f, 15f))
        }

        QsIcon.CODE -> {
            l(8.6f, 6.4f, 3.2f, 12f)
            l(3.2f, 12f, 8.6f, 17.6f)
            l(15.4f, 6.4f, 20.8f, 12f)
            l(20.8f, 12f, 15.4f, 17.6f)
        }

        QsIcon.POWER -> {
            drawArc(
                tint, -90f, 300f, false,
                topLeft = o(4.4f, 4.4f), size = Size(o(15.2f, 15.2f).x, o(15.2f, 15.2f).y),
                style = strokeStyle
            )
            l(12f, 2.8f, 12f, 10.4f)
        }
    }
}

/** 带旋转动画的加载环（刷新中 / 连接中）。 */
@Composable
fun LoadingRing(
    modifier: Modifier = Modifier,
    ringSize: Dp = 18.dp,
    color: Color = QsTheme.palette.accent,
    fraction: Float = 0.28f
) {
    val transition = rememberInfiniteTransition(label = "loadingRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAngle"
    )
    Canvas(
        modifier = modifier
            .size(ringSize)
            .graphicsLayer { rotationZ = angle }
    ) {
        val side = this.size.minDimension
        val sw = side * 0.10f
        val inset = sw / 2f
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0.05f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(side - sw, side - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
    }
}
