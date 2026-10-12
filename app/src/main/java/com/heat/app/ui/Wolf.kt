package com.heat.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small ethereal wolf running toward the viewer over drifting aurora ribbons.
 * Pure Canvas, three float animations, no bitmaps and no blur: cheap on the GPU and it stops
 * animating the moment it leaves composition.
 */
@Composable
fun WolfLoader(modifier: Modifier = Modifier.size(240.dp)) {
    val colors = auroraColors()
    val inf = rememberInfiniteTransition(label = "wolf")
    val t = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val run = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Restart), label = "run")
    val drift = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "drift")

    Canvas(modifier) {
        val side = size.minDimension
        val s = side / 100f
        withTransform({
            translate((size.width - side) / 2f, (size.height - side) / 2f)
            scale(s, s, Offset.Zero)
        }) {
            drawAurora(colors, drift.value)
            drawGroundGlow(colors, t.value)
            val p = t.value
            // ghost trail: earlier (smaller, further) copies of the wolf
            for ((back, amp) in listOf(0.22f to 0.22f, 0.11f to 0.4f)) {
                val q = p - back
                if (q > 0f) drawWolf(q, amp, run.value, colors, drift.value, false)
            }
            drawWolf(p, 1f, run.value, colors, drift.value, true)
            drawSparkles(colors, p)
        }
    }
}

