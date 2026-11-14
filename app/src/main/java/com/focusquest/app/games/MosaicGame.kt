package com.focusquest.app.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sustained focus: every moment of focus flips a tile to uncover a hidden picture.
 * Losing focus never takes progress away, which keeps it encouraging for younger kids.
 */
class MosaicGame : FocusGame {
    private val cols = 6
    private val rows = 9
    private val total = cols * rows
    private val rnd = Random(21)
    private var order = (0 until total).shuffled(rnd)
    private val revealedAt = FloatArray(total) { -1f }
    private var revealed = 0
    private var acc = 0f
    private var clock = 0f
    private var picture = 0
    private var completed = 0
    private var celebrate = 0f

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        clock += dt
        if (celebrate > 0f) {
            celebrate -= dt
            if (celebrate <= 0f) {
                picture++
                revealed = 0
                acc = 0f
                order = (0 until total).shuffled(rnd)
                revealedAt.fill(-1f)
            }
            return
        }
        if (focused) acc += dt * (0.9f + 1.6f * focus)
        while (acc >= 1f && revealed < total) {
            acc -= 1f
            revealedAt[order[revealed]] = clock
            revealed++
        }
        if (revealed == total) {
            completed++
            celebrate = 3f
        }
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        when (picture % 4) {
            0 -> mountains(time)
            1 -> seaside(time)
            2 -> nightVillage(time)
            else -> hotAirFestival(time)
        }
        val tw = size.width / cols
        val th = size.height / rows
        for (i in 0 until total) {
            val t = revealedAt[i]
            val alpha = if (t < 0f) 1f else (1f - (clock - t) / 0.45f).coerceIn(0f, 1f)
            if (alpha <= 0f) continue
            val cx = i % cols
            val cy = i / cols
            val hue = ((cx * 13 + cy * 7) % 10) / 10f
            val base = Color(0xFF3B2E8A).copy(red = 0.23f + hue * 0.12f)
            val shrink = (1f - alpha) * 0.5f
            drawRoundRect(
                Brush.linearGradient(listOf(base, Color(0xFF241A5C))),
                Offset(cx * tw + 3f + tw * shrink / 2, cy * th + 3f + th * shrink / 2),
                Size((tw - 6f) * (1f - shrink), (th - 6f) * (1f - shrink)),
                CornerRadius(14f),
                alpha = alpha,
            )
            if (t < 0f) {
                val sparkle = (sin(time * 2f + i * 1.7f) + 1f) / 2f
                drawCircle(Color.White.copy(alpha = 0.08f + 0.1f * sparkle), 6f, Offset(cx * tw + tw / 2, cy * th + th / 2))
            }
        }
        if (celebrate > 0f) {
            for (k in 0 until 40) {
                val a = k * 0.6f + time
                val d = (3f - celebrate) * size.minDimension * 0.25f + (k % 5) * 30f
                drawCircle(
                    listOf(Color(0xFFFFD43B), Color(0xFFFF6FA5), Color(0xFF2EE6A6), Color(0xFF5CC8FF))[k % 4].copy(alpha = (celebrate / 3f)),
                    10f, Offset(size.width / 2 + cos(a) * d, size.height / 2 + sin(a) * d),
                )
            }
        }
    }

    private fun DrawScope.mountains(time: Float) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFF9A8B), Color(0xFFFFD3A5))))
        drawCircle(Color(0xFFFFF1B5), w * 0.12f, Offset(w * 0.5f, h * 0.38f))
        drawPath(Path().apply { moveTo(0f, h * 0.6f); lineTo(w * 0.3f, h * 0.3f); lineTo(w * 0.6f, h * 0.6f); close() }, Color(0xFF6A4C93))
        drawPath(Path().apply { moveTo(w * 0.35f, h * 0.6f); lineTo(w * 0.7f, h * 0.25f); lineTo(w * 1.05f, h * 0.6f); close() }, Color(0xFF4A3470))
        drawPath(Path().apply { moveTo(w * 0.62f, h * 0.33f); lineTo(w * 0.7f, h * 0.25f); lineTo(w * 0.78f, h * 0.33f); close() }, Color.White)
        drawRect(Brush.verticalGradient(listOf(Color(0xFF5FA8D3), Color(0xFF1B4965)), h * 0.6f, h), Offset(0f, h * 0.6f), Size(w, h * 0.4f))
        for (i in 0 until 6) drawLine(Color.White.copy(alpha = 0.3f), Offset(w * (0.2f + i * 0.1f) + sin(time + i) * 20f, h * (0.66f + i * 0.04f)), Offset(w * (0.3f + i * 0.1f) + sin(time + i) * 20f, h * (0.66f + i * 0.04f)), 4f)
    }

    private fun DrawScope.seaside(time: Float) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF4FC3F7), Color(0xFFB3E5FC))))
        drawCircle(Color(0xFFFFEB3B), w * 0.09f, Offset(w * 0.8f, h * 0.15f))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0288D1), Color(0xFF01579B)), h * 0.5f, h * 0.8f), Offset(0f, h * 0.5f), Size(w, h * 0.3f))
        drawRect(Color(0xFFFFE0B2), Offset(0f, h * 0.8f), Size(w, h * 0.2f))
        val bx = w * 0.4f + sin(time * 0.3f) * w * 0.1f
        val by = h * 0.56f + sin(time * 1.5f) * 6f
        drawPath(Path().apply { moveTo(bx - 80f, by); lineTo(bx + 80f, by); lineTo(bx + 55f, by + 35f); lineTo(bx - 55f, by + 35f); close() }, Color(0xFFD84315))
        drawPath(Path().apply { moveTo(bx, by - 170f); lineTo(bx, by - 5f); lineTo(bx + 90f, by - 5f); close() }, Color.White)
        drawCircle(Color(0xFFFF7043), 60f, Offset(w * 0.15f, h * 0.88f))
        drawRect(Color(0xFF8D6E63), Offset(w * 0.75f, h * 0.7f), Size(14f, h * 0.15f))
        for (k in 0 until 5) drawLine(Color(0xFF2E7D32), Offset(w * 0.755f, h * 0.7f), Offset(w * 0.755f + cos(k * 0.9f + 2.2f) * 110f, h * 0.7f + sin(k * 0.9f + 2.2f) * 70f + 40f), 12f)
    }

    private fun DrawScope.nightVillage(time: Float) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1033), Color(0xFF2B2D6E))))
        for (i in 0 until 50) drawCircle(Color.White.copy(alpha = 0.4f + 0.4f * ((sin(time * 2 + i) + 1) / 2)), 2.5f, Offset((i * 137 % 1000) / 1000f * w, (i * 71 % 1000) / 1000f * h * 0.5f))
        drawCircle(Color(0xFFFFF8E1), w * 0.08f, Offset(w * 0.75f, h * 0.18f))
        drawCircle(Color(0xFF0B1033), w * 0.07f, Offset(w * 0.72f, h * 0.17f))
        drawRect(Color(0xFF1B5E20), Offset(0f, h * 0.7f), Size(w, h * 0.3f))
        for (i in 0 until 4) {
            val x = w * (0.08f + i * 0.23f)
            val hh = h * (0.12f + (i % 2) * 0.04f)
            drawRect(Color(0xFF5D4037), Offset(x, h * 0.7f - hh), Size(w * 0.17f, hh))
            drawPath(Path().apply { moveTo(x - 15f, h * 0.7f - hh); lineTo(x + w * 0.085f, h * 0.7f - hh - 90f); lineTo(x + w * 0.17f + 15f, h * 0.7f - hh); close() }, Color(0xFFB71C1C))
            drawRect(Color(0xFFFFD54F), Offset(x + w * 0.05f, h * 0.7f - hh * 0.7f), Size(w * 0.06f, hh * 0.3f))
        }
    }

    private fun DrawScope.hotAirFestival(time: Float) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF81D4FA), Color(0xFFE1F5FE))))
        drawRect(Color(0xFF9CCC65), Offset(0f, h * 0.82f), Size(w, h * 0.18f))
        val colors = listOf(Color(0xFFE53935), Color(0xFFFFB300), Color(0xFF8E24AA), Color(0xFF00897B))
        colors.forEachIndexed { i, c ->
            val x = w * (0.2f + i * 0.2f)
            val y = h * (0.25f + (i % 2) * 0.2f) + sin(time + i) * 15f
            val r = w * 0.08f
            drawCircle(c, r, Offset(x, y))
            drawLine(Color(0xFF5D4037), Offset(x - r * 0.4f, y + r * 0.9f), Offset(x - r * 0.2f, y + r * 1.5f), 3f)
            drawLine(Color(0xFF5D4037), Offset(x + r * 0.4f, y + r * 0.9f), Offset(x + r * 0.2f, y + r * 1.5f), 3f)
            drawRect(Color(0xFF795548), Offset(x - r * 0.22f, y + r * 1.5f), Size(r * 0.44f, r * 0.35f))
        }
    }

    override val progressLabel: String get() = "Picture ${picture + 1} · ${revealed * 100 / total}%"
    override val achievement: String
        get() = if (completed > 0) "Revealed $completed picture${if (completed == 1) "" else "s"}" else "Uncovered ${revealed * 100 / total}% of a picture"
}
