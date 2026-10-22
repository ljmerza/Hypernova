// "What changed": the files a folder changed since the last commit, their +/- counts, one file's
// diff, and what stays out (secrets, links leading outside, files that didn't change).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { changesOf, diffOf, MAX_DIFF } from '../src/changes.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-changes-'))
const repo = join(scratch, 'repo')
const git = (...a: string[]) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...a], { cwd: repo, stdio: 'ignore' })

before(() => {
  mkdirSync(join(repo, 'sub'), { recursive: true })
  git('init', '-q')
  writeFileSync(join(repo, 'a.txt'), 'one\ntwo\nthree\n')
  writeFileSync(join(repo, 'gone.txt'), 'bye\n')
  writeFileSync(join(repo, 'sub', 'b.txt'), 'x\n')
  git('add', '.')
  git('commit', '-qm', 'first')
  writeFileSync(join(repo, 'a.txt'), 'one\nTWO\nthree\nfour\n')
  rmSync(join(repo, 'gone.txt'))
  writeFileSync(join(repo, 'new.txt'), 'n1\nn2\n')
  writeFileSync(join(repo, '.env'), 'KEY=secret\n')
  writeFileSync(join(repo, 'pic.bin'), Buffer.from([0, 1, 2, 3]))
  writeFileSync(join(scratch, 'outside.txt'), 'top secret\n')
  symlinkSync(join(scratch, 'outside.txt'), join(repo, 'link.txt'))
})
after(() => rmSync(scratch, { recursive: true, force: true }))

test('lists changed files with counts, hides credentials', async () => {
  const c = await changesOf(repo, [])
  assert.equal(c.git, true)
  assert.equal(c.scope, 'tree')
  const by = Object.fromEntries(c.files.map((f) => [f.path, f]))
  assert.deepEqual([by['a.txt']!.add, by['a.txt']!.del, by['a.txt']!.status], [2, 1, 'M'])
  assert.deepEqual([by['gone.txt']!.add, by['gone.txt']!.del, by['gone.txt']!.status], [0, 1, 'D'])
  assert.deepEqual([by['new.txt']!.add, by['new.txt']!.del, by['new.txt']!.untracked], [2, 0, true])
  assert.equal(by['pic.bin']!.binary, true)
  assert.equal(by['.env'], undefined)
  assert.equal(c.hidden, 1)
})

test('limits to what the session touched, all=1 brings the rest', async () => {
  const c = await changesOf(repo, [join(repo, 'a.txt')])
  assert.equal(c.scope, 'session')
  assert.deepEqual(c.files.map((f) => f.path), ['a.txt'])
  assert.ok(c.others >= 3)
  const all = await changesOf(repo, [join(repo, 'a.txt')], true)
  assert.equal(all.scope, 'tree')
  assert.equal(all.files[0]!.path, 'a.txt')
  assert.ok(all.files.length > 3)
})

test('a subfolder session sees only its own folder', async () => {
  writeFileSync(join(repo, 'sub', 'b.txt'), 'x\ny\n')
  const c = await changesOf(join(repo, 'sub'), [])
  assert.deepEqual(c.files.map((f) => f.path), ['b.txt'])
})

test('diffs: modified, new, deleted', async () => {
  const d = await diffOf(repo, 'a.txt')
  assert.match(d.diff, /^\+TWO$/m)
  assert.match(d.diff, /^-two$/m)
  assert.match((await diffOf(repo, 'new.txt')).diff, /^\+n2$/m)
  assert.match((await diffOf(repo, 'gone.txt')).diff, /^-bye$/m)
  assert.equal((await diffOf(repo, 'pic.bin')).binary, true)
})

test('refuses secrets, escapes, unchanged files', async () => {
  await assert.rejects(diffOf(repo, '.env'), { code: 'secret' })
  await assert.rejects(diffOf(repo, '../outside.txt'), { code: 'outside' })
  await assert.rejects(diffOf(repo, join(scratch, 'outside.txt')), { code: 'bad_path' })
  await assert.rejects(diffOf(repo, 'link.txt'), { code: 'outside' })
  await assert.rejects(diffOf(repo, 'sub/b.txt/../../.git/config'), { status: 404 })
  writeFileSync(join(repo, 'same.txt'), 's\n')
  git('add', 'same.txt')
  git('commit', '-qm', 'same')
  await assert.rejects(diffOf(repo, 'same.txt'), { code: 'unchanged' })
})

test('a huge diff is cut at whole lines', async () => {
  writeFileSync(join(repo, 'big.txt'), 'line of text here\n'.repeat(40_000))
  const d = await diffOf(repo, 'big.txt')
  assert.equal(d.truncated, true)
  assert.ok(Buffer.byteLength(d.diff) <= MAX_DIFF)
  assert.ok(d.diff.endsWith('\n'))
})

test('not a git folder', async () => {
  const plain = mkdtempSync(join(scratch, 'plain-'))
  const c = await changesOf(plain, [])
  assert.equal(c.git, false)
  assert.deepEqual(c.files, [])
})
