package com.focusquest.app.classroom

import android.content.Context
import android.os.SystemClock
import com.focusquest.app.data.GameId
import com.focusquest.app.data.Store
import com.focusquest.app.report.ReportWriter
import com.focusquest.app.report.Reports
import com.focusquest.app.ui.theme.FqColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

data class WebDashboardState(
    val running: Boolean = false,
    val port: Int = 0,
    val pin: String = "",
    val viewers: Int = 0,
)

/**
 * Serves the trainer dashboard as a web page from the tablet, so a teacher can follow the
 * class on a laptop or classroom projector on the same Wi‑Fi. Access needs a 6-digit PIN that
 * is shown only on the tablet (students know the join code, not this PIN).
 */
class WebDashboardServer(
    private val context: Context,
    private val store: Store,
    private val trainer: TrainerServer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WebDashboardState())
    val state: StateFlow<WebDashboardState> = _state.asStateFlow()

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val viewers = AtomicInteger(0)
    private val random = SecureRandom()

    @Volatile private var token = ""
    @Volatile private var failedLogins = 0
    @Volatile private var lockedUntil = 0L

    fun start() {
        if (_state.value.running) return
        val ss = try {
            ServerSocket(PREFERRED_PORT)
        } catch (_: IOException) {
            try { ServerSocket(0) } catch (_: IOException) { return }
        }
        server = ss
        token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        failedLogins = 0
        lockedUntil = 0L
        _state.value = WebDashboardState(running = true, port = ss.localPort, pin = (100000 + random.nextInt(900000)).toString())
        acceptJob = scope.launch {
            while (isActive) {
                val socket = try { ss.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { try { socket.close() } catch (_: IOException) {}; continue }
                launch {
                    try { handle(socket) } finally {
                        slots.release()
                        try { socket.close() } catch (_: IOException) {}
                    }
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try { server?.close() } catch (_: IOException) {}
        server = null
        token = ""
        _state.value = WebDashboardState()
    }

    // ---- request handling ------------------------------------------------------------

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val out = socket.getOutputStream()
        val req = try {
            HttpLite.readRequest(BufferedInputStream(socket.getInputStream()))
        } catch (_: IOException) {
            null
        } ?: return
        try {
            route(req, out)
        } catch (_: IOException) {
            // client went away
        } catch (e: Exception) {
            try { HttpLite.json(out, 500, err("Server error")) } catch (_: IOException) {}
        }
    }

    private suspend fun route(req: HttpRequest, out: OutputStream) {
        val path = req.path
        if (req.method == "GET" && (path == "/" || path == "/index.html")) {
            val html = context.assets.open("web/dashboard.html").use { it.readBytes() }
            HttpLite.respond(out, 200, "text/html; charset=utf-8", html)
            return
        }
        if (path == "/favicon.ico") { HttpLite.respond(out, 204, "image/x-icon", ByteArray(0)); return }
        if (!path.startsWith("/api/")) { HttpLite.json(out, 404, err("Not found")); return }

        // Cross-site request protection: browsers only send this custom header from our own page.
        if (req.method == "POST" && req.headers["x-fq"] != "1") { HttpLite.json(out, 403, err("Forbidden")); return }

        if (path == "/api/login" && req.method == "POST") { login(req, out); return }
        if (path == "/api/logout" && req.method == "POST") {
            HttpLite.json(out, 200, """{"ok":true}""", listOf("Set-Cookie" to "fq=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"))
            return
        }
        if (!authorised(req)) { HttpLite.json(out, 401, err("Sign in required")); return }

        when {
            path == "/api/state" && req.method == "GET" -> HttpLite.json(out, 200, stateJson().toString())
            path == "/api/events" && req.method == "GET" -> events(out)
            path == "/api/meta" && req.method == "GET" -> HttpLite.json(out, 200, metaJson().toString())
            path == "/api/group/start" && req.method == "POST" -> {
                val body = JSONObject(req.bodyText.ifBlank { "{}" })
                val game = runCatching { GameId.valueOf(body.getString("game")) }.getOrNull()
                val duration = body.optInt("duration", 300).coerceIn(60, 1800)
                when {
                    game == null -> HttpLite.json(out, 400, err("Unknown game"))
                    trainer.state.value.group != null -> HttpLite.json(out, 409, err("A group session is already running"))
                    !trainer.startGroup(game, duration) -> HttpLite.json(out, 409, err("No students are connected"))
                    else -> HttpLite.json(out, 200, """{"ok":true}""")
                }
            }
            path == "/api/group/end" && req.method == "POST" -> { trainer.endGroup(); HttpLite.json(out, 200, """{"ok":true}""") }
            path == "/api/group/close" && req.method == "POST" -> {
                val g = trainer.closeGroup()
                HttpLite.json(out, if (g == null) 409 else 200, JSONObject().put("ok", g != null).put("groupId", g?.id ?: JSONObject.NULL).toString())
            }
            path == "/api/cheer" && req.method == "POST" -> {
                val body = JSONObject(req.bodyText.ifBlank { "{}" })
                val text = body.optString("text").trim().take(120)
                if (text.isEmpty()) { HttpLite.json(out, 400, err("Message is empty")); return }
                trainer.cheer(if (body.isNull("studentId") || !body.has("studentId")) null else body.getString("studentId"), text)
                HttpLite.json(out, 200, """{"ok":true}""")
            }
            path == "/api/reports/summary" && req.method == "GET" -> HttpLite.json(out, 200, summaryJson(days(req)).toString())
            path.startsWith("/api/reports/") && req.method == "GET" -> report(req, out)
            else -> HttpLite.json(out, 404, err("Not found"))
        }
    }

    private fun login(req: HttpRequest, out: OutputStream) {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) {
            HttpLite.json(out, 429, err("Too many attempts. Try again in ${(lockedUntil - now) / 1000 + 1} s."))
            return
        }
        val pin = runCatching { JSONObject(req.bodyText).optString("pin") }.getOrDefault("")
        // Constant-time comparison so the PIN can't be guessed from response timing.
        val ok = MessageDigest.isEqual(pin.toByteArray(), _state.value.pin.toByteArray())
        if (!ok) {
            failedLogins++
            if (failedLogins >= MAX_FAILED_LOGINS) { lockedUntil = now + LOCKOUT_MS; failedLogins = 0 }
            HttpLite.json(out, 401, err("Wrong PIN"))
            return
        }
        failedLogins = 0
        HttpLite.json(
            out, 200, JSONObject().put("ok", true).put("className", trainer.state.value.className).toString(),
            listOf("Set-Cookie" to "fq=$token; Path=/; HttpOnly; SameSite=Strict"),
        )
    }

    private fun authorised(req: HttpRequest): Boolean {
        val t = token
        val c = req.cookie("fq") ?: return false
        return t.isNotEmpty() && MessageDigest.isEqual(c.toByteArray(), t.toByteArray())
    }

    /** Server-Sent Events: pushes the live class state once a second. */
    private suspend fun events(out: OutputStream) {
        HttpLite.writeHead(out, 200, "text/event-stream; charset=utf-8", null, listOf("Connection" to "keep-alive"))
        out.write("retry: 2000\n\n".toByteArray())
        out.flush()
        viewers.incrementAndGet().also { n -> _state.update { it.copy(viewers = n) } }
        try {
            while (_state.value.running) {
                out.write(("data: " + stateJson().toString() + "\n\n").toByteArray(Charsets.UTF_8))
                out.flush()
                delay(1000)
            }
        } finally {
            viewers.decrementAndGet().also { n -> _state.update { it.copy(viewers = n) } }
        }
    }

    private fun days(req: HttpRequest) = req.query["days"]?.toIntOrNull()?.coerceIn(0, 3650) ?: 30

    private fun range(days: Int): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val from = if (days == 0) (store.classSessions.value.minOfOrNull { it.startedAt } ?: now)
        else Reports.startOfDay(now) - (days - 1) * Reports.DAY_MS
        return from to now
    }

    private fun report(req: HttpRequest, out: OutputStream) {
        val (from, to) = range(days(req))
        val students = store.classStudents.value
        val sessions = store.classSessions.value
        val className = trainer.state.value.className.ifBlank { store.settings.value.className.ifBlank { "My class" } }
        val file: File = when (req.path) {
            "/api/reports/class.pdf" -> ReportWriter.classPdf(context, className, Reports.classRows(students, sessions, from, to, to), from, to)
            "/api/reports/class.csv" -> ReportWriter.csv(context, className, students, sessions.filter { it.startedAt in from..to })
            "/api/reports/student.pdf" -> {
                val st = students.firstOrNull { it.id == req.query["id"] } ?: run { HttpLite.json(out, 404, err("Unknown student")); return }
                ReportWriter.studentPdf(context, Reports.student(st, sessions, from, to))
            }
            "/api/reports/group.pdf" -> {
                val g = store.groupSessions.value.firstOrNull { it.id == req.query["id"] } ?: run { HttpLite.json(out, 404, err("Unknown session")); return }
                ReportWriter.groupPdf(context, Reports.group(g, students, sessions))
            }
            else -> { HttpLite.json(out, 404, err("Not found")); return }
        }
        val type = if (file.extension == "csv") "text/csv; charset=utf-8" else "application/pdf"
        HttpLite.writeHead(out, 200, type, file.length(), listOf("Content-Disposition" to "attachment; filename=\"${file.name}\""))
        file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    // ---- JSON ------------------------------------------------------------------------

    private fun hex(c: androidx.compose.ui.graphics.Color): String =
        "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private fun stateJson(): JSONObject {
        val s = trainer.state.value
        val now = SystemClock.elapsedRealtime()
        val students = JSONArray()
        s.students.values.sortedBy { it.profile.name.lowercase() }.forEach { st ->
            val o = JSONObject()
                .put("id", st.profile.id).put("name", st.profile.name).put("age", st.profile.age)
                .put("color", hex(FqColors.avatars[st.profile.colorIndex.mod(FqColors.avatars.size)]))
                .put("connected", st.connected).put("joined", st.everConnected).put("attention", st.attention).put("meditation", st.meditation)
                .put("signal", st.signal).put("signalOk", st.signalOk).put("source", st.source)
                .put("alerts", JSONArray(st.alerts(now)))
                .put("attentionHistory", JSONArray(st.attentionHistory)).put("calmHistory", JSONArray(st.calmHistory))
            val ses = st.session
            o.put("session", if (ses == null) JSONObject.NULL else JSONObject()
                .put("game", ses.game.name).put("gameTitle", ses.game.title).put("metric", ses.game.metric.name)
                .put("elapsed", ses.elapsedSec).put("duration", ses.durationSec).put("focusPct", ses.focusPct)
                .put("points", ses.points).put("streak", ses.streakSec).put("threshold", ses.threshold)
                .put("focused", ses.focused).put("paused", ses.paused).put("progress", ses.progress))
            students.put(o)
        }
        val g = s.group
        return JSONObject()
            .put("running", s.running).put("className", s.className).put("code", s.code)
            .put("students", students)
            .put("group", if (g == null) JSONObject.NULL else JSONObject()
                .put("id", g.id).put("game", g.game.name).put("gameTitle", g.game.title)
                .put("plannedSec", g.plannedSec).put("elapsedSec", ((System.currentTimeMillis() - g.startedAt) / 1000).toInt())
                .put("results", g.resultIds.size).put("total", g.studentIds.size)
                .put("ended", g.endedAt != 0L).put("allResultsIn", g.allResultsIn))
    }

    private fun metaJson(): JSONObject = JSONObject()
        .put("games", JSONArray(GameId.entries.map {
            JSONObject().put("id", it.name).put("title", it.title).put("metric", it.metric.name)
                .put("skill", it.metric.skill).put("style", it.style).put("tagline", it.tagline)
        }))
        .put("durations", JSONArray(listOf(120, 300, 600, 900)))

    private fun summaryJson(days: Int): JSONObject {
        val (from, to) = range(days)
        val rows = Reports.classRows(store.classStudents.value, store.classSessions.value, from, to, to)
        return JSONObject()
            .put("from", from).put("to", to)
            .put("students", JSONArray(rows.map { r ->
                JSONObject().put("id", r.student.id).put("name", r.student.name).put("age", r.student.age)
                    .put("sessions", r.sessions).put("minutes", r.minutes)
                    .put("focusPct", r.focusPct ?: JSONObject.NULL).put("change", r.focusChange ?: JSONObject.NULL)
                    .put("lastSession", r.lastSession ?: JSONObject.NULL).put("flag", r.flag ?: JSONObject.NULL)
            }))
            .put("groups", JSONArray(store.groupSessions.value.take(50).map { g ->
                JSONObject().put("id", g.id).put("gameTitle", g.game.title).put("startedAt", g.startedAt)
                    .put("results", g.resultIds.size).put("total", g.studentIds.size)
            }))
    }

    private fun err(message: String) = JSONObject().put("error", message).toString()

    companion object {
        const val PREFERRED_PORT = 8080
        private const val MAX_CONNECTIONS = 24
        private const val MAX_FAILED_LOGINS = 5
        private const val LOCKOUT_MS = 60_000L
    }
}
