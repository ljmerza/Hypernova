// The programs Telescope runs (adb, simctl, osascript, ps, lsof, sips): always by full path, with fixed
// arguments, never through a shell, so nothing a transcript or a phone says can become part of a
// command line. The only words that come from outside are adb device ids, and those are checked
// first (android.ts). Whatever else a program needs (a file to draw, the terminal's text) goes in
// on its standard input, which doesn't show in the process list either.
import { spawn } from 'node:child_process'

export type Ran = {
  /** The exit code; null when it was killed (too slow, or it printed too much). */
  code: number | null
  out: Buffer
  err: string
  timedOut: boolean
  /** The program isn't there. */
  missing: boolean
}

export function runFile(cmd: string, args: string[], o: { input?: string; timeout?: number; max?: number } = {}): Promise<Ran> {
  return new Promise((resolve) => {
    let done = false
    const finish = (r: Ran) => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve(r)
    }
    const out: Buffer[] = []
    let size = 0
    let err = ''
    let timedOut = false
    let over = false
    let child: ReturnType<typeof spawn>
    try {
      child = spawn(cmd, args, { stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, LC_ALL: 'C' } })
    } catch (e: any) {
      resolve({ code: null, out: Buffer.alloc(0), err: String(e?.message ?? e), timedOut: false, missing: e?.code === 'ENOENT' })
      return
    }
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGKILL')
    }, o.timeout ?? 15_000)
    child.stdout!.on('data', (d: Buffer) => {
      size += d.length
      if (size > (o.max ?? 64 * 1024 * 1024)) {
        over = true
        child.kill('SIGKILL')
      } else out.push(d)
    })
    child.stderr!.on('data', (d: Buffer) => {
      if (err.length < 8192) err += d.toString('utf8')
    })
    child.on('error', (e: NodeJS.ErrnoException) => finish({ code: null, out: Buffer.alloc(0), err: String(e.message), timedOut, missing: e.code === 'ENOENT' || e.code === 'EACCES' }))
    child.on('close', (code) => finish({ code: over ? null : code, out: Buffer.concat(out), err, timedOut, missing: false }))
    child.stdin!.on('error', () => {})
    child.stdin!.end(o.input ?? '')
  })
}
