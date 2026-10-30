You are setting up my Android TV / Google TV projector over network ADB from this Windows PC. The goal: remove the bloat, then replace the home screen with a clean launcher you build from source, plus a quick-settings panel on the remote's ≡ button, light/dark themes and a fix for HDR auto-dimming. This was first done on a JMGO K970AN (Google TV, Android 14, MediaTek, Mali-G52). If my device is different, adapt it, and say so whenever a step is JMGO-specific.

## How to work

- **Ask before anything that depends on my taste or how I use the TV:** which apps to disable, dock apps, colours, what the remote buttons do, any trade-off. Ask one topic at a time, offer options with a recommendation, and wait for the answer. Do the obvious, safe things without asking.
- **Verify everything on the real screen.** After each visible change, take a screenshot (`adb exec-out screencap -p`) and look at it. Never say something works unless you have seen it or I have confirmed it on the TV.
- **Everything must be reversible.**
  - Disable packages (`pm disable-user --user 0`), never uninstall them.
  - Back up `pm list packages`, `settings list global|system|secure` and `getprop` to `backup\` before the first change.
  - Keep a `restore.ps1` that undoes everything, or a single package.
  - Keep the project in git (portable MinGit if git isn't installed). Commit after every meaningful step.
  - Before any big redesign, tag the state, zip the folder, pull the installed APK and write a `ROLLBACK.md`.
- **Never touch:** factory or engineering-menu packages, core Android, Play Services or Play Store, the projector's own services (keystone, autofocus, picture, remote, key handling, Chromecast). Write `tools\disable.ps1` so it refuses these.
- Keep all tools local to the project folder: a portable JDK 17, Gradle, the Android SDK command-line tools and platform-tools/adb. Install nothing system-wide.
- Finish with a plain-English `README.md` covering what changed, how to use every feature from the remote, how to undo it, and a table of the scripts. At the very end, remind me to switch USB debugging off.

## 1. Connect

1. Walk me through switching on Developer options (Settings › System › About › tap Build 7 times), then USB/network debugging. Find the TV's IP, then `adb connect <ip>:5555` and have me accept the prompt on the TV.
2. Write `tools\tv.ps1` with `-Cmd` (shell command), `-Keys` (keyevents) and `-Screenshot <path>`. Read the host from `$env:TV_HOST`.
3. Also write a screenshot tour (`tools\tour.ps1`) that walks every screen and menu with key presses, and a contact-sheet maker (`tools\sheet.ps1`), so every change can be checked in one look.
4. Gotcha: PowerShell mangles `$()`, quotes and pipes in inline shell commands. For anything non-trivial, write a `.sh` file, `adb push` it to `/data/local/tmp` and run `sh` on it. Delete these files at the end.
5. Gotcha: Windows paths can get too long for adb push. Copy files to a short temp path first.

## 2. Debloat (ask first)

1. List what's installed in groups: telemetry and feedback, vendor content and preinstallers (apps that push installs), vendor updaters, the voice assistant, streaming apps, and the stock launcher plus setup wizard.
2. Recommend a set, let me choose, disable the chosen packages, record them in `disabled-packages.txt`, reboot, and check that none came back.
3. **Wi-Fi gotcha:** if the setup wizard (e.g. `tungsten.setupwraith`) owns the saved Wi-Fi network, disabling it deletes the network. Forget and re-join the Wi-Fi from Settings first so the system owns it.
4. Optional, so ask: set Private DNS to `dns.adguard-dns.com` to block ad and tracker domains in apps. Tell me plainly it does **not** block YouTube ads.

## 3. The launcher (`tv.clean.home`)

A native Java Android app in `launcher/` using plain Views (no Compose, no Leanback). `androidx.recyclerview` is the only library. minSdk around 26, target 34, Outfit typeface (OFL) from assets.

The install script:
- builds with the local toolchain;
- installs via `adb push` + `pm install -r -g`, because `adb install` can hang on Android 14;
- grants `ACCESS_FINE_LOCATION` (needed to read the Wi-Fi name) and `READ_MEDIA_IMAGES`;
- runs `cmd package set-home-activity tv.clean.home/.MainActivity` only if Home isn't already this launcher.

Disable the stock launcher only once this one works.

### Design system (build this first)

- **Tokens in one place.** Use `res/values/colors.xml`, `dimens.xml` and `motion.xml`, read through `Theme.java` (colours) and `Tokens.java` (sizes, type, motion, fonts). No colour, size or timing is hardcoded anywhere else.
- **Colours:**
  - Dark-first: near-black `#0E1014` (not pure black), layered surfaces, and one accent, azure `#4A8CFF`.
  - Filled focus rows use `#2F6FE0` so white text stays above 4.5:1.
  - Add a matching light palette. Check WCAG AA contrast for every text colour against the surface it sits on.
