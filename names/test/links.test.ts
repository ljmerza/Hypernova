import { describe, expect, it } from "vitest";
import { env } from "cloudflare:test";
import { Harness } from "./harness.ts";
import { ANDROID_PACKAGE, assetLinks, certFingerprints, linkPage, scriptSha256 } from "../src/links.ts";

// The debug key the debug APK is signed with (~/.android/debug.keystore on the build Mac).
const DEBUG_KEY = "AD:02:C8:A9:F1:19:BE:D5:56:5F:9D:B5:2C:DD:EA:13:85:F9:B0:F4:7A:52:1E:38:2E:F4:3A:BD:5F:6A:82:8B";
// The release key's (docs/RELEASING.md): the APK people install.
const RELEASE_KEY = "FC:07:0D:B2:14:89:38:DF:C1:80:A6:01:DC:62:42:1E:AD:8E:F1:0D:98:B5:A0:60:6E:C9:20:89:A5:92:6A:2A";

describe("Android App Links: /.well-known/assetlinks.json", () => {
  it("names the app's package and the signing keys from the Worker's var", async () => {
    const h = new Harness();
    const res = await h.fetch("https://hypernova.observer/.well-known/assetlinks.json");
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("application/json");
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(res.redirected).toBe(false);
    const body = await res.json();
    expect(body).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: { namespace: "android_app", package_name: "com.prudnikweb.hypernova", sha256_cert_fingerprints: [DEBUG_KEY, RELEASE_KEY] },
      },
    ]);
    expect(ANDROID_PACKAGE).toBe("com.prudnikweb.hypernova");
    // The var the deploy uses is the one tests read (wrangler.jsonc).
    expect(certFingerprints(env.ANDROID_CERT_FINGERPRINTS)).toEqual([DEBUG_KEY, RELEASE_KEY]);
  });

  it("takes a list (a release key next to the debug one), plain hex too, and leaves out anything else", () => {
    const release = "12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0";
    expect(certFingerprints(`${DEBUG_KEY}, ${release.toLowerCase()}`)).toEqual([DEBUG_KEY, release]);
    expect(certFingerprints(`${DEBUG_KEY.replaceAll(":", "")} ${DEBUG_KEY}`)).toEqual([DEBUG_KEY]);
    expect(certFingerprints([DEBUG_KEY, "AB:CD", 7, "not a key"])).toEqual([DEBUG_KEY]);
    expect(certFingerprints(undefined)).toEqual([]);
    expect(certFingerprints("")).toEqual([]);
  });

  it("is a 404 when no key is set, and only on the apex", async () => {
    expect(assetLinks([]).status).toBe(404);
    const h = new Harness();
    expect((await h.fetch("https://hypernova.observer/.well-known/assetlinks.json", { method: "POST" })).status).toBe(404);
    const api = await h.fetch("https://api.hypernova.observer/.well-known/assetlinks.json");
    expect(api.status).toBe(404);
    expect(api.headers.get("content-type")).toContain("application/json");
  });
});

