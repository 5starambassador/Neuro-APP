import fs from "node:fs";
import PDFDocument from "pdfkit";
import { config } from "./config.js";
import { game } from "./games.js";
import { focusPercent, type ClassRowReport, type GroupReport, type StudentReport } from "./reports.js";

/** PDF reports drawn with pdfkit; layout mirrors the Android app's reports. */

const W = 595.28; // A4 in points
const H = 841.89;
const L = 40;
const R = W - 40;
const C = {
  brand: "#6C4BFF", ink: "#1C1E30", dim: "#6E738C", line: "#E1E3EE", panel: "#F2F0FF",
  header: "#E6E2FF", zebra: "#F8F8FC", warn: "#E68C14", alertBg: "#FFECEC", good: "#1EAA78", calm: "#2896DC",
};
const NOTE = "Attention-training summary, not a medical or diagnostic report.";

const hasCustomFont = !!config.pdfFont && fs.existsSync(config.pdfFont);
const REGULAR = hasCustomFont ? "custom" : "Helvetica";
const BOLD = hasCustomFont ? "custom" : "Helvetica-Bold";

export function fmtDate(t: number, tz: number) {
  return new Date(t + tz * 60_000).toLocaleDateString("en-GB", { timeZone: "UTC", day: "numeric", month: "short", year: "numeric" });
}
export function fmtDateTime(t: number, tz: number) {
  return new Date(t + tz * 60_000).toLocaleString("en-GB", { timeZone: "UTC", day: "numeric", month: "short", hour: "numeric", minute: "2-digit", hour12: true });
}
const mins = (sec: number) => (sec >= 60 ? `${Math.floor(sec / 60)}m ${sec % 60}s` : `${sec}s`);
const signed = (v: number | null) => (v == null ? "–" : v > 0 ? `+${v}` : `${v}`);

class Pdf {
  private doc: PDFKit.PDFDocument;
  private chunks: Buffer[] = [];
  y = 0;

  constructor(private docTitle: string) {
    this.doc = new PDFDocument({ size: "A4", margin: 0, bufferPages: true, info: { Title: docTitle, Author: "FocusQuest" } });
    if (hasCustomFont) this.doc.registerFont("custom", config.pdfFont!);
    this.doc.on("data", (c: Buffer) => this.chunks.push(c));
    this.newPage(true);
  }

  private newPage(first = false) {
    if (!first) this.doc.addPage({ size: "A4", margin: 0 });
    const d = this.doc;
    d.rect(0, 0, W, 6).fill(C.brand);
    d.font(BOLD).fontSize(11).fillColor(C.brand).text("FocusQuest", L, 18, { lineBreak: false });
    d.font(REGULAR).fontSize(9).fillColor(C.dim);
    d.text(this.docTitle, R - d.widthOfString(this.docTitle), 19, { lineBreak: false });
    this.y = 50;
  }

  ensure(h: number) { if (this.y + h > H - 50) this.newPage(); }
  space(h: number) { this.y += h; }

  title(t: string, sub?: string) {
    this.ensure(60);
    this.doc.font(BOLD).fontSize(20).fillColor(C.ink).text(t, L, this.y, { width: R - L });
    this.y = this.doc.y + 4;
    if (sub) { this.doc.font(REGULAR).fontSize(11).fillColor(C.dim).text(sub, L, this.y, { width: R - L }); this.y = this.doc.y + 4; }
    this.y += 6;
  }

  heading(t: string, keepWith = 0) {
    this.ensure(40 + keepWith);
    this.y += 8;
    this.doc.font(BOLD).fontSize(13).fillColor(C.ink).text(t, L, this.y, { lineBreak: false });
    this.y += 20;
  }

  paragraph(t: string, size = 10, color = C.ink) {
    this.doc.font(REGULAR).fontSize(size);
    const h = this.doc.heightOfString(t, { width: R - L });
    this.ensure(h + 4);
    this.doc.fillColor(color).text(t, L, this.y, { width: R - L });
    this.y += h + 4;
  }

  bullets(items: string[]) {
    for (const item of items) {
      this.doc.font(REGULAR).fontSize(10);
      const h = this.doc.heightOfString(item, { width: R - L - 14 });
      this.ensure(h + 4);
      this.doc.font(BOLD).fillColor(C.ink).text("•", L + 2, this.y, { lineBreak: false });
      this.doc.font(REGULAR).text(item, L + 14, this.y, { width: R - L - 14 });
      this.y += h + 4;
    }
  }

