# Zephyr TV Receiver

An app for TVs that can't run Zephyr TV (LG with webOS, Samsung with Tizen, Hisense with VIDAA) that plays what
Zephyr TV sends them. It
has no add-ons and no search: link it once to your Zephyr TV account, then select **Send to** in Zephyr TV's player
on a phone, a tablet or another TV, and pick this TV.

- **Link:** at the first start the TV shows a code and its QR code. Scan it with your phone, or open
  ztv.mccoy88f.link/link and type the code, and allow the TV.
- **Receive:** the TV says what comes and from which device for 5 seconds (**Watch now**, **Cancel**), then plays it
  from where it got to. With nothing sent, it shows **Ready to receive**.
- **Player:** as Zephyr TV's. OK pauses and plays, ◀ ▶ go back and forward 10 seconds, ▲ ▼ show the banner, Back stops.
- **Streams:** web addresses (MP4, MKV, HLS, DASH), with the formats the TV itself decodes. Torrents and downloaded
  files can't be sent to a Receiver: Zephyr TV says so.
- **Headers:** some streams need their own headers (a User-Agent, a Referer). On webOS and on Tizen through TizenBrew
  they play through the app's service, a small proxy on the TV (127.0.0.1); DASH plays straight, without them. VIDAA
  has no such service: Zephyr TV doesn't send those streams to it, and says so. Nor has a Tizen .wgt installed by
  itself: there, those streams don't play.

## LG (webOS)

`ZephyrTVReceiver.ipk` is attached to each [release](https://github.com/mccoy88f/Zephyr_TV/releases/latest).

- **Homebrew Channel** (rooted TVs): install the .ipk from Homebrew Channel, or with
  `luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{"id":"com.ares.defaultName","ipkUrl":"/tmp/ZephyrTVReceiver.ipk","subscribe":true}'`.
- **Developer Mode** (LG's Developer Mode app): install it with
  [webOS Dev Manager](https://github.com/webosbrew/dev-manager-desktop), or with LG's CLI:
  `ares-install --device <tv> ZephyrTVReceiver.ipk`.

## Samsung (Tizen)

Samsung TVs from 2017 (Tizen 3.0 and later).

- **TizenBrew** (recommended): install [TizenBrew](https://github.com/reisxd/TizenBrew) on the TV (its installer for the
  computer, with the TV's Developer Mode on; on Tizen 7 and later it asks for a Samsung account, to make the
  certificate). Then in TizenBrew's module manager select **Add GitHub Module** and enter `mccoy88f/Zephyr_TV`. The
  module is the released Receiver, with its service: streams that need their own headers play too.
- **Tizen Studio**: `tizen/package.sh <certificate profile>` makes a signed .wgt (a Samsung certificate with your TV's
  DUID, made in Tizen Studio's Certificate Manager); install it with `tizen install -n <file>.wgt -t <TV>` with the TV's
  Developer Mode on. Installed this way it has no service: streams that need their own headers don't play.

## Hisense (VIDAA)

On VIDAA an app is a web page with a name and an icon. The Receiver's are:

| | |
|---|---|
| Name | Zephyr TV Receiver |
| Address | `https://ztv.mccoy88f.link/receiver/index.html` |
| Icon | `https://ztv.mccoy88f.link/receiver/icon.png` (400 × 400) |

Hisense doesn't let anyone add an app to a TV. The community has found ways, which change with the firmware; none is
Hisense's, and each is at your own risk:

- **Older firmware** (VIDAA 4 and before): in the TV's browser, open `hisense://debug` and fill in **App Name** and
  **App Url** (the icons are optional).
- **VIDAA DevKit:** in the VIDAA Store, open the search and type `27753790` on the remote: the DevKit app installs.
  In its App Manager, add the address and the name.
- **Newer firmware:** [vidaa-appstore](https://github.com/trialuser/vidaa-appstore) answers for vidaahub.com on your
  network (its DNS server, set as the TV's DNS) and installs the app from that page with the name, address and icon above.
- **Sidee:** [Sidee](https://github.com/JohnCarter2021/Sidee) pairs with the TV from a computer (a PIN on the TV) and adds
  launcher tiles.

## Build

```sh
npm install -g @webos-tools/cli
webos/package.sh out
```

One web app for every TV, in `site/public/receiver/` (the site serves it to VIDAA TVs; ES5, as webOS 3 runs an old
Chromium): `app.js` is the same on every one, `platform.js` holds what differs (each TV's name, how the app leaves,
whether and how a stream gets its headers: another TV is one more entry there), `i18n.js` the words in Zephyr TV's
nine languages. Its one icon (400 px) and its one version (`VERSION` in `platform.js`) go into every package.

The proxy is one file for webOS and Tizen, `webos/service/service.js` (Node's own modules only, for the old Node of
webOS 3 and 4 and of TizenBrew): webOS's service answers its port through luna, TizenBrew's on port 8723.

- **webOS** adds only `webos/app/appinfo.json`; `webos/package.sh` puts it together, its icons made from the shared one
  (ImageMagick).
- **Tizen through TizenBrew** needs nothing more: `package.json` at the repository's root is the module (the app's
  page, the proxy as its service, the media keys), which TizenBrew takes from GitHub (jsDelivr) at the latest release.
- **Tizen as a .wgt** adds `tizen/config.xml`; `tizen/package.sh` signs it with your certificate.
- **VIDAA** needs nothing more: the site serves the page.

One version for all: `VERSION` in `platform.js`, the same in `package.json` (the webOS workflow checks it).
