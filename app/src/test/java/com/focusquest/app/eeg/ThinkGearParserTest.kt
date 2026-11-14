package com.focusquest.app.eeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkGearParserTest {

    private fun packet(vararg payload: Int): ByteArray {
        val checksum = payload.sum().inv() and 0xFF
        return (listOf(0xAA, 0xAA, payload.size) + payload.toList() + checksum)
            .map { it.toByte() }.toByteArray()
    }

    private fun parse(bytes: ByteArray): List<ThinkGearEvent> {
        val events = mutableListOf<ThinkGearEvent>()
        ThinkGearParser { events += it }.feed(bytes)
        return events
    }

    @Test
    fun parsesAttentionMeditationAndSignal() {
        val events = parse(packet(0x02, 0x00, 0x04, 0x3C, 0x05, 0x28))
        assertEquals(
            listOf(
                ThinkGearEvent.PoorSignal(0),
                ThinkGearEvent.Attention(60),
                ThinkGearEvent.Meditation(40),
            ),
            events,
        )
    }

    @Test
    fun parsesSignedRawValue() {
        val events = parse(packet(0x80, 0x02, 0xFF, 0x9C)) // -100
        assertEquals(listOf(ThinkGearEvent.Raw(-100)), events)
    }

    @Test
    fun parsesEegBands() {
        val payload = mutableListOf(0x83, 24)
        for (band in 1..8) payload += listOf(0x00, 0x01, band) // 256 + band
        val events = parse(packet(*payload.toIntArray()))
        val bands = (events.single() as ThinkGearEvent.Bands).bands
        assertEquals((1..8).map { 256L + it }, bands.values())
    }

    @Test
    fun rejectsBadChecksum() {
        val bytes = packet(0x04, 0x50)
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
        val events = mutableListOf<ThinkGearEvent>()
        val parser = ThinkGearParser { events += it }
        parser.feed(bytes)
        assertTrue(events.isEmpty())
        assertEquals(1, parser.checksumErrors)
    }

    @Test
    fun resyncsAfterGarbageAndSplitReads() {
        val stream = byteArrayOf(0x12, 0x00, 0xAA.toByte()) + packet(0x04, 0x46) + packet(0x55, 0x05, 0x10)
        val events = mutableListOf<ThinkGearEvent>()
        val parser = ThinkGearParser { events += it }
        stream.forEach { parser.feed(it.toInt() and 0xFF) } // one byte at a time
        assertEquals(listOf(ThinkGearEvent.Attention(70), ThinkGearEvent.Meditation(16)), events)
    }
}
