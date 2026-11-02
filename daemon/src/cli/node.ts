// Node.js for the daemon: the one Hypernova downloaded (<support>/node/<version>), else the
// one running this command, when either is Node 24 or newer; otherwise the newest Node 24 from
// nodejs.org, checked against nodejs.org's SHASUMS256.txt and unpacked into Hypernova's own folder.
// Nothing outside that folder is touched.
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import type { Platform } from '../platform.ts'
import type { Cli } from './context.ts'

/** The oldest Node major the installers accept, and the line they download: 24, the LTS the daemon is tested on. */
export const NODE_MAJOR = 24

/** nodejs.org's downloads (HYPERNOVA_NODE_DIST points the tests at a stand-in). */
export const nodeDist = (cli: Pick<Cli, 'env'>) => (cli.env.HYPERNOVA_NODE_DIST ?? 'https://nodejs.org/dist').replace(/\/$/, '')

/** nodejs.org's name for this machine's processor; null for one Hypernova doesn't take. */
export const nodeArch = (arch: string): string | null => (arch === 'x64' ? 'x64' : arch === 'arm64' ? 'arm64' : null)

export const majorOf = (version: string): number => Number(/^v?(\d+)\./.exec(version.trim())?.[1] ?? 0)

/** The version a Node binary reports, or null when it doesn't run. */
export async function versionOf(cli: Cli, node: string): Promise<string | null> {
  const r = await cli.sys.run(node, ['--version'], { timeout: 10_000 })
  return r.code === 0 ? r.out.trim() : null
}

/** The Node recorded for the service, when it's still there. */
export function recordedNode(cli: Cli): string | null {
  try {
    const p = readFileSync(cli.nodePathFile, 'utf8').trim()
    return p && existsSync(p) ? p : null
  } catch {
    return null
  }
}

export function recordNode(cli: Cli, node: string): void {
  mkdirSync(cli.support, { recursive: true, mode: 0o700 })
  mkdirSync(dirname(cli.nodePathFile), { recursive: true, mode: 0o700 })
  writeFileSync(cli.nodePathFile, node + '\n', { mode: 0o600 })
}

export type Release = { version: string; file: string; url: string }

/**
 * The newest Node [major] release with a build for this system and [arch], from nodejs.org's index
 * (`files` lists "linux-arm64", "linux-x64", and for a Mac's tarballs "osx-arm64-tar", "osx-x64-tar").
 */
export function pickRelease(index: unknown, arch: string, major = NODE_MAJOR, dist = 'https://nodejs.org/dist', platform: Platform = 'linux'): Release | null {
  const a = nodeArch(arch)
  if (!a || !Array.isArray(index)) return null
  const listed = platform === 'darwin' ? `osx-${a}-tar` : `linux-${a}`
  const fits = index.filter((r: any) => typeof r?.version === 'string' && majorOf(r.version) === major && Array.isArray(r.files) && r.files.includes(listed))
  // The index is newest first, but don't count on it.
  fits.sort((x: any, y: any) => cmpVersion(y.version, x.version))
  const r = fits[0] as { version: string } | undefined
  if (!r) return null
  const file = `node-${r.version}-${platform === 'darwin' ? 'darwin' : 'linux'}-${a}.tar.gz`
  return { version: r.version, file, url: `${dist}/${r.version}/${file}` }
}

function cmpVersion(a: string, b: string): number {
  const p = (v: string) => v.replace(/^v/, '').split('.').map(Number)
  const [x, y] = [p(a), p(b)]
  for (let i = 0; i < 3; i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0)
  return 0
}

/** The checksum SHASUMS256.txt gives [file], or null when it isn't listed. */
export function shaFor(shasums: string, file: string): string | null {
  for (const line of shasums.split('\n')) {
    const [sum, name] = line.trim().split(/\s+/)
    if (name === file && /^[0-9a-f]{64}$/i.test(sum ?? '')) return sum!.toLowerCase()
  }
  return null
}

async function get(url: string, timeoutMs: number): Promise<Buffer> {
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) })
  if (!res.ok) throw new Error(`${new URL(url).host} answered ${res.status} for ${url.split('/').pop()}.`)
  return Buffer.from(await res.arrayBuffer())
}

/**
 * Downloads, checks and unpacks Node [major] into <support>/node/<version>; returns its `bin/node`.
 * A download that doesn't match nodejs.org's checksum is thrown away.
 */
export async function downloadNode(cli: Cli, say: (s: string) => void = () => {}): Promise<string> {
  const dist = nodeDist(cli)
  let index: unknown
  try {
    index = JSON.parse((await get(`${dist}/index.json`, 30_000)).toString('utf8'))
  } catch (e: any) {
    throw new Error(`Couldn’t read Node.js’s release list (${e?.message ?? e}). Check this server’s internet connection.`)
  }
  const rel = pickRelease(index, cli.arch, NODE_MAJOR, dist, cli.platform)
  if (!rel) throw new Error(`nodejs.org has no Node.js ${NODE_MAJOR} for ${cli.platform === 'darwin' ? 'macOS' : 'Linux'} on ${cli.arch}.`)
  const want = shaFor((await get(`${dist}/${rel.version}/SHASUMS256.txt`, 30_000)).toString('utf8'), rel.file)
  if (!want) throw new Error(`nodejs.org’s checksum list has no entry for ${rel.file}.`)
  say(`Downloading Node.js ${rel.version} from ${new URL(dist).host}…`)
  const tarball = await get(rel.url, 10 * 60_000)
  if (createHash('sha256').update(tarball).digest('hex') !== want)
    throw new Error('The Node.js download didn’t match nodejs.org’s checksum, so Hypernova threw it away. Try again; if it keeps happening, something on the network is changing downloads.')
  mkdirSync(cli.nodeRoot, { recursive: true, mode: 0o700 })
  const staging = join(cli.nodeRoot, `.staging-${process.pid}`)
  rmSync(staging, { recursive: true, force: true })
  mkdirSync(staging, { recursive: true })
  try {
    const file = join(staging, rel.file)
    writeFileSync(file, tarball)
    const r = await cli.sys.run('tar', ['-xzf', file, '-C', staging, '--strip-components=1', '--no-same-owner'], { timeout: 5 * 60_000 })
    if (r.code !== 0) throw new Error(`Couldn’t unpack Node.js: ${r.err.trim() || `tar exited ${r.code}`}`)
    rmSync(file, { force: true })
    const dest = join(cli.nodeRoot, rel.version)
    rmSync(dest, { recursive: true, force: true })
    renameSync(staging, dest)
    return join(dest, 'bin', 'node')
  } finally {
    rmSync(staging, { recursive: true, force: true })
  }
}

/**
 * The Node the service will run: the recorded one, else this command's own, when it's Node 24 or
 * newer; else a fresh download. Recorded in <support>/node-path either way.
 */
export async function ensureNode(cli: Cli, say: (s: string) => void = () => {}): Promise<{ node: string; version: string; downloaded: boolean }> {
  for (const node of [recordedNode(cli), process.execPath]) {
    if (!node) continue
    const version = await versionOf(cli, node)
    if (version && majorOf(version) >= NODE_MAJOR) {
      recordNode(cli, node)
      return { node, version, downloaded: false }
    }
  }
  const node = await downloadNode(cli, say)
  const version = (await versionOf(cli, node)) ?? '?'
  recordNode(cli, node)
  return { node, version, downloaded: true }
}
