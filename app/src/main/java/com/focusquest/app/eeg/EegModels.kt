package com.focusquest.app.eeg

data class EegBands(
    val delta: Long,
    val theta: Long,
    val lowAlpha: Long,
    val highAlpha: Long,
    val lowBeta: Long,
    val highBeta: Long,
    val lowGamma: Long,
    val midGamma: Long,
) {
    fun values(): List<Long> = listOf(delta, theta, lowAlpha, highAlpha, lowBeta, highBeta, lowGamma, midGamma)

    companion object {
        val LABELS = listOf("δ", "θ", "Lα", "Hα", "Lβ", "Hβ", "Lγ", "Mγ")
    }
}

enum class SourceKind { None, Headset, Simulator }

enum class LinkState { Disconnected, Connecting, Connected, Error }

enum class SimMode { Auto, Touch }

data class PairedDevice(val name: String, val address: String) {
    val looksLikeHeadset: Boolean
        get() = HEADSET_HINTS.any { name.contains(it, ignoreCase = true) }

    companion object {
        private val HEADSET_HINTS = listOf("brainlink", "mindwave", "neurosky", "macrotellect", "eeg")
    }
}

data class EegState(
    val source: SourceKind = SourceKind.None,
    val link: LinkState = LinkState.Disconnected,
    val deviceName: String? = null,
    val attention: Int = 0,
    val meditation: Int = 0,
    val poorSignal: Int = 200,
    val bands: EegBands? = null,
    val blinkCount: Int = 0,
    val message: String? = null,
) {
    val connected: Boolean get() = link == LinkState.Connected

    /** True when readings are trustworthy enough to drive a game. */
    val signalOk: Boolean get() = connected && poorSignal < POOR_SIGNAL_LIMIT

    /** 0–100, higher is better. */
    val signalQuality: Int get() = if (!connected) 0 else (100 - poorSignal * 100 / 200).coerceIn(0, 100)

    companion object {
        const val POOR_SIGNAL_LIMIT = 50
    }
}
