package com.focusquest.app.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Goal-based focus: each burst of focus drops a plank into the bridge. When it's
 * complete the explorer crosses and a new, wider gorge appears.
 */
class BridgeGame : FocusGame {
    private enum class Phase { Building, Walking, Fading }

    private val rnd = Random(5)
    private var phase = Phase.Building
    private var planksNeeded = 5
    private var placed = 0
    private var acc = 0f
    private var hero = 0f        // 0 = left cliff edge, 1 = right cliff
    private var fade = 0f
    private var bridges = 0
    private var dropAnim = 0f

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        dropAnim = (dropAnim - dt * 3f).coerceAtLeast(0f)
        when (phase) {
            Phase.Building -> {
                // ~3–5 s of focus per plank: long enough to be a real goal, short enough to stay rewarding
                acc = if (focused) acc + dt * (0.2f + 0.3f * focus) else (acc - dt * 0.12f).coerceAtLeast(0f)
                if (acc >= 1f) {
                    acc = 0f
                    placed++
                    dropAnim = 1f
                    if (placed >= planksNeeded) phase = Phase.Walking
                }
            }
            Phase.Walking -> {
                hero += dt * 0.35f
                if (hero >= 1f) { phase = Phase.Fading; fade = 0f; bridges++ }
            }
            Phase.Fading -> {
                fade += dt / 1.2f
                if (fade >= 1f) {
                    planksNeeded = (5 + bridges + rnd.nextInt(2)).coerceAtMost(10)
                    placed = 0; hero = 0f; acc = 0f
                    phase = Phase.Building
                }
            }
        }
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        val w = size.width
        val h = size.height
        val palette = listOf(
            listOf(Color(0xFF7FD1FF), Color(0xFFDDF3FF)),
            listOf(Color(0xFFFFB199), Color(0xFFFFE3C2)),
            listOf(Color(0xFFB39DDB), Color(0xFFF3E5F5)),
        )[bridges % 3]
        drawRect(Brush.verticalGradient(palette))
        // Distant peaks
        drawPath(Path().apply {
            moveTo(0f, h * 0.55f)
            for (i in 0..8) lineTo(i / 8f * w, h * (0.42f + 0.08f * abs(sin(i * 1.3f + bridges))))
            lineTo(w, h * 0.55f); close()
        }, Color(0xFF9FA8DA).copy(alpha = 0.6f))

        val cliffTop = h * 0.6f
        val plankW = w * 0.065f
        val gap = planksNeeded * plankW
        val leftEdge = (w - gap) / 2f
        val rightEdge = leftEdge + gap

        // River far below
        drawRect(Brush.verticalGradient(listOf(Color(0xFF4FC3F7), Color(0xFF0277BD)), h * 0.85f, h), Offset(leftEdge, h * 0.85f), Size(gap, h * 0.15f))
        for (i in 0 until 4) {
            val x = leftEdge + ((time * 60f + i * gap / 4) % gap)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, h * 0.9f + i * 12f), Offset(x + 30f, h * 0.9f + i * 12f), 3f)
        }
        // Cliffs
        cliff(Offset(0f, cliffTop), Size(leftEdge, h - cliffTop))
        cliff(Offset(rightEdge, cliffTop), Size(w - rightEdge, h - cliffTop))

        // Ropes
        val sag = 18f
        drawLine(Color(0xFF6D4C41), Offset(leftEdge, cliffTop - 50f), Offset(rightEdge, cliffTop - 50f), 4f)
        drawLine(Color(0xFF8D6E63), Offset(leftEdge, cliffTop - 50f), Offset(leftEdge, cliffTop), 6f)
        drawLine(Color(0xFF8D6E63), Offset(rightEdge, cliffTop - 50f), Offset(rightEdge, cliffTop), 6f)

        // Placed planks
        for (i in 0 until placed) {
            val x = leftEdge + i * plankW
            val drop = if (i == placed - 1) dropAnim * 60f else 0f
            drawRoundRect(Color(0xFFA1887F), Offset(x + 3f, cliffTop - 6f - drop + sag * sin(i / (planksNeeded - 1f) * 3.14f) * 0.4f), Size(plankW - 6f, 22f), CornerRadius(5f))
        }
        // Hovering next plank: fades in as focus builds, wobbles when focus drifts
        if (phase == Phase.Building) {
            val x = leftEdge + placed * plankW
            val wobble = sin(time * 6f) * (1f - acc) * 12f
            rotate(wobble, Offset(x + plankW / 2, cliffTop - 140f)) {
                drawRoundRect(
                    lerp(Color.White, Color(0xFFA1887F), acc).copy(alpha = 0.35f + 0.65f * acc),
                    Offset(x + 3f, cliffTop - 150f + acc * 60f), Size(plankW - 6f, 22f), CornerRadius(5f),
                )
            }
        }

        // Explorer
        val heroX = if (phase == Phase.Building) leftEdge - w * 0.06f else leftEdge - w * 0.06f + (gap + w * 0.12f) * hero
        val walking = phase == Phase.Walking
        explorer(Offset(heroX, cliffTop - 6f), h * 0.05f, if (walking) time * 10f else 0f, time)

        if (phase == Phase.Fading) drawRect(Color.Black.copy(alpha = sin(fade * 3.14f) * 0.9f))
    }

    private fun DrawScope.cliff(topLeft: Offset, s: Size) {
        if (s.width <= 0f) return
        drawRect(Color(0xFF8D6E63), topLeft, s)
        drawRect(Color(0xFF6D4C41), topLeft + Offset(0f, 40f), Size(s.width, s.height))
        drawRect(Color(0xFF66BB6A), topLeft - Offset(0f, 12f), Size(s.width, 24f))
    }

    private fun DrawScope.explorer(feet: Offset, u: Float, step: Float, time: Float) {
        val bob = if (step == 0f) sin(time * 2f) * 2f else abs(sin(step)) * -4f
        val hip = feet - Offset(0f, u * 1.1f - bob)
        drawLine(Color(0xFF37474F), hip, feet + Offset(sin(step) * u * 0.5f, 0f), u * 0.22f)
        drawLine(Color(0xFF37474F), hip, feet + Offset(-sin(step) * u * 0.5f, 0f), u * 0.22f)
        drawRoundRect(Color(0xFFFF7043), hip - Offset(u * 0.4f, u * 1.2f), Size(u * 0.8f, u * 1.3f), CornerRadius(u * 0.3f))
        drawRoundRect(Color(0xFF8D6E63), hip - Offset(u * 0.65f, u * 1.1f), Size(u * 0.3f, u * 0.8f), CornerRadius(u * 0.1f))
        val head = hip - Offset(0f, u * 1.6f)
        drawCircle(Color(0xFFFFCC80), u * 0.42f, head)
        drawRoundRect(Color(0xFF795548), head - Offset(u * 0.55f, u * 0.5f), Size(u * 1.1f, u * 0.22f), CornerRadius(u * 0.1f))
        drawRoundRect(Color(0xFF795548), head - Offset(u * 0.32f, u * 0.75f), Size(u * 0.64f, u * 0.3f), CornerRadius(u * 0.12f))
    }

    override val progressLabel: String get() = "Bridge ${bridges + 1} · $placed/$planksNeeded planks"
    override val achievement: String get() = "Built $bridges bridge${if (bridges == 1) "" else "s"}"
}
