// Workflows' contract (automations/contract.ts; docs/PROTOCOL.md, "Automations", "Workflows"): the
// rule that decides what a client without steps reads as `action`, `then` as outputs, and a 400's
// field. Pure functions, no engine.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ERROR_CODES, FIRST_STEP_ID, bad, mirrorAction, outputsOfThen, stepOfAction, type Action, type Output, type Step } from '../src/automations/contract.ts'

const start: Action = { kind: 'start', projectPath: '/p/hypernova', prompt: 'Summarise what changed', providerId: 'anthropic', model: null, permissionMode: 'dontAsk', allowedTools: ['Bash(git log:*)'], then: 'notify' }
const cont: Action = { kind: 'continue', target: 'working', prompt: 'Wrap up', providerId: null, then: 'notify' }
const notify: Action = { kind: 'notify', text: 'Hello {project}' }

test('a one-step automation reads back as the very action an old client saved', () => {
  for (const a of [start, { ...start, then: 'none' } as Action, cont, notify]) {
    const { step, outputs } = stepOfAction(a)
    assert.equal(step.id, FIRST_STEP_ID)
    assert.equal(step.if, null)
    assert.deepEqual(mirrorAction([step], outputs), a)
  }
})

test('then as outputs: notify, none', () => {
  assert.deepEqual(outputsOfThen('notify'), [{ id: 'ou_0', text: null, kind: 'notify' }])
  assert.deepEqual(outputsOfThen('none'), [])
})

test('anything an old client cannot show exactly reads as a kind it does not know', () => {
  const one = stepOfAction(start).step
  const ask: Step = { id: 'st_ask', if: null, kind: 'ask', question: 'Post this review?', answers: ['Approve', 'Skip'], default: 'Skip', timeoutMin: 10 }
  const shell: Step = { id: 'st_sh', if: null, kind: 'shell', projectPath: '/p/hypernova', command: 'npm test', timeoutSec: 60 }
  const webhook: Output = { id: 'ou_1', text: null, kind: 'webhook', preset: 'slack', host: 'hooks.slack.com', last4: 'x9Qa' }
  const workflow = (n: number) => ({ kind: 'workflow', steps: n })
  // Several steps, an ask, a shell.
  assert.deepEqual(mirrorAction([one, { ...ask, if: { on: 'ok' } }], []), workflow(2))
  assert.deepEqual(mirrorAction([ask], []), workflow(1))
  assert.deepEqual(mirrorAction([shell], []), workflow(1))
  // A guard, a continue of an earlier step's session.
  assert.deepEqual(mirrorAction([{ ...one, if: { on: 'failed' } }], []), workflow(1))
  assert.deepEqual(mirrorAction([{ ...stepOfAction(cont).step, target: 'step:st_a' } as Step], []), workflow(1))
  // Outputs then can't say: a webhook, two, a text of its own, any on a notify step.
  assert.deepEqual(mirrorAction([one], [webhook]), workflow(1))
  assert.deepEqual(mirrorAction([one], [...outputsOfThen('notify'), { id: 'ou_2', text: null, kind: 'notify' }]), workflow(1))
  assert.deepEqual(mirrorAction([one], [{ id: 'ou_1', text: 'Done: {prev}', kind: 'notify' }]), workflow(1))
  assert.deepEqual(mirrorAction([stepOfAction(notify).step], outputsOfThen('notify')), workflow(1))
})

test('a 400 names the field it is about; the error codes are listed once per status', () => {
  const e = bad('bad_cron', 'Five fields: minute, hour, day, month, weekday.', 'trigger.expr')
  assert.equal(e.status, 400)
  assert.deepEqual(e.extra, { field: 'trigger.expr' })
  assert.equal(bad('too_many', '50 automations is the most.').extra, undefined)
  for (const codes of Object.values(ERROR_CODES)) assert.equal(new Set(codes).size, codes.length)
})
