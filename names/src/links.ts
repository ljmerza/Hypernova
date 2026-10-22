// Android App Links on the apex (docs/PROTOCOL.md, "Pairing"): the Mac's pairing QR code carries
// `https://<domain>/pair#v=1&u=…&c=…`, and Android opens such a link straight in the Hypernova app
// once it has checked `/.well-known/assetlinks.json` here. The pairing details are in the fragment,
// which a browser never sends to any server, so none of them ever reaches this Worker.
//
// When the link lands in a browser instead (the app isn't installed, Android hasn't verified the
// domain yet, or a scanner opened it in its own view), `/pair` and `/glasses` are a small static
// page that offers "Open Hypernova" (an intent:// link for Chrome, built in the page from the
// fragment) and says how to get the app from the computer. The page has no cookies and nothing to
// track, and its Content Security Policy allows only its own inline style and its one inline script
// (by hash): no connection anywhere (no connect-src, nothing loaded), no frames, no forms.

/** The Android app's package (android/app/build.gradle.kts, applicationId). */
export const ANDROID_PACKAGE = "com.prudnikweb.hypernova";

/** A SHA-256 certificate fingerprint as assetlinks.json takes it: 32 bytes in hex, colon-separated, upper case. */
const FINGERPRINT = /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/;

/**
 * The signing certificates' fingerprints from the Worker's `ANDROID_CERT_FINGERPRINTS` var: a
 * comma- or space-separated list (or a JSON array), so a release key can join the debug one. Anything
 * that isn't a SHA-256 fingerprint is left out; plain hex is given its colons.
 */
export function certFingerprints(value: unknown): string[] {
  const items = Array.isArray(value) ? value : typeof value === "string" ? value.split(/[\s,]+/) : [];
  const out: string[] = [];
  for (const item of items) {
    if (typeof item !== "string") continue;
    let fp = item.trim().toUpperCase();
    if (/^[0-9A-F]{64}$/.test(fp)) fp = fp.match(/../g)!.join(":");
    if (FINGERPRINT.test(fp) && !out.includes(fp)) out.push(fp);
  }
  return out;
}

/** `/.well-known/assetlinks.json`: this domain's https links may open the app signed with these keys. 404 when none is set. */
export function assetLinks(fingerprints: string[]): Response {
  if (!fingerprints.length) return notFound();
  const body = [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: { namespace: "android_app", package_name: ANDROID_PACKAGE, sha256_cert_fingerprints: fingerprints },
    },
  ];
  return new Response(JSON.stringify(body, null, 2) + "\n", {
    status: 200,
    headers: {
      "content-type": "application/json",
      "cache-control": "public, max-age=3600",
      "x-content-type-options": "nosniff",
    },
  });
}

export type LinkKind = "pair" | "glasses";

/**
 * The page's one script. It reads the fragment (only here, in the page: it never travels), keeps
 * the keys the app knows, takes them out of the address bar (and the history entry), and points
 * "Open Hypernova" at an intent:// link Chrome hands to the app (with this page's `#get` as the way
 * back when the app isn't there). It reaches nothing: the CSP allows no connection, and the script
 * only sets an href and a class.
 */
const SCRIPT = `(function () {
  var KEYS = ["v", "u", "c", "n", "fp", "lan", "lp", "ts", "ti", "ta", "tc"];
  var root = document.documentElement;
  var kind = root.getAttribute("data-kind");
  var open = document.getElementById("open");
  var android = /Android/i.test(navigator.userAgent);
  function dec(s) { try { return decodeURIComponent(s.replace(/\\+/g, " ")); } catch (e) { return null; } }
  function details(hash) {
    var q = {};
    var parts = hash.replace(/^#/, "").split("&");
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i], at = p.indexOf("=");
      if (!p || at < 1) continue;
      var k = dec(p.slice(0, at)), v = dec(p.slice(at + 1));
      if (k === null || v === null || KEYS.indexOf(k) < 0 || Object.prototype.hasOwnProperty.call(q, k)) continue;
      q[k] = v;
    }
    return q;
  }
  function query(q) {
    var out = [];
    for (var i = 0; i < KEYS.length; i++) if (Object.prototype.hasOwnProperty.call(q, KEYS[i])) out.push(KEYS[i] + "=" + encodeURIComponent(q[KEYS[i]]));
    return out.join("&");
  }
  var link = null;
  if (kind === "pair") {
    var q = details(location.hash);
    var ok = (q.v === undefined || q.v === "1") && /^https?:\\/\\/[^\\s]+$/i.test(q.u || "") && /^[A-Za-z0-9_-]{1,128}$/.test(q.c || "");
    // No details at all (typed in, or the tab reloaded after they were read and taken out of the
    // address): how to pair; details that don't hold up: say so.
    if (ok) link = "pair?" + query(q);
    else if (location.hash !== "#get") root.className = location.hash.length > 1 ? "missing" : "empty";
    // Read once, the details leave the address (and so the history entry): they go only to the app.
    if (location.hash && location.hash !== "#get") { try { history.replaceState(null, "", location.pathname); } catch (e) {} }
  } else {
    link = "glasses";
  }
  if (location.hash === "#get") root.className = "get";
  window.addEventListener("hashchange", function () { if (location.hash === "#get") root.className = "get"; });
  if (!link) return;
  var back = location.origin + location.pathname + "#get";
  var intent = "intent://" + link + "#Intent;scheme=hypernova;package=${ANDROID_PACKAGE};S.browser_fallback_url=" + encodeURIComponent(back) + ";end";
  open.setAttribute("href", android ? intent : "hypernova://" + link);
  open.removeAttribute("aria-disabled");
  // Chrome on Android goes on to the app by itself; a tap on the button does the same.
  if (android && location.hash !== "#get") setTimeout(function () { location.href = intent; }, 300);
})();`;

