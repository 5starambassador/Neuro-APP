package com.focusquest.app.eeg

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
import java.io.IOException
import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Single source of brain-activity data for the whole app. Either reads a real
 * NeuroSky-chip headset (BrainLink etc.) over Bluetooth SPP, or runs a simulator
 * so the games can be used and tested without hardware.
 */
class EegHub(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EegState())
    val state: StateFlow<EegState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var lastPacketAt = 0L

    /** Simulator: "Touch" mode raises attention while the screen is held. */
    @Volatile var simulatorBoost = false
    private val _simMode = MutableStateFlow(SimMode.Auto)
    val simMode: StateFlow<SimMode> = _simMode.asStateFlow()

    init {
        // Watchdog: a silent headset means no data, not "focused".
        scope.launch {
            while (isActive) {
                delay(1000)
                val s = _state.value
                if (s.source == SourceKind.Headset && s.connected &&
                    SystemClock.elapsedRealtime() - lastPacketAt > STALE_MS
                ) {
                    _state.update { it.copy(poorSignal = 200) }
                }
            }
        }
    }

    fun setSimMode(mode: SimMode) {
        _simMode.value = mode
    }

    private fun adapter(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun bluetoothSupported(): Boolean = adapter() != null

    @SuppressLint("MissingPermission")
    fun bluetoothEnabled(): Boolean = adapter()?.isEnabled == true

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> {
        if (!hasConnectPermission()) return emptyList()
        val devices = try {
            adapter()?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        return devices
            .map { PairedDevice(it.name ?: "Unknown device", it.address) }
            .sortedWith(compareByDescending<PairedDevice> { it.looksLikeHeadset }.thenBy { it.name })
    }

    @SuppressLint("MissingPermission")
    fun connectHeadset(device: PairedDevice) {
        stop()
        _state.value = EegState(
            source = SourceKind.Headset,
            link = LinkState.Connecting,
            deviceName = device.name,
        )
        job = scope.launch(Dispatchers.IO) {
            val adapter = adapter()
            if (adapter == null || !hasConnectPermission()) {
                fail("Bluetooth is unavailable or permission was not granted.")
                return@launch
            }
            val remote = try {
                adapter.getRemoteDevice(device.address)
            } catch (e: IllegalArgumentException) {
                fail("Invalid device address.")
                return@launch
            }
            val s = openSocket { remote.createRfcommSocketToServiceRecord(SPP_UUID) }
                ?: openSocket { remote.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
            if (s == null) {
                if (isActive) fail("Couldn't connect. Turn the headset on, make sure it is paired, and try again.")
                return@launch
            }
            socket = s
            lastPacketAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(link = LinkState.Connected, message = null) }

            val parser = ThinkGearParser(::onEvent)
            val buffer = ByteArray(1024)
            try {
                val input = s.inputStream
                while (isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) parser.feed(buffer, n)
                }
                if (isActive) fail("The headset disconnected.")
            } catch (_: IOException) {
                if (isActive) fail("Connection to the headset was lost.")
            } finally {
                closeSocket()
            }
        }
    }

    private inline fun openSocket(create: () -> BluetoothSocket): BluetoothSocket? = try {
        create().also { it.connect() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun onEvent(event: ThinkGearEvent) {
        lastPacketAt = SystemClock.elapsedRealtime()
        when (event) {
            is ThinkGearEvent.PoorSignal -> _state.update { it.copy(poorSignal = event.value) }
            is ThinkGearEvent.Attention -> _state.update { it.copy(attention = event.value) }
            is ThinkGearEvent.Meditation -> _state.update { it.copy(meditation = event.value) }
            is ThinkGearEvent.Bands -> _state.update { it.copy(bands = event.bands) }
            is ThinkGearEvent.Blink -> _state.update { it.copy(blinkCount = it.blinkCount + 1) }
            is ThinkGearEvent.Raw -> Unit // 512 Hz raw samples aren't needed by the games
        }
    }

    fun startSimulator() {
        stop()
        _state.value = EegState(
            source = SourceKind.Simulator,
            link = LinkState.Connected,
            deviceName = "Simulator",
            attention = 50,
            meditation = 50,
            poorSignal = 0,
        )
        job = scope.launch {
            val rnd = Random(SystemClock.elapsedRealtime())
            var attention = 50.0
            var meditation = 50.0
            var t = 0.0
            while (isActive) {
                delay(SIM_TICK_MS)
                t += SIM_TICK_MS / 1000.0
                val target = when (_simMode.value) {
                    SimMode.Auto -> 58 + 27 * sin(2 * PI * t / 45) + 8 * sin(2 * PI * t / 11)
                    SimMode.Touch -> if (simulatorBoost) 88.0 else 22.0
                }
                val rate = if (_simMode.value == SimMode.Touch) 0.35 else 0.12
                attention += (target - attention) * rate + rnd.nextDouble(-4.0, 4.0)
                attention = attention.coerceIn(1.0, 100.0)
                val calmTarget = when (_simMode.value) {
                    SimMode.Auto -> (100 - attention) * 0.6 + 30 + 15 * sin(2 * PI * t / 37)
                    // Holding the screen raises both readings so it works for focus and calm games.
                    SimMode.Touch -> if (simulatorBoost) 85.0 else 25.0
                }
                val calmRate = if (_simMode.value == SimMode.Touch) 0.3 else 0.08
                meditation += (calmTarget - meditation) * calmRate + rnd.nextDouble(-3.0, 3.0)
                meditation = meditation.coerceIn(1.0, 100.0)
                val a = attention.roundToInt()
                _state.update {
                    it.copy(
                        attention = a,
                        meditation = meditation.roundToInt(),
                        poorSignal = 0,
                        bands = syntheticBands(a, rnd),
                    )
                }
            }
        }
    }

    private fun syntheticBands(attention: Int, rnd: Random): EegBands {
        fun n(base: Double) = (base * rnd.nextDouble(0.7, 1.3)).toLong()
        val f = attention / 100.0
        return EegBands(
            delta = n(400_000.0 * (1.3 - f)),
            theta = n(120_000.0 * (1.2 - f)),
            lowAlpha = n(40_000.0),
            highAlpha = n(30_000.0),
            lowBeta = n(20_000.0 * (0.5 + f)),
            highBeta = n(18_000.0 * (0.5 + f)),
            lowGamma = n(8_000.0 * (0.6 + f)),
            midGamma = n(5_000.0 * (0.6 + f)),
        )
    }

    private fun fail(message: String) {
        _state.update { it.copy(link = LinkState.Error, message = message, poorSignal = 200) }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    /** Disconnect from whatever source is active. */
    fun stop() {
        job?.cancel()
        job = null
        closeSocket()
        simulatorBoost = false
        _state.value = EegState()
    }

    companion object {
        /** Standard Serial Port Profile UUID used by BrainLink / NeuroSky headsets. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val STALE_MS = 4000L
        private const val SIM_TICK_MS = 250L
    }
}
