# Changelog

## Unreleased

### Added
- **Search on Home.** Open it with the new **Search** button in the top bar, **Up** at the top of Home, or holding **Home** (with the ≡ helper on; it never delays a normal press). Hold ≡ and hold Settings can open it too. Inside other apps, nothing changes.
  - A keyboard made for the D-pad, and recent searches when the field is empty (kept on the TV only).
  - Results update as you type: **Apps**, then **Settings** (every Leios setting, also by other words for it, such as "wallpaper" for Background), then **Movies & TV**.
  - **Movies & TV** comes from TMDB, with where to watch from JustWatch. It's off until you say yes, and is only in builds made with a TMDB key. Results are **Apps, Movies & TV, then Settings**. Titles on your apps come first, with the app names (marked "rent" where an app only rents or sells it, such as YouTube); titles none of your apps has are shown quietly below, or hidden if you prefer. If more than one of your apps has it, you choose, with your most-used app first.
  - Picking a title opens **its own page in Netflix, Apple TV, Max or Prime Video** (using the app's ID for it from Wikidata), **Hulu's, YouTube's or Crunchyroll's search** with the title filled in, or otherwise the app with a note saying what to search for.
  - As you type, the rest of a popular title appears in grey; **Up** from the keyboard's top row takes it.
  - Apps hidden from Home stay out of search. Locked apps show marked **Locked**, and opening one asks for the PIN, as on Home. Guest mode shows no settings.
  - On the keyboard, **Back** deletes a letter; held, it deletes until the field is empty and stops there. A fresh Back on an empty field closes search. A setting opens in Customization with its row highlighted, behind the PIN if Customize is locked; Back returns to search.
- **Customization:** a full screen with every setting in seven sections. **Customization › System › Search & privacy** controls online search, region, titles not on your apps and recent searches; **About** shows the version and the TMDB and JustWatch credits.

### Changed
- **Customize** on Home is now a quick sheet: Background, Appearance, Dock & arrange apps, and **All customization**.
- **Look** is now **Look & sound**, and **Backup & reset** is now **System**. All saved settings keep working, and older backups and themes still restore.
- **Top bar** has a **Search button** toggle. Themes carry it.
- **Top-bar buttons are circles** with just their icon; the one you're on opens up to show its name, as before, and the focus ring follows it. The Wi-Fi name and the notification count show only while their button is focused, so the bar is calmer.
- Opening Customize with a remote shortcut lights the Customize button, not whichever button was focused before.

### Fixed
- Interface sounds were silent on TVs that mute Android's System sound channel at start, such as the JMGO N3 4K. Sounds now play at the TV's media volume when system sounds are muted. They never take audio focus, so other apps are never paused or turned down.
- Holding left or right at either end of the sounds Volume slider replayed the sound until the button was released.
- The first sound after turning sounds on, or changing their output, could be lost while the sounds were still loading.

### Changed
- Customization › Look & sound › Sounds has one **Volume** slider, from Off to 10, in place of Off / Subtle / Normal and a level of 1–5. Earlier settings carry over at the same loudness.
- New **Sounds › Output**: Automatic (the default), System sounds or Media volume, with a note that says when sounds can't be heard and why. **Silent on Bluetooth or ARC** moved here.

## 1.2.1 (versionCode 4)

### Fixed
- Pressing the Settings (gear) button while the screensaver showed could turn the TV off. The ≡ panel sees remote buttons first, which delayed Android counting the press as use, and the screen-off timer ran out. While the screensaver shows, buttons now skip the panel and go straight to Android: the first press just wakes the TV; press again to open Settings or the panel.
- Using only the ≡ panel for a long time could let the screen time out. Buttons the panel takes now count as using the TV.

### Changed
- New permission: keep the screen awake (`WAKE_LOCK`), used only for the fix above.

## 1.2 (versionCode 3)

The first public release.

### Changed
- The app is called **Leios** on screen, in Android's app lists and in Accessibility ("Leios quick settings").
- New backups and themes are saved in `Pictures/Leios` as `.leios.png` pictures.
- New TV banner with the Leios name.
- Photo-of-the-day requests now identify themselves as `LeiosLauncher` with a link to this project, as Wikimedia asks.
- A release build without signing details now stops with a clear message, instead of making an unsigned APK.

### Added
- Restore and Import theme also find backups and themes saved before the rename, in `Pictures/Clean Home`. They're only read, never changed.

## 1.1 (versionCode 2)

The first version in this repository, as `tv.leios.launcher`. It carries over the earlier private project's features with no behaviour changes, other than the new app ID.

### Added
- Home screen with dock, app grid and categories.
- ≡ quick settings panel in any app.
- Colour, shape, photo, GIF, slideshow, video and online backgrounds.
- Themes, fonts, clock, top bar, tile and accent options, and light, dark and automatic modes.
- Weather, sleep timer, test patterns, and start-up and wake options.
- PIN lock, app locks, schedules and guest mode.
- Backups, icon packs, notification bell, clock screensaver and optional sounds.

### Changed
- Release builds now need your own key. They never fall back to the debug key.
- The app ID and code package changed to `tv.leios.launcher`, so it installs as a new app beside older private builds.
