package com.focusquest.app.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Small layout helper over Android's [PdfDocument]: A4 pages, a top-down cursor,
 * automatic page breaks, and the building blocks the reports need.
 */
class PdfBuilder(private val docTitle: String, private val footerNote: String) {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var pageNo = 0
    var y = 0f
        private set

    val left = 40f
    val right = PAGE_W - 40f
    val width get() = right - left

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 10f }
    private val bold = Paint(text).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val dim = Paint(text).apply { color = DIM; textSize = 9f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }

    init { newPage() }

    private fun newPage() {
        page?.let { finishPage(it) }
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create())
        page = p
        canvas = p.canvas
        // Brand bar
        fill.color = BRAND
        canvas.drawRect(0f, 0f, PAGE_W, 6f, fill)
        canvas.drawText("FocusQuest", left, 26f, bold.withSize(11f, BRAND))
        canvas.drawText(docTitle, right - dim.measureText(docTitle), 26f, dim)
        y = 50f
    }

    private fun finishPage(p: PdfDocument.Page) {
        val c = p.canvas
        c.drawLine(left, PAGE_H - 36f, right, PAGE_H - 36f, stroke.apply { color = LINE })
        c.drawText(footerNote, left, PAGE_H - 22f, dim)
        val pn = "Page $pageNo"
        c.drawText(pn, right - dim.measureText(pn), PAGE_H - 22f, dim)
        doc.finishPage(p)
    }

    /** Starts a new page if fewer than [needed] points remain. */
    fun ensure(needed: Float) {
        if (y + needed > PAGE_H - 50f) newPage()
    }

    fun space(h: Float) { y += h }

    fun title(t: String, subtitle: String?) {
        ensure(60f)
        canvas.drawText(t, left, y + 18f, bold.withSize(20f, INK))
        y += 26f
        if (subtitle != null) {
            canvas.drawText(subtitle, left, y + 10f, text.withSize(11f, DIM))
            y += 18f
        }
        y += 6f
    }

    /** @param keepWith height of the content that must stay on the same page as this heading. */
    fun heading(t: String, keepWith: Float = 0f) {
        ensure(40f + keepWith)
        y += 8f
        canvas.drawText(t, left, y + 12f, bold.withSize(13f, INK))
        y += 20f
    }

    fun paragraph(t: String, size: Float = 10f, color: Int = INK) {
        val p = text.withSize(size, color)
        wrap(t, p, width).forEach { line ->
            ensure(size + 4f)
            canvas.drawText(line, left, y + size, p)
            y += size + 4f
        }
    }

    fun bullets(items: List<String>) {
        items.forEach { item ->
            val lines = wrap(item, text, width - 14f)
            lines.forEachIndexed { i, line ->
                ensure(14f)
                if (i == 0) canvas.drawText("•", left + 2f, y + 10f, bold)
                canvas.drawText(line, left + 14f, y + 10f, text)
                y += 14f
            }
            y += 2f
        }
    }

    /** A row of stat boxes. */
    fun stats(items: List<Pair<String, String>>, perRow: Int = 4) {
        val gap = 8f
        val boxW = (width - gap * (perRow - 1)) / perRow
        items.chunked(perRow).forEach { row ->
            ensure(52f)
            row.forEachIndexed { i, (label, value) ->
                val x = left + i * (boxW + gap)
                fill.color = PANEL
                canvas.drawRoundRect(RectF(x, y, x + boxW, y + 46f), 6f, 6f, fill)
                canvas.drawText(value, x + 8f, y + 22f, bold.withSize(14f, INK))
                canvas.drawText(label, x + 8f, y + 37f, dim)
            }
            y += 54f
        }
    }

    /**
     * Simple table. [weights] are relative column widths; [rowColor] optionally tints a row
     * (e.g. students that need attention).
     */
    fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float>, rowColor: (Int) -> Int? = { null }) {
        val total = weights.sum()
        val widths = weights.map { it / total * width }
        fun drawRow(cells: List<String>, paint: Paint, bg: Int?) {
            ensure(18f)
            if (bg != null) { fill.color = bg; canvas.drawRect(left, y, right, y + 17f, fill) }
            var x = left
            cells.forEachIndexed { i, c ->
                canvas.drawText(ellipsize(c, paint, widths[i] - 6f), x + 3f, y + 12f, paint)
                x += widths[i]
            }
            y += 17f
        }
        drawRow(headers, bold.withSize(9f, INK), HEADER_BG)
        rows.forEachIndexed { i, r ->
            if (y + 18f > PAGE_H - 50f) { newPage(); drawRow(headers, bold.withSize(9f, INK), HEADER_BG) }
            drawRow(r, text.withSize(9f, INK), rowColor(i) ?: if (i % 2 == 1) ZEBRA else null)
        }
        y += 6f
    }

    fun lineChart(values: List<Int>, threshold: Int?, height: Float = 120f, color: Int = BRAND) {
        ensure(height + 18f)
        y += 8f // room for the top axis label under any caption above
        val top = y
        val bottom = y + height
        stroke.color = LINE
        for (g in listOf(0, 25, 50, 75, 100)) {
            val gy = bottom - height * g / 100f
            canvas.drawLine(left + 22f, gy, right, gy, stroke)
            canvas.drawText("$g", left, gy + 3f, dim)
        }
        if (threshold != null) {
            val ty = bottom - height * threshold / 100f
            val dash = Paint(stroke).apply { this.color = WARN; strokeWidth = 1.2f; pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) }
            canvas.drawLine(left + 22f, ty, right, ty, dash)
        }
        if (values.size >= 2) {
            val step = (width - 22f) / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val px = left + 22f + i * step
                val py = bottom - height * v.coerceIn(0, 100) / 100f
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, Paint(stroke).apply { this.color = color; strokeWidth = 2f })
        } else if (values.size == 1) {
            fill.color = color
            canvas.drawCircle(left + 22f + (width - 22f) / 2, bottom - height * values[0] / 100f, 3f, fill)
        }
        y = top + height + 10f
    }

    fun barChart(values: List<Float>, labels: List<String>, height: Float = 100f, color: Int = BRAND, valueFormat: (Float) -> String = { "%.0f".format(it) }) {
        ensure(height + 44f)
        y += 14f // room for the value label above a full-height bar
        val maxV = maxOf(values.maxOrNull() ?: 0f, 1f)
        val slot = width / values.size.coerceAtLeast(1)
        val barW = slot * 0.6f
        values.forEachIndexed { i, v ->
            val x = left + i * slot + (slot - barW) / 2
            val bh = height * v / maxV
            fill.color = PANEL
            canvas.drawRect(x, y, x + barW, y + height, fill)
            fill.color = color
            canvas.drawRect(x, y + height - bh, x + barW, y + height, fill)
            if (v > 0f) {
                val s = valueFormat(v)
                canvas.drawText(s, x + barW / 2 - dim.measureText(s) / 2, y + height - bh - 3f, dim)
            }
            val l = ellipsize(labels.getOrElse(i) { "" }, dim, slot - 2f)
            canvas.drawText(l, x + barW / 2 - dim.measureText(l) / 2, y + height + 12f, dim)
        }
        y += height + 22f
    }

    fun write(file: File): File {
        page?.let { finishPage(it) }
        page = null
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun Paint.withSize(size: Float, color: Int) = Paint(this).apply { textSize = size; this.color = color }

    private fun wrap(t: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        t.split("\n").forEach { para ->
            var line = ""
            para.split(" ").forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= maxW) line = candidate else { if (line.isNotEmpty()) out += line; line = word }
            }
            out += line
        }
        return out
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    companion object {
        const val PAGE_W = 595f // A4 at 72 dpi
        const val PAGE_H = 842f
        val BRAND = Color.rgb(108, 75, 255)
        val INK = Color.rgb(28, 30, 48)
        val DIM = Color.rgb(110, 115, 140)
        val LINE = Color.rgb(225, 227, 238)
        val PANEL = Color.rgb(242, 240, 255)
        val HEADER_BG = Color.rgb(230, 226, 255)
        val ZEBRA = Color.rgb(248, 248, 252)
        val WARN = Color.rgb(230, 140, 20)
        val ALERT_BG = Color.rgb(255, 236, 236)
        val GOOD = Color.rgb(30, 170, 120)
        val CALM = Color.rgb(40, 150, 220)
    }
}
