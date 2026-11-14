import assert from "node:assert/strict";
import { test } from "node:test";
import type { SessionRecord, Student } from "./db.js";
import { DAY_MS, classRows, focusChange, focusPct, groupReport, sessionsCsv, startOfDay, studentReport } from "./reports.js";

const t0 = Date.UTC(2026, 10, 1);
const st = (id: string, name: string): Student => ({ id, class_id: "c", profile_id: id, name, age: 9, color: 0, token_hash: id, device: null, created_at: 0, last_seen: null });
const rec = (id: string, sid: string, day: number, dur: number, focused: number, g = "ROCKET"): SessionRecord => ({
  id, class_id: "c", student_id: sid, game: g, started_at: t0 + day * DAY_MS + 3_600_000, duration_sec: dur, focused_sec: focused,
  avg_attention: 60, avg_meditation: 50, longest_streak_sec: 20, points: 100, start_threshold: 50, end_threshold: 50,
  source: "Headset", timeline: [], group_id: null, received_at: 0,
});

test("focus % is time weighted", () => {
  assert.equal(focusPct([rec("1", "a", 0, 100, 60), rec("2", "a", 0, 300, 10)]), 18);
  assert.equal(focusPct([]), null);
});

test("focus change compares halves", () => {
  const s = [rec("1", "a", 0, 100, 20), rec("2", "a", 1, 100, 30), rec("3", "a", 2, 100, 60), rec("4", "a", 3, 100, 70)];
  assert.equal(focusChange(s), 40);
  assert.equal(focusChange(s.slice(0, 3)), null);
});

test("start of day honours the viewer's timezone", () => {
  const ist = 330; // UTC+5:30
  const t = Date.UTC(2026, 10, 1, 20, 0); // 1:30 next day in India
  assert.equal(startOfDay(t, ist), Date.UTC(2026, 10, 2) - ist * 60_000);
});

test("student report filters by student and range", () => {
  const all = [rec("1", "a", 0, 300, 150), rec("2", "a", 2, 300, 240, "LANTERN"), rec("3", "r", 1, 300, 300), rec("4", "a", 20, 300, 300)];
  const r = studentReport(st("a", "Asha"), all, t0, t0 + 7 * DAY_MS - 1, 0);
  assert.equal(r.sessions.length, 2);
  assert.equal(r.focusPct, 65);
  assert.equal(r.activeDays, 2);
  assert.equal(r.dailyMinutes.length, 7);
  assert.equal(r.dailyMinutes[0]![1], 5);
  assert.equal(r.avgCalm, 50);
  assert.ok(r.recommendations.length > 0);
});

test("class rows flag inactive and struggling students", () => {
  const now = t0 + 10 * DAY_MS;
  const all = [rec("1", "a", 9, 300, 60), rec("2", "r", 1, 300, 250)];
  const last = new Map([["a", t0 + 9 * DAY_MS], ["r", t0 + DAY_MS]]);
  const rows = classRows([st("r", "Ravi"), st("a", "Asha")], all, last, t0, now, now);
  assert.deepEqual(rows.map((r) => r.student.name), ["Asha", "Ravi"]);
  assert.equal(rows[0]!.flag, "Needs support");
  assert.equal(rows[1]!.flag, "Inactive 7+ days");
});

test("group report matches results and lists missing students", () => {
  const all = [rec("x", "a", 0, 120, 90), rec("y", "r", 0, 120, 30)];
  const g = { id: "g", class_id: "c", game: "ROCKET", planned_sec: 120, started_at: t0, ended_at: t0, student_ids: ["a", "r", "z"], result_ids: ["x", "y"] };
  const r = groupReport(g, [st("a", "Asha"), st("r", "Ravi")], all);
  assert.equal(r.completed, 2);
  assert.equal(r.avgFocusPct, 50);
  assert.equal(r.rows[0]!.student.name, "Asha");
  assert.equal(r.rows.at(-1)!.record, null);
});

test("csv escapes values and starts with a BOM", () => {
  const csv = sessionsCsv([st("a", 'Asha "A", Jr')], [rec("1", "a", 0, 100, 50)], 0);
  assert.ok(csv.startsWith("﻿student,"));
  assert.ok(csv.includes('"Asha ""A"", Jr"'));
});
