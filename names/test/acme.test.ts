import { describe, expect, it } from "vitest";
import { signRequest } from "../client.ts";
import { API, Harness, bodyOf } from "./harness.ts";

const DOMAIN = "hypernova.observer";
const V1 = "gZ3nBq0Dq7H3a2qT0Zz1l9s-X4bK8w2cJ0mNfR7vQ5E";
const V2 = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v";

describe("ACME challenge records", () => {
  it("publishes a TXT for <name>, another for l-<name>, and removes them one by one", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    const main = await client.setAcme(key, name, V1);
    expect(main.txt).toEqual([{ host: "main", value: V1, expiresAt: h.now + 3600 }]);
    const both = await client.setAcme(key, name, V2, "lan");
    expect(both.txt.map((t) => t.host)).toEqual(["main", "lan"]);

    const mainRecs = h.cf.byName(`_acme-challenge.${name}.${DOMAIN}`);
    expect(mainRecs).toHaveLength(1);
    expect(mainRecs[0]).toMatchObject({ type: "TXT", content: V1, ttl: 60, comment: `hypernova-names:${name}` });
    expect(h.cf.byName(`_acme-challenge.l-${name}.${DOMAIN}`)[0]).toMatchObject({ type: "TXT", content: V2 });

    const afterOne = await client.clearAcme(key, name, V1);
    expect(afterOne.txt).toEqual([{ host: "lan", value: V2, expiresAt: h.now + 3600 }]);
    expect(h.cf.byName(`_acme-challenge.${name}.${DOMAIN}`)).toHaveLength(0);

    const afterAll = await client.clearAcme(key, name);
    expect(afterAll.txt).toEqual([]);
    expect(h.cf.records.size).toBe(0);
  });

  it("restarts the hour when the same value is published again", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.setAcme(key, name, V1);
    h.now += 1800;
    const again = await client.setAcme(key, name, V1);
    expect(again.txt).toHaveLength(1);
    expect(again.txt[0].expiresAt).toBe(h.now + 3600);
    expect(h.cf.byName(`_acme-challenge.${name}.${DOMAIN}`)).toHaveLength(1);
  });

  it("allows at most 4 challenge records at a time", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.setAcme(key, name, V1);
    await client.setAcme(key, name, V2);
    await client.setAcme(key, name, V1, "lan");
    await client.setAcme(key, name, V2, "lan");
    await expect(client.setAcme(key, name, "one-more")).rejects.toMatchObject({ status: 409, code: "too_many_challenges" });
    await client.clearAcme(key, name, V1, "lan");
    await expect(client.setAcme(key, name, "one-more")).resolves.toBeTruthy();
  });

  it("removes challenge records an hour after they were published", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.setAcme(key, name, V1);
    h.now += 1800;
    await client.setAcme(key, name, V2, "lan");
    h.now += 1801; // the first is now past its hour, the second is not
    await h.maintenance();
    expect(h.cf.byName(`_acme-challenge.${name}.${DOMAIN}`)).toHaveLength(0);
    expect(h.cf.byName(`_acme-challenge.l-${name}.${DOMAIN}`)).toHaveLength(1);
    expect((await client.info(key, name)).txt.map((t) => t.host)).toEqual(["lan"]);
    h.now += 1800;
    await h.maintenance();
    expect(h.cf.records.size).toBe(0);
  });

  it("validates the value and the host", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await expect(client.setAcme(key, name, "")).rejects.toMatchObject({ code: "bad_value" });
    await expect(client.setAcme(key, name, 'quoted"value')).rejects.toMatchObject({ code: "bad_value" });
    await expect(client.setAcme(key, name, "x".repeat(256))).rejects.toMatchObject({ code: "bad_value" });
    const path = `/v1/names/${name}/acme`;
    const res = await h.fetch(API + path, await signRequest(key, "PUT", path, { value: V1, host: "wildcard" }, h.now));
    expect(res.status).toBe(400);
    expect((await bodyOf(res)).error).toBe("bad_host");
    expect(h.cf.records.size).toBe(0);
  });

  it("caps the day's first-ever challenges for everyone (429 + Retry-After); a name that published before is exempt; one name has a weekly budget", async () => {
    const h = new Harness();
    // ACME_FIRST_PER_DAY is 3 in the test env: three names get their first challenge.
    const a = await h.register();
    const b = await h.register();
    const c = await h.register();
    const d = await h.register();
    await a.client.setAcme(a.key, a.name, V1);
    await b.client.setAcme(b.key, b.name, V1);
    await c.client.setAcme(c.key, c.name, V1);
    await expect(d.client.setAcme(d.key, d.name, V1)).rejects.toMatchObject({ status: 429, code: "acme_busy" });
    // Renewals are exempt: a name that published before still gets its challenge today.
    await a.client.clearAcme(a.key, a.name);
    await expect(a.client.setAcme(a.key, a.name, V2)).resolves.toBeTruthy();
    // Tomorrow the new name goes through.
    h.now += 86400;
    await expect(d.client.setAcme(d.key, d.name, V1)).resolves.toBeTruthy();
  });

  it("limits one name's new challenges per week (ACME_PER_NAME_PER_WEEK is 5 in the test env)", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    for (let i = 0; i < 5; i++) {
      await client.setAcme(key, name, `value-${i}`);
      await client.clearAcme(key, name);
    }
    await expect(client.setAcme(key, name, "value-5")).rejects.toMatchObject({ status: 429, code: "acme_name_limit" });
    h.now += 7 * 86400;
    await expect(client.setAcme(key, name, "value-5")).resolves.toBeTruthy();
  });
});
