---
title: Remote access to Claude Code over Tailscale | Hypernova
description: Reach Claude Code on your Mac or Linux server from anywhere: directly on your Wi‑Fi, or over your own Tailscale network, nothing opened to the internet.
h1: Remote access to Claude Code, from anywhere
lede: Your phone talks to your own Mac or Linux server, directly on your Wi‑Fi and over your own Tailscale network everywhere else. No port forwarding, no Hypernova server in between, and the phone's VPN slot stays yours.
shot: mac-popover
shotAlt: The Mac menu bar popover: hypernova/ and the Mac's name, a tailnet chip with one paired phone, an approval for git push origin main with Deny, Always and Allow, the sessions and the usage meters
related: claude-code-on-your-phone, approve-claude-code-permissions-from-android, run-claude-code-on-a-linux-server
---

Claude Code runs on your Mac or on a Linux server: a Mac at home or in your bag, a box under the
desk, a VPS. To approve a command or send the next prompt from somewhere else, your phone has to
reach that computer safely. Hypernova does
this with two routes it picks for you, and it never puts a server of its own in the path.

## Two routes, picked for you

**Local first.** When the phone and the computer share a network (your Wi‑Fi, or a Mac on the
phone's hotspot), the phone talks to it directly over HTTPS. The connection is pinned to the
computer's own certificate from the pairing code, and the phone presents a device certificate of its own: mutual
TLS. Connections from anything but a private address are dropped before TLS starts. On a VPS with a
public address this route is off, and the tailnet is the way in.

**Your tailnet everywhere else.** Away from home, the phone reaches the computer over
[Tailscale](https://tailscale.com), a private network between your own devices. The app tries the
local route first, then its built-in Tailscale node, then the computer's tailnet address, and switches
by itself as you come and go. Settings shows which route is in use and keeps a connection log of
every decision.

## Tailscale built in, on both ends

You don't need the Tailscale app on either device.

- **On the Mac**, Hypernova carries its own Tailscale node. In Setup you scan a code or open the
  sign-in link and sign in with Google, GitHub, Apple or Microsoft. The Mac then appears in your
  tailnet as `hypernova-<your Mac's name>`.
- **On a Linux server**, the daemon runs the same kind of node, `hypernova-<host>`, and
  `hypernova pair` shows its sign-in code the first time.
- **On the phone**, the Android app carries its own node too. It runs inside the app, without
  Android's VPN service, so it never takes the phone's VPN slot and a VPN you already use keeps
  working. You sign in once with the same account. A phone that already reaches the computer through
  the Tailscale app, or locally, never starts a second node.
- **Prefer the Tailscale app?** If it's on your Mac and signed in, Setup can use it instead
  (**Use the Tailscale app**, then **Turn on phone access**). That needs HTTPS certificates turned
  on for your tailnet, in the Tailscale admin console under DNS. A Linux server can use the app
  too ([Hypernova on Linux](/install/linux/)).

Self-hosting your control server with Headscale works too: the daemon's own node can point at it,
and the phone asks you before it talks to that server.

## What stays private

- **Nothing is opened to the internet.** The phone API listens on the computer's loopback address only
  and reaches your tailnet through the node. There is no port to forward on your router.
- **Each phone has its own keys.** Pairing gives every phone its own token and certificate.
  Forget a phone (on a Mac, in the menu bar) and its access ends at once.
- **No Hypernova server carries your sessions.** The only hosted piece is a small name service for
  pairing and a private name for your computer. The pairing details travel after the `#` in the pairing
  link, which a browser never sends to any site.

The full threat model, including what Tailscale itself sees, is in
[SECURITY-MODEL.md](https://github.com/prudnik-web/Hypernova/blob/main/docs/SECURITY-MODEL.md).

## Mind the key expiry

Tailscale asks every device to sign in again after a while. In the
[admin console](https://login.tailscale.com/admin/machines), open your Mac's or server's ⋯ menu and
choose **Disable key expiry**, so your phone doesn't lose it on the road. On a Mac, Setup warns you
two weeks ahead.

## How to set it up

1. [Install Hypernova](/install/) on your Mac and run Setup.
2. In the [Tailscale step](/install/#4-tailscale), scan the code with your phone or click **Open
   the sign-in link**, and sign in.
3. Pair your phone in the last step, **Your phone**, by scanning the pairing code.
4. When the app asks, sign in to Tailscale on the phone with the same account.
5. Disable key expiry for the Mac in the Tailscale admin console.
6. Leave the house. The app moves to your tailnet by itself.

On a Linux server, `hypernova pair` covers steps 2 to 4: see [Hypernova on Linux](/install/linux/).
Only ever at home? Skip the Tailscale step with **Not now**: the same Wi‑Fi is enough. If the phone
can't reach the computer, see [Troubleshooting](/install/#troubleshooting).

## FAQ

### Do I need the Tailscale app on my phone?

No. The Hypernova app carries its own Tailscale node. If you already use the Tailscale app on the
phone and it reaches your computer, Hypernova uses that route and doesn't start its own.

### Does Hypernova open a port on my router?

No. There is nothing to forward: away from home the phone comes in through your tailnet, and the
local route drops anything that isn't on a private network.

### Will my phone's VPN still work?

Yes. Hypernova's Tailscale node doesn't use Android's VPN service, so a VPN you use keeps its slot.

### Does any of my code go through a Hypernova server?

No. Hypernova moves sessions, prompts and files between your phone and your own Mac or Linux server only. The name
service helps with pairing and a private name, and is not in the path of any session.

### Can I run the same setup on a server instead of a Mac?

Yes. The daemon runs on a Linux server or in Docker with the same built-in Tailscale node. See
[Run Claude Code on a Linux server](/guides/run-claude-code-on-a-linux-server/).
