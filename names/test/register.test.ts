import { describe, expect, it } from "vitest";
import { generateServerKey } from "../client.ts";
import { API, Harness, JSON_HEADERS, bodyOf } from "./harness.ts";

describe("POST /v1/register", () => {
  it("hands out a random 10-character name for a valid key", async () => {
    const h = new Harness();
    const key = await generateServerKey();
    const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ publicKey: key.publicKey }) });
    expect(res.status).toBe(201);
    const body = await bodyOf(res);
    expect(body.name).toMatch(/^[a-z0-9]{10}$/);
    expect(body.fqdn).toBe(`${body.name}.hypernova.observer`);
    expect(body.lanFqdn).toBe(`l-${body.name}.hypernova.observer`);
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(res.headers.get("cache-control")).toBe("no-store");
  });

  it("gives different names to different registrations", async () => {
    const h = new Harness();
    const names = new Set<string>();
    for (let i = 0; i < 3; i++) names.add((await h.register()).name);
    expect(names.size).toBe(3);
  });

  it("refuses keys that are not raw Ed25519 public keys", async () => {
    const h = new Harness();
    for (const publicKey of ["", "not base64!", btoa("short"), btoa("x".repeat(33))]) {
      const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ publicKey }) });
      expect(res.status, publicKey).toBe(400);
      expect((await bodyOf(res)).error).toBe("bad_key");
    }
    const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: "nonsense" });
    expect(res.status).toBe(400);
    expect((await bodyOf(res)).error).toBe("bad_json");
  });

  it("limits one source address to 5 registrations a day", async () => {
    const h = new Harness();
    for (let i = 0; i < 5; i++) await h.register();
    // The client hands on how long to wait: the rest of the day.
    await expect(h.register()).rejects.toMatchObject({ status: 429, code: "too_many_registrations", retryAfter: 86400 - (h.now % 86400) });
    // Another address is not affected.
    h.callerIp = "8.8.4.4";
    await expect(h.register()).resolves.toBeTruthy();
    // The next day the first address can register again.
    h.callerIp = "93.184.216.34";
    h.now += 86400;
    await expect(h.register()).resolves.toBeTruthy();
  });

  it("limits registrations globally (REGISTER_PER_DAY is 10 in the test env)", async () => {
    const h = new Harness();
    // Three addresses take turns, so none reaches its own limit of 5 before the global 10.
    const key = await generateServerKey();
    const ips = ["1.1.1.1", "1.0.0.1", "9.9.9.9"];
    const outcomes: string[] = [];
    for (let i = 0; i < 12; i++) {
      h.callerIp = ips[i % 3];
      const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ publicKey: key.publicKey }) });
      outcomes.push(res.status === 201 ? "ok" : (await bodyOf(res)).error);
    }
    expect(outcomes.filter((o) => o === "ok").length).toBe(10);
    expect(outcomes.slice(10)).toEqual(["service_busy", "service_busy"]);
  });

  it("refuses a request that did not come through Cloudflare", async () => {
    const h = new Harness();
    h.callerIp = "";
    const key = await generateServerKey();
    const res = await h.fetch(`${API}/v1/register`, { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ publicKey: key.publicKey }) });
    expect(res.status).toBe(400);
    expect((await bodyOf(res)).error).toBe("no_caller_address");
  });

  it("answers GET / on the API host with a pointer and no page", async () => {
    const h = new Harness();
    const res = await h.fetch(`${API}/`);
    expect(res.status).toBe(200);
    expect((await bodyOf(res)).service).toBe("hypernova-names");
  });
});
