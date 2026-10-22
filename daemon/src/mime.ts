// What a file is, by its name (and, for one without an extension, by its first bytes): one table
// for the phone's file viewer and downloads (files.ts), previews and attachments.
import { extname } from 'node:path'

const TEXT = 'text/plain; charset=utf-8'
const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8', '.htm': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.xml': 'application/xml', '.csv': 'text/csv; charset=utf-8', '.txt': TEXT, '.md': 'text/markdown; charset=utf-8', '.wasm': 'application/wasm',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.ico': 'image/x-icon',
  '.heic': 'image/heic', '.heif': 'image/heif', '.avif': 'image/avif', '.bmp': 'image/bmp', '.tif': 'image/tiff', '.tiff': 'image/tiff',
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.mov': 'video/quicktime', '.webm': 'video/webm',
  '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.aac': 'audio/aac', '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.flac': 'audio/flac', '.opus': 'audio/ogg',
  '.pdf': 'application/pdf', '.zip': 'application/zip', '.gz': 'application/gzip', '.tgz': 'application/gzip', '.tar': 'application/x-tar', '.7z': 'application/x-7z-compressed',
  '.apk': 'application/vnd.android.package-archive', '.dmg': 'application/x-apple-diskimage',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '.doc': 'application/msword', '.xls': 'application/vnd.ms-excel',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.otf': 'font/otf',
}
/** Source code and plain-text formats, shown as text. */
const TEXT_EXT = new Set([
  '.ts', '.tsx', '.jsx', '.cjs', '.kt', '.kts', '.swift', '.py', '.rb', '.go', '.rs', '.java', '.c', '.h', '.cpp', '.hpp', '.m', '.mm', '.cs', '.php', '.lua', '.pl',
  '.sh', '.zsh', '.bash', '.fish', '.yml', '.yaml', '.toml', '.ini', '.cfg', '.conf', '.properties', '.gradle', '.lock', '.env', '.sql', '.vue', '.svelte', '.scss', '.less',
  '.log', '.diff', '.patch', '.tex', '.rst', '.org', '.graphql', '.proto', '.dockerfile', '.gitignore', '.jsonl', '.ndjson', '.tsv',
])

/**
 * The type a file's name says, or null when it says none. A name without an extension
 * (`Makefile`, `LICENSE`, `.gitignore`, a compiled binary) says none: [sniff] tells.
 */
export function mimeByName(p: string): string | null {
  const e = extname(p).toLowerCase()
  if (!e) return null
  return MIME[e] ?? (TEXT_EXT.has(e) ? TEXT : null)
}

/** The type without its parameters ("text/plain"), as attachments name theirs. */
export const bareMime = (p: string): string | null => mimeByName(p)?.split(';')[0] ?? null

/** Whether [p] has no extension, so its first bytes decide what it is. */
export const needsSniff = (p: string) => !extname(p)

/** Text unless its first bytes hold a NUL (a binary, a database), for a name with no extension. */
export function sniff(head: Buffer): string {
  return head.includes(0) ? 'application/octet-stream' : TEXT
}
