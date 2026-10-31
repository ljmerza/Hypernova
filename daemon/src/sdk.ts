// Claude Code's Agent SDK (@anthropic-ai/claude-agent-sdk), loaded the first time something needs
// it. It is Anthropic's ("All rights reserved"), so the Mac app doesn't carry it: Setup fetches the
// version the lockfile pins from npm into the installed daemon, once (docs/DECISIONS.md, "Going
// public"); the Linux install gets it with the rest of `npm ci`. The daemon starts and serves
// without it: Codex, shared terminals and everything else work, and only what drives Claude Code
// through it (sessions the daemon runs itself, the "/" list, Rewind) answers 409 `sdk_missing`
// with the way to get it (docs/PROTOCOL.md, "Claude Code's SDK").
//
// Nothing here imports the rest of the daemon, so `hypernova doctor` can ask about an installed
// copy too (sdkAt).
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import type * as Sdk from '@anthropic-ai/claude-agent-sdk'
import { ON_MAC } from './platform.ts'

export type ClaudeSdk = typeof Sdk
export const SDK_PACKAGE = '@anthropic-ai/claude-agent-sdk'
/** An optional one-line file next to a bundled daemon: the SDK version to fetch (else the lockfile's). */
export const SDK_VERSION_FILE = 'sdk-version'
/** This daemon's own folder (its package.json and node_modules). */
export const DAEMON_DIR = dirname(dirname(fileURLToPath(import.meta.url)))

/**
 * The SDK in a daemon's folder: [installed] when its package is there with its entry file,
 * [version] that package's, [wanted] the version this daemon pins (its `sdk-version`, else its
 * lockfile's; null when neither says).
 */
export type SdkState = { installed: boolean; version: string | null; wanted: string | null }

/** What a client shows when the SDK isn't there, with the way to get it on this system. */
export const SDK_MISSING_MAC = 'Claude Code’s SDK isn’t installed on this Mac yet. On the Mac, open Hypernova’s Setup, go to Background service and click Fetch again.'
export const SDK_MISSING_LINUX = 'Claude Code’s SDK isn’t installed on this server. Run hypernova setup again: it installs the daemon’s packages with npm.'

/** The SDK isn't there (or doesn't load): 409 `sdk_missing` wherever it's asked for. */
export class SdkMissingError extends Error {
  readonly status = 409
  readonly code = 'sdk_missing'
  constructor(message = ON_MAC ? SDK_MISSING_MAC : SDK_MISSING_LINUX, options?: { cause?: unknown }) {
    super(message, options)
  }
}

/** [pkg]'s folder under [root]/node_modules, its version and the file its "." export names, if it's all there. */
function findPackage(root: string, pkg: string): { version: string | null; entry: string } | null {
  const dir = join(root, 'node_modules', ...pkg.split('/'))
  let j: any
  try {
    j = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'))
  } catch {
    return null
  }
  // The SDK is ESM with one "." export ({types, default}); `main` and index.js only as a last resort.
  const dot = typeof j?.exports === 'string' ? j.exports : j?.exports?.['.']
  const target = typeof dot === 'string' ? dot : (dot?.import ?? dot?.node ?? dot?.default)
  const entry = join(dir, typeof target === 'string' ? target : typeof j?.main === 'string' ? j.main : 'index.js')
  return existsSync(entry) ? { version: typeof j?.version === 'string' ? j.version : null, entry } : null
}

/** The version a daemon in [root] pins: its `sdk-version` line, else its lockfile's entry. */
function pinnedIn(root: string, pkg: string): string | null {
  try {
    const v = readFileSync(join(root, SDK_VERSION_FILE), 'utf8').trim()
    if (v) return v
  } catch {}
  try {
    const v = JSON.parse(readFileSync(join(root, 'package-lock.json'), 'utf8'))?.packages?.[`node_modules/${pkg}`]?.version
    if (typeof v === 'string' && v) return v
  } catch {}
  return null
}

export type SdkLoader = { load(): Promise<ClaudeSdk>; state(now?: number): SdkState }

/**
 * The SDK of the daemon in [root]. `load` imports it once and keeps it; while it's missing (or
 * doesn't load) every call rejects with SdkMissingError and looks again the next time, so one
 * fetched meanwhile (Setup's Fetch again) is used without a restart. `state` is cheap (two small
 * reads, at most every 5 s) for the snapshot. [pkg]: tests point it at a stand-in.
 */
export function sdkLoader(root = DAEMON_DIR, pkg = SDK_PACKAGE): SdkLoader {
  let loaded: Promise<ClaudeSdk> | null = null
  let ready = false
  let failed = 0
  let wanted: string | null | undefined
  let seen: { at: number; s: SdkState } | null = null
  return {
    load() {
      if (loaded) return loaded
      const found = findPackage(root, pkg)
      if (!found) return Promise.reject(new SdkMissingError())
      // Node keeps a module that failed to load as failed: a copy fetched again since is asked for
      // under another address, so it's read afresh.
      const url = pathToFileURL(found.entry).href + (failed ? `?again=${failed}` : '')
      const p: Promise<ClaudeSdk> = import(url).then(
        (m) => {
          ready = true
          seen = null
          return m as ClaudeSdk
        },
        (e) => {
          // Not kept: a broken copy fetched again loads on the next call.
          if (loaded === p) loaded = null
          failed++
          throw new SdkMissingError(undefined, { cause: e })
        },
      )
      loaded = p
      return p
    },
    state(now = Date.now()) {
      if (seen && now - seen.at < 5000) return seen.s
      if (wanted === undefined) wanted = pinnedIn(root, pkg)
      const found = findPackage(root, pkg)
      // Loaded once, it stays usable in this process whatever happens to the folder.
      const s: SdkState = { installed: ready || !!found, version: found?.version ?? null, wanted }
      seen = { at: now, s }
      return s
    },
  }
}

const own = sdkLoader()
/** This daemon's SDK: rejects with SdkMissingError (409 `sdk_missing`) while it isn't installed. */
export const claudeSdk = (): Promise<ClaudeSdk> => own.load()
/** Whether this daemon's SDK is there, its version and the pinned one (the snapshot, `/local/status`). */
export const sdkState = (): SdkState => own.state()
/** The SDK in the daemon folder [dir] (`hypernova doctor`, for the installed copy). */
export const sdkAt = (dir: string): SdkState => sdkLoader(dir).state()
