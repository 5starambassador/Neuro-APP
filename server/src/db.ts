import fs from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";

export interface Org { id: string; name: string; created_at: number }
export interface User { id: string; org_id: string; email: string; name: string; password_hash: string; role: "admin" | "teacher"; created_at: number }
export interface ClassRow { id: string; org_id: string; teacher_id: string | null; name: string; join_code: string; archived: number; created_at: number }
export interface Student { id: string; class_id: string; profile_id: string; name: string; age: number; color: number; token_hash: string; device: string | null; created_at: number; last_seen: number | null }
export interface SessionRecord {
  id: string; class_id: string; student_id: string; game: string; started_at: number;
  duration_sec: number; focused_sec: number; avg_attention: number; avg_meditation: number;
  longest_streak_sec: number; points: number; start_threshold: number; end_threshold: number;
  source: string; timeline: number[]; group_id: string | null; received_at: number;
}
export interface GroupSession {
  id: string; class_id: string; game: string; planned_sec: number; started_at: number; ended_at: number;
  student_ids: string[]; result_ids: string[];
}

const SCHEMA = `
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS orgs (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin', 'teacher')),
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS classes (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  teacher_id TEXT REFERENCES users(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  join_code TEXT NOT NULL UNIQUE,
  archived INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS students (
  id TEXT PRIMARY KEY,
  class_id TEXT NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
  profile_id TEXT NOT NULL,
  name TEXT NOT NULL,
  age INTEGER NOT NULL,
  color INTEGER NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  device TEXT,
  created_at INTEGER NOT NULL,
  last_seen INTEGER,
  UNIQUE (class_id, profile_id)
);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT NOT NULL,
  class_id TEXT NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
  student_id TEXT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  game TEXT NOT NULL,
  started_at INTEGER NOT NULL,
  duration_sec INTEGER NOT NULL,
  focused_sec INTEGER NOT NULL,
  avg_attention INTEGER NOT NULL,
  avg_meditation INTEGER NOT NULL,
  longest_streak_sec INTEGER NOT NULL,
  points INTEGER NOT NULL,
  start_threshold INTEGER NOT NULL,
  end_threshold INTEGER NOT NULL,
  source TEXT NOT NULL,
  timeline TEXT NOT NULL,
  group_id TEXT,
  received_at INTEGER NOT NULL,
  PRIMARY KEY (student_id, id)
);
CREATE INDEX IF NOT EXISTS sessions_class_time ON sessions (class_id, started_at);
CREATE TABLE IF NOT EXISTS group_sessions (
  id TEXT PRIMARY KEY,
  class_id TEXT NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
  game TEXT NOT NULL,
  planned_sec INTEGER NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER NOT NULL,
  student_ids TEXT NOT NULL,
  result_ids TEXT NOT NULL
);
`;

type Row = Record<string, unknown>;

/** Thin data-access layer over node:sqlite. Every query that takes ids is org-scoped by the caller. */
export class Db {
  readonly sql: DatabaseSync;

  constructor(file: string) {
    if (file !== ":memory:") fs.mkdirSync(path.dirname(file), { recursive: true });
    this.sql = new DatabaseSync(file);
    this.sql.exec(SCHEMA);
  }

  private one<T>(query: string, ...params: (string | number | null)[]): T | undefined {
    return this.sql.prepare(query).get(...params) as T | undefined;
  }
  private all<T>(query: string, ...params: (string | number | null)[]): T[] {
    return this.sql.prepare(query).all(...params) as T[];
  }
  private run(query: string, ...params: (string | number | null)[]) {
    return this.sql.prepare(query).run(...params);
  }

  transaction<T>(fn: () => T): T {
    this.sql.exec("BEGIN");
    try {
      const r = fn();
      this.sql.exec("COMMIT");
      return r;
    } catch (e) {
      this.sql.exec("ROLLBACK");
      throw e;
    }
  }

  // ---- orgs & users ------------------------------------------------------------------

