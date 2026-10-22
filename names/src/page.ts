// The static page at the apex (https://hypernova.observer/). One self-contained HTML document in
// the Hyper cosmos look: warm near-black, one mint accent, a still starfield drawn with CSS so
// the page needs no script, no fonts from elsewhere, no cookies and nothing to track. The Content
// Security Policy forbids everything except the inline style, so even an injected tag could not
// load anything.

const REPO = "https://github.com/prudnik-web/Hypernova";

export function apexPage(domain: string): Response {
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<title>Hypernova</title>
<meta name="description" content="Hypernova is a self-hosted control room for Claude Code. ${domain} gives each Hypernova server a name.">
<style>
:root{--bg:#14110E;--bg-900:#1B1814;--line:#2C2823;--ink:#F5F2EB;--muted:#B5B0A6;--dim:#7A7468;--accent:#7FFFA0;--accent-dim:#4A8C5E;
--sans:Manrope,ui-sans-serif,system-ui,sans-serif;--mono:"JetBrains Mono",ui-monospace,"SF Mono",Menlo,monospace}
*{box-sizing:border-box}
html,body{margin:0;min-height:100%;background:var(--bg)}
body{color:var(--ink);font:500 16px/1.55 var(--sans);font-feature-settings:"ss01","tnum";
background:
radial-gradient(1.5px 1.5px at 12% 18%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 73% 9%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 88% 41%,#fff 60%,transparent 61%),
radial-gradient(1.5px 1.5px at 31% 67%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 55% 83%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 8% 88%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 46% 29%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 93% 76%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 64% 52%,#fff 60%,transparent 61%),
radial-gradient(1px 1px at 22% 44%,#fff 60%,transparent 61%),
radial-gradient(600px 400px at 80% 10%,rgba(127,255,160,.07),transparent 70%),
radial-gradient(500px 500px at 10% 90%,rgba(127,255,160,.05),transparent 70%),
var(--bg);min-height:100vh;display:grid;place-items:center;padding:24px 16px}
main{max-width:560px;width:100%}
.seam{display:flex;align-items:center;gap:12px;color:var(--dim);font-size:10px;letter-spacing:.18em;text-transform:uppercase}
.seam::after{content:"";flex:1;height:1px;background:var(--line)}
.dot{width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 12px var(--accent)}
h1{font-weight:200;font-size:44px;letter-spacing:-.02em;margin:18px 0 6px}
p{color:var(--muted);margin:0 0 14px}
.card{margin-top:22px;padding:18px 20px;border:1px solid var(--line);border-radius:18px;background:rgba(27,24,20,.84)}
.card p{margin:0 0 10px}.card p:last-child{margin:0}
code{font:500 13px var(--mono);color:var(--accent);background:var(--bg-900);padding:2px 6px;border-radius:6px}
a{color:var(--accent);text-decoration:none;border-bottom:1px solid var(--accent-dim)}a:hover{border-color:var(--accent)}
.pill{display:inline-block;margin-top:18px;padding:11px 20px;border-radius:999px;border:1px solid var(--accent-dim);color:var(--ink)}
.pill:hover{border-color:var(--accent)}
footer{margin-top:28px;color:var(--dim);font-size:13px}
</style>
</head>
<body>
<main>
<div class="seam"><span class="dot"></span>Hypernova</div>
<h1>Hypernova</h1>
<p>A self-hosted control room for Claude Code: a native phone app and a menu bar app that follow your sessions, approve what asks, and keep an eye on the budget.</p>
<p>Open source. Your server, your keys, your data.</p>
<div class="card">
<p><strong>What this domain is for.</strong> Every Hypernova server gets a random name here, such as <code>k3x9mq2z7a.${domain}</code>, so the phone can reach it over real HTTPS. Only the name points at the server; no traffic passes through this domain.</p>
<p>There is nothing to see at this address: no accounts, no cookies, no tracking.</p>
</div>
<a class="pill" href="${REPO}">Hypernova on GitHub</a>
<footer>The name service is open source too: <a href="${REPO}/tree/main/names">names/</a>.</footer>
</main>
</body>
</html>
`;
  return new Response(html, {
    status: 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "public, max-age=3600",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
    },
  });
}
