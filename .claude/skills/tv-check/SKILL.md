---
name: tv-check
description: Check a change on the projector. Builds the debug APK (tests and Lint too), installs it after asking, screenshots the screens named, makes a contact sheet and checks logcat for crashes and ANRs. Use for /tv-check, or when asked to try a change on the TV.
argument-hint: "[screens, e.g. home, customize look, search] [-Apk path] [-NoInstall]"
---

# /tv-check

Screens to check: $ARGUMENTS (none named: the dock, the grid, the top bar and Customization's first section).

## 0. Ready?

- `$env:TV_HOST` is set (it comes from `.claude\settings.local.json`) and `platform-tools\adb.exe` exists
  in the repo root. If either is missing, stop and say what's missing.
- `.\setup-kit\tv.ps1 -Cmd 'true'` connects. If it fails, the projector may be in standby (ADB over
  Wi-Fi drops then) or USB debugging is off. Ask the owner to wake it or turn debugging on; don't retry in a loop.

## 1. Build

From `launcher\`: `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`. If anything fails, stop and report it.
Skip this step with `-Apk` (an APK the owner built) or `-NoInstall`.

## 2. Ask, then install

Skip this step with `-NoInstall`: just screenshot what's installed.

1. See what's installed: `.\setup-kit\tv.ps1 -Cmd 'dumpsys package tv.leios.launcher | grep -m3 -E "versionName|versionCode|pkgFlags"'`.
   No `DEBUGGABLE` in `pkgFlags` means a release-signed build is installed. A debug build can't
   replace it: the signatures differ, and the only way round is uninstalling, which wipes the owner's
   settings. **Never uninstall.** Say so, then offer to screenshot what's installed (`-NoInstall`) or to
   install an APK the owner signed (`-Apk <path>`).
2. Ask the owner: "Install <APK, commit, version> on the projector?" Wait for a clear yes.
3. Note the TV's time, for the log check: `$since = (.\setup-kit\tv.ps1 -Cmd "date '+%m-%d %H:%M:%S.000'")`.
4. Install: `powershell -ExecutionPolicy Bypass -File setup-kit\install.ps1` (add `-Apk <path>` for another APK).
   It installs as an update, so settings and permissions stay, and it claims Home only if needed.

## 3. Screenshots

- Folder: `shots\tv-check\<yyyyMMdd-HHmmss>\`. Check it's ignored first:
  `git check-ignore -q shots/tv-check/probe.png` must succeed. Screenshots never go anywhere git tracks.
- Drive the remote with the helpers in `dev-tools\nav.ps1` (dot-source it: `GoHome`, `OpenCustomize`,
  `K <keys>`, `Shot <path>`), or with `.\setup-kit\tv.ps1 -Keys ... -Screenshot <path>`. For every screen at
  once: `dev-tools\tour.ps1 -Out <folder>`.
- Name the files `NN-<screen>.png`, in order.
- Injected keys skip the quick-settings panel's accessibility service. For the panel, the gear button or
  anything else it catches, ask the owner to press the real remote, then take the screenshot.
- An empty screenshot means protected content (HDMI, Live TV, some apps). That's not a bug.
- Look at every screenshot and compare it with what the change should show.

## 4. Contact sheet

`powershell -ExecutionPolicy Bypass -File dev-tools\sheet.ps1 -Dir <folder>`, then show the owner the sheets.

## 5. Crashes and ANRs

Since `$since` (or the last 10 minutes if nothing was installed):

```powershell
.\setup-kit\tv.ps1 -Cmd "logcat -d -b crash -T '$since'"
.\setup-kit\tv.ps1 -Cmd "logcat -d -b events -T '$since' | grep -E 'am_anr|am_crash|am_proc_died' | grep tv.leios.launcher"
.\setup-kit\tv.ps1 -Cmd "logcat -d -T '$since' | grep -E 'FATAL EXCEPTION|ANR in' -A 20"
.\setup-kit\tv.ps1 -Cmd 'settings get secure enabled_accessibility_services'
```

The last one must still list `tv.leios.launcher`: the panel service is still on. Logs can hold private
device details. Quote only the lines that matter, and save logs only under `logs\` (git-ignored).

## 6. Report

What was installed (or that nothing was), where the screenshots and sheets are, what each screen shows
compared with what was expected, crashes or ANRs (or "none since <time>"), and what couldn't be checked
(anything that needs the real remote).

Never force-stop, uninstall, disable, reboot or change settings on the TV as part of this check.
