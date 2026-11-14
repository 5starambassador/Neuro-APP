package com.focusquest.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focusquest.app.AppViewModel
import com.focusquest.app.Screen
import com.focusquest.app.classroom.LiveStudent
import com.focusquest.app.data.GameId
import com.focusquest.app.data.Metric
import com.focusquest.app.ui.theme.FqColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CHEERS = listOf("Great focus! 🌟", "Keep going! 💪", "Nice and calm 🌙", "You've got this! 🚀")

@Composable
fun TrainerScreen(vm: AppViewModel) {
    val state by vm.trainer.state.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val groups by vm.store.groupSessions.collectAsStateWithLifecycle()

    // A trainer tablet should stay awake while a class is running.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    var selected by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Trainer dashboard", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.push(Screen.Reports(trainer = true)) }) { Icon(Icons.Filled.Assessment, "Reports") }
            }
        }

        if (!state.running) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                StartClassCard(settings.className) { name ->
                    vm.store.updateSettings { it.copy(className = name) }
                    vm.trainer.start(name)
                }
            }
            if (groups.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Past group sessions") }
                items(groups.take(20), span = { GridItemSpan(maxLineSpan) }) { g ->
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = FqColors.Surface,
                        modifier = Modifier.fillMaxWidth().clickable { vm.push(Screen.GroupReport(g.id)) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(g.game.icon, null, tint = FqColors.Primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${g.game.title} · ${g.className}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(g.startedAt)) +
                                        " · ${g.resultIds.size}/${g.studentIds.size} results",
                                    color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text("Report", color = FqColors.Primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            return@LazyVerticalGrid
        }

        // ---- class running ---------------------------------------------------------
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.className, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.students.values.count { it.connected }} of ${state.students.size} students connected",
                            color = FqColors.TextDim,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Join code", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                        Text(state.code, fontSize = 32.sp, fontWeight = FontWeight.Black, color = FqColors.Focus, letterSpacing = 4.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Students: Settings → Join a class. Pick “${state.className}” or enter ${state.host ?: "this tablet's IP"} : ${state.port}.",
                    color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                )
                state.error?.let { Text(it, color = FqColors.Warm, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.trainer.cheer(null, CHEERS.random()) }) {
                        Icon(Icons.Filled.Celebration, null); Spacer(Modifier.width(6.dp)); Text("Cheer all")
                    }
                    OutlinedButton(onClick = { confirmStop = true }) { Text("End class") }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { WebDashboardCard(vm, state.host) }

        item(span = { GridItemSpan(maxLineSpan) }) { GroupControl(vm) }

        val sorted = state.students.values.sortedWith(
            compareByDescending<LiveStudent> { it.alerts().isNotEmpty() && it.connected }
                .thenByDescending { it.connected }
                .thenBy { it.profile.name.lowercase() },
        )
        if (sorted.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Waiting for students to join…", color = FqColors.TextDim, modifier = Modifier.padding(8.dp))
            }
        }
        items(sorted, key = { it.profile.id }) { st -> StudentTile(st) { selected = st.profile.id } }
    }

    selected?.let { id ->
        val st = state.students[id]
        if (st == null) selected = null else StudentDialog(vm, st) { selected = null }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("End the class?") },
            text = { Text("Students will be disconnected. Any group session in progress is saved first.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.trainer.stop() }) { Text("End class") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StartClassCard(initialName: String, onStart: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    Card {
        Text("Start a class", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Students on the same Wi‑Fi join from their phones and appear here live: attention, calm, signal and game progress, " +
                "updated every second. Nothing leaves your local network.",
            color = FqColors.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(30) },
            label = { Text("Class name") }, placeholder = { Text("e.g. Grade 4B Focus Club") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onStart(name.trim().ifEmpty { "FocusQuest class" }) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Start class")
        }
    }
}

@Composable
private fun WebDashboardCard(vm: AppViewModel, host: String?) {
    val web by vm.webDashboard.state.collectAsStateWithLifecycle()
    if (!web.running) return
    var showPin by remember { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Web dashboard", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Open on a laptop or projector on this Wi‑Fi:",
                    color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "http://${host ?: "<tablet-ip>"}:${web.port}",
                    color = FqColors.Calm, style = MaterialTheme.typography.titleMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("PIN", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                // Hidden by default so students glancing at the tablet don't see it.
                Text(
                    if (showPin) web.pin else "••••••",
                    fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp,
                    modifier = Modifier.clickable { showPin = !showPin },
                )
                Text(if (showPin) "tap to hide" else "tap to show", color = FqColors.TextDim, fontSize = 11.sp)
            }
        }
        if (web.viewers > 0) {
            Spacer(Modifier.height(6.dp))
            Text("${web.viewers} browser${if (web.viewers == 1) "" else "s"} watching", color = FqColors.Focus, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GroupControl(vm: AppViewModel) {
    val state by vm.trainer.state.collectAsStateWithLifecycle()
    val group = state.group
    var game by remember { mutableStateOf(GameId.ROCKET) }
    var duration by remember { mutableIntStateOf(300) }
    val connected = state.students.values.count { it.connected }

    Card {
        if (group == null) {
            Text("Group session", style = MaterialTheme.typography.titleMedium)
            Text("Start the same game on every connected student's phone.", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GameId.entries) { g -> FilterChip(selected = game == g, onClick = { game = g }, label = { Text(g.title) }) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(120, 300, 600, 900)) { d -> FilterChip(selected = duration == d, onClick = { duration = d }, label = { Text("${d / 60} min") }) }
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = { vm.trainer.startGroup(game, duration) }, enabled = connected > 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected > 0) "Start ${game.title} for $connected student${if (connected == 1) "" else "s"}" else "No students connected")
            }
        } else {
            val ended = group.endedAt != 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(group.game.icon, null, tint = FqColors.Focus)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${group.game.title} · group session", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${group.resultIds.size}/${group.studentIds.size} results in" + if (ended) " · ended" else "",
                        color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ended && !group.allResultsIn) {
                    OutlinedButton(onClick = { vm.trainer.endGroup() }) { Text("End for everyone") }
                }
                Button(
                    onClick = { vm.trainer.closeGroup()?.let { vm.push(Screen.GroupReport(it.id)) } },
                    colors = ButtonDefaults.buttonColors(containerColor = if (group.allResultsIn || ended) FqColors.Primary else FqColors.SurfaceHigh),
                ) { Text(if (group.allResultsIn) "View live session report" else "Save & view report") }
            }
        }
    }
}