  stats(items: [string, string][], perRow = 4) {
    const gap = 8;
    const bw = (R - L - gap * (perRow - 1)) / perRow;
    for (let i = 0; i < items.length; i += perRow) {
      this.ensure(54);
      items.slice(i, i + perRow).forEach(([label, value], j) => {
        const x = L + j * (bw + gap);
        this.doc.roundedRect(x, this.y, bw, 46, 6).fill(C.panel);
        this.doc.font(BOLD).fontSize(14).fillColor(C.ink).text(value, x + 8, this.y + 9, { width: bw - 12, lineBreak: false, ellipsis: true });
        this.doc.font(REGULAR).fontSize(9).fillColor(C.dim).text(label, x + 8, this.y + 29, { width: bw - 12, lineBreak: false, ellipsis: true });
      });
      this.y += 54;
    }
  }

  table(headers: string[], rows: string[][], weights: number[], rowColor: (i: number) => string | null = () => null) {
    const total = weights.reduce((a, b) => a + b, 0);
    const widths = weights.map((w) => (w / total) * (R - L));
    const draw = (cells: string[], bold: boolean, bg: string | null) => {
      this.ensure(18);
      if (bg) this.doc.rect(L, this.y, R - L, 17).fill(bg);
      let x = L;
      this.doc.font(bold ? BOLD : REGULAR).fontSize(9).fillColor(C.ink);
      cells.forEach((c, i) => {
        this.doc.text(c, x + 3, this.y + 4, { width: widths[i]! - 6, lineBreak: false, ellipsis: true });
        x += widths[i]!;
      });
      this.y += 17;
    };
    draw(headers, true, C.header);
    rows.forEach((r, i) => {
      if (this.y + 18 > H - 50) { this.newPage(); draw(headers, true, C.header); }
      draw(r, false, rowColor(i) ?? (i % 2 ? C.zebra : null));
    });
    this.y += 6;
  }

  lineChart(values: number[], threshold: number | null, height = 120, color = C.brand) {
    this.ensure(height + 18);
    this.y += 8;
    const top = this.y;
    const bottom = top + height;
    const x0 = L + 22;
    const d = this.doc;
    d.font(REGULAR).fontSize(8).fillColor(C.dim);
    for (const g of [0, 25, 50, 75, 100]) {
      const gy = bottom - (height * g) / 100;
      d.moveTo(x0, gy).lineTo(R, gy).lineWidth(0.6).strokeColor(C.line).stroke();
      d.text(String(g), L, gy - 3, { lineBreak: false });
    }
    if (threshold != null) {
      const ty = bottom - (height * threshold) / 100;
      d.moveTo(x0, ty).lineTo(R, ty).lineWidth(1).dash(5, { space: 4 }).strokeColor(C.warn).stroke().undash();
    }
    if (values.length >= 2) {
      const step = (R - x0) / (values.length - 1);
      values.forEach((v, i) => {
        const px = x0 + i * step;
        const py = bottom - (height * Math.max(0, Math.min(100, v))) / 100;
        if (i === 0) d.moveTo(px, py); else d.lineTo(px, py);
      });
      d.lineWidth(2).strokeColor(color).lineJoin("round").stroke();
    } else if (values.length === 1) {
      d.circle(x0 + (R - x0) / 2, bottom - (height * values[0]!) / 100, 3).fill(color);
    }
    this.y = bottom + 10;
  }

  barChart(values: number[], labels: string[], height = 100, color = C.brand) {
    this.ensure(height + 44);
    this.y += 14;
    const max = Math.max(1, ...values);
    const slot = (R - L) / Math.max(1, values.length);
    const bw = slot * 0.6;
    const d = this.doc;
    values.forEach((v, i) => {
      const x = L + i * slot + (slot - bw) / 2;
      const bh = (height * v) / max;
      d.rect(x, this.y, bw, height).fill(C.panel);
      if (bh > 0) d.rect(x, this.y + height - bh, bw, bh).fill(color);
      d.font(REGULAR).fontSize(8).fillColor(C.dim);
      if (v > 0) { const s = String(Math.round(v)); d.text(s, x + bw / 2 - d.widthOfString(s) / 2, this.y + height - bh - 11, { lineBreak: false }); }
      d.text(labels[i] ?? "", x - (slot - bw) / 2, this.y + height + 4, { width: slot, align: "center", lineBreak: false, ellipsis: true });
    });
    this.y += height + 22;
  }

