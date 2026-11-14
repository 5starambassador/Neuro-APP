import crypto from "node:crypto";
import { promisify } from "node:util";
import type { FastifyReply, FastifyRequest } from "fastify";
import { config } from "./config.js";
import type { Db, User } from "./db.js";

const scrypt = promisify(crypto.scrypt) as (pw: string, salt: Buffer, len: number, opts: crypto.ScryptOptions) => Promise<Buffer>;
const SCRYPT = { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
export const SESSION_COOKIE = "fq_session";
export const MIN_PASSWORD = 10;

export const newId = () => crypto.randomUUID();
export const randomToken = (bytes = 32) => crypto.randomBytes(bytes).toString("base64url");
export const sha256 = (s: string) => crypto.createHash("sha256").update(s).digest("hex");

/** Class join codes avoid look-alike characters (0/O, 1/I/L). */
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export function joinCode(length = 6): string {
  let s = "";
  for (const b of crypto.randomBytes(length)) s += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return s;
}

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.randomBytes(16);
  const hash = await scrypt(password, salt, 64, SCRYPT);
  return `scrypt$${salt.toString("base64")}$${hash.toString("base64")}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [kind, saltB64, hashB64] = stored.split("$");
  if (kind !== "scrypt" || !saltB64 || !hashB64) return false;
  const expected = Buffer.from(hashB64, "base64");
  const actual = await scrypt(password, Buffer.from(saltB64, "base64"), expected.length, SCRYPT);
  return crypto.timingSafeEqual(actual, expected);
}

/** A dummy hash so unknown emails take as long as wrong passwords (no user enumeration by timing). */
let dummyHash: Promise<string> | undefined;
export async function verifyAgainstDummy(password: string) {
  dummyHash ??= hashPassword(randomToken());
  await verifyPassword(password, await dummyHash);
}

export function passwordProblem(pw: unknown): string | null {
  if (typeof pw !== "string" || pw.length < MIN_PASSWORD) return `Use at least ${MIN_PASSWORD} characters.`;
  if (pw.length > 200) return "That password is too long.";
  return null;
}

export function startSession(db: Db, reply: FastifyReply, userId: string) {
  const token = randomToken();
  const maxAge = config.sessionDays * 24 * 3600;
  db.insertAuthSession(sha256(token), userId, Date.now() + maxAge * 1000);
  reply.setCookie(SESSION_COOKIE, token, {
    path: "/", httpOnly: true, sameSite: "strict", secure: config.cookieSecure, maxAge,
  });
}

export function endSession(db: Db, req: FastifyRequest, reply: FastifyReply) {
  const token = req.cookies[SESSION_COOKIE];
  if (token) db.deleteAuthSession(sha256(token));
  reply.clearCookie(SESSION_COOKIE, { path: "/" });
}

export function currentUser(db: Db, req: FastifyRequest): User | undefined {
  const token = req.cookies[SESSION_COOKIE];
  if (!token || token.length > 200) return undefined;
  return db.authSessionUser(sha256(token), Date.now());
}

export function currentTokenHash(req: FastifyRequest): string | undefined {
  const t = req.cookies[SESSION_COOKIE];
  return t ? sha256(t) : undefined;
}

declare module "fastify" {
  interface FastifyRequest {
    user?: User;
  }
}

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export function requireUser(req: FastifyRequest): User {
  if (!req.user) throw new HttpError(401, "Please sign in.");
  return req.user;
}

export function requireAdmin(req: FastifyRequest): User {
  const u = requireUser(req);
  if (u.role !== "admin") throw new HttpError(403, "Only a school admin can do that.");
  return u;
}

/** Student devices authenticate with a bearer token issued when they join a class. */
export function bearer(req: FastifyRequest): string | undefined {
  const h = req.headers.authorization;
  if (!h?.startsWith("Bearer ")) return undefined;
  const t = h.slice(7).trim();
  return t.length > 0 && t.length <= 200 ? t : undefined;
}
