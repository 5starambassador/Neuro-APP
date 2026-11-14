import type { WebSocket } from "ws";
import type { Db, SessionRecord, Student } from "./db.js";
import { AVATAR_COLORS, game, type Game } from "./games.js";
import { newId } from "./auth.js";

/** Live state of one student, as shown on the dashboard. */
interface LiveStudent {
  id: string;
  name: string;
  age: number;
  color: number;
  connected: boolean;
  everConnected: boolean;
  lastSeen: number;
  attention: number;
  meditation: number;
  signal: number;
  signalOk: boolean;
  source: string;
  session: LiveSession | null;
  attentionHistory: number[];
  calmHistory: number[];
  poorSignalSince: number;
}

interface LiveSession {
  game: Game;
  elapsed: number;
  duration: number;
  focusPct: number;
  points: number;
  streak: number;
  threshold: number;
  focused: boolean;
  paused: boolean;
  progress: string;
  groupId: string | null;
}

interface GroupRun {
  id: string;
  game: Game;
  plannedSec: number;
  startedAt: number;
  endedAt: number;
  studentIds: string[];
  results: Map<string, string>; // studentId -> session id
}

interface ClassLive {
  id: string;
  name: string;
  students: Map<string, LiveStudent>;
  sockets: Map<string, WebSocket>;
  group: GroupRun | null;
  subscribers: Set<(json: string) => void>;
}

const STALE_MS = 5_000;
const HISTORY = 90;

const clamp = (n: unknown, lo: number, hi: number, dflt = 0) => {
  const v = typeof n === "number" && Number.isFinite(n) ? n : dflt;
  return Math.min(hi, Math.max(lo, Math.round(v)));
};
const str = (v: unknown, max: number, dflt = "") => (typeof v === "string" ? v.slice(0, max) : dflt);

export function alertsFor(s: LiveStudent, now: number): string[] {
  const out: string[] = [];
  if (!s.connected) {
    if (s.everConnected) out.push("Disconnected");
    return out;
  }
  if (s.source === "None") out.push("No headset");
  else if (s.poorSignalSince && now - s.poorSignalSince > 10_000) out.push("Poor signal");
  const ses = s.session;
  if (ses && !ses.paused && ses.elapsed >= 60 && ses.focusPct < 30) out.push(ses.game.metric === "CALM" ? "Low calm" : "Low focus");
  return out;
}

/**
 * Keeps every class's live state in memory: connected student sockets, the latest readings,
 * running group sessions and the dashboards watching them. Durable data goes to the Db.
 */
export class LiveHub {
  private classes = new Map<string, ClassLive>();
  private timer: NodeJS.Timeout;

  constructor(private db: Db) {
    this.timer = setInterval(() => this.tick(), 1000);
    this.timer.unref();
  }

  close() {
    clearInterval(this.timer);
    for (const c of this.classes.values()) for (const ws of c.sockets.values()) ws.close(1001, "Server shutting down");
  }

  private load(classId: string): ClassLive | undefined {
    let c = this.classes.get(classId);
    if (c) return c;
    const row = this.db.classById(classId);
    if (!row) return undefined;
    c = { id: row.id, name: row.name, students: new Map(), sockets: new Map(), group: null, subscribers: new Set() };
    for (const s of this.db.studentsInClass(classId)) c.students.set(s.id, blank(s));
    this.classes.set(classId, c);
    return c;
  }

  /** Called after class edits so live names/rosters stay in sync. */
  refreshClass(classId: string) {
    const c = this.classes.get(classId);
    const row = this.db.classById(classId);
    if (!c) return;
    if (!row) { this.dropClass(classId); return; }
    c.name = row.name;
  }

  dropClass(classId: string) {
    const c = this.classes.get(classId);
    if (!c) return;
    for (const ws of c.sockets.values()) ws.close(4004, "Class removed");
    this.classes.delete(classId);
  }

  removeStudent(classId: string, studentId: string) {
    const c = this.classes.get(classId);
    if (!c) return;
    c.sockets.get(studentId)?.close(4003, "Removed from class");
    c.sockets.delete(studentId);
    c.students.delete(studentId);
  }

  // ---- student connections -----------------------------------------------------------

