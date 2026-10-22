// The routes of https://api.<domain> and the hourly maintenance. Everything that touches DNS
// goes through DnsClient, everything that touches state through Db, so this file is only the
// rules: who may do what, with which addresses, how often.

import { checkDeclaredLength, isJsonContentType, readCapped } from "./body.ts";
import { verifySignedRequest, isValidPublicKey } from "./auth.ts";
import { Db, type RecordRow } from "./db.ts";
import { DnsClient, CloudflareApiError, ownerOf, type DnsRecordType } from "./dns.ts";
import { ApiError } from "./errors.ts";
import { isPrivateAddress, isPublicAddress, parseIp, recordType, registrationBuckets, type ParsedIp } from "./ip.ts";
import { NAME_PATTERN, randomName } from "./names.ts";
import { apexPage } from "./page.ts";
import { assetLinks, certFingerprints, linkPage } from "./links.ts";
import type { AcmeHost } from "../client.ts";

export interface Env {
  DB: D1Database;
  /** Secret: an API token allowed only to edit this zone's DNS. */
  CF_API_TOKEN: string;
  CF_ZONE_ID: string;
  /** The zone, e.g. "hypernova.observer". Names live directly under it. */
  DOMAIN: string;
  /** Where the API answers; defaults to "api.<DOMAIN>". Any other host shows the page. */
  API_HOST?: string;
  /** Overrides for the limits below, as strings because Wrangler vars are strings. */
  REGISTER_PER_IP_PER_DAY?: string;
  /** Per IPv6 /48 (a whole site or customer allocation); defaults to 4x the per-address limit. */
  REGISTER_PER_48_PER_DAY?: string;
  REGISTER_PER_DAY?: string;
  REQUESTS_PER_NAME_PER_HOUR?: string;
  /** New certificates' first challenges per day, all names together (Let's Encrypt allows 50 new certificates a week for the whole domain until it is on the Public Suffix List). */
  ACME_FIRST_PER_DAY?: string;
  /** Challenge records one name may publish a week (a certificate needs one or two; retries need more). */
  ACME_PER_NAME_PER_WEEK?: string;
  /** Where the Cloudflare API lives; only tests change it. */
  CF_API_BASE?: string;
  /**
   * The SHA-256 fingerprints of the certificates the Android app is signed with (comma- or
   * space-separated, or a JSON array): `/.well-known/assetlinks.json` lets this domain's links open
   * the app (links.ts). Unset: no App Links.
   */
  ANDROID_CERT_FINGERPRINTS?: string | string[];
}

/** What the Worker needs from the outside world, so tests can replace both. */
export type Deps = {
  fetch: typeof fetch;
  /** Unix seconds. */
  now: () => number;
};

const DAY = 86400;
const HOUR = 3600;
/** A name whose server has not called for this long is released. */
export const RELEASE_AFTER_SECONDS = 90 * DAY;
/** ACME challenge records disappear after this even if the client forgets to delete them. */
export const ACME_TTL_SECONDS = HOUR;
/** At most this many private addresses under l-<name>. */
export const MAX_LAN_ADDRESSES = 4;
/** At most this many challenge records per name at a time: a renewal needs two. */
export const MAX_ACME_RECORDS = 4;
/** A name's last_seen is only rewritten when it is older than this: the release clock is 90 days, so minute-level precision is worth no write. */
const TOUCH_EVERY_SECONDS = HOUR;
/** How many idle names one maintenance run releases, to keep the run short. */
const RELEASE_BATCH = 25;

type Ctx = {
  env: Env;
  db: Db;
  dns: DnsClient;
  now: number;
  domain: string;
  limits: { registerPerIp: number; registerPer48: number; registerPerDay: number; perNamePerHour: number; acmeFirstPerDay: number; acmePerNameWeek: number };
};

function intVar(value: string | undefined, fallback: number): number {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
}

