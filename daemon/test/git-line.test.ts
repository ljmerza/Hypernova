// The session sheet's git line, beside "What changed": the branch, its upstream and how far apart
// they are (against a local bare upstream, nothing fetched), the uncommitted files in the folder,
// the linked worktree's name, a folder that isn't a repository, and git too slow to wait for.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { changesOf, gitLineTimeout } from '../src/changes.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-gitline-'))
const bare = join(scratch, 'up.git')
const repo = join(scratch, 'repo')
const other = join(scratch, 'other')
const git = (cwd: string, ...a: string[]) =>
  execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'init.defaultBranch=main', ...a], { cwd, stdio: 'ignore' })
const commit = (cwd: string, file: string, text: string) => {
  writeFileSync(join(cwd, file), text)
  git(cwd, 'add', file)
  git(cwd, 'commit', '-qm', file)
}

before(() => {
  git(scratch, 'init', '-q', '--bare', bare)
  git(scratch, 'clone', '-q', bare, repo)
  commit(repo, 'a.txt', 'one\n')
  git(repo, 'push', '-q', '-u', 'origin', 'main')
  // The upstream moves on from another clone; this one fetches it but doesn't merge.
  git(scratch, 'clone', '-q', bare, other)
  commit(other, 'b.txt', 'b\n')
  git(other, 'push', '-q')
  git(repo, 'fetch', '-q')
  // Two commits of its own not pushed.
  commit(repo, 'c.txt', 'c\n')
  commit(repo, 'd.txt', 'd\n')
})
after(() => rmSync(scratch, { recursive: true, force: true }))

test('the branch, its upstream and how far apart', async () => {
  const c = await changesOf(repo, [])
  assert.equal(c.branch, 'main')
  assert.equal(c.upstream, 'origin/main')
  assert.equal(c.ahead, 2)
  assert.equal(c.behind, 1)
  assert.equal(c.worktree, null)
  assert.equal(c.dirty, 0)
})

test('the uncommitted files, whatever the scope: untracked and credentials too', async () => {
  const dir = join(scratch, 'dirty')
  git(scratch, 'clone', '-q', bare, dir)
  writeFileSync(join(dir, 'a.txt'), 'two\n')
  writeFileSync(join(dir, 'new.txt'), 'n\n')
  writeFileSync(join(dir, '.env'), 'KEY=1\n')
  const c = await changesOf(dir, [join(dir, 'a.txt')])
  assert.equal(c.scope, 'session')
  assert.deepEqual(c.files.map((f) => f.path), ['a.txt'])
  assert.equal(c.dirty, 3)
  // Even with its upstream: nothing ahead or behind.
  assert.deepEqual([c.ahead, c.behind], [0, 0])
})

test('a branch with no upstream, and a detached HEAD', async () => {
  git(repo, 'branch', '-q', 'solo')
  const wt = join(scratch, 'solo-wt')
  git(repo, 'worktree', 'add', '-q', wt, 'solo')
  const c = await changesOf(wt, [])
  assert.equal(c.branch, 'solo')
  assert.deepEqual([c.upstream, c.ahead, c.behind], [null, null, null])
  const det = join(scratch, 'detached')
  git(repo, 'worktree', 'add', '-q', '--detach', det, 'HEAD')
  const d = await changesOf(det, [])
  assert.equal(d.branch, null)
  assert.deepEqual([d.upstream, d.ahead, d.behind], [null, null, null])
})

test('a linked worktree says its name; a subfolder of it too', async () => {
  const wt = join(scratch, 'worktrees', 'sheet-info')
  mkdirSync(join(scratch, 'worktrees'), { recursive: true })
  git(repo, 'worktree', 'add', '-q', '-b', 'sheet-info', wt)
  mkdirSync(join(wt, 'sub'))
  const c = await changesOf(wt, [])
  assert.equal(c.worktree, 'sheet-info')
  assert.equal(c.branch, 'sheet-info')
  assert.equal((await changesOf(join(wt, 'sub'), [])).worktree, 'sheet-info')
  // The main checkout is no worktree of its own.
  assert.equal((await changesOf(repo, [])).worktree, null)
})

test('a gone upstream: the name, no counts', async () => {
  const dir = join(scratch, 'gone')
  git(scratch, 'clone', '-q', bare, dir)
  git(dir, 'checkout', '-q', '-b', 'topic')
  git(dir, 'push', '-q', '-u', 'origin', 'topic')
  git(dir, 'push', '-q', 'origin', '--delete', 'topic')
  const c = await changesOf(dir, [])
  assert.equal(c.upstream, 'origin/topic')
  assert.deepEqual([c.ahead, c.behind], [null, null])
})

test('kept briefly: a commit just after shows on the next look, not this one', async () => {
  const dir = join(scratch, 'cached')
  git(scratch, 'clone', '-q', bare, dir)
  assert.equal((await changesOf(dir, [])).ahead, 0)
  commit(dir, 'e.txt', 'e\n')
  const again = await changesOf(dir, [])
  assert.equal(again.ahead, 0)
  // The folder's own changes are never kept: the commit took e.txt out of them at once.
  assert.equal(again.dirty, 0)
})

test('not a git folder: nothing of git', async () => {
  const plain = mkdtempSync(join(scratch, 'plain-'))
  writeFileSync(join(plain, 'x.txt'), 'x\n')
  const c = await changesOf(plain, [])
  assert.equal(c.git, false)
  assert.deepEqual([c.branch, c.upstream, c.ahead, c.behind, c.dirty, c.worktree], [null, null, null, null, 0, null])
})

test('git too slow for the line: the changes still come, without it', async () => {
  const dir = join(scratch, 'slow')
  git(scratch, 'clone', '-q', bare, dir)
  writeFileSync(join(dir, 'a.txt'), 'slow\n')
  // A git that hangs on the upstream's question only.
  const bin = join(scratch, 'bin')
  mkdirSync(bin)
  const realGit = execFileSync('sh', ['-c', 'command -v git']).toString().trim()
  writeFileSync(join(bin, 'git'), `#!/bin/sh\ncase "$*" in *for-each-ref*) exec sleep 10;; esac\nexec ${realGit} "$@"\n`)
  chmodSync(join(bin, 'git'), 0o755)
  const path = process.env.PATH
  const ms = gitLineTimeout.ms
  process.env.PATH = `${bin}:${path}`
  gitLineTimeout.ms = 300
  try {
    const t0 = Date.now()
    const c = await changesOf(dir, [])
    assert.ok(Date.now() - t0 < 5000, 'waited for the hanging git')
    assert.equal(c.branch, 'main')
    assert.deepEqual([c.upstream, c.ahead, c.behind], [null, null, null])
    assert.deepEqual(c.files.map((f) => f.path), ['a.txt'])
    assert.equal(c.dirty, 1)
  } finally {
    process.env.PATH = path
    gitLineTimeout.ms = ms
  }
})
