// After a restart of the Mac (docs/PROTOCOL.md, "After a restart of the Mac"): every shared
// terminal went with it, so the daemon finds each conversation ended. The ones it last saw open,
// active in the 48 hours before, come back marked `endedBy: "mac_restart"` for the phone to keep
// on Home; each opens with its whole history from its transcript, once, in order of activity.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomBytes, randomUUID } from 'node:crypto'

const scratch = mkdtempSync(join(tmpdir(), 'hn-reboot-'))
const support = join(scratch, 'support')
process.env.HYPERNOVA_SUPPORT = support
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.ZDOTDIR = join(scratch, 'zdot')
const H = 3600_000
const now = Date.now()
// The Mac came back ten minutes ago.
const boot = now - 10 * 60_000
process.env.HYPERNOVA_BOOTED_AT = String(boot)
for (const d of [support, process.env.ZDOTDIR, join(process.env.CLAUDE_CONFIG_DIR, 'sessions')]) mkdirSync(d, { recursive: true })

const proj = (name: string) => {
  const p = join(scratch, 'projects', name)
  mkdirSync(p, { recursive: true })
  return p
}
const alpha = proj('alpha')
const beta = proj('beta')
const slug = (cwd: string) => cwd.replace(/[^a-zA-Z0-9]/g, '-')

/** A transcript as Claude Code writes one, last written at [at]; [image]: bytes of a picture pasted with the first prompt. */
function transcript(id: string, cwd: string, prompts: string[], at: number, o: { folder?: string; image?: number } = {}): string {
  const dir = join(process.env.CLAUDE_CONFIG_DIR!, 'projects', o.folder ?? slug(cwd))
  mkdirSync(dir, { recursive: true })
  const lines: object[] = [{ type: 'permission-mode', permissionMode: 'default', sessionId: id }]
  let parent: string | null = null
  let t = at - prompts.length * 60_000
  for (const p of prompts) {
    const content: object[] = [{ type: 'text', text: p }]
    if (o.image && parent === null) content.push({ type: 'image', source: { type: 'base64', media_type: 'image/png', data: randomBytes(o.image).toString('base64') } })
    for (const m of [
      { type: 'user', message: { role: 'user', content } },
      { type: 'assistant', message: { id: `msg_${randomBytes(6).toString('hex')}`, role: 'assistant', model: 'claude-test', content: [{ type: 'text', text: `Done: ${p}` }] } },
    ]) {
      const uuid: string = randomUUID()
      lines.push({ parentUuid: parent, isSidechain: false, cwd, sessionId: id, timestamp: new Date((t += 20_000)).toISOString(), uuid, ...m })
      parent = uuid
    }
  }
  const file = join(dir, `${id}.jsonl`)
  writeFileSync(file, lines.map((l) => JSON.stringify(l)).join('\n') + '\n')
  utimesSync(file, new Date(at), new Date(at))
  return file
}

