package com.focusquest.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focusquest.app.AppViewModel
import com.focusquest.app.Screen
import com.focusquest.app.data.Metric
import com.focusquest.app.data.SessionRecord
import com.focusquest.app.ui.theme.FqColors

/** Shown right after a session ([fresh] = true) and when opening one from history. */
@Composable
fun ResultScreen(vm: AppViewModel, record: SessionRecord, achievement: String, plannedSec: Int) {
    val fresh = achievement.isNotEmpty()
    val eeg by vm.hub.state.collectAsStateWithLifecycle()
    val pct = record.focusPercent
    val headline = when {
        pct >= 80 -> "Laser focus! 🌟"
        pct >= 60 -> "Great session! 🎉"
        pct >= 40 -> "Nice work 👍"
        else -> "Good practice 💪"
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!fresh) IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            if (!fresh) IconButton(onClick = { vm.store.deleteSession(record.id); vm.back() }) {
                Icon(Icons.Filled.Delete, "Delete session", tint = FqColors.TextDim)
            }
        }
        Text(if (fresh) headline else record.game.title, style = MaterialTheme.typography.headlineLarge)
        if (fresh) Text(achievement, color = FqColors.TextDim)
        Spacer(Modifier.height(20.dp))
        AttentionRing(pct, "focused", focusColor(pct), size = 170.dp, stroke = 16.dp, suffix = "%")
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Points", "%,d".format(record.points), Modifier.weight(1f), FqColors.Warm)
            StatTile("Best streak", formatDuration(record.longestStreakSec), Modifier.weight(1f), FqColors.Focus)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (record.game.metric == Metric.CALM) StatTile("Avg calm", "${record.avgMeditation}", Modifier.weight(1f))
            else StatTile("Avg attention", "${record.avgAttention}", Modifier.weight(1f))
            StatTile("Goal level", "${record.startThreshold} → ${record.endThreshold}", Modifier.weight(1f), FqColors.Calm)
        }
        Spacer(Modifier.height(14.dp))
        Card {
            SectionTitle("${record.game.metric.label} during the session")
            AttentionChart(record.attentionTimeline, record.endThreshold)
            Text(
                "Time ${formatDuration(record.durationSec)} · focused ${formatDuration(record.focusedSec)} · ${record.source}",
                color = FqColors.TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (fresh) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.replaceTop(Screen.Session(record.game, if (plannedSec > 0) plannedSec else record.durationSec.coerceAtLeast(60))) },
                enabled = eeg.connected,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Play again") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
        }
    }
}
