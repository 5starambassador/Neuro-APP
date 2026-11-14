package com.focusquest.app.classroom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.focusquest.app.data.GameId
import com.focusquest.app.data.SessionRecord
import com.focusquest.app.data.Store
import com.focusquest.app.data.toJson
import com.focusquest.app.eeg.EegHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class FoundClass(val name: String, val host: String, val port: Int)

enum class LinkPhase { Idle, Connecting, Connected, Reconnecting, Error }

data class StudentLinkState(
    val phase: LinkPhase = LinkPhase.Idle,
    val className: String = "",
    /** True for an online (server) class, false for a same‑Wi‑Fi trainer tablet. */
    val online: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val server: String = "",
    val message: String? = null,
)

sealed interface ClassEvent {
    data class Start(val groupId: String, val game: GameId, val durationSec: Int) : ClassEvent
    data class Stop(val groupId: String) : ClassEvent
    data class Cheer(val text: String) : ClassEvent
}

/**
 * Student side of a class. Two transports, one protocol:
 * - Online: HTTPS join with a class code, then a secure WebSocket to the FocusQuest server
 *   (works from anywhere; remembered and resumed after app restarts).
 * - Same Wi‑Fi: plain TCP to a trainer tablet found by mDNS (works without internet).
 * Live state goes out every second; finished sessions are resent until acknowledged.
 */
