#!/usr/bin/env node
// Builds the Hypernova site (https://hypernova.nikbraun.tech) into site/dist/. Plain Node, no
// dependencies. The install pages are rendered from the repository's own guides, so the app, the
// README and the site say the same thing.
//
//   node site/build.mjs [options]
//     --install-md <file>   the install guide (default: docs/INSTALL.md next to site/)
//     --linux-md <file>     the Linux guide (default: docs/LINUX.md)
//     --media <dir>         where the intro film is (hypernova-intro.mp4, .webm, poster.jpg)
//     --latest <file>       a latest.json to print sizes and checksums into the pages
//     --sample              add the sample download/latest.json (local preview only)
//     --indexnow-key <key>  write <key>.txt for IndexNow (scripts/indexnow.mjs)
//     --out <dir>           where to build (default: site/dist)
//     --no-compress         skip the .br and .gz files
//     --check               check the built site (links, anchors, pages, sitemap)
//     --serve [port]        serve it at http://127.0.0.1:<port>/ (default 4173)
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { renderMarkdown, frontMatter, sections, inlineText, inline, slugify, escapeHtml } from './lib/markdown.mjs';
import { qrSvg } from './lib/qr.mjs';
import { check } from './lib/check.mjs';
import { serve, CSP } from './lib/serve.mjs';
import * as P from './src/templates/pages.mjs';
import * as parts from './src/templates/parts.mjs';

const SITE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(SITE, 'src');
const REPO = path.resolve(SITE, '..');

// ── Options ────────────────────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const opt = { compress: true };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  const val = () => { const v = argv[++i]; if (v == null || v.startsWith('--')) die(`${a} needs a value`); return v; };
  switch (a) {
    case '--install-md': opt.installMd = path.resolve(val()); break;
    case '--linux-md': opt.linuxMd = path.resolve(val()); break;
    case '--media': opt.media = path.resolve(val()); break;
    case '--latest': opt.latest = path.resolve(val()); break;
    case '--out': opt.out = path.resolve(val()); break;
    case '--indexnow-key': opt.indexnow = val(); break;
    case '--sample': opt.sample = true; break;
    case '--no-compress': opt.compress = false; break;
    case '--check': opt.check = true; break;
    case '--serve': opt.serve = /^\d+$/.test(argv[i + 1] || '') ? +argv[++i] : 4173; break;
    case '-h': case '--help': console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 17).map((l) => l.replace(/^\/\/ ?/, '')).join('\n')); process.exit(0);
    default: die(`unknown option ${a} (see --help)`);
  }
}
function die(m) { console.error(`build: ${m}`); process.exit(2); }
const OUT = opt.out || path.join(SITE, 'dist');
const INSTALL_MD = opt.installMd || path.join(REPO, 'docs/INSTALL.md');
const LINUX_MD = opt.linuxMd || path.join(REPO, 'docs/LINUX.md');
const readJson = (f) => JSON.parse(fs.readFileSync(f, 'utf8'));

// ── The output folder ──────────────────────────────────────────────────────────────────────────
const MARK = '.hypernova-site';
if (fs.existsSync(OUT)) {
  const entries = fs.readdirSync(OUT);
  if (entries.length && !entries.includes(MARK)) die(`${OUT} exists and wasn't built by this script; not deleting it`);
  fs.rmSync(OUT, { recursive: true, force: true });
}
fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(path.join(OUT, MARK), 'Built by site/build.mjs. Safe to delete.\n');
const write = (rel, data) => { const f = path.join(OUT, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, data); };

