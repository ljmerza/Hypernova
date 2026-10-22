// Re-encodes the README's vetted screenshots for the site: WebP (every browser the app's users
// have reads it) at two widths, with no metadata (a canvas re-encode carries none), into
// site/src/img/, and writes site/src/img/images.json
// (each image's widths and height ratio, read by build.mjs for width/height attributes).
//
// Dev-only, run once when the screenshots change. It drives headless Chrome through puppeteer-core,
// which is not a dependency of the site: install it anywhere outside the repo and point at it:
//
//   (cd /tmp/pp && npm i --no-save puppeteer-core)
//   PUPPETEER_CORE=/tmp/pp/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js \
//     node site/tools/images.mjs
//
// CHROME overrides the browser (default: Google Chrome in /Applications).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
const out = path.resolve(here, '../src/img');
const chrome = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const mod = process.env.PUPPETEER_CORE;
if (!mod) { console.error('Set PUPPETEER_CORE to puppeteer-core\'s ESM entry (see the header).'); process.exit(2); }
const puppeteer = (await import(pathToFileURL(mod).href)).default;

// key → source, the widths to make, and the formats.
const PHONE = [320, 630];
const shots = {
  'phone-home': ['docs/images/phone-home.jpg', PHONE],
  'phone-chat': ['docs/images/phone-chat.jpg', PHONE],
  'phone-automations': ['docs/images/phone-automations.jpg', [320, 486]],
  'phone-dictation': ['docs/images/phone-dictation.jpg', [320, 486]],
  'phone-new-session': ['docs/images/phone-new-session.jpg', PHONE],
  'phone-statistics': ['docs/images/phone-statistics.jpg', [320, 486]],
  'phone-sky-limit': ['docs/images/phone-sky-limit.jpg', PHONE],
  'phone-model-picker': ['docs/images/phone-model-picker.jpg', PHONE],
  'phone-files': ['docs/images/phone-files.jpg', PHONE],
  'phone-dialog': ['docs/images/phone-dialog.jpg', PHONE],
  'mac-popover': ['docs/images/mac-popover.jpg', [320, 640]],
  'mac-automation': ['docs/images/mac-automation.jpg', [320, 640]],
};
// The glasses' frames are tiny, clean PNGs (no chunks but IHDR, IDAT, IEND): copied as they are.
const pngs = ['lens-glance', 'lens-needs-you', 'lens-result', 'lens-telescope'];

fs.mkdirSync(out, { recursive: true });
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.goto('about:blank');
// Other tools add their own entries (render-static.mjs: the orrery still); keep them.
const manifestFile = path.join(out, 'images.json');
const manifest = fs.existsSync(manifestFile) ? JSON.parse(fs.readFileSync(manifestFile, 'utf8')) : {};
for (const [key, [src, widths]] of Object.entries(shots)) {
  const buf = fs.readFileSync(path.join(repo, src));
  const mime = src.endsWith('.png') ? 'image/png' : 'image/jpeg';
  const dataUrl = `data:${mime};base64,${buf.toString('base64')}`;
  const res = await page.evaluate(async (dataUrl, widths) => {
    const img = new Image();
    img.src = dataUrl;
    await img.decode();
    const outs = [];
    for (const w of widths) {
      const h = Math.round(img.naturalHeight * w / img.naturalWidth);
      const c = document.createElement('canvas');
      c.width = w; c.height = h;
      const g = c.getContext('2d');
      g.imageSmoothingQuality = 'high';
      g.drawImage(img, 0, 0, w, h);
      outs.push({ w, h, webp: c.toDataURL('image/webp', 0.82) });
    }
    return { nw: img.naturalWidth, nh: img.naturalHeight, outs };
  }, dataUrl, widths);
  manifest[key] = { ratio: +(res.nh / res.nw).toFixed(5), widths: [] };
  for (const o of res.outs) {
    for (const fmt of ['webp']) {
      const file = path.join(out, `${key}-${o.w}.${fmt}`);
      fs.writeFileSync(file, Buffer.from(o[fmt].split(',')[1], 'base64'));
    }
    manifest[key].widths.push([o.w, o.h]);
  }
  console.log(key, res.outs.map(o => `${o.w}x${o.h}`).join(' '));
}
for (const key of pngs) {
  const buf = fs.readFileSync(path.join(repo, 'lens/docs', key + '.png'));
  const w = buf.readUInt32BE(16), h = buf.readUInt32BE(20);
  fs.writeFileSync(path.join(out, `${key}-${w}.png`), buf);
  manifest[key] = { ratio: +(h / w).toFixed(5), widths: [[w, h]], png: true };
}
fs.writeFileSync(path.join(out, 'images.json'), JSON.stringify(manifest, null, 2) + '\n');
await browser.close();