- **Type and spacing:**
  - Type scale 80 / 40 / 24 / 19 / 17 / 15 sp. Nothing is smaller than 15 sp, and there are no thin weights (they wash out on a projector).
  - Spacing on a 4 dp grid.
  - Overscan-safe margins of 48 dp horizontally and 28 dp vertically.
- **Motion:**
  - Focus moves in about 160 ms with an ease-out curve and no overshoot.
  - Scale-up of 1.08 for tiles and 1.05 for pills.
- **Icons:** a small set of stroke vector icons (24 grid, 2 px), tinted at runtime.

### Home

- **Top bar:**
  - On the left, the clock, date and weather (temperature and conditions only, no city name).
  - On the right, pills for **Inputs**, **Wi-Fi**, **Customize** and **Settings**. The focused pill fills with the accent.
- **Dock:** a frosted band of 4–7 big app tiles, defaulting to `dock_apps` in `res/values/config.xml`. The focused app's name is shown large above it.
- **Grid:**
  - Down from the dock lifts it and shows all apps over a soft backdrop, so the names stay readable.
  - Up/Down move exactly one row. Back returns to the dock, and Home while on Home returns to the dock.
  - Show placeholder tiles while apps load.
  - Apps without a banner get a generated tile: their icon and name on a card tinted with the icon's colour.
- **Focus:** one accent ring with a soft glow, drawn as a few faint rings rather than a blur, in a single highlight view that glides between targets.
- **Gotchas:**
  - Leave headroom above the first row and use `setClipToPadding(false)` on the containers, the top bar included, or the scaled-up focus gets clipped.
  - Text shadows render as solid boxes on this MediaTek GPU, so don't use them.

### One menu component for everything

Write a `MenuView` used by every Home menu and by the ≡ panel:
- a header with a breadcrumb ("CUSTOMIZE › LOOK") and a title;
- rows with an icon, label, value and a chevron, tick, real slider, stepper, spinner or note;
- an accent focus bar that slides between rows, scrolling so one row ahead stays visible;
- disabled rows greyed out with the reason underneath;
- a footer with remote hints ("OK Select · BACK Back").

Keep every menu to 7 rows or fewer. Use your own toast and text-input card in the same style instead of stock Android ones. Slow things (photo lists, city search) show a spinner row.

### Hold OK on a tile: app options

A small card beside the tile:
- **Open**.
- **Move**: on the dock, Left/Right moves the tile and Down sends it to the grid. In the grid, all four arrows move it anywhere, and Up from the top row puts it in the dock, swapping if the dock is full. Moving inside the grid switches sorting to Custom.
- **Hide from Home**.
- **App info**.
- **Close app**.
- **Uninstall…**, only for apps I installed myself.

### Customize (a sheet on the right, under the top bar)

Every change saves at once and survives reboots. Back goes up a level, and Right opens a row with a chevron. Customize reopens on the section used last.

- **Background:**
  - **Colours:**
    - Dark mode: Black, Midnight, Graphite, Aurora, Ember, Dusk.
    - Light mode: Pearl, Mist, Sand.
  - **Shapes:** Apple-style abstract backgrounds drawn in code once into a screen-sized bitmap off the main thread, each in a dark and a light palette:
    - Bloom: large soft overlapping colour discs.
    - Orbit: a glowing sphere with fine fading rings.
    - Prism: overlapping translucent triangles.
    - Dunes: a low sun behind layered hills.
  - **Photo from this TV**: up to 1000 images from internal storage and USB volumes, sorted by name, skipping images under 800 px wide. Each photo previews after a short pause, and the chosen one is copied so it survives unplugging.
  - **Slideshow from USB**: every 15 s to 15 min. Random order with no repeats, cross-fades, paused while an app is open.
  - Each mode (light/dark) remembers its own background. Choices preview live, and Back cancels.
- **Dock:** slots (4–7, with the grid using the same number of columns), Rearrange, Reset to default.
- **Apps:** Show or hide (ticked apps show on Home), Sort A–Z, Most used or Custom (my own order, kept even when I switch away and back), Arrange apps.
- **Weather:** on/off, city typed with the on-screen keyboard (projectors have no location), °C/°F.
  - Use Open-Meteo (free, no key): geocode the city, then fetch the current weather with coordinates rounded to ~1 km, at most every 30 min and only while Home is open.
  - Also request `daily=sunrise,sunset&timezone=auto&timeformat=unixtime` for the Automatic theme.
- **Look:** Appearance (Dark / Light / Automatic, where Automatic is light from sunrise to sunset), Accent glow, App names.
- **Projector & sleep:**
  - Shortcuts to the vendor's Picture, Brightness and Keystone screens. On JMGO they are `com.android.tv.settings/.partnercustomizer.picture.PictureActivity`, `…picture.JmgoLightActivity` and `com.jmgo.jmgosettinglib.module.keystone.ManualFittingActivity`.
  - A sleep timer, 15 min to 2 h, with a 1-minute warning. It turns the screen off through a device-admin receiver that has only the force-lock policy, approved once. Explain this to me before asking for it.
