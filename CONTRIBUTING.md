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

## Translating Leios

Leios is ready to translate, but has no translations yet. Every word on screen is in `launcher/app/src/main/res/values/strings*.xml`, one file per area:

| File | What's in it |
|---|---|
| `strings.xml` | Shared words: remote-button hints, On/Off, separators, the top bar |
| `strings_home.xml` | Home, the first-run tip, moving apps, hold OK on an app |
| `strings_settings.xml` | Every setting's name, its description, and words people search for it by |
| `strings_customize.xml`, `strings_background.xml`, `strings_home_layout.xml`, `strings_look.xml`, `strings_projector.xml`, `strings_lock.xml`, `strings_system.xml` | Customization's sections |
| `strings_search.xml` | Search, voice and the search keyboard |
| `strings_panel.xml` | The ≡ panel |

To add a language:

1. Copy each `strings*.xml` into a new folder named for the language, such as `res/values-es/` (Spanish) or `res/values-pt-rBR/` (Brazilian Portuguese). Translate the text between the tags. Leave the `name="…"` attributes alone.
2. Read the comment above a string. It says where the text shows and what each placeholder holds.
3. Keep every placeholder: `%1$s` is text (a name), `%d` and `%1$s` in counts are numbers. Their order can change (`%2$s … %1$s`), but they must all stay.
4. `<plurals>` hold one line per plural form. Add the forms your language uses (`zero`, `one`, `two`, `few`, `many`, `other`), and drop the ones it doesn't.
5. Strings marked `translatable="false"` stay as they are: the app's name, `OK` (it's printed on remotes), units such as °C, and service names.
6. Leave out anything you haven't translated. Android shows English for it.
7. Add your language to `res/xml/locales_config.xml`, so Android 13 and later offer it in the app-language settings.

Some things need more than text:

- **The search keyboard** (`search_keyboard_rows`) has 6 rows of 7 keys. A language with up to about 35 letters can list its own. Scripts with many more characters (Chinese, Japanese, Korean) or with letters that change shape (Arabic, Hindi) need a different keyboard, which isn't built yet. Voice search works in any language the TV's speech recognition knows.
- **Search words** (`…_words`) are separated by commas. Use the words people say in your language. They don't need to translate the English ones.
- **Right-to-left languages** (Arabic, Hebrew, Persian) mirror most of Leios. Moving the remote's left and right isn't mirrored yet, and nor are a few screens drawn by hand: the test patterns, the search keyboard and the menu sliders.

### Checking a translation

Build with Android's pseudo-languages to see whether text fits and layouts mirror:

```powershell
cd launcher
.\gradlew.bat assembleDebug -Ppseudo
```

Then pick **English (XA)** (long, accented text) or **Arabic (XB)** (right to left) in the TV's language settings. Never publish a `-Ppseudo` build.

## Before you open a pull request

```powershell
cd launcher
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Say what you tested, and on which device. If you couldn't test on real hardware, say so.

## Keep it private

Don't commit keystores, passwords, `keystore.properties`, IP addresses, Wi-Fi names, device dumps or screenshots that show personal details. Scripts read the TV's address from `$env:TV_HOST`.
