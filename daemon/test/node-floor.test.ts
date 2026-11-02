// Node's security floor: docs/DECISIONS.md, "Node's floor". The daemon's own check, the two install
// scripts' and package.json's engines say the same thing.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { nodeMeetsFloor, pickRelease } from '../src/cli/node.ts'

const CASES: [string, boolean][] = [
  ['v22.23.4', false], // The daemon runs on it (engines); the installers stay on 24 and up.
  ['v23.11.1', false],
  ['v24.0.0', false],
  ['v24.9.0', false],
  ['v24.22.0', false],
  ['v24.22.1', true],
  ['v24.22.2', true],
  ['v24.23.0', true],
  ['v25.9.0', false], // Past its end of life, with no October release.
  ['v26.12.0', false],
  ['v26.12.1', true],
  ['v26.13.0', true],
  ['v28.0.0', true],
]

test('the floor: October 2026 on the 24 and 26 lines, never 25', () => {
  for (const [v, ok] of CASES) assert.equal(nodeMeetsFloor(v), ok, v)
})

for (const script of ['install-linux.sh', 'install-macos.sh']) {
  test(`${script} checks the same floor`, () => {
    const text = readFileSync(new URL(`../../scripts/${script}`, import.meta.url), 'utf8')
    const code = /^node_ok\(\) .* -e "(.*)" 2>\/dev\/null; \}$/m.exec(text)?.[1]
    assert.ok(code, 'node_ok is one line with its check in double quotes')
    for (const [v, ok] of CASES) {
      const pretend = `Object.defineProperty(process,'versions',{value:{node:${JSON.stringify(v.slice(1))}}});`
      assert.equal(spawnSync(process.execPath, ['-e', pretend + code]).status, ok ? 0 : 1, v)
    }
  })
}

test('package.json says the same: 22.23.4, 24.22.1 and 26.12.1 are each where their line starts', () => {
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.engines.node, '^22.23.4 || ^24.22.1 || >=26.12.1')
})

test('a release list with nothing at the floor gives nothing to download', () => {
  const old = [{ version: 'v24.22.0', files: ['linux-x64'] }]
  assert.equal(pickRelease(old, 'x64'), null)
  const both = [...old, { version: 'v24.22.1', files: ['linux-x64'] }]
  assert.equal(pickRelease(both, 'x64')?.version, 'v24.22.1')
})