// ── Assets, fingerprinted ──────────────────────────────────────────────────────────────────────
const assets = new Map(); // '/css/site.css' → '/assets/css/site.0123456789.css'
const hash = (buf) => crypto.createHash('sha256').update(buf).digest('hex').slice(0, 10);
function addAsset(logical, buf, { fingerprint = true } = {}) {
  const ext = path.extname(logical);
  const base = logical.slice(0, -ext.length);
  const out = fingerprint ? `/assets${base}.${hash(buf)}${ext}` : `/assets${logical}`;
  write(out, buf);
  assets.set(logical, out);
  return out;
}
const asset = (logical) => {
  const a = assets.get(logical);
  if (!a) throw new Error(`no asset ${logical}`);
  return a;
};
for (const f of fs.readdirSync(path.join(SRC, 'fonts'))) addAsset(`/fonts/${f}`, fs.readFileSync(path.join(SRC, 'fonts', f)), { fingerprint: f.endsWith('.ttf') });
for (const f of fs.readdirSync(path.join(SRC, 'img'))) if (/\.(webp|png|jpg|svg)$/.test(f)) addAsset(`/img/${f}`, fs.readFileSync(path.join(SRC, 'img', f)));
{
  let css = fs.readFileSync(path.join(SRC, 'css/site.css'), 'utf8');
  css = css.replace(/url\((\/(?:fonts|img)\/[^)]+)\)/g, (_, p) => `url(${asset(p)})`);
  addAsset('/css/site.css', Buffer.from(css));
}
for (const f of fs.readdirSync(path.join(SRC, 'js'))) addAsset(`/js/${f}`, fs.readFileSync(path.join(SRC, 'js', f)));
// Root files with stable names: icons and the social card.
for (const f of fs.readdirSync(path.join(SRC, 'static'))) fs.copyFileSync(path.join(SRC, 'static', f), path.join(OUT, f));

