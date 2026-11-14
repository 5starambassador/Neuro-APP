import path from "node:path";

const env = process.env;
const production = env.NODE_ENV === "production";

/** All settings come from environment variables (see .env.example). */
export const config = {
  production,
  port: Number(env.PORT ?? 4000),
  host: env.HOST ?? "0.0.0.0",
  dataDir: path.resolve(env.DATA_DIR ?? "data"),
  /** Set when running behind a reverse proxy (Caddy, nginx, a PaaS) that terminates HTTPS. */
  trustProxy: env.TRUST_PROXY === "true",
  /** Reject plain-HTTP requests (use in production behind an HTTPS proxy). */
  requireHttps: env.REQUIRE_HTTPS ? env.REQUIRE_HTTPS === "true" : production,
  /** Mark cookies Secure (HTTPS only). Defaults to on in production. */
  cookieSecure: env.COOKIE_SECURE ? env.COOKIE_SECURE === "true" : production,
  /** Optional built-in TLS (instead of a proxy): paths to a PEM certificate and key. */
  tlsCert: env.TLS_CERT,
  tlsKey: env.TLS_KEY,
  /** Optional TTF/OTF font for PDF reports, for names outside the Latin alphabet. */
  pdfFont: env.PDF_FONT,
  sessionDays: Number(env.SESSION_DAYS ?? 7),
  logLevel: env.LOG_LEVEL ?? (production ? "info" : "warn"),
};