@Composable
private fun StudentTile(st: LiveStudent, onClick: () -> Unit) {
    val alerts = st.alerts()
    val s = st.session
    val calm = s?.game?.metric == Metric.CALM
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = FqColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (alerts.isNotEmpty() && st.connected) Modifier.border(2.dp, FqColors.Danger.copy(alpha = 0.8f), RoundedCornerShape(18.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(st.profile, 34.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        when {
                            !st.connected -> if (st.everConnected) "Offline" else "Not joined yet"
                            st.source == "Simulator" -> "Simulator"
                            st.source == "None" -> "No headset"
                            else -> "Signal ${st.signal}%"
                        },
                        color = FqColors.TextDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.connected) FqColors.Focus else FqColors.TextDim))
            }
            Spacer(Modifier.height(8.dp))
            if (s != null && st.connected) {
                val left = (s.durationSec - s.elapsedSec).coerceAtLeast(0)
                Text("${s.game.title} · %d:%02d left".format(left / 60, left % 60), style = MaterialTheme.typography.bodySmall, color = FqColors.TextDim, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${s.focusPct}%", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = focusColor(s.focusPct))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (s.paused) "paused" else if (s.focused) (if (calm) "calm ✓" else "focused ✓") else "drifting",
                        color = if (s.focused && !s.paused) FqColors.Focus else FqColors.TextDim,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            } else if (st.connected) {
                Text("Not in a game", style = MaterialTheme.typography.bodySmall, color = FqColors.TextDim)
                Spacer(Modifier.height(4.dp))
            }
            MiniBar("Attention", st.attention, FqColors.Focus, st.connected)
            MiniBar("Calm", st.meditation, FqColors.Calm, st.connected)
            Spacer(Modifier.height(6.dp))
            Sparkline(if (calm) st.calmHistory else st.attentionHistory, s?.threshold, if (calm) FqColors.Calm else FqColors.Focus)
            if (alerts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ " + alerts.joinToString(" · "), color = FqColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MiniBar(label: String, value: Int, color: Color, live: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, color = FqColors.TextDim, fontSize = 11.sp, modifier = Modifier.width(58.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))) {
            if (live) Box(Modifier.fillMaxWidth(value.coerceIn(0, 100) / 100f).height(6.dp).clip(RoundedCornerShape(50)).background(color))
        }
        Text(if (live) "$value" else "–", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(26.dp).padding(start = 4.dp))
    }
}

@Composable
private fun Sparkline(values: List<Int>, threshold: Int?, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        if (threshold != null) {
            val ty = size.height - size.height * threshold / 100f
            drawLine(FqColors.Warm.copy(alpha = 0.6f), Offset(0f, ty), Offset(size.width, ty), 1.5f)
        }
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val pt = Offset(i * step, size.height - size.height * v.coerceIn(0, 100) / 100f)
            if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        drawPath(p, color, style = Stroke(3f))
    }
}

@Composable
private fun StudentDialog(vm: AppViewModel, st: LiveStudent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Avatar(st.profile, 48.dp) },
        title = { Text(st.profile.name) },
        text = {
            Column {
                Text("Age ${st.profile.age} · ${if (st.connected) "connected" else "offline"}", color = FqColors.TextDim)
                st.session?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    Text("${s.game.title}: ${s.progress}")
                    Text("On goal ${s.focusPct}% · streak ${s.streakSec}s · goal ${s.threshold} · ⭐ ${s.points}", color = FqColors.TextDim)
                }
                if (st.connected) {
                    Spacer(Modifier.height(12.dp))
                    Text("Send encouragement", style = MaterialTheme.typography.titleSmall)
                    CHEERS.forEach { c ->
                        TextButton(onClick = { vm.trainer.cheer(st.profile.id, c); onDismiss() }) { Text(c) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { onDismiss(); vm.push(Screen.Reports(trainer = true)) }) { Text("Reports") }
        },
    )
}
