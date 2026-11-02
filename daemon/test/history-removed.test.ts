// History after an upgrade (docs/DECISIONS.md, "Delete takes a session off Home, History brings it
// back"): what <support>/deleted-sessions.json holds from when Delete hid a session everywhere shows in
// History now, however old, with nothing lost, and resuming takes its line out. And what Claude Code's
// own /resume would offer of a transcript (resume-offer.ts).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-history-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
for (const d of [process.env.HYPERNOVA_SUPPORT, process.env.HOME]) mkdirSync(d, { recursive: true })
const dir = join(process.env.CLAUDE_CONFIG_DIR, 'projects', 'a-project')
mkdirSync(dir, { recursive: true })

function transcript(id: string, daysAgo: number, extra: object = {}, folder = dir): string {
  const p = join(folder, `${id}.jsonl`)
  writeFileSync(p, JSON.stringify({ parentUuid: null, type: 'user', cwd: scratch, sessionId: id, entrypoint: 'cli', timestamp: new Date().toISOString(), message: { role: 'user', content: `Words of ${id}` }, ...extra }) + '\n')
  const t = new Date(Date.now() - daysAgo * 24 * 3600_000)
  utimesSync(p, t, t)
  return p
}

// As a server has them from before: one recent, one past where the start's look reaches, and one
// /resume itself wouldn't offer. Their lines are as the old Delete wrote them.
const AT = Date.now() - 3600_000
const FILE = join(process.env.HYPERNOVA_SUPPORT, 'deleted-sessions.json')
writeFileSync(FILE, JSON.stringify({ 'gone-recent': { at: AT, pid: 123 }, 'gone-old': { at: AT, pid: null }, 'gone-print': { at: AT, pid: null }, 'gone-no-transcript': { at: AT, pid: null } }))
transcript('gone-recent', 1)
transcript('gone-old', 90)
transcript('gone-print', 90, { entrypoint: 'sdk-cli' })
transcript('kept-old', 90)
transcript('rewound-old', 90)
writeFileSync(join(process.env.HYPERNOVA_SUPPORT, 'rewound.json'), JSON.stringify({ 'rewound-old': { to: 'kept-old', at: AT, pid: null } }))

const hub = await import('../src/hub.ts')
const { deletedOf, undelete } = await import('../src/deleted.ts')
const { continuedIn, offeredByResume, offeredFromText } = await import('../src/resume-offer.ts')

test('sessions deleted before the upgrade show in History, each ended and titled, and the file is left as it was', async () => {
  const before = readFileSync(FILE, 'utf8')
  hub.discover()
  assert.equal(hub.getSession('gone-recent')?.state, 'ended', 'from the start’s look')
  await hub.discoverOlder()
  for (const id of ['gone-recent', 'gone-old', 'gone-print']) {
    assert.equal(hub.getSession(id)?.state, 'ended', id)
    assert.equal(hub.getSession(id)?.title, `Words of ${id}`)
    assert.equal(hub.view(hub.getSession(id)!).endedBy, null, 'not kept on Home as one a restart cut short')
  }
  assert.equal(hub.getSession('gone-no-transcript'), undefined, 'nothing to show of one with no transcript')
  assert.equal(hub.getSession('kept-old')?.state, 'ended', 'what /resume would offer, from long ago')
  assert.equal(hub.getSession('rewound-old'), undefined, 'a transcript a rewind replaced stays hidden')
  assert.equal(readFileSync(FILE, 'utf8'), before, 'an upgrade alone changes nothing in it')
})

test('resuming takes its line out; the others’ stay', () => {
  undelete('gone-old')
  assert.equal(deletedOf('gone-old'), null)
  const left = JSON.parse(readFileSync(FILE, 'utf8'))
  assert.deepEqual(Object.keys(left).sort(), ['gone-no-transcript', 'gone-print', 'gone-recent'])
  assert.deepEqual(left['gone-recent'], { at: AT, pid: 123 })
  hub.upsertSession('gone-old', { state: 'idle' })
  assert.equal(hub.getSession('gone-old')?.state, 'idle', 'runs like any other')
  hub.upsertSession('gone-recent', { state: 'idle' })
  assert.equal(hub.getSession('gone-recent')?.state, 'ended', 'one still removed doesn’t')
})

test('what /resume offers, and what it passes over, as Claude Code 2.1.316 decides', () => {
  const user = (o: object = {}, text = 'hello') => JSON.stringify({ parentUuid: null, type: 'user', entrypoint: 'cli', message: { content: text }, ...o })
  assert.equal(offeredFromText(user(), ''), true, 'a terminal’s or a job’s (sessionKind bg)')
  assert.equal(offeredFromText(user({ sessionKind: 'bg' }), ''), true)
  assert.equal(offeredFromText(user({ isSidechain: true }), ''), false, 'an agent’s')
  assert.equal(offeredFromText(user({ teamName: 'crew' }), ''), false, 'a teammate’s')
  assert.equal(offeredFromText(user({ sessionKind: 'daemon' }), ''), false)
  assert.equal(offeredFromText(user({ sessionKind: 'daemon-worker' }), ''), false)
  for (const e of ['sdk-cli', 'sdk-ts', 'sdk-py']) assert.equal(offeredFromText(user({ entrypoint: e }), ''), false, e)
  assert.equal(offeredFromText('{"type":"summary","summary":"x"}', user({ entrypoint: undefined, sessionKind: undefined }).replace('"entrypoint":"cli"', '') + '\n{"entrypoint":"sdk-ts"}'), false, 'the tail says when the head doesn’t')
  assert.equal(offeredFromText(user({}, '<command-name>/loop</command-name> 5m check'), ''), false, 'a /loop session')
  assert.equal(offeredFromText(`${user({ isMeta: true }, 'caveat')}\n${user({}, 'real')}`, ''), true)
})

test('a transcript with no message, and one that went on in another session, aren’t offered', () => {
  const titled = join(dir, 'just-a-title.jsonl')
  writeFileSync(titled, '{"type":"custom-title","customTitle":"x","sessionId":"just-a-title"}\n')
  assert.equal(offeredByResume(titled), false)
  assert.equal(offeredByResume(join(dir, 'not-there.jsonl')), false)
  const moved = transcript('moved-on', 2)
  writeFileSync(moved, readFileSync(moved, 'utf8') + JSON.stringify({ type: 'continued-in', sessionId: 'moved-on', continuedInSessionId: 'moved-to' }) + '\n')
  assert.equal(continuedIn(readFileSync(moved, 'utf8')), 'moved-to')
  assert.equal(offeredByResume(moved), true, 'where it went has no transcript: it’s still the conversation')
  transcript('moved-to', 2)
  assert.equal(offeredByResume(moved), false)
  // Something said after that move: this one is the conversation again.
  const later = transcript('moved-back', 2)
  writeFileSync(later, readFileSync(later, 'utf8') + JSON.stringify({ type: 'continued-in', sessionId: 'moved-back', continuedInSessionId: 'moved-to' }) + '\n' + JSON.stringify({ type: 'assistant', message: { stop_reason: 'end_turn' } }) + '\n')
  assert.equal(offeredByResume(later), true)
})
