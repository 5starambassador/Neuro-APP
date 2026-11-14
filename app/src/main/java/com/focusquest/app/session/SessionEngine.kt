package com.focusquest.app.session

import com.focusquest.app.data.GameId
import com.focusquest.app.data.Metric
import com.focusquest.app.data.SessionRecord
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Frame-by-frame bookkeeping for one training session: decides when the player is
 * "focused", tracks streaks, points and the attention timeline, and adapts the
 * threshold so the difficulty stays in the sweet spot.
 */
class SessionEngine(
    val durationSec: Int,
    startThreshold: Int,
    private val adaptive: Boolean,
    /** Which reading counts towards the goal: attention for focus games, meditation for calm games. */
    val metric: Metric = Metric.ATTENTION,
) {
    val startThreshold = startThreshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    var threshold = this.startThreshold
        private set
    var elapsed = 0f
        private set
    var focused = false
        private set
    /** Smoothed value of the driving reading (attention or calm). */
    var smoothedLevel = 0f
        private set
    var currentStreak = 0f
        private set
    var points = 0f
        private set

    private var focusedTime = 0f
    private var longestStreak = 0f
    private var attentionSum = 0L
    private var meditationSum = 0L
    private var samples = 0
    private val timeline = ArrayList<Int>()
    private var nextSampleAt = 1f
    private var windowStart = 0f
    private var windowFocused = 0f
    private var lastMilestone = 0

    val finished: Boolean get() = elapsed >= durationSec
    val remainingSec: Int get() = (durationSec - elapsed).coerceAtLeast(0f).let { kotlin.math.ceil(it).toInt() }
    val focusPercent: Int get() = if (elapsed <= 0f) 0 else (focusedTime / elapsed * 100).roundToInt()

    /** Focus intensity above the threshold, 0..1. */
    val focusLevel: Float
        get() = if (!focused) 0f else ((smoothedLevel - threshold) / (100f - threshold)).coerceIn(0f, 1f)

    data class Step(val focused: Boolean, val focusLevel: Float, val streakMilestone: Boolean)

    /** Advance by [dt] seconds. Call only while the signal is good and the session isn't paused. */
    fun step(dt: Float, attention: Int, meditation: Int): Step {
        if (finished) return Step(focused, focusLevel, false)
        val d = dt.coerceIn(0f, 0.25f).coerceAtMost(durationSec - elapsed)
        val level = if (metric == Metric.ATTENTION) attention else meditation
        if (elapsed == 0f && smoothedLevel == 0f) smoothedLevel = level.toFloat()
        smoothedLevel += (level - smoothedLevel) * (d * 3f).coerceAtMost(1f)

        focused = if (focused) smoothedLevel >= threshold - HYSTERESIS else smoothedLevel >= threshold
        elapsed += d

        var milestone = false
        if (focused) {
            focusedTime += d
            windowFocused += d
            currentStreak += d
            if (currentStreak > longestStreak) longestStreak = currentStreak
            points += d * 10f * (1f + focusLevel)
            val m = (currentStreak / MILESTONE_SEC).toInt()
            if (m > lastMilestone) {
                lastMilestone = m
                milestone = true
                points += 25f * m
            }
        } else {
            currentStreak = 0f
            lastMilestone = 0
        }

        while (elapsed >= nextSampleAt) {
            timeline += level
            attentionSum += attention
            meditationSum += meditation
            samples++
            nextSampleAt += 1f
        }

        if (adaptive && elapsed - windowStart >= ADAPT_WINDOW_SEC) {
            val ratio = windowFocused / (elapsed - windowStart)
            threshold = when {
                ratio > 0.75f -> threshold + ADAPT_STEP
                ratio < 0.30f -> threshold - ADAPT_STEP
                else -> threshold
            }.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
            windowStart = elapsed
            windowFocused = 0f
        }
        return Step(focused, focusLevel, milestone)
    }

    fun toRecord(profileId: String, game: GameId, startedAt: Long, source: String): SessionRecord =
        SessionRecord(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            game = game,
            startedAt = startedAt,
            durationSec = elapsed.roundToInt(),
            focusedSec = focusedTime.roundToInt(),
            avgAttention = if (samples == 0) 0 else (attentionSum / samples).toInt(),
            avgMeditation = if (samples == 0) 0 else (meditationSum / samples).toInt(),
            longestStreakSec = longestStreak.roundToInt(),
            points = points.roundToInt(),
            startThreshold = startThreshold,
            endThreshold = threshold,
            source = source,
            attentionTimeline = downsample(timeline, SessionRecord.MAX_TIMELINE),
        )

    companion object {
        const val MIN_THRESHOLD = 25
        const val MAX_THRESHOLD = 80
        const val ADAPT_WINDOW_SEC = 30f
        const val ADAPT_STEP = 5
        const val HYSTERESIS = 3f
        const val MILESTONE_SEC = 15f

        fun downsample(values: List<Int>, max: Int): List<Int> {
            if (values.size <= max) return values.toList()
            val bucket = values.size.toDouble() / max
            return List(max) { i ->
                val from = (i * bucket).toInt()
                val to = ((i + 1) * bucket).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
                values.subList(from, to).average().roundToInt()
            }
        }
    }
}
