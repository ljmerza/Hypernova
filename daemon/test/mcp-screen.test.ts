// /mcp's screens as they are, each a real one from Claude Code 2.1.317 (fixtures/mcp-screens).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { screenFile as screen } from './played-mcp-term.ts'
const { mcpHits, noMcpServers, readMcpList, readMcpPage, readMcpTools, showsMcpDialog, wholeName } = await import('../src/mcp-screen.ts')

const SECRET = 'PLANTEDsecret0123456789abcdef'

test('the list: each server with its status, where it comes from and its tools', () => {
  const l = readMcpList(screen('list-needs-auth'))!
  assert.equal(l.total, 6)
  assert.deepEqual(
    l.rows.map((r) => [r.name, r.status, r.source, r.toolCount]),
    [
      ['projsrv', 'connected', 'project', 1],
      ['many', 'connected', 'local', 30],
      ['slowstart', 'pending', 'local', null],
      ['two words', 'connected', 'local', 1],
      ['webauth', 'needs-auth', 'local', null],
      ['usertiny', 'connected', 'user', 1],
    ],
  )
  assert.equal(l.rows[0]!.cursor, true)
  assert.equal(l.rows[1]!.cursor, false)
})

test('the list: failed, off, and off with a notice laid over the rule while Claude works', () => {
  const failed = readMcpList(screen('list-mixed'))!
  assert.equal(failed.rows.find((r) => r.name === 'broken')!.status, 'failed')
  assert.equal(failed.rows.find((r) => r.name === 'broken')!.toolCount, null)
  const off = readMcpList(screen('busy-list-disabled'))!
  assert.equal(off.rows.find((r) => r.name === 'tiny')!.status, 'disabled')
  assert.equal(off.total, 3)
})

test('a narrow screen cuts names and pages the list', () => {
  const l = readMcpList(screen('list-narrow-cut'))!
  assert.equal(l.total, 41)
  assert.equal(l.moreBelow, 17)
  assert.equal(l.rows[0]!.cut, true)
  assert.equal(l.rows[0]!.toolCount, 1)
  assert.equal(wholeName(l.rows[0]!.name, ['srv-00-with-a-rather-long-name-indeed', 'srv-01-with-a-rather-long-name-indeed']), 'srv-00-with-a-rather-long-name-indeed')
  assert.equal(wholeName(l.rows[0]!.name, ['other']), null)
  assert.equal(wholeName('srv-…', ['srv-00', 'srv-01']), null, 'two it could be is none')
})

test('a server’s page: only its status and its options, never its command, URL or file', () => {
  const c = readMcpPage(screen('page-connected'))!
  assert.equal(c.status, 'connected')
  assert.deepEqual(c.options, ['View tools', 'Reconnect', 'Disable'])
  assert.equal(c.cursor, 0)
  assert.deepEqual(readMcpPage(screen('page-failed'))!.options, ['Reconnect', 'Disable'])
  assert.equal(readMcpPage(screen('page-failed'))!.status, 'failed')
  const auth = readMcpPage(screen('page-needs-auth'))!
  assert.equal(auth.status, 'needs-auth')
  assert.deepEqual(auth.options, ['Authenticate', 'Disable'])
  assert.deepEqual(readMcpPage(screen('page-authed'))!.options, ['View tools', 'Re-authenticate', 'Clear authentication', 'Reconnect', 'Disable'])
  // A secret on the command line, as the page would print it, goes nowhere.
  const planted = screen('page-connected').replace(/Command: .*/, `Command:   mytool --token ${SECRET} https://user:${SECRET}@example.com`)
  assert.ok(planted.includes(SECRET))
  assert.ok(!JSON.stringify(readMcpPage(planted)).includes(SECRET))
  assert.ok(!JSON.stringify(readMcpList(planted)).includes(SECRET))
  assert.equal(readMcpTools(planted), null)
})

test('the tools: names and hints, paged', () => {
  const t = readMcpTools(screen('tools-paged'))!
  assert.equal(t.total, 30)
  assert.equal(t.moreBelow, 5)
  assert.deepEqual(t.tools[0], { name: 'tool_00', cut: false, cursor: true, readOnly: true })
  assert.deepEqual(t.tools[1], { name: 'tool_01', cut: false, cursor: false, destructive: true })
  assert.deepEqual(t.tools[2], { name: 'tool_02', cut: false, cursor: false })
  const mid = readMcpTools(screen('tools-paged-mid'))!
  assert.equal(mid.moreAbove, 3)
  assert.equal(mid.moreBelow, 3)
  assert.equal(mid.tools[0]!.name, 'tool_03')
})

test('which screen it is', () => {
  for (const s of ['list-mixed', 'list-needs-auth', 'page-connected', 'page-failed', 'tools-paged', 'busy-list-disabled']) assert.equal(showsMcpDialog(screen(s)), true, s)
  for (const s of ['dismissed-idle', 'result-reconnected', 'result-no-servers', 'busy-result', 'busy-dismissed']) assert.equal(showsMcpDialog(screen(s)), false, s)
  assert.equal(readMcpList(screen('dismissed-idle')), null)
  assert.equal(noMcpServers(screen('result-no-servers')), true)
  assert.equal(noMcpServers(screen('dismissed-idle')), false)
})

test('what /mcp answers, under the command or beside a turn', () => {
  const one = (file: string, action: 'reconnect' | 'enable' | 'disable', name: string) => mcpHits(screen(file), action, name).at(-1)
  assert.equal(one('result-reconnected', 'reconnect', 'many')!.said, 'ok')
  assert.equal(one('busy-result', 'reconnect', 'many')!.said, 'ok')
  assert.equal(one('result-reconnect-failed', 'reconnect', 'broken')!.said, 'failed')
  assert.equal(one('result-disabled', 'disable', 'many')!.said, 'ok')
  assert.equal(one('result-enabled', 'enable', 'many')!.said, 'ok')
  assert.equal(one('result-not-found', 'reconnect', 'nosuch')!.said, 'not_found')
  assert.equal(one('result-other-session', 'enable', 'many')!.said, 'other_session')
  // The same words about another server aren't this one's.
  assert.equal(mcpHits(screen('result-reconnected'), 'reconnect', 'man').length, 0)
  // A name with a regular expression's characters in it is only a name.
  assert.equal(mcpHits(screen('result-reconnected'), 'reconnect', 'm.ny').length, 0)
  assert.equal(mcpHits(`  ⎿  "two words" is disabled — enable it in /mcp first`, 'reconnect', 'two words')[0]!.said, 'turned_off')
  assert.equal(mcpHits(`  ⎿  two words requires authentication. Use /mcp to authenticate.`, 'reconnect', 'two words')[0]!.said, 'needs_auth')
  assert.equal(mcpHits(`  ⎿  MCP server "x" is already disabled`, 'disable', 'x')[0]!.said, 'already')
  assert.equal(mcpHits(`  ⎿  x was disabled in another session — disable and re-enable it in /mcp, or restart, to reconnect`, 'reconnect', 'x')[0]!.said, 'other_session')
  // What an error quotes is masked.
  const secret = mcpHits(`  ⎿  Failed to reconnect to x: bad --token ${SECRET}`, 'reconnect', 'x')[0]!
  assert.equal(secret.said, 'failed')
  assert.ok(!secret.words.includes(SECRET))
})