function makeCtx(env: Env, deps: Deps): Ctx {
  return {
    env,
    db: new Db(env.DB),
    dns: new DnsClient(env.CF_ZONE_ID, env.CF_API_TOKEN, deps.fetch, env.CF_API_BASE),
    now: deps.now(),
    domain: env.DOMAIN.toLowerCase(),
    limits: {
      registerPerIp: intVar(env.REGISTER_PER_IP_PER_DAY, 5),
      registerPer48: intVar(env.REGISTER_PER_48_PER_DAY, intVar(env.REGISTER_PER_IP_PER_DAY, 5) * 4),
      registerPerDay: intVar(env.REGISTER_PER_DAY, 500),
      perNamePerHour: intVar(env.REQUESTS_PER_NAME_PER_HOUR, 120),
      acmeFirstPerDay: intVar(env.ACME_FIRST_PER_DAY, 6),
      acmePerNameWeek: intVar(env.ACME_PER_NAME_PER_WEEK, 8),
    },
  };
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...headers,
    },
  });
}

// ---------- the fetch handler ----------

export async function handleRequest(req: Request, env: Env, deps: Deps): Promise<Response> {
  const url = new URL(req.url);
  const host = url.hostname.toLowerCase();
  const domain = env.DOMAIN.toLowerCase();
  const apiHost = (env.API_HOST ?? `api.${domain}`).toLowerCase();

  if (host !== apiHost) {
    // The apex (and anything else that reaches the Worker) only ever shows static pages: the
    // domain's own, and the app links' (links.ts), whose details stay in the fragment.
    if (req.method === "GET" || req.method === "HEAD") {
      if (url.pathname === "/") return apexPage(domain);
      if (url.pathname === "/.well-known/assetlinks.json") return assetLinks(certFingerprints(env.ANDROID_CERT_FINGERPRINTS));
      if (url.pathname === "/pair" || url.pathname === "/glasses") return linkPage(url.pathname === "/pair" ? "pair" : "glasses", domain);
    }
    return new Response("Not found", { status: 404, headers: { "content-type": "text/plain", "cache-control": "no-store" } });
  }

  try {
    return await route(req, url.pathname, makeCtx(env, deps));
  } catch (e) {
    if (e instanceof ApiError) return json(e.status, { error: e.code, message: e.message }, e.headers);
    if (e instanceof CloudflareApiError) {
      console.error("dns upstream", e.message);
      return json(502, { error: "dns_upstream", message: "The DNS provider refused the change; try again in a minute." });
    }
    console.error("unhandled", e);
    return json(500, { error: "internal", message: "Something went wrong on our side." });
  }
}

async function route(req: Request, path: string, c: Ctx): Promise<Response> {
  if (path === "/" && req.method === "GET") {
    return json(200, { service: "hypernova-names", domain: c.domain, docs: "https://github.com/prudnik-web/Hypernova/tree/main/names" });
  }
  if (path === "/v1/register") {
    if (req.method !== "POST") throw new ApiError(405, "method", "Use POST.");
    return register(req, c);
  }
  const m = /^\/v1\/names\/([a-z0-9]{1,64})(\/address|\/acme)?$/.exec(path);
  if (!m) throw new ApiError(404, "not_found", "No such route.");
  const name = m[1];
  const sub = m[2] ?? "";
  const allowed = sub === "" ? ["GET", "DELETE"] : sub === "/address" ? ["PUT"] : ["PUT", "DELETE"];
  if (!allowed.includes(req.method)) throw new ApiError(405, "method", `Use ${allowed.join(" or ")}.`);

  // Refuse an oversized declared body before touching the database at all.
  checkDeclaredLength(req);
  const body = await authenticate(req, name, c);
  if (sub === "") return req.method === "GET" ? info(name, c) : release(name, c);
  if (sub === "/address") return setAddress(req, name, body, c);
  return req.method === "PUT" ? setAcme(name, body, c) : clearAcme(name, body, c);
}

// ---------- registration ----------

