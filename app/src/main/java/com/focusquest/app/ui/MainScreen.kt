package com.focusquest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PictureAsPdf
import com.focusquest.app.classroom.LinkPhase
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focusquest.app.AppViewModel
import com.focusquest.app.Screen
import com.focusquest.app.Tab
import com.focusquest.app.data.GameId
import com.focusquest.app.data.SessionRecord
import com.focusquest.app.ui.theme.FqColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(vm: AppViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = FqColors.Surface) {
                NavItem(vm, Tab.Train, Icons.Filled.SportsEsports, "Train")
                NavItem(vm, Tab.Progress, Icons.Filled.Insights, "Progress")
                NavItem(vm, Tab.Device, Icons.Filled.Bluetooth, "Headset")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (vm.tab) {
                Tab.Train -> TrainScreen(vm)
                Tab.Progress -> ProgressScreen(vm)
                Tab.Device -> DeviceScreen(vm)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(vm: AppViewModel, tab: Tab, icon: ImageVector, label: String) {
    NavigationBarItem(
        selected = vm.tab == tab,
        onClick = { vm.tab = tab },
        icon = { Icon(icon, null) },
        label = { Text(label) },
    )
}

val GameId.icon: ImageVector
    get() = when (this) {
        GameId.ROCKET -> Icons.Filled.RocketLaunch
        GameId.GARDEN -> Icons.Filled.LocalFlorist
        GameId.DRIVE -> Icons.Filled.DirectionsCar
        GameId.MOSAIC -> Icons.Filled.Extension
        GameId.BRIDGE -> Icons.Filled.Construction
        GameId.ARCHERY -> Icons.Filled.GpsFixed
        GameId.BALLOON -> Icons.Filled.Cloud
        GameId.LANTERN -> Icons.Filled.Nightlight
    }

val GameId.gradient: List<Color>
    get() = when (this) {
        GameId.ROCKET -> listOf(Color(0xFF3B2A99), Color(0xFF0B1033))
        GameId.GARDEN -> listOf(Color(0xFF1E8C5A), Color(0xFF0F3B2E))
        GameId.DRIVE -> listOf(Color(0xFFB0306A), Color(0xFF2A0F3B))
        GameId.MOSAIC -> listOf(Color(0xFF6A3DE8), Color(0xFF2B1466))
        GameId.BRIDGE -> listOf(Color(0xFFC77D2E), Color(0xFF4A2A10))
        GameId.ARCHERY -> listOf(Color(0xFFD9433B), Color(0xFF4A1210))
        GameId.BALLOON -> listOf(Color(0xFF2E8FBF), Color(0xFF103A55))
        GameId.LANTERN -> listOf(Color(0xFF3F3A8C), Color(0xFF120F33))
    }

/** Train-screen sections, in the order a session plan would usually use them. */
private val GAME_SECTIONS = listOf(
    Triple("Focus · sustained", "Hold attention steady for the whole session", listOf(GameId.ROCKET, GameId.DRIVE, GameId.MOSAIC)),
    Triple("Focus · goals & bursts", "Build focus to complete a goal, then go again", listOf(GameId.GARDEN, GameId.BRIDGE, GameId.ARCHERY)),
    Triple("Calm & relaxation", "Driven by the calm reading: slow breathing, relaxed body", listOf(GameId.BALLOON, GameId.LANTERN)),
)

private val DURATIONS = listOf(120, 300, 600, 900)

@Composable
fun TrainScreen(vm: AppViewModel) {
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val sessions by vm.store.sessions.collectAsStateWithLifecycle()
    val eeg by vm.hub.state.collectAsStateWithLifecycle()
    val classLink by vm.studentLink.state.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.firstOrNull()
    val mine = sessions.filter { it.profileId == profile?.id }
    var pendingGame by remember { mutableStateOf<GameId?>(null) }
    var duration by remember(settings.defaultDurationSec) { mutableStateOf(settings.defaultDurationSec) }

    val todays = mine.filter { isSameDay(it.startedAt, System.currentTimeMillis()) }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hi, ${profile?.name ?: "there"}", style = MaterialTheme.typography.titleLarge)
                    Text("Ready to train your focus?", color = FqColors.TextDim)
                }
                IconButton(onClick = { vm.push(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalChip(eeg) { vm.tab = Tab.Device }
                if (classLink.phase != LinkPhase.Idle) {
                    val (c, t) = when (classLink.phase) {
                        LinkPhase.Connected -> FqColors.Focus to "Class: ${classLink.className}"
                        LinkPhase.Error -> FqColors.Danger to "Class: not joined"
                        else -> FqColors.Warm to "Class: reconnecting"
                    }
                    Text(
                        t, color = c, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.15f))
                            .clickable { vm.push(Screen.JoinClass) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Today", formatDuration(todays.sumOf { it.durationSec }), Modifier.weight(1f), FqColors.Focus)
                StatTile("Day streak", "${dayStreak(mine)} 🔥", Modifier.weight(1f), FqColors.Warm)
                StatTile("Points", "%,d".format(mine.sumOf { it.points }), Modifier.weight(1f), FqColors.Calm)
            }
        }
        item {
            SectionTitle("Session length")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DURATIONS) { d ->
                    FilterChip(selected = duration == d, onClick = { duration = d }, label = { Text("${d / 60} min") })
                }
            }
        }
        GAME_SECTIONS.forEach { (title, subtitle, games) ->
            item(key = title) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(games, key = { it.name }) { game ->
                GameCard(game) {
                    if (eeg.connected) vm.push(Screen.Session(game, duration)) else pendingGame = game
                }
            }
        }
    }

    pendingGame?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingGame = null },
            title = { Text("No headset connected") },
            text = { Text("Connect your EEG headband for real neurofeedback, or try the game with the simulator.") },
            confirmButton = {
                TextButton(onClick = { pendingGame = null; vm.tab = Tab.Device }) { Text("Connect headset") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingGame = null
                    vm.hub.startSimulator()
                    vm.push(Screen.Session(game, duration))
                }) { Text("Use simulator") }
            },
        )
    }
}

