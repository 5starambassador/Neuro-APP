package com.focusquest.app.classroom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import com.focusquest.app.data.GameId
import com.focusquest.app.data.GroupSession
import com.focusquest.app.data.Profile
import com.focusquest.app.data.Store
import com.focusquest.app.data.profileFromJson
import com.focusquest.app.data.sessionFromJson
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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One student as seen live on the trainer's dashboard. */
data class LiveStudent(
    val profile: Profile,
    val connected: Boolean = false,
    val lastSeen: Long = 0L,
    val attention: Int = 0,
    val meditation: Int = 0,
    val signal: Int = 0,
    val signalOk: Boolean = false,
    val source: String = "None",
    val session: LiveSessionView? = null,
    val attentionHistory: List<Int> = emptyList(),
    val calmHistory: List<Int> = emptyList(),
    /** elapsedRealtime when the signal last went bad, or 0 while it's fine. */
    val poorSignalSince: Long = 0L,
) {
    /** Reasons this student may need the trainer's help right now (empty = fine). */
    fun alerts(now: Long = SystemClock.elapsedRealtime()): List<String> = buildList {
        if (!connected) { add("Disconnected"); return@buildList }
        if (source == "None") add("No headset")
        else if (poorSignalSince != 0L && now - poorSignalSince > 10_000) add("Poor signal")
        val s = session
        if (s != null && !s.paused && s.elapsedSec >= 60 && s.focusPct < 30) add("Low ${if (s.game.metric.name == "CALM") "calm" else "focus"}")
    }
}

data class LiveSessionView(
    val game: GameId,
    val elapsedSec: Int,
    val durationSec: Int,
    val focusPct: Int,
    val points: Int,
    val streakSec: Int,
    val threshold: Int,
    val focused: Boolean,
    val progress: String,
    val paused: Boolean,
    val groupId: String?,
)

data class GroupRun(
    val id: String,
    val game: GameId,
    val plannedSec: Int,
    val startedAt: Long,
    val studentIds: List<String>,
    val resultIds: Map<String, String> = emptyMap(), // studentId -> recordId
    val endedAt: Long = 0L,
) {
    val allResultsIn: Boolean get() = studentIds.isNotEmpty() && studentIds.all { it in resultIds }
}

data class TrainerState(
    val running: Boolean = false,
    val className: String = "",
    val code: String = "",
    val host: String? = null,
    val port: Int = 0,
    val students: Map<String, LiveStudent> = emptyMap(),
    val group: GroupRun? = null,
    val error: String? = null,
)

/**
 * Trainer mode: accepts student connections on the local network, advertises itself with
 * mDNS/NSD so students can find it, and keeps a live view of every student.
 */
