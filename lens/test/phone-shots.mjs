// The phone screen at a phone's size (390 x 844 @2x): before pairing, and with sample data from top
// to bottom, including a Telescope picture. Needs the dev server or `vite preview` running.
//   node test/phone-shots.mjs <outDir> [baseUrl]
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const [out = 'shots/phone', base = 'http://127.0.0.1:5177'] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, reducedMotion: 'no-preference' });
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', e => errors.push(e.message));
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

const shot = async (name) => { await page.waitForTimeout(400); await page.screenshot({ path: `${out}/${name}.png` }); console.log(`${out}/${name}.png`); };
const scrollTo = async (sel) => { await page.evaluate(s => document.querySelector(s)?.scrollIntoView({ block: 'start' }), sel); await page.waitForTimeout(300); };

await page.goto(`${base}/?quiet`);
await page.waitForTimeout(2600);           // the bridge wait (1.5 s) in a plain browser
await shot('unpaired');

await page.goto(`${base}/?demo=1&scene=telescope&quiet`);
await page.waitForTimeout(4000);
await shot('home');
await scrollTo('.lens-frame');
await page.evaluate(() => scrollBy(0, -60));
await shot('mirror');
await scrollTo('.shot-card');
await page.evaluate(() => scrollBy(0, -60));
await shot('telescope');
await page.click('.shot-card .shot');
await shot('telescope-full');
await page.click('.viewer button');
const settings = await page.$$('.hn-seam .hn-label');
for (const s of settings) if ((await s.textContent())?.trim() === 'Settings') { await s.scrollIntoViewIfNeeded(); break; }
await page.evaluate(() => scrollBy(0, 300));
await shot('settings');
await page.evaluate(() => scrollTo(0, document.body.scrollHeight));
await shot('bottom');

if (errors.length) { console.error('page errors:\n' + errors.join('\n')); process.exitCode = 1; }
await browser.close();
