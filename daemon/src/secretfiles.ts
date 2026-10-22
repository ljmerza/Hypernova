import { basename, join, sep } from 'node:path'

// Files that hold credentials stay on the Mac: the phone can see them in a folder listing, but never
// read, share or download them, even inside a project (a project's .env is the usual one).
const SECRET_NAME = /^(?:\.env(?:\..+)?|\.envrc|\.npmrc|\.pypirc|\.netrc|\.git-credentials|\.pgpass|\.my\.cnf|\.s3cfg|\.boto|\.dockercfg|\.(?:zsh|bash|sh|python|node_repl|psql|mysql|sqlite|irb|lesshst)_?history|credentials(?:\.json)?|id_(?:rsa|dsa|ecdsa|ed25519)(?:\.pub)?|.+\.(?:pem|key|p12|pfx|jks|keystore|kdbx|ovpn|ppk))$/i
// Templates people commit on purpose are fine to read.
const TEMPLATE = /^\.env\.(?:example|sample|template|dist|defaults)$/i
const SECRET_DIR = new Set(['.ssh', '.aws', '.gnupg', '.kube', '.docker', '.config/gcloud'])

export function isSecretFile(full: string): boolean {
  const name = basename(full)
  if (TEMPLATE.test(name)) return false
  if (SECRET_NAME.test(name)) return true
  // A Mac's disk ignores case: ~/.SSH is ~/.ssh.
  const parts = full.toLowerCase().split(sep)
  return parts.some((p, i) => SECRET_DIR.has(p) || SECRET_DIR.has(`${p}/${parts[i + 1] ?? ''}`))
}

/**
 * The home folder's key places (docs/PROTOCOL.md, "Files"): where credentials, keychains, mail,
 * messages, browsers' profiles and other apps' private data live. Never browsed from the phone,
 * whatever "Browse outside projects" says: a listing of the folder that holds one shows it as a
 * locked row, and nothing inside it is listed, read or downloaded. Relative to the home folder.
 * The daemon's own state and Claude Code's are added by the caller (they can be moved).
 */
export const KEY_PLACES: readonly string[] = [
  // Keys and tokens of command-line tools.
  '.ssh', '.gnupg', '.aws', '.azure', '.config/gcloud', '.config/gh', '.kube', '.docker', '.password-store',
  '.config/op', '.terraform.d', '.vault-token', '.oci', '.fly', '.config/doctl', '.wrangler',
  '.cargo/credentials', '.cargo/credentials.toml', '.gem/credentials', '.claude.json',
  // Other coding agents' and tools' sign-ins.
  '.codex', '.gemini', '.config/github-copilot', '.config/configstore', '.config/rclone', '.config/hub',
  // macOS: keychains, cookies, mail, messages, Safari, accounts, and apps' own data (browsers'
  // profiles, password managers, chat apps' tokens and iPhone backups are in Application Support).
  'Library/Keychains', 'Library/Cookies', 'Library/Mail', 'Library/Messages', 'Library/Safari',
  'Library/Accounts', 'Library/Calendars', 'Library/IdentityServices', 'Library/HTTPStorages', 'Library/WebKit',
  'Library/Containers', 'Library/Group Containers', 'Library/Application Support',
  // Linux: browsers' profiles, keyrings, mail.
  '.mozilla', '.thunderbird', '.config/google-chrome', '.config/chromium', '.config/BraveSoftware',
  '.config/microsoft-edge', '.config/vivaldi', '.config/opera', '.local/share/keyrings', '.config/Bitwarden',
]

/** [KEY_PLACES] as full paths under [home]. */
export const keyPlacesIn = (home: string): string[] => KEY_PLACES.map((p) => join(home, ...p.split('/')))
