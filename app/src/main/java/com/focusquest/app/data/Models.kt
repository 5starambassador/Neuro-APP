package com.focusquest.app.data

/** Which headset reading drives a game. */
enum class Metric(val label: String, val skill: String) {
    ATTENTION("Attention", "Focus"),
    CALM("Calm", "Calm"),
}

/**
 * Games are grouped by the skill they train:
 * - Sustained focus: hold attention steadily over time.
 * - Focus bursts: build up focus and keep it until a goal triggers.
 * - Calm: lower arousal and relax (driven by the meditation reading).
 */
enum class GameId(val title: String, val tagline: String, val metric: Metric, val style: String) {
    ROCKET("Sky Rocket", "Stay focused to blast higher through the stars", Metric.ATTENTION, "Sustained"),
    DRIVE("Night Drive", "Focus to speed down the glowing highway", Metric.ATTENTION, "Sustained"),
    MOSAIC("Picture Reveal", "Each moment of focus uncovers part of a hidden picture", Metric.ATTENTION, "Sustained"),
    GARDEN("Bloom Garden", "Focus to grow flowers and chase the rain away", Metric.ATTENTION, "Goal-based"),
    BRIDGE("Bridge Builder", "Lay planks with your focus so your explorer can cross", Metric.ATTENTION, "Goal-based"),
    ARCHERY("Archer's Focus", "Hold focus to draw the bow; steadier focus hits closer to the bullseye", Metric.ATTENTION, "Bursts"),
    BALLOON("Calm Balloon", "Relax and breathe slowly to float over the hills", Metric.CALM, "Relaxation"),
    LANTERN("Lantern Lake", "Stay calm to light lanterns and send them into the night", Metric.CALM, "Relaxation"),
}

data class Profile(
    val id: String,
    val name: String,
    val age: Int,
    val colorIndex: Int,
)

data class SessionRecord(
    val id: String,
    val profileId: String,
    val game: GameId,
    val startedAt: Long,
    val durationSec: Int,
    val focusedSec: Int,
    val avgAttention: Int,
    val avgMeditation: Int,
    val longestStreakSec: Int,
    val points: Int,
    val startThreshold: Int,
    val endThreshold: Int,
    val source: String,
    /**
     * One value per sampled second of the game's driving reading (attention, or calm for
     * calm games), downsampled to at most [MAX_TIMELINE] points.
     */
    val attentionTimeline: List<Int>,
) {
    /** Rounded the same way as report totals so the numbers always agree. */
    val focusPercent: Int get() = if (durationSec == 0) 0 else kotlin.math.round(focusedSec * 100.0 / durationSec).toInt()

    companion object {
        const val MAX_TIMELINE = 180
    }
}

/** A trainer-led session that several students played at the same time over Wi‑Fi. */
data class GroupSession(
    val id: String,
    val className: String,
    val game: GameId,
    val plannedSec: Int,
    val startedAt: Long,
    val endedAt: Long,
    val studentIds: List<String>,
    val resultIds: List<String>,
)

data class Settings(
    val defaultDurationSec: Int = 300,
    val adaptiveThreshold: Boolean = true,
    val threshold: Int = 50,
    val haptics: Boolean = true,
    val activeProfileId: String? = null,
    /** Name shown to students when this device runs Trainer mode. */
    val className: String = "",
    /** Online class this phone belongs to (FocusQuest server); kept so it reconnects after restarts. */
    val onlineServer: String = "",
    val onlineToken: String = "",
    val onlineClassName: String = "",
)
