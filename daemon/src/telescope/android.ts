// Telescope's emulator source: the Android emulator or a phone on USB or Wi-Fi debugging, through
// adb (`adb exec-out screencap -p`, a PNG of the screen as the device draws it). Which device: the
// one the phone names, else the one the session used if its commands say (`adb -s …`,
// `ANDROID_SERIAL=…`, `flutter run -d …`), else the only one connected.
import { existsSync } from 'node:fs'
import { HOME } from '../util.ts'
import { pngSize } from '../shots.ts'
import { fail, type Capture } from './common.ts'
import { runFile } from './run.ts'
import { PLATFORM, adbCandidates } from '../platform.ts'

/** adb, where Android Studio and Homebrew put it (a LaunchAgent's PATH has neither). */
export function findAdb(): string | null {
  const env = process.env.HYPERNOVA_ADB
  if (env) return existsSync(env) ? env : null
  // Android Studio's SDK, then Homebrew's on a Mac or the system package's on Linux (platform.ts).
  return adbCandidates(PLATFORM, process.env, HOME).find((p) => existsSync(p)) ?? null
}

/**
 * A device id as adb writes them: `emulator-5554`, a USB serial, `192.168.1.20:5555`, or a Wi-Fi
 * pairing's `adb-R58M…-x7y._adb-tls-connect._tcp`. The only words from outside that reach a
 * command line, so nothing else passes (no spaces, quotes, or a leading dash adb would read as
 * an option).
 */
export const validSerial = (s: unknown): s is string => typeof s === 'string' && /^[A-Za-z0-9][\w.:-]{0,95}$/.test(s)

export type AdbDevice = { serial: string; state: string; model: string | null; emulator: boolean }

/** `adb devices -l`: one device a line, its state, then `key:value` details. */
export function parseAdbDevices(out: string): AdbDevice[] {
  const list: AdbDevice[] = []
  for (const line of out.split('\n')) {
    const m = /^(\S+)\s+(device|offline|unauthorized|recovery|sideload|bootloader|authorizing|no permissions\b[^\n]*?)(?:\s+(.*))?$/.exec(line.trim())
    if (!m || !validSerial(m[1])) continue
    const model = /(?:^|\s)model:(\S+)/.exec(m[3] ?? '')?.[1]?.replace(/_/g, ' ') ?? null
    list.push({ serial: m[1]!, state: m[2]!.split(' ')[0]!, model, emulator: /^emulator-\d+$/.test(m[1]!) })
  }
  return list
}

/** Which kind of device the owner said: "telescope phone" or "telescope emulator". */
export type DeviceKind = 'phone' | 'emulator'

/**
 * The device to show: the one asked for (it must be connected), else the session's if it's
 * connected (and of the kind said), else the only one of the kind said, else the only one ready;
 * with several and nothing said, the only emulator (the one a session usually builds on). A device that
 * isn't ready says why. The glasses can't say a device id, so the words for several ask for a kind.
 */
export function chooseDevice(devices: AdbDevice[], asked: string | null, used: string | null, prefer: DeviceKind | null = null): AdbDevice {
  if (asked) {
    const d = devices.find((x) => x.serial === asked)
    if (!d) throw fail(404, 'no_device', `${asked} isn’t connected to the Mac.`)
    return ready(d)
  }
  if (!devices.length) throw fail(404, 'no_emulator', 'No emulator is running, and no Android phone is connected.')
  const pool = prefer ? devices.filter((d) => d.emulator === (prefer === 'emulator')) : devices
  const mine = used ? pool.find((x) => x.serial === used) : undefined
  if (mine) return ready(mine)
  if (!pool.length) {
    if (prefer === 'emulator') throw fail(404, 'no_emulator', 'No emulator is running.')
    throw fail(404, 'no_phone', 'No Android phone is connected to the Mac.')
  }
  if (pool.length === 1) return ready(pool[0]!)
  // A phone left plugged in but not allowed (or offline) isn't one the owner means.
  const usable = pool.filter((d) => d.state === 'device')
  if (usable.length === 1) return usable[0]!
  const emulators = usable.filter((d) => d.emulator)
  if (!prefer && emulators.length === 1) return emulators[0]!
  const names = pool.map((d) => d.serial)
  throw fail(409, 'which_device', prefer
    ? `Several ${prefer === 'phone' ? 'phones' : 'emulators'} are connected (${names.join(', ')}), and the session didn’t name one.`
    : `Several Android devices are connected (${names.join(', ')}). Say “telescope phone” or “telescope emulator”.`, { devices: names })
}

function ready(d: AdbDevice): AdbDevice {
  if (d.state === 'device') return d
  if (d.state === 'unauthorized') throw fail(409, 'device_unauthorized', `Allow USB debugging on ${d.model ?? d.serial} first.`)
  throw fail(409, 'device_offline', `${d.model ?? d.serial} isn’t ready (${d.state}).`)
}

/** A screenshot of the chosen device. */
export async function captureAndroid(o: { asked: string | null; used: string | null; prefer?: DeviceKind | null }): Promise<Capture> {
  const adb = findAdb()
  if (!adb) throw fail(501, 'no_adb', 'Android’s adb isn’t on this Mac. Install Android Studio’s platform tools.')
  if (o.asked !== null && !validSerial(o.asked)) throw fail(400, 'bad_device', 'That isn’t a device id.')
  const listed = await runFile(adb, ['devices', '-l'], { timeout: 8000, max: 1024 * 1024 })
  if (listed.code !== 0) throw fail(502, 'capture_failed', 'adb didn’t answer. Is the emulator still starting?')
  const d = chooseDevice(parseAdbDevices(listed.out.toString('utf8')), o.asked, validSerial(o.used) ? o.used : null, o.prefer ?? null)
  const shot = await runFile(adb, ['-s', d.serial, 'exec-out', 'screencap', '-p'], { timeout: 15_000, max: 40 * 1024 * 1024 })
  const size = pngSize(shot.out)
  if (shot.code !== 0 || !size) throw fail(502, 'capture_failed', `${d.model ?? d.serial} didn’t give a picture.`)
  // An emulator's model is the system image's ("sdk gphone64 arm64"); its AVD's name says more.
  let name = d.model ?? d.serial
  if (d.emulator) {
    const avd = await runFile(adb, ['-s', d.serial, 'emu', 'avd', 'name'], { timeout: 3000, max: 4096 })
    const first = avd.code === 0 ? avd.out.toString('utf8').split('\n')[0]!.trim() : ''
    if (/^[\w.-]{1,64}$/.test(first) && first !== 'OK') name = first.replace(/_/g, ' ')
  }
  return { png: shot.out, title: d.emulator ? `Android emulator · ${name}` : name, label: name, device: d.serial }
}
