// The Mac's pairing QR code, in any of its shapes (docs/PROTOCOL.md, "Pairing"), as the Android
// app reads it (data/Pairing.kt). Pure.

export interface PairLink { baseUrl: string; code: string; macName: string }

/**
 * Finds a pairing link in scanned or pasted text: the app link
 * `hypernova://pair?v=1&u=<base>&c=<code>&n=<name>`, the web link the Mac's QR carries
 * (`https://<mac>.ts.net:47321/pair?c=<code>&n=<name>`, whose base is its own origin), or the
 * `intent://pair?…#Intent;…` hand-over. Null if there isn't one.
 */
export function parsePairLink(text: string | null | undefined): PairLink | null {
  if (!text) return null;
  const t = text.trim();
  const app = t.search(/hypernova:\/\//i);
  if (app >= 0) return parseApp(t.slice(app).split(/\s/)[0]);
  const intent = t.search(/intent:\/\/pair/i);
  if (intent >= 0) return parseApp('hypernova://' + t.slice(intent).split(/\s/)[0].split('#Intent')[0].replace(/^intent:\/\//i, ''));
  const web = /https?:\/\/[^\s/?#]+(?::\d+)?\/pair\?[^\s]*/i.exec(t);
  return web ? parseWeb(web[0]) : null;
}

function query(raw: string): URLSearchParams {
  const q = raw.indexOf('?');
  return new URLSearchParams(q >= 0 ? raw.slice(q + 1).split('#')[0] : '');
}

function parseApp(raw: string): PairLink | null {
  if (!/^hypernova:\/\/pair\b/i.test(raw)) return null;
  const q = query(raw);
  const v = q.get('v');
  if (v !== null && v !== '1') return null;
  const base = q.get('u')?.trim().replace(/\/+$/, '');
  const code = q.get('c')?.trim();
  if (!base || !code || !/^https?:\/\//i.test(base)) return null;
  let host = 'Mac';
  try { host = new URL(base).hostname; } catch { return null; }
  return { baseUrl: base, code, macName: q.get('n')?.trim() || host };
}

function parseWeb(raw: string): PairLink | null {
  let url: URL;
  try { url = new URL(raw); } catch { return null; }
  const q = url.searchParams;
  const code = q.get('c')?.trim();
  if (!code) return null;
  const u = q.get('u')?.trim().replace(/\/+$/, '');
  const base = u && /^https?:\/\//i.test(u) ? u : url.origin;
  return { baseUrl: base, code, macName: q.get('n')?.trim() || url.hostname.split('.')[0] };
}
