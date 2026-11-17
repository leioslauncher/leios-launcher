---
name: release
description: The owner's release checklist for a new Leios version, one step at a time. Every step that publishes waits for the owner's yes.
argument-hint: "<version, e.g. 1.4>"
disable-model-invocation: true
---

# /release $ARGUMENTS

Go through these steps in order, one at a time. Say which step you're on. **⏸ = stop and wait for the
owner's clear yes**, asked in chat and naming exactly what will happen. A yes covers that one step only.
If anything fails or looks wrong, stop and report it; don't work around it.

Tools not on PATH: `$env:LEIOS_GIT`, `$env:LEIOS_GH`, `$env:LEIOS_GITLEAKS`. apksigner and aapt2 are
in `$env:ANDROID_HOME\build-tools\<version>\`. Work files go in a folder under `logs\release-<version>\`
(git-ignored).

## 1. Ready?

- On `main`, nothing uncommitted, and level with `origin/main` (`git status -sb`).
- CI passed on the latest commit on GitHub: `gh run list --branch main -L 3`.
- Version: if none was given, suggest the next one. `versionCode` is one more than the current one in
  `launcher/app/build.gradle`. ⏸ Owner confirms `versionName` and `versionCode`.

## 2. Save point

`git tag pre-release-<version>` (local only; never pushed).

## 3. Version and changelog

1. Set `versionCode` and `versionName` in `launcher/app/build.gradle`.
2. Draft the `CHANGELOG.md` entry from the commits since the last release tag (`git log <last tag>..HEAD`).
   Show it to the owner and fix it until they're happy.
3. Commit: `Release <version>`.

## 4. Build, test and Lint

From `launcher\`: `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`. Everything must pass.

## 5. The owner removes the TMDB key (owner only)

The public build has no TMDB key. Claude can't touch the key file (the guard hook blocks it), so ask
the owner to:

1. Move `launcher\tmdb.properties` out of the repo for now.
2. Make sure `TMDB_API_KEY` isn't set in the terminal they'll build in.

⏸ Owner confirms both.

## 6. The owner builds the signed release (owner only)

The owner runs this in their own terminal and types the keystore password there:

```powershell
powershell -ExecutionPolicy Bypass -File dev-tools\build-release.ps1 -Keystore <their keystore>
```

Never ask for the password or the keystore path, and never run this yourself. ⏸ Owner says it's built.
Remind them to put `tmdb.properties` back once step 7 passes.

## 7. Check the build

APK: `launcher\app\build\outputs\apk\release\app-release.apk`. AAB: `...\bundle\release\app-release.aab`.

1. `apksigner verify --print-certs <apk>`: verified, with one signer.
2. The certificate matches the last release's: download the last release's APK into the work folder
   (`gh release download <last tag> -p '*.apk' -D <work folder>`; it's read-only, but the hook will ask)
   and compare the `--print-certs` SHA-256 values. Different = stop.
3. `aapt2 dump badging <apk>`: package `tv.leios.launcher`, with the versionCode and versionName from step 1.
4. `Get-FileHash -Algorithm SHA256 <apk>`. Note it.
5. Copy the APK to `<work folder>\leios-launcher-<version>.apk`. This is the only file that gets uploaded.

## 8. Privacy check

1. `powershell -ExecutionPolicy Bypass -File .claude\scripts\push-check.ps1` (what `git push origin main` sends).
2. Run the `privacy-reviewer` agent on the folder it printed, and add the draft release notes as an extra file.
3. Anything other than PASS: stop and show the owner the findings.

## 9. Push main

⏸ "Push main (<n> commits, up to <short hash>) to GitHub?" Then `git push origin main`.
Wait for the CI run on that commit to finish once (`gh run watch <run id> --exit-status`). It must pass.

## 10. Tag

The tag goes on the commit the APK was built from (the `Release <version>` commit, `git rev-parse HEAD`).

1. `git tag -a v<version> <commit> -m "Leios <version>"`
2. ⏸ "Push tag v<version>?" Then `git push origin v<version>`. Push this tag by name, never `--tags`:
   local tags like `pre-*` must stay local.

## 11. Release

1. Write the notes in the work folder: the CHANGELOG entry, then `SHA-256: <hash>`.
2. ⏸ "Create a **draft** release v<version> with only leios-launcher-<version>.apk?"
   Then `gh release create v<version> <work folder>\leios-launcher-<version>.apk --draft --title "Leios <version>" --notes-file <notes>`.
3. Ask the owner to look at the draft on GitHub.
4. ⏸ "Publish release v<version>?" Then `gh release edit v<version> --draft=false --latest`.

## 12. Check it as a stranger would

1. Download the APK with no login: `Invoke-WebRequest <release asset URL> -OutFile <work folder>\public.apk`
   (the URL comes from `gh release view v<version> --json assets`).
2. Its SHA-256 and certificate must match step 7.
3. `gh release view v<version>` shows it as the latest release, with exactly one file.

## 13. Copies and notes

1. Copy the APK, the AAB and the notes, plus a `RELEASE-INFO.txt` (version, commit, APK SHA-256,
   certificate SHA-256, date) to `$env:LEIOS_BACKUP_DIR\release-<version>\`.
2. Remind the owner to put `launcher\tmdb.properties` back if they haven't.
3. ⏸ If the owner wants the project page updated (version, release link, SHA-256), ask before republishing it.
4. Add a short entry to the in-progress log (see `CLAUDE.local.md`): version, commit, tag, release link, SHA-256.
