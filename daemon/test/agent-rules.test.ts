// Claude Code's permission rules, through the real routes (agent-rules.ts, rule-text.ts). Every file here is a scratch one.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-agent-rules-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
const CLAUDE = process.env.CLAUDE_CONFIG_DIR
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(CLAUDE, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const API = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
process.env.HYPERNOVA_LAN_PORT = '0'

const { broadWords, covers, judgeRule, maskedRule, restoredRule } = await import('../src/rule-text.ts')
const { changeRule } = await import('../src/agent-rules.ts')
const { jsonListEdit } = await import('../src/agent-settings-edit.ts')
const { startServers } = await import('../src/http.ts')
const { addDevice, settings } = await import('../src/store.ts')
settings.lanAccess = false
const TOKEN = 'agent-rules-test-device-token-0123456789ab'
addDevice({ id: 'phone', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${API}/v1/health`)).ok) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})
const USER = join(CLAUDE, 'settings.json')
beforeEach(() => {
  rmSync(USER, { force: true })
  rmSync(join(process.env.HYPERNOVA_SUPPORT!, 'settings-backups'), { recursive: true, force: true })
  rmSync(join(process.env.CODEX_HOME!, 'rules'), { recursive: true, force: true })
})

const PLANT = 'PLANTEDruleSecret0123456789abcdefABCDEF'
const sha = (s: string) => createHash('sha256').update(s).digest('hex')
const call = async (method: string, path: string, body?: unknown, token: string | null = TOKEN) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const text = await r.text()
  assert.ok(!text.includes(PLANT), `a secret reached a client from ${path}: ${text}`)
  return { status: r.status, body: JSON.parse(text) as any, text }
}
const list = async () => (await call('GET', '/v1/agents/rules?agent=claude')).body
const change = (rule: object, expected: unknown, more: object = {}) => call('PUT', '/v1/agents/settings', { agent: 'claude', rule, expected, ...more })
const onDisk = () => JSON.parse(readFileSync(USER, 'utf8'))
const texts = (l: any) => l.rules.map((r: any) => r.text)
const put = (o: unknown) => {
  const t = typeof o === 'string' ? o : JSON.stringify(o, null, 2) + '\n'
  writeFileSync(USER, t)
  return sha(t)
}

test('the list: each kept in its list, in the file’s order, with a hash and where it is', async () => {
  const h = put({ theme: 'dark', permissions: { allow: ['Read', 'Bash(npm run test:*)', 'mcp__s__t'], ask: ['WebFetch(domain:example.com)'], deny: ['Read(~/.ssh/**)'], defaultMode: 'plan' } })
  const r = await list()
  assert.equal(r.supported, true)
  assert.equal(r.state, 'ok')
  assert.equal(r.hash, h)
  assert.equal(r.where, '~/.claude/settings.json')
  assert.deepEqual(texts(r.lists.allow), ['Read', 'Bash(npm run test:*)', 'mcp__s__t'])
  assert.deepEqual(texts(r.lists.ask), ['WebFetch(domain:example.com)'])
  assert.deepEqual(texts(r.lists.deny), ['Read(~/.ssh/**)'])
  assert.deepEqual(r.lists.allow.rules.map((x: any) => x.index), [0, 1, 2])
})

test('no file: empty lists, and adding makes it, 0600, with only the rule in it', async () => {
  const r = await list()
  assert.equal(r.state, 'missing')
  assert.equal(r.hash, null)
  assert.deepEqual(r.lists.allow, { rules: [], others: 0 })
  const c = await change({ op: 'add', list: 'deny', text: 'Read(.env)' }, null)
  assert.equal(c.status, 200)
  assert.deepEqual(onDisk(), { permissions: { deny: ['Read(.env)'] } })
  assert.equal(statSync(USER).mode & 0o777, 0o600)
})

test('routes need a paired device', async () => {
  assert.equal((await call('GET', '/v1/agents/rules?agent=claude', undefined, null)).status, 401)
  assert.equal((await call('PUT', '/v1/agents/settings', { agent: 'claude', rule: { op: 'add', list: 'deny', text: 'Bash' }, expected: null }, null)).status, 401)
})

test('add, edit, delete and move change only a rule’s bytes', async () => {
  const start = `{
    "theme":   "dark",
    "weird": [1.0, 1e3, "\\u00e9"],
    "permissions": {
        "allow": [
            "Read",
            "Bash(npm test)"
        ],
        "ask": [ "Edit" ],
        "defaultMode": "plan"
    },
    "hooks": {"Stop": []}
}
`
  let h = put(start)
  let c = await change({ op: 'add', list: 'ask', text: '  WebSearch  ' }, h)
  assert.equal(c.status, 200)
  assert.equal(c.body.changed, true)
  let now = readFileSync(USER, 'utf8')
  assert.equal(now, start.replace('[ "Edit" ]', '[ "Edit", "WebSearch" ]'), 'trimmed, and only those bytes')
  h = c.body.hash
  assert.equal(h, sha(now))

  // Ask's rule edited: loosens, so confirmed.
  c = await change({ op: 'edit', list: 'ask', index: 0, text: 'Edit(src/**)' }, h, { confirmed: true })
  assert.equal(c.status, 200)
  now = readFileSync(USER, 'utf8')
  assert.ok(now.includes('[ "Edit(src/**)", "WebSearch" ]'))
  h = c.body.hash

  c = await change({ op: 'move', list: 'allow', index: 0, to: 'ask' }, h)
  assert.equal(c.status, 200)
  assert.deepEqual(onDisk().permissions.allow, ['Bash(npm test)'])
  assert.deepEqual(onDisk().permissions.ask, ['Edit(src/**)', 'WebSearch', 'Read'])
  h = c.body.hash

  c = await change({ op: 'delete', list: 'allow', index: 0 }, h)
  assert.equal(c.status, 200)
  assert.deepEqual(onDisk().permissions.allow, [])
  const after = onDisk()
  assert.equal(after.theme, 'dark')
  assert.equal(after.permissions.defaultMode, 'plan')
  now = readFileSync(USER, 'utf8')
  for (const kept of ['"theme":   "dark"', '[1.0, 1e3, "\\u00e9"]', '"hooks": {"Stop": []}']) assert.ok(now.includes(kept), kept)
})

test('jsonListEdit: first, middle, last and only, each leaving what’s beside it', () => {
  const t = '{"permissions":{"allow":["a", "b",\n "c"]}}'
  const p = ['permissions', 'allow']
  assert.equal(jsonListEdit(t, p, { op: 'remove', index: 0 }), '{"permissions":{"allow":["b",\n "c"]}}')
  assert.equal(jsonListEdit(t, p, { op: 'remove', index: 1 }), '{"permissions":{"allow":["a",\n "c"]}}')
  assert.equal(jsonListEdit(t, p, { op: 'remove', index: 2 }), '{"permissions":{"allow":["a", "b"]}}')
  assert.equal(jsonListEdit('{"permissions":{"allow":["a"]}}', p, { op: 'remove', index: 0 }), '{"permissions":{"allow":[]}}')
  assert.equal(jsonListEdit('{"permissions":{"allow":[]}}', p, { op: 'add', text: 'x' }), '{"permissions":{"allow":["x"]}}')
  assert.equal(jsonListEdit('{}', p, { op: 'add', text: 'x' }).replace(/\s/g, ''), '{"permissions":{"allow":["x"]}}')
  assert.equal(jsonListEdit('{"permissions":{"allow":[1,"a"]}}', p, { op: 'replace', index: 1, text: 'é"\n' }), '{"permissions":{"allow":[1,"é\\"\\n"]}}')
  assert.throws(() => jsonListEdit('{"permissions":{"allow":"nope"}}', p, { op: 'add', text: 'x' }), { code: 'unsupported_shape' })
  assert.throws(() => jsonListEdit('{"permissions":[1]}', p, { op: 'add', text: 'x' }), { code: 'unsupported_shape' })
})

test('loosening is refused without the confirm, on the server, and nothing is written', async () => {
  const h = put({ permissions: { allow: ['Read'], ask: ['Edit'], deny: ['Bash(rm *)'] } })
  const bytes = readFileSync(USER, 'utf8')
  const cases: [object, RegExp][] = [
    [{ op: 'add', list: 'allow', text: 'Bash(npm test)' }, /without asking/],
    [{ op: 'edit', list: 'allow', index: 0, text: 'Read(src/**)' }, /becomes/],
    [{ op: 'edit', list: 'ask', index: 0, text: 'Edit(src/**)' }, /no longer on Ask/],
    [{ op: 'edit', list: 'deny', index: 0, text: 'Bash(rm -rf *)' }, /no longer on Deny/],
    [{ op: 'delete', list: 'ask', index: 0 }, /comes off Ask/],
    [{ op: 'delete', list: 'deny', index: 0 }, /comes off Deny/],
    [{ op: 'move', list: 'ask', index: 0, to: 'allow' }, /from Ask to Allow/],
    [{ op: 'move', list: 'deny', index: 0, to: 'allow' }, /from Deny to Allow/],
    [{ op: 'move', list: 'deny', index: 0, to: 'ask' }, /from Deny to Ask/],
  ]
  for (const [rule, words] of cases) {
    const c = await change(rule, h)
    assert.equal(c.status, 403, JSON.stringify(rule))
    assert.equal(c.body.error?.code ?? c.body.code, 'confirm_needed')
    assert.match(c.body.error?.message ?? c.body.message, words)
    assert.equal(readFileSync(USER, 'utf8'), bytes, 'nothing written')
  }
  const notAYes = await change({ op: 'add', list: 'allow', text: 'Bash(npm test)' }, h, { confirmed: 'true' })
  assert.equal(notAYes.status, 403, 'only a plain true counts')
  const yes = await change({ op: 'add', list: 'allow', text: 'Bash(npm test)' }, h, { confirmed: true })
  assert.equal(yes.status, 200)
  assert.equal(yes.body.loosened, true)
  assert.deepEqual(onDisk().permissions.allow, ['Read', 'Bash(npm test)'])
})

test('tightening asks nothing', async () => {
  let h = put({ permissions: { allow: ['Read', 'Edit'], ask: ['Grep'] } })
  for (const rule of [{ op: 'add', list: 'deny', text: 'Bash' }, { op: 'add', list: 'ask', text: 'Bash(rm *)' }, { op: 'delete', list: 'allow', index: 1 }, { op: 'move', list: 'allow', index: 0, to: 'ask' }, { op: 'move', list: 'ask', index: 0, to: 'deny' }]) {
    const c = await change(rule, h)
    assert.equal(c.status, 200, JSON.stringify(rule) + c.text)
    assert.equal(c.body.loosened, false)
    h = c.body.hash
  }
  assert.deepEqual(onDisk().permissions, { allow: [], ask: ['Bash(rm *)', 'Read'], deny: ['Bash', 'Grep'] })
})

test('a broad rule says plainly what it does, and asks', async () => {
  const h = put({})
  for (const [text, words] of [['Bash', /any command.*Bypass/], ['Bash(*)', /any command.*Bypass/], ['Bash(python3:*)', /python3 can run anything.*Bypass/], ['Edit', /change any file/], ['Read(//**)', /read any file/], ['WebFetch', /any site/], ['mcp__srv__*', /every tool of the srv server/]] as const) {
    const c = await change({ op: 'add', list: 'allow', text }, h)
    assert.equal(c.status, 403, text)
    assert.match(c.body.error?.message ?? c.body.message, words)
  }
  assert.equal(broadWords('Bash(npm test)'), null)
  assert.equal(broadWords('Read(src/**)'), null)
  assert.equal(broadWords('mcp__srv__one'), null)
  const shown = put({ permissions: { allow: ['Bash', 'Bash(npm test)'], deny: ['Bash'] } })
  assert.ok(shown)
  const r = await list()
  assert.match(r.lists.allow.rules[1].text, /npm test/)
  assert.equal(r.lists.allow.rules[0].overriddenBy, 'deny', 'Deny has the same rule')
})

test('a row says when another list wins over it', async () => {
  put({ permissions: { allow: ['Bash(npm test)', 'Read', 'Bash(ls)', 'mcp__a__b'], ask: ['Bash(ls)', 'Edit'], deny: ['Bash', 'mcp__a__*', 'Read(.env)'] } })
  const r = await list()
  const allow = r.lists.allow.rules
  assert.equal(allow[0].overriddenBy, 'deny')
  assert.equal(allow[0].overrides, 'Bash')
  assert.equal(allow[1].overriddenBy, undefined, 'a narrower deny doesn’t win over all of Read')
  assert.equal(allow[2].overriddenBy, 'deny', 'deny is looked at first')
  assert.equal(allow[3].overriddenBy, 'deny')
  assert.equal(r.lists.ask.rules[0].overriddenBy, 'deny')
  assert.equal(r.lists.ask.rules[1].overriddenBy, undefined)
  assert.equal(r.lists.deny.rules[0].overriddenBy, undefined)
})

test('an invalid rule: refused with why, or kept as typed when asked, with a note on its row', async () => {
  const h = put({})
  const bad = await change({ op: 'add', list: 'deny', text: 'Bash(' }, h)
  assert.equal(bad.status, 400)
  assert.equal(bad.body.error?.code ?? bad.body.code, 'bad_rule')
  assert.ok(!existsSync(USER) || onDisk().permissions === undefined)
  const kept = await change({ op: 'add', list: 'deny', text: 'Bash(' }, h, { asTyped: true })
  assert.equal(kept.status, 200)
  const r = await list()
  assert.equal(r.lists.deny.rules[0].text, 'Bash(')
  assert.match(r.lists.deny.rules[0].problem, /closing/)
})

test('judgeRule follows Claude Code’s own check', () => {
  const problem = (r: string, l: 'allow' | 'ask' | 'deny' = 'allow') => judgeRule(r, l).problem ?? null
  for (const r of ['Bash', 'Bash(npm run test:*)', 'Bash(git *)', 'Read(~/x/**)', 'Edit(src/**)', 'WebFetch(domain:github.com)', 'mcp__server__tool', 'mcp__server', 'mcp__server__*', 'WebSearch', 'Agent(Explore)', 'Bash(*)']) assert.equal(problem(r), null, r)
  for (const r of ['', '  ', 'Bash(', 'Bash)', 'Bash(x)y', 'Bash()', 'bash', 'bash(ls)', 'WebFetch(https://x.test)', 'WebFetch(x.test)', 'mcp__a__b(c)', 'Read(a:*)', 'Bash(:*)', '*', 'Ba*', 'WebSearch(a*)']) assert.ok(problem(r), r)
  // A wildcard name is refused in Allow alone.
  assert.equal(problem('Ba*', 'deny'), null)
  assert.match(judgeRule('Write(~/**)', 'allow').warning!, /never matches/)
  assert.match(judgeRule('Glob(a)', 'deny').warning!, /Read\(path\)/)
  assert.match(judgeRule('Foo(bar)', 'allow').warning!, /isn’t a tool this server knows/)
  assert.match(judgeRule('Bash(a:*b)', 'allow').warning!, /isn’t at the end/)
})

test('covers: the same rule, a whole tool, a whole server; never a guess', () => {
  assert.ok(covers('Bash', 'Bash(x)'))
  assert.ok(covers('Bash(*)', 'Bash(x)'))
  assert.ok(covers('Bash(x)', 'Bash(x)'))
  assert.ok(covers('Task', 'Agent(y)'), 'an old name')
  assert.ok(covers('mcp__a', 'mcp__a__b'))
  assert.ok(!covers('Bash(x)', 'Bash(y)'))
  assert.ok(!covers('Bash(x)', 'Bash'))
  assert.ok(!covers('mcp__a__b', 'mcp__a__c'))
})

test('a secret in a rule stays on the server, and an edit keeps it', async () => {
  const rule = `Bash(curl -H "Authorization: Bearer ${PLANT}" https://x.test/*)`
  let h = put({ permissions: { allow: [rule, 'Read'], ask: [`Bash(tool --token ${PLANT})`] } })
  const r = await list()
  const row = r.lists.allow.rules[0]
  assert.ok(row.masked)
  assert.equal(row.text, 'Bash(curl -H "Authorization: Bearer <hidden on the server 1>" https://x.test/*)')
  assert.equal(r.lists.ask.rules[0].text, 'Bash(tool --token <hidden on the server 1>)')

  // An edit round the placeholder: the secret goes back, not the words for it.
  const edited = row.text.replace('x.test', 'y.test')
  let c = await change({ op: 'edit', list: 'allow', index: 0, text: edited }, h)
  assert.equal(c.status, 403, 'an edit in Allow still asks')
  assert.ok(!c.text.includes(PLANT))
  assert.ok((c.body.error?.message ?? c.body.message).includes('<hidden on the server 1>'))
  c = await change({ op: 'edit', list: 'allow', index: 0, text: edited }, h, { confirmed: true })
  assert.equal(c.status, 200)
  assert.equal(onDisk().permissions.allow[0], rule.replace('x.test', 'y.test'))
  assert.ok(!readFileSync(USER, 'utf8').includes('hidden on the server'))
  h = c.body.hash

  // Sending the rule back as shown changes nothing.
  const same = await change({ op: 'edit', list: 'ask', index: 0, text: 'Bash(tool --token <hidden on the server 1>)' }, h, { confirmed: true })
  assert.equal(same.body.changed, false)
  assert.equal(onDisk().permissions.ask[0], `Bash(tool --token ${PLANT})`)

  // Moving and deleting send no text at all.
  c = await change({ op: 'move', list: 'allow', index: 0, to: 'deny' }, h)
  assert.equal(c.status, 200)
  assert.ok(onDisk().permissions.deny[0].includes(PLANT))

  // Words that aren't this rule's secret never land in the file.
  const h2 = c.body.hash
  for (const [rule2, code] of [
    [{ op: 'edit', list: 'ask', index: 0, text: 'Bash(tool --token <hidden on the server 2>)' }, 'placeholder_unknown'],
    [{ op: 'edit', list: 'ask', index: 0, text: 'Bash(tool --token <hidden on the server>)' }, 'placeholder_unknown'],
    [{ op: 'edit', list: 'ask', index: 0, text: 'Bash(tool --token •••)' }, 'masked_value'],
    [{ op: 'add', list: 'deny', text: 'Bash(x <hidden on the server 1>)' }, 'placeholder_unknown'],
  ] as const) {
    const refused = await change(rule2, h2, { confirmed: true })
    assert.equal(refused.status, 400, JSON.stringify(rule2))
    assert.equal(refused.body.error?.code ?? refused.body.code, code)
    assert.ok(onDisk().permissions.ask[0].includes(PLANT))
  }
})

test('maskedRule hides what a tool row hides, and any value after a key’s name', () => {
  const shown = (r: string) => maskedRule(r).shown
  for (const [r, want] of [
    ['Bash(API_KEY=abc npm start)', 'Bash(API_KEY=<hidden on the server 1> npm start)'],
    ['Bash(curl -u admin:pw x.test)', 'Bash(curl -u <hidden on the server 1> x.test)'],
    ['Bash(curl https://u:pw@x.test/a?token=z)', 'Bash(curl https://<hidden on the server 1>@x.test/a?token=<hidden on the server 2>)'],
    ['Bash(TOKEN=$X y)', 'Bash(TOKEN=$X y)'],
    ['Bash(npm run test:*)', 'Bash(npm run test:*)'],
    ['Read(~/x/**)', 'Read(~/x/**)'],
    ['WebFetch(domain:token.example.com)', 'WebFetch(domain:token.example.com)'],
  ]) {
    assert.equal(shown(r!), want)
    assert.equal(restoredRule(want!, r!), r)
  }
})

test('a file changed underneath: file_changed, also just before the rename', async () => {
  const h = put({ permissions: { deny: ['Read'] } })
  const stale = await change({ op: 'add', list: 'deny', text: 'Edit' }, 'an-old-hash')
  assert.equal(stale.status, 409)
  assert.equal(stale.body.error?.code ?? stale.body.code, 'file_changed')
  assert.throws(
    () => changeRule({ agent: 'claude', rule: { op: 'add', list: 'deny', text: 'Edit' }, expected: h }, {}, { beforeRename: () => writeFileSync(USER, '{"theirs":true}') }),
    { code: 'file_changed' },
  )
  assert.deepEqual(onDisk(), { theirs: true }, 'their write stands')
  assert.equal(readdirSync(CLAUDE).filter((f) => f.includes('.hn-')).length, 0, 'no temporary file left')
})

test('Undo: back byte for byte, and a tightening’s Undo asks since it loosens', async () => {
  const start = '{\n  "permissions": { "allow": ["Read", "Edit"], "deny": [] }\n}\n'
  const h = put(start)
  // Moving a rule to Deny tightens, and putting it back loosens.
  const c = await change({ op: 'move', list: 'allow', index: 1, to: 'deny' }, h)
  assert.equal(c.status, 200)
  const r = await list()
  assert.equal(r.undo.needsConfirm, true)
  assert.match(r.undo.change, /Edit goes on Allow/)
  const asked = await call('POST', '/v1/agents/settings/undo', { agent: 'claude', expected: c.body.hash })
  assert.equal(asked.status, 403)
  assert.equal(asked.body.error?.code ?? asked.body.code, 'confirm_needed')
  assert.notEqual(readFileSync(USER, 'utf8'), start)
  const done = await call('POST', '/v1/agents/settings/undo', { agent: 'claude', expected: c.body.hash, confirmed: true })
  assert.equal(done.status, 200)
  assert.equal(readFileSync(USER, 'utf8'), start)
  assert.equal(done.body.loosened, true)
  assert.equal((await list()).undo, null, 'one step')

  // A loosening's Undo only tightens.
  const c2 = await change({ op: 'add', list: 'allow', text: 'Grep' }, sha(start), { confirmed: true })
  const offer = (await list()).undo
  assert.equal(offer.needsConfirm, false)
  assert.equal((await call('POST', '/v1/agents/settings/undo', { agent: 'claude', expected: c2.body.hash })).status, 200)
  assert.equal(readFileSync(USER, 'utf8'), start)

  // Undo of a change that made the file takes it away.
  rmSync(USER)
  const c3 = await change({ op: 'add', list: 'deny', text: 'Bash' }, null)
  assert.equal((await call('POST', '/v1/agents/settings/undo', { agent: 'claude', expected: c3.body.hash })).status, 403, 'a rule coming off Deny, even by Undo')
  assert.equal((await call('POST', '/v1/agents/settings/undo', { agent: 'claude', expected: c3.body.hash, confirmed: true })).status, 200)
  assert.ok(!existsSync(USER))
})

test('Undo’s words keep a secret back, and the settings screen’s reply doesn’t offer a rule’s Undo', async () => {
  const rule = `Bash(x --token ${PLANT})`
  const h = put({ permissions: { allow: [rule] } })
  const c = await change({ op: 'delete', list: 'allow', index: 0 }, h)
  const r = await list()
  assert.match(r.undo.change, /<hidden on the server 1>\) goes on Allow/)
  assert.equal((await call('GET', '/v1/agents/settings')).body.claude.undo, null)
  const asked = await call('POST', '/v1/agents/settings/undo', { agent: 'claude', expected: c.body.hash })
  assert.equal(asked.status, 403)
  assert.equal((await call('POST', '/v1/agents/settings/undo', { agent: 'claude', expected: c.body.hash, confirmed: true })).status, 200)
  assert.equal(onDisk().permissions.allow[0], rule)
})

test('a change backs up what was there, 0600, beside the other settings backups', async () => {
  const h = put({ permissions: { deny: [`Bash(x --token ${PLANT})`] } })
  const before = readFileSync(USER)
  await change({ op: 'add', list: 'deny', text: 'Read' }, h)
  const dir = join(process.env.HYPERNOVA_SUPPORT!, 'settings-backups')
  const backups = readdirSync(dir).filter((n) => /^claude-\d+$/.test(n))
  assert.equal(backups.length, 1)
  assert.deepEqual(readFileSync(join(dir, backups[0]!)), before)
  assert.equal(statSync(join(dir, backups[0]!)).mode & 0o777, 0o600)
})

test('a link is shown and never written through, and says why', async () => {
  const real = join(scratch, 'dotfiles-settings.json')
  const text = JSON.stringify({ permissions: { allow: ['Read'] } })
  writeFileSync(real, text)
  symlinkSync(real, USER)
  const r = await list()
  assert.equal(r.whyNot, 'link')
  assert.deepEqual(texts(r.lists.allow), ['Read'])
  assert.equal(r.hash, null)
  const c = await change({ op: 'add', list: 'deny', text: 'Bash' }, null)
  assert.equal(c.status, 409)
  assert.equal(c.body.error?.code ?? c.body.code, 'file_is_link')
  assert.equal(readFileSync(real, 'utf8'), text)
  rmSync(USER)
})

test('a read-only file, a broken file, a list that isn’t one, and entries that aren’t text', async () => {
  let h = put({ permissions: { allow: ['Read'] } })
  chmodSync(USER, 0o400)
  const ro = await list()
  assert.equal(ro.whyNot, 'read_only')
  const refused = await change({ op: 'add', list: 'deny', text: 'Bash' }, h)
  assert.equal(refused.body.error?.code ?? refused.body.code, 'file_read_only')
  chmodSync(USER, 0o600)

  put(`{ "env": { "X": "${PLANT}" `)
  const broken = await list()
  assert.equal(broken.state, 'malformed')
  assert.equal(broken.lists, null)

  h = put({ permissions: { ask: 'nope', allow: [123, 'Read', { a: 1 }] } })
  const odd = await list()
  assert.equal(odd.lists.ask.rules, null)
  assert.equal(odd.lists.allow.others, 2)
  assert.deepEqual(odd.lists.allow.rules.map((r: any) => [r.index, r.text]), [[1, 'Read']], 'an index counts everything in the list')
  const ask = await change({ op: 'add', list: 'ask', text: 'Bash' }, h)
  assert.equal(ask.body.error?.code ?? ask.body.code, 'unsupported_shape')
  const move = await change({ op: 'move', list: 'allow', index: 1, to: 'ask' }, h)
  assert.equal(move.body.error?.code ?? move.body.code, 'unsupported_shape')
  const noRule = await change({ op: 'delete', list: 'allow', index: 0 }, h)
  assert.equal(noRule.body.error?.code ?? noRule.body.code, 'file_changed', 'an entry that isn’t a rule isn’t one to change')
  const del = await change({ op: 'delete', list: 'allow', index: 1 }, h)
  assert.equal(del.status, 200)
  assert.deepEqual(onDisk().permissions.allow, [123, { a: 1 }], 'what isn’t a rule stays')
})

test('a request off the shape, a repeat, and a move onto a rule that’s there', async () => {
  const h = put({ permissions: { allow: ['Read'], deny: ['Read'], ask: ['Edit'] } })
  for (const rule of [{}, { op: 'add', list: 'nope', text: 'x' }, { op: 'nope', list: 'deny' }, { op: 'add', list: 'deny' }, { op: 'delete', list: 'deny' }, { op: 'delete', list: 'deny', index: -1 }, { op: 'delete', list: 'deny', index: 0.5 }, { op: 'move', list: 'deny', index: 0, to: 'deny' }, 'x']) {
    const c = await change(rule as object, h)
    assert.equal(c.status, 400, JSON.stringify(rule))
  }
  const same = await change({ op: 'add', list: 'deny', text: 'Read' }, h)
  assert.equal(same.body.changed, false)
  const moved = await change({ op: 'move', list: 'allow', index: 0, to: 'deny' }, h)
  assert.equal(moved.status, 200)
  assert.deepEqual(onDisk().permissions.deny, ['Read'], 'not twice')
  assert.deepEqual(onDisk().permissions.allow, [])
  const h2 = moved.body.hash
  const dup = await change({ op: 'edit', list: 'deny', index: 0, text: 'Read' }, h2, { confirmed: true })
  assert.equal(dup.body.changed, false)
  const long = await change({ op: 'add', list: 'deny', text: `Bash(${'x'.repeat(9000)})` }, h2)
  assert.equal(long.body.error?.code ?? long.body.code, 'bad_value')
  const ctl = await change({ op: 'add', list: 'deny', text: 'Bash(a\u0007)' }, h2)
  assert.equal(ctl.body.error?.code ?? ctl.body.code, 'bad_value')
  assert.equal((await call('PUT', '/v1/agents/settings', { agent: 'codex', rule: { op: 'add', list: 'deny', text: 'Bash' }, expected: null })).body.error?.code, 'not_for_codex')
  assert.equal((await call('PUT', '/v1/agents/settings', { agent: 'claude', rule: { op: 'add', list: 'deny', text: 'Bash' }, expected: 5 })).status, 400)
})

test('other keys through the same PUT are as they were', async () => {
  const h = put({ permissions: { allow: ['Read'] } })
  const c = await call('PUT', '/v1/agents/settings', { agent: 'claude', key: 'permissions.defaultMode', value: 'plan', expected: h })
  assert.equal(c.status, 200)
  assert.equal(c.body.key, 'permissions.defaultMode')
  assert.equal((await call('PUT', '/v1/agents/settings', { agent: 'claude', key: 'permissions.allow', value: 'Bash', expected: c.body.hash })).status, 403)
})

test('Codex: no lists, and how many files of its own rules, never what’s in them', async () => {
  const none = (await call('GET', '/v1/agents/rules?agent=codex')).body
  assert.deepEqual(none, { agent: 'codex', supported: false, rulesFiles: 0 })
  const dir = join(process.env.CODEX_HOME!, 'rules')
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'default.rules'), `prefix_rule(pattern=["x","${PLANT}"], decision="allow")\n`)
  writeFileSync(join(dir, 'notes.txt'), 'not one')
  const r = await call('GET', '/v1/agents/rules?agent=codex')
  assert.equal(r.body.rulesFiles, 1)
  assert.equal(r.status, 200)
})

test('nothing secret in the daemon’s log, whatever was changed or refused', async () => {
  const h = put({ permissions: { deny: [`Bash(x --token ${PLANT})`] } })
  const c = await change({ op: 'add', list: 'deny', text: `Bash(y --password ${PLANT})` }, h)
  assert.equal(c.status, 200)
  await change({ op: 'add', list: 'allow', text: `Bash(z --token ${PLANT})` }, c.body.hash)
  await change({ op: 'add', list: 'deny', text: `Bash(${PLANT}` }, c.body.hash)
  await change({ op: 'delete', list: 'deny', index: 0 }, c.body.hash, { confirmed: true })
  const logs = readdirSync(process.env.HYPERNOVA_LOGS!).map((f) => readFileSync(join(process.env.HYPERNOVA_LOGS!, f), 'utf8')).join('\n')
  assert.match(logs, /agent settings: claude rules add deny/)
  assert.match(logs, /agent settings: claude rules delete deny \(loosens, confirmed\)/)
  assert.ok(!logs.includes(PLANT))
})
