// Claude Code's SDK, loaded when first needed (src/sdk.ts): missing, it's a coded 409 (`sdk_missing`)
// that says how to get it; fetched meanwhile, the next ask loads it without a restart; a broken copy
// fetched again loads too; and the version this daemon pins comes from `sdk-version`, else the lock.
// A stand-in package in a scratch folder: nothing here loads the real SDK or runs Claude Code.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { SDK_MISSING_LINUX, SDK_MISSING_MAC, SDK_PACKAGE, SdkMissingError, sdkAt, sdkLoader, sdkState } from '../src/sdk.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hn-sdk-'))
after(() => rmSync(scratch, { recursive: true, force: true }))

/** A daemon folder with no packages: its package.json, and what else [files] holds. */
function daemonDir(name: string, files: Record<string, string> = {}): string {
  const dir = join(scratch, name)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: 'hypernova-daemon', type: 'module' }))
  for (const [f, text] of Object.entries(files)) writeFileSync(join(dir, f), text)
  return dir
}

/** A stand-in SDK in [dir]'s node_modules, shaped as the real one ships (an ESM "." export). */
function putSdk(dir: string, version: string, body: string): void {
  const pkg = join(dir, 'node_modules', ...SDK_PACKAGE.split('/'))
  mkdirSync(pkg, { recursive: true })
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: SDK_PACKAGE, version, type: 'module', exports: { '.': { types: './sdk.d.ts', default: './sdk.mjs' } } }))
  writeFileSync(join(pkg, 'sdk.mjs'), body)
}

const LATER = () => Date.now() + 60_000

test('missing: a coded 409 with the way to get it, and the state says so', async () => {
  const dir = daemonDir('missing')
  const sdk = sdkLoader(dir)
  await assert.rejects(sdk.load(), (e: unknown) => {
    assert.ok(e instanceof SdkMissingError)
    assert.equal(e.code, 'sdk_missing')
    assert.equal(e.status, 409)
    assert.ok([SDK_MISSING_MAC, SDK_MISSING_LINUX].includes(e.message))
    assert.match(e.message, /Claude Code’s SDK isn’t installed/)
    return true
  })
  assert.deepEqual(sdk.state(), { installed: false, version: null, wanted: null })
  // The words name the fix on each system: Setup's button on a Mac, hypernova setup on Linux.
  assert.match(SDK_MISSING_MAC, /Setup.*Fetch again/)
  assert.match(SDK_MISSING_LINUX, /hypernova setup/)
})

test('fetched while the daemon runs: the next ask loads it, no restart', async () => {
  const dir = daemonDir('later', { 'sdk-version': '9.9.9\n' })
  const sdk = sdkLoader(dir)
  await assert.rejects(sdk.load(), SdkMissingError)
  assert.deepEqual(sdk.state(), { installed: false, version: null, wanted: '9.9.9' })
  putSdk(dir, '9.9.9', 'export const query = () => "q"\nexport const forkSession = async () => ({ sessionId: "s" })\n')
  const m = await sdk.load()
  assert.equal(typeof m.query, 'function')
  assert.equal(typeof m.forkSession, 'function')
  // Loaded once, it's the same module every time.
  assert.equal(await sdk.load(), m)
  // The state is read at most every 5 s; past that, it's there.
  assert.deepEqual(sdk.state(LATER()), { installed: true, version: '9.9.9', wanted: '9.9.9' })
})

test('a copy that doesn’t load is sdk_missing too, and one fetched again loads', async () => {
  const dir = daemonDir('broken')
  putSdk(dir, '1.0.0', 'throw new Error("half a download")\n')
  const sdk = sdkLoader(dir)
  await assert.rejects(sdk.load(), (e: unknown) => e instanceof SdkMissingError && /half a download/.test(String((e.cause as Error)?.message)))
  putSdk(dir, '1.0.1', 'export const query = () => "fixed"\n')
  assert.equal((await sdk.load()).query({} as never), 'fixed')
})

test('the pinned version: sdk-version first, else the lockfile, else unknown', () => {
  const lock = JSON.stringify({ packages: { '': {}, [`node_modules/${SDK_PACKAGE}`]: { version: '0.3.1' } } })
  assert.equal(sdkAt(daemonDir('pin-file', { 'sdk-version': '0.3.2\n', 'package-lock.json': lock })).wanted, '0.3.2')
  assert.equal(sdkAt(daemonDir('pin-lock', { 'package-lock.json': lock })).wanted, '0.3.1')
  assert.equal(sdkAt(daemonDir('pin-none')).wanted, null)
  // A package.json without its entry file isn't an SDK that loads.
  const half = daemonDir('pin-half')
  putSdk(half, '0.3.1', '')
  rmSync(join(half, 'node_modules', ...SDK_PACKAGE.split('/'), 'sdk.mjs'))
  assert.equal(sdkAt(half).installed, false)
})

test('this daemon’s own state reads its folder', () => {
  const s = sdkState()
  assert.equal(typeof s.installed, 'boolean')
  // The checkout pins one in its lockfile.
  assert.match(s.wanted ?? '', /^\d+\.\d+\.\d+/)
})
