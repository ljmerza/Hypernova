import { describe, expect, it } from "vitest";
import { exportPrivateKey, generateServerKey, importServerKey, signRequest } from "../client.ts";
import { API, Harness, JSON_HEADERS, bodyOf } from "./harness.ts";

/** Counts the rows in every table, to prove a refused call wrote nothing. */
async function rowCounts(): Promise<Record<string, number>> {
  const { env } = await import("cloudflare:test");
  const out: Record<string, number> = {};
  for (const t of ["names", "records", "nonces", "limits"]) {
    out[t] = (await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${t}`).first<{ n: number }>())!.n;
  }
  return out;
}

describe("D1 write budget", () => {
  it("writes nothing for unsigned, badly signed, unknown-name and malformed calls", async () => {
    const h = new Harness();
    const { key, name } = await h.register();
    const stranger = await generateServerKey();
    const before = await rowCounts();
    const path = `/v1/names/${name}`;

    expect((await h.fetch(API + path)).status).toBe(401); // unsigned
    expect((await h.fetch(API + path, await signRequest(stranger, "GET", path, undefined, h.now))).status).toBe(401); // wrong key
    expect((await h.fetch(API + path, await signRequest(key, "GET", path, undefined, h.now + 1000))).status).toBe(401); // expired
    const ghost = "/v1/names/abcdefghij";
    expect((await h.fetch(API + ghost, await signRequest(key, "GET", ghost, undefined, h.now))).status).toBe(404);
    expect((await h.fetch(`${API}/v1/register`, { method: "POST", body: "x" })).status).toBe(415);
    expect((await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: "{bad" })).status).toBe(400);
    expect((await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ publicKey: "nope" }) })).status).toBe(400);

    expect(await rowCounts()).toEqual(before);
  });

  it("writes nothing once a registration limit is hit", async () => {
    const h = new Harness();
    for (let i = 0; i < 5; i++) await h.register();
    const before = await rowCounts();
    for (let i = 0; i < 5; i++) await expect(h.register()).rejects.toMatchObject({ status: 429 });
    expect(await rowCounts()).toEqual(before);
  });

  it("writes nothing for a valid call over the per-name limit", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    for (let i = 0; i < 30; i++) await client.info(key, name);
    const before = await rowCounts();
    await expect(client.info(key, name)).rejects.toMatchObject({ status: 429 });
    expect(await rowCounts()).toEqual(before);
  });

  it("a valid read call adds just a nonce and a counter, and only rewrites last_seen hourly", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    const before = await rowCounts();
    await client.info(key, name);
    const after = await rowCounts();
    expect(after.nonces - before.nonces).toBe(1);
    expect(after.limits - before.limits).toBe(1);
    const { env } = await import("cloudflare:test");
    const seen = async () => (await env.DB.prepare("SELECT last_seen FROM names WHERE name = ?").bind(name).first<{ last_seen: number }>())!.last_seen;
    const first = await seen();
    h.now += 600;
    await client.info(key, name);
    expect(await seen()).toBe(first);
    h.now += 3000;
    await client.info(key, name);
    expect(await seen()).toBe(h.now);
  });
});

describe("registration limits for IPv6", () => {
  it("counts every address of one /64 as the same caller", async () => {
    const h = new Harness();
    for (let i = 0; i < 5; i++) {
      h.callerIp = `2a00:1450:4001:800::${i + 1}`; // one /64, different hosts
      await h.register();
    }
    h.callerIp = "2a00:1450:4001:800:aaaa:bbbb:cccc:dddd";
    await expect(h.register()).rejects.toMatchObject({ status: 429, code: "too_many_registrations" });
    // A different /64 in the same /48 is still allowed.
    h.callerIp = "2a00:1450:4001:801::1";
    await expect(h.register()).resolves.toBeTruthy();
  });

  it("refuses with too_many_registrations when the /48 is full while the global budget is not", async () => {
    const h = new Harness();
    const { env } = await import("cloudflare:test");
    // Pre-fill the /48 counter as if 20 callers of the site registered today.
    const { registrationBuckets } = await import("../src/ip.ts");
    const { sha256Short } = await import("../src/api.ts");
    const buckets = await registrationBuckets("2001:4860:1234:1::1", sha256Short);
    const day = Math.floor(h.now / 86400);
    await env.DB.prepare("INSERT INTO limits (key, count, expires_at) VALUES (?, 20, ?)").bind(`reg:ip48:${buckets.site}:${day}`, (day + 1) * 86400).run();
    h.callerIp = "2001:4860:1234:ffff::9";
    await expect(h.register()).rejects.toMatchObject({ status: 429, code: "too_many_registrations" });
    h.callerIp = "2001:4860:1235::9"; // a different /48
    await expect(h.register()).resolves.toBeTruthy();
  });

  it("treats IPv4-mapped IPv6 like the IPv4 address", async () => {
    const h = new Harness();
    for (let i = 0; i < 5; i++) await h.register();
    h.callerIp = "::ffff:93.184.216.34";
    await expect(h.register()).rejects.toMatchObject({ status: 429 });
  });
});

describe("Content-Type on register", () => {
  it("refuses anything but application/json before doing work", async () => {
    const h = new Harness();
    const key = await generateServerKey();
    const body = JSON.stringify({ publicKey: key.publicKey });
    for (const type of ["text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", null]) {
      const headers: Record<string, string> = type ? { "content-type": type } : {};
      const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers, body: type ? body : undefined });
      expect(res.status, String(type)).toBe(415);
      expect((await bodyOf(res)).error).toBe("unsupported_media_type");
    }
    const ok = await h.fetch(`${API}/v1/register`, { method: "POST", headers: { "content-type": "Application/JSON; charset=utf-8" }, body });
    expect(ok.status).toBe(201);
  });
});

describe("body size", () => {
  it("refuses a declared Content-Length over 4 KB without reading the body", async () => {
    // Straight at readCapped: the harness re-wraps requests, which would hide whether the stream
    // was touched.
    const { readCapped } = await import("../src/body.ts");
    let pulled = false;
    const stream = new ReadableStream({ pull(c) { pulled = true; c.enqueue(new Uint8Array(10)); c.close(); } }, { highWaterMark: 0 });
    const req = new Request(`${API}/v1/register`, { method: "POST", headers: { ...JSON_HEADERS, "content-length": "5000" }, body: stream, duplex: "half" } as RequestInit);
    await expect(readCapped(req)).rejects.toMatchObject({ status: 413 });
    expect(pulled).toBe(false);
    // And end to end, a big declared body is refused.
    const h = new Harness();
    const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: "x".repeat(5000) });
    expect(res.status).toBe(413);
  });

  it("stops reading a chunked body without a length once it passes 4 KB", async () => {
    const h = new Harness();
    let chunks = 0;
    const stream = new ReadableStream({
      pull(c) {
        chunks++;
        c.enqueue(new Uint8Array(1024).fill(32));
        if (chunks >= 1000) c.close();
      },
    });
    const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: stream, duplex: "half" } as RequestInit);
    expect(res.status).toBe(413);
    expect(chunks).toBeLessThan(20);
  });

  it("applies to signed calls too, before the database is asked anything", async () => {
    const h = new Harness();
    const { key, name } = await h.register();
    const path = `/v1/names/${name}/acme`;
    const init = await signRequest(key, "PUT", path, { value: "a".repeat(5000) }, h.now);
    const res = await h.fetch(API + path, init);
    expect(res.status).toBe(413);
  });
});

describe("nonce expiry", () => {
  it("keeps a nonce strictly longer than the timestamp it was accepted with stays valid", async () => {
    const h = new Harness();
    const { key, name } = await h.register();
    const path = `/v1/names/${name}`;
    const ts = h.now;
    const init = await signRequest(key, "GET", path, undefined, ts);
    expect((await h.fetch(API + path, init)).status).toBe(200);
    // At the very last accepted second the prune must not have forgotten it.
    h.now = ts + 300;
    await h.maintenance();
    const replay = await h.fetch(API + path, init);
    expect(replay.status).toBe(401);
    expect((await bodyOf(replay)).error).toBe("replayed");
    // One second later the timestamp itself is refused, and the nonce row may go.
    h.now = ts + 301;
    await h.maintenance();
    const late = await h.fetch(API + path, init);
    expect((await bodyOf(late)).error).toBe("expired");
    const { env } = await import("cloudflare:test");
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM nonces").first<{ n: number }>())!.n).toBe(0);
  });
});

describe("client key storage", () => {
  it("exports the private key as PKCS#8 and signs with the re-imported copy", async () => {
    const h = new Harness();
    const key = await generateServerKey();
    expect(key.privateKey.extractable).toBe(true);
    const pkcs8 = await exportPrivateKey(key);
    expect(pkcs8.length).toBe(48); // the fixed size of an Ed25519 PKCS#8 key
    const loaded = await importServerKey(pkcs8, key.publicKey);

    const client = h.client();
    const reg = await client.register(key.publicKey);
    // The Worker verifies the signature against the public key registered from the original.
    const info = await client.info(loaded, reg.name);
    expect(info.name).toBe(reg.name);
    // Same bytes out again: a full round trip.
    expect(await exportPrivateKey(loaded)).toEqual(pkcs8);
  });
});
