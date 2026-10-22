// Renders the site's fixed images into site/src/static/: the social card og.jpg (1200×630, drawn
// by the site's own CSS and sky from tools/og.html), apple-touch-icon.png (180×180) and
// favicon.ico (16 and 32 px PNGs in an ICO), both from favicon.svg.
//
// Dev-only, run when the look changes. Like tools/images.mjs it drives headless Chrome through
// puppeteer-core installed outside the repo:
//   PUPPETEER_CORE=/tmp/pp/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js \
//     node site/tools/render-static.mjs
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const site = path.resolve(here, '..');
const src = path.join(site, 'src');
const out = path.join(src, 'static');
const chrome = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
if (!process.env.PUPPETEER_CORE) { console.error('Set PUPPETEER_CORE (see the header).'); process.exit(2); }
const puppeteer = (await import(pathToFileURL(process.env.PUPPETEER_CORE).href)).default;

// A tiny server for the source tree: /css, /js, /fonts, /img from src/, /tools from here.
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.ttf': 'font/ttf', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  const file = p.startsWith('/tools/') ? path.join(here, p.slice(7)) : p === '/' ? path.join(here, 'og.html') : path.join(src, p);
  if (!file.startsWith(site) || !fs.existsSync(file)) { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox', '--force-color-profile=srgb'] });
const page = await browser.newPage();

// The social card.
await page.setViewport({ width: 1200, height: 630, deviceScaleFactor: 1 });
await page.goto(base + '/', { waitUntil: 'networkidle0' });
await page.evaluate(() => document.fonts.ready);
await new Promise((r) => setTimeout(r, 1800));
const png = path.join(out, 'og.png');
await page.screenshot({ path: png, type: 'png' });
// JPEG through macOS's sips (no metadata beyond the colour profile).
execFileSync('sips', ['-s', 'format', 'jpeg', '-s', 'formatOptions', '86', png, '--out', path.join(out, 'og.jpg')], { stdio: 'ignore' });
fs.rmSync(png);

// The home stage's still (for pages without scripts): the desktop stage, transparent, as WebP.
await page.setViewport({ width: 1200, height: 700, deviceScaleFactor: 2 });
await page.goto(base + '/tools/orrery.html', { waitUntil: 'networkidle0' });
await page.evaluate(() => document.fonts.ready.then(() => 1));
await new Promise((r) => setTimeout(r, 1800));
const stage = await page.$('.stage');
const stillPng = await stage.screenshot({ type: 'png', omitBackground: true });
const webp = await page.evaluate(async (b64) => {
  const img = new Image();
  img.src = 'data:image/png;base64,' + b64;
  await img.decode();
  const c = document.createElement('canvas');
  c.width = img.naturalWidth; c.height = img.naturalHeight;
  c.getContext('2d').drawImage(img, 0, 0);
  return { url: c.toDataURL('image/webp', 0.86), w: c.width, h: c.height };
}, Buffer.from(stillPng).toString('base64'));
fs.writeFileSync(path.join(src, 'img', `orrery-${webp.w}.webp`), Buffer.from(webp.url.split(',')[1], 'base64'));
const imagesJson = path.join(src, 'img', 'images.json');
const images = JSON.parse(fs.readFileSync(imagesJson, 'utf8'));
images.orrery = { ratio: +(webp.h / webp.w).toFixed(5), widths: [[webp.w, webp.h]] };
fs.writeFileSync(imagesJson, JSON.stringify(images, null, 2) + '\n');
console.log(`orrery-${webp.w}.webp`);

// The icons from favicon.svg.
const svg = fs.readFileSync(path.join(out, 'favicon.svg'), 'utf8');
async function icon(size, bg) {
  await page.setViewport({ width: size, height: size, deviceScaleFactor: 1 });
  await page.setContent(`<html><body style="margin:0;background:${bg}">${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`);
  return page.screenshot({ type: 'png', omitBackground: bg === 'transparent' });
}
fs.writeFileSync(path.join(out, 'apple-touch-icon.png'), await icon(180, '#14110e'));
const pngs = [await icon(16, 'transparent'), await icon(32, 'transparent'), await icon(48, 'transparent')];
// An ICO whose entries are PNGs (every current browser reads them).
const head = Buffer.alloc(6 + 16 * pngs.length);
head.writeUInt16LE(0, 0); head.writeUInt16LE(1, 2); head.writeUInt16LE(pngs.length, 4);
let offset = head.length;
pngs.forEach((b, i) => {
  const size = [16, 32, 48][i], e = 6 + 16 * i;
  head.writeUInt8(size, e); head.writeUInt8(size, e + 1); head.writeUInt8(0, e + 2); head.writeUInt8(0, e + 3);
  head.writeUInt16LE(1, e + 4); head.writeUInt16LE(32, e + 6); head.writeUInt32LE(b.length, e + 8); head.writeUInt32LE(offset, e + 12);
  offset += b.length;
});
fs.writeFileSync(path.join(out, 'favicon.ico'), Buffer.concat([head, ...pngs]));

await browser.close();
server.close();
for (const f of ['og.jpg', 'apple-touch-icon.png', 'favicon.ico']) console.log(f, fs.statSync(path.join(out, f)).size, 'bytes');
