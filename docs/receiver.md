# Zephyr TV Receiver

An app for TVs that can't run Zephyr TV (LG with webOS, Hisense with VIDAA) and play what Zephyr TV sends them. It
has no add-ons and no search: link it once to your Zephyr TV account, then select **Send to** in Zephyr TV's player
on a phone, a tablet or another TV, and pick this TV.

- **Link:** at the first start the TV shows a code and its QR code. Scan it with your phone, or open
  ztv.mccoy88f.link/link and type the code, and allow the TV.
- **Receive:** the TV says what comes and from which device for 5 seconds (**Watch now**, **Cancel**), then plays it
  from where it got to. With nothing sent, it shows **Ready to receive**.
- **Player:** as Zephyr TV's. OK pauses and plays, ◀ ▶ go back and forward 10 seconds, ▲ ▼ show the banner, Back stops.
- **Streams:** web addresses (MP4, MKV, HLS, DASH), with the formats the TV itself decodes. Torrents and downloaded
  files can't be sent to a Receiver: Zephyr TV says so.
- **Headers:** some streams need their own headers (a User-Agent, a Referer). On webOS they play through the app's
  service, a small proxy on the TV (127.0.0.1); DASH plays straight, without them. VIDAA has no such service: Zephyr TV
  doesn't send those streams to it, and says so.

## LG (webOS)

`ZephyrTVReceiver.ipk` is attached to each [release](https://github.com/mccoy88f/Zephyr_TV/releases/latest).

- **Homebrew Channel** (rooted TVs): install the .ipk from Homebrew Channel, or with
  `luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{"id":"com.ares.defaultName","ipkUrl":"/tmp/ZephyrTVReceiver.ipk","subscribe":true}'`.
- **Developer Mode** (LG's Developer Mode app): install it with
  [webOS Dev Manager](https://github.com/webosbrew/dev-manager-desktop), or with LG's CLI:
  `ares-install --device <tv> ZephyrTVReceiver.ipk`.

## Hisense (VIDAA)

On VIDAA an app is a web page with a name and an icon. The Receiver's are:

| | |
|---|---|
| Name | Zephyr TV Receiver |
| Address | `https://ztv.mccoy88f.link/receiver/index.html` |
| Icon | `https://ztv.mccoy88f.link/receiver/icon.png` (400 × 400) |

Hisense doesn't let anyone add an app to a TV. The community has found ways, which change with the firmware; none is
Hisense's, and each is at your own risk:

- **Older firmware** (VIDAA 4 and before): in the TV's browser, open `hisense://debug` and add the name and the address.
- **Newer firmware:** [vidaa-appstore](https://github.com/trialuser/vidaa-appstore) answers for vidaahub.com on your
  network (its DNS server, set as the TV's DNS) and installs the app from that page with the name, address and icon above.
- **Sidee:** [Sidee](https://github.com/JohnCarter2021/Sidee) pairs with the TV from a computer (a PIN on the TV) and adds
  launcher tiles.

## Build

```sh
npm install -g @webos-tools/cli
webos/package.sh out
```

The web app is in `site/public/receiver/` (the site serves it to VIDAA TVs; ES5, as webOS 3 runs an old Chromium);
webOS's own files (`appinfo.json`, its icons) are in `webos/app/`, its service in `webos/service/` (Node's own modules
only, for the old Node of webOS 3 and 4). `webos/package.sh` puts them together.
