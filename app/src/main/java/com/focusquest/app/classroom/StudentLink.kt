package com.focusquest.app.classroom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.focusquest.app.data.GameId
import com.focusquest.app.data.SessionRecord
import com.focusquest.app.data.Store
import com.focusquest.app.eeg.EegHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

data class FoundClass(val name: String, val host: String, val port: Int)

enum class LinkPhase { Idle, Connecting, Connected, Reconnecting, Error }

data class StudentLinkState(
    val phase: LinkPhase = LinkPhase.Idle,
    val className: String = "",
    val host: String = "",
    val port: Int = 0,
    val message: String? = null,
)

sealed interface ClassEvent {
    data class Start(val groupId: String, val game: GameId, val durationSec: Int) : ClassEvent
    data class Stop(val groupId: String) : ClassEvent
    data class Cheer(val text: String) : ClassEvent
}

/**
 * Student side of the classroom link: finds trainers on the Wi‑Fi, streams live state to
 * the joined trainer every second, delivers finished sessions (resent until acknowledged),
 * and relays the trainer's commands to the UI.
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
    @Volatile private var socket: Socket? = null
    private val sendLock = Any()

    /** Results not yet acknowledged by the trainer; resent after a reconnect. */
    private val pending = LinkedHashMap<String, Pair<SessionRecord, String?>>()

    // ---- discovery -------------------------------------------------------------------

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
        // Android allows one resolve at a time, so they're serialised here.
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

    // ---- connection ------------------------------------------------------------------

    fun join(host: String, port: Int, code: String) {
        leave()
        _state.value = StudentLinkState(LinkPhase.Connecting, host = host, port = port)
        connection = scope.launch {
            var attempt = 0
            while (isActive) {
                val result = runConnection(host, port, code)
                if (!isActive) break
                if (result is Outcome.Rejected) {
                    _state.update { it.copy(phase = LinkPhase.Error, message = result.message) }
                    break
                }
                // Lost or couldn't reach the trainer: keep retrying quietly.
                attempt++
                _state.update {
                    it.copy(
                        phase = if (it.className.isEmpty() && attempt >= 3) LinkPhase.Error else LinkPhase.Reconnecting,
                        message = if (it.className.isEmpty()) "Can't reach the trainer at $host:$port" else "Reconnecting to ${it.className}…",
                    )
                }
                if (_state.value.phase == LinkPhase.Error) break
                delay(3000)
            }
        }
    }

    private sealed interface Outcome {
        data object Lost : Outcome
        data class Rejected(val message: String) : Outcome
    }

    private suspend fun runConnection(host: String, port: Int, code: String): Outcome {
        val profile = store.settings.value.activeProfileId?.let { id -> store.profiles.value.firstOrNull { it.id == id } }
            ?: store.profiles.value.firstOrNull()
            ?: return Outcome.Rejected("Create a player profile first.")
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
            s.soTimeout = 0
            socket = s
            val input = BufferedInputStream(s.getInputStream())
            send(Protocol.hello(code, profile, "${Build.MANUFACTURER} ${Build.MODEL}"))
            val reply = JSONObject(Protocol.readLine(input) ?: return Outcome.Lost)
            if (reply.optString("t") == "error") return Outcome.Rejected(reply.optString("msg", "The trainer refused the connection."))
            _state.update { it.copy(phase = LinkPhase.Connected, className = reply.optString("className"), message = null) }
            synchronized(pending) { pending.values.toList() }.forEach { (rec, group) -> send(Protocol.result(rec, group)) }

            val sender = scope.launch {
                while (isActive) {
                    send(Protocol.state(hub.state.value, liveSession.value))
                    delay(1000)
                }
            }
            try {
                while (true) {
                    val line = Protocol.readLine(input) ?: break
                    val m = try { JSONObject(line) } catch (_: Exception) { continue }
                    when (m.optString("t")) {
                        "start" -> runCatching {
                            _events.tryEmit(ClassEvent.Start(m.getString("group"), GameId.valueOf(m.getString("game")), m.getInt("duration")))
                        }
                        "stop" -> _events.tryEmit(ClassEvent.Stop(m.optString("group")))
                        "cheer" -> _events.tryEmit(ClassEvent.Cheer(m.optString("text").take(120)))
                        "ack" -> synchronized(pending) { pending.remove(m.optString("id")) }
                    }
                }
            } finally {
                sender.cancel()
            }
            return Outcome.Lost
        } catch (_: IOException) {
            return Outcome.Lost
        } catch (_: Exception) {
            return Outcome.Lost
        } finally {
            try { s.close() } catch (_: IOException) {}
            if (socket === s) socket = null
        }
    }

    private fun send(o: JSONObject) {
        val s = socket ?: return
        try {
            synchronized(sendLock) {
                val out = s.getOutputStream()
                out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: IOException) {
            try { s.close() } catch (_: IOException) {}
        }
    }

    /** Queues a finished session for the trainer; it is resent until acknowledged. */
    fun sendResult(record: SessionRecord, groupId: String?) {
        if (_state.value.phase == LinkPhase.Idle || _state.value.phase == LinkPhase.Error) return
        synchronized(pending) { pending[record.id] = record to groupId }
        scope.launch { send(Protocol.result(record, groupId)) }
    }

    fun leave() {
        connection?.cancel()
        connection = null
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        _state.value = StudentLinkState()
    }
}
