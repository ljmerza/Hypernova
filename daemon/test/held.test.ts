// Fork: held prompts (held.ts): the line itself, with stand-in ops. A prompt held while Claude waits
// on the owner goes in once the session is ready, oldest first; one the box doesn't take stays
// first in line; one Enter didn't send is "returned"; a session that ended drops them after a
// grace, naming them so the phone takes their text back; and the line survives a restart.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-held-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const held = await import('../src/held.ts')
const { ATTACH_DIR } = await import('../src/attachments.ts')

type Sent = { sid: string; prompt: string; clientId: string | null }
const ready = new Map<string, 'ready' | 'wait' | 'ended'>()
const sent: Sent[] = []
const unsent: { sid: string; u: any }[] = []
const changed: string[] = []
/** Errors the next sends throw, by session (a code each). */
const fail = new Map<string, string[]>()
held.useHeldOps({
  ready: (sid) => ready.get(sid) ?? 'wait',
  async send(sid, prompt, clientId) {
    const code = fail.get(sid)?.shift()
    if (code) throw Object.assign(new Error(code), { code })
    sent.push({ sid, prompt, clientId })
  },
  changed: (sid) => void changed.push(sid),
  unsent: (sid, u) => void unsent.push({ sid, u }),
})
/** A clock past any cool-down. */
let clock = 1_000_000
const later = () => (clock += 10_000)

test('held prompts go in order once the session is ready, and not before', async () => {
  held.hold('s1', 'first', 'c1')
  held.hold('s1', 'second', 'c2')
  assert.deepEqual(held.heldOf('s1').map((e) => e.prompt), ['first', 'second'])
  assert.deepEqual(held.heldView('s1').held?.map((h) => [h.text, h.clientId]), [['first', 'c1'], ['second', 'c2']])
  assert.ok(changed.includes('s1'), 'its clients hear of it')
  await held.drain('s1', () => later())
  assert.equal(sent.length, 0, 'waiting on the owner: nothing typed')
  ready.set('s1', 'ready')
  await held.drain('s1', () => later())
  assert.deepEqual(sent.map((s) => [s.prompt, s.clientId]), [['first', 'c1'], ['second', 'c2']])
  assert.deepEqual(held.heldView('s1'), {}, 'a session with none looks as before')
})

test('a try the box doesn’t take keeps it first in line, and the next try waits a moment', async () => {
  sent.length = 0
  ready.set('s2', 'ready')
  fail.set('s2', ['typing'])
  held.hold('s2', 'one', null)
  held.hold('s2', 'two', null)
  // hold() looks at the line soon after; here it's worked through by hand.
  const t = later()
  await held.drain('s2', () => t)
  assert.equal(sent.length, 0)
  assert.equal(held.heldOf('s2').length, 2)
  await held.drain('s2', () => t + 100)
  assert.equal(sent.length, 0, 'within the cool-down')
  await held.drain('s2', () => later())
  assert.deepEqual(sent.map((s) => s.prompt), ['one', 'two'])
})

test('Enter didn’t send it: it leaves the line as "returned", naming it; the rest stay', async () => {
  sent.length = 0
  unsent.length = 0
  ready.set('s3', 'ready')
  fail.set('s3', ['not_sent', 'typing'])
  held.hold('s3', 'stuck in the box', 'c3')
  held.hold('s3', 'after it', 'c4')
  await held.drain('s3', () => later())
  assert.deepEqual(held.heldOf('s3').map((e) => e.prompt), ['after it'])
  assert.equal(unsent[0]?.u.reason, 'returned')
  assert.deepEqual(unsent[0]?.u.prompts, [{ text: 'stuck in the box', clientId: 'c3' }])
  held.removeHeld('s3', held.heldOf('s3')[0]!.id)
})

test('the conversation went on elsewhere: every held prompt is dropped, named', async () => {
  unsent.length = 0
  ready.set('s4', 'ready')
  fail.set('s4', ['switched'])
  held.hold('s4', 'a', 'ca')
  held.hold('s4', 'b', 'cb')
  await held.drain('s4', () => later())
  assert.equal(held.heldOf('s4').length, 0)
  assert.equal(unsent[0]?.u.reason, 'dropped')
  assert.deepEqual(unsent[0]?.u.prompts.map((p: any) => p.clientId), ['ca', 'cb'])
})

test('an ended session keeps them for a grace (it may be resumed), then drops them', () => {
  unsent.length = 0
  ready.set('s5', 'ended')
  held.hold('s5', 'too late', 'c5')
  const t0 = Date.now()
  held.tick(t0)
  held.tick(t0 + held.HELD_ENDED_MS - 1)
  assert.equal(held.heldOf('s5').length, 1, 'still within the grace')
  held.tick(t0 + held.HELD_ENDED_MS)
  assert.equal(held.heldOf('s5').length, 0)
  assert.equal(unsent[0]?.u.reason, 'dropped')
  assert.match(unsent[0]?.u.text, /session ended/)
})

test('edit keeps its files; edit and remove answer gone for one that left', async () => {
  const file = join(ATTACH_DIR, 'shot.png')
  mkdirSync(ATTACH_DIR, { recursive: true })
  writeFileSync(file, 'png')
  const e = held.hold('s6', `@"${file}"\n\nlook at this`, null)
  assert.deepEqual(held.heldView('s6').held?.[0]?.files?.map((f) => f.name), ['shot.png'])
  assert.equal(held.heldView('s6').held?.[0]?.text, 'look at this')
  assert.equal(held.editHeld('s6', e.id, 'look at this, closely'), 'ok')
  assert.equal(held.heldOf('s6')[0]?.prompt, `@"${file}"\n\nlook at this, closely`)
  assert.equal(held.editHeld('s6', 'nope', 'x'), 'gone')
  assert.equal(held.removeHeld('s6', e.id), 'ok')
  assert.equal(held.removeHeld('s6', e.id), 'gone')
})

test('one being typed right now can’t be edited or removed', async () => {
  let release!: () => void
  const s7 = 's7'
  held.hold(s7, 'slow', null)
  ready.set(s7, 'ready')
  held.useHeldOps({
    ready: (sid) => ready.get(sid) ?? 'wait',
    send: () => new Promise<void>((ok) => (release = ok)),
    changed: () => {},
    unsent: () => {},
  })
  const going = held.drain(s7, () => later())
  const id = held.heldOf(s7)[0]!.id
  assert.equal(held.editHeld(s7, id, 'changed'), 'sending')
  assert.equal(held.removeHeld(s7, id), 'sending')
  release()
  await going
  assert.equal(held.heldOf(s7).length, 0)
})

test('the line is kept in held.json and read back by a daemon started again', async () => {
  held.hold('s8', 'survives a restart', 'c8')
  const file = join(process.env.HYPERNOVA_SUPPORT!, 'held.json')
  assert.ok(existsSync(file))
  assert.equal(JSON.parse(readFileSync(file, 'utf8')).s8[0].prompt, 'survives a restart')
  // A second copy of the module, as a new daemon reads it (the query makes it a module of its own).
  const again = (await import(`../src/held.ts?${'restart'}`)) as typeof held
  assert.deepEqual(again.heldOf('s8').map((e) => [e.prompt, e.clientId]), [['survives a restart', 'c8']])
})