// Phone sessions in shared terminals: two open when the Mac went down, one its owner had quit,
// one open but idle for days, one whose transcript sits in another folder (a symlinked project)
// and is older than History reaches. One terminal session open too, and a daemon (SDK) session.
const ids = {
  open1: '10000000-0000-4000-8000-000000000001',
  open2: '10000000-0000-4000-8000-000000000002',
  quit: '10000000-0000-4000-8000-000000000003',
  stale: '10000000-0000-4000-8000-000000000004',
  moved: '10000000-0000-4000-8000-000000000005',
  sdk: '10000000-0000-4000-8000-000000000006',
  term: '20000000-0000-4000-8000-000000000001',
  pictures: '20000000-0000-4000-8000-000000000002',
  old: '20000000-0000-4000-8000-000000000003',
}
const managed = (id: string, cwd: string, createdAt: number, engine: 'term' | 'sdk' = 'term') => ({ id, cwd, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt, title: `[Hypernova test] ${id.slice(-1)}`, engine })
writeFileSync(
  join(support, 'sessions.json'),
  JSON.stringify([
    managed(ids.open1, alpha, boot - 30 * H),
    managed(ids.open2, beta, boot - 5 * H),
    managed(ids.quit, alpha, boot - 6 * H),
    managed(ids.stale, beta, boot - 80 * H),
    managed(ids.moved, alpha, now - 41 * 24 * H),
    managed(ids.sdk, beta, boot - 2 * H, 'sdk'),
  ]),
)
transcript(ids.open1, alpha, ['[Hypernova test] plan the release', 'and list the open issues'], boot - 2 * H)
transcript(ids.open2, beta, ['[Hypernova test] fix the login bug'], boot - 1 * H)
transcript(ids.quit, alpha, ['[Hypernova test] a quick question'], boot - 4 * H)
transcript(ids.stale, beta, ['[Hypernova test] an idle one'], boot - 72 * H)
transcript(ids.moved, alpha, ['[Hypernova test] in the real folder'], now - 40 * 24 * H, { folder: '-private-somewhere-else-alpha' })
transcript(ids.term, beta, ['Tidy the README'], boot - 30 * 60_000)
// Its first line is over 256 KB (screenshots pasted with the first prompt).
transcript(ids.pictures, beta, ['Make it look like these screenshots', 'Use the second font'], boot - 3 * H, { image: 360_000 })
transcript(ids.old, beta, ['From last month'], now - 31 * 24 * H)
// More conversations than History used to hold (40).
const fillers = Array.from({ length: 45 }, (_, i) => `30000000-0000-4000-8000-${String(i).padStart(12, '0')}`)
fillers.forEach((id, i) => transcript(id, beta, [`[Hypernova test] filler ${i}`], now - (2 + i * 10) * H))
// What the daemon wrote last, before the Mac went down.
writeFileSync(join(support, 'open-sessions.json'), JSON.stringify({ at: boot - 5 * 60_000, open: [ids.open1, ids.open2, ids.stale, ids.term, ids.sdk], cut: {} }))

const hub = await import('../src/hub.ts')
const reboot = await import('../src/reboot.ts')

const procs: ChildProcess[] = []
after(() => {
  for (const p of procs) p.kill('SIGKILL')
  rmSync(scratch, { recursive: true, force: true })
})
const view = (id: string) => hub.listSessions().find((s) => s.id === id)
const saved = () => JSON.parse(readFileSync(join(support, 'open-sessions.json'), 'utf8'))

hub.discover()

test('after a restart of the Mac, the conversations it cut short are marked; the rest are plain ended', () => {
  for (const id of [ids.open1, ids.open2, ids.term]) assert.deepEqual([view(id)?.state, view(id)?.endedBy], ['ended', 'mac_restart'], id)
  // Quit before the restart, or idle for days before it: History.
  for (const id of [ids.quit, ids.stale, ids.pictures]) assert.deepEqual([view(id)?.state, view(id)?.endedBy], ['ended', null], id)
  // The daemon's own sessions don't run in a terminal: nothing to cut.
  assert.deepEqual([view(ids.sdk)?.state, view(ids.sdk)?.endedBy], ['idle', null])
  assert.deepEqual(saved().cut, { [ids.open1]: boot, [ids.open2]: boot, [ids.term]: boot })
  assert.deepEqual(saved().open, [])
})

test('each conversation is listed once, in order of its last activity, with its whole history', () => {
  const list = hub.listSessions()
  assert.equal(new Set(list.map((s) => s.id)).size, list.length)
  const at = list.map((s) => s.lastActivityAt)
  assert.deepEqual(at, [...at].sort((a, b) => b - a))
  // A phone session is as recent as its transcript, not as when it started.
  assert.ok(Math.abs(view(ids.open1)!.lastActivityAt - (boot - 2 * H)) < 2)
  assert.equal(view(ids.open1)?.title, '[Hypernova test] 1')
  assert.deepEqual(
    hub.eventsFor(ids.open1).filter((e) => e.kind === 'user').map((e) => (e as { text: string }).text),
    ['[Hypernova test] plan the release', 'and list the open issues'],
  )
})

test('a phone session finds its transcript in another folder, however old', () => {
  const s = view(ids.moved)
  assert.ok(Math.abs(s!.lastActivityAt - (now - 40 * 24 * H)) < 2000)
  assert.ok(s!.lastActivityAt > s!.createdAt)
  assert.equal(hub.eventsFor(ids.moved).filter((e) => e.kind === 'user').length, 1)
})

