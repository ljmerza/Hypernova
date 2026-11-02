// A toggle that reaches a new process before it has taken the server in tries again (mcp-probe.ts).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { toggleServer, useProbe } from '../src/mcp-probe.ts'

const standIn = (failures: number) => {
  const calls = { toggles: 0 }
  useProbe(async (_folder, _waiting, work) =>
    work({
      mcpServerStatus: async () => [],
      reconnectMcpServer: async () => {},
      toggleMcpServer: async () => {
        calls.toggles++
        if (calls.toggles <= failures) throw new Error('Server not found: tiny')
      },
    } as never),
  )
  return calls
}

test('a server the new process has not taken in yet gets more tries', async () => {
  const calls = standIn(2)
  await toggleServer('/tmp', [], 'tiny', false)
  assert.equal(calls.toggles, 3)
  useProbe(null)
})

test('a server that is really gone still fails', async () => {
  const calls = standIn(Number.POSITIVE_INFINITY)
  await assert.rejects(toggleServer('/tmp', [], 'tiny', false, 400), /Server not found/)
  assert.ok(calls.toggles >= 2)
  useProbe(null)
})

test('any other failure is not tried again', async () => {
  let toggles = 0
  useProbe(async (_folder, _waiting, work) =>
    work({
      mcpServerStatus: async () => [],
      reconnectMcpServer: async () => {},
      toggleMcpServer: async () => {
        toggles++
        throw new Error('no such thing')
      },
    } as never),
  )
  await assert.rejects(toggleServer('/tmp', [], 'tiny', false), /no such thing/)
  assert.equal(toggles, 1)
  useProbe(null)
})