let scriptHash: Promise<string> | null = null;

/** The CSP's hash of the inline script, worked out once. */
export function scriptSha256(): Promise<string> {
  scriptHash ??= crypto.subtle.digest("SHA-256", new TextEncoder().encode(SCRIPT)).then((d) => {
    let bin = "";
    for (const b of new Uint8Array(d)) bin += String.fromCharCode(b);
    return btoa(bin);
  });
  return scriptHash;
}

const WORDS: Record<LinkKind, { title: string; heading: string; lede: string; missing: string; empty: string }> = {
  pair: {
    title: "Pair with Hypernova",
    heading: "Pair this phone.",
    lede: "Open Hypernova on this phone to finish pairing with your computer.",
    missing: "This link has lost its pairing details. Scan the code on your computer again.",
    empty: "Scan the pairing code on your computer with this phone’s camera: it opens Hypernova.",
  },
  glasses: {
    title: "Open Hypernova",
    heading: "Hypernova Lens.",
    lede: "The glasses’ access and settings are in Hypernova on this phone: Settings → Glasses.",
    missing: "",
    empty: "",
  },
};

/** `/pair` and `/glasses`: the same static page for everyone; what it opens comes from the fragment, in the page. */
export async function linkPage(kind: LinkKind, domain: string): Promise<Response> {
  const w = WORDS[kind];
  const html = `<!doctype html>
<html lang="en" data-kind="${kind}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="dark">
<meta name="robots" content="noindex">
<meta name="referrer" content="no-referrer">
<title>${w.title}</title>
<style>
:root{--bg:#14110E;--bg-900:#1B1814;--line:#2C2823;--ink:#F5F2EB;--muted:#B5B0A6;--dim:#7A7468;--accent:#7FFFA0;--accent-dim:#4A8C5E;--amber:#FFC46B;
--sans:Manrope,ui-sans-serif,system-ui,sans-serif;--mono:"JetBrains Mono",ui-monospace,"SF Mono",Menlo,monospace}
*{box-sizing:border-box}
html,body{margin:0;min-height:100%;background:var(--bg)}
body{color:var(--ink);font:500 16px/1.55 var(--sans);
background:radial-gradient(1.5px 1.5px at 14% 16%,#fff 60%,transparent 61%),radial-gradient(1px 1px at 78% 11%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 86% 47%,#fff 60%,transparent 61%),radial-gradient(1px 1px at 9% 82%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 52% 91%,#fff 60%,transparent 61%),radial-gradient(520px 380px at 85% 0%,rgba(127,255,160,.07),transparent 70%),var(--bg);
min-height:100vh;display:grid;place-items:center;padding:24px 16px}
main{max-width:440px;width:100%}
.seam{display:flex;align-items:center;gap:12px;color:var(--dim);font-size:10px;letter-spacing:.18em;text-transform:uppercase}
.seam::after{content:"";flex:1;height:1px;background:var(--line)}
.dot{width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 12px var(--accent)}
h1{font-weight:200;font-size:34px;line-height:1.15;letter-spacing:-.02em;margin:18px 0 8px}
p{color:var(--muted);margin:0 0 14px}
.open{display:block;text-align:center;text-decoration:none;font-weight:600;border-radius:999px;padding:15px 20px;margin:22px 0 0;background:var(--accent);color:var(--bg)}
.open[aria-disabled]{opacity:.35;pointer-events:none}
.card{margin-top:22px;padding:16px 18px;border:1px solid var(--line);border-radius:18px;background:rgba(27,24,20,.84)}
.card h2{font-size:15px;font-weight:600;margin:0 0 6px;color:var(--ink)}
.card p{font-size:14px;margin:0 0 8px}.card p:last-child{margin:0}
code{font:500 13px var(--mono);color:var(--accent);background:var(--bg-900);padding:2px 6px;border-radius:6px}
.missing-note,.empty-note{display:none;color:var(--amber)}.empty-note{color:var(--ink)}
.missing .missing-note,.empty .empty-note{display:block}.missing .open,.empty .open{display:none}
.get .app{border-color:var(--accent-dim)}
footer{margin-top:24px;color:var(--dim);font-size:12px;line-height:1.5}
</style>
</head>
<body>
<main>
<div class="seam"><span class="dot"></span>Hypernova</div>
<h1>${w.heading}</h1>
<p>${w.lede}</p>
${w.missing ? `<p class="missing-note">${w.missing}</p>\n<p class="empty-note">${w.empty}</p>\n` : ""}<a id="open" class="open" href="#" aria-disabled="true">Open Hypernova</a>
<div class="card app">
<h2>No Hypernova on this phone yet?</h2>
<p>Get it from your computer. On a Mac: click Hypernova in the menu bar, choose <em>No app on it yet? Get Hypernova for Android</em>, and scan that code with this phone. On a Linux server, <code>hypernova pair</code> says where to get it.</p>
<p>Once it’s installed, scan the code on your computer again.</p>
</div>
<footer>${kind === "pair" ? "The pairing details are in this link after the #, which a browser never sends to a server: they stay on this phone and go only to the app. " : ""}This page has no cookies and no analytics, and it can’t connect anywhere. ${domain} only gives Hypernova servers their names.</footer>
</main>
<script>${SCRIPT}</script>
</body>
</html>
`;
  return new Response(html, {
    status: 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "public, max-age=3600",
      "content-security-policy": `default-src 'none'; script-src 'sha256-${await scriptSha256()}'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`,
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
      "x-robots-tag": "noindex",
    },
  });
}

function notFound(): Response {
  return new Response("Not found", { status: 404, headers: { "content-type": "text/plain", "cache-control": "no-store" } });
}
