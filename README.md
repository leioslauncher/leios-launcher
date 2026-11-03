# Leios Launcher

A clean, ad-free home screen for Android TV and Google TV, written from scratch in plain Java. Say it "LAY-os": it comes from the ancient Greek word for smooth.

There are no accounts, no ads and no tracking. It's controlled entirely from the remote.

![The home screen](docs/screenshots/01-home.jpg)

| | |
|---|---|
| ![Quick settings](docs/screenshots/02-quick-settings.jpg) | ![Customize](docs/screenshots/03-customize.jpg) |
| ![App options](docs/screenshots/04-app-options.jpg) | ![Arranging apps](docs/screenshots/05-arrange.jpg) |
| ![Light and dark](docs/screenshots/06-light-dark.jpg) | ![Backgrounds](docs/screenshots/07-backgrounds.jpg) |

App logos in the screenshots belong to their owners. They're shown only because those apps were installed on the test device.

## Before you start

**Provided as is, with no warranty. You use it at your own risk.** This project isn't made or approved by JMGO, Google or any TV maker.

- **It changes your device.** Installing a home screen, and especially using the optional setup kit, changes how your device behaves. A wrong change can break features such as casting, voice search or updates.
- **The last resort is a factory reset,** which erases everything on the device. Know how to do one on your model before you start.
- **It may affect your warranty or support,** and a firmware update can undo or break some changes.
- **Network debugging opens a door on your network.** While it's on, any device on your Wi-Fi can ask to connect. The TV shows a prompt, so only accept your own PC, and turn debugging off when you're done.

## Supported devices

| Device | Status |
|---|---|
| JMGO N3 4K (reports itself as K970AN), Google TV, Android 14 | **Tested** |
| Other Android TV and Google TV devices, Android 9 or later | **Not tested.** May work, but behaviour will vary |

The JMGO-only rows hide themselves on other devices, but that has never been tested on a non-JMGO device.

## Features

- **Home:** a dock, an app grid, categories, and moving, hiding and renaming apps.
- **Quick settings on the ≡ button, in any app:** volume, brightness, inputs, Wi-Fi and sleep timer.
- **Backgrounds:** colours, shapes, photos, GIFs, a slideshow, video, and an optional online photo of the day.
- **Look:** light, dark or automatic, plus fonts, clock, top bar, tile styles, accent colour, themes and optional sounds.
- **Weather:** for a city you type in.
- **Projector tools:** picture, brightness and keystone shortcuts, test patterns, sleep timer, and start-up and wake options. Some of these are JMGO-only.
- **Lock:** PIN lock, app locks, schedules and guest mode.
- **Backup:** save and restore your settings, with automatic backups.
- **Extras:** a clock screensaver, icon packs and a notification bell.

Icon packs have only been tested with a test pack. Bluetooth and ARC auto-mute, full-motion film backgrounds, right-to-left languages and large text haven't been tested on hardware.

## Build from source

You need JDK 17 and the Android SDK with platform 34 and build-tools 34.0.0. Android Studio can install the SDK. The Gradle wrapper downloads Gradle itself.

Windows PowerShell, from the repo root (swap in your own paths):

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\android-sdk'
cd launcher
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

The debug APK lands in `launcher\app\build\outputs\apk\debug\`. It's signed with your own automatic debug key.

**Release builds:** without a signing key, `assembleRelease` makes an unsigned APK. It never uses the debug key. To sign one, copy `launcher\keystore.properties.example` to `launcher\keystore.properties` and fill it in, or set the `LEIOS_*` environment variables listed there. Keep the key outside the repo, and never commit it.

## Set up on a TV

These steps use ADB and were tested only on the device above.

1. On the TV, go to Settings > System > About and press Build 7 times. Then turn on **USB debugging** in Developer options.
2. Install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) so `adb` is available, or unpack them into `platform-tools\` in this repo.
3. Build the APK, then install it and make it Home:

   ```powershell
   $env:TV_HOST = '192.0.2.10'   # your TV's IP address
   .\setup-kit\install.ps1
   ```

4. Accept the "Allow USB debugging?" prompt on the TV.
5. For the ≡ panel, turn on **Leios quick settings** in Settings > System > Accessibility.
6. For the bell, allow notification access in Android's settings.
7. Turn USB debugging off when you're done.

To use the clock as the screensaver, run this once:

```powershell
.\setup-kit\tv.ps1 -Cmd 'settings put secure screensaver_components tv.leios.launcher/.Screensaver'
```

Avoid **Force stop** on the launcher. Android then turns the ≡ panel off until you re-enable it in Accessibility.

Backups are saved as pictures in `Pictures/Leios`. Leios can also restore backups and themes saved before the project was renamed, from `Pictures/Clean Home`; it only reads those, never changes them.

## Undo everything

Network debugging needs to be on. To hand Home back and undo the setup kit:

```powershell
.\setup-kit\restore.ps1
.\setup-kit\tv.ps1 -Cmd reboot
```

To remove the launcher too, drop its sleep-timer permission, then uninstall it:

```powershell
.\setup-kit\tv.ps1 -Cmd 'dpm remove-active-admin --user 0 tv.leios.launcher/.SleepTimer\$Admin'
.\setup-kit\tv.ps1 -Cmd 'pm uninstall tv.leios.launcher'
```

## What's in this repo

| Folder | What |
|---|---|
| `launcher/` | The app. It builds on its own and doesn't depend on anything else here |
| `setup-kit/` | **Optional** ADB scripts: install, debloat, restore and the HDR fix. See its [README](setup-kit/README.md) |
| `dev-tools/` | Test, measurement and screenshot scripts. See its [README](dev-tools/README.md) |
| `docs/` | Screenshots |

## More

- [Privacy](PRIVACY.md)
- [Changelog](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

GPL-3.0. See [LICENSE](LICENSE). The fonts have their own license; see the [notices](THIRD_PARTY_NOTICES.md).