async function register(req: Request, c: Ctx): Promise<Response> {
  // A browser page on any site can send a "simple request" (a form post, text/plain) to this
  // unsigned route without a CORS preflight. Requiring application/json makes the browser
  // preflight it, and we answer no CORS headers, so only real clients get through. Checked
  // first, before any work or any write.
  if (!isJsonContentType(req)) throw new ApiError(415, "unsupported_media_type", "Send Content-Type: application/json.");
  const body = await readJson(new TextDecoder().decode(await readCapped(req)));
  const publicKey = (body as { publicKey?: unknown }).publicKey;
  if (typeof publicKey !== "string" || !(await isValidPublicKey(publicKey))) {
    throw new ApiError(400, "bad_key", "publicKey must be a raw 32-byte Ed25519 public key in base64.");
  }

  // Registration is anonymous, so the only brake is the source address, hashed so the database
  // never holds it. IPv6 callers usually own a whole /64 (and often a /48), so counting single
  // addresses would let one machine mint millions of "different" callers: IPv6 is counted per
  // /64 and, more loosely, per /48. All counters live in rows that expire with their day.
  const day = Math.floor(c.now / DAY);
  const dayEnd = (day + 1) * DAY;
  const buckets = await registrationBuckets(callerIp(req), sha256Short);
  const keys = [`reg:ip:${buckets.caller}:${day}`, ...(buckets.site ? [`reg:ip48:${buckets.site}:${day}`] : []), `reg:all:${day}`];
  const limits = [c.limits.registerPerIp, ...(buckets.site ? [c.limits.registerPer48] : []), c.limits.registerPerDay];

  // Look first (reads are plentiful on the free tier), count only when this call will be
  // served. A flood of over-limit calls then costs no writes at all.
  const counts = await c.db.peek(keys, c.now);
  refuseIfOver(counts, limits, keys.length, dayEnd - c.now);
  const after = await Promise.all(keys.map((k) => c.db.bump(k, dayEnd)));
  // Two calls can pass the look at once; the counts returned by the writes settle it.
  refuseIfOver(after.map((n) => n - 1), limits, keys.length, dayEnd - c.now);

  for (let attempt = 0; attempt < 5; attempt++) {
    const name = randomName();
    if (await c.db.insertName(name, publicKey, c.now)) {
      return json(201, { name, fqdn: `${name}.${c.domain}`, lanFqdn: `l-${name}.${c.domain}`, domain: c.domain });
    }
  }
  throw new ApiError(500, "internal", "Could not find a free name; try again.");
}

/** Throws the matching 429 when any counter has already reached its limit. The last key is the global one. */
function refuseIfOver(counts: number[], limits: number[], n: number, wait: number): void {
  for (let i = 0; i < n; i++) {
    if (counts[i] < limits[i]) continue;
    if (i === n - 1) throw new ApiError(429, "service_busy", "No more names today; try again tomorrow.", retryAfter(wait));
    throw new ApiError(429, "too_many_registrations", "This address registered enough names for today.", retryAfter(wait));
  }
}

// ---------- signed calls ----------

/** Checks the signature, the nonce and the per-name limit, marks the name as alive, and returns the body. */
async function authenticate(req: Request, name: string, c: Ctx): Promise<Uint8Array> {
  if (!NAME_PATTERN.test(name)) throw new ApiError(404, "unknown_name", "No such name.");
  const row = await c.db.getName(name);
  if (!row) throw new ApiError(404, "unknown_name", "No such name.");
  const { body, nonce, nonceExpiresAt } = await verifySignedRequest(req, name, row.public_key, c.now);

  // Only requests that proved they hold the key count, so a stranger who learned a name from a
  // certificate log cannot lock its owner out.
  const hour = Math.floor(c.now / HOUR);
  const hourEnd = (hour + 1) * HOUR;
  const limitKey = `name:${name}:${hour}`;
  const limited = () => new ApiError(429, "rate_limited", "Too many requests for this name this hour.", retryAfter(hourEnd - c.now));
  const [used] = await c.db.peek([limitKey], c.now);
  if (used >= c.limits.perNamePerHour) throw limited();
  // The nonce is written only now that the call will be served; a valid but over-limit call
  // writes nothing, and replaying a refused call is harmless since it was refused.
  if (!(await c.db.claimNonce(name, nonce, nonceExpiresAt))) throw new ApiError(401, "replayed", "This nonce was already used.");
  const hits = await c.db.bump(limitKey, hourEnd);
  if (hits > c.limits.perNamePerHour) throw limited();

  if (c.now - row.last_seen >= TOUCH_EVERY_SECONDS) await c.db.touchName(name, c.now);
  return body;
}

async function info(name: string, c: Ctx): Promise<Response> {
  const row = (await c.db.getName(name))!;
  const records = await c.db.records(name);
  const pub = records.find((r) => r.kind === "public");
  return json(200, {
    name,
    fqdn: `${name}.${c.domain}`,
    lanFqdn: `l-${name}.${c.domain}`,
    createdAt: row.created_at,
    lastSeen: c.now,
    public: pub ? { type: pub.rr_type, address: pub.content } : null,
    lan: records.filter((r) => r.kind === "lan").map((r) => r.content),
    txt: acmeList(name, records, c),
  });
}

