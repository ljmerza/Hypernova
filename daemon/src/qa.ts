// The testing checklist: a page the owner fills in (pass/fail, notes, pasted screenshots),
// saved to <support>/qa so the next build can be fixed from it.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { SUPPORT, readJson, writeJson, randomToken } from './util.ts'
import { CHECKLIST } from './qa-checklist.ts'

const DIR = join(SUPPORT, 'qa')
const SHOTS = join(DIR, 'shots')
mkdirSync(SHOTS, { recursive: true, mode: 0o700 })
const RESULTS = join(DIR, 'results.json')

type ItemResult = { status: 'pass' | 'fail' | 'skip' | null; note: string; shots: string[]; updatedAt: number }
type Results = { items: Record<string, ItemResult>; observations: { id: string; text: string; shots: string[]; createdAt: number }[] }
const load = (): Results => readJson<Results>(RESULTS, { items: {}, observations: [] })

export function qaState() {
  return { checklist: CHECKLIST, results: load() }
}

export function qaSave(body: any): Results {
  const r = load()
  if (body.item) {
    const cur = r.items[body.item] ?? { status: null, note: '', shots: [], updatedAt: 0 }
    if ('status' in body) cur.status = body.status
    if ('note' in body) cur.note = String(body.note ?? '').slice(0, 20000)
    if (body.removeShot) cur.shots = cur.shots.filter((s) => s !== body.removeShot)
    cur.updatedAt = Date.now()
    r.items[body.item] = cur
  }
  if (body.observation) {
    const o = body.observation
    const existing = r.observations.find((x) => x.id === o.id)
    if (o.delete) r.observations = r.observations.filter((x) => x.id !== o.id)
    else if (existing) existing.text = String(o.text ?? '').slice(0, 20000)
    else r.observations.push({ id: o.id ?? randomToken(6), text: String(o.text ?? ''), shots: [], createdAt: Date.now() })
  }
  writeJson(RESULTS, r)
  return r
}

export function qaShot(target: string, bytes: Buffer, mime: string): string {
  const ext = mime.includes('png') ? 'png' : mime.includes('webp') ? 'webp' : mime.includes('gif') ? 'gif' : 'jpg'
  const name = `${Date.now()}-${randomToken(4)}.${ext}`
  writeFileSync(join(SHOTS, name), bytes)
  const r = load()
  if (target.startsWith('obs:')) {
    const o = r.observations.find((x) => x.id === target.slice(4))
    if (o) o.shots.push(name)
  } else {
    const cur = r.items[target] ?? { status: null, note: '', shots: [], updatedAt: 0 }
    cur.shots.push(name)
    cur.updatedAt = Date.now()
    r.items[target] = cur
  }
  writeJson(RESULTS, r)
  return name
}

export function qaShotPath(name: string): string | null {
  if (!/^[\w.-]+$/.test(name)) return null
  const p = join(SHOTS, name)
  return existsSync(p) ? p : null
}

export function qaPage(): string {
  return PAGE
}

