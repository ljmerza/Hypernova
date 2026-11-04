# Changes from the original

This whole project is a modified version of the original work. It isn't the original, and the
original's author isn't responsible for it.

- **The original:** Hypernova by Nikita Prudnikov (prudnik-web), https://github.com/prudnik-web/Hypernova,
  under the Apache License 2.0.
- **The fork point:** commit `c64bbf9a448b54cbc145d3c005b10a4b86f3f1f0`.
- **Modified by:** Leonardo Merza, from 2026. Attribution is in `NOTICE`, the licence in `LICENSE`.

To see exactly what changed: `git diff -M c64bbf9`.

## What changed, in short

The Mac app, the glasses app (Lens), the website, the name service and the built-in Tailscale node were
removed, so the phone reaches the server only over the local network or a VPN into it. Much of the
Android app's data layer moved to `android/shared`, and many features were added and changed in the app
and the daemon. The client's Statistics screen was written again from what it does, not from the original's code: the
daemon's part of Statistics and `android/.../data/Stats.kt` are still the original's, modified (`docs/DECISIONS.md`,
"Statistics, written new", says what that does and doesn't amount to). `docs/DECISIONS.md` has the reasons.

## Which files say so

Every file of the original that differs now carries this line in a comment near its top, in the comment
syntax of its file type:

```text
Modified by Leonardo Merza, 2026. See NOTICE.
```

`scripts/license-notices.sh` finds those files and adds the line. Moved files count as the original's
files, modified.

## Modified files that can't hold that line

This file is the notice for these. All of them have changed from the original.

**JSON, which has no comments:**

- `daemon/package.json`
- `daemon/package-lock.json`

**Plain text that says so itself, in its last lines:**

- `NOTICE`
- `android/app/src/main/assets/legal/NOTICE` (a copy of `NOTICE` that the app ships)

Two binary files of the original have changed since the fork point: `docs/images/social-preview.png` (the
project's picture) and `android/gradle/wrapper/gradle-wrapper.jar` (Gradle's own wrapper, from the Gradle upgrade). No
other image or binary file of the original has been modified: the ones added since the fork point
are new files.

## Files with no line of their own

- **New files** aren't the original's. Some of them hold code that came from an original file when it was
  split up or rewritten, too little alike for git to call them the same file. They're part of this
  statement.
- **Third-party code** in `third_party/` and its copies in `android/app/src/main/assets/` keep their own
  licences and notices. The third-party files the original shipped are unchanged, and those added since
  are new files.
- **`skills/hyper-cosmos/`**, the original's design language as a skill, has been removed.
- **Built packages:** comments don't survive compiling, so the APK's code and resources don't carry the
  line. `NOTICE`, which the APK and every server package hold, says the same thing for them, and the
  server packages hold this file too.
