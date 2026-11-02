// What a variable really does, with the real agents in a throwaway home. Claude Code runs against a stand-in API that asks it to
// run a command and reads the answer, so no model is reached. Skipped where they aren't installed (CI).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as httpServer } from 'node:http'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const nvm = join(homedir(), '.nvm/versions/node')
const realCodex = [process.env.HN_E2E_CODEX, ...(existsSync(nvm) ? readdirSync(nvm).map((v) => join(nvm, v, 'bin/codex')) : [])]
  .filter((p): p is string => !!p && existsSync(p))
  .find((p) => {
    try {
      const v = /(\d+)\.(\d+)/.exec(execFileSync(p, ['--version'], { encoding: 'utf8' }))
      return v ? Number(v[1]) > 0 || Number(v[2]) >= 163 : false
    } catch {
      return false
    }
  })

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-env-e2e-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
const home = join(scratch, 'home')
const claudeDir = join(scratch, 'claude')
const codexDir = join(scratch, 'codex')
const proj = join(scratch, 'proj')
for (const d of [home, claudeDir, codexDir, join(proj, '.claude')]) mkdirSync(d, { recursive: true })
const { claudeSdk } = await import('../src/sdk.ts')

test('Claude Code: local wins, the shell loses, a running session sees new and changed values at its next command, and keeps a deleted one', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 120_000 }, async () => {
  const results: string[] = []
  let cmd = 'echo none'
  let seq = 0
  const srv = httpServer((req, res) => {
    let b = ''
    req.on('data', (d) => (b += d))
    req.on('end', () => {
      if (!req.url!.includes('/v1/messages') || req.method !== 'POST') {
        res.writeHead(200, { 'content-type': 'application/json' })
        return res.end('{}')
      }
      let j: any = {}
      try {
        j = JSON.parse(b)
      } catch {}
      const hasBash = (j.tools ?? []).some((t: any) => t.name === 'Bash')
      const last = j.messages?.at(-1)
      const tr = Array.isArray(last?.content) ? last.content.find((c: any) => c.type === 'tool_result') : null
      if (tr) results.push(typeof tr.content === 'string' ? tr.content : tr.content.map((x: any) => x.text).join(''))
      const send = (ev: string, o: object) => res.write(`event: ${ev}\ndata: ${JSON.stringify({ type: ev, ...o })}\n\n`)
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      send('message_start', { message: { id: `m${++seq}`, type: 'message', role: 'assistant', content: [], model: j.model, stop_reason: null, stop_sequence: null, usage: { input_tokens: 1, output_tokens: 1 } } })
      if (hasBash && !tr) {
        send('content_block_start', { index: 0, content_block: { type: 'tool_use', id: `t${seq}`, name: 'Bash', input: {} } })
        send('content_block_delta', { index: 0, delta: { type: 'input_json_delta', partial_json: JSON.stringify({ command: cmd, description: 'x' }) } })
        send('content_block_stop', { index: 0 })
        send('message_delta', { delta: { stop_reason: 'tool_use', stop_sequence: null }, usage: { output_tokens: 1 } })
      } else {
        send('content_block_start', { index: 0, content_block: { type: 'text', text: '' } })
        send('content_block_delta', { index: 0, delta: { type: 'text_delta', text: 'ok' } })
        send('content_block_stop', { index: 0 })
        send('message_delta', { delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 1 } })
      }
      send('message_stop', {})
      res.end()
    })
  })
  await new Promise<void>((r) => srv.listen(0, '127.0.0.1', r))
  const port = (srv.address() as { port: number }).port
  const env: Record<string, string> = { PATH: process.env.PATH!, HOME: home, CLAUDE_CONFIG_DIR: claudeDir, ANTHROPIC_API_KEY: 'sk-fake', ANTHROPIC_BASE_URL: `http://127.0.0.1:${port}`, HN_FROM_SHELL: 'shell' }
  const user = join(claudeDir, 'settings.json')
  writeFileSync(user, JSON.stringify({ env: { HN_A: 'user', HN_B: 'user', HN_C: 'user', HN_FROM_SHELL: 'settings' } }))
  writeFileSync(join(proj, '.claude/settings.json'), JSON.stringify({ env: { HN_A: 'project', HN_B: 'project' } }))
  writeFileSync(join(proj, '.claude/settings.local.json'), JSON.stringify({ env: { HN_A: 'local' } }))
  writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ projects: { [proj]: { hasTrustDialogAccepted: true } } }))
  const { query } = await claudeSdk()
  const queue: any[] = []
  let wake = () => {}
  const input = (async function* () {
    for (;;) {
      while (queue.length) yield queue.shift()
      await new Promise<void>((r) => (wake = r))
    }
  })()
  const q = query({ prompt: input, options: { cwd: proj, settingSources: ['user', 'project', 'local'], persistSession: false, env, pathToClaudeCodeExecutable: realpathSync(realClaude!), model: 'claude-opus-5-5', permissionMode: 'bypassPermissions', allowDangerouslySkipPermissions: true, extraArgs: { settings: '{"disableAllHooks":true}' } } })
  const it = q[Symbol.asyncIterator]()
  const turn = async (c: string) => {
    cmd = c
    queue.push({ type: 'user', session_id: '', parent_tool_use_id: null, message: { role: 'user', content: '[Hypernova test]' } })
    wake()
    for (;;) {
      const { value, done } = await it.next()
      if (done || (value as any).type === 'result') return results.at(-1)
    }
  }
  try {
    assert.equal(await turn('echo "A=$HN_A B=$HN_B C=$HN_C S=$HN_FROM_SHELL NEW=[$HN_NEW]"'), 'A=local B=project C=user S=settings NEW=[]')
    writeFileSync(user, JSON.stringify({ env: { HN_A: 'user', HN_B: 'user', HN_C: 'changed', HN_NEW: 'added', HN_FROM_SHELL: 'settings' } }))
    await new Promise((r) => setTimeout(r, 4000))
    assert.equal(await turn('echo "C=$HN_C NEW=$HN_NEW"'), 'C=changed NEW=added')
    writeFileSync(user, JSON.stringify({ env: { HN_A: 'user', HN_B: 'user', HN_FROM_SHELL: 'settings' } }))
    await new Promise((r) => setTimeout(r, 4000))
    assert.equal(await turn('echo "C=$HN_C NEW=$HN_NEW"'), 'C=changed NEW=added', 'a deleted one stays until the session restarts')
  } finally {
    q.close?.()
    srv.closeAllConnections()
    srv.close()
  }
})

