import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import type { FastifyInstance } from "fastify";
import { buildApp } from "./app.js";
import { Db } from "./db.js";

let app: FastifyInstance;
let cookie = "";
let classId = "";
let joinCode = "";
let studentToken = "";

const H = () => ({ cookie, "x-fq": "1" });

before(async () => {
  ({ app } = await buildApp({ db: new Db(":memory:"), logger: false }));
  await app.ready();
});
after(async () => { await app.close(); });

test("first visit needs setup; setup creates the admin and signs in", async () => {
  const status = await app.inject({ method: "GET", url: "/api/status" });
  assert.equal(status.json().setupNeeded, true);
  const weak = await app.inject({ method: "POST", url: "/api/setup", headers: { "x-fq": "1" }, payload: { orgName: "Achariya School", name: "Priya", email: "priya@example.org", password: "short" } });
  assert.equal(weak.statusCode, 400);
  const res = await app.inject({ method: "POST", url: "/api/setup", headers: { "x-fq": "1" }, payload: { orgName: "Achariya School", name: "Priya", email: "priya@example.org", password: "correct horse battery" } });
  assert.equal(res.statusCode, 200);
  cookie = String(res.cookies.find((c) => c.name === "fq_session")?.value ? `fq_session=${res.cookies.find((c) => c.name === "fq_session")!.value}` : "");
  assert.ok(cookie);
  const again = await app.inject({ method: "POST", url: "/api/setup", headers: { "x-fq": "1" }, payload: { orgName: "X", name: "Y", email: "y@example.org", password: "another long password" } });
  assert.equal(again.statusCode, 409);
});

test("protected routes need a session and the CSRF header", async () => {
  assert.equal((await app.inject({ method: "GET", url: "/api/classes" })).statusCode, 401);
  const noHeader = await app.inject({ method: "POST", url: "/api/classes", headers: { cookie }, payload: { name: "4B" } });
  assert.equal(noHeader.statusCode, 403);
  const bad = await app.inject({ method: "POST", url: "/api/login", headers: { "x-fq": "1" }, payload: { email: "priya@example.org", password: "wrong password!" } });
  assert.equal(bad.statusCode, 401);
});

test("teacher creates a class with a join code", async () => {
  const res = await app.inject({ method: "POST", url: "/api/classes", headers: H(), payload: { name: "Grade 4B Focus Club" } });
  assert.equal(res.statusCode, 200);
  ({ id: classId, joinCode } = res.json());
  assert.match(joinCode, /^[A-Z2-9]{6}$/);
  const list = await app.inject({ method: "GET", url: "/api/classes", headers: H() });
  assert.equal(list.json()[0].name, "Grade 4B Focus Club");
});

