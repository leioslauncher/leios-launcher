# Play Store readiness

> **Planning notes, not a promise.** There's no Play release and no date. This is research kept public so contributors can see what a Play version would need.

A planning document, written 2026-10-30, for making Leios Launcher a free, full launcher on Google Play for Android TV and Google TV. **It isn't a promise of a release or a date.** Nothing here has been changed in the app yet, and no Play account or enrolment exists.

Policies change often. Check each link again before acting on it. This is a practical reading of Google's pages, not legal advice.

**Status key:** OK / Needs work / Blocker. **Effort:** S (hours), M (days), L (a week or more, or uncertain).

## The short version

There are three real blockers, and the first decides whether a Play release is worth doing:

1. **Becoming Home without ADB.** Phase 2 testing found an official route that may work: Android's own "Default home app" screen and Home role exist on Google TV, though no menu shows them. It still needs a real selection test and a test on other devices. See section 1.
2. **JMGO's private service.** It must come out of the Play build.
3. **No TV launcher entry.** The app has no `LEANBACK_LAUNCHER` entry, which Google requires. This one is easy to fix.

Recommended first step: add a "Make Leios your Home" button that opens Android's own Home screen chooser, and test it on several Google TV devices before investing in the rest.

## 1. Becoming Home on Android TV and Google TV

### Test results, 2026-11-03 (JMGO N3 4K, Google TV, Android 14, build UKNV.260415.001)

| Test | What happened |
|---|---|
| Is Android's Home role there? | **Yes.** `cmd role get-role-holders android.app.role.HOME` works; the role exists |
| Is there a Home chooser screen? | **Yes, but hidden.** `android.settings.HOME_SETTINGS` opens Google's permission controller "Default home app" screen (`HomeSettingsActivity`). No Settings menu leads to it, but any app can open it with that intent. The "ask to become Home" dialog (`REQUEST_ROLE`) also exists |
| A fresh install, simulated | With Google TV's home enabled and Leios not chosen, Android **gave the Home role straight to Google TV**, with no prompt. Pressing Home showed Google TV's home |
| What the chooser offers | The "Default home app" screen then listed **both Google TV and Leios**, Google TV selected. It's operable with the D-pad. **Choosing Leios there wasn't tested** (not agreed for this round) |
| Accessibility override | Not tested; it would need new code |

Everything was put back afterwards: Leios is Home and Google TV's home is disabled again, as before the test.

### Every route an app from Play could use

