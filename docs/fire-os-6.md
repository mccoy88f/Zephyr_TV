# Android 7 and Fire OS 6

[Guida completa in italiano e frasi vocali verificate](fire-os-6-it.md).

This change targets API 24 and later. Android 8-only features are skipped on older
systems: system Watch Next/preview channels and adaptive-icon foreground layers.
The gallery uses a software canvas there. Dynamic shortcuts start at API 25.
Core-library desugaring supplies Java time and NIO APIs on Android 7.

## Optional Fire OS 6 Home redirect

Amazon remains the system launcher. The opt-in `HomeKeeper` observes local usage
events and brings Zephyr's own existing task to the front when the exact Amazon
Home activity appears. Normal polling does not redirect Settings or search.
At service startup, Zephyr is also opened when the most recent foreground event
is absent or belongs to Amazon's launcher package; this startup fallback can
therefore replace an Amazon search screen present at that moment.
It does not disable system packages, acquire a wake lock, use root or send usage
events over the network. It does poll while the display is interactive.

Enable usage access for the package actually installed, then open **Zephyr TV
Home manager** from the app list and select **Use Zephyr as Home**:

```sh
adb shell appops set link.mccoy88f.zephyr.debug GET_USAGE_STATS allow
```

The command above is for the debug build. The release package has no `.debug`.
Home redirection is disabled until explicitly enabled in the manager. On a reboot
the service starts from BOOT_COMPLETED; other receivers can delay delivery.

**Pause and open Amazon Home** stops the service and persists the disabled state.
For emergency recovery from a computer:

```sh
adb shell am force-stop link.mccoy88f.zephyr.debug
adb shell appops set link.mccoy88f.zephyr.debug GET_USAGE_STATS default
adb shell input keyevent KEYCODE_HOME
```

Debug builds do not offer official release APKs as in-place updates: those use a
different package and can have a different minimum SDK.

## Voice routing is not an Alexa replacement

When Android delivers SEARCH, ASSIST or VOICE_ASSIST to Zephyr, it opens Zephyr's
search. Dictation requires an installed Android RecognitionService. A standard
ACTION_SEARCH with a query can also be sent explicitly to MainActivity.

Recognized exact app names, optionally prefixed with `open` or `apri`, open the
installed app. Other words are searched as content; `search`/`cerca` is stripped.
Queries do not become shell commands.

Stock Fire OS 6.7.1.1 on the tested AFTMM exposes no Android RecognitionService.
Its physical microphone button is routed by Amazon to Alexa/search. These changes
do not intercept Amazon's private audio path or claim that Alexa hands its query
to third-party apps. The missing-service message keeps keyboard/phone search
available rather than pretending to have heard speech.

There is a separate, optional [experimental voice bridge](../voice-bridge/README.md)
for Alexa's existing YouTube integration. It forwards already-transcribed queries
to Zephyr without overriding the microphone. Unlike the main Zephyr APK, it must
replace the installed YouTube/SmartTube Amazon bridge package and has its own
installation and recovery constraints. Physical Alexa search and app launch
were verified with that companion on the tested Fire TV.

Amazon documents the microphone button as a system-wide action that apps cannot
override: [general FAQ](https://developer.amazon.com/docs/fire-tv/faq-general.html)
and [controller guidelines](https://developer.amazon.com/docs/fire-tv/controller-behavior-guidelines.html).

## Validation

Physical device: Amazon AFTMM, Fire OS 6.7.1.1, Android 7.1.2 / API 25.
The official API-26 APK was rejected with INSTALL_FAILED_OLDER_SDK; this debug
build installed and rendered the launcher. Home redirection and the recovery
manager were exercised. Explicit ACTION_SEARCH opened Stremio for `apri Stremio`
and displayed internal content results for `cerca Inception`.
Without the optional companion, the physical microphone and an injected SEARCH
key still opened Amazon results.
After a reboot, the opted-in foreground service and Zephyr returned automatically
without manually launching the app (observed about 143 seconds after boot).

The JVM suite has 106 passing tests. Debug assembly and lint completed (lint has
existing warnings). The selected FireTvCompatibilityDeviceTest passed on API 25:
it replaces playback progress in a temporary directory and exercises desugared
Java time without changing the user's history. API 24 has not been tested on
physical hardware. Playback, every add-on and every device model are not covered
by these checks.
