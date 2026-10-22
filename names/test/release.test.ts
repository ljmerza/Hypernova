import { describe, expect, it } from "vitest";
import { API, Harness, bodyOf } from "./harness.ts";

const DOMAIN = "hypernova.observer";

describe("DELETE /v1/names/{name}", () => {
  it("removes every record of the name and forgets it", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    const foreign = h.cf.seedForeign("A", `api.${DOMAIN}`, "192.0.2.1");
    await client.updateAddress(key, name, ["192.168.0.2"]);
    await client.setAcme(key, name, "challenge-value");
    expect(h.cf.records.size).toBe(4);

    await client.release(key, name);
    expect([...h.cf.records.keys()]).toEqual([foreign.id]);
    await expect(client.info(key, name)).rejects.toMatchObject({ status: 404 });
    // The name is simply unknown afterwards, signed or not.
    const res = await h.fetch(`${API}/v1/names/${name}`);
    expect(res.status).toBe(404);
    expect((await bodyOf(res)).error).toBe("unknown_name");
  });

  it("only the holder of the key can release", async () => {
    const h = new Harness();
    const a = await h.register();
    const b = await h.register();
    await a.client.updateAddress(a.key, a.name);
    await expect(b.client.release(b.key, a.name)).rejects.toMatchObject({ status: 401 });
    expect(h.cf.byName(`${a.name}.${DOMAIN}`)).toHaveLength(1);
  });
});
