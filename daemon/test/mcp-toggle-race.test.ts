// What a toggle answers, with a stand-in for the process (mcp-probe.ts): a server the new process hasn't taken in gets more tries,
// and a 200 means it's in the file. Every file here is a scratch one.
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, beforeEach, test } from 'node:test'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-mcp-toggle-')))
process.env.HOME = join(scratch, 'home')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(process.env.CLAUDE_CONFIG_DIR, { recursive: true })
const { toggleServer, useProbe, useSaveWait } = await import('../src/mcp-probe.ts')

const folder = join(scratch, 'app')
mkdirSync(folder, { recursive: true })
const file = join(process.env.CLAUDE_CONFIG_DIR, '.claude.json')
const entry = () => JSON.parse(readFileSync(file, 'utf8')).projects[folder]
const write = (o: object) => writeFileSync(file, JSON.stringify({ projects: { [folder]: o } }))
/** What Claude Code's writer does for one toggle. */
const applied = (name: string, enabled: boolean) => {
  const e = entry()
  const list: string[] = e.disabledMcpServers ?? []
  e.disabledMcpServers = enabled ? list.filter((n) => n !== name) : [...new Set([...list, name])]
  write(e)
}

beforeEach(() => {
  write({})
  useSaveWait(8000)
})
after(() => {
  useProbe(null)
  rmSync(scratch, { recursive: true, force: true })
})

const standIn = (failures: number) => {
  const calls = { toggles: 0 }
  useProbe(async (_folder, _waiting, work) =>
    work({
      mcpServerStatus: async () => [],
      reconnectMcpServer: async () => {},
      toggleMcpServer: async (name: string, enabled: boolean) => {
        calls.toggles++
        if (calls.toggles <= failures) throw new Error('Server not found: tiny')
        applied(name, enabled)
      },
    } as never),
  )
  return calls
}

test('a server the new process has not taken in yet gets more tries', async () => {
  const calls = standIn(2)
  await toggleServer(folder, [], 'tiny', false)
  assert.equal(calls.toggles, 3)
})

test('a server that is really gone still fails', async () => {
  const calls = standIn(Number.POSITIVE_INFINITY)
  await assert.rejects(toggleServer(folder, [], 'tiny', false, 400), /Server not found/)
  assert.ok(calls.toggles >= 2)
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
  await assert.rejects(toggleServer(folder, [], 'tiny', false), /no such thing/)
  assert.equal(toggles, 1)
})

/** As Claude Code does: resolves at once, and writes a moment later. The process is up until [work] ends. */
const writesLater = (ms: number, log: string[] = []) =>
  useProbe(async (_folder, _waiting, work) => {
    log.push('started')
    const r = await work({
      mcpServerStatus: async () => [],
      reconnectMcpServer: async () => {},
      toggleMcpServer: async (name: string, enabled: boolean) => {
        setTimeout(() => {
          applied(name, enabled)
          log.push('written')
        }, ms)
      },
    } as never)
    log.push('stopped')
    return r
  })

for (const enabled of [true, false]) {
  test(`turning ${enabled ? 'on' : 'off'}: it answers once the file says so, with the process kept up until then`, async () => {
    write(enabled ? { disabledMcpServers: ['tiny'] } : {})
    const log: string[] = []
    writesLater(300, log)
    await toggleServer(folder, [], 'tiny', enabled)
    assert.equal(entry().disabledMcpServers.includes('tiny'), !enabled)
    assert.deepEqual(log, ['started', 'written', 'stopped'])
  })
}

test('a write that never comes: a new process tries once, and then it says so rather than succeed', async () => {
  useSaveWait(100)
  write({ disabledMcpServers: ['tiny'] })
  let processes = 0
  useProbe(async (_f, _w, work) => {
    processes++
    return work({ mcpServerStatus: async () => [], reconnectMcpServer: async () => {}, toggleMcpServer: async () => {} } as never)
  })
  await assert.rejects(toggleServer(folder, [], 'tiny', true), (e: any) => {
    assert.equal(e.code, 'not_saved')
    assert.equal(e.status, 502)
    assert.match(e.message, /tiny is still off/)
    return true
  })
  assert.equal(processes, 2)
})

test('the second process can be the one that saves it', async () => {
  useSaveWait(100)
  write({ disabledMcpServers: ['tiny'] })
  let processes = 0
  useProbe(async (_f, _w, work) => {
    const n = ++processes
    return work({ mcpServerStatus: async () => [], reconnectMcpServer: async () => {}, toggleMcpServer: async (name: string, on: boolean) => { if (n === 2) applied(name, on) } } as never)
  })
  await toggleServer(folder, [], 'tiny', true)
  assert.equal(processes, 2)
  assert.deepEqual(entry().disabledMcpServers, [])
})

test('one of Claude Code\'s built-in servers that start off keeps its own list, the other way round', async () => {
  useSaveWait(100)
  const builtIn = (name: string, on: boolean) => {
    const e = entry()
    const list: string[] = e.enabledMcpServers ?? []
    e.enabledMcpServers = on ? [...list, name] : list.filter((n) => n !== name)
    write(e)
  }
  write({ enabledMcpServers: ['chrome'] })
  useProbe(async (_f, _w, work) => work({ mcpServerStatus: async () => [], reconnectMcpServer: async () => {}, toggleMcpServer: async (n: string, on: boolean) => builtIn(n, on) } as never))
  await toggleServer(folder, [], 'chrome', false)
  assert.deepEqual(entry().enabledMcpServers, [])
  await toggleServer(folder, [], 'chrome', true)
  assert.deepEqual(entry().enabledMcpServers, ['chrome'])
})

test('in a repository it reads the entry Claude Code files it under, the root\'s', async () => {
  const repo = join(scratch, 'repo')
  mkdirSync(join(repo, '.git'), { recursive: true })
  mkdirSync(join(repo, 'sub'), { recursive: true })
  writeFileSync(file, JSON.stringify({ projects: { [repo]: {} } }))
  useProbe(async (_f, _w, work) =>
    work({
      mcpServerStatus: async () => [],
      reconnectMcpServer: async () => {},
      toggleMcpServer: async () => writeFileSync(file, JSON.stringify({ projects: { [repo]: { disabledMcpServers: ['tiny'] } } })),
    } as never),
  )
  await toggleServer(join(repo, 'sub'), [], 'tiny', false)
  assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')).projects[repo].disabledMcpServers, ['tiny'])
})