| Route | Official? | Works on this device? | Policy risk | Recommendation |
|---|---|---|---|---|
| **A. Open Android's "Default home app" screen** (`Settings.ACTION_HOME_SETTINGS`) and let the user pick Leios | Yes: a [standard Android settings intent](https://developer.android.com/reference/android/provider/Settings#ACTION_HOME_SETTINGS) | The screen opens and lists Leios. **Picking it: untested** | None found: the user makes the choice in Android's own screen | **First choice.** Test picking, then test after a reboot and a Google TV update |
| **B. Ask for the Home role** with [`RoleManager.createRequestRoleIntent(ROLE_HOME)`](https://developer.android.com/reference/android/app/role/RoleManager) | Yes, Android 10+ | The dialog exists. **Untested** | None found | Try alongside A; use whichever works |
| **C. Home picker on Home press** | Standard Android | **No.** Google TV took the role silently | None | Don't rely on it |
| **D. Accessibility override** (catch Home, bring Leios forward; what [Projectivy uses](https://www.androidauthority.com/google-tv-ads-projectivy-launcher-3573626/)) | No | Untested. [A 2026 Google TV update broke it for some users](https://www.androidauthority.com/google-tv-update-blocks-projectivy-override-3649445) | **High.** Google bars using accessibility to [work around Android protections](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en). Others pass review, but that isn't approval | Only as a last resort, opt-in, fully disclosed |
| **E. Button-remap apps** (Button Mapper, tvQuickActions) | No | Not tested | Same as D, for those apps | Mention in help only |
| **F. Open Leios as a normal app** | Yes | Yes, once `LEANBACK_LAUNCHER` is added | None | Always offer, as the fallback |
| **G. Device owner / kiosk** | Yes, managed devices only | Needs a factory reset and provisioning | n/a | Not for Play users |

**Not known:** whether Google TV keeps a user's choice from route A after reboots and system updates, and whether other Google TV and Android TV devices have the same hidden screen. Google TV may also hide or change it in future firmware, because it isn't documented for TV.

## 2. Features that depend on ADB, settings or the device

| Feature | Depends on | Status | Proposed change | Effort |
|---|---|---|---|---|
| Set as Home | ADB | Needs work | Section 1: route A or B, tested on more devices | M |
| Wi-Fi name | ADB-granted location | Needs work | Show "Connected" instead (section 4) | S |
| Screensaver | ADB `settings put secure screensaver_components`; Google TV has no menu for it, per our notes | Needs work | Keep it, but document that it may not be selectable. Don't advertise it on Play | S |
| HDR fix, debloat, Private DNS | ADB, setup kit | OK | Already separate from the app. Never mention them in the Play listing | S |
| Forgotten PIN | ADB broadcast to `Lock$Reset` | Needs work | Most Play users won't have ADB. Add a recovery path, or warn clearly that clearing app data is the reset | M |
| Brightness, Picture, Keystone | JMGO | Blocker | Section 5 | M |
| Inputs | Device support | OK | Row already hides when there are none. Test it | S |
| Gear key | A remote-specific scancode | Needs work | Treat as a JMGO-only extra | S |
| Android 9 to 12 paths | Untested | Needs work | Test on real or emulated devices | M |

## 3. Accessibility service (≡ panel, PIN and key handling)

| Item | Status | Finding | Proposed change | Effort |
|---|---|---|---|---|
| Does our use qualify? | Needs work | Allowed, with conditions. The app isn't a disability tool, so it **must not** set `isAccessibilityTool`, because [misuse is a violation](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en) | Keep that flag off | S |
| Disclosure and consent | **Needs work** | Apps not declared as accessibility tools need a [clear in-app disclosure, affirmative consent and a Play Console declaration](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en). The service description text can't replace the disclosure | Add a full-screen explanation with a clear "Agree" before sending the user to Settings. List what it sees: key presses and, when apps are locked, which app came forward | M |
| Scope | OK | `panel_service.xml` has `canRetrieveWindowContent="false"`, and no text is read. `onKeyEvent` handles only its own keys | Keep it minimal | S |
| Overlay on other apps | Needs work | [TV-TR](https://developer.android.com/docs/quality-guidelines/tv-app-quality) says an app must not partially obscure other apps. The panel does exactly that on request | Ask in review, or make the panel an opt-in extra | S |
| Search triggers | Needs work | Only **holding Home** needs the service: it watches how long Home is held (never takes or delays the press) and opens search only when Leios's Home is in front. The Search button, Up at the top of Home and holding OK on the top bar work without it; hold ≡ / hold Settings → "Open search" need it, like all remote shortcuts | List "holding Home on Home opens search" in the disclosure; everything still works without the service | S |
| PIN over locked apps | Needs work | Watching which app comes forward is sensitive, even though it's stored nowhere. It's also easy to bypass, so it can't be sold as security | Keep it off by default, and describe it as a household control | S |
| Alternatives | | No narrower Android API catches a button inside other apps. Google says to [prefer narrower APIs where possible](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en). The fallback is a Play build where quick settings live on Home only, with no service at all | Build the app so the service is optional; Home must work fully without it | M |

## 4. Permissions and declared components

| Item | Used for | Status | Finding | Proposed change | Effort |
|---|---|---|---|---|---|
| `ACCESS_FINE_LOCATION` | Wi-Fi name only | **Needs work** | Never requested at runtime, so it does nothing on a Play install. The [location rules](https://support.google.com/googleplay/android-developer/answer/18100766?hl=en) require minimum scope and, from January 27, 2027, a declaration for fine location | **Remove it** | S |
| `ACCESS_COARSE_LOCATION` | Same | Needs work | Same | **Remove it** | S |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `INTERNET` | Status, weather, photo of the day | OK | I found no restriction on these for this use | Keep | S |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | Backgrounds, slideshow | **Needs work** | Allowed only [when the photo picker isn't enough](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en), with a declaration. Occasional picking wouldn't qualify, and whether the system picker works on TVs is unverified. USB slideshows may be the strongest case | Test the picker on TV. If it's missing, file a declaration; if refused, drop folder slideshows from the Play build | M |
| `READ_EXTERNAL_STORAGE` (to API 32) | Same, older Android | Needs work | Same reasoning | Follow the decision above | S |
| `KILL_BACKGROUND_PROCESSES` | "Close app" | Needs work | Not found in the restricted list, but its value is low | Consider removing | S |
| `REQUEST_DELETE_PACKAGES` | "Uninstall" | OK | Android asks the user to confirm. No restriction found | Keep; re-check before submitting | S |
| Device admin (`force-lock`) | Sleep timer | **Needs work** | I found no current Play page specific to this, so the rule is unverified. Admin apps draw extra scrutiny | Replace with the accessibility service's lock action, or drop screen-off from the Play build | M |
| Notification access | The bell | Needs work | User-granted. I found no specific Play rule. It's data in memory only, and must be covered in the Data safety form review | Keep off by default. Re-check policy | S |
| `<queries>` package visibility | App grid, icon packs | OK | Finite queries, and we don't use `QUERY_ALL_PACKAGES`, which [needs a declared core purpose](https://support.google.com/googleplay/android-developer/answer/10158779?hl=en). A launcher's grid may need more on some devices | Keep. Test that phone-only sideloaded apps still appear | S |
| `com.jmgo.hippo` query | JMGO features | Blocker | Section 5 | Remove from the Play build | S |
| `Lock$Reset` receiver | PIN reset | OK | Protected by `DUMP`, which only the system and ADB hold | Keep | S |

## 5. JMGO's private service

| Item | Status | Finding | Proposed change | Effort |
|---|---|---|---|---|
| `JmgoDlp.java` binds to `com.jmgo.hippo` | **Blocker** | It's an undocumented vendor service, and the interface was found by studying JMGO's app. Google bars [accessing services or APIs against their terms, or interfering with other apps](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en). I don't know JMGO's terms, and the policy doesn't mention vendor services directly, so the risk isn't settled | Leave it out of the Play build with a Gradle product flavor, keeping it in the side-loaded build | M |
| JMGO shortcuts (Picture, Keystone) | Needs work | These open JMGO's own screens | Also leave out of the Play build, unless they use a public intent | S |
| Non-JMGO behaviour | **Needs work** | Rows are meant to hide, but this has never been tested | Test on at least two other devices | M |

## 6. Packaging and TV quality

| Item | Status | Finding | Proposed change | Effort |
|---|---|---|---|---|
| Target API | OK | TV apps must [target API 34 or higher](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en); we target 34. Phone apps need 36, which doesn't apply to a TV-only release. Newer Android versions may not offer an app with an old target | Raise to 35 or 36 anyway, with a full re-test | M |
| Min SDK | OK | [TV-PS](https://developer.android.com/docs/quality-guidelines/tv-app-quality) needs 31 or lower; we use 28 | Keep | S |
| App Bundle | Needs work | [AABs are mandatory for TV apps](https://developer.android.com/docs/quality-guidelines/tv-app-quality). We've built APKs only | Build with `bundleRelease`, and test it | S |
| 64-bit and 16 KB pages | Likely OK | [Required from August 1, 2026](https://developer.android.com/training/tv/publishing/distribute). The app is Java with no native code of its own | Confirm when the bundle is built | S |
| Memory and code rules | Unknown | Google announced [new quality requirements for February 2027](https://support.google.com/googleplay/android-developer/answer/18100766?hl=en) | Read them when planning the release | S |
| `LEANBACK_LAUNCHER` | **Blocker, easy** | [TV-ML and TV-LM](https://developer.android.com/docs/quality-guidelines/tv-app-quality) require it. `MainActivity` only has `HOME` | Add the launcher entry, and handle being opened as a normal app, including Back ([TV-DB](https://developer.android.com/docs/quality-guidelines/tv-app-quality)) | S |
| Banner and icon | **Needs work** | `banner.xml` is 320x180 and shows the Leios name since 1.2, but it's used as the icon too. [TV-LB](https://developer.android.com/docs/quality-guidelines/tv-app-quality) needs a proper icon, and [TV-BN](https://developer.android.com/docs/quality-guidelines/tv-app-quality) needs the name on the banner. Play guidance [gives a different icon size](https://support.google.com/googleplay/android-developer/answer/18058043?hl=en), so check Play Console | Design a Leios banner and icon | M |
| App name inside the app | OK (1.2) | Renamed to Leios in 1.2. Old `.cleanhome.png` backups stay restorable | None | S |
| D-pad, Menu button | OK | D-pad throughout. The ≡ key is an extra, so [TV-DM](https://developer.android.com/docs/quality-guidelines/tv-app-quality) looks fine: everything is reachable from Customize. Confirm that | Check it | S |
| Touchscreen and hardware features | OK | All marked `required=false` ([TV-MT](https://developer.android.com/docs/quality-guidelines/tv-app-quality)) | Remove the location features along with the permissions | S |
| Leanback declaration | OK | `android.software.leanback` is `required=false`. That's fine for an app also usable elsewhere. TV-only behaviour should be decided | Decide on TV-only, then set it | S |
| Store listing | Needs work | [Needs a TV screenshot and banner, and "Android TV" in the description](https://developer.android.com/training/tv/publishing/distribute). Our screenshots show other companies' logos | Make new screenshots with neutral test apps, and no comparisons to other brands | M |
| GPL on Play | Likely OK | [VLC for Android is GPLv3 on Play](https://torrentfreak.com/google-wont-take-down-pirate-vlc-with-five-million-downloads-180206/). I found no official Google statement. Publish the source matching each release | Tag each release | S |
| Name clearance | Needs work | Only web and Play searches so far | Search USPTO and EUIPO before any listing | M |

## 7. Data safety and privacy policy

| Item | Status | Finding | Proposed change | Effort |
|---|---|---|---|---|
| Data safety form | Needs work | [Declare your data use](https://developer.android.com/privacy-and-security/declare-data-use). Likely answers: approximate location or city text is sent to Open-Meteo to deliver weather, optional, and not stored by us. No ads, analytics, sale or accounts | Fill it in from `PRIVACY.md`, and re-check how Google treats rounded coordinates | S |
| Data safety: online title search | Needs work | Optional and off by default. With the user's yes, the search text, language and region go to TMDB (a third party) to deliver results; posters are fetched from TMDB too. Nothing is linked to an identity, no account, not stored by us; cached on the device for a day (posters a month). Recent searches stay on the device and out of backups. Likely Data safety answers: "App activity › In-app search history" **shared** with TMDB for app functionality, optional, not collected by the developer, encrypted in transit. A Play build would also need TMDB's logo with its credit, and must stay free (TMDB's terms forbid commercial use without a deal) | Re-check the category wording against the form when filling it in | S |
| Privacy policy URL | Needs work | Expected for this kind of app, though my search didn't confirm the exact rule | Host `PRIVACY.md` at a stable public address | S |
| Backup pictures | Needs work | They're readable by other apps with photo access, and hold the city and PIN hash | Leave out the PIN hash and coordinates, or warn before saving | M |

## 8. Signing

**Where we are:** the public version uses a new release key, `leios-release.jks`, kept outside the repo. Release builds never use the debug key, which [app stores don't accept](https://developer.android.com/studio/publish/app-signing).

**How Play signing would work** (explanation only; nothing is enrolled):

- **App signing key:** the key users' devices trust. With [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en), Google holds it and signs what users download.
- **Upload key:** the key you use to prove an upload came from you. If it's lost or stolen, [you can ask Google to reset it](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en). It doesn't change what users receive.

**The recommended key plan:**

1. Give Google `leios-release.jks` as the app signing key when first setting up. [This choice must be made before the first release reaches open testing or production](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en). Then GitHub APKs and Play installs share one signature, so they can update each other.
2. Create a **separate upload key** for Play, and keep it apart from the release key.
3. Keep both offline-backed-up, with passwords in a password manager.

If you'd rather let Google generate the Play key, that's simpler, but GitHub and Play installs then can't update each other.

**Lost keys:** a lost upload key can be reset. If your own release key is lost, GitHub users can't get updates to their install, and Google's copy can't help them.

**Android developer verification:** Google now [ties each package name to its signing key](https://developer.android.com/developer-verification/guides), and [Play packages must be registered](https://support.google.com/googleplay/android-developer/answer/16761053?hl=en). Enforcement starts in some countries in 2026 and goes global from 2027. Google's own pages differ on some details, so check the current flow. Plan to register `tv.leios.launcher` with the release key when you set up the account. Sideloading over ADB [stays available to developers](https://developer.android.com/developer-verification/guides/faq), and the setup kit depends on that.

## 9. Developer account

| Item | Status | Finding | Effort |
|---|---|---|---|
| Registration | Not started | [US$25 one-time fee, with identity checks](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en). Your legal name and possibly government ID are needed. Developer details can be shown publicly, so check that before signing up | S |
| Device check | Not started | [Personal accounts verify an Android device](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en) with the Play Console mobile app | S |
| Closed testing | **Needs work** | New personal accounts need [at least 12 testers opted in for 14 days in a row](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) before applying for production. Google may ask for more testing. Testers need a TV device, which makes this harder | L |
| Trader status | Not started | [Must be declared from November 2026](https://support.google.com/googleplay/android-developer/answer/18100766?hl=en) | S |
| TV opt-in and review | Not started | [TV is a separate opt-in with its own review](https://developer.android.com/training/tv/publishing/distribute) | S |

## 10. Not verified

- An official Google statement on whether launchers may replace Home on Google TV, and how Projectivy passes review. The hidden "Default home app" screen was found working on one device only.
- Play rules specific to device admin and notification access on TV.
- Whether the system photo picker works on TV devices.
- Whether JMGO has terms covering its service.
- Trademark clearance of the name.
- Whether each streaming app really opens its search from Leios (read from the apps' declared intents only; not yet tried on hardware).

## 11. Recommended plan

| Phase | Goal | Done when |
|---|---|---|
| 2 | Go public on GitHub, with the first signed release | Repo public, release tagged, project page updated |
| 3 | Decide if Play is viable | A "Make Leios your Home" button using Android's own chooser (section 1, routes A and B) works on several Google TV devices and survives reboots; accessibility override only if that fails |
| 4 | A Play-ready build | `play` flavor without JMGO code or location, `LEANBACK_LAUNCHER`, new banner, disclosure screen, renamed app, target API raised, optional accessibility |
| 5 | Wider testing | Tested on at least two non-JMGO devices, Android 9 to current |
| 6 | Accounts and closed test | Developer account, trademark search, signing set up, 12 testers for 14 days |
| 7 | Release | Declarations filed, TV review passed, production |

Phase 3 comes first because its result can change everything after it.
