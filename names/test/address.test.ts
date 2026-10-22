import { describe, expect, it } from "vitest";
import { signRequest } from "../client.ts";
import { API, Harness, PUBLIC_IP, bodyOf } from "./harness.ts";

const DOMAIN = "hypernova.observer";

describe("PUT /v1/names/{name}/address", () => {
  it("points <name> at the caller's own address, never one from the body", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    // A body that tries to name a public address is simply ignored for the public record.
    const path = `/v1/names/${name}/address`;
    const res = await h.fetch(API + path, await signRequest(key, "PUT", path, { address: "198.51.100.1", ip: "198.51.100.1" }, h.now));
    expect(res.status).toBe(200);
    const body = await bodyOf(res);
    expect(body.public).toEqual({ type: "A", address: PUBLIC_IP });

    const recs = h.cf.byName(`${name}.${DOMAIN}`);
    expect(recs).toHaveLength(1);
    expect(recs[0]).toMatchObject({ type: "A", content: PUBLIC_IP, ttl: 60, proxied: false, comment: `hypernova-names:${name}` });
    expect(h.cf.byName(`l-${name}.${DOMAIN}`)).toHaveLength(0);

    const info = await client.info(key, name);
    expect(info.public).toEqual({ type: "A", address: PUBLIC_IP });
  });

  it("switches between A and AAAA as the caller's address changes, keeping one record", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.updateAddress(key, name);
    h.callerIp = "2001:4860:4860::8888";
    const v6 = await client.updateAddress(key, name);
    expect(v6.public).toEqual({ type: "AAAA", address: "2001:4860:4860:0:0:0:0:8888" });
    const recs = h.cf.byName(`${name}.${DOMAIN}`);
    expect(recs).toHaveLength(1);
    expect(recs[0].type).toBe("AAAA");

    // Same address again: no DNS call at all.
    const before = h.cf.calls.length;
    await client.updateAddress(key, name);
    expect(h.cf.calls.length).toBe(before);

    h.callerIp = "5.6.7.8";
    await client.updateAddress(key, name);
    expect(h.cf.byName(`${name}.${DOMAIN}`)).toHaveLength(1);
    expect(h.cf.byName(`${name}.${DOMAIN}`)[0]).toMatchObject({ type: "A", content: "5.6.7.8" });
  });

  it("refuses when the caller's address is not public", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    for (const ip of ["10.0.0.5", "192.168.1.1", "100.64.3.3", "127.0.0.1", "fe80::1", "fd00::1"]) {
      h.callerIp = ip;
      await expect(client.updateAddress(key, name), ip).rejects.toMatchObject({ status: 400, code: "caller_not_public" });
    }
    expect(h.cf.records.size).toBe(0);
  });

  it("publishes private addresses under l-<name> and refuses everything else", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    const state = await client.updateAddress(key, name, ["192.168.1.20", "fd12:3456::7", "100.100.1.2", "192.168.1.20"]);
    expect(state.lan).toEqual(["192.168.1.20", "fd12:3456:0:0:0:0:0:7", "100.100.1.2"]);
    const lan = h.cf.byName(`l-${name}.${DOMAIN}`);
    expect(lan.map((r) => [r.type, r.content]).sort()).toEqual([
      ["A", "100.100.1.2"],
      ["A", "192.168.1.20"],
      ["AAAA", "fd12:3456:0:0:0:0:0:7"],
    ]);

    for (const bad of ["8.8.8.8", "203.0.113.1", "169.254.1.1", "127.0.0.1", "fe80::1", "2001:db8::1", "::1", "0.0.0.0"]) {
      await expect(client.updateAddress(key, name, [bad]), bad).rejects.toMatchObject({ status: 400, code: "lan_not_private" });
    }
    for (const bad of ["not an ip", "192.168.1", "192.168.1.256", "fd00::1%eth0"]) {
      await expect(client.updateAddress(key, name, [bad]), bad).rejects.toMatchObject({ status: 400, code: "bad_lan" });
    }
    // A refused request changes nothing.
    expect(h.cf.byName(`l-${name}.${DOMAIN}`)).toHaveLength(3);
  });

  it("changes only the LAN records that differ, and removes them all for an empty list", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.updateAddress(key, name, ["192.168.1.20", "192.168.1.21"]);
    const firstIds = h.cf.byName(`l-${name}.${DOMAIN}`).map((r) => r.id).sort();
    const before = h.cf.calls.length;
    await client.updateAddress(key, name, ["192.168.1.21", "10.0.0.1"]);
    // One delete and one create.
    expect(h.cf.calls.slice(before).map((c) => c.method).sort()).toEqual(["DELETE", "POST"]);
    const after = h.cf.byName(`l-${name}.${DOMAIN}`);
    expect(after.map((r) => r.content).sort()).toEqual(["10.0.0.1", "192.168.1.21"]);
    expect(after.some((r) => firstIds.includes(r.id))).toBe(true);

    await client.updateAddress(key, name, []);
    expect(h.cf.byName(`l-${name}.${DOMAIN}`)).toHaveLength(0);
    expect(h.cf.byName(`${name}.${DOMAIN}`)).toHaveLength(1);
  });

  it("caps the LAN list and rejects non-array values", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await expect(client.updateAddress(key, name, ["10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5"])).rejects.toMatchObject({ code: "bad_lan" });
    const path = `/v1/names/${name}/address`;
    const res = await h.fetch(API + path, await signRequest(key, "PUT", path, { lan: "10.0.0.1" }, h.now));
    expect(res.status).toBe(400);
  });

  it("recreates the public record when it was removed at Cloudflare by hand", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.updateAddress(key, name);
    const rec = h.cf.byName(`${name}.${DOMAIN}`)[0];
    h.cf.records.delete(rec.id);
    h.callerIp = "5.6.7.8";
    await client.updateAddress(key, name);
    const recs = h.cf.byName(`${name}.${DOMAIN}`);
    expect(recs).toHaveLength(1);
    expect(recs[0].content).toBe("5.6.7.8");
  });

  it("leaves the public record out with public: false, removing one published before", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.updateAddress(key, name, ["192.168.1.20"]);
    expect(h.cf.byName(`${name}.${DOMAIN}`)).toHaveLength(1);

    const state = await client.updateAddress(key, name, ["192.168.1.20"], { public: false });
    expect(state.public).toBeNull();
    expect(state.lan).toEqual(["192.168.1.20"]);
    expect(h.cf.byName(`${name}.${DOMAIN}`)).toHaveLength(0);
    expect(h.cf.byName(`l-${name}.${DOMAIN}`)).toHaveLength(1);
    expect((await client.info(key, name)).public).toBeNull();

    // Nothing to publish from the caller, so its address does not matter (a test machine, a NAT).
    h.callerIp = "10.0.0.5";
    const before = h.cf.calls.length;
    await client.updateAddress(key, name, ["192.168.1.20"], { public: false });
    expect(h.cf.calls.length).toBe(before);

    // Asked for again, it comes back.
    h.callerIp = PUBLIC_IP;
    expect((await client.updateAddress(key, name, ["192.168.1.20"])).public).toEqual({ type: "A", address: PUBLIC_IP });
    expect(h.cf.byName(`${name}.${DOMAIN}`)).toHaveLength(1);

    const path = `/v1/names/${name}/address`;
    const res = await h.fetch(API + path, await signRequest(key, "PUT", path, { lan: [], public: "no" }, h.now));
    expect(res.status).toBe(400);
    expect((await bodyOf(res)).error).toBe("bad_public");
  });

  it("reports a DNS provider failure as 502 without inventing state", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    h.cf.failNext = 500;
    await expect(client.updateAddress(key, name)).rejects.toMatchObject({ status: 502, code: "dns_upstream" });
    expect((await client.info(key, name)).public).toBeNull();
  });
});