class TrainerServer(private val context: Context, private val store: Store) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TrainerState())
    val state: StateFlow<TrainerState> = _state.asStateFlow()

    private var server: ServerSocket? = null
    private var jobs = mutableListOf<Job>()
    private val clients = ConcurrentHashMap<String, Client>()
    private var registration: NsdManager.RegistrationListener? = null

    private class Client(val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        @Synchronized fun send(o: JSONObject) {
            out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
        fun close() = try { socket.close() } catch (_: IOException) {}
    }

    fun start(className: String) {
        if (_state.value.running) return
        val ss = try {
            ServerSocket(0)
        } catch (e: IOException) {
            _state.update { it.copy(error = "Couldn't start the class: ${e.message}") }
            return
        }
        server = ss
        val roster = store.classStudents.value.associate { it.id to LiveStudent(profile = it) }
        _state.value = TrainerState(
            running = true,
            className = className,
            code = (1000..9999).random().toString(),
            host = localIpv4(context),
            port = ss.localPort,
            students = roster,
        )
        advertise(className, ss.localPort)
        jobs += scope.launch {
            while (isActive) {
                val socket = try { ss.accept() } catch (_: IOException) { break }
                launch { handle(socket) }
            }
        }
        // Mark students whose updates stopped as disconnected.
        jobs += scope.launch {
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                _state.update { s ->
                    s.copy(students = s.students.mapValues { (_, st) ->
                        if (st.connected && now - st.lastSeen > STALE_MS) st.copy(connected = false) else st
                    })
                }
            }
        }
    }

    private fun advertise(name: String, port: Int) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name.ifBlank { "FocusQuest class" }
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _state.update { it.copy(error = "Automatic discovery unavailable; students can join with the address below.") }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            registration = null
        }
    }

    private suspend fun handle(socket: Socket) {
        val client = Client(socket)
        var studentId: String? = null
        try {
            socket.soTimeout = 20_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val hello = JSONObject(Protocol.readLine(input) ?: return)
            if (hello.optString("t") != "hello") return
            if (hello.optString("code") != _state.value.code) {
                client.send(Protocol.error("Wrong class code"))
                return
            }
            val profile = profileFromJson(hello.getJSONObject("profile"))
            studentId = profile.id
            clients.put(profile.id, client)?.close() // replace an older connection from the same student
            store.upsertClassStudent(profile)
            updateStudent(profile.id) { (it ?: LiveStudent(profile)).copy(profile = profile, connected = true, lastSeen = SystemClock.elapsedRealtime()) }
            client.send(Protocol.welcome(_state.value.className))

            while (true) {
                val line = Protocol.readLine(input) ?: break
                val msg = try { JSONObject(line) } catch (_: Exception) { continue }
                when (msg.optString("t")) {
                    "state" -> onState(profile.id, msg)
                    "result" -> onResult(profile.id, msg, client)
                }
            }
        } catch (_: Exception) {
            // Socket closed or malformed data: treat as a disconnect.
        } finally {
            val id = studentId
            if (id != null && clients[id] === client) {
                clients.remove(id)
                updateStudent(id) { it?.copy(connected = false) }
            }
            client.close()
        }
    }

    private fun onState(id: String, m: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val s = m.optJSONObject("session")
        val view = s?.let {
            try {
                LiveSessionView(
                    game = GameId.valueOf(it.getString("game")),
                    elapsedSec = it.optInt("elapsed"), durationSec = it.optInt("duration"),
                    focusPct = it.optInt("focusPct"), points = it.optInt("points"), streakSec = it.optInt("streak"),
                    threshold = it.optInt("threshold"), focused = it.optBoolean("focused"),
                    progress = it.optString("progress"), paused = it.optBoolean("paused"),
                    groupId = if (it.isNull("group")) null else it.optString("group"),
                )
            } catch (_: Exception) { null }
        }
        val ok = m.optBoolean("ok")
        val att = m.optInt("att")
        val med = m.optInt("med")
        updateStudent(id) { prev ->
            val p = prev ?: return@updateStudent null
            p.copy(
                connected = true, lastSeen = now,
                attention = att, meditation = med, signal = m.optInt("sig"), signalOk = ok,
                source = m.optString("src", "None"), session = view,
                attentionHistory = (p.attentionHistory + att).takeLast(HISTORY),
                calmHistory = (p.calmHistory + med).takeLast(HISTORY),
                poorSignalSince = if (ok) 0L else if (p.poorSignalSince == 0L) now else p.poorSignalSince,
            )
        }
    }

    private fun onResult(id: String, m: JSONObject, client: Client) {
        val record = sessionFromJson(m.getJSONObject("record"))?.copy(profileId = id) ?: return
        store.addClassSession(record)
        client.send(Protocol.ack(record.id))
        val groupId = if (m.isNull("group")) null else m.optString("group")
        _state.update { s ->
            val g = s.group
            if (g != null && g.id == groupId) s.copy(group = g.copy(resultIds = g.resultIds + (id to record.id))) else s
        }
    }

    private fun updateStudent(id: String, f: (LiveStudent?) -> LiveStudent?) {
        _state.update { s ->
            val next = f(s.students[id]) ?: return@update s
            s.copy(students = s.students + (id to next))
        }
    }

    // ---- trainer actions -------------------------------------------------------------

    /** Starts the same game for every connected student. */
    fun startGroup(game: GameId, durationSec: Int): Boolean {
        val ids = _state.value.students.values.filter { it.connected }.map { it.profile.id }
        if (ids.isEmpty()) return false
        val run = GroupRun(UUID.randomUUID().toString(), game, durationSec, System.currentTimeMillis(), ids)
        _state.update { it.copy(group = run) }
        broadcast(ids, Protocol.start(run.id, game, durationSec))
        return true
    }

    /** Asks every student in the group session to stop now (their results are still sent). */
    fun endGroup() {
        val g = _state.value.group ?: return
        _state.update { it.copy(group = g.copy(endedAt = System.currentTimeMillis())) }
        broadcast(g.studentIds, Protocol.stop(g.id))
    }

    /** Saves the group session and clears it from the dashboard. */
    fun closeGroup(): GroupSession? {
        val g = _state.value.group ?: return null
        val saved = GroupSession(
            id = g.id, className = _state.value.className, game = g.game, plannedSec = g.plannedSec,
            startedAt = g.startedAt, endedAt = if (g.endedAt == 0L) System.currentTimeMillis() else g.endedAt,
            studentIds = g.studentIds, resultIds = g.resultIds.values.toList(),
        )
        store.addGroupSession(saved)
        _state.update { it.copy(group = null) }
        return saved
    }

    fun cheer(studentId: String?, text: String) {
        val ids = if (studentId != null) listOf(studentId) else clients.keys.toList()
        broadcast(ids, Protocol.cheer(text))
    }

    private fun broadcast(ids: List<String>, message: JSONObject) {
        scope.launch {
            ids.forEach { id ->
                val c = clients[id] ?: return@forEach
                try { c.send(message) } catch (_: IOException) { c.close() }
            }
        }
    }

    fun stop() {
        if (_state.value.group != null) closeGroup()
        registration?.let { l ->
            try { context.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (_: Exception) {}
        }
        registration = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        try { server?.close() } catch (_: IOException) {}
        server = null
        clients.values.forEach { it.close() }
        clients.clear()
        _state.value = TrainerState()
    }

    companion object {
        private const val STALE_MS = 5_000L
        private const val HISTORY = 90
    }
}
