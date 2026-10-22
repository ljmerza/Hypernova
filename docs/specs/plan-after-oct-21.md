# The plan: the self-hosted Hypernova, built in full (from 2026-10-17)

Agreed with the owner on 2026-10-16/17. The details are in docs/DECISIONS.md ("Direction: open source,
self-hosted, Tailscale inside") and, for the other projects, in prudnikov-site/docs/SPEC.md
and docs/RELEASES.md. Order follows the owner: "we should first suggest users an opportunity to
install the daemon on their VPS".


> **Update 2026-10-17:** the owner lifted the weekly limit ("build what we wanted to build", pausing
> only near 95% of the 5-hour window), so this is no longer an MVP run after Oct 21: the whole plan
> is built now, in waves, each one built, reviewed, fixed and shipped. The owner rents a test VPS,
> installs Xcode for iOS, and wants voice engines chosen **at first use** (not in setup).
> Waves: 7 local HTTPS (running) → 8 the Linux daemon + CLI, Lens via the app, the name-service
> Worker → 9 public HTTPS for a VPS + the setup and pairing UX + Claude Code installed for you →
> 10 speech on the device (built-in, Soniox on the phone, the first-use chooser) → 11 Tailscale
> inside (home Mac) → 12 shared Kotlin: iOS, desktop, web → 13 Nemotron and local voices on the
> phone → 14 the open-source launch (licence, SECURITY.md, CI, clean installs, tips, the site).

## Before (the owner, any time)
- Upload Lens 0.1.6 (`lens/out.ehpk`): removes the bridge test box.

## Next, right after the local HTTPS wave: Fable usage in the app
- The owner: "We should also add Fable usage into app" (Fable 5.1 has its own weekly limit).
  Claude Code 2.1.302's status line reports only `five_hour` and `seven_day`; the per-model weeks
  appear only on its `/usage` screen ("Current week (all models)", "Current week (Sonnet only)",
  …).
- **How:** a usage probe in the daemon. A hidden shared terminal runs Claude Code with hooks off,
  shows `/usage`, the screen is read as terminals are today, then it closes. It never touches
  login tokens, never calls the model, and is filtered from sessions and History (its transcript
  removed if one appears).
- **When:** every 15 minutes while an app is connected, when the usage details open, and after a
  limit hits.
- **Where:** a "Fable · week" meter (and any other per-model week) next to 5-hour and weekly in
  the phone's sun details and the chat usage strip, a line in the Mac popover, and the glasses'
  usage line when it's the tightest. Built on Opus.

## Week 1 (Oct 21–28): Hypernova runs on a server
1. **Prototype (1–2 days):**
   - **A real network test first:** a small VPS, reached from a phone over
     mobile data for a day. Check whether Tailscale goes direct or through its
     relays, how often it drops, and whether plain HTTPS to the VPS stays up where Tailscale
     doesn't. The result decides the default connection (item 5).
   - Direct HTTPS from the Android app to the daemon on the VPS: a self-signed certificate
     pinned by the pairing code, plus the per-device key.
   - Tailscale (`tsnet`) inside the daemon and the Android app (userspace, no VPN), for the
     home-Mac case.
   - Lens → app → server over the phone bridge (already proven on Android, 2026-10-17).
2. **The Linux daemon:**
   - Paths and a systemd service.
   - Mac-only features switched off: battery, Stay awake, window and simulator capture, `say`.
   - Commands: `hypernova setup`, `pair`, `status`, `doctor`, `logs`.
   - An install script, with Docker as the quick way to try it.
   - Safe on a public port: TLS only, nothing answers a device without its pairing key (a
     client certificate), the pairing endpoint exists only while the code is shown and each
     code works once, rate limits, no admin routes from outside, automatic security updates.
3. **Claude Code on the server:** Hypernova downloads and installs it by itself with
   Anthropic's official installer (or uses an existing install; it updates itself after that).
   The user only clicks "Sign in to Claude" (its own login, a link shown as a QR code on a
   server). The Agent SDK fallback uses API keys only, never the subscription login.
4. **Small fixes along the way:** Lens's `app.json` description and `lens/README.md` still talk
   about the Mac only and Hyperscribe Companion.

## Week 2 (Oct 28–Nov 4): no setup at all
5. **Three connection types, chosen automatically** (the owner, 2026-10-17, on networks that filter VPN traffic: "what if we won't use tailscale then? And connection will be through http?"):
   1. **Same Wi-Fi or the phone's hotspot:** a direct local connection, encrypted and pinned
      by the pairing code. This covers the Mac in the backpack and works with no signal.
   2. **A server with a public address (a VPS):** direct HTTPS on 443. It looks like any app
      talking to its website, and it needs no account at all: scan the code and you're
      connected.
   3. **A Mac at home reached from outside:** built-in Tailscale (one sign-in on each end), as
      an option.
   The phone never uses its VPN slot for Hypernova (Android allows only one VPN, and so does
   iOS), so a user's own VPN keeps working.
5b. **Real HTTPS names: `<random>.hypernova.observer`** (the owner: "I'll buy domain like
    hypernova and users will get their subdomains"; bought 2026-10-17 at Cloudflare Registrar,
    DNS on Cloudflare, the domain only for users' servers):
    - **The owner's setup:** two-factor authentication on the Cloudflare account, DNSSEC on
      (done 2026-10-17). He'd rather not extend the registration yet, so the Public Suffix List
      waits; it needs more than 2 years left.
    - **Ours:**
      - Launch without the Public Suffix List. Let's Encrypt's new-certificate limit then
        covers the whole domain, about 50 new servers a week (renewals don't count); fall back
        to another free certificate authority (ZeroSSL) if it's ever hit. The web app keeps its
        session out of shared cookies (subdomains can set cookies on their siblings). Extend the
        registration and apply when sign-ups near that rate, or a few weeks before a big public
        launch (approval takes weeks).
      - A CAA record allowing only Let's Encrypt.
      - The Worker's Cloudflare token limited to editing this domain's DNS.
      - The root shows only a static page, with no cookies or logins.
    - One domain, its DNS on Cloudflare's free plan. A tiny name service (a Cloudflare Worker)
      gives each server a random name pointing at its IP; no traffic passes through it.
    - Each server gets its own Let's Encrypt certificate by DNS check through the Worker. So it
      works for a VPS, a Mac at home, and on the local network (a name for a `192.168…`
      address).
    - Apply for the Public Suffix List as soon as the domain is bought (Let's Encrypt's
      new-certificate limit per domain; one subdomain's reputation kept apart from the others).
    - Names are public in Certificate Transparency logs, so they're random and the pairing key
      stays required. Users can bring their own domain (a block on the shared domain would cut
      off everyone at once).
    - Without a domain, or if the name service is down, the native apps use the pinned
      self-signed certificate. The web app needs the real one.
6. **Lens talks only to the app, and stays simple** (the owner: "the glasses app should be simple,
   because all the visuals are in the app for a phone"):
   - The loopback bridge, with "Allow Hypernova Lens to connect?".
   - One public Lens build whose whitelist is `127.0.0.1` only.
   - No glasses pairing.
   - Lens's phone page becomes one card ("Connected to Hypernova ✓ · Open Hypernova"). The
     glasses' settings move into the Hypernova app (Settings → Glasses). The lens display stays
     as it is.
6b. **Speech on the device only, chosen by the user** (the owner: "we shouldn't run Nemotron on
    Mac or GPU machine. Device only ... multiple choice or skip at all ... similar with TTS").
    All speech runs on the phone; the server never handles audio or speech keys (Soniox calls
    and its key move from the daemon to the phone app).
    - **Asked at first use, not in setup** (the owner, 2026-10-17): the first tap on the mic, hold
      on the temple or "Read aloud" asks once, with a recommendation pre-selected from the phone's
      speed check. **"Voice input"** (pick one; Settings can change it later):
      - **Nemotron 3.5 ASR on this phone:** about 650 MB, free, offline; a speed check first, and
        the option is greyed out with the reason on a phone too slow for real time.
      - **Soniox:** cloud, the user's own key, paid by usage.
      - **The phone's built-in recognizer.**
      - **Skip:** no mic in the app or on the glasses (mic buttons hidden; holding the temple
        says "Voice is off — set it up in Hypernova").
    - **"Read aloud"** (asked the same way):
      - **A voice model on this phone** (`sherpa-onnx`: Kokoro for English; a Piper or Silero
        voice for Russian, quality to test).
      - **Soniox TTS.**
      - **The phone's built-in voice.**
      - **Skip:** no read-aloud, no speaking wave, no "tap read aloud" on the glasses.
    - **Engine for Nemotron:** Companion's `transcribe.cpp` built for Android (Vulkan or CPU) and
      iOS (Metal); the fallback is `sherpa-onnx`. 8-bit model downloaded once over Wi-Fi after
      checking its licence, loaded only while in use. Path: glasses mic → Lens → bridge → app →
      text back.
    - **Prototype first on the owner's phone:**
      - Nemotron: real-time factor, how fast partial words appear, Russian quality, battery.
      - Local voices: quality in English and Russian.
    - The Hyperscribe Companion client leaves Lens (`src/companion/`, the only reason Lens is
      AGPL), so the whole repo can take one licence.

## Week 3 (Nov 4–11): the open-source launch (self-hosted alpha)
9. **Launch checklist:**
   - A licence for everything outside `lens/` (the owner to choose: Apache-2.0 recommended, or
     AGPL like Lens).
   - Install on a clean Mac and a clean VPS by following the README only.
   - One security review of the whole remote surface (pairing, tokens, the bridge, terminals,
     shared pages), plus `SECURITY.md`.
   - Anthropic's current terms checked.
   - GitHub Actions running all four layers' checks; `CONTRIBUTING.md`.
   - The night logs moved out of `docs/specs`; "alpha, self-hosted" stated plainly.
10. **The README and site story:**
    - "Self-hosted mission control for Claude Code"; the origin in his words.
    - The real-life film ("a working day without a desk"), once the owner answers on footage,
      voice-over and length.
11. **Five testers for a week, then the launch:** Hacker News, r/selfhosted, r/ClaudeAI, X.

## Alongside (smaller tracks, as the week's budget allows)
12. **Personal site** (prudnikov-site; SPEC "Direction update"):
    - Projects first, English then Russian, a clean light look.
    - A page per project, with downloads from GitHub releases and a setup guide.
    - CV page and PDF; services as its own page.
    - Hosting and domain to decide.
13. **Hyperscribe 1.0:** align the version (`app.json` says 0.1.0, the newest `.ehpk` is 0.7.1);
    a GitHub release with the `.ehpk`, changelog and sideload QR; a guide on the site.
14. **Glasses tips:** the spoken phrases, at the right moment (DECISIONS "Tips on the glasses").

## After the launch
15. **Shared Kotlin (Compose Multiplatform):**
    - Networking to Ktor and kotlinx.serialization; strings to Compose Resources; phone-only
      features behind interfaces.
    - Then iOS: VPN mode for the glasses, plus a small push relay or ntfy for "needs you".
    - Desktop clients (Windows, Mac).
    - The web app (PWA with Web Push).
16. **Windows as a computer** (after Linux): the terminal and service parts ported.
17. **Hyperscribe Companion release:** choose packaging (an installer per OS, or a one-command
    install); macOS signing and notarization.
18. **Hypernavigate: postponed** until the owner decides (2026-10-17). When it returns: the lag
    measured on the glasses, city packs, the voice-search issue.
19. **Earlier Hypernova items still open:**
    - The Files feature (spec-files.md, needs the owner's go).
    - Checks on real hardware: Soniox speech on Bluetooth headphones, a real automation run.
    - The owner's pick of a default Soniox voice.
    - Syncing the hyper-cosmos skill.
    - LATER L54–L56.

## Decisions waiting for the owner
1. The licence (Apache-2.0 recommended). Once the Companion client leaves Lens, one licence can
   cover the whole repo.
2. Companion packaging.
3. Site hosting and domain.
4. Which Hyperscribe version is 1.0.
5. The film: footage, voice-over, length.
6. Go-ahead for Files.
7. The default Soniox voice.