  attach(student: Student, ws: WebSocket) {
    const c = this.load(student.class_id);
    if (!c) { ws.close(4004, "Class not found"); return; }
    const old = c.sockets.get(student.id);
    if (old && old !== ws) old.close(4000, "Replaced by a newer connection");
    c.sockets.set(student.id, ws);
    const prev = c.students.get(student.id) ?? blank(student);
    c.students.set(student.id, { ...prev, name: student.name, age: student.age, color: student.color, connected: true, everConnected: true, lastSeen: Date.now() });
    this.db.touchStudent(student.id, Date.now());
    send(ws, { t: "welcome", className: c.name });

    ws.on("message", (data) => {
      const text = data.toString();
      if (text.length > 64 * 1024) return;
      let m: Record<string, unknown>;
      try { m = JSON.parse(text) as Record<string, unknown>; } catch { return; }
      if (m.t === "state") this.onState(c, student.id, m);
      else if (m.t === "result") this.onResult(c, student, ws, m);
    });
    ws.on("close", () => {
      if (c.sockets.get(student.id) === ws) {
        c.sockets.delete(student.id);
        const s = c.students.get(student.id);
        if (s) s.connected = false;
        this.db.touchStudent(student.id, Date.now());
      }
    });
  }

  private onState(c: ClassLive, id: string, m: Record<string, unknown>) {
    const s = c.students.get(id);
    if (!s) return;
    const now = Date.now();
    const ok = m.ok === true;
    s.connected = true;
    s.lastSeen = now;
    s.attention = clamp(m.att, 0, 100);
    s.meditation = clamp(m.med, 0, 100);
    s.signal = clamp(m.sig, 0, 100);
    s.signalOk = ok;
    s.source = str(m.src, 20, "None");
    s.attentionHistory = [...s.attentionHistory, s.attention].slice(-HISTORY);
    s.calmHistory = [...s.calmHistory, s.meditation].slice(-HISTORY);
    s.poorSignalSince = ok ? 0 : s.poorSignalSince || now;
    const ses = m.session as Record<string, unknown> | undefined | null;
    s.session = ses && typeof ses === "object" ? {
      game: game(str(ses.game, 20)),
      elapsed: clamp(ses.elapsed, 0, 86_400), duration: clamp(ses.duration, 0, 86_400),
      focusPct: clamp(ses.focusPct, 0, 100), points: clamp(ses.points, 0, 10_000_000),
      streak: clamp(ses.streak, 0, 86_400), threshold: clamp(ses.threshold, 0, 100, 50),
      focused: ses.focused === true, paused: ses.paused === true,
      progress: str(ses.progress, 80), groupId: typeof ses.group === "string" ? ses.group.slice(0, 64) : null,
    } : null;
  }

  private onResult(c: ClassLive, student: Student, ws: WebSocket, m: Record<string, unknown>) {
    const r = m.record as Record<string, unknown> | undefined;
    if (!r || typeof r.id !== "string") return;
    const groupId = typeof m.group === "string" ? m.group.slice(0, 64) : null;
    const timeline = Array.isArray(r.timeline) ? r.timeline.slice(0, 600).map((v) => clamp(v, 0, 100)) : [];
    const record: SessionRecord = {
      id: r.id.slice(0, 64),
      class_id: c.id,
      student_id: student.id,
      game: game(str(r.game, 20)).id,
      started_at: clamp(r.startedAt, 0, 8.64e15, Date.now()),
      duration_sec: clamp(r.durationSec, 0, 86_400),
      focused_sec: clamp(r.focusedSec, 0, 86_400),
      avg_attention: clamp(r.avgAttention, 0, 100),
      avg_meditation: clamp(r.avgMeditation, 0, 100),
      longest_streak_sec: clamp(r.longestStreakSec, 0, 86_400),
      points: clamp(r.points, 0, 10_000_000),
      start_threshold: clamp(r.startThreshold, 0, 100, 50),
      end_threshold: clamp(r.endThreshold, 0, 100, 50),
      source: str(r.source, 40, "Headset"),
      timeline,
      group_id: groupId,
      received_at: Date.now(),
    };
    if (record.focused_sec > record.duration_sec) record.focused_sec = record.duration_sec;
    this.db.insertSession(record);
    send(ws, { t: "ack", id: record.id });
    if (c.group && c.group.id === groupId) c.group.results.set(student.id, record.id);
  }

  // ---- trainer actions ---------------------------------------------------------------