class StudentLink(
    private val context: Context,
    private val hub: EegHub,
    private val store: Store,
    private val liveSession: StateFlow<LiveSession?>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(StudentLinkState())
    val state: StateFlow<StudentLinkState> = _state.asStateFlow()

    private val _found = MutableStateFlow<List<FoundClass>>(emptyList())
    val found: StateFlow<List<FoundClass>> = _found.asStateFlow()

    private val _events = MutableSharedFlow<ClassEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ClassEvent> = _events.asSharedFlow()

    private var connection: Job? = null

    /** Sends one protocol message on whichever transport is currently open (null when offline). */
    @Volatile private var sender: ((JSONObject) -> Boolean)? = null

    /** Results not yet acknowledged; resent after every reconnect. */
    private val pending = LinkedHashMap<String, Pair<SessionRecord, String?>>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keeps mobile NAT/proxies from dropping idle sockets
        .build()

    // ---- shared protocol -------------------------------------------------------------

    private fun handleIncoming(m: JSONObject) {
        when (m.optString("t")) {
            "start" -> runCatching {
                _events.tryEmit(ClassEvent.Start(m.getString("group"), GameId.valueOf(m.getString("game")), m.getInt("duration")))
            }
            "stop" -> _events.tryEmit(ClassEvent.Stop(m.optString("group")))
            "cheer" -> _events.tryEmit(ClassEvent.Cheer(m.optString("text").take(120)))
            "ack" -> synchronized(pending) { pending.remove(m.optString("id")) }
        }
    }

    private fun onConnected(className: String) {
        _state.update { it.copy(phase = LinkPhase.Connected, className = className, message = null) }
        synchronized(pending) { pending.values.toList() }.forEach { (rec, group) -> sender?.invoke(Protocol.result(rec, group)) }
    }

    private fun startStateLoop(): Job = scope.launch {
        while (isActive) {
            sender?.invoke(Protocol.state(hub.state.value, liveSession.value))
            delay(1000)
        }
    }

    /** Queues a finished session for the trainer; resent until acknowledged. */
    fun sendResult(record: SessionRecord, groupId: String?) {
        val phase = _state.value.phase
        if (phase == LinkPhase.Idle || phase == LinkPhase.Error) return
        synchronized(pending) { pending[record.id] = record to groupId }
        scope.launch { sender?.invoke(Protocol.result(record, groupId)) }
    }

    // ---- online classes (FocusQuest server) -----------------------------------------

    /** Normalises what a teacher types (“focus.school.org”, “https://…/”) to a base URL. */
    fun normaliseServer(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        return s
    }

    fun joinOnline(serverInput: String, code: String) {
        leave()
        val server = normaliseServer(serverInput)
        _state.value = StudentLinkState(LinkPhase.Connecting, online = true, server = server)
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable && server.startsWith("http://", ignoreCase = true)) {
            fail("For privacy, online classes need a secure https:// address.")
            return
        }
        connection = scope.launch {
            val profile = activeProfile() ?: run { fail("Create a player profile first."); return@launch }
            val body = JSONObject()
                .put("code", code.trim().uppercase())
                .put("profile", profile.toJson())
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            val reply = try {
                http.newCall(
                    Request.Builder().url("$server/api/student/join")
                        .post(body.toString().toRequestBody(JSON)).build(),
                ).execute().use { r -> r.code to (r.body?.string() ?: "") }
            } catch (e: IOException) {
                fail("Couldn't reach $server. Check the address and your internet connection.")
                return@launch
            } catch (e: IllegalArgumentException) {
                fail("That server address isn't valid.")
                return@launch
            }
            val json = runCatching { JSONObject(reply.second) }.getOrNull()
            if (reply.first != 200 || json == null || !json.has("token")) {
                fail(json?.optString("error")?.ifBlank { null } ?: "The server refused to join (${reply.first}).")
                return@launch
            }
            val token = json.getString("token")
            val className = json.optString("className")
            store.updateSettings { it.copy(onlineServer = server, onlineToken = token, onlineClassName = className) }
            runOnline(server, token, className)
        }
    }

    /** Reconnects to the saved online class (called at app start). */
    fun resumeOnline() {
        val s = store.settings.value
        if (s.onlineToken.isBlank() || s.onlineServer.isBlank() || connection?.isActive == true) return
        _state.value = StudentLinkState(LinkPhase.Connecting, online = true, server = s.onlineServer, className = s.onlineClassName)
        connection = scope.launch { runOnline(s.onlineServer, s.onlineToken, s.onlineClassName) }
    }

    private sealed interface WsEvent {
        data class Message(val text: String) : WsEvent
        data class Closed(val code: Int, val reason: String) : WsEvent
        data class Failed(val error: Throwable) : WsEvent
    }

    private suspend fun runOnline(server: String, token: String, savedName: String) {
        val wsUrl = server.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/ws/student"
        var backoff = 2_000L
        while (scope.isActive && connection?.isActive != false) {
            val events = Channel<WsEvent>(Channel.UNLIMITED)
            val ws = http.newWebSocket(
                Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(WsEvent.Message(text)) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); events.trySend(WsEvent.Closed(code, reason)) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(WsEvent.Closed(code, reason)) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(WsEvent.Failed(t)) }
                },
            )
            sender = { msg -> ws.send(msg.toString()) }
            val loop = startStateLoop()
            var closeCode = 0
            try {
                for (e in events) {
                    when (e) {
                        is WsEvent.Message -> {
                            val m = runCatching { JSONObject(e.text) }.getOrNull() ?: continue
                            if (m.optString("t") == "welcome") {
                                backoff = 2_000L
                                val name = m.optString("className").ifBlank { savedName }
                                onConnected(name)
                                if (name != store.settings.value.onlineClassName) store.updateSettings { it.copy(onlineClassName = name) }
                            } else handleIncoming(m)
                        }
                        is WsEvent.Closed -> { closeCode = e.code; break }
                        is WsEvent.Failed -> break
                    }
                }
            } finally {
                loop.cancel()
                sender = null
                ws.cancel()
            }
            // 4001 unauthorised / 4003 removed by the teacher / 4004 class closed: stop retrying.
            if (closeCode in setOf(4001, 4003, 4004)) {
                store.updateSettings { it.copy(onlineToken = "") }
                fail(
                    when (closeCode) {
                        4003 -> "Your teacher removed you from ${savedName.ifBlank { "the class" }}."
                        4004 -> "This class has been closed by the teacher."
                        else -> "This phone is no longer in the class. Join again with the class code."
                    },
                )
                return
            }
            _state.update { it.copy(phase = LinkPhase.Reconnecting, message = "Reconnecting to ${it.className.ifBlank { "the class" }}…") }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    // ---- same‑Wi‑Fi trainer tablet ---------------------------------------------------

    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolveMutex = Mutex()

    fun startDiscovery() {
        if (discovery != null) return
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("focusquest")?.apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovery = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                scope.launch { resolve(nsd, info)?.let { f -> _found.update { list -> list.filterNot { it.name == f.name } + f } } }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.update { list -> list.filterNot { it.name == info.serviceName } }
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discovery = null
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): FoundClass? = resolveMutex.withLock {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses.firstOrNull()?.hostAddress else si.host?.hostAddress
                    if (cont.isActive) cont.resume(host?.let { FoundClass(si.serviceName, it, si.port) })
                }
            })
        }
    }

    fun stopDiscovery() {
        discovery?.let { l -> try { context.getSystemService(NsdManager::class.java)?.stopServiceDiscovery(l) } catch (_: Exception) {} }
        discovery = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    fun join(host: String, port: Int, code: String) {
        leave()
        _state.value = StudentLinkState(LinkPhase.Connecting, host = host, port = port)
        connection = scope.launch {
            var attempt = 0
            while (isActive) {
                val result = runLocal(host, port, code)
                if (!isActive) break
                if (result is Outcome.Rejected) { fail(result.message); break }
                attempt++
                val neverConnected = _state.value.className.isEmpty()
                if (neverConnected && attempt >= 3) { fail("Can't reach the trainer at $host:$port"); break }
                _state.update { it.copy(phase = LinkPhase.Reconnecting, message = if (neverConnected) "Trying to reach the trainer…" else "Reconnecting to ${it.className}…") }
                delay(3000)
            }
        }
    }

    private sealed interface Outcome {
        data object Lost : Outcome
        data class Rejected(val message: String) : Outcome
    }

    private fun runLocal(host: String, port: Int, code: String): Outcome {
        val profile = activeProfile() ?: return Outcome.Rejected("Create a player profile first.")
        val s = Socket()
        val lock = Any()
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val input = BufferedInputStream(s.getInputStream())
            sender = { msg ->
                try {
                    synchronized(lock) { out.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8)); out.flush() }
                    true
                } catch (_: IOException) { try { s.close() } catch (_: IOException) {}; false }
            }
            sender?.invoke(Protocol.hello(code, profile, "${Build.MANUFACTURER} ${Build.MODEL}"))
            val reply = JSONObject(Protocol.readLine(input) ?: return Outcome.Lost)
            if (reply.optString("t") == "error") return Outcome.Rejected(reply.optString("msg", "The trainer refused the connection."))
            onConnected(reply.optString("className"))
            val loop = startStateLoop()
            try {
                while (true) {
                    val line = Protocol.readLine(input) ?: break
                    runCatching { JSONObject(line) }.getOrNull()?.let(::handleIncoming)
                }
            } finally {
                loop.cancel()
            }
            return Outcome.Lost
        } catch (_: Exception) {
            return Outcome.Lost
        } finally {
            sender = null
            try { s.close() } catch (_: IOException) {}
        }
    }

    // ---- common ----------------------------------------------------------------------

    private fun activeProfile() =
        store.settings.value.activeProfileId?.let { id -> store.profiles.value.firstOrNull { it.id == id } }
            ?: store.profiles.value.firstOrNull()

    private fun fail(message: String) {
        _state.update { it.copy(phase = LinkPhase.Error, message = message) }
    }

    /** Leaves the current class. For an online class the server is told, and the saved link is forgotten. */
    fun leave() {
        connection?.cancel()
        connection = null
        sender = null
        val s = store.settings.value
        if (s.onlineToken.isNotBlank() && s.onlineServer.isNotBlank()) {
            val server = s.onlineServer
            val token = s.onlineToken
            scope.launch {
                runCatching {
                    http.newCall(
                        Request.Builder().url("$server/api/student/leave").header("Authorization", "Bearer $token")
                            .post(ByteArray(0).toRequestBody(null)).build(),
                    ).execute().close()
                }
            }
            store.updateSettings { it.copy(onlineToken = "", onlineServer = server) } // keep the address for next time
        }
        _state.value = StudentLinkState()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