- **Reset everything:** says what it clears and preselects Cancel.

### Light mode

- Photos stay exactly as they are, with no brightening.
- Behind the clock and the app name, use soft radial fades in the left corners only, sized to the fade itself, instead of boxes.
- Dark mode dims photos slightly, drawn into the decoded bitmap once rather than as a layer.

### Performance on this GPU (measure with `dumpsys gfxinfo tv.clean.home`)

- Partial updates exist (EGL_KHR_partial_update), so size the focus-highlight view to the area it moves across, not the whole screen.
- Bake rounded corners into banner bitmaps at tile size, off the main thread. Avoid outline clips and fading edges, which create offscreen layers; use small gradient views instead.
- Make unused overlays INVISIBLE rather than just transparent, and drop solid backgrounds under full-screen photos.
- With a full-screen photo the GPU floor here is about 19 ms a frame. Tell me honestly if 60 fps isn't reachable.

## 4. Control panel on the ≡ button (everywhere, even inside apps)

A launcher can't see keys inside other apps, so this is an **AccessibilityService** with `canRequestFilterKeyEvents` and no window-content access. It draws a `TYPE_ACCESSIBILITY_OVERLAY` panel on the right using the same `MenuView`.

- `onKeyEvent`:
  - KEYCODE_MENU toggles the panel and is consumed.
  - While the panel is open, D-pad, OK and Back drive it. Home closes it and passes through.
  - Everything else passes through untouched.
- **Rows (7):**
  - **Volume** slider (STREAM_MUSIC, ±5%).
  - **Brightness** slider (see below).
  - **Inputs** ›.
  - **Wi-Fi** › (shows the network name).
  - **Sleep timer** (◀ ▶).
  - **Projector** › (Picture, Keystone).
  - **Settings**.
  The header shows the time and date.
- Keys the service consumes never get Android's key repeat, so repeat them in the service until the key is released:
  - Up/Down at `ViewConfiguration`'s rate, like Home's menus.
  - Left/Right on sliders after ~400 ms, then every ~110 ms.
- The remote's gear button goes nowhere once the Google TV launcher is disabled. On the K970AN it arrives as KEYCODE_NOTIFICATION, scancode 256. Log the key codes the service sees to find it on other remotes. Have the service catch it and open Settings from anywhere.
- **Tell me these trade-offs before building, and put them in the README:**
  - While any key filter is active, holding volume steps once per press instead of ramping. That's Android's behaviour. Volume keys never reach the service anyway.
  - **Never `am force-stop tv.clean.home`.** It silently removes the service from `enabled_accessibility_services`, and I then have to re-enable it in Settings › Accessibility. Reinstalling with `pm install -r` is fine.
  - Keys injected with `input keyevent` bypass the filter, so I must test the panel with the real remote.
- **Brightness on JMGO:** apps can't reach the `jmgomiddle-01` system service (SELinux).
  1. Bind `com.jmgo.hippo`'s service with action `action.jmgo.middleware.service`. Add `<queries>` for `com.jmgo.hippo` and `com.android.tv.settings`.
  2. You get `com.jmgo.middleware.service.IJmGOSystemService`; transaction 5 returns the DLP binder `com.jmgo.middleware.dlp.IJmGODlpManagerService` (set = 1 with key and value, get = 2 with key).
  3. Key 512 is the laser brightness, 0–100, which the vendor screen shows as steps 1–10 (value = (step−1)×11, step 10 = 100).
  4. Only ever send key 512. If the service isn't available, open the vendor Brightness screen instead.
- On other brands, use only settings and intents the device documents or exposes. Ask me before calling anything else.

## 5. HDR auto-dimming in dark scenes

On JMGO the cause is `com.jmgo.middleware.policy.BlackCurrentPolicy` in `com.jmgo.hippo`. After about 10 s of a near-black picture at full brightness, it halves the laser. It reads `Settings.System black_current_enable` (default 1) once at boot.

1. Fix it with `settings put system black_current_enable 0`, then reboot. Have me confirm it with a dark HDR scene.
2. Add `settings delete system black_current_enable` to `restore.ps1`.
3. Local Dimming and Dark Detail (`settings global tv_picture_video_*`) made no difference on the K970AN. Any dimness left over is normal HDR tone mapping.
4. On other models, look for a similar option in the device's own settings. Test one setting at a time and put each back if it doesn't help.

## 6. Photos for the slideshow (optional)

Write `tools\prepare-photos.ps1 -Source <folder> -Dest <folder>` that writes 1920×1080 JPEGs and never changes the originals:

- landscapes get a 16:9 cover crop biased slightly upward (y 0.4);
- portraits are centred on a blurred, scaled-up copy of themselves.

**Gotcha:** some projectors (the K970AN does) cut USB power while USB debugging is on, so the slideshow drive works only with debugging off.

Start now with step 1. Tell me what you need from me.
