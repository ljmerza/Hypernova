#!/usr/bin/env node
// Tells IndexNow (Bing, Yandex, Seznam, Naver and others share it) that the site's pages changed,
// so they recrawl them soon. Plain Node, no dependencies. Run it after a deploy.
//
//   node site/scripts/indexnow.mjs [--dry-run] [--key <key>] [--dist <dir>] [--host <name>]
//
// The key: --key, else $INDEXNOW_KEY, else site/.cache/indexnow-key (made once, random, git-ignored).
// IndexNow checks that https://<host>/<key>.txt holds the key, so this writes <key>.txt into the
// built site (and `node site/build.mjs --indexnow-key <key>` writes it on every build): deploy
// that file before submitting. The URLs are every <loc> in the built sitemap.xml.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const SITE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const opt = { dry: false, dist: path.join(SITE, 'dist'), host: 'hypernova.nikbraun.tech' };
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === '--dry-run') opt.dry = true;
  else if (a === '--key') opt.key = args[++i];
  else if (a === '--dist') opt.dist = path.resolve(args[++i]);
  else if (a === '--host') opt.host = args[++i];
  else { console.error(`indexnow: unknown option ${a}`); process.exit(2); }
}

let key = opt.key || process.env.INDEXNOW_KEY;
const keyFile = path.join(SITE, '.cache', 'indexnow-key');
if (!key && fs.existsSync(keyFile)) key = fs.readFileSync(keyFile, 'utf8').trim();
if (!key) {
  key = crypto.randomBytes(16).toString('hex');
  fs.mkdirSync(path.dirname(keyFile), { recursive: true });
  fs.writeFileSync(keyFile, key + '\n');
  console.log(`Made a new IndexNow key and kept it in ${path.relative(process.cwd(), keyFile)}.`);
}
if (!/^[a-zA-Z0-9-]{8,128}$/.test(key)) { console.error('indexnow: the key must be 8–128 letters, digits or dashes'); process.exit(2); }

const sitemap = path.join(opt.dist, 'sitemap.xml');
if (!fs.existsSync(sitemap)) { console.error(`indexnow: ${sitemap} not found; build the site first`); process.exit(2); }
const urlList = [...fs.readFileSync(sitemap, 'utf8').matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
const foreign = urlList.filter((u) => new URL(u).host !== opt.host);
if (foreign.length) { console.error(`indexnow: the sitemap has URLs outside ${opt.host}: ${foreign.join(', ')}`); process.exit(2); }

fs.writeFileSync(path.join(opt.dist, `${key}.txt`), key);
const payload = { host: opt.host, key, keyLocation: `https://${opt.host}/${key}.txt`, urlList };

if (opt.dry) {
  console.log(`Would POST to https://api.indexnow.org/indexnow (key file: ${path.relative(process.cwd(), path.join(opt.dist, key + '.txt'))}):`);
  console.log(JSON.stringify(payload, null, 2));
  process.exit(0);
}

// The key file has to be live first, or IndexNow answers 403.
const live = await fetch(payload.keyLocation).then((r) => (r.ok ? r.text() : '')).catch(() => '');
if (live.trim() !== key) {
  console.error(`indexnow: ${payload.keyLocation} doesn't serve the key yet. Deploy ${key}.txt with the site, then run this again.`);
  process.exit(1);
}
const res = await fetch('https://api.indexnow.org/indexnow', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
  body: JSON.stringify(payload),
});
const meaning = { 200: 'accepted', 202: 'accepted, the key will be checked', 400: 'bad request', 403: 'the key file does not match', 422: 'URLs outside the host, or a bad key', 429: 'too many requests, try later' };
console.log(`IndexNow: ${res.status} ${meaning[res.status] || res.statusText} (${urlList.length} URLs)`);
process.exit(res.ok ? 0 : 1);
