# Developer tools

Scripts for testing the launcher. Users don't need these, and the app doesn't depend on them.

Most need `$env:TV_HOST`, network debugging on, and `platform-tools\adb.exe` in the repo root.

| File | What it does |
|---|---|
| `tour.ps1`, `sheet.ps1`, `nav.ps1` | Screenshot every screen, make contact sheets, and remote helpers |
| `qa-measure.ps1`, `qa-soak.ps1`, `qa-monkey.ps1`, `qa-sound.ps1`, `qa-video.ps1` | Performance, stress, sound and video tests |
| `prepare-photos.ps1` | Makes 1920×1080 slideshow backgrounds |
| `promo.ps1`, `promo-lib.ps1` | Make promo images from raw screenshots in `shots\promo\raw`, which aren't in this repo |
| `make-sounds.py` | Renders the app's sounds. Needs Python with `numpy` and `soundfile` |
| `testapp/`, `testpack/` | A test app and a test icon pack |

Some tests use `run-as`, so they need a debug build. They save and change the launcher's settings, so back yours up first. Screenshots and logs go in `shots/` and `logs/`, which git ignores because they can show personal details.

Avoid `am force-stop` on the launcher: it turns the accessibility service off.
