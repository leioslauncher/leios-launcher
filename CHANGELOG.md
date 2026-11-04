# Changelog

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
