import { describe, expect, it } from "vitest";
import { HEADER_NONCE, HEADER_SIGNATURE, HEADER_TIMESTAMP, generateServerKey, randomNonce, signRequest } from "../client.ts";
import { API, Harness, bodyOf } from "./harness.ts";

describe("signed requests", () => {
  it("accepts a request signed by the name's key", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    const info = await client.info(key, name);
    expect(info.name).toBe(name);
    expect(info.public).toBeNull();
  });

  it("refuses a request signed by another key", async () => {
    const h = new Harness();
    const { name, client } = await h.register();
    const other = await generateServerKey();
    await expect(client.info(other, name)).rejects.toMatchObject({ status: 401, code: "bad_signature" });
  });

  it("refuses an unsigned request", async () => {
    const h = new Harness();
    const { name } = await h.register();
    const res = await h.fetch(`${API}/v1/names/${name}`);
    expect(res.status).toBe(401);
    expect((await bodyOf(res)).error).toBe("unsigned");
  });

  it("refuses a replayed request (same nonce twice)", async () => {
    const h = new Harness();
    const { key, name } = await h.register();
    const path = `/v1/names/${name}`;
    const init = await signRequest(key, "GET", path, undefined, h.now, randomNonce());
    expect((await h.fetch(API + path, init)).status).toBe(200);
    const again = await h.fetch(API + path, init);
    expect(again.status).toBe(401);
    expect((await bodyOf(again)).error).toBe("replayed");
  });

  it("refuses a timestamp outside the 5 minute window, in either direction", async () => {
    const h = new Harness();
    const { key, name } = await h.register();
    const path = `/v1/names/${name}`;
    for (const skew of [-301, 301]) {
      const init = await signRequest(key, "GET", path, undefined, h.now + skew);
      const res = await h.fetch(API + path, init);
      expect(res.status, `skew ${skew}`).toBe(401);
      expect((await bodyOf(res)).error).toBe("expired");
    }
    for (const skew of [-299, 299]) {
      const init = await signRequest(key, "GET", path, undefined, h.now + skew);
      expect((await h.fetch(API + path, init)).status, `skew ${skew}`).toBe(200);
    }
  });

  it("binds the signature to the method, the path and the body", async () => {
    const h = new Harness();
    const { key, name } = await h.register();
    const path = `/v1/names/${name}/acme`;
    const good = await signRequest(key, "PUT", path, { value: "abc" }, h.now);

    // Same headers, different body.
    const tampered = await h.fetch(API + path, { ...good, body: JSON.stringify({ value: "xyz" }) });
    expect(tampered.status).toBe(401);
    // Same headers and body, different method.
    const wrongMethod = await h.fetch(API + path, { ...good, method: "DELETE" });
    expect(wrongMethod.status).toBe(401);
    // Signature made for one name used on another.
    const other = await h.register();
    const wrongPath = await h.fetch(`${API}/v1/names/${other.name}/acme`, good);
    expect(wrongPath.status).toBe(401);
    // The original still works (its nonce was never accepted).
    expect((await h.fetch(API + path, good)).status).toBe(200);
  });

  it("refuses malformed signature headers without touching the key", async () => {
    const h = new Harness();
    const { key, name } = await h.register();
    const path = `/v1/names/${name}`;
    const good = await signRequest(key, "GET", path, undefined, h.now);
    const headers = good.headers as Record<string, string>;
    const cases: [string, string, string][] = [
      [HEADER_TIMESTAMP, "yesterday", "bad_timestamp"],
      [HEADER_NONCE, "short", "bad_nonce"],
      [HEADER_SIGNATURE, "not-base64!!", "bad_signature"],
    ];
    for (const [header, value, code] of cases) {
      const res = await h.fetch(API + path, { ...good, headers: { ...headers, [header]: value } });
      expect(res.status, header).toBe(401);
      expect((await bodyOf(res)).error).toBe(code);
    }
  });

  it("answers 404 for an unknown name and refuses names of the wrong shape", async () => {
    const h = new Harness();
    const key = await generateServerKey();
    for (const name of ["abcdefghij", "short", "UPPERCASE1"]) {
      const path = `/v1/names/${name}`;
      const res = await h.fetch(API + path, await signRequest(key, "GET", path, undefined, h.now));
      expect(res.status, name).toBe(404);
    }
  });

  it("rate-limits signed calls per name (30 an hour in the test env), counting only valid ones", async () => {
    const h = new Harness();
    const { key, name, client } = await h.register();
    const other = await generateServerKey();
    // Forged attempts do not count against the owner.
    for (let i = 0; i < 10; i++) await expect(client.info(other, name)).rejects.toMatchObject({ status: 401 });
    for (let i = 0; i < 30; i++) await client.info(key, name);
    await expect(client.info(key, name)).rejects.toMatchObject({ status: 429, code: "rate_limited" });
    h.now += 3600;
    await expect(client.info(key, name)).resolves.toBeTruthy();
  });
});
