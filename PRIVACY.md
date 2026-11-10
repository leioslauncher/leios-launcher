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
| `api.themoviedb.org` and `image.tmdb.org` (TMDB) | Only if you turn on **online title search** (off by default; asked once, the first time you search). Only in builds made with a TMDB key | What you type in search, the language and the region (your TV's country or the one you pick), and requests for title posters. Once a day, a request for this week's popular titles, and once a week about 30 requests for a list of well-known movies and shows (nothing typed in either), used to complete what you type and to find titles from the first letters of a word. When you pick a title, its TMDB number, to get its Wikidata number. The app's name is the only User-Agent. No account, no device details. Answers are cached on the TV for a day (posters and title numbers for a month), so the same search isn't sent twice |
| `www.wikidata.org` | Only with online title search on, and only when you **pick** a title in an app that can open titles directly (Netflix, Apple TV, Max, Prime Video) | That title's Wikidata number, to look up the app's own ID for it. Nothing you typed. The User-Agent names the app and this project's public address, as Wikimedia asks. Cached on the TV for a month |

Nothing else leaves the device through the app. If you never choose a city or the online background, and don't turn on online title search, the app makes no web requests. Searching apps and settings never leaves the TV.

## Permissions

| Permission or access | Why | Notes |
|---|---|---|
| Wi-Fi and network state | Shows your network's status and name | |
| Location (fine and coarse) | Android hides the Wi-Fi name without it | **The app never asks for your location and never reads it.** The setup script grants it over ADB. Without it, the name may simply not show |
| Photos and videos | Lists them for backgrounds and slideshows | Read on the device only. The picture you choose is copied into the app |
| Internet | The services above | |
| Close other apps | The "Close app" action | |
| Request to uninstall | The "Uninstall" action | Android asks you to confirm |
| Keep the screen awake | Buttons the ≡ panel takes count as using the TV | Held for about a second per press |
| Microphone | Search's mic key | Asked the first time you press it. Only while you're using it: the TV's speech recognition (on the tested projector, Google's speech services) listens once and gives Leios the words. Leios records nothing, keeps no audio, and sends none anywhere itself. What the speech service does with the audio is covered by its own maker's policy |

## Special access you turn on yourself

- **Accessibility (≡ panel):** it watches remote buttons so the panel works in any app. It also notices how long Home is held, so holding Home on Leios's Home can open search (the press itself goes on unchanged). It can't read screen content. When you've locked apps, it also sees which app came to the front, so it can show the PIN screen. It stores no record of this.
- **Notification access (bell):** it keeps notification titles and app names in memory to show the list on Home. They aren't saved to storage, and they don't leave the device.
- **Device admin (sleep timer):** only the "lock the screen" permission, used to turn the projector off.

## What's stored on the device

Your settings stay in the app's private storage: city and coordinates, app order, how often you open apps from Home, and a salted hash of your PIN, never the PIN itself. Android's cloud backup is turned off for this app.

**Search:** recent searches are kept on the TV only, never in backups. Turn them off or clear them in **Customization › System › Search & privacy**. Turning online title search off also deletes its cache.

**Backups:** saved backups are pictures in your device's Pictures folder, so other apps with photo access can open them. A backup holds your settings, including your city, usage counts and the PIN hash. Be careful who you share one with.

**Debug builds** may write a sound-playback note to the device log. Release builds don't.

## The setup kit

The optional scripts in `setup-kit/` run from your PC over ADB. They contact your TV and nothing else. Nothing in them sends data to this project.

## Questions

Please open an issue. For anything sensitive, follow [SECURITY.md](SECURITY.md).
