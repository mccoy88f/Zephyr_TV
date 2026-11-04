# Contributing to Zephyr TV

Thanks for wanting to help. Zephyr TV is a fork of [Plume Launcher](https://github.com/aradhyas8/Plume_Launcher) by aradhyas8. It is a small project with one maintainer, so this page explains what fits, what doesn't, and how to send a change that can be merged quickly.

## What Zephyr TV is

Zephyr TV is a minimal home screen for Android TV and Google TV, which also works on Android phones and tablets with a finger. Every change is weighed against these principles:

- **Small and light.** A 1.2 MB APK, about 50–70 MB of RAM, no extra background services. One library only: Media3's ExoPlayer, for the Stremio add-ons' channels, in a player that runs in a process of its own.
- **Simple home screen.** One apps row and one Continue Watching row. Nothing else competes for attention.
- **Yours, not ours.** No ads, no sponsored or recommended content, no analytics, no account.
- **Quiet on the network.** Zephyr TV only goes online for the things listed under [Privacy](README.md#privacy). Anything new that connects to the internet must be optional and off until the user turns it on.

## Code map

All the code is in `app/src/main/java/link/mccoy88f/zephyr/`. The home screen is one activity; its state lives in `MainActivity.kt` and its functions are kept by topic in the files next to it:

| File | What it does |
|---|---|
| `MainActivity.kt` | Start-up, theme, wallpapers, apps row, All apps, screensaver, remote navigation |
| `SettingsPanel.kt` | Zephyr TV settings: sections, pages, on/off rows, layout lock |
| `Spotlight.kt` | TMDB Spotlight: title, arrows, app buttons, cycling |
| `WatchRow.kt` | Continue Playing, recommendations per app, cards and posters |
| `Addons.kt`, `Stremio.kt`, `PlayerActivity.kt` | Stremio add-ons' TV channels (rows, tiles, settings), the add-on client and cache, the player |
| `HomeSetup.kt` | Home button service, default launcher, guided setup |
| `Updates.kt`, `Updater.kt` | Update checks and installs (screen, then GitHub and the installer) |
| `WeatherView.kt`, `Weather.kt` | Weather under the clock (screen, then the services) |
| `PhoneScreens.kt`, `PhoneLink.kt` | Jobs done on a phone through a QR code (screens, then the small web server) |
| `TmdbClient.kt`, `BackdropCache.kt` | TMDB: trending, genres, trailers, providers, app links; backdrops on disk |
| `Http.kt`, `Prefs.kt`, `Problems.kt` | Network access, every settings key, the recent problems log |
| `Gallery.kt`, `HomeOverride.kt`, `Qr.kt`, `WallpaperUpload.kt` | Screensaver, Home key service, QR encoder, photo import |

New settings keys go in `Prefs.kt`. Anything that goes online uses `Http`, runs off the UI thread and reports failures with `warn()`.

## What's out of scope

These will be declined, however well they're built:

- Recommendation, trending or "discover" rows, and anything else that fills the home screen with content the user didn't ask for
- Ads, analytics, crash reporting services, or accounts
- New third-party libraries or SDKs (AndroidX included), besides the player's Media3
- Services, workers or alarms that run while Zephyr TV isn't on screen
- Online metadata services that need an API key, such as TMDB
- Extra home screen rows, widgets or sections beyond the apps row and the card row

Not sure if an idea fits? Open a feature request first and ask. That's much cheaper than finding out after the code is written.

## Before you start

- **Bug fixes:** go ahead and open a pull request. Link the issue if there is one.
- **Features:** open a [feature request](../../issues/new/choose) first and wait for a reply. Pull requests for features that weren't discussed may be closed.
- **Translations:** see [Translations](#translations) below.

## Pull requests

- **One change per pull request.** A translation, a bug fix and a new menu are three pull requests. Mixed pull requests are hard to review and will be asked to split.
- **Keep the diff small.** Match the style of the code around your change. Don't reformat or reorganise code you aren't changing.
- **Test on a real TV** (or an Android TV emulator if you have no TV) and say which device and Android version you used.
- **Mention size and memory changes.** If your change adds resources or keeps things in memory, give the APK size before and after.
- **No new permissions** without discussing them in an issue first.

By sending a pull request you agree that your contribution is licensed under the project's [GPL-3.0 licence](LICENSE).

## Translations

Translations are welcome. Shipping every language costs very little: each language adds a few KB to the APK and no memory, because Android only loads the strings for the language the TV is set to.

- A translation pull request adds or edits **only** `app/src/main/res/values-<code>/strings.xml`, for example `values-it/strings.xml`. No code changes, no new features.
- Translate every string in `app/src/main/res/values/strings.xml`. Keep placeholders such as `%1$s` and `%d` exactly as they are.
- Keep text short. TV screens are read from across the room, and long labels get cut off.
- If you add a language, please say whether you're happy to be asked about new strings later. Languages that fall far behind may be removed.

All of the text is in `res/values/strings.xml`, with the Italian translation in `res/values-it/strings.xml`. A new language is a new `values-xx` folder with the same keys.

## Reporting bugs

Use the [bug report form](../../issues/new/choose). The TV model, Android version and Zephyr TV version matter most, because many problems only happen on one manufacturer's TVs. A photo or video of the screen helps a lot.

## Building

See [Building from source](README.md#building-from-source) in the README.

## Screenshots

The README images in `docs/screens/` come from an Android TV emulator. To take them again, run the **Screenshots** workflow from the Actions tab: it commits the new images to the branch it runs on. The Spotlight images need the `ZEPHYR_TMDB_KEY` repository secret (a TMDB API key). The steps are in `scripts/screenshots.sh`.