async function release(name: string, c: Ctx): Promise<Response> {
  await deleteAllRecords(name, c);
  await c.db.deleteName(name);
  return json(200, { released: name });
}

// ---------- addresses ----------

async function setAddress(req: Request, name: string, bodyBytes: Uint8Array, c: Ctx): Promise<Response> {
  const body = (await readJson(new TextDecoder().decode(bodyBytes))) as { lan?: unknown; public?: unknown };
  if (body.public !== undefined && typeof body.public !== "boolean") throw new ApiError(400, "bad_public", "public must be true or false.");
  // A computer at home reached only on its own network (a Mac) has no use for a public record,
  // and publishing it would put the home's address in DNS under a name anyone can read from the
  // certificate logs. `public: false` leaves it out, and removes one published before.
  const wantPublic = body.public !== false;

  // The public address is the caller's own, as seen by Cloudflare, never something from the
  // body: that is what stops anyone from pointing a name at a server that is not theirs.
  const caller = wantPublic ? parseIp(callerIp(req)) : null;
  if (wantPublic && (!caller || !isPublicAddress(caller))) {
    throw new ApiError(400, "caller_not_public", "Your address as seen from the internet is not a public one; a name must point at a reachable address.");
  }

  const lan = parseLan(body.lan);

  const existing = await c.db.records(name);
  const pubFqdn = `${name}.${c.domain}`;
  const publicRows = existing.filter((r) => r.kind === "public");
  if (caller) await upsertSingle(name, "public", pubFqdn, caller, publicRows, c);
  else await deleteRows(publicRows, c);
  await syncSet(name, "lan", `l-${name}.${c.domain}`, lan, existing.filter((r) => r.kind === "lan"), c);

  return json(200, {
    name,
    fqdn: pubFqdn,
    public: caller ? { type: recordType(caller), address: caller.text } : null,
    lanFqdn: `l-${name}.${c.domain}`,
    lan: lan.map((ip) => ip.text),
  });
}

async function deleteRows(rows: RecordRow[], c: Ctx): Promise<void> {
  for (const r of rows) {
    await c.dns.delete(r.cf_id);
    await c.db.deleteRecord(r.id);
  }
}

function parseLan(value: unknown): ParsedIp[] {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw new ApiError(400, "bad_lan", "lan must be an array of addresses.");
  if (value.length > MAX_LAN_ADDRESSES) throw new ApiError(400, "bad_lan", `At most ${MAX_LAN_ADDRESSES} private addresses.`);
  const out: ParsedIp[] = [];
  for (const item of value) {
    if (typeof item !== "string") throw new ApiError(400, "bad_lan", "lan must be an array of addresses.");
    const ip = parseIp(item);
    if (!ip) throw new ApiError(400, "bad_lan", `"${item}" is not an IP address.`);
    if (!isPrivateAddress(ip)) throw new ApiError(400, "lan_not_private", `"${item}" is not a private address; l-<name> only takes RFC 1918, 100.64/10 and IPv6 unique local addresses.`);
    if (!out.some((o) => o.text === ip.text)) out.push(ip);
  }
  return out;
}

/** Keeps exactly one record (A or AAAA) at a DNS name, changing it in place when it exists. */
async function upsertSingle(name: string, kind: "public", rrName: string, ip: ParsedIp, rows: RecordRow[], c: Ctx): Promise<void> {
  const type = recordType(ip);
  const [keep, ...extra] = rows;
  for (const r of extra) {
    await c.dns.delete(r.cf_id);
    await c.db.deleteRecord(r.id);
  }
  if (keep && keep.rr_type === type && keep.content === ip.text) return;
  if (keep) {
    try {
      await c.dns.update(keep.cf_id, type, rrName, ip.text, name);
      await c.db.updateRecordContent(keep.id, type, ip.text);
      return;
    } catch (e) {
      // Someone removed it in the dashboard: forget it and make a new one.
      if (!(e instanceof CloudflareApiError && e.status === 404)) throw e;
      await c.db.deleteRecord(keep.id);
    }
  }
  await createRecord(name, kind, type, rrName, ip.text, null, c);
}

