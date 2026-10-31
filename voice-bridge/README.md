# Experimental Fire TV voice bridge

[Guida in italiano: configurazione, frasi vocali, limiti e ripristino](../docs/fire-os-6-it.md).

An optional companion APK, separate from Zephyr. It receives the YouTube search
request which Alexa has already transcribed, then forwards the text to Zephyr or
SmartTube. It has no microphone, Internet, accessibility or usage-stats permission,
and no background service. Logs record the destination, not the spoken query.

This is an independent protocol implementation, not an official SmartTube,
YouTube or Amazon release. The request format is documented in SmartTube's
[IntentExtractor](https://github.com/yuliskov/SmartTube/blob/master/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/utils/IntentExtractor.java).
SmartTube's developer describes the existing YouTube bridge workaround in
[issue 5877](https://github.com/yuliskov/SmartTube/issues/5877).

## Important installation constraint

Alexa addresses `com.amazon.firetv.youtube`, so this APK must occupy that package.
It **cannot coexist with the Amazon YouTube app or the original SmartTube Amazon
bridge**. It does not replace SmartTube itself. A different signing key means the
previous package must be uninstalled, not updated in place. Uninstalling removes
that package's data. Save the installed APK and any important data before testing;
do not remove a system app or use root for this prototype.

On the tested Fire TV the existing package was already SmartTube's small bridge,
not the official YouTube client. Its APK was backed up and can be restored.

Build and test from the repository root:

```sh
./gradlew :voice-bridge:assembleDebug :voice-bridge:testDebugUnitTest :voice-bridge:lintDebug
```

Output: `voice-bridge/build/outputs/apk/debug/voice-bridge-debug.apk`.
The min SDK is 24. Only Fire OS 6 / API 25 has been tested physically.

## Usage

Open **Zephyr / SmartTube voice bridge (test)** from the app list. The default
destination is SmartTube; choose **Use Zephyr for searches** to change it.
The setting is stored immediately. Zephyr must be the build in this PR with
ACTION_SEARCH support. When both debug and release builds exist, debug is chosen.

- Say **“cerca Inception su YouTube”**: with Zephyr selected, its content results open.
- Say **“cerca Stremio su YouTube”**: an exact installed-app name opens that app.
- Prefix the search words with **“SmartTube”** to override the destination for one
  search, or select SmartTube again in the bridge settings. “Zephyr” (also
  “zephir”, “zefir”, “zefiro”) overrides in the opposite direction.
- Standard video and channel links without search text keep going to SmartTube.

Alexa still owns the microphone and speech recognition. The phrase **“on YouTube”
is required** to use its existing integration. Installing this APK does not teach
Alexa the name “Zephyr”, remap the microphone button, or enable every Alexa skill.
Availability can differ across Fire OS versions, languages and Alexa updates.
Prefix overrides are unit-tested, not physically voice-tested.

## Recovery

For normal use, select SmartTube in the bridge settings. To restore the original
YouTube/bridge APK, uninstall **only this companion package**, then install your
saved original APK. The signatures differ, so `adb install -r` is insufficient.
Do not uninstall Zephyr or SmartTube to change the search destination.

## Validation on 2026-10-30

Amazon AFTMM, Fire OS 6.7.1.1, Android 7.1.2 / API 25:

- 15 pure routing assertions passed via a JUnit test; debug assembly and lint
  completed (lint warnings remain).
- Direct YouTube search intents opened SmartTube with its setting selected,
  Zephyr with its setting selected, and Stremio for `apri Stremio`.
- A physical microphone test of “cerca Inception su YouTube” was confirmed by the
  tester and by the system-UID launch of the bridge, ACTION_SEARCH delivery to
  Zephyr, and the displayed Inception results.
- A second physical microphone test of “cerca Stremio su YouTube” opened Stremio;
  the tester confirmed it stayed open.

This companion remains a prototype. There is no physical API-24 test, no claim of
cross-device Alexa compatibility, and no end-to-end voice test of every routing
option or direct video playback path.
