// `build.mjs --check`: reads the built site the way a crawler and a careful reviewer would.
//  - Every page is well-formed (tags balanced and nested), has one h1, a unique <title> and meta
//    description, a canonical URL, unique ids, images with alt text and dimensions.
//  - Every JSON-LD block parses.
//  - Every internal link, image, script, stylesheet and anchor resolves.
//  - sitemap.xml lists exactly the built, indexable pages; robots.txt points at it.
//  - The install pages carry every heading of docs/INSTALL.md and docs/LINUX.md.
//  - The Caddyfile's Content-Security-Policy is the one the pages were tested with.
import fs from 'node:fs';
import path from 'node:path';

const VOID = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr']);

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

const decode = (s) => s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
const attr = (tag, name) => { const m = tag.match(new RegExp(`\\s${name}="([^"]*)"`)); return m ? decode(m[1]) : null; };

/** Balance check: returns a list of problems. */
function wellFormed(html) {
  const problems = [];
  const stack = [];
  const re = /<!--[\s\S]*?-->|<!doctype[^>]*>|<(\/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^\s"'>\/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'>]+))?)*)\s*(\/?)>/gi;
  let m;
  while ((m = re.exec(html))) {
    if (!m[2]) continue;
    const close = m[1] === '/', name = m[2].toLowerCase(), self = m[4] === '/';
    if (!close && (name === 'script' || name === 'style')) {
      const end = html.indexOf(`</${name}>`, re.lastIndex);
      if (end < 0) { problems.push(`<${name}> never closed`); break; }
      re.lastIndex = end + name.length + 3;
      continue;
    }
    if (close) {
      if (VOID.has(name)) { problems.push(`stray </${name}>`); continue; }
      const top = stack.pop();
      if (top !== name) { problems.push(`</${name}> closes <${top}> (line ${html.slice(0, m.index).split('\n').length})`); if (top) stack.push(top); }
    } else if (!VOID.has(name) && !self) {
      stack.push(name);
    }
  }
  if (stack.length) problems.push(`unclosed: ${stack.join(' > ')}`);
  // Blocks inside a paragraph (a browser closes the <p> early and the layout changes).
  const inP = html.match(/<p\b[^>]*>(?:(?!<\/p>)[\s\S])*?<(figure|div|ul|ol|table|h[1-6]|pre|blockquote|section|article)\b/);
  if (inP) problems.push(`<${inP[1]}> inside a <p>`);
  // Stray < or & that aren't markup or entities.
  const text = html.replace(/<script[\s\S]*?<\/script>/gi, '');
  const amp = text.match(/&(?![a-zA-Z][a-zA-Z0-9]*;|#\d+;|#x[0-9a-fA-F]+;)/g);
  if (amp) problems.push(`${amp.length} unescaped &`);
  return problems;
}

export function check({ dist, site, installMd, linuxMd, caddyfile, csp }) {
  const errors = [], warns = [];
  const err = (f, m) => errors.push(`${f}: ${m}`);
  const warn = (f, m) => warns.push(`${f}: ${m}`);
  const files = walk(dist).filter((f) => !/\.(br|gz)$/.test(f));
  const rel = (f) => '/' + path.relative(dist, f).split(path.sep).join('/');
  const htmlFiles = files.filter((f) => f.endsWith('.html'));
  const pages = new Map();
  const titles = new Map(), descs = new Map();
  const external = new Set();

  for (const f of htmlFiles) {
    const html = fs.readFileSync(f, 'utf8');
    const r = rel(f);
    const urlPath = r.endsWith('/index.html') ? r.slice(0, -10) : r;
    const ids = new Set();
    for (const m of html.matchAll(/\sid="([^"]+)"/g)) {
      if (ids.has(m[1])) err(r, `duplicate id "${m[1]}"`);
      ids.add(m[1]);
    }
    const noindex = /<meta name="robots" content="noindex">/.test(html);
    pages.set(urlPath, { file: f, html, ids, noindex });
    for (const p of wellFormed(html)) err(r, p);
    const h1 = html.match(/<h1[\s>]/g) || [];
    if (h1.length !== 1) err(r, `${h1.length} h1 elements`);
    if (!/^<!doctype html>\n<html lang="en">/.test(html)) err(r, 'missing doctype or lang="en"');
    const title = (html.match(/<title>([^<]*)<\/title>/) || [])[1];
    const desc = (html.match(/<meta name="description" content="([^"]*)">/) || [])[1];
    if (!title) err(r, 'no <title>'); else {
      if (titles.has(title)) err(r, `title repeats ${titles.get(title)}`); else titles.set(title, r);
      const t = decode(title);
      if (t.length > 70 || t.length < 15) warn(r, `title is ${t.length} characters`);
    }
    if (!desc) err(r, 'no meta description'); else {
      if (descs.has(desc)) err(r, `description repeats ${descs.get(desc)}`); else descs.set(desc, r);
      const d = decode(desc);
      if (d.length > 165 || d.length < 70) warn(r, `description is ${d.length} characters`);
    }
    const canon = attr((html.match(/<link rel="canonical"[^>]*>/) || [''])[0], 'href');
    if (!noindex) {
      if (!canon) err(r, 'no canonical');
      else if (canon !== site.url + urlPath) err(r, `canonical ${canon} is not ${site.url + urlPath}`);
    }
    for (const m of html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)) {
      try { const j = JSON.parse(m[1]); if (!j['@context'] || !j['@type']) err(r, 'JSON-LD without @context or @type'); } catch (e) { err(r, `JSON-LD does not parse: ${e.message}`); }
    }
    for (const m of html.matchAll(/<img\b[^>]*>/g)) {
      const t = m[0];
      if (attr(t, 'alt') == null) err(r, `img without alt: ${t.slice(0, 80)}`);
      if (!attr(t, 'width') || !attr(t, 'height')) err(r, `img without width/height: ${t.slice(0, 80)}`);
    }
    if (/\sstyle="/.test(html)) err(r, 'inline style attribute (the CSP blocks it)');
    if (/<script(?![^>]*\bsrc=)(?![^>]*type="application\/ld\+json")[^>]*>/.test(html)) err(r, 'inline script (the CSP blocks it)');
  }

  // Links, sources and anchors.
  const resolve = (from, target) => {
    let u;
    try { u = new URL(target, site.url + from); } catch { return null; }
    if (u.origin !== new URL(site.url).origin) return null;
    return { path: decodeURIComponent(u.pathname), hash: u.hash ? decodeURIComponent(u.hash.slice(1)) : '' };
  };
  const exists = (p) => {
    const f = path.join(dist, p);
    const st = fs.statSync(f, { throwIfNoEntry: false });
    if (!st) return null;
    if (st.isDirectory()) return fs.existsSync(path.join(f, 'index.html')) ? (p.endsWith('/') ? p : p + '/') : null;
    return p;
  };
  for (const [urlPath, pg] of pages) {
    const r = rel(pg.file);
    const refs = [];
    for (const m of pg.html.matchAll(/\s(href|src|poster)="([^"]*)"/g)) refs.push(decode(m[2]));
    for (const m of pg.html.matchAll(/\ssrcset="([^"]*)"/g)) for (const part of decode(m[1]).split(',')) refs.push(part.trim().split(/\s+/)[0]);
    for (const m of pg.html.matchAll(/<meta property="og:image" content="([^"]*)"/g)) refs.push(decode(m[1]));
    for (const ref of refs) {
      if (!ref) { err(r, 'empty link'); continue; }
      if (/^(mailto|tel|javascript):/i.test(ref)) { if (/^javascript:/i.test(ref)) err(r, 'javascript: link'); continue; }
      const t = resolve(urlPath, ref);
      if (!t) { if (/^https?:/.test(ref)) external.add(ref.split('#')[0]); continue; }
      if (t.path.startsWith('/download/')) {
        if (!exists(t.path)) warn(r, `${t.path} is not in dist (the release puts it on the server)`);
        continue;
      }
      const found = exists(t.path);
      if (!found) { err(r, `broken link ${ref}`); continue; }
      if (t.hash) {
        const key = found.endsWith('/') ? found : found.replace(/\/index\.html$/, '/');
        const target = pages.get(key) || pages.get(found);
        if (target && !target.ids.has(t.hash)) err(r, `missing anchor ${ref}`);
      }
    }
  }

  // Sitemap and robots.
  const sm = path.join(dist, 'sitemap.xml');
  if (!fs.existsSync(sm)) err('sitemap.xml', 'missing'); else {
    const locs = [...fs.readFileSync(sm, 'utf8').matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
    const want = [...pages].filter(([, p]) => !p.noindex).map(([u]) => site.url + u).sort();
    const got = [...locs].sort();
    for (const u of want) if (!got.includes(u)) err('sitemap.xml', `missing ${u}`);
    for (const u of got) if (!want.includes(u)) err('sitemap.xml', `lists ${u}, which is not a built page`);
    if (!/<lastmod>\d{4}-\d{2}-\d{2}<\/lastmod>/.test(fs.readFileSync(sm, 'utf8'))) err('sitemap.xml', 'no lastmod');
  }
  const robots = path.join(dist, 'robots.txt');
  if (!fs.existsSync(robots) || !fs.readFileSync(robots, 'utf8').includes(`Sitemap: ${site.url}/sitemap.xml`)) err('robots.txt', 'missing or without the Sitemap line');
  for (const f of ['llms.txt', 'llms-full.txt', '404.html', 'favicon.ico', 'favicon.svg', 'apple-touch-icon.png', 'og.jpg']) if (!fs.existsSync(path.join(dist, f))) err(f, 'missing');

  // The install guides, heading for heading.
  for (const [md, urlPath] of [[installMd, '/install/'], [linuxMd, '/install/linux/']]) {
    const pg = pages.get(urlPath);
    if (!pg) { err(urlPath, 'not built'); continue; }
    const heads = md.src.split('\n').filter((l, i, a) => /^#{2,6}\s/.test(l) && !inFence(a, i)).map((l) => l.replace(/^#+\s+/, '').trim());
    let missing = 0;
    for (const h of heads) {
      const plain = h.replace(/[`*_]/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
      const text = pg.html.replace(/<[^>]+>/g, '');
      if (!decode(text).includes(plain)) { err(urlPath, `heading not rendered: "${h}"`); missing++; }
    }
    for (const id of md.ids) if (!pg.ids.has(id)) err(urlPath, `heading id missing: #${id}`);
    if (!missing) warns.push(`${urlPath}: all ${heads.length} headings of ${md.name} rendered`);
  }

  // The Caddyfile's CSP.
  if (caddyfile && fs.existsSync(caddyfile)) {
    const c = fs.readFileSync(caddyfile, 'utf8').match(/Content-Security-Policy\s+"([^"]+)"/);
    if (!c) err('Caddyfile', 'no Content-Security-Policy');
    else if (c[1] !== csp) err('Caddyfile', `its CSP differs from the one the preview serves:\n  ${c[1]}\n  ${csp}`);
  }

  return { errors, warns, pages: pages.size, external: [...external].sort() };
}

function inFence(lines, i) {
  let open = false;
  for (let k = 0; k < i; k++) if (/^ {0,3}(```|~~~)/.test(lines[k])) open = !open;
  return open;
}