  userCount(): number { return (this.one<{ n: number }>("SELECT COUNT(*) n FROM users")?.n) ?? 0; }
  insertOrg(o: Org) { this.run("INSERT INTO orgs VALUES (?,?,?)", o.id, o.name, o.created_at); }
  org(id: string) { return this.one<Org>("SELECT * FROM orgs WHERE id=?", id); }
  insertUser(u: User) {
    this.run("INSERT INTO users VALUES (?,?,?,?,?,?,?)", u.id, u.org_id, u.email, u.name, u.password_hash, u.role, u.created_at);
  }
  userByEmail(email: string) { return this.one<User>("SELECT * FROM users WHERE email=?", email); }
  user(id: string) { return this.one<User>("SELECT * FROM users WHERE id=?", id); }
  usersInOrg(orgId: string) { return this.all<User>("SELECT * FROM users WHERE org_id=? ORDER BY name", orgId); }
  setPassword(userId: string, hash: string) { this.run("UPDATE users SET password_hash=? WHERE id=?", hash, userId); }
  deleteUser(id: string, orgId: string) { return this.run("DELETE FROM users WHERE id=? AND org_id=?", id, orgId).changes > 0; }

  insertAuthSession(tokenHash: string, userId: string, expiresAt: number) {
    this.run("INSERT INTO auth_sessions VALUES (?,?,?)", tokenHash, userId, expiresAt);
  }
  authSessionUser(tokenHash: string, now: number) {
    return this.one<User>(
      "SELECT u.* FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>?", tokenHash, now,
    );
  }
  deleteAuthSession(tokenHash: string) { this.run("DELETE FROM auth_sessions WHERE token_hash=?", tokenHash); }
  deleteUserSessions(userId: string, exceptHash?: string) {
    this.run("DELETE FROM auth_sessions WHERE user_id=? AND token_hash<>?", userId, exceptHash ?? "");
  }
  purgeExpiredSessions(now: number) { this.run("DELETE FROM auth_sessions WHERE expires_at<=?", now); }

  // ---- classes -----------------------------------------------------------------------

  insertClass(c: ClassRow) {
    this.run("INSERT INTO classes VALUES (?,?,?,?,?,?,?)", c.id, c.org_id, c.teacher_id, c.name, c.join_code, c.archived, c.created_at);
  }
  classInOrg(id: string, orgId: string) { return this.one<ClassRow>("SELECT * FROM classes WHERE id=? AND org_id=?", id, orgId); }
  classById(id: string) { return this.one<ClassRow>("SELECT * FROM classes WHERE id=?", id); }
  classByCode(code: string) { return this.one<ClassRow>("SELECT * FROM classes WHERE join_code=? AND archived=0", code); }
  classesInOrg(orgId: string) {
    return this.all<ClassRow & { teacher_name: string | null; student_count: number }>(
      `SELECT c.*, u.name teacher_name, (SELECT COUNT(*) FROM students s WHERE s.class_id=c.id) student_count
       FROM classes c LEFT JOIN users u ON u.id=c.teacher_id WHERE c.org_id=? ORDER BY c.archived, c.name`, orgId,
    );
  }
  updateClass(id: string, fields: { name?: string; join_code?: string; archived?: number; teacher_id?: string | null }) {
    const sets: string[] = [];
    const vals: (string | number | null)[] = [];
    for (const [k, v] of Object.entries(fields)) {
      if (v === undefined) continue;
      sets.push(`${k}=?`);
      vals.push(v as string | number | null);
    }
    if (sets.length) this.run(`UPDATE classes SET ${sets.join(",")} WHERE id=?`, ...vals, id);
  }
  deleteClass(id: string) { this.run("DELETE FROM classes WHERE id=?", id); }

  // ---- students ----------------------------------------------------------------------

