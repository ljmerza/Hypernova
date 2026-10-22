import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isSecretFile } from '../src/secretfiles.ts'

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
