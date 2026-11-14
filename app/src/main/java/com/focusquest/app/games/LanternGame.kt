package com.focusquest.app.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin
import kotlin.random.Random

/**
 * Calm training: staying relaxed slowly lights a paper lantern. Once it glows fully it
 * floats up and joins the others, so the night sky fills with the player's calm moments.
 */
class LanternGame : FocusGame {
    private class Lantern(var x: Float, var y: Float, val restY: Float, val phase: Float, val scale: Float, val warm: Color)

    private val rnd = Random(17)
    private val sky = ArrayList<Lantern>()
    private var glow = 0f
    private var released = 0
    private val warmColors = listOf(Color(0xFFFFA726), Color(0xFFFF7043), Color(0xFFFFCA28), Color(0xFFFF8A65))

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        glow = if (focused) glow + dt * (0.06f + 0.09f * focus) else (glow - dt * 0.025f).coerceAtLeast(0f)
        if (glow >= 1f) {
            glow = 0f
            released++
            sky += Lantern(
                x = 0.5f, y = 0.8f,
                restY = 0.08f + rnd.nextFloat() * 0.42f,
                phase = rnd.nextFloat() * 6f,
                scale = 0.6f + rnd.nextFloat() * 0.5f,
                warm = warmColors[rnd.nextInt(warmColors.size)],
            ).also { it.x = 0.5f }
            if (sky.size > 30) sky.removeAt(0)
        }
        sky.forEachIndexed { i, l ->
            if (l.y > l.restY) l.y -= dt * 0.05f
            val targetX = 0.1f + ((i * 0.37f + l.phase * 0.1f) % 0.8f)
            l.x += (targetX - l.x) * dt * 0.15f
        }
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.6f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF070B24), Color(0xFF1C1F4A), Color(0xFF3B2C5E)), 0f, horizon))
        for (i in 0 until 60) {
            val tw = (sin(time * 1.5f + i * 2.1f) + 1f) / 2f
            drawCircle(Color.White.copy(alpha = 0.25f + 0.5f * tw), 2f, Offset((i * 137 % 1000) / 1000f * w, (i * 71 % 1000) / 1000f * horizon * 0.9f))
        }
        val moon = Offset(w * 0.8f, h * 0.28f)
        drawCircle(Color(0xFFFFF8E1).copy(alpha = 0.12f), w * 0.11f, moon)
        drawCircle(Color(0xFFFFF8E1), w * 0.055f, moon)
        // Mountains
        drawPath(Path().apply {
            moveTo(0f, horizon)
            lineTo(w * 0.15f, horizon - h * 0.1f); lineTo(w * 0.32f, horizon - h * 0.04f); lineTo(w * 0.55f, horizon - h * 0.14f)
            lineTo(w * 0.75f, horizon - h * 0.05f); lineTo(w, horizon - h * 0.11f); lineTo(w, horizon); close()
        }, Color(0xFF120E2E))
        // Lake
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1A1D45), Color(0xFF080A1F)), horizon, h), Offset(0f, horizon), Size(w, h - horizon))
        for (i in 0 until 10) {
            val y = horizon + (i + 1) * (h - horizon) / 12f
            val x = ((i * 0.23f + time * 0.02f) % 1f) * w
            drawLine(Color.White.copy(alpha = 0.06f), Offset(x, y), Offset(x + w * 0.15f, y), 2f)
        }

        // Sky lanterns and their reflections
        sky.forEach { l ->
            val p = Offset(l.x * w + sin(time * 0.6f + l.phase) * 10f, l.y * h + sin(time * 0.8f + l.phase) * 6f)
            if (p.y < horizon) {
                val ry = horizon + (horizon - p.y) * 0.5f
                drawRoundRect(l.warm.copy(alpha = 0.15f), Offset(p.x - 10f * l.scale, ry), Size(20f * l.scale, 60f * l.scale), CornerRadius(10f))
            }
            lantern(p, 34f * l.scale, 1f, l.warm, time + l.phase)
        }

        // Boat holding the lantern being lit
        val boat = Offset(w / 2, h * 0.84f + sin(time * 1.2f) * 5f)
        drawPath(Path().apply {
            moveTo(boat.x - w * 0.2f, boat.y); lineTo(boat.x + w * 0.2f, boat.y)
            lineTo(boat.x + w * 0.14f, boat.y + 45f); lineTo(boat.x - w * 0.14f, boat.y + 45f); close()
        }, Color(0xFF3E2723))
        drawLine(Color(0xFF5D4037), Offset(boat.x, boat.y), Offset(boat.x, boat.y - h * 0.08f), 6f)
        val current = Offset(boat.x, boat.y - h * 0.08f - 50f)
        // Reflection of the current lantern's glow on the water
        drawCircle(Color(0xFFFFB74D).copy(alpha = 0.18f * glow), w * 0.18f * glow + 10f, Offset(boat.x, boat.y + 70f))
        lantern(current, 52f, glow, Color(0xFFFFA726), time)
    }

    /** @param lit 0 = dark paper, 1 = fully glowing. */
    private fun DrawScope.lantern(c: Offset, s: Float, lit: Float, warm: Color, time: Float) {
        val flicker = 0.9f + 0.1f * sin(time * 9f)
        if (lit > 0.02f) drawCircle(warm.copy(alpha = 0.22f * lit * flicker), s * (1.4f + lit), c)
        val body = androidx.compose.ui.graphics.lerp(Color(0xFF6D5A4A), warm, lit)
        drawRoundRect(
            Brush.verticalGradient(listOf(body, androidx.compose.ui.graphics.lerp(body, Color(0xFFFFF3E0), lit * 0.5f), body), c.y - s, c.y + s),
            Offset(c.x - s * 0.62f, c.y - s), Size(s * 1.24f, s * 2f), CornerRadius(s * 0.35f),
        )
        drawRect(Color(0xFF4E342E), Offset(c.x - s * 0.5f, c.y + s * 0.92f), Size(s, s * 0.12f))
        if (lit > 0.1f) drawCircle(Color(0xFFFFF8E1).copy(alpha = lit), s * 0.15f * flicker, Offset(c.x, c.y + s * 0.6f))
    }

    override val progressLabel: String get() = "$released 🏮 · ${(glow * 100).toInt()}%"
    override val achievement: String get() = "Released $released lantern${if (released == 1) "" else "s"}"
}
