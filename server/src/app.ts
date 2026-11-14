import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import cookie from "@fastify/cookie";
import rateLimit from "@fastify/rate-limit";
import fastifyStatic from "@fastify/static";
import websocket from "@fastify/websocket";
import {
  HttpError, bearer, currentTokenHash, currentUser, endSession, hashPassword, joinCode, newId, passwordProblem,
  randomToken, requireAdmin, requireUser, sha256, startSession, verifyAgainstDummy, verifyPassword,
} from "./auth.js";
import { config } from "./config.js";
import { Db, type ClassRow, type User } from "./db.js";
import { GAMES, isGame } from "./games.js";
import { LiveHub } from "./live.js";
import { classPdf, groupPdf, studentPdf } from "./pdf.js";
import { DAY_MS, classRows, groupReport, sessionsCsv, startOfDay, studentReport } from "./reports.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = [path.join(here, "..", "public"), path.join(here, "..", "..", "public")].find((p) => fs.existsSync(p))!;

type Body = Record<string, unknown>;
const s = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");

export interface AppOptions { db?: Db; logger?: boolean }

export async function buildApp(opts: AppOptions = {}): Promise<{ app: FastifyInstance; db: Db; hub: LiveHub }> {
  const db = opts.db ?? new Db(path.join(config.dataDir, "focusquest.db"));
  const hub = new LiveHub(db);
  const https = config.tlsCert && config.tlsKey
    ? { key: fs.readFileSync(config.tlsKey), cert: fs.readFileSync(config.tlsCert) } : undefined;

  const app = Fastify({
    logger: opts.logger === false ? false : { level: config.logLevel },
    trustProxy: config.trustProxy,
    bodyLimit: 64 * 1024,
    ...(https ? { https } : {}),
  }) as unknown as FastifyInstance;

  await app.register(cookie);
  await app.register(rateLimit, { global: true, max: 600, timeWindow: "1 minute" });
  await app.register(websocket, { options: { maxPayload: 64 * 1024 } });

  // ---- cross-cutting: HTTPS, security headers, auth, CSRF ----------------------------
  app.addHook("onRequest", async (req, reply) => {
    if (config.requireHttps && req.protocol !== "https" && !req.url.startsWith("/healthz")) {
      if (req.method === "GET" && !req.url.startsWith("/api") && !req.url.startsWith("/ws")) {
        return reply.redirect(`https://${req.hostname}${req.url}`, 301);
      }
      return reply.code(403).send({ error: "HTTPS is required." });
    }
    reply.header("X-Content-Type-Options", "nosniff");
    reply.header("X-Frame-Options", "DENY");
    reply.header("Referrer-Policy", "no-referrer");
    reply.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    reply.header("Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    if (req.protocol === "https") reply.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    if (req.url.startsWith("/api/")) {
      reply.header("Cache-Control", "no-store");
      req.user = currentUser(db, req);
      // Cookie-authenticated writes must come from our own page (custom header => no cross-site forms).
      const studentApi = req.url.startsWith("/api/student/");
      if (!studentApi && req.method !== "GET" && req.method !== "HEAD" && req.headers["x-fq"] !== "1") {
        return reply.code(403).send({ error: "Forbidden" });
      }
    }
  });

  app.setErrorHandler((err: Error & { statusCode?: number }, req, reply) => {
    if (err instanceof HttpError) return reply.code(err.status).send({ error: err.message });
    if (err.statusCode === 429) return reply.code(429).send({ error: "Too many requests. Please wait a moment." });
    if (err.statusCode && err.statusCode < 500) return reply.code(err.statusCode).send({ error: err.message });
    req.log.error(err);
    return reply.code(500).send({ error: "Something went wrong." });
  });

  const ownClass = (req: FastifyRequest): ClassRow => {
    const u = requireUser(req);
    const id = (req.params as { id: string }).id;
    const c = db.classInOrg(id, u.org_id);
    if (!c) throw new HttpError(404, "Class not found.");
    return c;
  };
  const publicUser = (u: User) => ({ id: u.id, name: u.name, email: u.email, role: u.role });
  const tzOf = (req: FastifyRequest) => {
    const v = Number((req.query as Body).tz);
    return Number.isFinite(v) ? Math.max(-840, Math.min(840, Math.round(v))) : 0;
  };
  const rangeOf = (req: FastifyRequest, classId: string) => {
    const days = Math.max(0, Math.min(3650, Number((req.query as Body).days ?? 30) || 0));
    const now = Date.now();
    const from = days === 0 ? (db.firstSessionTime(classId) ?? now) : startOfDay(now, tzOf(req)) - (days - 1) * DAY_MS;
    return { from, to: now };
  };

  app.get("/healthz", async () => ({ ok: true }));

  // ---- setup & accounts --------------------------------------------------------------
  app.get("/api/status", async (req) => ({
    setupNeeded: db.userCount() === 0,
    user: req.user ? { ...publicUser(req.user), orgName: db.org(req.user.org_id)?.name ?? "" } : null,
  }));

  const loginLimit = { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } };

  app.post("/api/setup", loginLimit, async (req, reply) => {
    const b = req.body as Body;
    // Only possible while the server has no accounts at all; the first account is the school admin.
    if (db.userCount() > 0) throw new HttpError(409, "Setup is already complete.");
    const orgName = s(b.orgName, 80), name = s(b.name, 80), email = s(b.email, 200).toLowerCase();
    if (!orgName || !name || !/^\S+@\S+\.\S+$/.test(email)) throw new HttpError(400, "Enter the school name, your name and a valid email.");
    const problem = passwordProblem(b.password);
    if (problem) throw new HttpError(400, problem);
    const orgId = newId(), userId = newId(), now = Date.now();
    const hash = await hashPassword(b.password as string);
    db.transaction(() => {
      db.insertOrg({ id: orgId, name: orgName, created_at: now });
      db.insertUser({ id: userId, org_id: orgId, email, name, password_hash: hash, role: "admin", created_at: now });
    });
    startSession(db, reply, userId);
    return { ok: true };
  });

  app.post("/api/login", loginLimit, async (req, reply) => {
    const b = req.body as Body;
    const email = s(b.email, 200).toLowerCase();
    const password = typeof b.password === "string" ? b.password.slice(0, 200) : "";
    const u = db.userByEmail(email);
    if (!u) { await verifyAgainstDummy(password); throw new HttpError(401, "Email or password is incorrect."); }
    if (!(await verifyPassword(password, u.password_hash))) throw new HttpError(401, "Email or password is incorrect.");
    db.purgeExpiredSessions(Date.now());
    startSession(db, reply, u.id);
    return { ok: true };
  });

  app.post("/api/logout", async (req, reply) => { endSession(db, req, reply); return { ok: true }; });

  app.post("/api/me/password", loginLimit, async (req) => {
    const u = requireUser(req);
    const b = req.body as Body;
    if (!(await verifyPassword(String(b.current ?? ""), u.password_hash))) throw new HttpError(400, "Current password is incorrect.");
    const problem = passwordProblem(b.password);
    if (problem) throw new HttpError(400, problem);
    db.setPassword(u.id, await hashPassword(b.password as string));
    db.deleteUserSessions(u.id, currentTokenHash(req)); // sign out other browsers
    return { ok: true };
  });

  app.get("/api/users", async (req) => db.usersInOrg(requireAdmin(req).org_id).map(publicUser));

  app.post("/api/users", async (req) => {
    const admin = requireAdmin(req);
    const b = req.body as Body;
    const name = s(b.name, 80), email = s(b.email, 200).toLowerCase();
    const role = b.role === "admin" ? "admin" : "teacher";
    if (!name || !/^\S+@\S+\.\S+$/.test(email)) throw new HttpError(400, "Enter a name and a valid email.");
    if (db.userByEmail(email)) throw new HttpError(409, "That email already has an account.");
    const tempPassword = randomToken(9); // shown once to the admin, who passes it on
    db.insertUser({ id: newId(), org_id: admin.org_id, email, name, password_hash: await hashPassword(tempPassword), role, created_at: Date.now() });
    return { ok: true, tempPassword };
  });

  app.delete("/api/users/:uid", async (req) => {
    const admin = requireAdmin(req);
    const uid = (req.params as { uid: string }).uid;
    if (uid === admin.id) throw new HttpError(400, "You can't remove your own account.");
    if (!db.deleteUser(uid, admin.org_id)) throw new HttpError(404, "User not found.");
    return { ok: true };
  });

  // ---- classes -----------------------------------------------------------------------
  app.get("/api/meta", async () => ({ games: GAMES, durations: [120, 300, 600, 900] }));

  app.get("/api/classes", async (req) => {
    const u = requireUser(req);
    return db.classesInOrg(u.org_id).map((c) => ({
      id: c.id, name: c.name, joinCode: c.join_code, archived: !!c.archived, teacherName: c.teacher_name,
      studentCount: c.student_count, mine: c.teacher_id === u.id,
    }));
  });

  const uniqueCode = () => { for (;;) { const c = joinCode(); if (!db.classByCode(c)) return c; } };

  app.post("/api/classes", async (req) => {
    const u = requireUser(req);
    const name = s((req.body as Body).name, 60);
    if (!name) throw new HttpError(400, "Give the class a name.");
    const c: ClassRow = { id: newId(), org_id: u.org_id, teacher_id: u.id, name, join_code: uniqueCode(), archived: 0, created_at: Date.now() };
    db.insertClass(c);
    return { id: c.id, joinCode: c.join_code };
  });

  app.patch("/api/classes/:id", async (req) => {
    const c = ownClass(req);
    const b = req.body as Body;
    const name = b.name === undefined ? undefined : s(b.name, 60);
    if (name === "") throw new HttpError(400, "Give the class a name.");
    db.updateClass(c.id, {
      name,
      join_code: b.newCode === true ? uniqueCode() : undefined,
      archived: typeof b.archived === "boolean" ? (b.archived ? 1 : 0) : undefined,
    });
    hub.refreshClass(c.id);
    return { ok: true, joinCode: db.classById(c.id)?.join_code };
  });

  app.delete("/api/classes/:id", async (req) => {
    const c = ownClass(req);
    hub.dropClass(c.id);
    db.deleteClass(c.id);
    return { ok: true };
  });

  app.delete("/api/classes/:id/students/:sid", async (req) => {
    const c = ownClass(req);
    const sid = (req.params as { sid: string }).sid;
    if (!db.deleteStudent(sid, c.id)) throw new HttpError(404, "Student not found.");
    hub.removeStudent(c.id, sid);
    return { ok: true };
  });

  // ---- live class --------------------------------------------------------------------
  app.get("/api/classes/:id/state", async (req) => hub.state(ownClass(req).id));

  app.get("/api/classes/:id/events", async (req, reply) => {
    const c = ownClass(req);
    reply.hijack();
    const res = reply.raw;
    res.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-store", Connection: "keep-alive",
      "X-Accel-Buffering": "no", "X-Content-Type-Options": "nosniff",
    });
    res.write("retry: 2000\n\n");
    const unsubscribe = hub.subscribe(c.id, (json) => res.write(`data: ${json}\n\n`));
    req.raw.on("close", unsubscribe);
  });

  app.post("/api/classes/:id/group/start", async (req) => {
    const c = ownClass(req);
    const b = req.body as Body;
    if (!isGame(b.game)) throw new HttpError(400, "Unknown game.");
    const duration = Math.max(60, Math.min(1800, Number(b.duration) || 300));
    const r = hub.startGroup(c.id, b.game, duration);
    if (!r.ok) throw new HttpError(409, r.error);
    return { ok: true };
  });
  app.post("/api/classes/:id/group/end", async (req) => ({ ok: hub.endGroup(ownClass(req).id) }));
  app.post("/api/classes/:id/group/close", async (req) => {
    const id = hub.closeGroup(ownClass(req).id);
    if (!id) throw new HttpError(409, "No group session is running.");
    return { ok: true, groupId: id };
  });
  app.post("/api/classes/:id/cheer", async (req) => {
    const c = ownClass(req);
    const b = req.body as Body;
    const text = s(b.text, 120);
    if (!text) throw new HttpError(400, "Message is empty.");
    hub.cheer(c.id, typeof b.studentId === "string" ? b.studentId : null, text);
    return { ok: true };
  });

  // ---- reports -----------------------------------------------------------------------
  app.get("/api/classes/:id/reports/summary", async (req) => {
    const c = ownClass(req);
    const { from, to } = rangeOf(req, c.id);
    const rows = classRows(db.studentsInClass(c.id), db.sessionsInClass(c.id, from, to), db.lastSessionTimes(c.id), from, to);
    return {
      from, to,
      students: rows.map((r) => ({ id: r.student.id, name: r.student.name, age: r.student.age, sessions: r.sessions, minutes: r.minutes,
        focusPct: r.focusPct, change: r.focusChange, lastSession: r.lastSession, flag: r.flag })),
      groups: db.groupsInClass(c.id).map((g) => ({ id: g.id, gameTitle: GAMES.find((x) => x.id === g.game)?.title ?? g.game,
        startedAt: g.started_at, results: g.result_ids.length, total: g.student_ids.length })),
    };
  });

  const sendFile = (reply: FastifyReply, name: string, type: string, body: Buffer | string) =>
    reply.header("Content-Type", type).header("Content-Disposition", `attachment; filename="${name.replace(/[^\w.\- ]+/g, "_")}"`).send(body);
  const safe = (x: string) => x.replace(/[^A-Za-z0-9]+/g, "_").replace(/^_|_$/g, "") || "report";
  const stamp = () => Math.floor(Date.now() / 1000);

  app.get("/api/classes/:id/reports/class.pdf", async (req, reply) => {
    const c = ownClass(req);
    const { from, to } = rangeOf(req, c.id);
    const rows = classRows(db.studentsInClass(c.id), db.sessionsInClass(c.id, from, to), db.lastSessionTimes(c.id), from, to);
    return sendFile(reply, `FocusQuest_Class_${safe(c.name)}_${stamp()}.pdf`, "application/pdf", await classPdf(c.name, rows, from, to, tzOf(req)));
  });
  app.get("/api/classes/:id/reports/class.csv", async (req, reply) => {
    const c = ownClass(req);
    const { from, to } = rangeOf(req, c.id);
    return sendFile(reply, `FocusQuest_${safe(c.name)}_${stamp()}.csv`, "text/csv; charset=utf-8",
      sessionsCsv(db.studentsInClass(c.id), db.sessionsInClass(c.id, from, to), tzOf(req)));
  });
  app.get("/api/classes/:id/reports/student.pdf", async (req, reply) => {
    const c = ownClass(req);
    const st = db.studentsInClass(c.id).find((x) => x.id === (req.query as Body).sid);
    if (!st) throw new HttpError(404, "Student not found.");
    const { from, to } = rangeOf(req, c.id);
    const r = studentReport(st, db.sessionsInClass(c.id, from, to), from, to, tzOf(req));
    return sendFile(reply, `FocusQuest_${safe(st.name)}_${stamp()}.pdf`, "application/pdf", await studentPdf(r, c.name, tzOf(req)));
  });
  app.get("/api/classes/:id/reports/group.pdf", async (req, reply) => {
    const c = ownClass(req);
    const g = db.group(String((req.query as Body).gid ?? ""), c.id);
    if (!g) throw new HttpError(404, "Session not found.");
    const r = groupReport(g, db.studentsInClass(c.id), db.sessionsInClass(c.id));
    return sendFile(reply, `FocusQuest_Live_${safe(c.name)}_${Math.floor(g.started_at / 1000)}.pdf`, "application/pdf", await groupPdf(r, c.name, tzOf(req)));
  });

  // ---- student devices ---------------------------------------------------------------
  app.post("/api/student/join", { config: { rateLimit: { max: 20, timeWindow: "1 minute" } } }, async (req) => {
    const b = req.body as Body;
    const code = s(b.code, 12).toUpperCase().replace(/[^A-Z0-9]/g, "");
    const c = code.length === 6 ? db.classByCode(code) : undefined;
    if (!c) throw new HttpError(404, "No class found with that code.");
    const p = (b.profile ?? {}) as Body;
    const profileId = s(p.id, 64), name = s(p.name, 40);
    if (!profileId || !name) throw new HttpError(400, "A player name is required.");
    const age = Math.max(0, Math.min(120, Number(p.age) || 0));
    const color = Math.max(0, Math.min(99, Number(p.color) || 0));
    const device = s(b.device, 80) || null;
    const token = randomToken();
    const existing = db.studentByProfile(c.id, profileId);
    let studentId: string;
    if (existing) {
      studentId = existing.id;
      db.rejoinStudent(existing.id, { name, age, color, token_hash: sha256(token), device });
    } else {
      studentId = newId();
      db.insertStudent({ id: studentId, class_id: c.id, profile_id: profileId, name, age, color, token_hash: sha256(token), device, created_at: Date.now(), last_seen: null });
    }
    hub.refreshClass(c.id);
    hub.removeStudent(c.id, studentId); // reloads the roster entry with the new details on next connect
    return { token, studentId, classId: c.id, className: c.name };
  });

  app.post("/api/student/leave", async (req) => {
    const token = bearer(req);
    const st = token ? db.studentByToken(sha256(token)) : undefined;
    if (!st) throw new HttpError(401, "Not in a class.");
    // Keeps the student's past sessions for the teacher's reports; only the device link ends.
    db.rejoinStudent(st.id, { name: st.name, age: st.age, color: st.color, token_hash: sha256(randomToken()), device: st.device });
    hub.removeStudent(st.class_id, st.id);
    return { ok: true };
  });

  app.get("/ws/student", { websocket: true }, (socket, req) => {
    const token = bearer(req);
    const st = token ? db.studentByToken(sha256(token)) : undefined;
    if (!st) { socket.close(4001, "Unauthorized"); return; }
    const cls = db.classById(st.class_id);
    if (!cls || cls.archived) { socket.close(4004, "Class is closed"); return; }
    hub.attach(st, socket);
  });

  // ---- web dashboard (static) --------------------------------------------------------
  // "no-cache" = browsers may keep a copy but must revalidate (ETag), so updates reach teachers immediately.
  await app.register(fastifyStatic, {
    root: publicDir, index: ["index.html"], cacheControl: false,
    setHeaders: (res) => res.setHeader("Cache-Control", "no-cache"),
  });

  app.addHook("onClose", async () => hub.close());
  return { app, db, hub };
}