const PAGE = /* html */ `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Hypernova testing checklist</title>
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@200;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
:root{--bg:#14110E;--bg9:#1B1814;--bg8:#221F1B;--line:#2C2823;--ink:#F5F2EB;--muted:#B5B0A6;--dim:#7A7468;--accent:#7FFFA0;--amber:#E8B341;--danger:#FF7878;color-scheme:dark}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--ink);font:500 15px/22px Manrope,system-ui,sans-serif;font-feature-settings:"ss01","tnum"}
main{max-width:760px;margin:0 auto;padding:calc(env(safe-area-inset-top) + 24px) 16px 120px}
.wm{font-weight:200;font-size:22px;color:var(--muted)}.wm b{color:var(--accent);font-weight:200}
h1{font-size:28px;line-height:32px;letter-spacing:-.02em;margin:18px 0 6px}
p.lead{color:var(--muted);margin:0 0 18px}
.seam{display:flex;align-items:center;gap:10px;margin:30px 4px 12px;font-size:10px;letter-spacing:.18em;text-transform:uppercase;font-weight:600;color:var(--muted)}
.seam:after{content:"";flex:1;height:1px;background:var(--line)}.seam i{font-style:normal;color:var(--dim);letter-spacing:.09em;order:2}
.card{background:linear-gradient(rgba(34,31,27,.84),rgba(27,24,20,.86));box-shadow:inset 0 0 0 1px rgba(245,242,235,.08);border-radius:16px;padding:14px 16px;margin:10px 0}
.card.pass{box-shadow:inset 0 0 0 1px rgba(127,255,160,.35)}.card.fail{box-shadow:inset 0 0 0 1px rgba(255,120,120,.45)}.card.skip{opacity:.6}
.t{font-size:16px;line-height:21px}.how{color:var(--muted);font-size:13px;line-height:19px;margin-top:6px;white-space:pre-line}
.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px;align-items:center}
button,.btn{font:600 13px/1 Manrope,system-ui;border:0;border-radius:999px;height:36px;padding:0 16px;cursor:pointer;background:rgba(34,31,27,.3);color:var(--muted);box-shadow:inset 0 0 0 1px var(--line)}
button.on.pass{background:var(--accent);color:var(--bg);box-shadow:none}button.on.fail{background:var(--danger);color:var(--bg);box-shadow:none}button.on.skip{background:var(--dim);color:var(--bg);box-shadow:none}
textarea{width:100%;min-height:44px;margin-top:10px;background:rgba(15,13,10,.7);color:var(--ink);border:0;box-shadow:inset 0 0 0 1px var(--line);border-radius:12px;padding:10px 12px;font:500 14px/20px Manrope,system-ui;resize:vertical}
textarea:focus{outline:none;box-shadow:inset 0 0 0 1px rgba(127,255,160,.5)}
.shots{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.shots a{position:relative}.shots img{height:96px;border-radius:10px;box-shadow:0 0 0 1px var(--line);display:block}
.shots .x{position:absolute;top:4px;right:4px;width:22px;height:22px;padding:0;border-radius:50%;background:rgba(20,17,14,.85);color:var(--ink);font-size:12px}
.hint{color:var(--dim);font:400 11px/16px "JetBrains Mono",monospace}
.bar{position:fixed;left:0;right:0;bottom:0;padding:12px 16px calc(env(safe-area-inset-bottom) + 12px);background:rgba(20,17,14,.82);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);display:flex;gap:14px;justify-content:center;font:400 12px "JetBrains Mono",monospace;color:var(--muted)}
.bar b{color:var(--accent);font-weight:500}.bar .f{color:var(--danger)}
label.btn{display:inline-flex;align-items:center}
</style></head><body><main>
<div class="wm">hypernova<b>/</b></div>
<h1>Testing checklist.</h1>
<p class="lead">Go through these in order. Mark each one, write what you saw, and paste screenshots (copy an image, click into a card, then paste) or pick them from your gallery. Everything saves on your Mac as you go.</p>
<div id="list"></div>
<div class="seam">Your own observations<i>anything else</i></div>
<div id="obs"></div>
<button id="addObs">Add an observation</button>
</main>
<div class="bar"><span><b id="np">0</b> pass</span><span><span class="f" id="nf">0</span> fail</span><span id="nl">0 left</span><span id="saved"></span></div>
<script>
const Q = location.search, api = (p) => p + (p.includes('?') ? '&' : '?') + Q.slice(1);
let S = null, focused = null;
const el = (t, a = {}, ...c) => { const e = document.createElement(t); for (const [k, v] of Object.entries(a)) k.startsWith('on') ? e.addEventListener(k.slice(2), v) : e.setAttribute(k, v); for (const x of c) e.append(x); return e };
async function load() { S = await (await fetch(api('qa/state'))).json(); render() }
async function save(body) { const r = await fetch(api('qa/save'), { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }); S.results = await r.json(); tally(); flash() }
function flash() { const s = document.getElementById('saved'); s.textContent = 'saved'; clearTimeout(flash.t); flash.t = setTimeout(() => s.textContent = '', 1500) }
async function upload(target, file) { const r = await fetch(api('qa/shot?target=' + encodeURIComponent(target)), { method: 'POST', headers: { 'content-type': file.type || 'image/png' }, body: file }); if (r.ok) { await load() } }
function shotsEl(target, names, removable) { const d = el('div', { class: 'shots' }); for (const n of names) { const a = el('a', { href: api('qa/shot/' + n), target: '_blank' }, el('img', { src: api('qa/shot/' + n), alt: 'screenshot' })); if (removable) a.append(el('button', { class: 'x', title: 'Remove', onclick: (e) => { e.preventDefault(); save({ item: target, removeShot: n }).then(load) } }, '✕')); d.append(a) } return d }
function picker(target) { const i = el('input', { type: 'file', accept: 'image/*', multiple: '', style: 'display:none' }); i.addEventListener('change', async () => { for (const f of i.files) await upload(target, f) }); return el('label', { class: 'btn' }, 'Add screenshot', i) }
function render() {
  const list = document.getElementById('list'); list.innerHTML = '';
  for (const sec of S.checklist) {
    list.append(el('div', { class: 'seam' }, sec.title, el('i', {}, sec.items.length + ' checks')));
    for (const it of sec.items) {
      const r = S.results.items[it.id] || { status: null, note: '', shots: [] };
      const card = el('div', { class: 'card ' + (r.status || ''), tabindex: '0', onfocusin: () => focused = it.id, onclick: () => focused = it.id });
      card.append(el('div', { class: 't' }, it.title));
      if (it.how) card.append(el('div', { class: 'how' }, it.how));
      const row = el('div', { class: 'row' });
      for (const [k, label] of [['pass', 'Works'], ['fail', 'Problem'], ['skip', 'Skip']]) row.append(el('button', { class: k + (r.status === k ? ' on' : ''), onclick: () => save({ item: it.id, status: r.status === k ? null : k }).then(render) }, label));
      row.append(picker(it.id));
      card.append(row);
      const ta = el('textarea', { placeholder: 'What did you see? (optional)' }); ta.value = r.note || '';
      ta.addEventListener('input', () => { clearTimeout(ta.t); ta.t = setTimeout(() => save({ item: it.id, note: ta.value }), 600) });
      card.append(ta, shotsEl(it.id, r.shots || [], true));
      list.append(card);
    }
  }
  const obs = document.getElementById('obs'); obs.innerHTML = '';
  for (const o of S.results.observations) {
    const card = el('div', { class: 'card', onfocusin: () => focused = 'obs:' + o.id, onclick: () => focused = 'obs:' + o.id });
    const ta = el('textarea', { placeholder: 'What did you notice?' }); ta.value = o.text;
    ta.addEventListener('input', () => { clearTimeout(ta.t); ta.t = setTimeout(() => save({ observation: { id: o.id, text: ta.value } }), 600) });
    card.append(ta, el('div', { class: 'row' }, picker('obs:' + o.id), el('button', { onclick: () => save({ observation: { id: o.id, delete: true } }).then(load) }, 'Delete')), shotsEl('obs:' + o.id, o.shots, false));
    obs.append(card);
  }
  tally();
}
function tally() { let p = 0, f = 0, n = 0; for (const s of S.checklist) for (const it of s.items) { n++; const st = S.results.items[it.id]?.status; if (st === 'pass') p++; if (st === 'fail') f++ } const done = Object.values(S.results.items).filter(x => x.status).length; document.getElementById('np').textContent = p; document.getElementById('nf').textContent = f; document.getElementById('nl').textContent = (n - done) + ' left' }
document.getElementById('addObs').onclick = async () => { const id = Math.random().toString(36).slice(2, 10); await save({ observation: { id, text: '' } }); await load(); focused = 'obs:' + id };
window.addEventListener('paste', async (e) => { if (!focused) return; for (const item of e.clipboardData?.items || []) if (item.type.startsWith('image/')) { e.preventDefault(); await upload(focused, item.getAsFile()) } });
load();
</script></body></html>`
