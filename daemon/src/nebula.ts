// Nebula (docs/PROTOCOL.md, "Nebula"): the folder where new sessions from the glasses are born, each
// in a subfolder of its own named after its first prompt and `git init`ed. ~/Programming/Nebula by
// default (inside the projects folder), movable from the phone, made when the first session starts.
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, statSync } from 'node:fs'
import { isAbsolute, join, relative, resolve } from 'node:path'
import { HOME, log } from './util.ts'
import { saveSettings, settings } from './store.ts'
import { broadcast } from './hub.ts'
import { listProjects, type Project } from './services.ts'

export type NebulaInfo = { path: string; default: string; exists: boolean }

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

export const defaultNebula = () => join(settings.projectsRoot || join(HOME, 'Programming'), 'Nebula')
export const nebulaRoot = () => settings.nebula || defaultNebula()

/** A path as the phone shows it: "~/Programming/Nebula" inside the home folder. */
const tilde = (p: string) => (p === HOME || p.startsWith(HOME + '/') ? '~' + p.slice(HOME.length) : p)

export function nebulaInfo(): NebulaInfo {
  const path = nebulaRoot()
  return { path: tilde(path), default: tilde(defaultNebula()), exists: existsSync(path) }
}

/**
 * A folder name in plain kebab-case: lowercase letters, digits and dashes, 40 characters at most.
 * The lens names it from the prompt already (lens/src/core/nebula.ts); this keeps any name safe.
 */
export function kebab(name: string): string {
  const k = String(name ?? '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
    .replace(/-+$/, '')
  return k || 'session'
}

/** `name`, or `name-2`, `name-3`… : the first that isn't in `dir` yet (compared as macOS does, ignoring case). */
export function uniqueIn(dir: string, name: string): string {
  let have = new Set<string>()
  try {
    have = new Set(readdirSync(dir).map((n) => n.toLowerCase()))
  } catch {}
  if (!have.has(name.toLowerCase())) return name
  for (let i = 2; ; i++) if (!have.has(`${name}-${i}`.toLowerCase())) return `${name}-${i}`
}

/**
 * Moves Nebula. It must be a folder inside the home folder (not the home folder itself, nor in
 * Library or a hidden folder): the phone chooses where sessions are made, and that's as far as it
 * reaches. "~/…" is the home folder. Made later, when the first session starts there.
 */
export function setNebula(raw: unknown): NebulaInfo {
  const s = String(raw ?? '').trim()
  if (!s) throw fail(400, 'bad_path', 'Give Nebula a folder.')
  const abs = resolve(s === '~' ? HOME : s.startsWith('~/') ? join(HOME, s.slice(2)) : s)
  const rel = relative(HOME, abs)
  if (!isAbsolute(s.replace(/^~/, HOME)) || !rel || rel.startsWith('..') || isAbsolute(rel)) throw fail(400, 'bad_path', 'Nebula has to be a folder inside your home folder.')
  const top = rel.split('/')[0]!
  if (top === 'Library' || rel.split('/').some((p) => p.startsWith('.'))) throw fail(400, 'bad_path', 'Pick a folder outside Library and hidden folders.')
  if (existsSync(abs) && !statSync(abs).isDirectory()) throw fail(400, 'bad_path', 'That’s a file, not a folder.')
  settings.nebula = abs === defaultNebula() ? null : abs
  saveSettings()
  log('nebula moved', abs)
  return nebulaInfo()
}

/** A new project in Nebula: `<nebula>/<name>` made unique, made (Nebula too if it isn't yet) and `git init`ed. */
export function createNebulaProject(name: string): Project {
  const root = nebulaRoot()
  mkdirSync(root, { recursive: true })
  const clean = uniqueIn(root, kebab(name))
  const path = join(root, clean)
  mkdirSync(path)
  try {
    execFileSync('git', ['init', '-q'], { cwd: path, timeout: 10000 })
  } catch {}
  log('nebula project created', path)
  broadcast({ t: 'projects', projects: listProjects() })
  return { path, name: clean, lastActivityAt: Date.now(), sessionCount: 0 }
}

/** `GET /v1/nebula` and `POST /v1/nebula {path}`. */
export async function nebulaApi(method: string, body: () => Promise<any>): Promise<{ nebula: NebulaInfo }> {
  if (method === 'GET') return { nebula: nebulaInfo() }
  if (method === 'POST') return { nebula: setNebula((await body())?.path) }
  throw fail(405, 'method', 'Use GET or POST.')
}
