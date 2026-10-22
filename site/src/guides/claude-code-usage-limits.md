---
title: Claude Code usage limit tracker: 5-hour and weekly | Hypernova
description: See Claude Code's 5-hour and weekly limits as Claude Code reports them, where your pace is heading and when each resets, on your phone and Mac.
h1: A Claude Code usage limit tracker for your phone and menu bar
lede: Hypernova shows the 5-hour window and the week as Claude Code itself reports them, draws where your pace is heading, and counts down to each reset. When a limit stops a session, it says when the work can carry on.
shot: phone-sky-limit
shotAlt: Hypernova's Home at the 5-hour limit: the accretion disk in the middle of the sky is spent and dark red, REFILLS IN 0:16 under it, projects around it as planets, and two sessions below that need you
related: claude-code-and-codex-together, claude-code-on-your-phone, approve-claude-code-permissions-from-android
---

Claude Code on a Claude plan works inside two budgets: a 5-hour window and a weekly one. Most
people find out where they stand when a session stops. Whether Claude Code runs on your Mac or a Linux
server, Hypernova keeps both windows in view on your phone and your glasses (and on a Mac, in the
menu bar), so you see the limit coming and know when it lifts.

## Where the numbers come from

Claude Code hands its rate limits to the status line: the used share and the reset time of the
5-hour window and of the week. Setup's **Connect Claude Code** step (on Linux, the install script)
adds a status line tap that reads them. Your own status line keeps working; Hypernova only listens in. These are Claude Code's
own readings, not a guess made from transcripts.

Some models also have a week of their own, which Claude Code shows only on its `/usage` screen
("Current week (Sonnet only)", or one per model with its own limit). Hypernova reads that screen
too, so those weeks show next to each model in the model picker.

## The sun is your 5-hour window

On Home, the sun in the middle of the sky is an accretion disk, and the disk is the 5-hour window.
The hot, bright part is the budget left. As you spend, the disk cools from its rim inward. While
something works, knots of hot gas spiral in; at rest the disk is still.

- **The ring** round the sun shows the share used, in mint, amber from 80%. A faint arc runs on
  to where your pace leads by the reset, and a red notch marks the moment when the pace reaches the
  limit first.
- **At the limit** the disk goes quiet and deep red, and a countdown under it says when it refills
  (`REFILLS IN 0:16`). At the reset it flares back to a full disk.
- **The weekly limit** stops the disk's supply: it drains to a faint point, with the week's
  countdown under it.

## Meters that show where your pace is heading

The same windows appear as meters wherever you look: the usage card on Home, a compact strip at
the top of every chat, the Mac's menu bar popover and the glasses' glance. Each meter has one tag
on the right: the countdown to the reset (`↻ 2h 1m`), or, when your current pace reaches the limit
before the reset, the time to the limit in red. While something works, a faint fill shows where
the pace leads.

The pace is worked out on your computer from the latest official readings, so the phone, the menu bar and
the glasses all say the same thing.

![The model picker in a session: Claude's models, with Fable 5.1's own week at 41% and Sonnet's at 3%, each with its reset countdown](img:phone-model-picker)

## When a limit stops a session

A session the limit stopped says so on its card, with when the limit lifts. When Claude Code will
carry on by itself at the reset, the card says when, with **Don't** and **Continue now** beside it.

Automations can use the reset for you. The limit templates come first:

- **Carry on after the limit** picks up every session the 5-hour limit stopped, the moment it
  resets.
- **Do it when I have budget again** parks a task until your limit comes back.
- **Night shift** puts a fresh overnight budget to work.
- **Weekly debrief** tells you your week from Statistics when the weekly window resets.

None of them spends paid usage credits: a run never carries on past a limit on usage credits, it
waits for the reset or is skipped. **Switch provider at the limit** moves working sessions to
another provider, which costs money, so it asks for your explicit yes when you save it.

## How to set it up

1. [Install Hypernova](/install/) on your Mac and open it. Setup starts by itself.
2. In Setup, run **Connect Claude Code**. It adds the hooks and the status line tap.
3. Pair your phone in the last step, **Your phone**.
4. Use Claude Code as usual. The meters fill in once a session has talked to Claude.
5. Optional: in the app's **Automations** tab, tap **New automation** and pick **Carry on after
   the limit**.

On a Linux server, the install script connects Claude Code and `hypernova pair` pairs the phone:
see [Hypernova on Linux](/install/linux/).

## FAQ

### Are these the same numbers as Claude Code's `/usage`?

Yes. The 5-hour and weekly numbers are the ones Claude Code passes to its status line, and the
per-model weeks are read from its `/usage` screen.

### When does my Claude Code limit reset?

Each window has its own reset time, which Claude Code reports with the numbers. Hypernova shows it
as a countdown on every meter, or as a day and time when a week's reset is more than a day away.

### Will Hypernova spend usage credits when I hit the limit?

Not without your yes. Automations never carry on past a limit on usage credits, and one that runs
on another provider can only be saved once you agree that it costs money.

### Does it track Codex limits too?

Yes, once Codex is on for Hypernova: Codex's own windows get their own meters, labelled Codex
usage. See [Claude Code and Codex together](/guides/claude-code-and-codex-together/).

### Do I need the phone to see my limits?

On a Mac, no: the menu bar popover shows the same 5-hour and weekly meters.
