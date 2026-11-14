package com.focusquest.app.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin

class DriveGame : FocusGame {
    private var speed = 0f       // km/h
    private var distance = 0f    // metres
    private var travel = 0f      // scroll phase for road markings

    override fun update(dt: Float, focus: Float, focused: Boolean) {
        val target = if (focused) 50f + 130f * focus else 8f
        speed = lerp(speed, target, dt * (if (focused) 1.2f else 2f))
        distance += speed / 3.6f * dt
        travel += speed * dt * 0.02f
    }

    override fun draw(scope: DrawScope, time: Float) = with(scope) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.42f

        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0B2B), Color(0xFF3A1C6E), Color(0xFFFF6F91)), 0f, horizon))
        for (i in 0 until 40) {
            val sx = (i * 137 % 1000) / 1000f * w
            val sy = (i * 71 % 1000) / 1000f * horizon * 0.8f
            drawCircle(Color.White.copy(alpha = 0.3f + 0.5f * ((i % 5) / 5f)), 1.6f, Offset(sx, sy))
        }
        val moon = Offset(w * 0.8f, horizon * 0.6f)
        drawCircle(Color(0xFFFFF4D6).copy(alpha = 0.15f), w * 0.1f, moon)
        drawCircle(Color(0xFFFFF4D6), w * 0.06f, moon)
        // Distant hills
        val hills = Path().apply {
            moveTo(0f, horizon)
            for (i in 0..10) {
                val x = i / 10f * w
                lineTo(x, horizon - 30f - 25f * sin(i * 1.7f + distance * 0.0002f))
            }
            lineTo(w, horizon); close()
        }
        drawPath(hills, Color(0xFF241046))
        drawRect(Color(0xFF120A26), Offset(0f, horizon), Size(w, h - horizon))

        // Road (perspective trapezoid)
        val road = Path().apply {
            moveTo(w * 0.47f, horizon); lineTo(w * 0.53f, horizon)
            lineTo(w * 1.05f, h); lineTo(-w * 0.05f, h); close()
        }
        drawPath(road, Brush.verticalGradient(listOf(Color(0xFF2A2440), Color(0xFF3B3557)), horizon, h))

        // Moving lane markings and roadside lamps, placed in depth (z: 0 far .. 1 near)
        val segments = 14
        for (i in 0 until segments) {
            val z = ((i + travel) % segments) / segments
            val z2 = (z + 0.035f).coerceAtMost(1f)
            val y1 = horizon + (h - horizon) * z * z
            val y2 = horizon + (h - horizon) * z2 * z2
            val half1 = 2f + 10f * z
            drawPath(
                Path().apply {
                    moveTo(w / 2 - half1 * 0.5f, y1); lineTo(w / 2 + half1 * 0.5f, y1)
                    lineTo(w / 2 + half1 * 0.6f, y2); lineTo(w / 2 - half1 * 0.6f, y2); close()
                },
                Color(0xFFFFE08A),
            )
            if (i % 2 == 0) {
                val edge = 0.03f + 0.55f * z
                val lampH = 10f + 140f * z * z
                for (side in listOf(-1f, 1f)) {
                    val x = w / 2 + side * w * edge
                    drawLine(Color(0xFF6E6A86), Offset(x, y1), Offset(x, y1 - lampH), 1f + 4f * z)
                    drawCircle(Color(0xFF7DF9FF).copy(alpha = 0.25f), 4f + 20f * z, Offset(x, y1 - lampH))
                    drawCircle(Color(0xFFBFFCFF), 1f + 5f * z, Offset(x, y1 - lampH))
                }
            }
        }

        // Speed streaks
        val streak = ((speed - 60f) / 120f).coerceIn(0f, 1f)
        if (streak > 0f) {
            for (i in 0 until 16) {
                val x = (i * 61 % 100) / 100f * w
                val y = horizon + ((i * 37 % 100) / 100f + time * 2f) % 1f * (h - horizon)
                drawLine(Color.White.copy(alpha = 0.18f * streak), Offset(x, y), Offset(x + (x - w / 2) * 0.12f, y + 40f), 2f)
            }
        }

        // Car
        val cw = w * 0.3f
        val ch = cw * 0.5f
        val bounce = sin(time * 18f) * (speed / 180f) * 2.5f
        val left = w / 2 - cw / 2
        val top = h * 0.8f + bounce
        // Headlight beams reaching up the road; brighter the faster you go
        val beamAlpha = 0.06f + 0.16f * (speed / 180f).coerceIn(0f, 1f)
        val reach = top - (top - horizon) * (0.35f + 0.4f * (speed / 180f).coerceIn(0f, 1f))
        for (side in listOf(-1f, 1f)) {
            val bx = w / 2 + side * cw * 0.3f
            drawPath(
                Path().apply {
                    moveTo(bx - cw * 0.06f, top); lineTo(bx + cw * 0.06f, top)
                    lineTo(w / 2 + side * cw * 0.12f + cw * 0.18f, reach); lineTo(w / 2 + side * cw * 0.12f - cw * 0.18f, reach); close()
                },
                Brush.verticalGradient(listOf(Color(0x00FFF6C8), Color(0xFFFFF6C8).copy(alpha = beamAlpha)), reach, top),
            )
        }
        drawRoundRect(Color(0xFFFF3D7F), Offset(left, top), Size(cw, ch), CornerRadius(22f))
        drawRoundRect(Color(0xFF1C1633), Offset(left + cw * 0.15f, top - ch * 0.35f), Size(cw * 0.7f, ch * 0.5f), CornerRadius(18f))
        drawRoundRect(Color(0xFF7DF9FF).copy(alpha = 0.6f), Offset(left + cw * 0.2f, top - ch * 0.28f), Size(cw * 0.6f, ch * 0.3f), CornerRadius(12f))
        drawRoundRect(Color(0xFFFF1744), Offset(left + cw * 0.06f, top + ch * 0.28f), Size(cw * 0.18f, ch * 0.16f), CornerRadius(6f))
        drawRoundRect(Color(0xFFFF1744), Offset(left + cw * 0.76f, top + ch * 0.28f), Size(cw * 0.18f, ch * 0.16f), CornerRadius(6f))
        drawRoundRect(Color(0xFF0D0B18), Offset(left + cw * 0.05f, top + ch * 0.85f), Size(cw * 0.22f, ch * 0.3f), CornerRadius(8f))
        drawRoundRect(Color(0xFF0D0B18), Offset(left + cw * 0.73f, top + ch * 0.85f), Size(cw * 0.22f, ch * 0.3f), CornerRadius(8f))
    }

    override val progressLabel: String get() = "${speed.toInt()} km/h · %.2f km".format(distance / 1000f)
    override val achievement: String get() = "Drove %.2f km".format(distance / 1000f)
}
