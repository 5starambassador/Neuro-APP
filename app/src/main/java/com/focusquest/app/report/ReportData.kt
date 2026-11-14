package com.focusquest.app.report

import com.focusquest.app.data.GameId
import com.focusquest.app.data.GroupSession
import com.focusquest.app.data.Metric
import com.focusquest.app.data.Profile
import com.focusquest.app.data.SessionRecord
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** Report figures are computed here (pure Kotlin, unit-tested); PdfReports only draws them. */

data class GameSummary(val game: GameId, val sessions: Int, val totalSec: Int, val focusPct: Int, val bestStreakSec: Int)

data class StudentReport(
    val student: Profile,
    val from: Long,
    val to: Long,
    val sessions: List<SessionRecord>,   // oldest first
    val totalSec: Int,
    val activeDays: Int,
    val focusPct: Int,                   // time-weighted % of time at or above the goal
    val bestStreakSec: Int,
    val totalPoints: Int,
    val avgAttention: Int?,              // over focus-game sessions
    val avgCalm: Int?,                   // over calm-game sessions
    val focusChange: Int?,               // later half minus earlier half, percentage points
    val goalStart: Int?,
    val goalEnd: Int?,
    val perGame: List<GameSummary>,
    val dailyMinutes: List<Pair<Long, Float>>,
    val recommendations: List<String>,
)

data class ClassRow(
    val student: Profile,
    val sessions: Int,
    val minutes: Int,
    val focusPct: Int?,
    val focusChange: Int?,
    val lastSession: Long?,
    val flag: String?,
)

data class GroupRow(val student: Profile, val record: SessionRecord?)

data class GroupReport(
    val group: GroupSession,
    val rows: List<GroupRow>,
    val avgFocusPct: Int?,
    val completed: Int,
)

object Reports {
    const val DAY_MS = 24L * 60 * 60 * 1000

    fun focusPct(records: List<SessionRecord>): Int? {
        val total = records.sumOf { it.durationSec }
        return if (total == 0) null else (records.sumOf { it.focusedSec } * 100.0 / total).roundToInt()
    }

    /** Change in focus % between the earlier and later half of the sessions (needs 4+). */
    fun focusChange(sortedOldestFirst: List<SessionRecord>): Int? {
        if (sortedOldestFirst.size < 4) return null
        val half = sortedOldestFirst.size / 2
        val early = focusPct(sortedOldestFirst.take(half)) ?: return null
        val late = focusPct(sortedOldestFirst.takeLast(half)) ?: return null
        return late - early
    }

    fun startOfDay(t: Long, tz: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun student(
        student: Profile,
        all: List<SessionRecord>,
        from: Long,
        to: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): StudentReport {
        val s = all.filter { it.profileId == student.id && it.startedAt in from..to }.sortedBy { it.startedAt }
        val focusGames = s.filter { it.game.metric == Metric.ATTENTION }
        val calmGames = s.filter { it.game.metric == Metric.CALM }
        val days = s.map { startOfDay(it.startedAt, tz) }.toSet()

        // Daily minutes for the last (up to) 14 days of the range.
        val lastDay = startOfDay(to, tz)
        val firstDay = maxOf(startOfDay(from, tz), lastDay - 13 * DAY_MS)
        val daily = generateSequence(firstDay) { d ->
            Calendar.getInstance(tz).apply { timeInMillis = d; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        }.takeWhile { it <= lastDay }.map { d ->
            d to s.filter { startOfDay(it.startedAt, tz) == d }.sumOf { it.durationSec } / 60f
        }.toList()

        val perGame = s.groupBy { it.game }.map { (g, list) ->
            GameSummary(g, list.size, list.sumOf { it.durationSec }, focusPct(list) ?: 0, list.maxOf { it.longestStreakSec })
        }.sortedByDescending { it.totalSec }

        val base = StudentReport(
            student = student, from = from, to = to, sessions = s,
            totalSec = s.sumOf { it.durationSec },
            activeDays = days.size,
            focusPct = focusPct(s) ?: 0,
            bestStreakSec = s.maxOfOrNull { it.longestStreakSec } ?: 0,
            totalPoints = s.sumOf { it.points },
            avgAttention = focusGames.takeIf { it.isNotEmpty() }?.map { it.avgAttention }?.average()?.roundToInt(),
            avgCalm = calmGames.takeIf { it.isNotEmpty() }?.map { it.avgMeditation }?.average()?.roundToInt(),
            focusChange = focusChange(s),
            goalStart = focusGames.firstOrNull()?.startThreshold,
            goalEnd = focusGames.lastOrNull()?.endThreshold,
            perGame = perGame,
            dailyMinutes = daily,
            recommendations = emptyList(),
        )
        return base.copy(recommendations = recommendations(base))
    }

    /** Rule-based coaching suggestions; worded as practice tips, never as clinical advice. */
    fun recommendations(r: StudentReport): List<String> = buildList {
        val n = r.sessions.size
        if (n == 0) { add("No sessions were recorded in this period."); return@buildList }
        val weeks = maxOf(1.0, (r.to - r.from) / (7.0 * DAY_MS))
        if (n / weeks < 3) add("Aim for 3–5 short sessions a week. Regular practice helps more than occasional long sessions.")
        if (r.focusPct >= 70 && (r.goalEnd ?: 0) >= (r.goalStart ?: 0) + 5) {
            add("Regularly above the goal: try 10-minute sessions or a higher starting goal in Settings.")
        }
        if (r.focusPct < 40) add("Time on goal is low: use 2–5 minute sessions and goal-based games (Bloom Garden, Bridge Builder) to build success.")
        r.focusChange?.let {
            if (it >= 8) add("Time on goal rose by $it points across the period. Keep the same routine.")
            if (it <= -8) add("Time on goal fell by ${-it} points. Check headset fit, time of day and tiredness before sessions.")
        }
        if (r.sessions.none { it.game.metric == Metric.CALM } && n >= 3) {
            add("Add a calm game (Calm Balloon or Lantern Lake) at the start of a session to practise settling down.")
        }
        if (isEmpty()) add("Steady progress. Keep going!")
    }

    fun classRows(
        students: List<Profile>,
        all: List<SessionRecord>,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis(),
    ): List<ClassRow> = students.map { st ->
        val s = all.filter { it.profileId == st.id && it.startedAt in from..to }.sortedBy { it.startedAt }
        val last = all.filter { it.profileId == st.id }.maxOfOrNull { it.startedAt }
        val pct = focusPct(s)
        val change = focusChange(s)
        val flag = when {
            last == null || now - last > 7 * DAY_MS -> "Inactive 7+ days"
            pct != null && pct < 40 -> "Needs support"
            change != null && change <= -8 -> "Declining"
            change != null && change >= 8 -> "Improving"
            else -> null
        }
        ClassRow(st, s.size, (s.sumOf { it.durationSec } / 60.0).roundToInt(), pct, change, last, flag)
    }.sortedBy { it.student.name.lowercase() }

    fun group(group: GroupSession, students: List<Profile>, all: List<SessionRecord>): GroupReport {
        val byId = all.associateBy { it.id }
        val results = group.resultIds.mapNotNull { byId[it] }
        val rows = group.studentIds.map { id ->
            val st = students.firstOrNull { it.id == id } ?: Profile(id, "Unknown student", 0, 0)
            GroupRow(st, results.firstOrNull { it.profileId == id })
        }.sortedByDescending { it.record?.focusPercent ?: -1 }
        return GroupReport(group, rows, focusPct(results), results.size)
    }
}
