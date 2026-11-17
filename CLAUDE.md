# Leios: notes for Claude

Leios is an Android TV home screen (`tv.leios.launcher`) for a JMGO projector. **This repo is public.**
`CONTRIBUTING.md` has the full rules; this is the short version.

## Build and test

Run from `launcher\`, with `JAVA_HOME` (JDK 17) and `ANDROID_HOME` set.

| What | Command |
|---|---|
| Everything CI runs | `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` |
| Debug build | `.\gradlew.bat assembleDebug` |
| Unit tests | `.\gradlew.bat testDebugUnitTest` |
| Lint (errors stop the build) | `.\gradlew.bat lintDebug` |
| Install on the TV (ask first) | `powershell -ExecutionPolicy Bypass -File setup-kit\install.ps1` (needs `$env:TV_HOST`) |
| Signed release | The owner runs `dev-tools\build-release.ps1` (it asks for the keystore password). See `/release`. |

Scripts are blocked by default on this PC: run them as `powershell -ExecutionPolicy Bypass -File <script>`.

## Code rules

- Plain Java and Views. No new libraries without asking.
- Design tokens only: colours, sizes and timings live in `res/values/` (`colors.xml`, `dimens.xml`,
  `motion.xml`) and are read through `Theme` and `Tokens`. Nothing hardcoded in Java.
- Every menu is drawn by `MenuView`. Menus stay at 7 rows or fewer.
- All text people see goes in `res/values/strings*.xml`. Lint fails the build on hardcoded text.
- Settings are read through `SafePrefs`. Everything must work with a D-pad.
- `launcher/` never depends on `setup-kit/` or `dev-tools/`.

## How to work

- Save first: before a big change, commit and add a local tag (`pre-<topic>`). Local tags are never pushed.
- Small commits, one change each.
- After every change, run the tests and Lint.
- Never push, push a tag or publish a release without the owner's yes.

## The projector

- Ask before touching the projector: installing, changing settings, rebooting.
- Test with the real remote. Injected keys (`input keyevent`) skip the quick-settings panel's
  accessibility service, so they can't test the panel, the gear button or anything else it catches.
- ADB over Wi-Fi drops when the projector goes into standby. Reconnect after it wakes; a dropped
  connection is not a crash.
- Never force-stop `tv.leios.launcher` or `tv.clean.home`: Android then switches the accessibility
  service off.
- Never disable or uninstall an app while it's an active device admin. Remove the admin in the TV's
  Settings first. (Doing it froze the projector once.)

## Privacy

Never commit or publish secrets or personal details: keystores, passwords, `keystore.properties`,
`tmdb.properties`, API keys, IP addresses, Wi-Fi names, usernames, private paths, device dumps, logs, or
screenshots that show personal details. Scripts read the TV's address from `$env:TV_HOST`. Screenshots
and logs go in `shots\` and `logs\`, which git ignores. Before any push, run
`.claude\scripts\push-check.ps1` and then the `privacy-reviewer` agent.

## Where things are

- In-progress log: `HANDOFF.md`. It's git-ignored and kept outside this repo; `CLAUDE.local.md` says
  where. Read it before starting and add to it, but don't copy it here.
- `/tv-check`: build, install (after asking), screenshots, contact sheet, crash and ANR check.
- `/release`: the release checklist. Only the owner starts it.
- `.claude/hooks/guard.ps1`: blocks force-stop and private files; asks before push, release and risky ADB commands.
