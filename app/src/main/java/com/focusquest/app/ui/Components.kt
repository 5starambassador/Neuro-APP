package com.focusquest.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusquest.app.data.Profile
import com.focusquest.app.eeg.EegBands
import com.focusquest.app.eeg.EegState
import com.focusquest.app.eeg.LinkState
import com.focusquest.app.eeg.SourceKind
import com.focusquest.app.ui.theme.FqColors
import kotlin.math.ln
import kotlin.math.max

@Composable
fun Avatar(profile: Profile?, size: Dp = 44.dp) {
    val color = FqColors.avatars[(profile?.colorIndex ?: 0) % FqColors.avatars.size]
    Box(
        Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile?.name?.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}

@Composable
fun SignalChip(state: EegState, onClick: () -> Unit) {
    val (color, text) = when {
        state.link == LinkState.Connecting -> FqColors.Warm to "Connecting…"
        state.link == LinkState.Error -> FqColors.Danger to "Not connected"
        !state.connected -> FqColors.TextDim to "No headset"
        state.source == SourceKind.Simulator -> FqColors.Calm to "Simulator"
        state.signalOk -> FqColors.Focus to "Signal ${state.signalQuality}%"
        else -> FqColors.Warm to "Adjust headset"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AttentionRing(value: Int, label: String, color: Color, size: Dp = 120.dp, stroke: Dp = 12.dp, suffix: String = "") {
    val animated by animateFloatAsState(value / 100f, label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(color.copy(alpha = 0.15f), 135f, 270f, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * animated, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value$suffix", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = FqColors.TextDim)
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = Color.White) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = FqColors.Surface) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = FqColors.TextDim)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = modifier.padding(vertical = 8.dp))
}

/** Attention-over-time line with a dashed threshold line. */
@Composable
fun AttentionChart(values: List<Int>, threshold: Int?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val w = size.width
        val h = size.height
        for (g in listOf(25, 50, 75)) {
            val y = h - h * g / 100f
            drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(w, y), 1f)
        }
        if (threshold != null) {
            val y = h - h * threshold / 100f
            drawLine(FqColors.Warm.copy(alpha = 0.8f), Offset(0f, y), Offset(w, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        if (values.size < 2) return@Canvas
        val step = w / (values.size - 1)
        val line = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, h - h * v.coerceIn(0, 100) / 100f)
            if (i == 0) { line.moveTo(p.x, p.y); fill.moveTo(p.x, h); fill.lineTo(p.x, p.y) } else { line.lineTo(p.x, p.y); fill.lineTo(p.x, p.y) }
        }
        fill.lineTo(w, h); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(FqColors.Focus.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, FqColors.Focus, style = Stroke(4f, cap = StrokeCap.Round))
    }
}

@Composable
fun BarChart(values: List<Float>, labels: List<String>, modifier: Modifier = Modifier, color: Color = FqColors.Primary) {
    val maxV = max(values.maxOrNull() ?: 0f, 1f)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / values.size
            val barW = slot * 0.55f
            values.forEachIndexed { i, v ->
                val bh = size.height * (v / maxV)
                drawRoundRect(color.copy(alpha = 0.18f), Offset(i * slot + (slot - barW) / 2, 0f), Size(barW, size.height), androidx.compose.ui.geometry.CornerRadius(10f))
                if (v > 0f) drawRoundRect(color, Offset(i * slot + (slot - barW) / 2, size.height - bh), Size(barW, bh), androidx.compose.ui.geometry.CornerRadius(10f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach {
                Text(it, Modifier.weight(1f), color = FqColors.TextDim, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Log-scaled bars for the eight EEG frequency bands. */
@Composable
fun BandBars(bands: EegBands?, modifier: Modifier = Modifier) {
    val values = bands?.values()?.map { ln(1.0 + it).toFloat() } ?: List(8) { 0f }
    val colors = listOf(0xFF5C6BFF, 0xFF7C5CFF, 0xFF5CC8FF, 0xFF2EE6A6, 0xFFB6F25C, 0xFFFFE15C, 0xFFFFB547, 0xFFFF5C7A).map { Color(it) }
    val maxV = max(values.maxOrNull() ?: 1f, 1f)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                val frac by animateFloatAsState((v / maxV).coerceIn(0.03f, 1f), label = "band$i")
                Box(Modifier.weight(1f).fillMaxWidth().height(90.dp * frac).clip(RoundedCornerShape(6.dp)).background(colors[i]))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EegBands.LABELS.forEach {
                Text(it, Modifier.weight(1f), color = FqColors.TextDim, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

fun formatDuration(sec: Int): String = if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
