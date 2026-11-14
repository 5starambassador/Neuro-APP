package com.focusquest.app.eeg

/**
 * Streaming parser for the NeuroSky ThinkGear serial protocol, which BrainLink
 * headsets (and other NeuroSky-chip headsets) send over Bluetooth SPP.
 *
 * Packet layout:  [0xAA] [0xAA] [PLENGTH] [PAYLOAD x PLENGTH] [CHECKSUM]
 * CHECKSUM = inverse of the low byte of the payload sum.
 *
 * Payload is a sequence of data rows: optional 0x55 EXCODE bytes, a CODE byte,
 * and either one value byte (CODE < 0x80) or [VLENGTH] + VLENGTH value bytes.
 */
class ThinkGearParser(private val onEvent: (ThinkGearEvent) -> Unit) {

    private enum class State { SYNC1, SYNC2, LENGTH, PAYLOAD, CHECKSUM }

    private var state = State.SYNC1
    private val payload = IntArray(MAX_PAYLOAD)
    private var payloadLength = 0
    private var payloadIndex = 0
    private var payloadSum = 0

    var packetsParsed = 0L
        private set
    var checksumErrors = 0L
        private set

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) feed(bytes[i].toInt() and 0xFF)
    }

    fun feed(b: Int) {
        when (state) {
            State.SYNC1 -> if (b == SYNC) state = State.SYNC2
            State.SYNC2 -> state = if (b == SYNC) State.LENGTH else State.SYNC1
            State.LENGTH -> when {
                b == SYNC -> Unit // extra sync byte, keep waiting for length
                b > MAX_PAYLOAD -> state = State.SYNC1
                else -> {
                    payloadLength = b
                    payloadIndex = 0
                    payloadSum = 0
                    state = if (b == 0) State.CHECKSUM else State.PAYLOAD
                }
            }
            State.PAYLOAD -> {
                payload[payloadIndex++] = b
                payloadSum += b
                if (payloadIndex >= payloadLength) state = State.CHECKSUM
            }
            State.CHECKSUM -> {
                val expected = payloadSum.inv() and 0xFF
                if (b == expected) {
                    packetsParsed++
                    parsePayload()
                } else {
                    checksumErrors++
                }
                state = State.SYNC1
            }
        }
    }

    private fun parsePayload() {
        var i = 0
        while (i < payloadLength) {
            while (i < payloadLength && payload[i] == EXCODE) i++
            if (i >= payloadLength) return
            val code = payload[i++]
            if (code >= 0x80) {
                if (i >= payloadLength) return
                val vLength = payload[i++]
                if (i + vLength > payloadLength) return
                handleMultiByte(code, i, vLength)
                i += vLength
            } else {
                if (i >= payloadLength) return
                handleSingleByte(code, payload[i++])
            }
        }
    }

    private fun handleSingleByte(code: Int, value: Int) {
        when (code) {
            CODE_POOR_SIGNAL -> onEvent(ThinkGearEvent.PoorSignal(value))
            CODE_ATTENTION -> onEvent(ThinkGearEvent.Attention(value.coerceIn(0, 100)))
            CODE_MEDITATION -> onEvent(ThinkGearEvent.Meditation(value.coerceIn(0, 100)))
            CODE_BLINK -> onEvent(ThinkGearEvent.Blink(value))
        }
    }

    private fun handleMultiByte(code: Int, start: Int, length: Int) {
        when (code) {
            CODE_RAW -> if (length == 2) {
                val raw = ((payload[start] shl 8) or payload[start + 1]).toShort().toInt()
                onEvent(ThinkGearEvent.Raw(raw))
            }
            CODE_ASIC_EEG -> if (length == 24) {
                val v = LongArray(8) { band ->
                    val o = start + band * 3
                    (payload[o].toLong() shl 16) or (payload[o + 1].toLong() shl 8) or payload[o + 2].toLong()
                }
                onEvent(ThinkGearEvent.Bands(EegBands(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])))
            }
        }
    }

    companion object {
        const val SYNC = 0xAA
        const val EXCODE = 0x55
        const val MAX_PAYLOAD = 169
        const val CODE_POOR_SIGNAL = 0x02
        const val CODE_ATTENTION = 0x04
        const val CODE_MEDITATION = 0x05
        const val CODE_BLINK = 0x16
        const val CODE_RAW = 0x80
        const val CODE_ASIC_EEG = 0x83
    }
}

sealed interface ThinkGearEvent {
    /** 0 = good contact, 200 = sensor not touching skin. */
    data class PoorSignal(val value: Int) : ThinkGearEvent
    data class Attention(val value: Int) : ThinkGearEvent
    data class Meditation(val value: Int) : ThinkGearEvent
    data class Blink(val strength: Int) : ThinkGearEvent
    data class Raw(val value: Int) : ThinkGearEvent
    data class Bands(val bands: EegBands) : ThinkGearEvent
}
