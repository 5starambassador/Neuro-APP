package com.focusquest.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focusquest.app.AppViewModel
import com.focusquest.app.classroom.FoundClass
import com.focusquest.app.classroom.LinkPhase
import com.focusquest.app.data.Profile
import com.focusquest.app.data.SessionRecord
import com.focusquest.app.report.Reports
import com.focusquest.app.report.ReportWriter
import com.focusquest.app.ui.theme.FqColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------
// Student: join a class
// ---------------------------------------------------------------------------------------

@Composable
fun JoinClassScreen(vm: AppViewModel) {
    val link by vm.studentLink.state.collectAsStateWithLifecycle()
    val found by vm.studentLink.found.collectAsStateWithLifecycle()
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val me = profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.firstOrNull()

    DisposableEffect(Unit) {
        vm.studentLink.startDiscovery()
        onDispose { vm.studentLink.stopDiscovery() }
    }

    var codeFor by remember { mutableStateOf<FoundClass?>(null) }
    var online by remember { mutableStateOf(true) }
    var server by remember { mutableStateOf(settings.onlineServer) }
    var onlineCode by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(link.host) }
    var port by remember { mutableStateOf(if (link.port > 0) "${link.port}" else "") }
    var code by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Join a class", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(me, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (link.phase) {
                                LinkPhase.Connected -> "Connected to ${link.className}"
                                LinkPhase.Connecting -> "Connecting…"
                                LinkPhase.Reconnecting -> link.message ?: "Reconnecting…"
                                LinkPhase.Error -> "Not connected"
                                LinkPhase.Idle -> "Not in a class"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (link.phase == LinkPhase.Error) link.message ?: "" else "Joining as ${me?.name ?: "—"}",
                            color = if (link.phase == LinkPhase.Error) FqColors.Danger else FqColors.TextDim,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (link.phase == LinkPhase.Connecting || link.phase == LinkPhase.Reconnecting) CircularProgressIndicator(Modifier.size(24.dp))
                }
                if (link.phase == LinkPhase.Connected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        (if (link.online) "Online class on ${link.server}. " else "Same Wi‑Fi class. ") +
                            "Your teacher can now see your attention and calm live, and may start games for the class. " +
                            "Keep FocusQuest open.",
                        color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (link.phase != LinkPhase.Idle) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { vm.studentLink.leave() }) { Text("Leave class") }
                }
            }
        }
        if (link.phase == LinkPhase.Idle || link.phase == LinkPhase.Error) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = online, onClick = { online = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Online class") }
                    SegmentedButton(selected = !online, onClick = { online = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Same Wi‑Fi") }
                }
            }
        }
        if ((link.phase == LinkPhase.Idle || link.phase == LinkPhase.Error) && online) {
            item {
                Card {
                    Text("Join an online class", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Works from school or home. Your teacher gives you the server address and a 6-letter class code.",
                        color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        server, { server = it.trim() }, label = { Text("Server address") },
                        placeholder = { Text("focus.yourschool.org") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        onlineCode, { v -> onlineCode = v.uppercase().filter { it.isLetterOrDigit() }.take(6) },
                        label = { Text("Class code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.studentLink.joinOnline(server, onlineCode) },
                        enabled = server.isNotBlank() && onlineCode.length == 6 && me != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Join class") }
                }
            }
        }
        if ((link.phase == LinkPhase.Idle || link.phase == LinkPhase.Error) && !online) {
            item { SectionTitle("Classes nearby") }
            if (found.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Searching this Wi‑Fi…", color = FqColors.TextDim)
                    }
                }
            }
            items(found, key = { it.name }) { c ->
                Surface(
                    shape = RoundedCornerShape(16.dp), color = FqColors.Surface,
                    modifier = Modifier.fillMaxWidth().clickable { codeFor = c; code = "" },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Groups, null, tint = FqColors.Focus)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text("${c.host}:${c.port}", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Join", color = FqColors.Primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                Card {
                    Text("Join with an address", style = MaterialTheme.typography.titleMedium)
                    Text("Shown on the trainer's dashboard.", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("Trainer IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            code, { code = it.filter(Char::isDigit).take(4) }, label = { Text("Code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.studentLink.join(host, port.toInt(), code) },
                        enabled = host.isNotBlank() && port.toIntOrNull() != null && code.length == 4 && me != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Join") }
                }
            }
        }
    }

    codeFor?.let { c ->
        AlertDialog(
            onDismissRequest = { codeFor = null },
            title = { Text("Join ${c.name}") },
            text = {
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(4) }, label = { Text("4-digit class code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = code.length == 4, onClick = { vm.studentLink.join(c.host, c.port, code); codeFor = null }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { codeFor = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Reports (student device: its own players · trainer device: the class)
// ---------------------------------------------------------------------------------------

private val RANGES = listOf("7 days" to 7, "30 days" to 30, "90 days" to 90, "All time" to 0)

@Composable
fun ReportsScreen(vm: AppViewModel, trainer: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localProfiles by vm.store.profiles.collectAsStateWithLifecycle()
    val localSessions by vm.store.sessions.collectAsStateWithLifecycle()
    val classStudents by vm.store.classStudents.collectAsStateWithLifecycle()
    val classSessions by vm.store.classSessions.collectAsStateWithLifecycle()
    val groups by vm.store.groupSessions.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()

    val students: List<Profile> = if (trainer) classStudents else localProfiles
    val sessions: List<SessionRecord> = if (trainer) classSessions else localSessions
    val groupName = if (trainer) settings.className.ifBlank { "My class" } else "All players"

    var rangeDays by remember { mutableStateOf(30) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val from = if (rangeDays == 0) (sessions.minOfOrNull { it.startedAt } ?: now) else Reports.startOfDay(now) - (rangeDays - 1) * Reports.DAY_MS

    fun generate(label: String, make: () -> File) {
        busy = label
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(make) }
            busy = null
            result.onSuccess { ReportWriter.share(context, it) }.onFailure { error = "Couldn't create the report: ${it.message}" }
        }
    }

    val rows = remember(students, sessions, from) { Reports.classRows(students, sessions, from, now, now) }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("Reports", style = MaterialTheme.typography.headlineMedium)
                    Text(if (trainer) "Class: $groupName" else "Players on this device", color = FqColors.TextDim)
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RANGES) { (label, days) -> FilterChip(selected = rangeDays == days, onClick = { rangeDays = days }, label = { Text(label) }) }
            }
        }
        busy?.let { label ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Creating $label…")
                }
            }
        }
        error?.let { item { Text(it, color = FqColors.Danger) } }

        if (students.isEmpty()) {
            item {
                Text(
                    if (trainer) "No students yet. Start a class from the trainer dashboard and students' sessions will appear here."
                    else "No players yet.",
                    color = FqColors.TextDim,
                )
            }
            return@LazyColumn
        }

        item {
            Card {
                Text(if (trainer) "Whole class" else "All players", style = MaterialTheme.typography.titleMedium)
                val n = rows.sumOf { it.sessions }
                Text("${students.size} students · $n session${if (n == 1) "" else "s"} · ${rows.sumOf { it.minutes }} min in range", color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null, onClick = {
                        generate("class summary") { ReportWriter.classPdf(context, groupName, rows, from, now) }
                    }) { Icon(Icons.Filled.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("Class summary") }
                    OutlinedButton(enabled = busy == null, onClick = {
                        generate("CSV export") { ReportWriter.csv(context, groupName, students, sessions.filter { it.startedAt in from..now }) }
                    }) { Icon(Icons.Filled.TableChart, null); Spacer(Modifier.width(6.dp)); Text("CSV") }
                }
            }
        }

        item { SectionTitle("Student reports") }
        items(rows, key = { it.student.id }) { row ->
            Surface(
                shape = RoundedCornerShape(16.dp), color = FqColors.Surface,
                modifier = Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                    generate("${row.student.name}'s report") {
                        ReportWriter.studentPdf(context, Reports.student(row.student, sessions, from, now))
                    }
                },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(row.student, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.student.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${row.sessions} session${if (row.sessions == 1) "" else "s"} · ${row.minutes} min" +
                                (row.focusPct?.let { " · $it% on goal" } ?: ""),
                            color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                        )
                        row.flag?.let { Text(it, color = if (it == "Improving") FqColors.Focus else FqColors.Warm, style = MaterialTheme.typography.bodySmall) }
                    }
                    Icon(Icons.Filled.PictureAsPdf, "Create PDF", tint = FqColors.Primary)
                }
            }
        }

        if (trainer && groups.isNotEmpty()) {
            item { SectionTitle("Live session reports") }
            items(groups.take(30), key = { it.id }) { g ->
                Surface(
                    shape = RoundedCornerShape(16.dp), color = FqColors.Surface,
                    modifier = Modifier.fillMaxWidth().clickable { vm.push(com.focusquest.app.Screen.GroupReport(g.id)) },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(g.game.icon, null, tint = FqColors.Primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.game.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(g.startedAt)) + " · ${g.resultIds.size} results",
                                color = FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Live (group) session report
// ---------------------------------------------------------------------------------------

@Composable
fun GroupReportScreen(vm: AppViewModel, groupId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by vm.store.groupSessions.collectAsStateWithLifecycle()
    val students by vm.store.classStudents.collectAsStateWithLifecycle()
    val sessions by vm.store.classSessions.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == groupId }
    var busy by remember { mutableStateOf(false) }

    if (group == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("This session is no longer available.", color = FqColors.TextDim)
        }
        return
    }
    val report = remember(group, students, sessions) { Reports.group(group, students, sessions) }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Live session report", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${group.game.title} · ${group.className} · " + SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(group.startedAt)),
                        color = FqColors.TextDim,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Students", "${report.rows.size}", Modifier.weight(1f))
                StatTile("Results", "${report.completed}", Modifier.weight(1f), FqColors.Calm)
                StatTile("Avg on goal", report.avgFocusPct?.let { "$it%" } ?: "–", Modifier.weight(1f), FqColors.Focus)
            }
        }
        item {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { runCatching { ReportWriter.groupPdf(context, report) } }
                        busy = false
                        f.onSuccess { ReportWriter.share(context, it) }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Icon(Icons.Filled.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Creating PDF…" else "Share PDF report") }
        }
        items(report.rows, key = { it.student.id }) { row ->
            val r = row.record
            Surface(shape = RoundedCornerShape(16.dp), color = FqColors.Surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(row.student, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.student.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (r == null) "No result (disconnected or left)"
                                else "${formatDuration(r.durationSec)} · best streak ${formatDuration(r.longestStreakSec)} · ⭐ ${r.points}",
                                color = if (r == null) FqColors.Warm else FqColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (r != null) Text("${r.focusPercent}%", style = MaterialTheme.typography.titleLarge, color = focusColor(r.focusPercent))
                    }
                    if (r != null && r.attentionTimeline.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        AttentionChart(r.attentionTimeline, r.endThreshold, Modifier.height(90.dp))
                    }
                }
            }
        }
    }
}
