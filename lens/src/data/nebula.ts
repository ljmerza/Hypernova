// Nebula on the Mac: the folder new sessions from the glasses are born in, one subfolder each
// (DAEMON.md, 10). The phone shows it and can move it; the Mac keeps it and makes the folders.
import { ApiError, type Api } from './api.ts';

export interface NebulaInfo {
  /** Where it is on the Mac, "~/…" when inside the home folder. */
  path: string;
  /** Where it is when nobody has moved it. */
  default: string;
  /** It has been made (it is, once the first session started there). */
  exists: boolean;
}

/** Nebula as the Mac has it; null from a Mac whose Hypernova predates it. */
export async function getNebula(api: Api): Promise<NebulaInfo | null> {
  try {
    return (await api.get<{ nebula: NebulaInfo }>('/v1/nebula')).nebula;
  } catch (err) {
    if (err instanceof ApiError && err.problem === 'missing') return null;
    throw err;
  }
}

/** Move Nebula (a folder inside the Mac's home folder; "~/…" works). The Mac answers with where it is now. */
export async function setNebula(api: Api, path: string): Promise<NebulaInfo> {
  return (await api.post<{ nebula: NebulaInfo }>('/v1/nebula', { path })).nebula;
}