  startGroup(classId: string, gameId: string, durationSec: number): { ok: true } | { ok: false; error: string } {
    const c = this.load(classId);
    if (!c) return { ok: false, error: "Class not found" };
    if (c.group) return { ok: false, error: "A group session is already running" };
    const ids = [...c.students.values()].filter((s) => s.connected).map((s) => s.id);
    if (!ids.length) return { ok: false, error: "No students are connected" };
    const run: GroupRun = { id: newId(), game: game(gameId), plannedSec: durationSec, startedAt: Date.now(), endedAt: 0, studentIds: ids, results: new Map() };
    c.group = run;
    this.broadcast(c, ids, { t: "start", group: run.id, game: run.game.id, duration: durationSec });
    return { ok: true };
  }

  endGroup(classId: string) {
    const c = this.load(classId);
    if (!c?.group) return false;
    c.group.endedAt = Date.now();
    this.broadcast(c, c.group.studentIds, { t: "stop", group: c.group.id });
    return true;
  }

  closeGroup(classId: string): string | null {
    const c = this.load(classId);
    const g = c?.group;
    if (!c || !g) return null;
    this.db.insertGroup({
      id: g.id, class_id: c.id, game: g.game.id, planned_sec: g.plannedSec, started_at: g.startedAt,
      ended_at: g.endedAt || Date.now(), student_ids: g.studentIds, result_ids: [...g.results.values()],
    });
    c.group = null;
    return g.id;
  }

  cheer(classId: string, studentId: string | null, text: string) {
    const c = this.load(classId);
    if (!c) return;
    const ids = studentId ? [studentId] : [...c.sockets.keys()];
    this.broadcast(c, ids, { t: "cheer", text });
  }

  private broadcast(c: ClassLive, ids: string[], msg: object) {
    for (const id of ids) { const ws = c.sockets.get(id); if (ws) send(ws, msg); }
  }

  // ---- dashboards --------------------------------------------------------------------

  subscribe(classId: string, fn: (json: string) => void): () => void {
    const c = this.load(classId);
    if (!c) return () => {};
    c.subscribers.add(fn);
    fn(JSON.stringify(this.state(classId)));
    return () => c.subscribers.delete(fn);
  }

  state(classId: string) {
    const c = this.load(classId);
    if (!c) return null;
    const now = Date.now();
    const row = this.db.classById(classId);
    const g = c.group;
    return {
      running: true,
      classId: c.id,
      className: c.name,
      code: row?.join_code ?? "",
      students: [...c.students.values()].sort((a, b) => a.name.localeCompare(b.name)).map((s) => ({
        id: s.id, name: s.name, age: s.age, color: AVATAR_COLORS[((s.color % AVATAR_COLORS.length) + AVATAR_COLORS.length) % AVATAR_COLORS.length],
        connected: s.connected, joined: s.everConnected, attention: s.attention, meditation: s.meditation,
        signal: s.signal, signalOk: s.signalOk, source: s.source, alerts: alertsFor(s, now),
        attentionHistory: s.attentionHistory, calmHistory: s.calmHistory,
        session: s.session && {
          game: s.session.game.id, gameTitle: s.session.game.title, metric: s.session.game.metric,
          elapsed: s.session.elapsed, duration: s.session.duration, focusPct: s.session.focusPct,
          points: s.session.points, streak: s.session.streak, threshold: s.session.threshold,
          focused: s.session.focused, paused: s.session.paused, progress: s.session.progress,
        },
      })),
      group: g && {
        id: g.id, game: g.game.id, gameTitle: g.game.title, plannedSec: g.plannedSec,
        elapsedSec: Math.floor((now - g.startedAt) / 1000), results: g.results.size, total: g.studentIds.length,
        ended: g.endedAt !== 0, allResultsIn: g.studentIds.length > 0 && g.studentIds.every((id) => g.results.has(id)),
      },
    };
  }

  private tick() {
    const now = Date.now();
    for (const c of this.classes.values()) {
      for (const s of c.students.values()) if (s.connected && now - s.lastSeen > STALE_MS) s.connected = false;
      if (c.subscribers.size) {
        const json = JSON.stringify(this.state(c.id));
        for (const fn of c.subscribers) fn(json);
      }
    }
  }
}

function blank(s: Student): LiveStudent {
  return {
    id: s.id, name: s.name, age: s.age, color: s.color, connected: false, everConnected: false, lastSeen: 0,
    attention: 0, meditation: 0, signal: 0, signalOk: false, source: "None", session: null,
    attentionHistory: [], calmHistory: [], poorSignalSince: 0,
  };
}

function send(ws: WebSocket, msg: object) {
  if (ws.readyState === 1) ws.send(JSON.stringify(msg));
}