private fun DrawScope.drawAurora(colors: List<Color>, drift: Float) {
    val tau = (2 * PI).toFloat()
    for (k in 0 until 3) {
        val path = Path()
        val base = 16f + k * 9f
        val amp = 6f + k * 2f
        var x = -5f
        fun yAt(px: Float) = base + amp * sin(tau * (px / 100f * 1.1f + drift + k * 0.31f))
        path.moveTo(x, yAt(x))
        while (x < 105f) {
            x += 5f
            path.lineTo(x, yAt(x))
        }
        val br = Brush.horizontalGradient(
            listOf(
                colors[k].copy(alpha = 0f),
                colors[(k + 1) % 4].copy(alpha = 0.75f),
                colors[(k + 2) % 4].copy(alpha = 0f)
            ),
            startX = 0f, endX = 100f
        )
        drawPath(path, br, alpha = 0.35f, style = Stroke(width = 9f - k * 1.5f, cap = StrokeCap.Round))
        drawPath(path, br, alpha = 0.55f, style = Stroke(width = 2.6f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawGroundGlow(colors: List<Color>, p: Float) {
    val sc = 0.35f + 0.65f * (1f - (1f - p) * (1f - p))
    val w = 60f * sc
    drawOval(
        Brush.radialGradient(
            listOf(colors[0].copy(alpha = 0.35f), Color.Transparent),
            center = Offset(50f, 93f), radius = 30f * sc
        ),
        topLeft = Offset(50f - w / 2f, 88f), size = Size(w, 12f)
    )
}

private fun wolfShapes(ph: Float): List<Path> {
    val l = ArrayList<Path>(14)
    // haunches peeking out behind the front legs
    l += Path().apply { addOval(Rect(21f, 68f, 35f, 88f - ph * 0.5f)) }
    l += Path().apply { addOval(Rect(65f, 68f, 79f, 88f + ph * 0.5f)) }
    // chest / body
    l += Path().apply {
        moveTo(38f, 50f)
        cubicTo(30f, 58f, 27f, 70f, 31f, 86f)
        lineTo(69f, 86f)
        cubicTo(73f, 70f, 70f, 58f, 62f, 50f)
        close()
    }
    // front legs, alternating reach toward the viewer
    l += Path().apply { addRoundRect(RoundRect(34f, 70f, 45f, 90f + ph, CornerRadius(5f))) }
    l += Path().apply { addRoundRect(RoundRect(55f, 70f, 66f, 90f - ph, CornerRadius(5f))) }
    l += Path().apply { addOval(Rect(32f, 87f + ph, 47f, 95f + ph)) }
    l += Path().apply { addOval(Rect(53f, 87f - ph, 68f, 95f - ph)) }
    // head
    l += Path().apply { addOval(Rect(37f, 31f, 63f, 55f)) }
    l += Path().apply { moveTo(39f, 36f); lineTo(35f, 15f); lineTo(48f, 31f); close() }
    l += Path().apply { moveTo(61f, 36f); lineTo(65f, 15f); lineTo(52f, 31f); close() }
    l += Path().apply { moveTo(38f, 46f); lineTo(29f, 53f); lineTo(40f, 54f); close() }
    l += Path().apply { moveTo(62f, 46f); lineTo(71f, 53f); lineTo(60f, 54f); close() }
    l += Path().apply { addOval(Rect(43f, 45f, 57f, 60f)) }
    return l
}

private fun DrawScope.drawWolf(
    p: Float, amp: Float, run: Float, colors: List<Color>, drift: Float, detail: Boolean
) {
    val e = 1f - (1f - p) * (1f - p)
    val sc = 0.32f + 0.68f * e
    val fade = minOf(1f, p / 0.18f) * minOf(1f, (1f - p) / 0.22f)
    val a = (amp * fade).coerceIn(0f, 1f)
    if (a <= 0.01f) return
    val bob = abs(sin(run * PI.toFloat())) * 1.8f
    val ty = (1f - e) * -14f - bob * sc
    withTransform({
        translate(0f, ty)
        scale(sc, sc, Offset(50f, 90f))
    }) {
        val brush = Brush.linearGradient(
            colors,
            start = Offset(-60f + 120f * drift, 0f),
            end = Offset(40f + 120f * drift, 100f),
            tileMode = TileMode.Mirror
        )
        val ph = sin(run * 2f * PI.toFloat()) * 4.5f
        val shapes = wolfShapes(ph)
        // soft halo (two translucent strokes instead of an expensive blur)
        for (sh in shapes) {
            drawPath(sh, brush, alpha = a * 0.10f, style = Stroke(width = 7f))
            drawPath(sh, brush, alpha = a * 0.18f, style = Stroke(width = 3.5f))
        }
        for (sh in shapes) drawPath(sh, brush, alpha = a * 0.88f)
        if (detail) {
            // inner ears, muzzle, nose
            drawPath(Path().apply { moveTo(40f, 34f); lineTo(37f, 20f); lineTo(46f, 31f); close() }, Color.White, alpha = a * 0.18f)
            drawPath(Path().apply { moveTo(60f, 34f); lineTo(63f, 20f); lineTo(54f, 31f); close() }, Color.White, alpha = a * 0.18f)
            drawOval(Color.White, Offset(45f, 48f), Size(10f, 8f), alpha = a * 0.22f)
            drawOval(Color.White, Offset(47.5f, 52f), Size(5f, 3.4f), alpha = a * 0.65f)
            // glowing eyes
            drawCircle(colors[0], radius = 4.5f, center = Offset(44.2f, 40.3f), alpha = a * 0.25f)
            drawCircle(colors[0], radius = 4.5f, center = Offset(55.8f, 40.3f), alpha = a * 0.25f)
            drawOval(Color.White, Offset(41.8f, 39f), Size(4.8f, 2.6f), alpha = a)
            drawOval(Color.White, Offset(53.4f, 39f), Size(4.8f, 2.6f), alpha = a)
        }
    }
}

private fun DrawScope.drawSparkles(colors: List<Color>, p: Float) {
    for (i in 0 until 9) {
        val ph = (p + i * 0.117f) % 1f
        val x = (i * 37 % 90) + 5f
        val y0 = 70f + (i * 13 % 25)
        val y = y0 - ph * 45f
        val al = sin(ph * PI.toFloat()) * 0.8f
        drawCircle(colors[i % 4], radius = 0.8f + (i % 3) * 0.4f, center = Offset(x, y), alpha = al)
    }
}