test('a transcript whose first line is larger than the head is still listed, with its folder and title', () => {
  const s = view(ids.pictures)
  assert.equal(s?.cwd, beta)
  // Its first prompt, as the phone shows it (the line after it says a picture came with it).
  assert.equal(s?.title.split('\n')[0], 'Make it look like these screenshots')
  // Where it left off, for its card, though nobody has opened it.
  assert.equal(s?.lastText, 'Done: Use the second font')
})

test('History reaches back 30 days, past the 40 conversations it used to stop at', () => {
  assert.equal(fillers.filter((id) => view(id)).length, fillers.length)
  assert.equal(view(ids.old), undefined)
})

test('a prompt resumes a cut-short conversation: the mark goes, and a later end is a plain one', () => {
  hub.upsertSession(ids.open1, { state: 'starting' })
  assert.equal(view(ids.open1)?.endedBy, null)
  assert.equal(saved().cut[ids.open1], undefined)
  hub.upsertSession(ids.open1, { state: 'ended' })
  assert.equal(view(ids.open1)?.endedBy, null)
})

test("Claude Code's registry: an entry from before the boot is stale, one since is the conversation running again", () => {
  const registry = join(process.env.CLAUDE_CONFIG_DIR!, 'sessions')
  // The daemon's own pid is alive: only its start time, before the boot, says the entry is stale.
  writeFileSync(join(registry, `${process.pid}.json`), JSON.stringify({ pid: process.pid, sessionId: ids.quit, cwd: alpha, kind: 'interactive', status: 'idle', startedAt: boot - 3 * H, updatedAt: boot - 3 * H }))
  const p = spawn('sleep', ['60'], { stdio: 'ignore' })
  procs.push(p)
  writeFileSync(join(registry, `${p.pid}.json`), JSON.stringify({ pid: p.pid, sessionId: ids.open2, cwd: beta, kind: 'interactive', status: 'idle', startedAt: Date.now(), updatedAt: Date.now() }))
  hub.pollRegistry()
  assert.equal(view(ids.quit)?.state, 'ended')
  assert.deepEqual([view(ids.open2)?.state, view(ids.open2)?.endedBy], ['idle', null])
  assert.equal(saved().cut[ids.open2], undefined)
  assert.ok(saved().open.includes(ids.open2))
})

test('cutShort: only after a boot, the 48 hours before it, the 10 most recent', () => {
  const at = (n: number) => boot - n * H
  const open = Array.from({ length: 13 }, (_, i) => `s${i}`)
  const last = (id: string) => (id === 's12' ? at(49) : id === 's11' ? boot + 60_000 : at(Number(id.slice(1)) + 1))
  // Written after the boot: only the daemon restarted.
  assert.deepEqual(reboot.cutShort({ at: boot + 1, open, cut: {} }, boot, last), {})
  const cut = reboot.cutShort({ at: boot - 1, open, cut: {} }, boot, last)
  // s11 has been active since the boot, s12 not for 49 hours; of the other 11, the 10 most recent.
  assert.deepEqual(Object.keys(cut).sort(), ['s0', 's1', 's2', 's3', 's4', 's5', 's6', 's7', 's8', 's9'])
  assert.ok(Object.values(cut).every((v) => v === boot))
  // One the daemon doesn't know (or that runs) can't have been cut short.
  assert.deepEqual(reboot.cutShort({ at: 0, open: ['x'], cut: {} }, boot, () => undefined), {})
})

test('an end in the last minute before the daemon goes still counts as open; the mark runs out after 48 hours', () => {
  const t = Date.now()
  reboot.noteOpen(['q1'], t)
  reboot.noteOpen([], t + 30_000)
  assert.ok(saved().open.includes('q1'))
  reboot.noteOpen([], t + 61_000)
  assert.ok(!saved().open.includes('q1'))
  // 48 hours after the Mac came back, the marks still there run out and their sessions are news.
  assert.ok(reboot.cutByRestart(ids.term, boot + 47 * H))
  assert.deepEqual(reboot.noteOpen([], boot + 48 * H), [ids.term])
  assert.equal(reboot.cutByRestart(ids.term), false)
  assert.equal(view(ids.term)?.endedBy, null)
})
