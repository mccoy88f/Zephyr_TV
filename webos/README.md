# Zephyr TV Receiver (webOS)

A webOS app for LG TVs that plays what Zephyr TV sends it. It has no add-ons and no search: link it once to your
Zephyr TV account, then select **Send to** in Zephyr TV's player on a phone, a tablet or another TV, and pick this TV.

- **Link:** at the first start the TV shows a code and its QR code. Scan it with your phone, or open
  ztv.mccoy88f.link/link and type the code, and allow the TV.
- **Receive:** the TV says what comes and from which device for 5 seconds (**Watch now**, **Cancel**), then plays it
  from where it got to.
- **Player:** as Zephyr TV's. OK pauses and plays, ◀ ▶ go back and forward 10 seconds, ▲ ▼ show the banner, Back stops.
- **Streams:** web addresses (MP4, MKV, HLS, DASH). A stream that needs its own headers (a User-Agent, a Referer)
  plays through the app's service, a small proxy on the TV (127.0.0.1); DASH plays straight, without those headers.
  Torrents and downloaded files can't be sent here: Zephyr TV says so.

## Install

`ZephyrTVReceiver.ipk` is attached to each [release](https://github.com/mccoy88f/Zephyr_TV/releases/latest).

- **Homebrew Channel** (rooted TVs): copy the .ipk to the TV and install it from Homebrew Channel, or with
  `luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{"id":"com.ares.defaultName","ipkUrl":"/tmp/ZephyrTVReceiver.ipk","subscribe":true}'`.
- **Developer Mode** (any TV with LG's Developer Mode app): install it with
  [webOS Dev Manager](https://github.com/webosbrew/dev-manager-desktop), or with LG's CLI:
  `ares-install --device <tv> ZephyrTVReceiver.ipk`.

## Build

```sh
npm install -g @webos-tools/cli
ares-package webos/app webos/service -o out
```

`app/` is the web app (no libraries: webOS 3 runs an old Chromium, so ES5); `service/` is its JavaScript service
(Node's own modules only, for the old Node of webOS 3 and 4).
