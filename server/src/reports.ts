import type { GroupSession, SessionRecord, Student } from "./db.js";
import { game, type Game } from "./games.js";

/** Report figures (pure functions, unit-tested). Mirrors report/ReportData.kt in the app. */

export const DAY_MS = 24 * 60 * 60 * 1000;

export const focusPercent = (s: Pick<SessionRecord, "duration_sec" | "focused_sec">) =>
  s.duration_sec === 0 ? 0 : Math.round((s.focused_sec * 100) / s.duration_sec);

/** Time-weighted share of play time at or above the goal. */
export function focusPct(records: SessionRecord[]): number | null {
  const total = records.reduce((a, r) => a + r.duration_sec, 0);
  return total === 0 ? null : Math.round((records.reduce((a, r) => a + r.focused_sec, 0) * 100) / total);
}

/** Change between the earlier and later half of the sessions (needs 4+). */
export function focusChange(oldestFirst: SessionRecord[]): number | null {
  if (oldestFirst.length < 4) return null;
  const half = Math.floor(oldestFirst.length / 2);
  const early = focusPct(oldestFirst.slice(0, half));
  const late = focusPct(oldestFirst.slice(-half));
  return early == null || late == null ? null : late - early;
}

/** Local midnight for a viewer whose clock is `tzOffsetMin` minutes ahead of UTC. */
export function startOfDay(t: number, tzOffsetMin: number): number {
  const shift = tzOffsetMin * 60_000;
  return Math.floor((t + shift) / DAY_MS) * DAY_MS - shift;
}

export interface GameSummary { game: Game; sessions: number; totalSec: number; focusPct: number; bestStreakSec: number }

export interface StudentReport {
  student: Student;
  from: number;
  to: number;
  sessions: SessionRecord[];
  totalSec: number;
  activeDays: number;
  focusPct: number;
  bestStreakSec: number;
  totalPoints: number;
  avgAttention: number | null;
  avgCalm: number | null;
  focusChange: number | null;
  goalStart: number | null;
  goalEnd: number | null;
  perGame: GameSummary[];
  dailyMinutes: [number, number][];
  recommendations: string[];
}

const avg = (xs: number[]) => (xs.length ? Math.round(xs.reduce((a, b) => a + b, 0) / xs.length) : null);

export function studentReport(student: Student, all: SessionRecord[], from: number, to: number, tz: number): StudentReport {
  const s = all.filter((r) => r.student_id === student.id && r.started_at >= from && r.started_at <= to)
    .sort((a, b) => a.started_at - b.started_at);
  const focusGames = s.filter((r) => game(r.game).metric === "ATTENTION");
  const calmGames = s.filter((r) => game(r.game).metric === "CALM");
  const lastDay = startOfDay(to, tz);
  const firstDay = Math.max(startOfDay(from, tz), lastDay - 13 * DAY_MS);
  const daily: [number, number][] = [];
  for (let d = firstDay; d <= lastDay; d += DAY_MS) {
    daily.push([d, s.filter((r) => startOfDay(r.started_at, tz) === d).reduce((a, r) => a + r.duration_sec, 0) / 60]);
  }
  const groups = new Map<string, SessionRecord[]>();
  for (const r of s) groups.set(r.game, [...(groups.get(r.game) ?? []), r]);
  const perGame = [...groups.entries()].map(([g, list]) => ({
    game: game(g), sessions: list.length, totalSec: list.reduce((a, r) => a + r.duration_sec, 0),
    focusPct: focusPct(list) ?? 0, bestStreakSec: Math.max(...list.map((r) => r.longest_streak_sec)),
  })).sort((a, b) => b.totalSec - a.totalSec);

  const report: StudentReport = {
    student, from, to, sessions: s,
    totalSec: s.reduce((a, r) => a + r.duration_sec, 0),
    activeDays: new Set(s.map((r) => startOfDay(r.started_at, tz))).size,
    focusPct: focusPct(s) ?? 0,
    bestStreakSec: s.length ? Math.max(...s.map((r) => r.longest_streak_sec)) : 0,
    totalPoints: s.reduce((a, r) => a + r.points, 0),
    avgAttention: avg(focusGames.map((r) => r.avg_attention)),
    avgCalm: avg(calmGames.map((r) => r.avg_meditation)),
    focusChange: focusChange(s),
    goalStart: focusGames[0]?.start_threshold ?? null,
    goalEnd: focusGames.at(-1)?.end_threshold ?? null,
    perGame,
    dailyMinutes: daily,
    recommendations: [],
  };
  report.recommendations = recommendations(report);
  return report;
}