@Composable
private fun GameCard(game: GameId, onPlay: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(game.gradient))
            .clickable(onClick = onPlay)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(game.icon, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${game.metric.skill.uppercase()} · ${game.style.uppercase()}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(game.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(game.tagline, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Play ${game.title}", tint = game.gradient.last()) }
        }
    }
}

@Composable
fun ProgressScreen(vm: AppViewModel) {
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val sessions by vm.store.sessions.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.firstOrNull()
    val mine = sessions.filter { it.profileId == profile?.id }

    val (weekValues, weekLabels) = remember(mine) { lastSevenDays(mine) }
    val trend = remember(mine) { mine.take(20).reversed().map { it.focusPercent } }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${profile?.name ?: ""}'s progress", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.push(Screen.Reports(trainer = false)) }) {
                    Icon(Icons.Filled.PictureAsPdf, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Reports")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Sessions", "${mine.size}", Modifier.weight(1f))
                StatTile("Total time", formatDuration(mine.sumOf { it.durationSec }), Modifier.weight(1f), FqColors.Focus)
                StatTile(
                    "Avg focus",
                    if (mine.isEmpty()) "–" else "${mine.map { it.focusPercent }.average().toInt()}%",
                    Modifier.weight(1f),
                    FqColors.Warm,
                )
            }
        }
        item {
            Card {
                SectionTitle("Minutes trained · last 7 days")
                BarChart(weekValues, weekLabels)
            }
        }
        item {
            Card {
                SectionTitle("Focus % · recent sessions")
                if (trend.size < 2) {
                    Text("Play two or more sessions to see your trend.", color = FqColors.TextDim)
                } else {
                    AttentionChart(trend, threshold = null)
                }
            }
        }
        if (mine.isNotEmpty()) item { SectionTitle("History") }
        items(mine, key = { it.id }) { s -> SessionRow(s) { vm.push(Screen.Result(s, "")) } }
        if (mine.isEmpty()) {
            item { Text("No sessions yet. Pick a game on the Train tab to start.", color = FqColors.TextDim) }
        }
    }
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = FqColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SessionRow(s: SessionRecord, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FqColors.Surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(s.game.icon, null, tint = FqColors.Primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.game.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(s.startedAt)) +
                        " · " + formatDuration(s.durationSec),
                    color = FqColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("${s.focusPercent}%", style = MaterialTheme.typography.titleLarge, color = focusColor(s.focusPercent))
        }
    }
}

fun focusColor(percent: Int): Color = when {
    percent >= 70 -> FqColors.Focus
    percent >= 40 -> FqColors.Warm
    else -> FqColors.Danger
}

fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun dayKey(millis: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

/** Consecutive days with at least one session, counting back from today (or yesterday). */
fun dayStreak(sessions: List<SessionRecord>): Int {
    val days = sessions.map { dayKey(it.startedAt) }.toSet()
    val cal = Calendar.getInstance()
    if (dayKey(cal.timeInMillis) !in days) cal.add(Calendar.DAY_OF_YEAR, -1)
    var streak = 0
    while (dayKey(cal.timeInMillis) in days) {
        streak++
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return streak
}

private fun lastSevenDays(sessions: List<SessionRecord>): Pair<List<Float>, List<String>> {
    val fmt = SimpleDateFormat("EEE", Locale.getDefault())
    val values = ArrayList<Float>()
    val labels = ArrayList<String>()
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -6)
    repeat(7) {
        val t = cal.timeInMillis
        values += sessions.filter { isSameDay(it.startedAt, t) }.sumOf { it.durationSec } / 60f
        labels += fmt.format(Date(t)).take(2)
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return values to labels
}