test("student joins with the code and streams live data over WebSocket", async () => {
  const wrong = await app.inject({ method: "POST", url: "/api/student/join", payload: { code: "ZZZZZZ", profile: { id: "p1", name: "Meera" } } });
  assert.equal(wrong.statusCode, 404);
  const join = await app.inject({ method: "POST", url: "/api/student/join", payload: { code: joinCode.toLowerCase(), profile: { id: "p1", name: "Meera", age: 9, color: 1 }, device: "Test" } });
  assert.equal(join.statusCode, 200);
  studentToken = join.json().token;

  // Listeners are attached in onInit so nothing the server sends on connect is missed.
  const closed = new Promise<number>((resolve) => {
    void app.injectWS("/ws/student", { headers: { authorization: "Bearer nope" } }, {
      onInit: (s) => s.on("close", (code: number) => resolve(code)),
    }).catch(() => {});
  });
  assert.equal(await closed, 4001);

  const messages: Record<string, unknown>[] = [];
  const waiters: { t: string; resolve: (m: Record<string, unknown>) => void }[] = [];
  const ws = await app.injectWS("/ws/student", { headers: { authorization: `Bearer ${studentToken}` } }, {
    onInit: (s) => s.on("message", (d: Buffer) => {
      const m = JSON.parse(d.toString()) as Record<string, unknown>;
      const i = waiters.findIndex((w) => w.t === m.t);
      if (i >= 0) waiters.splice(i, 1)[0]!.resolve(m); else messages.push(m);
    }),
  });
  const next = (t: string) => new Promise<Record<string, unknown>>((resolve) => {
    const i = messages.findIndex((m) => m.t === t);
    if (i >= 0) resolve(messages.splice(i, 1)[0]!); else waiters.push({ t, resolve });
  });
  assert.equal((await next("welcome")).className, "Grade 4B Focus Club");

  ws.send(JSON.stringify({ t: "state", att: 72, med: 40, sig: 100, ok: true, src: "Headset", link: "Connected" }));
  await new Promise((r) => setTimeout(r, 50));
  const state = (await app.inject({ method: "GET", url: `/api/classes/${classId}/state`, headers: H() })).json();
  assert.equal(state.students[0].name, "Meera");
  assert.equal(state.students[0].connected, true);
  assert.equal(state.students[0].attention, 72);

  const start = await app.inject({ method: "POST", url: `/api/classes/${classId}/group/start`, headers: H(), payload: { game: "BRIDGE", duration: 120 } });
  assert.equal(start.statusCode, 200);
  const startMsg = await next("start");
  assert.equal(startMsg.game, "BRIDGE");

  ws.send(JSON.stringify({ t: "result", group: startMsg.group, record: {
    id: "rec-1", game: "BRIDGE", startedAt: Date.now() - 60_000, durationSec: 60, focusedSec: 45, avgAttention: 65, avgMeditation: 40,
    longestStreakSec: 30, points: 500, startThreshold: 50, endThreshold: 55, source: "Headset", timeline: [50, 60, 70],
  } }));
  assert.equal((await next("ack")).id, "rec-1");

  const close = await app.inject({ method: "POST", url: `/api/classes/${classId}/group/close`, headers: H() });
  const gid = close.json().groupId;
  assert.ok(gid);

  const summary = (await app.inject({ method: "GET", url: `/api/classes/${classId}/reports/summary?days=7`, headers: H() })).json();
  assert.equal(summary.students[0].sessions, 1);
  assert.equal(summary.students[0].focusPct, 75);
  assert.equal(summary.groups[0].results, 1);

  for (const url of [
    `/api/classes/${classId}/reports/class.pdf?days=7`,
    `/api/classes/${classId}/reports/student.pdf?sid=${summary.students[0].id}&days=0`,
    `/api/classes/${classId}/reports/group.pdf?gid=${gid}`,
  ]) {
    const pdf = await app.inject({ method: "GET", url, headers: H() });
    assert.equal(pdf.statusCode, 200, url);
    assert.equal(pdf.rawPayload.subarray(0, 5).toString(), "%PDF-");
  }
  const csv = await app.inject({ method: "GET", url: `/api/classes/${classId}/reports/class.csv?days=7`, headers: H() });
  assert.ok(csv.body.includes("Meera"));
  ws.terminate();
});

test("dashboard files revalidate so updates reach browsers, under a strict CSP", async () => {
  for (const url of ["/", "/app.js", "/app.css"]) {
    const res = await app.inject({ method: "GET", url });
    assert.equal(res.statusCode, 200, url);
    assert.equal(res.headers["cache-control"], "no-cache", url);
    assert.match(String(res.headers["content-security-policy"]), /style-src 'self';/);
  }
  // Inline style attributes are blocked by that CSP, so the dashboard must not set them.
  const js = (await app.inject({ method: "GET", url: "/app.js" })).body;
  assert.ok(!js.includes('setAttribute("style"'), "app.js must style elements via CSSOM, not style attributes");
});

test("classes are isolated between schools", async () => {
  const other = await buildApp({ db: new Db(":memory:"), logger: false });
  // A session cookie from this server is meaningless to another org's data.
  const res = await app.inject({ method: "GET", url: `/api/classes/not-a-real-id/state`, headers: H() });
  assert.equal(res.statusCode, 404);
  await other.app.close();
});
