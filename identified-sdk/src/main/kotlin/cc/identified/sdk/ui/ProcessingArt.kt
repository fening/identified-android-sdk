package cc.identified.sdk.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Native line art from ProcessingScan.tsx; the subject of the scan matches the operation. */
@Composable
internal fun ProcessingArt(face: Boolean = false, page: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "scan")
    val scan by
        transition.animateFloat(
            initialValue = 16f,
            targetValue = 150f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
            label = "scan-position",
        )
    Canvas(
        Modifier.widthIn(max = 240.dp)
            .fillMaxWidth(.7f)
            .aspectRatio(220f / 150f)
            .clearAndSetSemantics {}
    ) {
        withTransform({ scale(size.width / 220f, size.height / 150f, Offset.Zero) }) {
            fun line(x: Float, y: Float, endX: Float, endY: Float, alpha: Float = .45f) =
                drawLine(
                    accent.copy(alpha = alpha),
                    Offset(x, y),
                    Offset(endX, endY),
                    3.5f,
                    StrokeCap.Round,
                )
            fun portrait(cx: Float, cy: Float, radius: Float) {
                drawCircle(accent, radius, Offset(cx, cy), style = Stroke(3f))
                val shoulders =
                    Path().apply {
                        moveTo(cx - radius * 1.33f, cy + radius * 2.22f)
                        cubicTo(
                            cx - radius * 1.33f,
                            cy + radius * 1.16f,
                            cx + radius * 1.33f,
                            cy + radius * 1.16f,
                            cx + radius * 1.33f,
                            cy + radius * 2.22f,
                        )
                    }
                drawPath(shoulders, accent, style = Stroke(3f, cap = StrokeCap.Round))
            }
            if (face) {
                drawCircle(accent, 34f, Offset(62f, 75f), style = Stroke(3.5f))
                portrait(62f, 64f, 12f)
                line(104f, 75f, 116f, 75f, .5f)
                line(124f, 75f, 136f, 75f, .5f)
                drawRoundRect(
                    accent,
                    Offset(144f, 44f),
                    Size(62f, 62f),
                    CornerRadius(10f),
                    style = Stroke(3.5f),
                )
                portrait(164f, 66f, 8f)
                line(184f, 62f, 198f, 62f)
                line(184f, 74f, 194f, 74f)
            } else if (page) {
                drawRoundRect(
                    accent,
                    Offset(52f, 10f),
                    Size(116f, 130f),
                    CornerRadius(8f),
                    style = Stroke(3.5f),
                )
                listOf(40f to 148f, 60f to 148f, 80f to 130f, 100f to 136f, 120f to 112f).forEach {
                    (y, end) ->
                    line(72f, y, end, y)
                }
            } else {
                drawRoundRect(
                    accent,
                    Offset(14f, 16f),
                    Size(192f, 118f),
                    CornerRadius(12f),
                    style = Stroke(3.5f),
                )
                portrait(66f, 66f, 18f)
                line(112f, 56f, 186f, 56f, .55f)
                line(112f, 74f, 170f, 74f, .4f)
                line(112f, 98f, 132f, 98f, .4f)
                line(140f, 98f, 156f, 98f, .4f)
                line(164f, 98f, 186f, 98f, .4f)
            }
            clipRect(14f, 16f, 206f, 134f) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0f),
                            accent.copy(alpha = .28f),
                            accent.copy(alpha = .55f),
                        ),
                        startY = scan - 42f,
                        endY = scan,
                    ),
                    Offset(14f, scan - 42f),
                    Size(192f, 42f),
                )
                line(14f, scan, 206f, scan, .95f)
            }
        }
    }
}
