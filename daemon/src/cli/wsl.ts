// WSL on a Windows PC, as the `hypernova` command tells it (docs/INSTALL.md, "Windows
// (experimental)"): Hypernova runs there as on any Linux, but WSL starts and stops this Linux with
// Windows' terminals, runs systemd only when /etc/wsl.conf says so, and by default keeps it behind
// a NAT of its own where no phone can reach it. What doctor, pair, lan and setup add about that.
// Experimental: written from Microsoft's WSL documentation and checked with stand-ins, never yet on a
// real Windows PC; every Windows-side setting below names the page it comes from.
import { wslBehindNat } from '../platform.ts'
import type { Cli, WslHere } from './context.ts'
import type { Check } from './doctor.ts'

/** Microsoft's pages for each Windows-side setting the commands mention. */
export const WSL_DOCS = {
  systemd: 'https://learn.microsoft.com/windows/wsl/systemd',
  config: 'https://learn.microsoft.com/windows/wsl/wsl-config',
  networking: 'https://learn.microsoft.com/windows/wsl/networking',
}

/** The guide's section, for the longer story. */
export const WSL_GUIDE = 'docs/INSTALL.md, “Windows (experimental)”'

/**
 * WSL's networking mode, as `wslinfo --networking-mode` says it (WSL 2.0.4 and later); null when it
 * can't (an older WSL, a container on Docker Desktop). `HYPERNOVA_WSL_NETWORKING` stands in for it.
 */
export async function wslNetworkingOf(cli: Pick<Cli, 'env' | 'sys' | 'wsl'>): Promise<string | null> {
  if (!cli.wsl) return null
  const env = cli.env.HYPERNOVA_WSL_NETWORKING?.trim().toLowerCase()
  if (env) return /^[a-z]+$/.test(env) ? env : null
  if (cli.wsl.container || cli.wsl.version === 1) return null
  const r = await cli.sys.run('wslinfo', ['--networking-mode'], { timeout: 3000 })
  const v = r.code === 0 ? r.out.trim().toLowerCase() : ''
  return /^[a-z]+$/.test(v) ? v : null
}

/** Behind WSL's NAT (platform.ts's wslBehindNat), or a container on WSL's kernel: no phone reaches it on the Wi-Fi. */
export const behindNat = (w: WslHere, networking: string | null): boolean => w.container || wslBehindNat(w, networking)

const lanPort = (cli: Pick<Cli, 'env'>) => Number(cli.env.HYPERNOVA_LAN_PORT) || 47323
const distro = (w: WslHere) => w.distro ?? 'Ubuntu'

/** The lines that turn systemd on for this distribution (Microsoft's page, "How to enable systemd"). */
export const systemdFix = (w: WslHere): string =>
  `Turn it on: add these two lines to /etc/wsl.conf (sudo nano /etc/wsl.conf)\n  [boot]\n  systemd=true\nthen in PowerShell on Windows: wsl --shutdown, open ${distro(w)} again and run hypernova setup.\n(${WSL_DOCS.systemd})`

/** The Hyper-V firewall rule mirrored networking needs for the local route (Microsoft's networking page). */
export const firewallFix = (cli: Pick<Cli, 'env'>): string =>
  `In PowerShell as administrator, once:\n  New-NetFirewallHyperVRule -Name Hypernova -DisplayName "Hypernova local route" -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts ${lanPort(cli)}\n(${WSL_DOCS.networking})`