// ── Data ───────────────────────────────────────────────────────────────────────────────────────
const site = readJson(path.join(SRC, 'site.json'));
const images = readJson(path.join(SRC, 'img/images.json'));
const verification = readJson(path.join(SRC, 'verification.json'));
let latest = null;
if (opt.latest) latest = readJson(opt.latest);
const version = latest?.version || site.version;
const date = latest?.date || site.date;
const dateText = (d) => new Date(d + 'T12:00:00Z').toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
const mb = (n) => (n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB');
const sizes = {}, sums = {};
if (latest?.files) for (const [name, f] of Object.entries(latest.files)) { if (f.size) sizes[name] = mb(f.size); if (f.sha256) sums[name] = f.sha256; }
const mtime = (f) => fs.statSync(f).mtime.toISOString().slice(0, 10);

// ── Markdown from the repository ───────────────────────────────────────────────────────────────
const GH = `${site.repo}/blob/main/`;
const GH_TREE = `${site.repo}/tree/main/`;
/** Where a link in a repo file (at docDir) points: our own pages for the two guides, GitHub for the rest. */
function repoLink(docDir) {
  return (href) => {
    if (/^(https?:|mailto:|#|\/)/.test(href)) return href;
    const [p, hashPart] = href.split('#');
    const target = path.posix.normalize(path.posix.join(docDir, p));
    const h = hashPart ? `#${hashPart}` : '';
    if (target === 'docs/INSTALL.md') return `/install/${h}`;
    if (target === 'docs/LINUX.md') return `/install/linux/${h}`;
    const isDir = !path.posix.extname(target) || fs.statSync(path.join(REPO, target), { throwIfNoEntry: false })?.isDirectory();
    return (isDir ? GH_TREE : GH) + target + h;
  };
}
function codeLabel(lang) { return ({ sh: 'SHELL', bash: 'SHELL', zsh: 'SHELL', '': 'TEXT' })[lang] || lang.toUpperCase(); }

/** A repo guide: its H1 dropped, its first paragraph as the page's lede, the rest rendered. */
function repoDoc(file, docDir, fixes = []) {
  if (!fs.existsSync(file)) die(`${file} not found (pass --install-md / --linux-md)`);
  let src = fs.readFileSync(file, 'utf8');
  for (const [re, to, why] of fixes) if (re.test(src)) { src = src.replace(re, to); console.log(`  ${path.basename(file)}: ${why}`); }
  const lines = src.split('\n');
  const h1 = lines.findIndex((l) => /^# /.test(l));
  const body = h1 >= 0 ? lines.slice(h1 + 1).join('\n') : src;
  const secs = sections(body, 2);
  const pre = secs[0].body.trim().split(/\n\s*\n/);
  const introMd = pre.shift() || '';
  const rest = pre.join('\n\n') + '\n\n' + secs.slice(1).map((s) => `## ${s.title}\n${s.body}`).join('\n');
  const headings = [];
  const ids = new Map();
  const ctx = { link: repoLink(docDir), image: (k, a) => `<img src="${escapeHtml(k)}" alt="${escapeHtml(a)}">` };
  const html = renderMarkdown(rest, { ...ctx, headingIds: ids, onHeading: (h) => headings.push(h), codeLabel });
  return {
    src, name: path.relative(REPO, file), html, headings, ids: headings.map((h) => h.id),
    intro: inline(introMd.replace(/\n/g, ' '), ctx), introText: inlineText(introMd.replace(/\n/g, ' ')),
  };
}

console.log('Building the site…');
const installDoc = repoDoc(INSTALL_MD, 'docs');
const linuxDoc = repoDoc(LINUX_MD, 'docs', [
  // Stale since the first release carries a Linux tarball (the coordinator fixes it in main).
  [/\s*\(Hypernova has no public download yet\)/g, '', 'dropped the stale "(Hypernova has no public download yet)"'],
]);

// ── The site's own Markdown: guides and the FAQ ────────────────────────────────────────────────
const GUIDE_ORDER = [
  ['claude-code-on-your-phone', 'Claude Code on your phone', 'Android'],
  ['approve-claude-code-permissions-from-android', 'Approve permissions from your phone', 'Approvals'],
  ['codex-on-your-phone', 'Codex on your phone', 'Codex'],
  ['claude-code-and-codex-together', 'Claude Code and Codex together', 'Two agents'],
  ['claude-code-usage-limits', 'Usage limits', 'Limits'],
  ['claude-code-remote-access-tailscale', 'Remote access with Tailscale', 'Remote access'],
  ['claude-code-on-a-vps', 'An always-on host (VPS)', 'VPS'],
  ['run-claude-code-on-a-linux-server', 'Set up a Linux server', 'Linux'],
  ['claude-code-on-windows-wsl', 'On a Windows PC (experimental)', 'Windows'],
  ['claude-code-on-even-g2-glasses', 'On Even G2 glasses', 'Glasses'],
];
const ctx = {
  site, images, verification, version, date, dateText: dateText(date), sizes, sums, asset,
  homeDescription: 'Free, open-source control room for Claude Code and Codex: watch, approve and steer agents on your Mac or Linux server from Android, the menu bar and G2 glasses.',
};
const shotHtml = (key, alt) => parts.shot(ctx, key, alt, { sizes: '(min-width: 900px) 320px, 70vw' });

function faqFrom(md, idPrefix = '') {
  // "### Question" blocks under a "## …" heading: the question, its answer as HTML and as text.
  const items = [];
  for (const block of sections(md, 3).slice(1)) {
    const answer = block.body.trim();
    items.push({
      q: inlineText(block.title), id: idPrefix + slugify(inlineText(block.title)),
      html: renderMarkdown(answer, { link: (h) => h, anchors: false }),
      text: inlineText(answer.replace(/```[\s\S]*?```/g, '').replace(/\n+/g, ' ')).trim(),
    });
  }
  return items;
}

const guideFiles = fs.readdirSync(path.join(SRC, 'guides')).filter((f) => f.endsWith('.md'));
const guides = guideFiles.map((f) => {
  const file = path.join(SRC, 'guides', f);
  const slug = f.replace(/\.md$/, '');
  const { data, body } = frontMatter(fs.readFileSync(file, 'utf8'));
  for (const k of ['title', 'description', 'h1', 'lede', 'shot', 'shotAlt']) if (!data[k]) die(`${f}: front matter has no ${k}`);
  if (/^# /m.test(body)) die(`${f}: the body has an H1; the template gives the page its one H1`);
  const order = GUIDE_ORDER.findIndex(([s]) => s === slug);
  const ids = new Map([['main', 0]]);
  const html = renderMarkdown(body, { link: (h) => h, image: (k, a) => shotHtml(k, a), headingIds: ids, codeLabel });
  const faqSec = sections(body, 2).find((s) => /^(faq|frequently asked questions)$/i.test(s.title || ''));
  return {
    slug, file, ...data, html,
    nav: order >= 0 ? GUIDE_ORDER[order][1] : data.h1,
    kicker: order >= 0 ? GUIDE_ORDER[order][2] : 'Guide',
    order: order >= 0 ? order : 99,
    related: (data.related || '').split(',').map((s) => s.trim()).filter(Boolean),
    updated: data.updated || date, updatedText: dateText(data.updated || date),
    faq: faqSec ? faqFrom('### ' + faqSec.body.split(/\n### /).slice(1).join('\n### ')) : [],
  };
}).sort((a, b) => a.order - b.order || a.slug.localeCompare(b.slug));
ctx.guides = guides;

const faqSrc = frontMatter(fs.readFileSync(path.join(SRC, 'faq.md'), 'utf8'));
const faqGroups = sections(faqSrc.body, 2).slice(1).map((g) => ({ title: g.title, id: slugify(g.title), items: faqFrom(g.body) }));
ctx.faq = { ...faqSrc.data, groups: faqGroups, items: faqGroups.flatMap((g) => g.items) };

// ── Media: the intro film, when its files exist ────────────────────────────────────────────────
const media = readJson(path.join(SRC, 'media-manifest.json')).film;
if (opt.media) {
  if (!fs.existsSync(opt.media)) die(`--media ${opt.media} not found`);
  const have = {};
  for (const k of ['mp4', 'webm', 'poster']) {
    const f = path.join(opt.media, media[k]);
    if (fs.existsSync(f)) { fs.mkdirSync(path.join(OUT, 'media'), { recursive: true }); fs.copyFileSync(f, path.join(OUT, 'media', media[k])); have[k] = `/media/${media[k]}`; }
  }
  if (have.mp4 && have.poster) {
    const secs = /^PT(?:(\d+)M)?(?:(\d+)S)?$/.exec(media.duration || '');
    ctx.film = { ...media, mp4: have.mp4, webm: have.webm || null, poster: have.poster, durationText: secs ? `${secs[1] || 0}:${String(secs[2] || 0).padStart(2, '0')}` : '' };
    console.log(`  film: ${Object.keys(have).join(', ')}`);
  } else console.log(`  film: not shown (${opt.media} has ${Object.keys(have).join(', ') || 'none of its files'})`);
}

// ── Pages ──────────────────────────────────────────────────────────────────────────────────────
ctx.qr = qrSvg(`${site.url}/install/`, { level: 'M', margin: 3, label: `QR code for ${site.url}/install/` });
const built = []; // [urlPath, sources for lastmod]
function emit(urlPath, html, sources) {
  write(urlPath.endsWith('/') ? urlPath + 'index.html' : urlPath, html);
  if (sources) built.push([urlPath, sources]);
}
const T = (f) => path.join(SRC, 'templates', f);
emit('/', P.home(ctx), [T('pages.mjs'), path.join(SRC, 'faq.md')]);
emit('/install/', P.install(ctx, installDoc), [INSTALL_MD, T('pages.mjs')]);
emit('/install/linux/', P.linux(ctx, linuxDoc), [LINUX_MD, T('pages.mjs')]);
emit('/guides/', P.guidesIndex(ctx), guides.map((g) => g.file));
for (const g of guides) emit(`/guides/${g.slug}/`, P.guide(ctx, g), [g.file]);
emit('/faq/', P.faq(ctx), [path.join(SRC, 'faq.md')]);
emit('/404.html', P.notFound(ctx), null);

// ── Crawlers: sitemap, robots, llms.txt, verification, IndexNow ────────────────────────────────
const lastmod = (sources) => sources.map(mtime).sort().pop();
write('/sitemap.xml', `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${built.map(([u, s]) => `  <url><loc>${site.url}${u}</loc><lastmod>${lastmod(s)}</lastmod></url>`).join('\n')}
</urlset>
`);
write('/robots.txt', `User-agent: *\nAllow: /\n\nSitemap: ${site.url}/sitemap.xml\n`);
if (verification.googleFile) write('/' + verification.googleFile, `google-site-verification: ${verification.googleFile}`);
if (verification.yandexFile) write('/' + verification.yandexFile, `<html>\n<head>\n<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">\n</head>\n<body>Verification: ${verification.yandexFile.replace(/^yandex_|\.html$/g, '')}</body>\n</html>\n`);
if (verification.bingXml) write('/BingSiteAuth.xml', verification.bingXml);
// The IndexNow key from scripts/indexnow.mjs, when there is one, so every build keeps its file.
if (!opt.indexnow && fs.existsSync(path.join(SITE, '.cache/indexnow-key'))) opt.indexnow = fs.readFileSync(path.join(SITE, '.cache/indexnow-key'), 'utf8').trim();
if (opt.indexnow) {
  if (!/^[a-zA-Z0-9-]{8,128}$/.test(opt.indexnow)) die('--indexnow-key: 8–128 letters, digits or dashes');
  write(`/${opt.indexnow}.txt`, opt.indexnow);
}

const abs = (u) => site.url + u;
const llms = `# Hypernova

> A control room for Claude Code and Codex on Android, Mac and Linux. An Android app, a Mac menu bar app and a Linux service let you watch, approve and steer the coding agents running on your own Mac or Linux server, from your phone and Even Realities G2 glasses. Free and open source (Apache-2.0), self-hosted, with no Hypernova server in between.

- Current version: ${version} (${date}), alpha.
- Hosts: a Mac with Apple silicon on macOS 14 or later (the menu bar app), or Linux (Ubuntu or Debian, x86_64 or arm64; a VPS, a home server or Docker; the \`hypernova\` command).
- Windows: experimental. A Windows PC runs the Linux service inside WSL 2 (Ubuntu) or a Docker Desktop container, with Claude Code and Codex installed there, and the phone pairs over the built-in Tailscale. Documented and tested on Linux, not yet tried on a real Windows PC; there is no native Windows app.
- Phone: Android 10 or later. Glasses: Even Realities G2 (Hypernova Lens, through the phone app).
- Agents: Claude Code, OpenAI's Codex (0.163.0 or later), or both.
- Connection: directly on the same Wi-Fi (mutual TLS), or over your own Tailscale network (built in on the Mac, Linux and the phone). Only a small name service for pairing and private names is hosted; it never sees sessions.
- Author: ${site.author.name} (${site.author.url}). Source: ${site.repo}. Not affiliated with Anthropic, OpenAI or Even Realities.

## Install

- [Install Hypernova](${abs('/install/')}): downloads for Mac, Linux and Android, and the setup step by step
- [Hypernova on Linux](${abs('/install/linux/')}): a VPS, a home server or Docker
- [Windows (experimental)](${abs('/install/#5-windows-experimental-wsl2-or-docker-desktop')}): WSL 2 or Docker Desktop, not yet tried on a real Windows PC
- [Latest release on GitHub](${site.releases}): Hypernova.dmg, Hypernova.apk, Hypernova-linux.tar.gz, Hypernova-Lens.ehpk, SHA256SUMS.txt

## Guides

${guides.map((g) => `- [${g.h1}](${abs(`/guides/${g.slug}/`)}): ${g.description}`).join('\n')}

## Reference

- [FAQ](${abs('/faq/')}): ${ctx.faq.items.length} questions and answers
- [Source code](${site.repo})
- [Protocol](${GH}docs/PROTOCOL.md): the API between the apps and the service
- [Security model](${GH}docs/SECURITY-MODEL.md): what runs and is stored where, and who sees what
- [Decisions](${GH}docs/DECISIONS.md): what was agreed and why

## Optional

- [Full text](${abs('/llms-full.txt')}): the install guides, every guide and the FAQ in one file
`;
write('/llms.txt', llms);
const strip = (md) => md.replace(/^---\n[\s\S]*?\n---\n/, '').replace(/!\[([^\]]*)\]\(img:[^)]+\)/g, '[Screenshot: $1]');
write('/llms-full.txt', [
  llms.replace(/\n## Optional[\s\S]*$/, '\n'),
  `# Install Hypernova (${abs('/install/')})\n\n${installDoc.src.replace(/^# .*\n/, '')}`,
  `# Hypernova on Linux (${abs('/install/linux/')})\n\n${linuxDoc.src.replace(/^# .*\n/, '')}`,
  ...guides.map((g) => `# ${g.h1} (${abs(`/guides/${g.slug}/`)})\n\n${g.lede}\n\n${strip(fs.readFileSync(g.file, 'utf8'))}`),
  `# ${ctx.faq.h1} (${abs('/faq/')})\n\n${strip(fs.readFileSync(path.join(SRC, 'faq.md'), 'utf8'))}`,
].join('\n\n---\n\n'));

if (opt.sample) {
  const s = readJson(path.join(SRC, 'sample/latest.json'));
  write('/download/latest.json', JSON.stringify(s, null, 2) + '\n');
  console.log('  download/latest.json: the SAMPLE (local preview only)');
}

// ── Precompressed copies for the server (Caddy's file_server precompressed) ─────────────────────
let compressed = 0;
if (opt.compress) {
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]));
  for (const f of walk(OUT)) {
    if (!/\.(html|css|js|svg|xml|txt|json|ttf|ico)$/.test(f) || f.includes(`${path.sep}download${path.sep}`)) continue;
    const buf = fs.readFileSync(f);
    if (buf.length < 1024) continue;
    fs.writeFileSync(f + '.br', zlib.brotliCompressSync(buf, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 11, [zlib.constants.BROTLI_PARAM_SIZE_HINT]: buf.length } }));
    fs.writeFileSync(f + '.gz', zlib.gzipSync(buf, { level: 9 }));
    compressed++;
  }
}

console.log(`  ${built.length + 1} pages, ${assets.size} assets${compressed ? `, ${compressed} files precompressed` : ''}`);
console.log(`  install guide: ${installDoc.name} (${installDoc.headings.length} headings), Linux guide: ${linuxDoc.name} (${linuxDoc.headings.length})`);
console.log(`Built ${path.relative(process.cwd(), OUT) || OUT}`);

// ── Check and serve ────────────────────────────────────────────────────────────────────────────
if (opt.check) {
  const r = check({
    dist: OUT, site,
    installMd: { src: installDoc.src, ids: installDoc.ids, name: installDoc.name },
    linuxMd: { src: linuxDoc.src, ids: linuxDoc.ids, name: linuxDoc.name },
    caddyfile: path.join(SITE, 'deploy/Caddyfile.hypernova'), csp: CSP,
  });
  for (const w of r.warns) console.log(`  note  ${w}`);
  for (const e of r.errors) console.log(`  ERROR ${e}`);
  console.log(`Checked ${r.pages} pages: ${r.errors.length} errors; ${r.external.length} external links (not fetched).`);
  if (r.errors.length) process.exitCode = 1;
}
if (opt.serve) serve(OUT, { port: opt.serve });