  studentByProfile(classId: string, profileId: string) {
    return this.one<Student>("SELECT * FROM students WHERE class_id=? AND profile_id=?", classId, profileId);
  }
  studentByToken(tokenHash: string) { return this.one<Student>("SELECT * FROM students WHERE token_hash=?", tokenHash); }
  studentsInClass(classId: string) { return this.all<Student>("SELECT * FROM students WHERE class_id=? ORDER BY name", classId); }
  insertStudent(s: Student) {
    this.run("INSERT INTO students VALUES (?,?,?,?,?,?,?,?,?,?)",
      s.id, s.class_id, s.profile_id, s.name, s.age, s.color, s.token_hash, s.device, s.created_at, s.last_seen);
  }
  /** Re-joining from the same profile issues a fresh token and refreshes the details. */
  rejoinStudent(id: string, f: { name: string; age: number; color: number; token_hash: string; device: string | null }) {
    this.run("UPDATE students SET name=?, age=?, color=?, token_hash=?, device=? WHERE id=?", f.name, f.age, f.color, f.token_hash, f.device, id);
  }
  touchStudent(id: string, now: number) { this.run("UPDATE students SET last_seen=? WHERE id=?", now, id); }
  deleteStudent(id: string, classId: string) { return this.run("DELETE FROM students WHERE id=? AND class_id=?", id, classId).changes > 0; }

  // ---- sessions ----------------------------------------------------------------------

  /** Returns false if this session was already stored (e.g. resent after a reconnect). */
  insertSession(s: SessionRecord): boolean {
    const r = this.run(
      `INSERT OR IGNORE INTO sessions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      s.id, s.class_id, s.student_id, s.game, s.started_at, s.duration_sec, s.focused_sec, s.avg_attention,
      s.avg_meditation, s.longest_streak_sec, s.points, s.start_threshold, s.end_threshold, s.source,
      JSON.stringify(s.timeline), s.group_id, s.received_at,
    );
    return r.changes > 0;
  }
  sessionsInClass(classId: string, from = 0, to = Number.MAX_SAFE_INTEGER): SessionRecord[] {
    return this.all<Row>("SELECT * FROM sessions WHERE class_id=? AND started_at BETWEEN ? AND ? ORDER BY started_at", classId, from, to)
      .map(parseSession);
  }
  firstSessionTime(classId: string): number | undefined {
    return this.one<{ t: number | null }>("SELECT MIN(started_at) t FROM sessions WHERE class_id=?", classId)?.t ?? undefined;
  }
  lastSessionTimes(classId: string): Map<string, number> {
    return new Map(this.all<{ student_id: string; t: number }>(
      "SELECT student_id, MAX(started_at) t FROM sessions WHERE class_id=? GROUP BY student_id", classId,
    ).map((r) => [r.student_id, r.t]));
  }

  // ---- group sessions ----------------------------------------------------------------

  insertGroup(g: GroupSession) {
    this.run("INSERT OR REPLACE INTO group_sessions VALUES (?,?,?,?,?,?,?,?)",
      g.id, g.class_id, g.game, g.planned_sec, g.started_at, g.ended_at, JSON.stringify(g.student_ids), JSON.stringify(g.result_ids));
  }
  groupsInClass(classId: string, limit = 50): GroupSession[] {
    return this.all<Row>("SELECT * FROM group_sessions WHERE class_id=? ORDER BY started_at DESC LIMIT ?", classId, limit).map(parseGroup);
  }
  group(id: string, classId: string): GroupSession | undefined {
    const r = this.one<Row>("SELECT * FROM group_sessions WHERE id=? AND class_id=?", id, classId);
    return r ? parseGroup(r) : undefined;
  }
}

function parseSession(r: Row): SessionRecord {
  return { ...(r as unknown as SessionRecord), timeline: JSON.parse(String(r.timeline)) as number[] };
}
function parseGroup(r: Row): GroupSession {
  return {
    ...(r as unknown as GroupSession),
    student_ids: JSON.parse(String(r.student_ids)) as string[],
    result_ids: JSON.parse(String(r.result_ids)) as string[],
  };
}
