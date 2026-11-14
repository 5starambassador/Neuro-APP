package com.focusquest.app.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Focus-burst training: focus draws the bow; when fully drawn the arrow flies.
 * The steadier the focus while drawing, the closer to the bullseye it lands.
 */
class ArcheryGame : FocusGame {
    private val rnd = Random(3)
    private var charge = 0f
    private var focusSum = 0f
    private var drawTime = 0f
    private var flight = -1f          // 0..1 while an arrow is flying
    private var pendingOffset = 0f    // where the flying arrow will land, -1..1 of target radius
    private val stuck = ArrayDeque<Float>()
    private var targetPhase = 0f
    private var flash = 0f
    private var arrows = 0
    private var bullseyes = 0

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        targetPhase += dt * 0.5f
        flash = (flash - dt).coerceAtLeast(0f)
        if (flight >= 0f) {
            flight += dt / 0.55f
            if (flight >= 1f) {
                flight = -1f
                arrows++
                stuck.addLast(pendingOffset)
                if (stuck.size > 6) stuck.removeFirst()
                if (abs(pendingOffset) < 0.2f) { bullseyes++; flash = 1.2f }
            }
            return
        }
        if (focused) {
            charge += dt * (0.18f + 0.3f * focus)
            focusSum += focus * dt
            drawTime += dt
        } else {
            charge = (charge - dt * 0.1f).coerceAtLeast(0f)
            drawTime += dt * 0.5f // wavering also counts against steadiness
        }
        if (charge >= 1f) {
            val steadiness = if (drawTime > 0f) (focusSum / drawTime).coerceIn(0f, 1f) else 0f
            val spread = (1f - steadiness) * 0.95f + rnd.nextFloat() * 0.12f
            pendingOffset = (if (rnd.nextBoolean()) spread else -spread).coerceIn(-0.98f, 0.98f)
            charge = 0f; focusSum = 0f; drawTime = 0f
            flight = 0f
        }
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF8ED1FF), Color(0xFFE6F6FF))))
        drawCircle(Color(0xFFFFE27A), w * 0.07f, Offset(w * 0.15f, h * 0.22f))
        // Rolling hills and field
        val hills = Path().apply {
            moveTo(0f, h * 0.62f)
            for (i in 0..12) lineTo(i / 12f * w, h * 0.6f - 40f * sin(i * 0.9f))
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(hills, Color(0xFF7CC576))
        drawRect(Color(0xFF5DAE57), Offset(0f, h * 0.7f), Size(w, h * 0.3f))

        // Target on a stand, gently bobbing
        val r = w * 0.14f
        val tc = Offset(w * 0.78f, h * 0.48f + sin(targetPhase) * h * 0.06f)
        drawLine(Color(0xFF8B5A2B), tc + Offset(-r * 0.5f, r * 0.6f), Offset(tc.x - r * 0.8f, h * 0.74f), 10f)
        drawLine(Color(0xFF8B5A2B), tc + Offset(r * 0.5f, r * 0.6f), Offset(tc.x + r * 0.8f, h * 0.74f), 10f)
        val rings = listOf(Color.White, Color(0xFF263238), Color(0xFF2F80ED), Color(0xFFEB5757), Color(0xFFFFC542))
        rings.forEachIndexed { i, c -> drawCircle(c, r * (1f - i * 0.2f), tc) }
        if (flash > 0f) {
            for (k in 0 until 12) {
                val a = k * (2 * PI / 12).toFloat() + time
                val d = r * (1.1f + (1.2f - flash) * 0.6f)
                drawCircle(Color(0xFFFFD43B).copy(alpha = flash.coerceAtMost(1f)), 8f, tc + Offset(kotlin.math.cos(a) * d, sin(a) * d))
            }
        }
        stuck.forEach { off -> arrow(Offset(tc.x - r * 0.05f, tc.y + off * r), r * 1.1f, stuckIn = true) }

        // Bow
        val bow = Offset(w * 0.2f, h * 0.5f)
        val bh = h * 0.13f
        val pull = (if (flight >= 0f) 0f else charge) * w * 0.12f
        drawArc(
            Color(0xFF6D3B1F), -70f, 140f, false,
            Offset(bow.x - bh * 0.5f, bow.y - bh), Size(bh, bh * 2), style = Stroke(12f, cap = StrokeCap.Round),
        )
        val top = Offset(bow.x + bh * 0.5f * 0.34f, bow.y - bh * 0.94f)
        val bottom = Offset(bow.x + bh * 0.5f * 0.34f, bow.y + bh * 0.94f)
        val nock = Offset(top.x - pull, bow.y)
        drawLine(Color(0xFFEEEEEE), top, nock, 3f)
        drawLine(Color(0xFFEEEEEE), bottom, nock, 3f)
        if (flight < 0f) arrow(Offset(nock.x + r * 1.1f, bow.y), r * 1.1f, stuckIn = false)

        // Power meter under the bow
        val mw = w * 0.22f
        val mTop = bow.y + bh + 30f
        drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(bow.x - mw / 2, mTop), Size(mw, 18f), androidx.compose.ui.geometry.CornerRadius(9f))
        drawRoundRect(Color(0xFFFFB547), Offset(bow.x - mw / 2, mTop), Size(mw * charge, 18f), androidx.compose.ui.geometry.CornerRadius(9f))

        // Arrow in flight
        if (flight >= 0f) {
            val startX = bow.x + r * 1.1f
            val endX = tc.x - r * 0.05f
            val endY = tc.y + pendingOffset * r
            val x = startX + (endX - startX) * flight
            val y = bow.y + (endY - bow.y) * flight - sin(PI.toFloat() * flight) * h * 0.06f
            arrow(Offset(x, y), r * 1.1f, stuckIn = false)
        }
    }

    /** Draws an arrow whose tip is at [tip], pointing right. */
    private fun DrawScope.arrow(tip: Offset, length: Float, stuckIn: Boolean) {
        val tail = tip - Offset(length, 0f)
        drawLine(Color(0xFF5D4037), tail, tip - Offset(if (stuckIn) 0f else 14f, 0f), 6f)
        if (!stuckIn) {
            drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(tip.x - 22f, tip.y - 10f); lineTo(tip.x - 22f, tip.y + 10f); close() }, Color(0xFF90A4AE))
        }
        drawPath(Path().apply { moveTo(tail.x, tail.y); lineTo(tail.x + 26f, tail.y - 12f); lineTo(tail.x + 36f, tail.y); close() }, Color(0xFFFF5470))
        drawPath(Path().apply { moveTo(tail.x, tail.y); lineTo(tail.x + 26f, tail.y + 12f); lineTo(tail.x + 36f, tail.y); close() }, Color(0xFFFF8FA3))
    }

    override val progressLabel: String get() = "🎯 $arrows arrows · ◎ $bullseyes"
    override val achievement: String get() = "$bullseyes bullseye${if (bullseyes == 1) "" else "s"} from $arrows arrows"
}
