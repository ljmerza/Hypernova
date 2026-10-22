import { describe, expect, it } from "vitest";
import { env } from "cloudflare:test";
import { Harness } from "./harness.ts";

const DOMAIN = "hypernova.observer";
const DAY = 86400;

describe("the hourly maintenance", () => {
  it("releases names not seen for 90 days, with their records, and keeps the live ones", async () => {
    const h = new Harness();
    const idle = await h.register();
    await idle.client.updateAddress(idle.key, idle.name, ["10.0.0.1"]);
    const live = await h.register();
    await live.client.updateAddress(live.key, live.name);

    h.now += 89 * DAY;
    await live.client.info(live.key, live.name); // the live one checks in
    await h.maintenance();
    expect(h.cf.byName(`${idle.name}.${DOMAIN}`)).toHaveLength(1);

    h.now += 1 * DAY + 1;
    await h.maintenance();
    expect(h.cf.byName(`${idle.name}.${DOMAIN}`)).toHaveLength(0);
    expect(h.cf.byName(`l-${idle.name}.${DOMAIN}`)).toHaveLength(0);
    expect(h.cf.byName(`${live.name}.${DOMAIN}`)).toHaveLength(1);
    await expect(idle.client.info(idle.key, idle.name)).rejects.toMatchObject({ status: 404 });
    await expect(live.client.info(live.key, live.name)).resolves.toBeTruthy();
  });

  it("every signed call counts as being seen", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    h.now += 60 * DAY;
    await client.setAcme(key, name, "value");
    h.now += 60 * DAY;
    await h.maintenance();
    await expect(client.info(key, name)).resolves.toBeTruthy();
  });

  it("removes tagged records the database does not know, and never touches foreign ones", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.updateAddress(key, name);
    const ours = h.cf.byName(`${name}.${DOMAIN}`)[0];
    const foreign = h.cf.seedForeign("A", `www.${DOMAIN}`, "192.0.2.1");
    const foreignTagged = h.cf.seedForeign("A", `x.${DOMAIN}`, "192.0.2.2", "something else entirely");
    const oldOrphan = h.cf.seedOrphan("zzzzzzzzz1", 2 * 3600);
    const freshOrphan = h.cf.seedOrphan("zzzzzzzzz2", 60);

    await h.maintenance();
    const left = [...h.cf.records.keys()].sort();
    expect(left).toEqual([ours.id, foreign.id, foreignTagged.id, freshOrphan.id].sort());
    expect(h.cf.records.has(oldOrphan.id)).toBe(false);
  });

  it("prunes old nonces and counters", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    await client.info(key, name);
    const count = async (table: string) => (await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>())!.n;
    expect(await count("nonces")).toBe(1);
    expect(await count("limits")).toBeGreaterThan(0);
    h.now += 2 * DAY;
    await h.maintenance();
    expect(await count("nonces")).toBe(0);
    expect(await count("limits")).toBe(0);
  });
});
