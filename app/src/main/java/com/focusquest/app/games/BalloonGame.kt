package com.focusquest.app.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import kotlin.math.sin

/** Calm training: the balloon drifts upward while the player stays relaxed. */
class BalloonGame : FocusGame {
    private var altitude = 0f
    private var velocity = 0f
    private var drift = 0f
    private var burner = 0f

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        val target = if (focused) 20f + 90f * focus else -18f
        velocity = lerp(velocity, target, dt * 0.9f) // slow, gentle response suits relaxation
        altitude = (altitude + velocity * dt).coerceAtLeast(0f)
        if (altitude == 0f && velocity < 0f) velocity = 0f
        drift += dt * (18f + velocity.coerceAtLeast(0f) * 0.15f)
        burner = lerp(burner, if (focused) 0.3f + 0.7f * focus else 0f, dt * 3f)
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        val w = size.width
        val h = size.height
        val hi = (altitude / 2500f).coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(listOf(lerp(Color(0xFFFFC3A0), Color(0xFF4A6FA5), hi), lerp(Color(0xFFFFF0DB), Color(0xFFB8D8F2), hi))))
        drawCircle(Color(0xFFFFF5C0).copy(alpha = 0.5f), w * 0.16f, Offset(w * 0.75f, h * 0.2f))
        drawCircle(Color(0xFFFFF5C0), w * 0.09f, Offset(w * 0.75f, h * 0.2f))

        // Clouds pass downward as the balloon climbs, and sideways with the wind
        for (i in 0 until 6) {
            val y = ((i * 0.19f + altitude * 0.0006f) % 1.2f) * h - h * 0.1f
            val x = (((i * 0.31f) + drift * 0.0009f * (1 + i % 3)) % 1.4f) * w - w * 0.2f
            val c = Color.White.copy(alpha = 0.7f)
            val r = 45f + (i % 3) * 18f
            drawCircle(c, r, Offset(x, y)); drawCircle(c, r * 0.8f, Offset(x - r, y + r * 0.2f)); drawCircle(c, r * 0.85f, Offset(x + r, y + r * 0.15f))
        }

        // Hills sink away as altitude grows
        val groundY = h * 0.78f + altitude * 0.8f
        if (groundY < h + 200f) {
            for (layer in 0 until 3) {
                val base = groundY + layer * 50f
                val col = listOf(Color(0xFFA5D6A7), Color(0xFF81C784), Color(0xFF66BB6A))[layer]
                drawPath(Path().apply {
                    moveTo(0f, base)
                    for (i in 0..10) {
                        val x = i / 10f * w
                        lineTo(x, base - 50f * sin(i * 0.8f + drift * 0.004f * (layer + 1) + layer))
                    }
                    lineTo(w, h + 400f); lineTo(0f, h + 400f); close()
                }, col)
            }
        }
        // Birds
        for (i in 0 until 3) {
            val bx = ((drift * 0.003f + i * 0.33f) % 1.2f) * w - w * 0.1f
            val by = h * (0.3f + i * 0.08f) + sin(time * 2 + i) * 8f
            val flap = sin(time * 8 + i) * 10f
            drawLine(Color(0xFF37474F), Offset(bx - 18f, by - flap), Offset(bx, by), 3f)
            drawLine(Color(0xFF37474F), Offset(bx + 18f, by - flap), Offset(bx, by), 3f)
        }

        balloon(Offset(w / 2 + sin(time * 0.5f) * 14f, h * 0.45f + sin(time * 0.9f) * 8f), w * 0.17f, time)
    }

    private fun DrawScope.balloon(c: Offset, r: Float, time: Float) {
        val envelope = Path().apply {
            moveTo(c.x, c.y + r * 1.25f)
            cubicTo(c.x - r * 1.5f, c.y + r * 0.2f, c.x - r * 1.1f, c.y - r * 1.2f, c.x, c.y - r * 1.2f)
            cubicTo(c.x + r * 1.1f, c.y - r * 1.2f, c.x + r * 1.5f, c.y + r * 0.2f, c.x, c.y + r * 1.25f)
            close()
        }
        val stripes = listOf(Color(0xFFFF6F61), Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFF118AB2), Color(0xFFEF476F))
        clipPath(envelope) {
            val sw = r * 2.4f / stripes.size
            stripes.forEachIndexed { i, col -> drawRect(col, Offset(c.x - r * 1.2f + i * sw, c.y - r * 1.3f), Size(sw + 1f, r * 2.7f)) }
            drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent, Color.Black.copy(alpha = 0.22f)), c.x - r * 1.2f, c.x + r * 1.2f), Offset(c.x - r * 1.3f, c.y - r * 1.3f), Size(r * 2.6f, r * 2.7f))
        }
        val basketTop = c.y + r * 1.7f
        drawLine(Color(0xFF5D4037), Offset(c.x - r * 0.3f, c.y + r * 1.15f), Offset(c.x - r * 0.25f, basketTop), 3f)
        drawLine(Color(0xFF5D4037), Offset(c.x + r * 0.3f, c.y + r * 1.15f), Offset(c.x + r * 0.25f, basketTop), 3f)
        if (burner > 0.05f) {
            val fl = r * 0.35f * burner * (0.85f + 0.15f * sin(time * 25f))
            drawPath(Path().apply { moveTo(c.x - r * 0.08f, basketTop - 4f); quadraticTo(c.x, basketTop - 4f - fl * 2f, c.x + r * 0.08f, basketTop - 4f); close() }, Color(0xFFFFA726))
        }
        drawRoundRect(Color(0xFF8D6E63), Offset(c.x - r * 0.28f, basketTop), Size(r * 0.56f, r * 0.4f), CornerRadius(8f))
        drawLine(Color(0xFF6D4C41), Offset(c.x - r * 0.28f, basketTop + r * 0.13f), Offset(c.x + r * 0.28f, basketTop + r * 0.13f), 3f)
    }

    override val progressLabel: String get() = "%,d m".format(altitude.toInt())
    override val achievement: String get() = "Floated up to %,d m".format(altitude.toInt())
}
