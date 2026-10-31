# Zephyr TV extensions — standard, version 1

***[🇮🇹 Leggi in italiano](estensioni.md)***

> **Status: draft.** Version 1 is being defined together with its first extension, OMG Premium TV. Until it is
> marked final, details can change without a new version number.

An **extension** does what a Stremio add-on does — catalogs of films, series and TV channels, title pages, streams —
but it runs **on the TV**, inside Zephyr TV, as a Nuvio plugin does. There is no server to install or keep online:
the extension's code fetches what it needs from the internet and answers Zephyr TV directly.

The requests and answers are **the objects of the Stremio add-on protocol**. A Stremio add-on's handlers can become
an extension with little change, and an extension can be published as a Stremio add-on from the same code.
On top of the protocol, an extension can have **settings** that Zephyr TV shows on the TV, a **TV guide**, data it
keeps between runs, and **services** of Zephyr TV for work too large for a script (playlists with tens of thousands
of channels, guides of hundreds of megabytes).

Contents:

1. [The package](#1-the-package)
2. [The manifest](#2-the-manifest) — [what an extension provides](#what-an-extension-provides), [its app](#its-app),
   [collectors](#collectors)
3. [Handlers](#3-handlers)
4. [Objects](#4-objects)
5. [Settings](#5-settings)
6. [The runtime](#6-the-runtime)
7. [Zephyr TV services](#7-zephyr-tv-services)
8. [Install, update, remove](#8-install-update-remove)
9. [Security](#9-security)
10. [Versions of the standard](#10-versions-of-the-standard)
11. [A minimal extension](#11-a-minimal-extension)
12. [Coming next](#12-coming-next)

## 1. The package

An extension is one **`.zip`** file:

```
my-extension.zip
├── manifest.json      required, at the root
├── main.js            the code: one file, its imports bundled in (see 6)
├── icon.png           optional: square, 256×256 or more
└── locales/           optional: the manifest's texts in other languages
    ├── it.json
    └── fr.json
```

- At most **10 MB** zipped, **20 MB** unzipped. Paths are relative, with no `..`.
- Any other file is kept and can be read by the code with `zephyr.asset(path)` (see 7).
- `locales/<language>.json` holds `{ "name": …, "description": …, "settings": { "<key>": { "label": …, "description": … } } }`:
  Zephyr TV uses the file of the TV's language, else the manifest's own texts.

## 2. The manifest

`manifest.json` is a **Stremio add-on manifest** with a few fields of Zephyr TV's own:

```json
{
  "zephyr": 1,
  "id": "org.example.my-extension",
  "version": "1.0.0",
  "name": "My extension",
  "description": "Films, series and channels from …",
  "author": "Name",
  "icon": "icon.png",
  "main": "main.js",
  "types": ["movie", "series", "tv"],
  "resources": ["catalog", "meta", "stream", "guide"],
  "idPrefixes": ["myext:"],
  "catalogs": [
    { "type": "tv", "id": "channels", "name": "Channels",
      "extra": [{ "name": "genre", "options": [] }, { "name": "search" }, { "name": "skip" }] }
  ],
  "settings": true,
  "updateUrl": "https://example.org/my-extension/manifest-zephyrtv.json",
  "behaviorHints": { "adult": false, "configurationRequired": false }
}
```

| Field | Required | Meaning |
|---|---|---|
| `zephyr` | yes | The version of this standard the extension is written for: `1`. Zephyr TV refuses an extension with a version it doesn't know. |
| `id` | yes | Unique, stable across versions. Reverse domain notation is recommended. |
| `version` | yes | The extension's own version, [semver](https://semver.org): `1.4.2`. |
| `name`, `description`, `author` | `name` yes | As shown in Zephyr TV. |
| `icon` | no | A file in the package, or an `https://` address. |
| `main` | yes | The code file, in the package. |
| `types`, `resources`, `idPrefixes`, `catalogs` | as Stremio's | What the extension answers, exactly as in a [Stremio manifest](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md): a resource as a name, or as an object with its own `types` and `idPrefixes`. |
| `settings` | no | `true` when the code exports `settings()` (see 5). |
| `updateUrl` | no | The address of its `manifest-zephyrtv.json`, where its latest version is read (see 8). Recommended for an extension also handed out as a `.zip`. |
| `behaviorHints.adult` | no | Its content is 18+: Zephyr TV shows it only while 18+ is unlocked. |
| `behaviorHints.configurationRequired` | no | It does nothing until its settings are set: Zephyr TV opens them after installing it. |
| `collectors` | no | The collectors its content may be shown in instead of its own app: `["movies", "series"]` (see [Collectors](#collectors)). A catalog with `"collector": false` stays in its app. |
| `requires` | no | The features added to this standard after 1.0 that the extension can't do without: `["subtitles"]`. Zephyr TV refuses it when it lacks one (see 10). |

The resources are Stremio's `catalog`, `meta` and `stream`, and Zephyr TV's own `guide` and `featured` (see 3).
A catalog's `extra` follows Stremio's rules: `genre` (with its `options`), `search`, `skip`, `isRequired`.
With `"settings": true` an extension can also give its catalogs from its settings (see `manifest()` in 3).

### What an extension provides

An extension provides any part of these, alone or together, as its manifest's `resources` and `catalogs` say:

| It provides | With | Example |
|---|---|---|
| **Catalogs** only | `catalog` (and `meta`) of titles others play | lists of films by IMDb id (`tt…`): the streams come from add-ons, plugins and other extensions |
| **Streams** only | `stream`, for the `types` and `idPrefixes` it knows | links for films and series of any catalog |
| **Content** whole | `catalog`, `meta` and `stream` of its own ids | a playlist's channels, an account's films and series, a site's videos |
| **A guide** | `guide` | the programmes of its channels, or of everyone's |

### Its app

An extension with content of its own gets **an app of its own**: a virtual app on the apps row of the home screen,
with the extension's `icon` (or, without one, a tile with its name), and **all its rows under it** — films, series,
TV channels and videos alike, in the order of its catalogs. Opening it shows them as an app's rows are shown.

- **A catalog**, an extension whose catalogs list titles that others play (films and series by IMDb id, with no
  `stream` of its own for them), gets **no app**: its rows go under **Films** and **TV Series**, as the catalog
  sources' do. So do one with **streams** only and one with a **guide** only, which have no rows.
- An extension marked 18+ has its app only while 18+ is unlocked, or in the place **Adult content** sets.

### Collectors

Zephyr TV has **collectors**: virtual apps of its own, one for each kind of content, that gather the rows of every
extension (and add-on) that puts that kind there. A collector shows on the apps row only while something is in it.

| Collector | `collectors` value | Holds the types | In Zephyr TV |
|---|---|---|---|
| **Films** | `"movies"` | `movie`, and an extension's own types | the Films tile, as **Where films and series show** sets |
| **TV Series** | `"series"` | `series` | the TV Series tile, as above |
| **TV channels** | `"tv"` | `tv` | where **Where TV channels show** puts the add-ons' channels |
| **Radio** | `"radio"` | `radio` | a Radio tile |
| **Videos** | `"videos"` | `video`, `channel` | a Videos tile |
| **Courses** | `"courses"` | `course` | a Courses tile |
| **Podcasts** | `"podcasts"` | `podcast` | a Podcasts tile |
| **Audiobooks** | `"audiobooks"` | `audiobook` | an Audiobooks tile |
| **Events** | `"events"` | `event` | an Events tile |

The manifest says where its content **may** go, besides its own app:

```json
"collectors": ["movies", "series"]
```

- Without `collectors`, its content stays in its own app.
- A catalog of the manifest can opt out with `"collector": false`: that catalog stays in the extension's app even when
  the rest goes to the collectors (a row that only makes sense there, such as "Recently added to my account").
- In the extension's settings, **Where it shows** offers **In its app** (the default) or **In the collectors**, when
  the manifest has `collectors`. In the collectors, each of its rows goes to the collector of its type, if listed in
  `collectors`; the rows left (types not listed, catalogs with `"collector": false`) stay in its app, which shows only
  while it has some.
- **A catalog** (see above) has no app and needs no `collectors`: its rows go to Films and TV Series.
- **Radio stations** go by Zephyr TV's own **Radio** setting (Virtual apps and integrations): to the Radio collector
  from every extension (the default), each in its extension's app, or nowhere — whatever **Where it shows** says.
- In a collector, the rows of each extension come in the order of the extensions (Settings → Extra features → Zephyr Extensions), after the
  add-ons' and the catalog sources'.

### Types of content

The content is one of these `types`:

| `type` | It is | Its page |
|---|---|---|
| `movie` | a film | its details, then Watch |
| `series` | a series: `videos` with `season` and `episode` | its seasons and episodes |
| `tv` | a TV channel, live: what's on now (see `guide`), CH+ and CH− in the player | — it plays at once |
| `channel` | a collection of videos (a channel of clips, a show's extras): `videos` without seasons | its videos, newest first |
| `video` | **Zephyr TV.** One video on its own (a clip, a recording, a talk) | its details, then Watch |
| `course` | **Zephyr TV.** A course: its lessons as `videos`, in order; a `season` is a module | its modules and lessons; Watch goes on from the first lesson not seen |
| `podcast` | **Zephyr TV.** A podcast, audio or video: its episodes as `videos` | its episodes, newest first |
| `audiobook` | **Zephyr TV.** An audiobook: its chapters as `videos`, in order | its chapters; Listen goes on where it was left, chapter after chapter |
| `radio` | **Zephyr TV.** A radio station, live, audio only: its catalogs are listed as TV channels' are | — it plays at once, with its logo, its name and the song on air (ICY or ID3, when the stream says it) on screen |
| `event` | **Zephyr TV.** A live event at a time (a match, a concert): `startTime`, `endTime` | before it starts, the time left; while it's on, Watch; after it, Watch again when it has streams still |

- `video`, `channel`, `course`, `podcast`, `audiobook`, `radio` and `event` are the **generic content**: they show in
  the extension's app, or in their collector (see above), each in its rows. Rows of `event` show those on now first, then the next,
  soonest first; one over by more than a day, with no streams, is left out.
- A `radio` plays as a TV channel does (CH+ and CH− go through the row's stations), with no picture: its logo on a
  blur of itself, and the title the stream sends (ICY metadata), when it sends one.
- Any other type (an extension's own, such as `"anime"`) is shown and played as `movie` is.
- In a `channel`, `course`, `podcast` or `audiobook`, each of its `videos` plays through `stream` with the video's
  `id`, as an episode does; the next one starts by itself when one ends (for a `podcast`, the next older one).
- Audio-only content (an audio podcast, an audiobook) plays with its `poster`, or the video's `thumbnail`, on the
  screen and the time across the bottom; the TV's screen saver doesn't start while it plays.
- What's begun goes in **Continue Playing**, as films and episodes do: a course by its lesson, an audiobook by its
  chapter and position.
- A `video` or a `channel`'s video can say how long it is (`runtime`) and when it was published (`released`); a
  video begun goes in **Continue Playing**, as films and episodes do.

## 3. Handlers

`main.js` is a CommonJS module. It exports one function for each resource it has, and optionally `manifest()` and
`settings()`. Each can be `async`. Zephyr TV calls them with one object, `args`:

```js
module.exports = {
  async catalog({ type, id, extra, settings }) { return { metas: [] }; },
  async meta({ type, id, settings })            { return { meta: null }; },
  async stream({ type, id, settings })          { return { streams: [] }; },
  async guide({ settings })                     { return { sources: [] }; },
  async featured({ settings })                  { return { metas: [] }; },
  async manifest({ settings })                  { return { catalogs: [] }; },  // optional
  async settings({ settings })                  { return []; },                // optional, see 5
};
```

| Handler | Called | `args` | Answers |
|---|---|---|---|
| `catalog` | for a row, a page of it, a genre, a search | `type`, `id` (the catalog's), `extra`: `{ genre?, search?, skip? }` | `{ metas: [Meta preview] }`; a page is up to 100 |
| `meta` | when a title's or a channel's page opens | `type`, `id` | `{ meta: Meta }`, or `{ meta: null }` when unknown |
| `stream` | when something plays, or its streams are listed | `type`, `id` (for an episode `"<series id>:<season>:<episode>"`); for a past programme of a channel, `catchup: { start, end }` (see 4) | `{ streams: [Stream] }` |
| `guide` | once a day, and when settings change | — | `{ sources: [Guide source] }` (see 4) |
| `featured` | for Spotlight, and the top of the extension's app | — | `{ metas: [Meta preview] }`, up to 20, each with a `background` (a wide picture) and a `description` |
| `manifest` | when settings change, and on install | — | `{ catalogs }`: the catalogs as they are with these settings, replacing the manifest's |
| `settings` | when its options page opens | — | the fields (see 5) |

Every handler gets `settings`, the values the user chose (see 5). An answer may carry Stremio's `cacheMaxAge`
(seconds): Zephyr TV keeps it that long. Without it, catalogs are kept 6 hours, `meta` a day, `stream` not at all.

A handler that throws, or answers after its time (see 6), counts as an empty answer; the error goes to
**Settings → System → Recent problems**.

Zephyr TV asks an extension only for what its manifest declares: the `types` and `idPrefixes` of the resource.
Films and series with IMDb ids (`tt…`) can be asked for by any extension that declares `tt`, as with add-ons.

## 4. Objects

The objects are [Stremio's](https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api/responses).
This section lists what Zephyr TV reads, and the few fields it adds; anything else is ignored.

**Meta preview** (in a catalog) and **Meta** (on a page):

| Field | Notes |
|---|---|
| `id`, `type`, `name` | required |
| `poster`, `background`, `logo` | addresses; for a channel, `logo` is its logo (`poster` is used when there's no `logo`) |
| `description`, `releaseInfo`, `genres`, `imdbRating`, `runtime` | as Stremio's; a channel's `genres` are its groups (its rows) |
| `videos` | a series' episodes: `id`, `season`, `episode`, `title`, `released`, `thumbnail`, `overview` |
| `epgId` | **Zephyr TV.** A channel's id in the guide (an M3U playlist's `tvg-id`): see the guide below |
| `catchup` | **Zephyr TV.** A channel's past programmes can be watched: `{ "days": 7, "url"?: "<template>" }` (see Catch-up below) |
| `startTime`, `endTime` | **Zephyr TV.** An `event`'s, in ISO 8601 (`"2026-11-02T20:45:00+01:00"`) |

For films and series, the art Zephyr TV shows comes from the **main catalog** when it knows the title (by its id);
the extension's own is the fallback.

**Stream**:

| Field | Notes |
|---|---|
| `url` | required: HLS, DASH, or a file or MPEG-TS address |
| `name`, `title` / `description` | shown in the list of streams; the quality and language are read from them ("1080p", "ITA") |
| `behaviorHints.proxyHeaders.request` | headers to play it with: `{ "User-Agent": …, "Referer": … }` |
| `behaviorHints.bingeGroup` | as Stremio's: the next episode starts with a stream of the same group |
| `behaviorHints.audioOnly` | **Zephyr TV.** `true`: it's audio only (see the types above); an `.mp3`, `.m4a`, `.aac`, `.ogg` or `.opus` address is audio anyway |
| `drm` | **Zephyr TV.** `{ "clearkey": { "<key id hex>": "<key hex>" } }` for DASH protected by ClearKey |
| `externalUrl` | instead of `url`: an address another app opens — a link of a music or video app (`https://…`, or its own scheme such as `spotify:…`). Zephyr TV opens it with the app that handles it; Back comes back to Zephyr TV |
| `ytId` | instead of `url`: a YouTube video's id; Zephyr TV opens it in the YouTube app |
| `behaviorHints.androidPackage` | **Zephyr TV.** With `externalUrl`: the app to open it with first (`"com.example.app"`), when it's installed |

A stream with `externalUrl` or `ytId` is listed with the icon of the app that opens it. When no app on the TV
handles it, it isn't listed. What plays in another app has no position in Continue Playing.
`infoHash` (torrent) streams are not played (see 12).

**Catch-up.** A channel with `catchup` has its past programmes, up to `days` ago, playable from the guide, and
**From the start** for the one on now. Zephyr TV has a programme's stream in one of two ways:

- With `url`, a template: Zephyr TV fills it in and plays it, without asking the extension. Its placeholders, as M3U
  playlists write them: `{utc}` and `{utcend}` (start and end, Unix seconds), `{lutc}` (now), `{duration}` (seconds),
  `{offset}` (seconds ago it started), `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` (its start, the channel's local time).
- Without `url`: Zephyr TV calls `stream` with `catchup: { start, end }` (Unix seconds), and plays what it answers.

`zephyr.m3u` reads a playlist's `catchup`, `catchup-source` and `catchup-days` into the same object (see 7).

**Guide source** (an answer of `guide`): `{ "url": "https://…/guide.xml.gz", "headers"?: {…} }`, an
[XMLTV](https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd) file, plain or gzip. Zephyr TV downloads and reads it
itself. A channel's programmes come from the extension's sources first, matched by its `epgId`, then by its name
(ignoring case, accents and quality words such as HD); then from the guide set in Zephyr TV's settings.

## 5. Settings

With `"settings": true`, `settings()` returns the fields of the extension's options page, the same schema as Nuvio's
plugins with one more type, `list`:

| `type` | Shows | Fields |
|---|---|---|
| `header` | a title | `label` |
| `info` | a note | `label`, `description` |
| `text` | a text, typed with the remote or pasted from a phone | `key`, `label`, `description`, `placeholder`, `defaultValue`, `isPassword` |
| `select` | one choice of a list | `key`, `label`, `description`, `options: [{ label, value }]`, `defaultValue` |
| `toggle` | yes or no | `key`, `label`, `description`, `defaultValue` (boolean) |
| `list` | **Zephyr TV.** Items the user adds, edits, moves and removes | `key`, `label`, `description`, `item`: the fields of one item (no `list` inside), `itemLabel`: the key whose value names an item |

Values are JSON: a `list` is an array of objects, one per item, keyed by its fields' `key`s.

```js
async settings() {
  return [
    { type: "list", key: "playlists", label: "M3U playlists", itemLabel: "name",
      item: [
        { type: "text", key: "name", label: "Name" },
        { type: "text", key: "url", label: "Address", placeholder: "https://…/list.m3u" },
      ] },
    { type: "text", key: "proxy", label: "Proxy", description: "Optional." },
    { type: "toggle", key: "proxyOnly", label: "Only through the proxy", defaultValue: false },
  ];
}
```

The values reach every handler as `args.settings` (and `globalThis.SCRAPER_SETTINGS`, as for Nuvio's plugins).
When they change, Zephyr TV calls `manifest()` (when exported) and `guide()` again, and reads the catalogs anew.
Values are kept on the TV only, in Zephyr TV's backups too; `isPassword` values are shown hidden.

## 6. The runtime

The code runs in **QuickJS** (ES2023: `async`/`await`, classes, `Map`, `Proxy`, …), a context of its own for each
call. It isn't Node.js nor a browser; Zephyr TV gives it:

- `fetch`, `Headers` — as a browser's, but a response's `set-cookie` and redirects can be read; requests follow
  redirects and are not limited by CORS.
- `URL`, `URLSearchParams`, `TextEncoder`, `TextDecoder`, `Buffer` (base64, hex, utf-8), `atob`, `btoa`.
- `setTimeout`, `clearTimeout`, `console` (`log`, `warn`, `error`: to Recent problems, in debug builds).
- `localStorage` — the extension's own, strings by key, kept between calls (see 7).
- `require()` of `cheerio` (HTML as jQuery reads it) and `crypto-js`. Other packages: bundle them into `main.js`
  (with esbuild, for example: `esbuild src/index.ts --bundle --platform=neutral --format=cjs --outfile=main.js`).
- `zephyr` — Zephyr TV's services (see 7).

Limits for each call: **25 seconds** (a `stream` call; `catalog` and `guide` 60), **15 seconds** for each request,
**64 MB** of memory. No files, no WebSocket, no access to other extensions or to Zephyr TV's settings.

## 7. Zephyr TV services

The global `zephyr`:

| | |
|---|---|
| `zephyr.version` | Zephyr TV's version, `"2.3.0"` |
| `zephyr.standard` | the newest version of this standard it knows, `1` |
| `zephyr.language`, `zephyr.region` | the TV's: `"it"`, `"IT"` |
| `zephyr.asset(path)` | a file of the package, as text (`await`) |
| `zephyr.has(name)` | whether this Zephyr TV has a feature added after 1.0 (see 10) |
| `zephyr.m3u` | M3U playlists, read by Zephyr TV (below) |

**`zephyr.m3u`** reads a playlist natively, so that one of tens of thousands of channels never has to fit in the
script's memory. Zephyr TV downloads it, reads it and keeps it on the TV; the code asks for pages of it:

```js
// Reads the playlist (again when older than maxAge seconds): its groups and how many channels.
const list = await zephyr.m3u.load("main", { url, headers, maxAge: 86400 });
// maxAge: 0 explicitly refreshes now (the default only applies when maxAge is absent).
// → { channels: 18234, groups: ["News", "Sport", …], guides: ["https://…/epg.xml.gz"] }

// A page of its channels, by group or by words in the name.
const page = await zephyr.m3u.channels("main", { group: "Sport", search: "", skip: 0, limit: 100 });
// → [{ id, name, radio, group, logo, epgId, catchup?, streams: [{ url, name, headers, clearkey }] }]

// One channel, by the id it has in the playlist.
const channel = await zephyr.m3u.channel("main", id);
```

- The first argument names the playlist (`"main"`, or one per item of a `list` setting); it belongs to the extension.
- A channel's id is stable while the playlist keeps its `tvg-id`, or its name.
- The same channel in several qualities ("Channel HD", "Channel FHD", same `tvg-id` or the same name but for the
  quality) is one channel with several `streams`.
- Read for each channel: `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `#EXTVLCOPT` and `#EXTHTTP` (headers),
  `#KODIPROP` `inputstream.adaptive.license_key` (ClearKey). The playlist's `url-tvg` / `x-tvg-url` are its `guides`.
- A channel with `radio="true"` in its `#EXTINF` is a radio station: `radio: true`. `channels` takes `kind`, `"tv"` or
  `"radio"`, for one or the other; `load`'s summary counts them apart (`channels` and `groups` for TV channels,
  `radios` and `radioGroups` for radio stations), so an extension gives them catalogs of their own (`type: "radio"`).
- `zephyr.m3u.forget(name)` removes a playlist kept.
- `load` takes `text` instead of `url`: a playlist the extension wrote itself (from an account's API, for example),
  read and kept the same way; it's read again at each such call.

`localStorage` is the extension's own (`getItem`, `setItem`, `removeItem`, `clear`, `key`, `length`), strings, at most
1 MB. It's kept between calls and removed with the extension.

## 8. Install, update, remove

**Settings → Extra features → Zephyr Extensions**:

- **Add an extension**: the address of its **`manifest-zephyrtv.json`** (below), typed or pasted from a phone, or its
  `.zip` sent from a phone (QR code). Zephyr TV shows its name, author, what it provides and that it runs code by
  someone else, and installs it once confirmed.
- Each extension: **On**, **Options**, **Adult content (18+)**, **Move up / down** (its rows come in this order),
  **Update now**, **Remove**. Removing it deletes its settings, its `localStorage` and its playlists.
- **Add an extension** also takes a **repository**'s address (its `repository-zephyrtv.json`, see below): Zephyr TV
  adds the repository and shows its extensions.
- The extensions of the repositories that aren't installed are listed after the installed ones: OK installs one from
  its address. Each repository has its row: its extensions, **Read the list again**, **Remove the repository** (the
  extensions installed from it stay). Zephyr TV carries no extension; it has **its own repository** from the start
  (OMG Premium TV, Live Webcams and the others of
  [Zephyr-TV-Extensions](https://github.com/mccoy88f/Zephyr-TV-Extensions)), until the user removes it.

### `manifest-zephyrtv.json`

An extension is published on the internet with one JSON file named **`manifest-zephyrtv.json`**, next to its `.zip`
or anywhere else. Its address is what users add, and what Zephyr TV reads for updates:

```json
{
  "zephyr": 1,
  "id": "org.example.my-extension",
  "version": "1.1.0",
  "name": "My extension",
  "description": "Films, series and channels from …",
  "author": "Name",
  "icon": "https://example.org/my-extension/icon.png",
  "package": "my-extension-1.1.0.zip",
  "sha256": "9f2c…",
  "adult": false
}
```

| Field | Required | Meaning |
|---|---|---|
| `zephyr`, `id`, `version` | yes | As in the package's `manifest.json`, which must have the same values. |
| `name`, `description`, `author`, `icon` | `name` yes | Shown before installing, without downloading the package. |
| `package` | yes | The `.zip`: an address, or a path relative to this file's address. |
| `sha256` | yes | The package's SHA-256, in hex: a package that doesn't match is refused. |
| `adult` | no | Its content is 18+: the 18+ box is ticked when it's added. |

Only the file's own name is fixed: Zephyr TV accepts an address ending in `manifest-zephyrtv.json`, or the folder it's
in (`https://example.org/my-extension/` reads `https://example.org/my-extension/manifest-zephyrtv.json`).

### `repository-zephyrtv.json`

A **repository** publishes many extensions at one address: a JSON file named **`repository-zephyrtv.json`** that
lists their `manifest-zephyrtv.json`:

```json
{
  "zephyr": 1,
  "name": "My extensions",
  "extensions": [
    "webcams/manifest-zephyrtv.json",
    "https://example.org/other/manifest-zephyrtv.json"
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `zephyr` | yes | `1`. A repository for a newer version is refused. |
| `name` | yes | Shown in Zephyr TV. |
| `extensions` | yes | The addresses of its extensions' `manifest-zephyrtv.json`: absolute, or relative to the repository's. |

Zephyr TV reads a repository once a day, with each of its extensions' `manifest-zephyrtv.json` (not their packages).
An extension new in the list shows among those to install: none is installed by itself. 18+ ones show only while 18+
is unlocked. An extension installed from a repository updates from its own address, as any other.

### Links

A link installs an extension with one tap, on a phone or a TV that opens links:

- `ztv://ext?u=<address of its manifest-zephyrtv.json>`
- or, for chats, which only make `https` clickable:
  `https://mccoy88f.github.io/Zephyr_TV/open/#ext?u=<address of its manifest-zephyrtv.json>`

The address in `u` is URL-encoded. Zephyr TV shows what the extension is and who made it before installing it.
The same links take `addon?u=` (a Stremio add-on's `manifest.json`), `nuvio?u=` (a Nuvio repository) and
`title/<type>/<id>?n=<name>` (a film's or series' page); add-ons and repositories are added only once confirmed.

### Updates

Zephyr TV reads an extension's `manifest-zephyrtv.json` once a day: the address it was added from, or, for one
installed from a `.zip`, its `updateUrl`. A newer `version` for a `zephyr` it knows is downloaded,
checked against `sha256`, and installed in place of the old one, its settings and data kept. The `id` can't change.
An extension with neither address updates only when a new `.zip` of it is added.

## 9. Security

An extension is code by someone else. Zephyr TV runs it apart from everything else: the internet, its own settings
and data, the services above, nothing more. It can't read files, other apps, other extensions, or Zephyr TV's
settings, accounts and keys; it can't start anything. What it sends on the internet is up to it: install only
extensions you trust.

## 10. Versions of the standard

- `zephyr` in the manifest is the version an extension is written for. A Zephyr TV that knows versions up to N
  installs extensions of version N or older, and refuses newer ones, telling to update Zephyr TV.
- Within a version, changes only add: new optional fields, new services, new setting types. Each addition has a
  **name** (listed under **Changes**). An extension that can't do without one lists it in `requires`: a Zephyr TV
  that lacks it doesn't install it, and says to update Zephyr TV. One that can do without asks
  `zephyr.has("subtitles")` and uses it only then. Version 1.0 has no names: a Zephyr TV that knows version 1 knows
  all of 1.0.
- What would break an extension written for an older version goes in a new version; Zephyr TV keeps running the older
  ones as they were.
- Each version has its section under **Changes**, below.

### Changes

- **Version 1.0** (draft): the package, the manifest, the types `movie`, `series`, `tv`, `channel`, `video`, `course`, `podcast`
  and `audiobook`, `externalUrl` and `ytId` streams, audio-only streams,
  `manifest-zephyrtv.json`, `catalog`, `meta`, `stream`, `guide`,
  `manifest`, `settings` with `list`, `featured`, `radio`, `event`, collectors, catch-up, `requires` and `zephyr.has()`,
  `zephyr.m3u`, `localStorage`, `repository-zephyrtv.json`.

## 11. A minimal extension

`manifest.json`:

```json
{
  "zephyr": 1, "id": "org.example.hello", "version": "1.0.0", "name": "Hello", "main": "main.js",
  "types": ["tv"], "resources": ["catalog", "meta", "stream"], "idPrefixes": ["hello:"],
  "catalogs": [{ "type": "tv", "id": "hello", "name": "Hello" }]
}
```

`main.js`:

```js
const CHANNEL = { id: "hello:1", type: "tv", name: "Test channel", logo: "https://example.org/logo.png" };

module.exports = {
  async catalog() { return { metas: [CHANNEL] }; },
  async meta({ id }) { return { meta: id === CHANNEL.id ? CHANNEL : null }; },
  async stream({ id }) {
    return { streams: id === CHANNEL.id ? [{ name: "HD", url: "https://example.org/live/index.m3u8" }] : [] };
  },
};
```

Zip the two files (`zip hello.zip manifest.json main.js`) and add it from **Extensions → Add an extension**. To publish
it, put `hello.zip` online with a `manifest-zephyrtv.json` next to it:

```json
{ "zephyr": 1, "id": "org.example.hello", "version": "1.0.0", "name": "Hello",
  "package": "hello.zip", "sha256": "<sha256sum hello.zip>" }
```

and add its address: `https://example.org/hello/manifest-zephyrtv.json`.

## 12. Coming next

Planned for the next additions to version 1. Their names are **reserved**: an extension doesn't use them for anything
else (as a handler, a `type`, a field or a `zephyr` service). The shapes below are indicative; each becomes part of
the standard, with its name for `requires`, once Zephyr TV has it.

| Name | What it will be | Indicative shape |
|---|---|---|
| `subtitles` | external subtitles, as Stremio's resource | handler `subtitles({ type, id, extra })` → `{ subtitles: [{ id, url, lang }] }`, SRT or WebVTT |
| `progress` | what's watched, told to the extension (resume elsewhere, mark as watched) | handler `progress({ type, id, position, duration, state })`, `state`: `playing`, `paused`, `stopped`, `ended` |
| `auth` | signing in from a phone with a code (device code) | `zephyr.auth.deviceCode({ url, code, expires })`: Zephyr TV shows the code and a QR code until the extension says it's done |
| `resolver` | extensions that turn a hosting site's link into a stream, for the others | resource `resolve({ url })` → `{ streams }`; `zephyr.resolve(url)` for the others |
| `webview` | pages behind a browser check | `zephyr.webview({ url, until })` → `{ cookies, html }`, in a hidden WebView |
| `trailers` | trailers on a film's or series' page | `trailerStreams` of Meta, as Stremio's |
| `schedule` | work every few hours in the background (a playlist read again, a token renewed) | handler `schedule({ settings })`, with `scheduleHours` in the manifest |
| `subtle` | fast ciphers and hashes | `crypto.subtle` (`digest`, AES, HMAC) and `crypto.getRandomValues` |
| `widevine` | DASH protected by Widevine | `drm: { "widevine": { "license": "<url>", "headers"?: {…} } }` |

Not planned: torrent (`infoHash`) streams, WebSocket, signed packages.

### Optional local fixture artwork (Nello builds)

Check `zephyr.has("match-posters")` before using:

```js
const poster = await zephyr.matchPoster({
  home: "Home club", away: "Away club", time: "20:30",
  homeBadge: "https://example.org/home.png", awayBadge: "https://example.org/away.png"
});
```

The returned app-private `file://` URI can be used as the catalog item's `poster`
and `logo`. The native renderer composes both badges, names and kickoff time on
a 960×540 PNG, cached per extension with a 120-image limit. It never uploads the
fixture to an image service. Badge downloads require HTTPS; absent badges use
initials. The extension is responsible for identifying the clubs and providing
the correct local time. Include `match-posters` in `requires` only if no fallback
is possible on other Zephyr builds.
