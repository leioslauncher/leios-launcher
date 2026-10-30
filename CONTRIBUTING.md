# Contributing

Thanks for helping.

## Before you start

- For anything bigger than a small fix, please open an issue first so we can agree on the idea.
- Contributions are licensed under GPL-3.0, the same as the project.
- Only submit work you have the right to share. Please don't add decompiled code, vendor files or anything copied from other launchers.

## Rules for the app

- `launcher/` must build on its own. It must never depend on `setup-kit/` or `dev-tools/`.
- Plain Java and Views, with no new libraries unless we agree first.
- Colours, sizes and timings live in `res/values/`, not in code.
- Every setting is read through `SafePrefs`, so a bad value can't crash Home.
- Everything must work with a D-pad remote.
- Menus stay at 7 rows or fewer.
- Device-specific rows must hide themselves when they aren't supported.

## Before you open a pull request

```powershell
cd launcher
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Say what you tested, and on which device. If you couldn't test on real hardware, say so.

## Keep it private

Don't commit keystores, passwords, `keystore.properties`, IP addresses, Wi-Fi names, device dumps or screenshots that show personal details. Scripts read the TV's address from `$env:TV_HOST`.
