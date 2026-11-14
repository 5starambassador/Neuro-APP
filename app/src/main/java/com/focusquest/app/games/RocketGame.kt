package com.focusquest.app.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.sin
import kotlin.random.Random

class RocketGame : FocusGame {
    private class Star(var x: Float, var y: Float, val r: Float, val layer: Float)

    private val rnd = Random(7)
    private val stars = List(90) { Star(rnd.nextFloat(), rnd.nextFloat(), 1f + rnd.nextFloat() * 2.5f, 0.3f + rnd.nextFloat() * 0.7f) }
    private var altitude = 0f
    private var velocity = 0f
    private var thrust = 0f

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        val target = if (focused) 60f + 220f * focus else -25f
        velocity = lerp(velocity, target, dt * 1.8f)
        thrust = lerp(thrust, if (focused) 0.4f + 0.6f * focus else 0.05f, dt * 4f)
        altitude = (altitude + velocity * dt).coerceAtLeast(0f)
        if (altitude == 0f && velocity < 0f) velocity = 0f
        stars.forEach { s ->
            s.y += velocity * dt * 0.0012f * s.layer
            if (s.y > 1f) { s.y -= 1f; s.x = rnd.nextFloat() }
            if (s.y < 0f) { s.y += 1f; s.x = rnd.nextFloat() }
        }
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        val space = (altitude / 4000f).coerceIn(0f, 1f)
        val top = lerp(Color(0xFF4FB3FF), Color(0xFF05060F), space)
        val bottom = lerp(Color(0xFFBFE6FF), Color(0xFF1B1446), space)
        drawRect(Brush.verticalGradient(listOf(top, bottom)))

        val starAlpha = (0.15f + space).coerceAtMost(1f)
        stars.forEach { s ->
            drawCircle(Color.White.copy(alpha = starAlpha * s.layer), s.r * s.layer * 1.6f, Offset(s.x * size.width, s.y * size.height))
        }

        // Ground & launch pad, visible near lift-off
        val groundY = size.height * 0.86f + altitude * 2.2f
        if (groundY < size.height + 40f) {
            drawRect(Color(0xFF2E7D4F), Offset(0f, groundY), Size(size.width, size.height))
            drawRect(Color(0xFF5B5F73), Offset(size.width / 2 - 70f, groundY - 14f), Size(140f, 14f))
        }
        // Clouds drifting past in the lower atmosphere
        if (space < 0.6f) {
            val ca = (0.6f - space) / 0.6f
            for (i in 0 until 4) {
                val cy = ((i * 0.27f + altitude * 0.0009f) % 1.1f) * size.height
                val cx = ((i * 0.37f + time * 0.01f) % 1f) * size.width
                cloud(Offset(cx, cy), 60f + i * 14f, Color.White.copy(alpha = 0.75f * ca))
            }
        }

        val cx = size.width / 2
        val cy = size.height * 0.6f + sin(time * 2.4f) * 6f
        rocket(Offset(cx, cy), size.minDimension * 0.07f, thrust, time)
    }

    private fun DrawScope.cloud(c: Offset, r: Float, color: Color) {
        drawCircle(color, r, c)
        drawCircle(color, r * 0.75f, c + Offset(-r * 0.9f, r * 0.2f))
        drawCircle(color, r * 0.8f, c + Offset(r * 0.9f, r * 0.15f))
    }

    private fun DrawScope.rocket(c: Offset, u: Float, thrust: Float, time: Float) {
        // Flame
        val flicker = 0.85f + 0.15f * sin(time * 30f)
        val flameLen = u * (1.2f + 3.2f * thrust) * flicker
        val flame = Path().apply {
            moveTo(c.x - u * 0.55f, c.y + u * 1.6f)
            quadraticTo(c.x, c.y + u * 1.6f + flameLen * 1.3f, c.x + u * 0.55f, c.y + u * 1.6f)
            close()
        }
        drawPath(flame, Brush.verticalGradient(listOf(Color(0xFFFFF3A0), Color(0xFFFF8A00), Color(0x00FF3D00)), c.y + u * 1.6f, c.y + u * 1.6f + flameLen * 1.3f))
        // Fins
        val fins = Path().apply {
            moveTo(c.x - u * 0.7f, c.y + u * 0.6f); lineTo(c.x - u * 1.5f, c.y + u * 1.8f); lineTo(c.x - u * 0.7f, c.y + u * 1.5f); close()
            moveTo(c.x + u * 0.7f, c.y + u * 0.6f); lineTo(c.x + u * 1.5f, c.y + u * 1.8f); lineTo(c.x + u * 0.7f, c.y + u * 1.5f); close()
        }
        drawPath(fins, Color(0xFFFF5470))
        // Body
        val body = Path().apply {
            moveTo(c.x, c.y - u * 2.6f)
            cubicTo(c.x + u * 1.3f, c.y - u * 1.4f, c.x + u * 0.9f, c.y + u, c.x + u * 0.7f, c.y + u * 1.6f)
            lineTo(c.x - u * 0.7f, c.y + u * 1.6f)
            cubicTo(c.x - u * 0.9f, c.y + u, c.x - u * 1.3f, c.y - u * 1.4f, c.x, c.y - u * 2.6f)
            close()
        }
        drawPath(body, Brush.horizontalGradient(listOf(Color(0xFFE9ECF5), Color.White, Color(0xFFB9BFD3)), c.x - u, c.x + u))
        drawCircle(Color(0xFF3B4A7A), u * 0.5f, Offset(c.x, c.y - u * 0.6f))
        drawCircle(Color(0xFF7FD1FF), u * 0.36f, Offset(c.x, c.y - u * 0.6f))
    }

    override val progressLabel: String get() = "%,d m".format(altitude.toInt())
    override val achievement: String get() = "Reached %,d m".format(altitude.toInt())
}
