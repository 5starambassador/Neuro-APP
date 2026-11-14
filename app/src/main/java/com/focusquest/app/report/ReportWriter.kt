package com.focusquest.app.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.focusquest.app.data.Metric
import com.focusquest.app.data.Profile
import com.focusquest.app.data.SessionRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the PDF/CSV report files and hands them to Android's share sheet. */
object ReportWriter {
    private const val NOTE = "Attention-training summary, not a medical or diagnostic report."
    private val dateFmt get() = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val dateTimeFmt get() = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    private fun dir(context: Context) = File(context.cacheDir, "reports").apply { mkdirs() }
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "report" }
    private fun mins(sec: Int) = if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
    private fun signed(v: Int?) = when { v == null -> "–"; v > 0 -> "+$v"; else -> "$v" }

    fun studentPdf(context: Context, r: StudentReport): File {
        val range = "${dateFmt.format(Date(r.from))} – ${dateFmt.format(Date(r.to))}"
        val pdf = PdfBuilder("Student report · ${r.student.name}", NOTE)
        pdf.title("${r.student.name}: Focus training report", "Age ${r.student.age} · $range")
        pdf.stats(
            listOf(
                "Sessions" to "${r.sessions.size}",
                "Training time" to mins(r.totalSec),
                "Active days" to "${r.activeDays}",
                "Time on goal" to "${r.focusPct}%",
                "Best streak" to mins(r.bestStreakSec),
                "Change in period" to (r.focusChange?.let { "${signed(it)} pts" } ?: "–"),
                "Goal level" to if (r.goalStart != null) "${r.goalStart} → ${r.goalEnd}" else "–",
                "Points" to "%,d".format(r.totalPoints),
            ),
        )
        if (r.avgAttention != null || r.avgCalm != null) {
            pdf.paragraph(
                listOfNotNull(
                    r.avgAttention?.let { "Average attention in focus games: $it/100." },
                    r.avgCalm?.let { "Average calm in calm games: $it/100." },
                ).joinToString("  "),
                color = PdfBuilder.DIM,
            )
        }

        pdf.heading("Coaching suggestions")
        pdf.bullets(r.recommendations)

        if (r.sessions.isNotEmpty()) {
            pdf.heading("Time on goal per session (%)", keepWith = 130f)
            pdf.lineChart(r.sessions.map { it.focusPercent }, threshold = null, color = PdfBuilder.GOOD)

            pdf.heading("Minutes trained per day", keepWith = 140f)
            val dayFmt = SimpleDateFormat("d/M", Locale.getDefault())
            pdf.barChart(r.dailyMinutes.map { it.second }, r.dailyMinutes.map { dayFmt.format(Date(it.first)) }, valueFormat = { "%.0f".format(it) })

            pdf.heading("By game")
            pdf.table(
                listOf("Game", "Skill", "Sessions", "Time", "On goal", "Best streak"),
                r.perGame.map { listOf(it.game.title, it.game.metric.skill, "${it.sessions}", mins(it.totalSec), "${it.focusPct}%", mins(it.bestStreakSec)) },
                listOf(2.2f, 1f, 1f, 1.2f, 1f, 1.2f),
            )

            pdf.heading("Most recent session", keepWith = 150f)
            val last = r.sessions.last()
            pdf.paragraph("${last.game.title} · ${dateTimeFmt.format(Date(last.startedAt))} · ${mins(last.durationSec)} · ${last.focusPercent}% on goal", color = PdfBuilder.DIM)
            pdf.lineChart(last.attentionTimeline, last.endThreshold, color = if (last.game.metric == Metric.CALM) PdfBuilder.CALM else PdfBuilder.BRAND)

            pdf.heading("All sessions")
            pdf.table(
                listOf("Date", "Game", "Time", "On goal", "Avg level", "Streak", "Goal"),
                r.sessions.reversed().map {
                    listOf(
                        dateTimeFmt.format(Date(it.startedAt)), it.game.title, mins(it.durationSec), "${it.focusPercent}%",
                        "${if (it.game.metric == Metric.CALM) it.avgMeditation else it.avgAttention}",
                        mins(it.longestStreakSec), "${it.startThreshold}→${it.endThreshold}",
                    )
                },
                listOf(1.8f, 1.7f, 1f, 0.9f, 0.9f, 0.9f, 0.9f),
            )
        }
        pdf.space(6f)
        pdf.paragraph(
            "“Time on goal” is the share of play time the headset reading stayed at or above the session goal. " +
                "The goal adapts every 30 seconds when adaptive difficulty is on, so a rising goal level also shows progress.",
            size = 8.5f, color = PdfBuilder.DIM,
        )
        return pdf.write(File(dir(context), "FocusQuest_${safe(r.student.name)}_${System.currentTimeMillis() / 1000}.pdf"))
    }

    fun classPdf(context: Context, className: String, rows: List<ClassRow>, from: Long, to: Long): File {
        val range = "${dateFmt.format(Date(from))} – ${dateFmt.format(Date(to))}"
        val pdf = PdfBuilder("Class summary · $className", NOTE)
        pdf.title("$className: Class summary", "${rows.size} students · $range")
        val active = rows.filter { it.sessions > 0 }
        val withFocus = active.mapNotNull { it.focusPct }
        pdf.stats(
            listOf(
                "Students" to "${rows.size}",
                "Active in period" to "${active.size}",
                "Total minutes" to "${rows.sumOf { it.minutes }}",
                "Avg time on goal" to if (withFocus.isEmpty()) "–" else "${withFocus.average().toInt()}%",
            ),
        )
        val flagged = rows.filter { it.flag == "Needs support" || it.flag == "Declining" || it.flag?.startsWith("Inactive") == true }
        pdf.heading("Needs attention")
        if (flagged.isEmpty()) pdf.paragraph("Nobody is flagged in this period.", color = PdfBuilder.DIM)
        else pdf.bullets(flagged.map { "${it.student.name}: ${it.flag}" })

        if (active.isNotEmpty()) {
            pdf.heading("Time on goal by student (%)", keepWith = 140f)
            val chartRows = active.sortedByDescending { it.focusPct ?: 0 }.take(16)
            pdf.barChart(chartRows.map { (it.focusPct ?: 0).toFloat() }, chartRows.map { it.student.name }, color = PdfBuilder.GOOD)
        }

        pdf.heading("All students")
        pdf.table(
            listOf("Student", "Age", "Sessions", "Minutes", "On goal", "Change", "Last session", "Status"),
            rows.map {
                listOf(
                    it.student.name, "${it.student.age}", "${it.sessions}", "${it.minutes}",
                    it.focusPct?.let { p -> "$p%" } ?: "–", signed(it.focusChange),
                    it.lastSession?.let { t -> dateFmt.format(Date(t)) } ?: "Never", it.flag ?: "OK",
                )
            },
            listOf(1.8f, 0.6f, 0.9f, 0.9f, 0.9f, 0.8f, 1.3f, 1.4f),
            rowColor = { i -> if (rows[i].flag == "Needs support" || rows[i].flag == "Declining") PdfBuilder.ALERT_BG else null },
        )
        return pdf.write(File(dir(context), "FocusQuest_Class_${safe(className)}_${System.currentTimeMillis() / 1000}.pdf"))
    }

    fun groupPdf(context: Context, report: GroupReport): File {
        val g = report.group
        val pdf = PdfBuilder("Live session · ${g.className}", NOTE)
        pdf.title(
            "Live session report: ${g.game.title}",
            "${g.className} · ${dateTimeFmt.format(Date(g.startedAt))} · planned ${mins(g.plannedSec)}",
        )
        val records = report.rows.mapNotNull { it.record }
        pdf.stats(
            listOf(
                "Students" to "${report.rows.size}",
                "Results received" to "${report.completed}",
                "Avg time on goal" to (report.avgFocusPct?.let { "$it%" } ?: "–"),
                "Longest streak" to mins(records.maxOfOrNull { it.longestStreakSec } ?: 0),
            ),
        )
        records.maxByOrNull { it.focusPercent }?.let { best ->
            val name = report.rows.first { it.record == best }.student.name
            pdf.paragraph("Highest time on goal: $name (${best.focusPercent}%).", color = PdfBuilder.DIM)
        }
        val missing = report.rows.filter { it.record == null }
        if (missing.isNotEmpty()) pdf.paragraph("No result received from: ${missing.joinToString { it.student.name }} (disconnected or left early).", color = PdfBuilder.WARN)

        pdf.heading("Results")
        pdf.table(
            listOf("Student", "Played", "On goal", "Avg level", "Best streak", "Points", "Goal"),
            report.rows.map { row ->
                val r = row.record
                if (r == null) listOf(row.student.name, "–", "–", "–", "–", "–", "–")
                else listOf(
                    row.student.name, mins(r.durationSec), "${r.focusPercent}%",
                    "${if (r.game.metric == Metric.CALM) r.avgMeditation else r.avgAttention}",
                    mins(r.longestStreakSec), "%,d".format(r.points), "${r.startThreshold}→${r.endThreshold}",
                )
            },
            listOf(1.8f, 1f, 0.9f, 0.9f, 1f, 0.9f, 0.9f),
            rowColor = { i -> val r = report.rows[i].record; if (r == null || r.focusPercent < 30) PdfBuilder.ALERT_BG else null },
        )

        pdf.heading("Each student's ${g.game.metric.label.lowercase()} during the session")
        report.rows.forEach { row ->
            val r = row.record ?: return@forEach
            pdf.ensure(115f)
            pdf.paragraph("${row.student.name} · ${r.focusPercent}% on goal", size = 10f)
            pdf.lineChart(r.attentionTimeline, r.endThreshold, height = 70f, color = if (g.game.metric == Metric.CALM) PdfBuilder.CALM else PdfBuilder.BRAND)
        }
        return pdf.write(File(dir(context), "FocusQuest_Live_${safe(g.className)}_${g.startedAt / 1000}.pdf"))
    }

    /** CSV of every session for the given students (UTF‑8 with BOM so Excel opens it correctly). */
    fun csv(context: Context, name: String, students: List<Profile>, sessions: List<SessionRecord>): File {
        val byId = students.associateBy { it.id }
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val header = listOf(
            "student", "age", "started", "game", "skill", "duration_sec", "on_goal_sec", "on_goal_pct",
            "avg_attention", "avg_calm", "best_streak_sec", "points", "goal_start", "goal_end", "source",
        )
        val lines = sessions.filter { it.profileId in byId }.sortedBy { it.startedAt }.map { s ->
            val st = byId.getValue(s.profileId)
            listOf(
                st.name, "${st.age}", iso.format(Date(s.startedAt)), s.game.title, s.game.metric.skill,
                "${s.durationSec}", "${s.focusedSec}", "${s.focusPercent}", "${s.avgAttention}", "${s.avgMeditation}",
                "${s.longestStreakSec}", "${s.points}", "${s.startThreshold}", "${s.endThreshold}", s.source,
            ).joinToString(",") { esc(it) }
        }
        val file = File(dir(context), "FocusQuest_${safe(name)}_${System.currentTimeMillis() / 1000}.csv")
        file.writeText("﻿" + (listOf(header.joinToString(",")) + lines).joinToString("\r\n"), Charsets.UTF_8)
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        val type = if (file.extension == "csv") "text/csv" else "application/pdf"
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension.replace('_', ' '))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
