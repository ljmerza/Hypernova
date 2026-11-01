# Hypernova on Linux

Hypernova's daemon runs on any always-on Linux machine: a VPS, a home server, a PC, or a Docker
container. There is no menu bar app there; everything is the `hypernova` command, over SSH. Claude
Code, Codex or both run on the server beside it, and the phone app pairs with it the same way it
pairs with a Mac. The phone reaches the server on its own network (the local route, port 47323,
mutual TLS), or over your own VPN into that network (docs/DECISIONS.md, "Tailscale is removed").
[INSTALL.md](INSTALL.md) walks a first install through; this page has every command and detail.

Supported: Ubuntu and Debian (other distributions work with their packages installed by hand), on
x86_64 and arm64.

**A Windows PC** runs the same service inside WSL 2 (Ubuntu) or in a Docker Desktop container:
**experimental**, documented from Microsoft's and Docker's guides but not yet tried on a real Windows
PC. The steps, what works and what doesn't, are in
[INSTALL.md, "4. Windows (experimental)"](INSTALL.md#4-windows-experimental-wsl2-or-docker-desktop);
under WSL, `hypernova doctor`, `pair`, `lan` and `setup` say what differs there (systemd, keeping
WSL running, no way in for the phone behind WSL's NAT unless its networking is mirrored).

## Install

Run it as the user who will use Claude Code or Codex, not as root. The script asks `sudo` for system
packages only.

```sh
# In one line: the latest release's server package, downloaded and installed (scripts/get.sh;
# HYPERNOVA_TAG=<tag> for another release, `| bash -s -- --yes` for no questions):
curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash

# The same by hand. A release's server package (https://github.com/ljmerza/Hypernova/releases/latest:
# hypernova-server-<tag>-linux.tar.gz, one package for x86_64 and arm64), on the server:
gh release download -R ljmerza/Hypernova -p 'hypernova-server-*-linux.tar.gz'   # or save it from the page
tmp=$(mktemp -d) && tar -xzf hypernova-server-*-linux.tar.gz -C "$tmp" && "$tmp"/hypernova-*/scripts/install-linux.sh

# From a checkout of this repository, on the server:
scripts/install-linux.sh

# A package of your own: on your computer,
scripts/pack-linux.sh            # writes dist/linux/hypernova-<version>.tar.gz (a release renames it)
scp dist/linux/hypernova-*.tar.gz you@server:
# then on the server, from an unpacked copy's script or a checkout's:
scripts/install-linux.sh --from hypernova-<version>.tar.gz
```

Options: `--no-claude` (don't install Claude Code: Codex alone), `--yes` (no questions),
`--no-integration` (leave the hooks and the shell function for the first start: a container image).

What it does:

1. **System packages** it lacks, with `apt-get`: curl, tar, openssl, git, build tools (`make`,
   `g++`, `python3`: node-pty, which runs the shared terminals, has no prebuilt Linux binary and is
   built on the server), and `ps` and `lsof`.
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
network (or on a VPN into it) pair over the local route, the phone's only way in.
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
| `hypernova setup` | Checks and installs what's missing: Node.js 24, the daemon (with node-pty built), the `hypernova` command, Claude Code (Anthropic's installer, `curl -fsSL https://claude.ai/install.sh \| bash`, when it's absent), the systemd service with lingering, the hooks and status line in Claude Code's settings, and the `claude` shell function. It turns on sessions in `claude agents` (`"claudeAgents": true` in `settings.json`) unless that already says either way; the app's Settings → Agents & providers → Claude Code turns it off. Safe to run again. Then it asks you to run `claude` once to sign in: Hypernova never signs in, out or reads Claude Code's login. It also says what it found of Codex (see `hypernova codex`). `--from <dir>`, `--no-claude`, `--no-service`. |
| `hypernova pair` | The pairing QR code, in the terminal, and the link under it: this server's own pairing page on the local route, `https://<its address>:47323/pair?…` (the phone's camera opens it in the browser, which warns once about the server's own certificate, and the page opens the app; **Paste pairing link** in the app takes the same link), and where to get the app if the phone has none yet. The link carries the local route's addresses, for a phone on the same network or on a VPN into it; nothing outside your network is named. With the local route off it says so (`hypernova lan on`) and prints the app's own `hypernova://pair?…` link instead. Also prints the code and when it expires. Waits for the phone and names it (`--no-wait`, `--no-qr`; `--local` is still accepted and changes nothing). If the window is narrower than the QR code it says so (widen it, or use the link). |
| `hypernova status` | What runs, how phones reach it, Claude Code and whether it's signed in, Codex (found, on or off, signed in), the integration, paired devices, sessions (`--json`). |
| `hypernova codex [on\|off]` | OpenAI's Codex for Hypernova: what's found here (path, version against the minimum, 0.163.0), or turning it on or off, as the Mac's Setup switch does. On, the daemon connects to Codex's own app server and the `codex` function joins `claude` in the shell's block; the phone can start Codex sessions. It never installs or signs in Codex: `sudo npm i -g @openai/codex` (or Codex's own Linux build in `~/.local/bin`), then `codex login --device-auth` (a code to enter on any device). |
| `hypernova doctor` | Every piece checked, each problem with the command that fixes it (`--json`). Exit 1 when something stops Hypernova working. With Codex alone, a missing Claude Code is only noted; with neither agent, it fails. |
| `hypernova logs` | The daemon's log: the last 100 lines (`-n <lines>`), `-f` to follow, `--terminals` for the shared terminals' log. Both logs roll at 5 MB to `.1`. |
| `hypernova lan on\|off` | The phone's direct route on the same network (below). |
| `hypernova auto-update [on\|off]` | The server installing newer releases by itself (below, "Updating"), **on** unless turned off; the app's Settings has the same switch in the server's section (**Update this server automatically**). Without a word, how it stands: on or off, whether this install can (and why not), what it's doing, a try that failed, the last update it installed. |
| `hypernova uninstall` | Removes the integration, the service, the command, the daemon and its Node. Paired phones and settings stay for a reinstall unless `--purge`. Claude Code, Codex and their logins stay, and so does lingering (other services of yours may use it; it says how to turn it off). With no terminal it refuses unless you pass `--yes`. |
| `hypernova run` | The daemon in the foreground, its log on the terminal too: what a container runs, and handy for debugging. |

`hn ls` and `hn attach <id>` work as on the Mac (shared terminals, below).

## Where things live

| What | Where |
|---|---|
| State: identity, admin token, paired devices, settings, shared terminals' files | `~/.local/share/hypernova` (`$XDG_DATA_HOME/hypernova`) |
| The daemon itself, and JetBrains Mono beside it | `~/.local/share/hypernova/app` |
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
  same API through the local route below; nothing listens on a public port.
  `hypernova doctor` reads the kernel's socket table and fails if anything listens on those ports
  beyond loopback (a port forward, another program).
- The **local route** (47323, HTTPS) is for a phone on the same network: connections from
  anything but private, link-local, CGNAT and ULA addresses are dropped before TLS, and every
  route but health and pairing needs the phone's device certificate. On a Linux machine with a
  **public IPv4 address of its own** (a VPS) it is **off by default**: the phone is never on
  such a server's network, and the port would face the internet. `hypernova lan on` turns it on
  anyway; `hypernova lan off` turns it off on a PC. No name is registered or published anywhere: a
  phone's browser opens the server's pages at its address, with the server's own certificate.
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

**Away from home**, use your own VPN into the server's network (a WireGuard or UniFi VPN, say):
the phone then reaches the local route as if it were on the Wi-Fi. Hypernova carries no VPN or
Tailscale of its own any more (docs/DECISIONS.md, "Tailscale is removed"). A VPS has no home
network for the phone to join, so it needs `hypernova lan on` and a VPN that puts the phone on the
server's private network; never open port 47323 to the internet.

## What a Linux server doesn't have

The Mac's own features are switched off, and say why. The snapshot carries
`mac.platform: "linux"` and `mac.unavailable`, each feature with its sentence, so a client can
hide it and show the reason; asked anyway, the API answers 501 `unavailable` with the same
sentence (docs/PROTOCOL.md, "Types", "Mac-only features").

| Feature | On Linux |
|---|---|
| Battery | Not shown (`mac.battery` is null). |
| Stay awake | Off; a server stays on by itself. |
| The Mac's voice (`say`) | None; the phone reads aloud with its own voice. |
| Opening a terminal window ("Open on Mac", signing in "on the Mac") | None: no screen. `hn attach` over SSH. |

What keeps working on Linux:

- **Shared terminals.** Every phone session runs in a holder with its own PTY (node-pty, built
  at install). The `claude` shell function goes into **`~/.bashrc`** for bash (the default shell
  there) or `~/.zshrc` for zsh, the same marked block as on the Mac (`# >>> hypernova shared
  terminals` … `# <<< hypernova shared terminals`), so `claude` typed over SSH is a session the
  phone can see and type into; `hn ls`, `hn attach`. Another login shell leaves `claude` plain.
- **Codex**, as on a Mac: found in the same places (`~/.local/bin`, `/usr/local/bin`, `/usr/bin`,
  npm's, Volta's, Bun's, pnpm's and nvm's folders, then the login shell), 0.163.0 or later, used only
  while it's on (`hypernova codex on`, or the phone), its `codex` function in `~/.bashrc` beside
  `claude`.
- Everything else the daemon does: sessions, approvals, usage, attachments, automations, agents,
  statistics, the local route.

## Updating

Run the install script again from the new package (a newer release's
`hypernova-server-<tag>-linux.tar.gz`, as at the first install), checkout (`git pull`, then
`scripts/install-linux.sh`) or tarball (or `hypernova setup --from <dir>`).
The new daemon is prepared beside the old one and swapped in only once its terminal module works,
then the service restarts; open sessions keep running. (Docker: see "Updating the container" above.)

**By itself** (`hypernova auto-update on`, or the app's Settings → the server → **Update this server
automatically**; on unless turned off, owner 2026-11-01; `"autoUpdate": false` in `settings.json` turns it off). The daemon's look at
GitHub's latest release (30 s after start, then every 6 hours) then also installs a release newer
than the daemon, never an older or equal one:

- **Only an install of a release's package** (`get.sh`, or `install-linux.sh` from an unpacked
  `hypernova-server-<tag>-linux.tar.gz`) in `~/.local/share/hypernova/app`, run by
  `hypernova.service`. Setup marks the daemon it installs (`app/daemon/install.json`, `package` or
  `checkout`): an install from a git checkout (`scripts/install-linux.sh` in a clone, or a helper
  that pulls and runs it) updates from there, never by itself, and says "not available here". So do
  a container, `hypernova run`, a test copy started from elsewhere, and an install from before the
  marker (install the latest release once, and it can from then on).
- **Checked first**: the server package's SHA-256 against the release's note (`serverSha256`;
  older releases have none, and aren't installed), and its daemon's version against the tag.
- **At once**, whatever sessions are doing (owner, 2026-11-01): they keep running through the restart, in
  their shared terminals or `claude agents`, and the daemon is down for a second or two. A hook that fires
  meanwhile fails without holding the session up (the terminal shows "hook error: connect ECONNREFUSED").
  An approval that was waiting stays in Claude Code's own dialog, and the phone shows it from there.
- **Installed as above**, by the package's own `scripts/install-linux.sh --from <it> --yes
  --no-claude`, in a transient systemd unit of its own (`systemd-run --user`, `hypernova-update`),
  so the service's restart can't cut it off; its output in
  `~/.local/state/hypernova/logs/update.log`. If the new daemon doesn't answer within a minute, the
  old one is put back and the service restarted on it. It installs nothing new, and asks no
  `sudo`: a system package the new version needs makes it fail (logged); run the install by hand.
- **A failure** (a download, a checksum, the install) leaves the server as it was and is logged; the
  same release is tried again after 1 hour, 6, then 24, then not until the setting is turned off and
  on. The app says so (a notification, and the line under the switch), and so does `hypernova
  auto-update`. The phones are offered the release's app before the restart, and the new daemon logs "this
  server updated itself from A to B" (`hypernova logs`; the app shows it under the switch).

## Troubleshooting

`hypernova doctor` first: each problem comes with its fix. Then `hypernova logs`. Common ones:

- *"Building the terminal module needs make, g++, python3"*: `sudo apt-get install -y
  build-essential python3`, then `hypernova setup`.
- *"systemd's user manager isn't running"*: `sudo loginctl enable-linger $USER`, or log in over
  SSH, then `hypernova setup`.
- *`hypernova: command not found`* right after the install: log in again (Ubuntu adds
  `~/.local/bin` to PATH at login), or run `~/.local/bin/hypernova`.

## Tests

- Unit: `cd daemon && npm test` includes `test/platform.test.ts` (where things live, what exists
  on each system), `test/linux-cli.test.ts` (the command's pieces and commands against a stand-in
  daemon and system).
- End to end, in Docker: `scripts/test-linux-docker.sh` builds the image, starts the daemon, and
  checks `status`, `doctor`, `pair`, the API's health, pairing a stand-in phone, the Mac-only
  features answering 501, the LAN listener asking for device certificates, a shared terminal
  started by the `claude` function in bash (running a harmless stand-in, never the real Claude
  Code), `logs` and `uninstall`. `--no-claude` builds without Claude Code; nothing signs in.
- The service, in Docker: `scripts/test-linux-systemd.sh` boots Ubuntu 24.04 with systemd (a
  privileged container, publishing nothing), installs from a tarball as a user with sudo and no
  login session, and checks lingering, the unit, `status` and `doctor`, systemd starting the daemon
  again after `kill -9`, a session the daemon started (a stand-in Claude Code) kept through a service
  restart and an update and found again, the daemon back after a
  reboot with nobody logged in, and `uninstall`.
