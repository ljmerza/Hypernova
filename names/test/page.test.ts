import { describe, expect, it } from "vitest";
import { Harness } from "./harness.ts";

describe("the apex page", () => {
  it("serves a static page at https://hypernova.observer/ with no cookies and a strict CSP", async () => {
    const h = new Harness();
    const res = await h.fetch("https://hypernova.observer/");
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/html");
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(res.headers.get("content-security-policy")).toContain("default-src 'none'");
    const html = await res.text();
    expect(html).toContain("https://github.com/prudnik-web/Hypernova");
    expect(html).toContain("Claude Code");
    expect(html).not.toContain("<script");
    expect(html.length).toBeLessThan(8000);
  });

  it("shows nothing else on the apex, and no page on the API host", async () => {
    const h = new Harness();
    expect((await h.fetch("https://hypernova.observer/v1/register", { method: "POST" })).status).toBe(404);
    expect((await h.fetch("https://hypernova.observer/admin")).status).toBe(404);
    const api = await h.fetch("https://api.hypernova.observer/");
    expect(api.headers.get("content-type")).toContain("application/json");
  });
});
