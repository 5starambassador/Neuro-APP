package com.focusquest.app.session

import com.focusquest.app.data.GameId
import com.focusquest.app.data.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEngineTest {

    private fun SessionEngine.run(seconds: Int, attention: Int) {
        // A couple of extra frames absorb float rounding; the engine clamps at the duration.
        repeat(seconds * 10 + 2) { step(0.1f, attention, 40) }
    }

    @Test
    fun highAttentionCountsAsFocused() {
        val e = SessionEngine(durationSec = 20, startThreshold = 50, adaptive = false)
        e.run(20, 80)
        assertTrue(e.finished)
        val r = e.toRecord("p", GameId.ROCKET, 0L, "Simulator")
        assertEquals(20, r.durationSec)
        assertEquals(20, r.focusedSec)
        assertEquals(100, r.focusPercent)
        assertEquals(20, r.longestStreakSec)
        assertEquals(80, r.avgAttention)
        assertEquals(20, r.attentionTimeline.size)
        assertTrue(r.points > 0)
    }

    @Test
    fun lowAttentionIsNotFocused() {
        val e = SessionEngine(durationSec = 10, startThreshold = 50, adaptive = false)
        e.run(10, 20)
        val r = e.toRecord("p", GameId.GARDEN, 0L, "Simulator")
        assertEquals(0, r.focusedSec)
        assertEquals(0, r.points)
        assertFalse(e.focused)
    }

    @Test
    fun adaptiveThresholdRisesWhenTooEasyAndFallsWhenTooHard() {
        val easy = SessionEngine(durationSec = 120, startThreshold = 50, adaptive = true)
        easy.run(31, 90)
        assertEquals(55, easy.threshold)

        val hard = SessionEngine(durationSec = 120, startThreshold = 50, adaptive = true)
        hard.run(31, 10)
        assertEquals(45, hard.threshold)
    }

    @Test
    fun calmGamesAreDrivenByMeditationNotAttention() {
        // run() feeds attention as given and meditation = 40
        val calm = SessionEngine(durationSec = 10, startThreshold = 30, adaptive = false, metric = Metric.CALM)
        calm.run(10, 5) // very low attention, meditation 40 >= 30
        val r = calm.toRecord("p", GameId.LANTERN, 0L, "x")
        assertEquals(10, r.focusedSec)
        assertEquals(40, r.attentionTimeline.first())

        val calmMiss = SessionEngine(durationSec = 10, startThreshold = 50, adaptive = false, metric = Metric.CALM)
        calmMiss.run(10, 95) // high attention doesn't help a calm game
        assertEquals(0, calmMiss.toRecord("p", GameId.BALLOON, 0L, "x").focusedSec)
    }

    @Test
    fun stopsAtDuration() {
        val e = SessionEngine(durationSec = 5, startThreshold = 50, adaptive = false)
        e.run(8, 70)
        assertEquals(5, e.toRecord("p", GameId.DRIVE, 0L, "x").durationSec)
        assertEquals(0, e.remainingSec)
    }

    @Test
    fun downsampleAveragesBuckets() {
        assertEquals(listOf(2, 6), SessionEngine.downsample(listOf(1, 2, 3, 5, 6, 7), 2))
        assertEquals(listOf(1, 2), SessionEngine.downsample(listOf(1, 2), 5))
    }
}
