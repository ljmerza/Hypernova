// Telescope's simulator source: the iOS Simulator, through Xcode's simctl
// (`xcrun simctl io booted screenshot`). A Mac with only the Command Line Tools has no simctl: that
// says so plainly rather than failing somewhere deeper.
import { existsSync, readFileSync } from 'node:fs'
import { pngSize, MAX_PNG } from '../shots.ts'
import { fail, scratchFile, type Capture } from './common.ts'
import { runFile } from './run.ts'
import { LINUX_REASONS, has } from '../platform.ts'

export const findXcrun = (): string | null => {
  const env = process.env.HYPERNOVA_XCRUN
  if (env) return existsSync(env) ? env : null
  return existsSync('/usr/bin/xcrun') ? '/usr/bin/xcrun' : null
}

/** What simctl's complaint means, in words for the lens. */
export function simctlProblem(err: string): ReturnType<typeof fail> {
  if (/unable to find utility "simctl"|not a developer tool|xcode-select: error|requires Xcode|active developer directory/i.test(err))
    return fail(501, 'no_simulator', 'The iOS Simulator needs Xcode on the Mac.')
  if (/No devices are booted|Unable to find a device matching the provided destination UDID or alias: booted|device.*is not booted/i.test(err))
    return fail(404, 'simulator_off', 'No iOS Simulator is running.')
  return fail(502, 'capture_failed', 'The iOS Simulator didn’t give a picture.')
}

/** The booted Simulator's screen. */
export async function captureSimulator(): Promise<Capture> {
  // The code clients already know for "no Simulator here", with the reason for this server.
  if (!has('simulator')) throw fail(501, 'no_simulator', LINUX_REASONS.simulator)
  const xcrun = findXcrun()
  if (!xcrun) throw fail(501, 'no_simulator', 'The iOS Simulator needs Xcode on the Mac.')
  const out = scratchFile()
  try {
    const r = await runFile(xcrun, ['simctl', 'io', 'booted', 'screenshot', '--type=png', out.path], { timeout: 20_000, max: 1024 * 1024 })
    if (r.code !== 0) throw simctlProblem(`${r.err}\n${r.out.toString('utf8')}`)
    let png = Buffer.alloc(0)
    try {
      png = readFileSync(out.path)
    } catch {}
    if (!pngSize(png) || png.length > MAX_PNG) throw fail(502, 'capture_failed', 'The iOS Simulator didn’t give a picture.')
    return { png, title: 'iOS Simulator', label: 'iOS Simulator' }
  } finally {
    out.remove()
  }
}