describe("the link pages: /pair and /glasses", () => {
  it("are static, with no cookies and a CSP that allows no connection", async () => {
    const h = new Harness();
    for (const path of ["/pair", "/glasses"]) {
      const res = await h.fetch(`https://hypernova.observer${path}`);
      expect(res.status, path).toBe(200);
      expect(res.headers.get("content-type")).toContain("text/html");
      expect(res.headers.get("set-cookie")).toBeNull();
      expect(res.headers.get("referrer-policy")).toBe("no-referrer");
      expect(res.headers.get("x-robots-tag")).toBe("noindex");
      const csp = res.headers.get("content-security-policy")!;
      expect(csp).toContain("default-src 'none'");
      expect(csp).not.toContain("connect-src");
      expect(csp).not.toContain("img-src");
      expect(csp).toContain("form-action 'none'");
      expect(csp).toContain("frame-ancestors 'none'");
      // The one script, by its hash: nothing else may run, nothing may load.
      expect(csp).toContain(`script-src 'sha256-${await scriptSha256()}'`);
      const scriptSrc = csp.split(";").find((d) => d.trim().startsWith("script-src"))!;
      expect(scriptSrc).not.toContain("unsafe");
      const html = await res.text();
      expect(html).toContain("Open Hypernova");
      expect(html).toContain("No Hypernova on this phone yet?");
      expect(html.length).toBeLessThan(12_000);
    }
  });

  it("the script's hash in the CSP is the script on the page", async () => {
    const res = await linkPage("pair", "hypernova.observer");
    const html = await res.text();
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
    expect(scripts).toHaveLength(1);
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(scripts[0]));
    const b64 = btoa(String.fromCharCode(...new Uint8Array(digest)));
    expect(res.headers.get("content-security-policy")).toContain(`'sha256-${b64}'`);
  });

  it("use the fragment only in the page: nothing reaches out, nothing of a request comes back", async () => {
    const h = new Harness();
    const plain = await (await h.fetch("https://hypernova.observer/pair")).text();
    // A query (which would reach the server) changes nothing: the page never reflects a request.
    const withQuery = await (await h.fetch("https://hypernova.observer/pair?c=SECRETCODE&u=https%3A%2F%2Fevil.example&n=%3Cb%3Ehi")).text();
    expect(withQuery).toBe(plain);
    expect(plain).not.toContain("SECRETCODE");
    // The script reads location.hash and sets one link; it has no way to send anything anywhere.
    const script = plain.match(/<script>([\s\S]*?)<\/script>/)![1];
    expect(script).toContain("location.hash");
    // Read once, the details leave the address bar and the history entry.
    expect(script).toContain('history.replaceState(null, "", location.pathname)');
    for (const reach of ["fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "EventSource", "new Image", "importScripts", "document.cookie", "localStorage", "sessionStorage", "innerHTML", "document.write", "postMessage", ".src"]) {
      expect(script, reach).not.toContain(reach);
    }
    // Nothing loaded from anywhere: no images, styles, fonts, frames or forms.
    for (const tag of ["<img", "<link", "<iframe", "<form", "<object", "<embed", "src=", "@import", "url("]) expect(plain, tag).not.toContain(tag);
    // The app's own package, and the way back to this page when the app isn't there.
    expect(script).toContain("package=com.prudnikweb.hypernova");
    expect(script).toContain("S.browser_fallback_url=");
    expect(script).toContain('scheme=hypernova');
    // Only the keys the app knows go on to it.
    expect(script).toContain('["v", "u", "c", "n", "fp", "lan", "lp", "ts", "ti", "ta", "tc"]');
  });

  it("say where the details are and that nothing is tracked", async () => {
    const pair = await (await linkPage("pair", "hypernova.observer")).text();
    expect(pair).toContain("after the #, which a browser never sends to a server");
    expect(pair).toContain("no cookies and no analytics");
    // Details that don't hold up are said as such; none at all (typed in, or the tab reloaded after
    // they were read and taken out of the address) says how to pair.
    expect(pair).toContain('class="missing-note"');
    expect(pair).toContain('class="empty-note"');
    expect(pair).toContain('location.hash.length > 1 ? "missing" : "empty"');
    const glasses = await (await linkPage("glasses", "hypernova.observer")).text();
    expect(glasses).toContain("Settings → Glasses");
    expect(glasses).toContain('data-kind="glasses"');
  });

  it("are only GET and HEAD, only these paths, and only on the apex", async () => {
    const h = new Harness();
    expect((await h.fetch("https://hypernova.observer/pair", { method: "POST" })).status).toBe(404);
    expect((await h.fetch("https://hypernova.observer/pair", { method: "HEAD" })).status).toBe(200);
    expect((await h.fetch("https://hypernova.observer/pair/")).status).toBe(404);
    expect((await h.fetch("https://hypernova.observer/pairing")).status).toBe(404);
    expect((await h.fetch("https://api.hypernova.observer/pair")).headers.get("content-type")).toContain("application/json");
  });
});
