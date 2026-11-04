// Modified by Leonardo Merza, 2026. See NOTICE.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isAgentPlan, isSecretFile } from '../src/secretfiles.ts'

test('credential files stay on the Mac', () => {
  for (const p of ['/p/app/.env', '/p/app/.env.local', '/p/app/.env.production', '/p/app/certs/server.key', '/p/app/cert.pem', '/Users/me/.ssh/config', '/p/app/id_ed25519', '/p/app/.npmrc', '/Users/me/.aws/credentials', '/p/app/release.keystore'])
    assert.equal(isSecretFile(p), true, p)
})

test('histories and other tools’ credential files stay too, and a Mac’s case doesn’t matter', () => {
  for (const p of ['/Users/me/.zsh_history', '/Users/me/.bash_history', '/Users/me/.psql_history', '/Users/me/.node_repl_history', '/Users/me/.pgpass', '/Users/me/.my.cnf', '/Users/me/.s3cfg', '/Users/me/.SSH/config', '/Users/me/.Aws/config', '/p/app/.GIT-CREDENTIALS'])
    assert.equal(isSecretFile(p), true, p)
  for (const p of ['/p/app/history.md', '/p/app/src/history.ts', '/p/app/.zshrc']) assert.equal(isSecretFile(p), false, p)
})

test('ordinary files and committed templates open', () => {
  for (const p of ['/p/app/.env.example', '/p/app/.env.sample', '/p/app/README.md', '/p/app/src/env.ts', '/p/app/keys.ts', '/p/app/docs/ssh.md', '/p/app/environment.yml'])
    assert.equal(isSecretFile(p), false, p)
})

// The one way into a key place (docs/PROTOCOL.md, "Agents' plans"): this table is the rule.
// Real paths only: files.test.ts has the links, with real ones on a disk.
test('Markdown in an agent’s plans folder may be read, and nothing else in its folder', () => {
  const agents = ['/home/me/.codex', '/home/me/.claude']
  const allowed = [
    '/home/me/.codex/plans/handoff-payelements-inc129.md',
    '/home/me/.claude/plans/quiet-river.md',
    '/home/me/.codex/plans/older/2026/notes.md',
    '/home/me/.codex/plans/NOTES.MD',
    '/home/me/.claude/plans/Mixed.Md',
    '/home/me/.codex/plans/credentials.md',
  ]
  const refused = [
    // The rest of those folders, by name.
    '/home/me/.codex/auth.json',
    '/home/me/.codex/config.toml',
    '/home/me/.codex/AGENTS.md',
    '/home/me/.codex/README.md',
    '/home/me/.codex/sessions/2026/rollout.md',
    '/home/me/.codex/state.sqlite',
    '/home/me/.claude/credentials.json',
    '/home/me/.claude/.credentials.json',
    '/home/me/.claude/CLAUDE.md',
    '/home/me/.claude/settings.json',
    '/home/me/.claude/projects/-home-me/memory/MEMORY.md',
    '/home/me/.claude/skills/handoff/SKILL.md',
    '/home/me/.claude.json',
    // In the folder, but not Markdown.
    '/home/me/.codex/plans/auth.json',
    '/home/me/.codex/plans/config.toml',
    '/home/me/.codex/plans/x.md.bak',
    '/home/me/.codex/plans/x.md.tmp',
    '/home/me/.codex/plans/x.mdx',
    '/home/me/.codex/plans/x.markdown',
    '/home/me/.codex/plans/md',
    '/home/me/.codex/plans/.md',
    // Markdown that isSecretFile turns away.
    '/home/me/.codex/plans/.env.md',
    '/home/me/.codex/plans/.ssh/x.md',
    '/home/me/.claude/plans/.aws/x.md',
    '/home/me/.claude/plans/.GnuPG/x.md',
    // Beside the folder, or named like it.
    '/home/me/.codex/plans',
    '/home/me/.codex/plans.md',
    '/home/me/.codex/plans-old/x.md',
    '/home/me/.codex/sessions/plans/x.md',
    '/home/me/.claude/worktrees/w/plans/x.md',
    // Another spelling of the folder is another folder here: a Mac's realpath spells it as the disk does first.
    '/home/me/.codex/PLANS/x.md',
    '/home/me/.CODEX/plans/x.md',
    // Not an agent's: those go by the usual rules.
    '/home/me/plans/x.md',
    '/home/me/.gemini/plans/x.md',
    '/elsewhere/.codex/plans/x.md',
  ]
  for (const p of allowed) assert.equal(isAgentPlan(p, agents), true, p)
  for (const p of refused) assert.equal(isAgentPlan(p, agents), false, p)
})
