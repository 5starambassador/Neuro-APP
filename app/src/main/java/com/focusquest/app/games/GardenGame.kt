package com.focusquest.app.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class GardenGame : FocusGame {
    private val rnd = Random(11)
    private val petalColors = listOf(
        Color(0xFFFF6FA5), Color(0xFFFFB547), Color(0xFFB57BFF), Color(0xFFFF5C5C), Color(0xFF5CC8FF), Color(0xFFFFE15C),
    )
    private val bloomed = mutableListOf<Color>()
    private var currentColor = petalColors[0]
    private var growth = 0f
    private var rain = 1f

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        rain = lerp(rain, if (focused) 0f else 1f, dt * 1.2f)
        growth = if (focused) growth + dt * (0.035f + 0.06f * focus) else (growth - dt * 0.006f).coerceAtLeast(0f)
        if (growth >= 1f) {
            bloomed += currentColor
            growth = 0f
            currentColor = petalColors[rnd.nextInt(petalColors.size)]
        }
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(lerp(Color(0xFF7CC8FF), Color(0xFF59647A), rain), lerp(Color(0xFFDFF4FF), Color(0xFF9AA3B5), rain))))

        // Sun
        val sunAlpha = 1f - rain
        val sun = Offset(w * 0.8f, h * 0.14f)
        drawCircle(Color(0xFFFFE36E).copy(alpha = 0.25f * sunAlpha), w * 0.13f, sun)
        drawCircle(Color(0xFFFFD43B).copy(alpha = sunAlpha), w * 0.08f, sun)

        // Clouds roll in when focus drops
        for (i in 0 until 3) {
            val x = ((i * 0.4f + time * 0.015f) % 1.3f - 0.15f) * w
            val y = h * (0.1f + i * 0.07f)
            val c = lerp(Color.White, Color(0xFF6B7385), rain).copy(alpha = 0.35f + 0.6f * rain)
            drawCircle(c, 55f, Offset(x, y)); drawCircle(c, 42f, Offset(x - 55f, y + 12f)); drawCircle(c, 45f, Offset(x + 55f, y + 10f))
        }
        // Rain
        if (rain > 0.05f) {
            for (i in 0 until 70) {
                val rx = (i * 97 % 1000) / 1000f * w
                val ry = (((i * 53 % 1000) / 1000f + time * 1.4f) % 1f) * h
                drawLine(Color(0xFFCFE3FF).copy(alpha = 0.6f * rain), Offset(rx, ry), Offset(rx - 6f, ry + 22f), 2.5f)
            }
        }

        // Ground: a meadow strip where finished flowers are planted, then soil
        val groundY = h * 0.74f
        val meadowH = h * 0.12f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF4CBF5A), Color(0xFF2F8A3E)), groundY, groundY + meadowH), Offset(0f, groundY), Size(w, meadowH))
        drawRect(Color(0xFF6B4A2B), Offset(0f, groundY + meadowH), Size(w, h))

        // Bloomed garden: two rows of seven in the meadow
        val r = w * 0.03f
        bloomed.takeLast(14).forEachIndexed { i, color ->
            val row = i / 7
            val x = w * (0.07f + (i % 7) * 0.143f + row * 0.07f).let { if (it > 0.97f) it - 0.9f else it }
            val y = groundY + meadowH * (0.4f + row * 0.4f)
            val sway = sin(time * 1.5f + i) * 3f
            drawLine(Color(0xFF1F6B2A), Offset(x, y + r * 1.2f), Offset(x + sway, y - r * 0.4f), r * 0.25f)
            miniFlower(Offset(x + sway, y - r * 0.6f), r, color)
        }

        // The flower currently growing
        val base = Offset(w / 2, groundY)
        val stemH = h * 0.08f + h * 0.32f * growth
        val sway = sin(time * 1.2f) * 6f * (0.3f + growth)
        val top = Offset(base.x + sway, base.y - stemH)
        drawLine(Color(0xFF2E7D32), base, top, 12f)
        if (growth > 0.25f) leaf(Offset(base.x + sway * 0.4f, base.y - stemH * 0.4f), 34f * growth, 1f)
        if (growth > 0.4f) leaf(Offset(base.x + sway * 0.6f, base.y - stemH * 0.6f), 30f * growth, -1f)

        val open = ((growth - 0.45f) / 0.55f).coerceIn(0f, 1f)
        if (open <= 0f) {
            drawCircle(Color(0xFF4CAF50), 18f + 10f * growth, top)
            drawCircle(currentColor.copy(alpha = 0.7f), 10f + 8f * growth, top + Offset(0f, -6f))
        } else {
            val petal = 20f + 46f * open
            for (k in 0 until 8) {
                val a = k * (Math.PI / 4).toFloat() + time * 0.15f
                val p = top + Offset(cos(a) * petal * 0.75f, sin(a) * petal * 0.75f)
                drawCircle(currentColor, petal * 0.55f, p)
            }
            drawCircle(Color(0xFFFFD43B), petal * 0.42f, top)
            drawCircle(Color(0xFFE09F00), petal * 0.42f, top, style = Stroke(4f))
        }
    }

    private fun DrawScope.leaf(at: Offset, s: Float, dir: Float) {
        rotate(if (dir > 0) -35f else 35f, at) {
            drawOval(Color(0xFF43A047), at + Offset(if (dir > 0) 0f else -s * 2f, -s * 0.4f), Size(s * 2f, s * 0.8f))
        }
    }

    private fun DrawScope.miniFlower(c: Offset, r: Float, color: Color) {
        for (k in 0 until 5) {
            val a = k * (2 * Math.PI / 5).toFloat()
            drawCircle(color, r * 0.6f, c + Offset(cos(a) * r * 0.7f, sin(a) * r * 0.7f))
        }
        drawCircle(Color(0xFFFFD43B), r * 0.45f, c)
    }

    override val progressLabel: String get() = "${bloomed.size} 🌸  ${(growth * 100).toInt()}%"
    override val achievement: String get() = "Grew ${bloomed.size} flower${if (bloomed.size == 1) "" else "s"}"
}