/** Makes the records at a DNS name match the wanted set of addresses, touching only the differences. */
async function syncSet(name: string, kind: "lan", rrName: string, wanted: ParsedIp[], rows: RecordRow[], c: Ctx): Promise<void> {
  const wantedText = new Set(wanted.map((ip) => ip.text));
  for (const r of rows) {
    if (!wantedText.has(r.content)) {
      await c.dns.delete(r.cf_id);
      await c.db.deleteRecord(r.id);
    }
  }
  const have = new Set(rows.map((r) => r.content));
  for (const ip of wanted) {
    if (!have.has(ip.text)) await createRecord(name, kind, recordType(ip), rrName, ip.text, null, c);
  }
}

async function createRecord(name: string, kind: RecordRow["kind"], type: DnsRecordType, rrName: string, content: string, expiresAt: number | null, c: Ctx): Promise<void> {
  const created = await c.dns.create(type, rrName, content, name);
  await c.db.insertRecord({ name, kind, cf_id: created.id, rr_name: rrName, rr_type: type, content, created_at: c.now, expires_at: expiresAt });
}

// ---------- ACME challenges ----------

const ACME_VALUE = /^[A-Za-z0-9_-]{1,255}$/;

function acmeRrName(name: string, host: AcmeHost, c: Ctx): string {
  return host === "lan" ? `_acme-challenge.l-${name}.${c.domain}` : `_acme-challenge.${name}.${c.domain}`;
}

function parseAcmeHost(value: unknown, fallback: AcmeHost | undefined): AcmeHost | undefined {
  if (value === undefined) return fallback;
  if (value === "main" || value === "lan") return value;
  throw new ApiError(400, "bad_host", 'host must be "main" or "lan".');
}

function acmeList(name: string, records: RecordRow[], c: Ctx) {
  return records
    .filter((r) => r.kind === "acme")
    .map((r) => ({ host: (r.rr_name === acmeRrName(name, "lan", c) ? "lan" : "main") as AcmeHost, value: r.content, expiresAt: r.expires_at ?? 0 }));
}

async function setAcme(name: string, bodyBytes: Uint8Array, c: Ctx): Promise<Response> {
  const body = (await readJson(new TextDecoder().decode(bodyBytes))) as { value?: unknown; host?: unknown };
  if (typeof body.value !== "string" || !ACME_VALUE.test(body.value)) throw new ApiError(400, "bad_value", "value must be the DNS-01 challenge text (letters, digits, - and _).");
  const host = parseAcmeHost(body.host, "main")!;
  const rrName = acmeRrName(name, host, c);
  const expiresAt = c.now + ACME_TTL_SECONDS;

  const rows = await c.db.records(name, "acme");
  const same = rows.find((r) => r.rr_name === rrName && r.content === body.value);
  if (same) {
    // Publishing the same challenge twice (a retry) just restarts its hour.
    await c.db.refreshRecordExpiry(same.id, expiresAt);
  } else {
    if (rows.length >= MAX_ACME_RECORDS) throw new ApiError(409, "too_many_challenges", `At most ${MAX_ACME_RECORDS} challenge records at a time; delete finished ones first.`);
    // Let's Encrypt gives the whole domain 50 new certificates a week (until the Public Suffix List
    // lists it), and anyone can register: so a name's first-ever challenge counts against a daily
    // budget for everyone, and one name publishes only so many a week. A name that has published
    // before (a renewal, which needs one certificate in about sixty days) is exempt from the
    // daily budget, so growth never starves the servers already running.
    const day = Math.floor(c.now / DAY);
    const week = Math.floor(c.now / (7 * DAY));
    const seenKey = `acme:seen:${name}`;
    const weekKey = `acme:name:${name}:${week}`;
    const dayKey = `acme:first:${day}`;
    const [seen, usedWeek, usedDay] = await c.db.peek([seenKey, weekKey, dayKey], c.now);
    const weekEnd = (week + 1) * 7 * DAY;
    if (usedWeek >= c.limits.acmePerNameWeek) throw new ApiError(429, "acme_name_limit", "This name has asked for enough certificates this week.", retryAfter(weekEnd - c.now));
    const first = seen === 0;
    if (first && usedDay >= c.limits.acmeFirstPerDay) throw new ApiError(429, "acme_busy", "No more new certificates today; try again tomorrow. (Pairing works without the private name.)", retryAfter((day + 1) * DAY - c.now));
    await createRecord(name, "acme", "TXT", rrName, body.value, expiresAt, c);
    await c.db.bump(weekKey, weekEnd);
    if (first) {
      await c.db.bump(dayKey, (day + 1) * DAY);
      await c.db.bump(seenKey, c.now + 400 * DAY);
    }
  }
  return json(200, { name, txt: acmeList(name, await c.db.records(name, "acme"), c) });
}