/** Practice tips only, never clinical advice. */
export function recommendations(r: StudentReport): string[] {
  const out: string[] = [];
  const n = r.sessions.length;
  if (n === 0) return ["No sessions were recorded in this period."];
  const weeks = Math.max(1, (r.to - r.from) / (7 * DAY_MS));
  if (n / weeks < 3) out.push("Aim for 3–5 short sessions a week. Regular practice helps more than occasional long sessions.");
  if (r.focusPct >= 70 && (r.goalEnd ?? 0) >= (r.goalStart ?? 0) + 5) out.push("Regularly above the goal: try 10-minute sessions or a higher starting goal.");
  if (r.focusPct < 40) out.push("Time on goal is low: use 2–5 minute sessions and goal-based games (Bloom Garden, Bridge Builder) to build success.");
  if (r.focusChange != null && r.focusChange >= 8) out.push(`Time on goal rose by ${r.focusChange} points across the period. Keep the same routine.`);
  if (r.focusChange != null && r.focusChange <= -8) out.push(`Time on goal fell by ${-r.focusChange} points. Check headset fit, time of day and tiredness before sessions.`);
  if (n >= 3 && !r.sessions.some((x) => game(x.game).metric === "CALM")) out.push("Add a calm game (Calm Balloon or Lantern Lake) at the start of a session to practise settling down.");
  if (!out.length) out.push("Steady progress. Keep going!");
  return out;
}

export interface ClassRowReport {
  student: Student; sessions: number; minutes: number; focusPct: number | null; focusChange: number | null;
  lastSession: number | null; flag: string | null;
}

export function classRows(students: Student[], all: SessionRecord[], lastTimes: Map<string, number>, from: number, to: number, now = Date.now()): ClassRowReport[] {
  return students.map((st) => {
    const s = all.filter((r) => r.student_id === st.id && r.started_at >= from && r.started_at <= to).sort((a, b) => a.started_at - b.started_at);
    const last = lastTimes.get(st.id) ?? null;
    const pct = focusPct(s);
    const change = focusChange(s);
    const flag = last == null || now - last > 7 * DAY_MS ? "Inactive 7+ days"
      : pct != null && pct < 40 ? "Needs support"
      : change != null && change <= -8 ? "Declining"
      : change != null && change >= 8 ? "Improving" : null;
    return { student: st, sessions: s.length, minutes: Math.round(s.reduce((a, r) => a + r.duration_sec, 0) / 60), focusPct: pct, focusChange: change, lastSession: last, flag };
  }).sort((a, b) => a.student.name.localeCompare(b.student.name, undefined, { sensitivity: "base" }));
}

export interface GroupReport {
  group: GroupSession;
  rows: { student: Pick<Student, "id" | "name" | "age">; record: SessionRecord | null }[];
  avgFocusPct: number | null;
  completed: number;
}

export function groupReport(g: GroupSession, students: Student[], all: SessionRecord[]): GroupReport {
  const results = all.filter((r) => g.result_ids.includes(r.id) && g.student_ids.includes(r.student_id));
  const rows = g.student_ids.map((id) => ({
    student: students.find((s) => s.id === id) ?? { id, name: "Removed student", age: 0 },
    record: results.find((r) => r.student_id === id) ?? null,
  })).sort((a, b) => (b.record ? focusPercent(b.record) : -1) - (a.record ? focusPercent(a.record) : -1));
  return { group: g, rows, avgFocusPct: focusPct(results), completed: results.length };
}

/** CSV of sessions (UTF‑8 BOM so Excel opens it correctly). */
export function sessionsCsv(students: Student[], sessions: SessionRecord[], tz: number): string {
  const byId = new Map(students.map((s) => [s.id, s]));
  const esc = (v: string) => (/[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  const iso = (t: number) => new Date(t + tz * 60_000).toISOString().slice(0, 16).replace("T", " ");
  const header = ["student", "age", "started", "game", "skill", "duration_sec", "on_goal_sec", "on_goal_pct",
    "avg_attention", "avg_calm", "best_streak_sec", "points", "goal_start", "goal_end", "source"];
  const lines = sessions.filter((s) => byId.has(s.student_id)).sort((a, b) => a.started_at - b.started_at).map((s) => {
    const st = byId.get(s.student_id)!;
    const g = game(s.game);
    return [st.name, String(st.age), iso(s.started_at), g.title, g.skill, String(s.duration_sec), String(s.focused_sec),
      String(focusPercent(s)), String(s.avg_attention), String(s.avg_meditation), String(s.longest_streak_sec),
      String(s.points), String(s.start_threshold), String(s.end_threshold), s.source].map(esc).join(",");
  });
  return "﻿" + [header.join(","), ...lines].join("\r\n");
}