test('Codex: `set` reaches the commands it runs, a process already running doesn\'t see a change, a new one does', { skip: realCodex ? false : 'a current Codex isn’t installed here', timeout: 60_000 }, async () => {
  const env = { PATH: process.env.PATH!, HOME: home, CODEX_HOME: codexDir, HN_SHELL_VAR: 'fromshell' }
  writeFileSync(join(codexDir, 'config.toml'), '# keep me\n[shell_environment_policy]\ninherit = "all"\nset = { HN_A = "one" }\n')
  const run = () => execFileSync(realCodex!, ['sandbox', '--', 'sh', '-c', 'echo A=$HN_A NEW=[$HN_NEW] SHELL=$HN_SHELL_VAR'], { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  assert.equal(run(), 'A=one NEW=[] SHELL=fromshell')
  const { spawn } = await import('node:child_process')
  const c = spawn(realCodex!, ['app-server'], { env, stdio: ['pipe', 'pipe', 'ignore'] })
  try {
    let id = 0
    let buf = ''
    const wait = new Map<number, (m: any) => void>()
    c.stdout.on('data', (d) => {
      buf += d
      for (let i = buf.indexOf('\n'); i >= 0; i = buf.indexOf('\n')) {
        const line = buf.slice(0, i)
        buf = buf.slice(i + 1)
        try {
          const m = JSON.parse(line)
          if (m.id && wait.has(m.id)) {
            wait.get(m.id)!(m)
            wait.delete(m.id)
          }
        } catch {}
      }
    })
    const req = (method: string, params: unknown) =>
      new Promise<any>((r) => {
        const i = ++id
        wait.set(i, r)
        c.stdin.write(`${JSON.stringify({ id: i, method, params })}\n`)
      })
    await req('initialize', { clientInfo: { name: 'hn', version: '1' }, capabilities: { experimentalApi: true } })
    c.stdin.write(`${JSON.stringify({ method: 'initialized' })}\n`)
    const exec = async () => (await req('command/exec', { command: ['sh', '-c', 'echo NEW=[$HN_NEW]'], cwd: home })).result.stdout.trim()
    assert.equal(await exec(), 'NEW=[]')
    assert.equal((await req('config/value/write', { keyPath: 'shell_environment_policy.set.HN_NEW', value: 'added', mergeStrategy: 'upsert' })).result.status, 'ok')
    assert.equal(await exec(), 'NEW=[]', 'a process already running keeps what it read')
    assert.ok(readFileSync(join(codexDir, 'config.toml'), 'utf8').includes('# keep me'))
    assert.equal(run(), 'A=one NEW=[added] SHELL=fromshell')
  } finally {
    c.kill()
    rmSync(scratch, { recursive: true, force: true })
  }
})