async function clearAcme(name: string, bodyBytes: Uint8Array, c: Ctx): Promise<Response> {
  const text = new TextDecoder().decode(bodyBytes);
  const body = (text ? await readJson(text) : {}) as { value?: unknown; host?: unknown };
  if (body.value !== undefined && typeof body.value !== "string") throw new ApiError(400, "bad_value", "value must be a string.");
  const host = parseAcmeHost(body.host, undefined);
  const rows = (await c.db.records(name, "acme")).filter(
    (r) => (body.value === undefined || r.content === body.value) && (host === undefined || r.rr_name === acmeRrName(name, host, c)),
  );
  for (const r of rows) {
    await c.dns.delete(r.cf_id);
    await c.db.deleteRecord(r.id);
  }
  return json(200, { name, txt: acmeList(name, await c.db.records(name, "acme"), c) });
}

// ---------- shared ----------

async function deleteAllRecords(name: string, c: Ctx): Promise<void> {
  await deleteRows(await c.db.records(name), c);
}

function callerIp(req: Request): string {
  // Cloudflare sets this on every request that reaches the Worker; a missing header means the
  // request did not come through Cloudflare's edge, which is the only way in.
  const ip = req.headers.get("cf-connecting-ip");
  if (!ip) throw new ApiError(400, "no_caller_address", "The request did not come through Cloudflare.");
  return ip;
}

async function readJson(text: string): Promise<Record<string, unknown>> {
  if (text.length > 4096) throw new ApiError(413, "too_large", "The body is too large.");
  if (text.trim() === "") return {};
  try {
    const v = JSON.parse(text);
    if (typeof v !== "object" || v === null || Array.isArray(v)) throw new Error();
    return v as Record<string, unknown>;
  } catch {
    throw new ApiError(400, "bad_json", "The body must be a JSON object.");
  }
}

export async function sha256Short(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

function retryAfter(seconds: number): Record<string, string> {
  return { "retry-after": String(Math.max(1, seconds)) };
}

// ---------- maintenance (the cron trigger) ----------

export type MaintenanceReport = { expiredChallenges: number; releasedNames: number; orphansRemoved: number };

/**
 * Runs every hour: forgets old nonces and counters, removes challenge records past their hour,
 * releases names nobody has called for 90 days, and removes tagged records at Cloudflare that
 * the database no longer knows about (left behind by a crash between the two writes).
 */
export async function runMaintenance(env: Env, deps: Deps): Promise<MaintenanceReport> {
  const c = makeCtx(env, deps);
  const report: MaintenanceReport = { expiredChallenges: 0, releasedNames: 0, orphansRemoved: 0 };

  await c.db.prune(c.now);

  for (const r of await c.db.expiredRecords(c.now, 100)) {
    await c.dns.delete(r.cf_id);
    await c.db.deleteRecord(r.id);
    report.expiredChallenges++;
  }

  for (const name of await c.db.namesNotSeenSince(c.now - RELEASE_AFTER_SECONDS, RELEASE_BATCH)) {
    await deleteAllRecords(name, c);
    await c.db.deleteName(name);
    report.releasedNames++;
  }

  // Only records carrying our tag are even looked at, and only ones older than an hour, so a
  // record created a moment ago by a request still in flight is left alone.
  const known = await c.db.allRecordCfIds();
  const cutoff = (c.now - HOUR) * 1000;
  for (const rec of await c.dns.listTagged()) {
    if (!ownerOf(rec) || known.has(rec.id)) continue;
    const created = Date.parse(rec.createdOn);
    if (Number.isFinite(created) && created > cutoff) continue;
    await c.dns.delete(rec.id);
    report.orphansRemoved++;
  }

  return report;
}