  async finish(): Promise<Buffer> {
    const range = this.doc.bufferedPageRange();
    for (let i = range.start; i < range.start + range.count; i++) {
      this.doc.switchToPage(i);
      this.doc.moveTo(L, H - 36).lineTo(R, H - 36).lineWidth(0.6).strokeColor(C.line).stroke();
      this.doc.font(REGULAR).fontSize(9).fillColor(C.dim).text(NOTE, L, H - 28, { lineBreak: false });
      const pn = `Page ${i + 1} of ${range.count}`;
      this.doc.text(pn, R - this.doc.widthOfString(pn), H - 28, { lineBreak: false });
    }
    const done = new Promise<Buffer>((resolve) => this.doc.on("end", () => resolve(Buffer.concat(this.chunks))));
    this.doc.end();
    return done;
  }
}

export function studentPdf(r: StudentReport, className: string, tz: number): Promise<Buffer> {
  const pdf = new Pdf(`Student report · ${r.student.name}`);
  pdf.title(`${r.student.name}: Focus training report`, `${className} · age ${r.student.age} · ${fmtDate(r.from, tz)} – ${fmtDate(r.to, tz)}`);
  pdf.stats([
    ["Sessions", String(r.sessions.length)], ["Training time", mins(r.totalSec)], ["Active days", String(r.activeDays)],
    ["Time on goal", `${r.focusPct}%`], ["Best streak", mins(r.bestStreakSec)],
    ["Change in period", r.focusChange == null ? "–" : `${signed(r.focusChange)} pts`],
    ["Goal level", r.goalStart == null ? "–" : `${r.goalStart} to ${r.goalEnd}`], ["Points", r.totalPoints.toLocaleString("en")],
  ]);
  const avgs = [r.avgAttention != null ? `Average attention in focus games: ${r.avgAttention}/100.` : null,
    r.avgCalm != null ? `Average calm in calm games: ${r.avgCalm}/100.` : null].filter(Boolean).join("  ");
  if (avgs) pdf.paragraph(avgs, 10, C.dim);
  pdf.heading("Coaching suggestions");
  pdf.bullets(r.recommendations);
  if (r.sessions.length) {
    pdf.heading("Time on goal per session (%)", 130);
    pdf.lineChart(r.sessions.map(focusPercent), null, 120, C.good);
    pdf.heading("Minutes trained per day", 140);
    pdf.barChart(r.dailyMinutes.map(([, m]) => m), r.dailyMinutes.map(([d]) => {
      const dt = new Date(d + tz * 60_000); return `${dt.getUTCDate()}/${dt.getUTCMonth() + 1}`;
    }));
    pdf.heading("By game");
    pdf.table(["Game", "Skill", "Sessions", "Time", "On goal", "Best streak"],
      r.perGame.map((g) => [g.game.title, g.game.skill, String(g.sessions), mins(g.totalSec), `${g.focusPct}%`, mins(g.bestStreakSec)]),
      [2.2, 1, 1, 1.2, 1, 1.2]);
    const last = r.sessions.at(-1)!;
    const lg = game(last.game);
    pdf.heading("Most recent session", 150);
    pdf.paragraph(`${lg.title} · ${fmtDateTime(last.started_at, tz)} · ${mins(last.duration_sec)} · ${focusPercent(last)}% on goal`, 10, C.dim);
    pdf.lineChart(last.timeline, last.end_threshold, 120, lg.metric === "CALM" ? C.calm : C.brand);
    pdf.heading("All sessions");
    pdf.table(["Date", "Game", "Time", "On goal", "Avg level", "Streak", "Goal"],
      [...r.sessions].reverse().map((s) => {
        const g = game(s.game);
        return [fmtDateTime(s.started_at, tz), g.title, mins(s.duration_sec), `${focusPercent(s)}%`,
          String(g.metric === "CALM" ? s.avg_meditation : s.avg_attention), mins(s.longest_streak_sec), `${s.start_threshold}-${s.end_threshold}`];
      }), [1.8, 1.7, 1, 0.9, 0.9, 0.9, 0.9]);
  }
  pdf.space(6);
  pdf.paragraph("“Time on goal” is the share of play time the headset reading stayed at or above the session goal. The goal adapts every 30 seconds when adaptive difficulty is on, so a rising goal level also shows progress.", 8.5, C.dim);
  return pdf.finish();
}

