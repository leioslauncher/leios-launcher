# Privacy

Leios Launcher has no accounts, no ads, no analytics and no crash reporting. It has no server of its own, and its only library is AndroidX RecyclerView.

This describes the current code in `launcher/`.

## What leaves your device

The app contacts these services only for the features below. Each request also shows your IP address to that service, as any web request does.

| Service | When | What it sends |
|---|---|---|
| `geocoding-api.open-meteo.com` | When you search for a weather city | The text you typed |
| `api.open-meteo.com` | After you've chosen a city and weather is on: while Home is open, at most about every 30 minutes | The city's coordinates, rounded to about 1 km, and your temperature units |
| `api.wikimedia.org` and the Wikimedia image address it returns | Only if you choose the online photo-of-the-day background | Today's date, and a request for that picture |

Nothing else leaves the device through the app. If you never choose a city or the online background, the app makes no web requests.

## Permissions

| Permission or access | Why | Notes |
|---|---|---|
| Wi-Fi and network state | Shows your network's status and name | |
| Location (fine and coarse) | Android hides the Wi-Fi name without it | **The app never asks for your location and never reads it.** The setup script grants it over ADB. Without it, the name may simply not show |
| Photos and videos | Lists them for backgrounds and slideshows | Read on the device only. The picture you choose is copied into the app |
| Internet | The services above | |
| Close other apps | The "Close app" action | |
| Request to uninstall | The "Uninstall" action | Android asks you to confirm |

## Special access you turn on yourself

- **Accessibility (≡ panel):** it watches remote buttons so the panel works in any app. It can't read screen content. When you've locked apps, it also sees which app came to the front, so it can show the PIN screen. It stores no record of this.
- **Notification access (bell):** it keeps notification titles and app names in memory to show the list on Home. They aren't saved to storage, and they don't leave the device.
- **Device admin (sleep timer):** only the "lock the screen" permission, used to turn the projector off.

## What's stored on the device

Your settings stay in the app's private storage: city and coordinates, app order, how often you open apps from Home, and a salted hash of your PIN, never the PIN itself. Android's cloud backup is turned off for this app.

**Backups:** saved backups are pictures in your device's Pictures folder, so other apps with photo access can open them. A backup holds your settings, including your city, usage counts and the PIN hash. Be careful who you share one with.

**Debug builds** may write a sound-playback note to the device log. Release builds don't.

## The setup kit

The optional scripts in `setup-kit/` run from your PC over ADB. They contact your TV and nothing else. Nothing in them sends data to this project.

## Questions

Please open an issue. For anything sensitive, follow [SECURITY.md](SECURITY.md).