/** What WSL is and isn't doing for Hypernova: right after doctor's "Linux on …" line. */
export function wslMachineChecks(w: WslHere): Check[] {
  if (w.container) return [{ name: 'wsl', mark: 'info', text: 'Docker Desktop on Windows (WSL 2’s kernel): experimental, not yet tried on a real Windows PC.' }]
  if (w.version === 1) return [{ name: 'wsl', mark: 'warn', text: 'WSL 1: Hypernova on Windows is meant for WSL 2 (systemd, a real Linux kernel).', fix: `In PowerShell on Windows: wsl --set-version ${distro(w)} 2   (${WSL_GUIDE})` }]
  const out: Check[] = [{ name: 'wsl', mark: 'info', text: `WSL 2 on Windows: experimental, not yet tried on a real Windows PC (${WSL_GUIDE}).` }]
  if (w.systemd) out.push({ name: 'wsl-systemd', mark: 'ok', text: 'systemd runs this distribution: the service starts with it' })
  else out.push({ name: 'wsl-systemd', mark: 'warn', text: 'WSL doesn’t run systemd here, so there’s no service: Hypernova stops with the terminal it runs in.', fix: systemdFix(w) })
  out.push({
    name: 'wsl-awake',
    mark: 'info',
    text: 'WSL stops this Linux soon after its last Windows terminal closes (a systemd service doesn’t keep it running), and starts it again only when something opens it.',
    fix: `To keep it running, in %UserProfile%\\.wslconfig on Windows:\n  [general]\n  instanceIdleTimeout=-1\nthen wsl --shutdown in PowerShell. After Windows restarts, open ${distro(w)} once (${WSL_GUIDE}).\n(${WSL_DOCS.config})`,
  })
  return out
}

/**
 * How a phone reaches this WSL: not at all behind the NAT (no address of it is on the Wi-Fi), or
 * on the Wi-Fi with mirrored networking, once Windows' Hyper-V firewall lets the local route's port in.
 */
export function wslNetworkCheck(cli: Pick<Cli, 'env'>, w: WslHere, networking: string | null): Check {
  if (!behindNat(w, networking)) {
    if (w.version === 1) return { name: 'wsl-network', mark: 'info', text: `WSL 1 shares Windows’ own network: a phone on the same Wi‑Fi can reach it if Windows Firewall lets port ${lanPort(cli)} in.` }
    if (networking !== 'mirrored') return { name: 'wsl-network', mark: 'info', text: `WSL’s ${networking} networking: this Linux has an address on the Wi‑Fi of its own, so a phone there can reach it directly.` }
    return { name: 'wsl-network', mark: 'info', text: `WSL’s mirrored networking: this Linux has Windows’ own addresses, so a phone on the same Wi‑Fi can reach it directly once Windows’ Hyper-V firewall lets port ${lanPort(cli)} in.`, fix: firewallFix(cli) }
  }
  const mode = w.container ? 'Docker Desktop’s network' : networking ? `WSL’s ${networking === 'nat' ? 'NAT' : networking} networking` : 'WSL’s networking (wslinfo didn’t say: taken as NAT, the default)'
  const text = `${mode}: a phone can’t reach this Linux on the Wi‑Fi.`
  const fix: string[] = []
  if (!w.container) fix.push(`On Windows 11 22H2 and later, mirrored networking: networkingMode=mirrored under [wsl2] in %UserProfile%\\.wslconfig, then wsl --shutdown (${WSL_GUIDE}).`)
  if (!w.container && !networking) fix.push('wsl --update in PowerShell brings wslinfo, which says the mode.')
  return { name: 'wsl-network', mark: 'warn', text, ...(fix.length ? { fix: fix.join('\n') } : {}) }
}

/** pair's word about WSL, before the code: how the phone gets in, or why it can't. */
export function wslPairNote(cli: Pick<Cli, 'env'>, w: WslHere, networking: string | null): { mark: 'info' | 'warn'; text: string; fix?: string } {
  if (!behindNat(w, networking)) {
    if (networking === 'mirrored') return { mark: 'info', text: `WSL’s mirrored networking: a phone on the same Wi‑Fi can pair directly once Windows’ Hyper-V firewall lets port ${lanPort(cli)} in (hypernova doctor has the command).` }
    return { mark: 'info', text: `${w.version === 1 ? 'WSL 1 shares Windows’ network' : `WSL’s ${networking} networking`}: a phone on the same Wi‑Fi can pair directly if Windows lets port ${lanPort(cli)} in.` }
  }
  const where = w.container ? 'Docker Desktop on Windows' : 'WSL on Windows'
  return {
    mark: 'warn',
    text: `${where}: ${w.container ? 'the container’s network' : 'WSL’s NAT network'} keeps this PC off the phone’s Wi‑Fi, so this code has no way in.`,
    ...(w.container ? {} : { fix: `Mirrored networking on Windows 11 (${WSL_GUIDE})` }),
  }
}
