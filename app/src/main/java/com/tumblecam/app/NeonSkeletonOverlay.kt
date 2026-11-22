package com.tumblecam.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.pose.PoseLandmark as L

val NeonCyan = Color(0xFF00F0FF)
val NeonAmber = Color(0xFFFFB300)

private val BONES = listOf(
    L.LEFT_SHOULDER to L.RIGHT_SHOULDER, L.LEFT_HIP to L.RIGHT_HIP,
    L.LEFT_SHOULDER to L.LEFT_HIP, L.RIGHT_SHOULDER to L.RIGHT_HIP,
    L.LEFT_SHOULDER to L.LEFT_ELBOW, L.LEFT_ELBOW to L.LEFT_WRIST,
    L.RIGHT_SHOULDER to L.RIGHT_ELBOW, L.RIGHT_ELBOW to L.RIGHT_WRIST,
    L.LEFT_HIP to L.LEFT_KNEE, L.LEFT_KNEE to L.LEFT_ANKLE,
    L.RIGHT_HIP to L.RIGHT_KNEE, L.RIGHT_KNEE to L.RIGHT_ANKLE,
    L.LEFT_ANKLE to L.LEFT_FOOT_INDEX, L.RIGHT_ANKLE to L.RIGHT_FOOT_INDEX
)
private val GLOW = listOf(20f to 0.08f, 12f to 0.16f, 6f to 0.35f)

/** Rectangle the upright camera image occupies inside a view of size w x h (matches the GL letterboxing). */
fun fitRect(w: Float, h: Float, aspect: Float): Rect {
    val cw: Float
    val ch: Float
    if (w / h < aspect) { cw = w; ch = w / aspect } else { ch = h; cw = h * aspect }
    val l = (w - cw) / 2f
    val t = (h - ch) / 2f
    return Rect(l, t, l + cw, t + ch)
}

private fun DrawScope.neonLine(a: Offset, b: Offset, color: Color, u: Float) {
    GLOW.forEach { (w, alpha) -> drawLine(color.copy(alpha = alpha), a, b, w * u, StrokeCap.Round) }
    drawLine(lerp(color, Color.White, 0.65f), a, b, 2.5f * u, StrokeCap.Round)
}

@Composable
fun NeonSkeletonOverlay(state: TrackState?, modifier: Modifier = Modifier, contentAspect: Float = 9f / 16f) {
    Canvas(modifier) {
        val s = state ?: return@Canvas
        val r = fitRect(size.width, size.height, contentAspect)
        val u = 1.dp.toPx()
        val color = if (s.coasting) NeonAmber else NeonCyan
        fun px(x: Float, y: Float) = Offset(r.left + x * r.width, r.top + y * r.height)
        val minConf = if (s.coasting) 0f else 0.3f

        // predicted trajectory
        val path = Path()
        s.path.forEachIndexed { i, p -> val o = px(p.x, p.y); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        GLOW.forEach { (w, alpha) ->
            drawPath(path, color.copy(alpha = alpha), style = Stroke(w * u * 0.5f, cap = StrokeCap.Round))
        }
        drawPath(path, lerp(color, Color.White, 0.5f),
            style = Stroke(2f * u, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f * u, 9f * u))))

        // bones
        BONES.forEach { (a, b) ->
            val ja = s.joints[a]
            val jb = s.joints[b]
            if (ja != null && jb != null && ja.confidence >= minConf && jb.confidence >= minConf) {
                neonLine(px(ja.x, ja.y), px(jb.x, jb.y), color, u)
            }
        }
        // joints
        BONES.flatMap { listOf(it.first, it.second) }.distinct().forEach { type ->
            val j = s.joints[type] ?: return@forEach
            if (j.confidence < minConf) return@forEach
            val o = px(j.x, j.y)
            drawCircle(color.copy(alpha = 0.25f), 8f * u, o)
            drawCircle(lerp(color, Color.White, 0.7f), 3.5f * u, o)
        }
        // head
        s.joints[L.NOSE]?.takeIf { it.confidence >= minConf }?.let {
            val o = px(it.x, it.y)
            drawCircle(color.copy(alpha = 0.2f), 14f * u, o)
            drawCircle(lerp(color, Color.White, 0.6f), 10f * u, o, style = Stroke(2f * u))
        }
        // center-of-mass crosshair
        val c = px(s.com.x, s.com.y)
        drawCircle(color.copy(alpha = 0.2f), 16f * u, c)
        drawCircle(lerp(color, Color.White, 0.6f), 10f * u, c, style = Stroke(2f * u))
        drawLine(Color.White, Offset(c.x - 20f * u, c.y), Offset(c.x + 20f * u, c.y), 1.5f * u)
        drawLine(Color.White, Offset(c.x, c.y - 20f * u), Offset(c.x, c.y + 20f * u), 1.5f * u)
    }
}
