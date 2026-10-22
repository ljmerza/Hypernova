# Hypernova on Linux

Hypernova's daemon runs on any always-on Linux machine: a VPS, a home server, a PC, or a Docker
container. There is no menu bar app there; everything is the `hypernova` command, over SSH. Claude
Code, Codex or both run on the server beside it, and the phone app pairs with it the same way it
pairs with a Mac (docs/DECISIONS.md, "Direction: open source, self-hosted, Tailscale inside").
[INSTALL.md](INSTALL.md) walks a first install through; this page has every command and detail.

Supported: Ubuntu and Debian (other distributions work with their packages installed by hand), on
x86_64 and arm64.

**A Windows PC** runs the same service inside WSL 2 (Ubuntu) or in a Docker Desktop container:
**experimental**, documented from Microsoft's and Docker's guides but not yet tried on a real Windows
PC. The steps, what works and what doesn't, are in
[INSTALL.md, "5. Windows (experimental)"](INSTALL.md#5-windows-experimental-wsl2-or-docker-desktop);
under WSL, `hypernova doctor`, `pair`, `lan` and `setup` say what differs there (systemd, keeping
WSL running, the phone over Tailscale behind WSL's NAT).

## Install

Run it as the user who will use Claude Code or Codex, not as root. The script asks `sudo` for system
packages only.

```sh
# The release's package (https://github.com/prudnik-web/Hypernova/releases/latest, checksums in
# SHA256SUMS.txt; the guide is at https://hypernova.nikbraun.tech), on the server:
sha256sum -c --ignore-missing SHA256SUMS.txt
tmp=$(mktemp -d) && tar -xzf Hypernova-linux.tar.gz -C "$tmp" && "$tmp"/hypernova-*/scripts/install-linux.sh

# From a checkout of this repository, on the server:
scripts/install-linux.sh

# A package of your own: on your computer,
scripts/pack-linux.sh            # writes dist/linux/hypernova-<version>.tar.gz
scp dist/linux/hypernova-*.tar.gz you@server:
# then on the server, from an unpacked copy's script or a checkout's:
scripts/install-linux.sh --from hypernova-<version>.tar.gz
```

Options: `--no-claude` (don't install Claude Code: Codex alone), `--yes` (no questions),
`--no-integration` (leave the hooks and the shell function for the first start: a container image).

What it does:

1. **System packages** it lacks, with `apt-get`: curl, tar, openssl, git, build tools (`make`,
   `g++`, `python3`: node-pty, which runs the shared terminals, has no prebuilt Linux binary and is
   built on the server), and `ps` and `lsof` for Telescope.
2. **Node.js 24** from nodejs.org, checked against nodejs.org's `SHASUMS256.txt`, unpacked into
   Hypernova's own folder (`~/.local/share/hypernova/node`). A system Node 24 or newer is used
   instead when there is one. Nothing outside Hypernova's folder is touched.
3. **`hypernova setup`**, which does the rest (below).

## Try it in Docker

The quickest way to look, on any machine with Docker:

```sh
docker build -t hypernova .
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped \
  --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
docker exec -it hypernova claude            # sign in to Claude once
docker exec -it hypernova hypernova pair    # the pairing code
docker exec -it hypernova hypernova doctor
```

The image is Ubuntu 24.04 with the install script already run in it, as a normal user. The daemon
is the container's command (`hypernova run`); there is no systemd in a container, so no service.
Nothing is published to the network. On a Linux host, `--network host` lets a phone on the same
network pair over the local route, and lets the host's own Tailscale reach it.
`--build-arg CLAUDE=no` leaves Claude Code out. Codex in a container: its npm package needs a Node.js
on the PATH, which the image doesn't have, so copy Codex's own Linux build in
(`docker cp codex hypernova:/home/hypernova/.local/bin/codex`), then `docker exec -it hypernova codex
login --device-auth` and `docker exec -it hypernova hypernova codex on`.

The program (the daemon, its Node.js, the `hypernova` command) is in the image under
`/opt/hypernova`; only state (paired phones, settings, keys, Claude's login) is in the volume. The
image holds no identity: each volume makes its own computer id, admin token and hook secret at its
first start, so two containers from one image are two computers. `--restart unless-stopped` brings
the daemon back after a host reboot or a crash. A container is no phone's network, so the local
route is off there; `hypernova lan on` turns it on (with `--network host` on a Linux host).

**Updating the container:** build the image again, remove the container, run it again with the same
volume. The new image's daemon runs, with your phones and settings:

```sh
docker build -t hypernova . && docker rm -f hypernova
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped -v hypernova-home:/home/hypernova hypernova
```

## The commands

| Command | What it does |
|---|---|
| `hypernova setup` | Checks and installs what's missing: Node.js 24, the daemon (with node-pty built), the `hypernova` command, Claude Code (Anthropic's installer, `curl -fsSL https://claude.ai/install.sh \| bash`, when it's absent), the systemd service with lingering, the hooks and status line in Claude Code's settings, the `claude` shell function, and `tailscale serve` for the phone API when Tailscale is signed in. Safe to run again. Then it asks you to run `claude` once to sign in: Hypernova never signs in, out or reads Claude Code's login. It also says what it found of Codex (see `hypernova codex`). `--from <dir>`, `--no-claude`, `--no-service`. |
| `hypernova pair` | The pairing QR code, in the terminal, and the link under it: the same `https://hypernova.observer/pair#…` link the Mac shows (the phone's camera opens the app; the details are after the `#` and never reach that site), and where to get the app if the phone has none yet. With the built-in Tailscale node it first shows the Tailscale sign-in code when the node needs one (the app then signs in to the same account); with no tailnet the link carries the local route's addresses, for a phone on the same network. Also prints the code and when it expires. Never blocks on Tailscale: with no terminal, or with `--no-wait`, it shows the sign-in but doesn't wait for it; in a terminal, Enter skips the wait; `--local` skips Tailscale altogether (a home server for the local network). Waits for the phone and names it (`--no-wait`, `--no-qr`). If the window is narrower than the QR code it says so (widen it, or use the link). |
| `hypernova tailscale` | The built-in Tailscale node: who it is signed in as and where it serves the phone API, or its sign-in code. `hypernova tailscale logout` signs it out for another account. `hypernova tailscale off` turns the built-in node off (use the Tailscale app on this server instead); `on` turns it back on; `control <url>` points it at a Headscale server (`none` returns to tailscale.com). When the Tailscale app here is running and signed in, the daemon turns its `tailscale serve` on for the phone API and starts its own node only if that fails. |
| `hypernova status` | What runs, how phones reach it, Claude Code and whether it's signed in, Codex (found, on or off, signed in), the integration, paired devices, sessions (`--json`). |
| `hypernova codex [on\|off]` | OpenAI's Codex for Hypernova: what's found here (path, version against the minimum, 0.163.0), or turning it on or off, as the Mac's Setup switch does. On, the daemon connects to Codex's own app server and the `codex` function joins `claude` in the shell's block; the phone can start Codex sessions. It never installs or signs in Codex: `sudo npm i -g @openai/codex` (or Codex's own Linux build in `~/.local/bin`), then `codex login --device-auth` (a code to enter on any device). |
| `hypernova doctor` | Every piece checked, each problem with the command that fixes it (`--json`). Exit 1 when something stops Hypernova working. With Codex alone, a missing Claude Code is only noted; with neither agent, it fails. |
| `hypernova logs` | The daemon's log: the last 100 lines (`-n <lines>`), `-f` to follow, `--terminals` for the shared terminals' log. Both logs roll at 5 MB to `.1`. |
| `hypernova lan on\|off` | The phone's direct route on the same network (below). |
| `hypernova uninstall` | Removes the integration, Tailscale's route to it, the service, the command, the daemon and its Node. Paired phones and settings stay for a reinstall unless `--purge`. Claude Code, Codex and their logins stay, and so does lingering (other services of yours may use it; it says how to turn it off). `--purge` also signs the built-in Tailscale node out of your tailnet and gives up the private name; without it the node stays in the tailnet (and is picked up again by a reinstall). With no terminal it refuses unless you pass `--yes`. |
| `hypernova run` | The daemon in the foreground, its log on the terminal too: what a container runs, and handy for debugging. |

`hn ls` and `hn attach <id>` work as on the Mac (shared terminals, below).

## Where things live

| What | Where |
|---|---|
| State: identity, admin token, paired devices, settings, shared terminals' files | `~/.local/share/hypernova` (`$XDG_DATA_HOME/hypernova`) |
| The daemon itself, and the font Telescope draws the terminal with | `~/.local/share/hypernova/app` |
| Node.js, when Hypernova downloaded it | `~/.local/share/hypernova/node`, named in `~/.local/share/hypernova/node-path` |
| The log | `~/.local/state/hypernova/logs/daemon.log` (`$XDG_STATE_HOME/hypernova/logs`) |
| The command | `~/.local/bin/hypernova` (on PATH in Ubuntu's and Debian's default `~/.profile`; Claude Code's installer puts `claude` there too) |
| The service | `~/.config/systemd/user/hypernova.service` |

`HYPERNOVA_SUPPORT` and `HYPERNOVA_LOGS` still win over these, as on the Mac (a test daemon's
folders), and so do the port variables `HYPERNOVA_API_PORT`, `HYPERNOVA_LOCAL_PORT` and
`HYPERNOVA_LAN_PORT`; setup carries them into the service and the command.

## The service

A systemd **user** service, so the daemon and Claude Code run as you, never as root:

- **Lingering** (`loginctl enable-linger`) starts your user's systemd at boot, so the daemon runs
  with nobody logged in, as a VPS needs. Setup turns it on (with `sudo -n` when loginctl alone
  isn't allowed); it also starts your user manager when the shell has no login session of its own
  (`su`, `sudo -u`), which `systemctl --user` needs.
- `Restart=on-failure` after 5 seconds.
- `KillMode=process`: stopping or restarting the service ends the daemon alone. The shared
  terminals' holders, each holding one of your Claude sessions, keep running through an update or
  a crash, as they do on a Mac.
- `systemctl --user status|restart|stop hypernova` work as usual; `journalctl --user -u hypernova`
  has only what escapes the daemon's own log.

## Security

The same as on a Mac (docs/PROTOCOL.md, "Transport and auth", "Local network (direct HTTPS)"):

- The phone API (47321) and the local API (47322) listen on **127.0.0.1 only**. Phones reach the
  phone API through `tailscale serve` over your tailnet; nothing listens on a public port.
  `hypernova doctor` reads the kernel's socket table and fails if anything listens on those ports
  beyond loopback (a port forward, another program).
- The **local route** (47323, HTTPS) is for a phone on the same network: connections from
  anything but private, link-local, CGNAT and ULA addresses are dropped before TLS, and every
  route but health and pairing needs the phone's device certificate. On a Linux machine with a
  **public IPv4 address of its own** (a VPS) it is **off by default**: the phone is never on
  such a server's network, and the port would face the internet. `hypernova lan on` turns it on
  anyway; `hypernova lan off` turns it off on a PC. While it's on, the daemon keeps a private name under
  hypernova.observer with a real certificate for the download page in a phone's browser, its
  Wi-Fi address in DNS only while a code is shown (docs/PROTOCOL.md, "A name on the local
  network"); `hypernova pair` says so once, `hypernova name off` does without it, and `hypernova
  uninstall` gives it up.
- The admin token (`~/.local/share/hypernova/admin-token`, 0600) is for the local API and the
  `hypernova` command only.
- Running as root works but `doctor` warns: Claude Code's sessions would have the whole server.
  Claude Code also refuses to skip permissions as root, so on a root server phone sessions run
  without the Bypass mode (the daemon leaves the flag out and refuses a new session started in
  bypass mode, `bypass_root`) rather than exiting at once. Make a user for it: `adduser claude`,
  `loginctl enable-linger claude`, `su - claude`, then install there.
- The local route is **off by default** in a container and on a cloud VM (AWS, Google, Azure,
  DigitalOcean, Hetzner and others, which sit behind a NAT) as well as on a machine with a public
  address; bridge interfaces (docker0, br-*, virbr0) are never offered as a phone's address.
  Behind WSL 2's NAT on a Windows PC (any networking mode but mirrored, as `wslinfo
  --networking-mode` says) it is off too, and even when turned on its addresses are left out of
  pairing links: none of them is one a phone can reach.

**Tailscale is built in** (docs/PROTOCOL.md, "Tailscale built in"): the daemon runs its own node
(`daemon/bin/hypernova-tsnet-linux-*`, in the package when it was built with Go), named
`hypernova-<host>` on your tailnet, and serves the phone API over it. `hypernova pair` shows the
sign-in code the first time; nothing else to install. Your tailnet's HTTPS certificates give it a
trusted name; without them (Headscale), the phone pins the server's own certificate from the
pairing code. A self-hoster points it at Headscale with `tailscaleControlUrl` in the daemon's
settings (`POST /local/settings`, https, or http on a private address).

**The Tailscale app** instead, if you prefer (the built-in node then stays off when the app
serves the phone API): install it, sign in,
and let your user change `serve`, once:

```sh
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
sudo tailscale set --operator=$USER
hypernova pair        # turns on tailscale serve for the phone API, then shows the code
```

Your tailnet needs MagicDNS and HTTPS certificates on (https://login.tailscale.com/admin/dns).

## What a Linux server doesn't have

The Mac's own features are switched off, and say why. The snapshot carries
`mac.platform: "linux"` and `mac.unavailable`, each feature with its sentence, so a client can
hide it and show the reason; asked anyway, the API answers 501 `unavailable` with the same
sentence (docs/PROTOCOL.md, "Types", "Mac-only features").

| Feature | On Linux |
|---|---|
| Battery | Not shown (`mac.battery` is null). |
| Stay awake | Off; a server stays on by itself. |
| The Mac's voice (`say`) | None; Soniox reads aloud on the phone. |
| Opening a terminal window ("Open on Mac", signing in "on the Mac") | None: no screen. `hn attach` over SSH. |
| Telescope: app windows, the iOS Simulator | None. |

What keeps working on Linux:

- **Shared terminals.** Every phone session runs in a holder with its own PTY (node-pty, built
  at install). The `claude` shell function goes into **`~/.bashrc`** for bash (the default shell
  there) or `~/.zshrc` for zsh, the same marked block as on the Mac (`# >>> hypernova shared
  terminals` … `# <<< hypernova shared terminals`), so `claude` typed over SSH is a session the
  phone can see and type into; `hn ls`, `hn attach`. Another login shell leaves `claude` plain.
- **Telescope:** the session's **terminal** (drawn in TypeScript with JetBrains Mono, no AppKit),
  **web pages** (headless Chrome or Chromium when installed: `google-chrome-stable`, `chromium`,
  `chromium-browser`, the snap; run as root it starts without its sandbox), the **Android
  emulator or a phone** (`adb` from `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, `~/Android/Sdk`, or
  Debian's `adb` package), and **pictures** the session made, PNG for now (a JPEG, SVG or PDF says
  it needs a Mac).
- **Codex**, as on a Mac: found in the same places (`~/.local/bin`, `/usr/local/bin`, `/usr/bin`,
  npm's, Volta's, Bun's, pnpm's and nvm's folders, then the login shell), 0.163.0 or later, used only
  while it's on (`hypernova codex on`, or the phone), its `codex` function in `~/.bashrc` beside
  `claude`.
- Everything else the daemon does: sessions, approvals, usage, attachments, automations, agents,
  statistics, the local route, Soniox speech.

## Updating

Run the install script again from the new package (the release's `Hypernova-linux.tar.gz`, as at
the first install), checkout or tarball (or `hypernova setup --from <dir>`).
The new daemon is prepared beside the old one and swapped in only once its terminal module works,
then the service restarts; open sessions keep running. (Docker: see "Updating the container" above.)

## Troubleshooting

`hypernova doctor` first: each problem comes with its fix. Then `hypernova logs`. Common ones:

- *"Building the terminal module needs make, g++, python3"*: `sudo apt-get install -y
  build-essential python3`, then `hypernova setup`.
- *"systemd's user manager isn't running"*: `sudo loginctl enable-linger $USER`, or log in over
  SSH, then `hypernova setup`.
- *"Tailscale lets only root or its operator change serve"*: `sudo tailscale set --operator=$USER`,
  then `hypernova pair`.
- *`hypernova: command not found`* right after the install: log in again (Ubuntu adds
  `~/.local/bin` to PATH at login), or run `~/.local/bin/hypernova`.

## Tests

- Unit: `cd daemon && npm test` includes `test/platform.test.ts` (where things live, what exists
  on each system), `test/linux-cli.test.ts` (the command's pieces and commands against a stand-in
  daemon and system) and `test/linux-telescope.test.ts` (PNG and the terminal drawn without AppKit).
- End to end, in Docker: `scripts/test-linux-docker.sh` builds the image, starts the daemon, and
  checks `status`, `doctor`, `pair`, the API's health, pairing a stand-in phone, the Mac-only
  features answering 501, the LAN listener asking for device certificates, a shared terminal
  started by the `claude` function in bash (running a harmless stand-in, never the real Claude
  Code), `logs` and `uninstall`. `--no-claude` builds without Claude Code; nothing signs in.
- The service, in Docker: `scripts/test-linux-systemd.sh` boots Ubuntu 24.04 with systemd (a
  privileged container, publishing nothing), installs from a tarball as a user with sudo and no
  login session, and checks lingering, the unit, `status` and `doctor`, systemd starting the daemon
  again after `kill -9`, a session the daemon started (a stand-in Claude Code) kept through a service
  restart and an update and found again, Telescope drawing its terminal, the daemon back after a
  reboot with nobody logged in, and `uninstall`.