export function classPdf(className: string, rows: ClassRowReport[], from: number, to: number, tz: number): Promise<Buffer> {
  const pdf = new Pdf(`Class summary · ${className}`);
  pdf.title(`${className}: Class summary`, `${rows.length} students · ${fmtDate(from, tz)} – ${fmtDate(to, tz)}`);
  const active = rows.filter((r) => r.sessions > 0);
  const pcts = active.map((r) => r.focusPct).filter((p): p is number => p != null);
  pdf.stats([
    ["Students", String(rows.length)], ["Active in period", String(active.length)],
    ["Total minutes", String(rows.reduce((a, r) => a + r.minutes, 0))],
    ["Avg time on goal", pcts.length ? `${Math.round(pcts.reduce((a, b) => a + b, 0) / pcts.length)}%` : "–"],
  ]);
  const flagged = rows.filter((r) => r.flag === "Needs support" || r.flag === "Declining" || r.flag?.startsWith("Inactive"));
  pdf.heading("Needs attention");
  if (flagged.length) pdf.bullets(flagged.map((r) => `${r.student.name}: ${r.flag}`));
  else pdf.paragraph("Nobody is flagged in this period.", 10, C.dim);
  if (active.length) {
    pdf.heading("Time on goal by student (%)", 140);
    const top = [...active].sort((a, b) => (b.focusPct ?? 0) - (a.focusPct ?? 0)).slice(0, 16);
    pdf.barChart(top.map((r) => r.focusPct ?? 0), top.map((r) => r.student.name), 100, C.good);
  }
  pdf.heading("All students");
  pdf.table(["Student", "Age", "Sessions", "Minutes", "On goal", "Change", "Last session", "Status"],
    rows.map((r) => [r.student.name, String(r.student.age), String(r.sessions), String(r.minutes),
      r.focusPct == null ? "–" : `${r.focusPct}%`, signed(r.focusChange), r.lastSession ? fmtDate(r.lastSession, tz) : "Never", r.flag ?? "OK"]),
    [1.8, 0.6, 0.9, 0.9, 0.9, 0.8, 1.3, 1.4],
    (i) => (rows[i]!.flag === "Needs support" || rows[i]!.flag === "Declining" ? C.alertBg : null));
  return pdf.finish();
}

export function groupPdf(report: GroupReport, className: string, tz: number): Promise<Buffer> {
  const g = report.group;
  const gm = game(g.game);
  const pdf = new Pdf(`Live session · ${className}`);
  pdf.title(`Live session report: ${gm.title}`, `${className} · ${fmtDateTime(g.started_at, tz)} · planned ${mins(g.planned_sec)}`);
  const records = report.rows.map((r) => r.record).filter((r): r is NonNullable<typeof r> => r != null);
  pdf.stats([
    ["Students", String(report.rows.length)], ["Results received", String(report.completed)],
    ["Avg time on goal", report.avgFocusPct == null ? "–" : `${report.avgFocusPct}%`],
    ["Longest streak", mins(Math.max(0, ...records.map((r) => r.longest_streak_sec)))],
  ]);
  const best = report.rows.find((r) => r.record);
  if (best?.record) pdf.paragraph(`Highest time on goal: ${best.student.name} (${focusPercent(best.record)}%).`, 10, C.dim);
  const missing = report.rows.filter((r) => !r.record);
  if (missing.length) pdf.paragraph(`No result received from: ${missing.map((m) => m.student.name).join(", ")} (disconnected or left early).`, 10, C.warn);
  pdf.heading("Results");
  pdf.table(["Student", "Played", "On goal", "Avg level", "Best streak", "Points", "Goal"],
    report.rows.map(({ student, record: r }) => r
      ? [student.name, mins(r.duration_sec), `${focusPercent(r)}%`, String(gm.metric === "CALM" ? r.avg_meditation : r.avg_attention),
        mins(r.longest_streak_sec), r.points.toLocaleString("en"), `${r.start_threshold}-${r.end_threshold}`]
      : [student.name, "–", "–", "–", "–", "–", "–"]),
    [1.8, 1, 0.9, 0.9, 1, 0.9, 0.9],
    (i) => { const r = report.rows[i]!.record; return !r || focusPercent(r) < 30 ? C.alertBg : null; });
  pdf.heading(`Each student's ${gm.metric === "CALM" ? "calm" : "attention"} during the session`);
  for (const { student, record: r } of report.rows) {
    if (!r) continue;
    pdf.ensure(115);
    pdf.paragraph(`${student.name} · ${focusPercent(r)}% on goal`);
    pdf.lineChart(r.timeline, r.end_threshold, 70, gm.metric === "CALM" ? C.calm : C.brand);
  }
  return pdf.finish();
}
