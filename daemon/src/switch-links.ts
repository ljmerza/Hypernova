// Conversations that moved from one agent to the other (switch.ts): each switch starts a new
// session and links the two, kept in <support>/switches.json. A session shows where it came from
// (`switchedFrom`) and where it went on (`switchedTo`), so the phone draws them as one conversation.
import { join } from 'node:path'
import { SUPPORT, readJson, writeJson } from './util.ts'

type Links = Record<string, { from?: string; to?: string }>
const FILE = join(SUPPORT, 'switches.json')
let links: Links = readJson<Links>(FILE, {})
if (!links || typeof links !== 'object' || Array.isArray(links)) links = {}

export const linkOf = (id: string): { from: string | null; to: string | null } => ({ from: links[id]?.from ?? null, to: links[id]?.to ?? null })

/** Session [from] goes on in session [to]. */
export function addLink(from: string, to: string): void {
  links[from] = { ...links[from], to }
  links[to] = { ...links[to], from }
  writeJson(FILE, links)
}

/** The sessions a conversation went through before [id], nearest first, [max] at most. */
export function chainBefore(id: string, max = 10): string[] {
  const out: string[] = []
  for (let at = links[id]?.from; at && out.length < max && !out.includes(at); at = links[at]?.from) out.push(at)
  return out
}
