package com.focusquest.app.report

import com.focusquest.app.data.GameId
import com.focusquest.app.data.GroupSession
import com.focusquest.app.data.Profile
import com.focusquest.app.data.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ReportsTest {
    private val tz = TimeZone.getTimeZone("UTC")
    private val day = Reports.DAY_MS
    private val t0 = 1_700_000_000_000L - (1_700_000_000_000L % day) // midnight UTC
    private val asha = Profile("a", "Asha", 9, 0)
    private val ravi = Profile("r", "Ravi", 10, 1)

    private fun rec(id: String, who: String, dayIndex: Int, dur: Int, focused: Int, game: GameId = GameId.ROCKET, goal: Int = 50) =
        SessionRecord(id, who, game, t0 + dayIndex * day + 3_600_000, dur, focused, 60, 50, 20, 100, goal, goal, "Simulator", emptyList())

    @Test
    fun focusPercentIsTimeWeighted() {
        // 60 of 100 s and 10 of 300 s -> 70 / 400 = 17.5% -> 18
        assertEquals(18, Reports.focusPct(listOf(rec("1", "a", 0, 100, 60), rec("2", "a", 0, 300, 10))))
        assertNull(Reports.focusPct(emptyList()))
    }

    @Test
    fun focusChangeComparesHalves() {
        val s = listOf(rec("1", "a", 0, 100, 20), rec("2", "a", 1, 100, 30), rec("3", "a", 2, 100, 60), rec("4", "a", 3, 100, 70))
        assertEquals(40, Reports.focusChange(s)) // 65 - 25
        assertNull(Reports.focusChange(s.take(3)))
    }

    @Test
    fun studentReportFiltersByStudentAndRange() {
        val all = listOf(
            rec("1", "a", 0, 300, 150),
            rec("2", "a", 2, 300, 240, GameId.LANTERN),
            rec("3", "r", 1, 300, 300),
            rec("4", "a", 20, 300, 300), // outside range
        )
        val r = Reports.student(asha, all, t0, t0 + 7 * day - 1, tz)
        assertEquals(2, r.sessions.size)
        assertEquals(600, r.totalSec)
        assertEquals(2, r.activeDays)
        assertEquals(65, r.focusPct)
        assertEquals(7, r.dailyMinutes.size)
        assertEquals(5f, r.dailyMinutes[0].second)
        assertEquals(0f, r.dailyMinutes[1].second)
        assertEquals(50, r.avgCalm)
        assertTrue(r.recommendations.isNotEmpty())
    }

    @Test
    fun classRowsFlagInactiveAndStruggling() {
        val now = t0 + 10 * day
        val all = listOf(rec("1", "a", 9, 300, 60), rec("2", "r", 1, 300, 250))
        val rows = Reports.classRows(listOf(ravi, asha), all, t0, now, now)
        assertEquals(listOf("Asha", "Ravi"), rows.map { it.student.name })
        assertEquals("Needs support", rows[0].flag)
        assertEquals("Inactive 7+ days", rows[1].flag)
    }

    @Test
    fun groupReportMatchesResultsToStudents() {
        val all = listOf(rec("x", "a", 0, 120, 90), rec("y", "r", 0, 120, 30))
        val g = GroupSession("g", "Class 4B", GameId.ROCKET, 120, t0, t0 + 120_000, listOf("a", "r", "z"), listOf("x", "y"))
        val report = Reports.group(g, listOf(asha, ravi), all)
        assertEquals(2, report.completed)
        assertEquals(50, report.avgFocusPct) // 120/240
        assertEquals("Asha", report.rows.first().student.name)
        assertNull(report.rows.last().record) // student "z" never reported
    }
}
