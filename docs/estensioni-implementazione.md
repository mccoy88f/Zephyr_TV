# Estensioni — cosa implementare

Documento di lavoro: tutto quello che serve perché Zephyr TV supporti lo [standard delle estensioni](estensioni.md)
(versione 1.0) e perché OMG Premium TV diventi la prima estensione, proposta da Zephyr TV. Le fasi sono in ordine: ognuna si
appoggia alle precedenti, e alla fine di ognuna Zephyr TV si compila, passa i test e si può rilasciare.

Legenda: ☐ da fare · ◐ in parte già in Zephyr TV · ☑ fatto

---

## Fase 0 — Il motore condiviso ☑

Le estensioni girano nello stesso QuickJS dei plugin Nuvio. Oggi il motore è dentro `NuvioPlugins.kt`.

- ☑ Spostare il motore in un file suo (`JsRuntime.kt`): prelude, `fetch`, moduli (`cheerio`, `crypto-js`),
  `localStorage`, timer, `console`, conversione ASCII delle stringhe. I plugin Nuvio lo usano come oggi.
- ☑ Limiti per chiamata configurabili: tempo (25 s per `stream`, 60 s per `catalog` e `guide`), memoria (64 MB),
  tempo per richiesta (15 s).
- ☑ Ponte per i servizi asincroni di `zephyr.*` (funzioni Kotlin chiamate da JS, con risultato JSON).
- ☑ `localStorage` con limite di 1 MB (`QuotaExceededError` oltre).
- ◐ Già presenti: `fetch`, `Headers`, `URL`, `URLSearchParams`, `TextEncoder/Decoder`, `Buffer`, `atob/btoa`, timer,
  `localStorage`, `window`/`self`, `require` di cheerio e crypto-js.

## Fase 0b — Processi separati ◐

Uno script in un ciclo infinito non si ferma dentro QuickJS (la libreria non ha un modo per interromperlo: verificato),
e un crash nativo porta giù il suo processo. Per questo plugin ed estensioni girano fuori dal launcher e dal player.

- ☑ `ScriptService`: esegue gli script in un processo suo, chiamato con un `Messenger`; testi oltre 200.000 caratteri
  passano da un file (limite di 1 MB di Binder).
- ☑ Due processi: `:plugins` (plugin Nuvio) e `:extensions` (estensioni). Gli add-on restano nel launcher: il loro
  codice gira sui loro server, il launcher fa solo HTTP e JSON.
- ☑ `ScriptProcess`, il client: attende ogni risposta al massimo il tempo della chiamata più 5 s; oltre, **uccide il
  processo**, la chiamata vale vuota e va in Problemi recenti; la successiva lo fa ripartire. Crash del processo →
  le chiamate in corso falliscono, la successiva lo riavvia. Dopo 2 minuti senza chiamate si scollega.
- ☑ I plugin Nuvio girano in `:plugins`, sia dalla home sia dal player; nei test, come prima, nel processo dei test.
- ☐ **Prova su un dispositivo**: plugin che trovano stream, un plugin che non risponde (processo ucciso e ripartito),
  memoria dei processi. Da fare con la prima beta firmata (sul TV c'è la release firmata) o su un emulatore.

## Fase 1 — Pacchetto, installazione, aggiornamenti ◐

Nuovo file `Extensions.kt` (modello e disco) e `ExtensionPages.kt` (pagine delle impostazioni).

- ☑ **Modello**: `Extension(id, version, zephyr, name, description, author, icon, main, types, resources,
  idPrefixes, catalogs, settings, updateUrl, adult, configurationRequired, requires)`, letto da `manifest.json`.
- ☑ **Validazione** del manifest: `zephyr` conosciuto, `id` e `version` (semver), `main` presente nel pacchetto,
  `requires` tutti noti (altrimenti: "Aggiorna Zephyr TV"), campi obbligatori.
- ☑ **Pacchetto**: decompressione sicura (`java.util.zip`) in `files/extensions/<id>/`, con 10 MB compresso, 20 MB
  decompresso, niente `..` né percorsi assoluti, sostituzione atomica (cartella temporanea, poi rinomina).
- ☑ **`manifest-zephyrtv.json`**: lettura da un indirizzo che finisce con quel nome o da una cartella; `package`
  assoluto o relativo; verifica di `sha256`; `id`, `version` e `zephyr` uguali a quelli del `manifest.json` interno.
- ☑ **Anteprima** prima di installare: nome, autore, icona, descrizione, cosa fornisce (tipi e risorse), avviso
  "codice scritto da altri", casella 18+ (precompilata da `adult`), Installa.
- ☑ **Installazione dal telefono**: lo `.zip` con `PhonePages.file` (come gli sfondi), oppure l'indirizzo con
  `PhonePages.text`; con il telecomando, l'indirizzo.
- ☑ **Aggiornamenti**: una volta al giorno (come `refreshPlugins`), dall'indirizzo di installazione o da `updateUrl`;
  versione più nuova → scarica, verifica, sostituisce, mantiene impostazioni e dati. "Aggiorna ora" a mano.
- ☑ **Proposte** (al posto delle preinstallate, decisione del 2026-10-29): Zephyr TV non contiene estensioni;
  quelle proposte (OMG Premium TV) compaiono nella pagina Estensioni finché non sono installate e si installano con
  OK dal loro `manifest-zephyrtv.json`, sempre allo stesso indirizzo (`releases/latest/download`).
- ☑ **Rimozione**: cancella cartella, impostazioni, `localStorage`, playlist di `zephyr.m3u`, cache.
- ☑ **Preferenze** in `Prefs.kt`: ordine, spente, 18+, impostazioni (JSON), "Dove compare", indirizzo di
  installazione. Tutte nel backup (`Backup.kt`), tranne i file scaricati.
- ☐ **Testi tradotti**: `locales/<lingua>.json` per nome, descrizione ed etichette delle impostazioni.
- ☑ Pagina **Impostazioni → Funzioni aggiuntive → Estensioni Zephyr**: elenco (Attiva, Opzioni, 18+, Dove compare,
  Sposta su/giù, Aggiorna ora, Rimuovi), Aggiungi un'estensione. La sezione si chiama **Funzioni aggiuntive** (fatto).

- ☑ Provato sul TV (test strumentato `ExtensionInstallTest`): installazione dall'indirizzo servito in rete locale,
  verifica `sha256`, esecuzione di un handler nel processo `:extensions`.
- ☐ Opzioni e Dove compare nella pagina dell'estensione: fasi 3 e 8.

## Fase 2 — Le estensioni come sorgenti, al posto degli add-on ☑

Il punto chiave: per il resto di Zephyr TV un'estensione è un add-on Stremio che risponde da QuickJS invece che via
HTTP. Così righe, pagine, ricerca e player le usano senza codice nuovo.

- ☑ In `Stremio.kt`, separare **chi chiede** da **come si chiede**: oggi `Http.json("<base>/catalog/…json")`, domani
  un'interfaccia `ask(resource, type, id, extra)` con due realizzazioni, HTTP (add-on) e QuickJS (estensione).
- ☑ L'estensione produce uno `Stremio.Addon` dal suo manifest (con il parser che c'è già), con un indirizzo interno
  (`ext:<id>`) al posto di quello web.
- ☑ Chiamate agli handler con `args` (`type`, `id`, `extra`, `settings`, per `stream` anche `catchup`).
- ◐ **Cache** delle risposte: oggi quella degli add-on (cataloghi riletti dopo 6 ore, canali dopo 5 minuti); `cacheMaxAge` ancora da leggere.
- ☑ **`manifest()`**: i cataloghi ricalcolati dalle impostazioni, all'installazione e a ogni modifica.
- ☑ Errori e tempi scaduti → risposta vuota e riga in **Problemi recenti** (`Problems.kt`).
- ☑ Le estensioni compaiono nelle liste delle sorgenti di stream (`streamSources`), con nome e icona, nell'ordine
  scelto: dopo gli add-on, prima dei plugin Nuvio.
- ☑ `requires` e `zephyr.has(nome)`: elenco dei nomi noti a questo Zephyr TV (`libraries` dopo la 1.0).
- ☑ `libraries`: file CommonJS scaricati in `lib/` all'installazione, `require()` dal loro `name`, barra di stato e percentuale, aggiornamento indipendente dal `.zip`.
- ☑ Indirizzo interno `zext://<id>/…` risolto da `Stremio.get` (launcher e player) verso `ExtensionCalls`, che chiama
  l'handler nel processo `:extensions` con `args` (settings inclusi), `zephyr` (`version`, `standard`, `language`,
  `region`, `has`, `asset`), `SCRAPER_SETTINGS`, `SCRAPER_ID` e il `localStorage` dell'estensione.
- ☑ Provato sul TV (`ExtensionInstallTest`): manifest, catalogo e scheda dell'estensione chiesti come a un add-on.

## Fase 3 — Dove compaiono: l'app dell'estensione e i tipi ◐

- ☑ **App virtuale** per ogni estensione con contenuti propri: tile nella riga delle app con la sua `icon` (o il
  nome), le sue righe sotto, nell'ordine dei cataloghi (`Addons.kt`, `Titles.kt`, `WatchRow.kt`).
- ☑ **Riconoscere un catalogo**: cataloghi di soli id altrui (`tt…`) e nessuno `stream` per loro → nessuna app, righe
  sotto Film e Serie TV come le fonti di catalogo. Nessuna app anche per le estensioni di soli stream o sola guida.
- ☑ **Raccoglitori**: app virtuali di Zephyr per tipo di contenuto — Film, Serie TV, Canali TV (quelli che ci sono già),
  e nuovi Radio, Video, Corsi, Podcast, Audiolibri, Eventi, con icone e immagini delle tile. Ognuno compare solo
  finché contiene righe.
- ☑ **`collectors`** nel manifest e `"collector": false` sui cataloghi; **Dove compare** per estensione: Nella sua
  app (predefinito) o Nei raccoglitori; le righe che non vanno in un raccoglitore restano nella sua app.
- ☐ Ordine delle righe in un raccoglitore: add-on e fonti di catalogo, poi le estensioni nel loro ordine.
- ☑ **18+**: app e righe solo con 18+ sbloccato, o nel posto scelto in Contenuti per adulti (`AdultLock.kt`).
- ☐ **Tipi nuovi** (oggi Zephyr scarta `channel` e mette i tipi propri sotto Film — `Stremio.isVodType`):
  - ☐ `channel`: pagina con i video, dai più recenti; il successivo parte da solo.
  - ☐ `video`: pagina con dettagli e Guarda.
  - ☐ `course`: moduli (`season`) e lezioni in ordine; Guarda riprende dalla prima lezione non vista; segno di visto
    per lezione.
  - ☐ `podcast`: puntate dalle più recenti; alla fine parte la precedente; audio o video.
  - ☐ `audiobook`: capitoli in ordine; Ascolta riprende da capitolo e punto; capitolo dopo capitolo.
  - ☐ `radio`: si riproduce subito, come un canale; CH+/CH− tra le radio della riga.
  - ☐ `event`: in corso prima, poi i prossimi; prima dell'inizio il tempo che manca; esclusi quelli finiti da più di
    un giorno senza stream; aggiornamento della riga all'inizio di un evento.
  - ☐ Altri tipi propri: come `movie`, ma nella sua app.
- ☐ **Continua a guardare** per `video`, lezioni, puntate e capitoli (`VodProgress.kt`), con la loro posizione.
- ☐ **Immagini**: film e serie con le copertine del catalogo principale e quelle dell'estensione come riserva
  (`MainArt.kt`, già fatto per gli add-on).
- ☐ **`featured`**: fino a 20 titoli con immagine larga; in cima all'app dell'estensione e come sorgente dello
  Spotlight (`Spotlight.kt`, Impostazioni → Spotlight → Sorgente: "In evidenza dalle estensioni").
- ☑ Fatto generalizzando l'app propria degli add-on per adulti (`ownApp`): l'app dell'estensione ha i suoi canali, film e
  serie; in modalità raccoglitori ogni riga va nel raccoglitore del suo tipo (`extensionRoutes`), le altre restano
  nella sua app. Tile Radio, Video, Corsi, Podcast, Audiolibri, Eventi solo quando hanno righe.
- ☑ Tipo `channel` accettato per le estensioni (per gli add-on Stremio resta escluso, come prima).
- ☐ Ancora da fare: `"collector": false` sul singolo catalogo; i comportamenti propri dei tipi generici (ordine e
  avvio automatico di podcast, corsi, audiolibri; radio che parte subito; conto alla rovescia degli eventi);
  `featured` nello Spotlight. Oggi i tipi generici si mostrano nelle loro righe e si riproducono come un film.

## Fase 4 — Il player ◐

In `PlayerActivity.kt`.

- ☐ **Solo audio** (`behaviorHints.audioOnly`, o estensione del file audio): `poster` o `thumbnail` su una sfocatura,
  tempo in basso, schermo sempre acceso, niente salvaschermo.
- ☐ **Radio**: logo al centro, titolo in onda dai metadati ICY (Media3 li legge già), nessuna barra del tempo.
- ☑ **`externalUrl`**: `Intent.ACTION_VIEW` con l'indirizzo, prima con `behaviorHints.androidPackage` se installata;
  **`ytId`**: app YouTube (`vnd.youtube:<id>`). Nella lista degli stream con l'icona dell'app che li apre; nascosti se
  nessuna app li gestisce. Serve `<queries>` nel manifest Android per vedere le app (visibilità dei pacchetti da
  Android 11).
- ☑ **ClearKey** (`drm.clearkey`): `MediaItem.DrmConfiguration` con `C.CLEARKEY_UUID` e le chiavi in locale.
- ☑ **`behaviorHints.audioUrl`**: video-only più un secondo indirizzo audio, riprodotti insieme (`MergingMediaSource`).
- ☐ **`bingeGroup`**: l'episodio successivo con uno stream dello stesso gruppo, se c'è.
- ☐ **Prossimo elemento in automatico** per `channel`, `course`, `podcast`, `audiobook` (oggi c'è per le serie).
- ☑ `externalUrl` / `ytId` aprono l'app (con `androidPackage` per prima, se c'è); ClearKey con le chiavi come licenza
  locale (Media3, nessuna libreria in più). Nella lista non hanno ancora l'icona dell'app né vengono nascosti senza app.
- ☐ Solo audio, radio, prossimo elemento automatico, `bingeGroup`.

## Fase 5 — Catch-up dei canali

- ☐ Campo `catchup` del canale: `days` e `url` facoltativo.
- ☐ **Modello**: compilazione dei segnaposto `{utc}`, `{utcend}`, `{lutc}`, `{duration}`, `{offset}`, `{Y}` `{m}`
  `{d}` `{H}` `{M}` `{S}` (ora locale del canale).
- ☐ Senza `url`: `stream` con `catchup: { start, end }`.
- ☐ **Nella guida** (`Guide.kt`): i programmi passati, fino a `days` giorni, riproducibili; nel player e nella pagina
  del canale **Dall'inizio** per il programma in onda.
- ☐ Lettura di `catchup`, `catchup-source`, `catchup-days` (e di `timeshift`) nelle playlist (fase 7).

## Fase 6 — La guida delle estensioni ◐

Oggi `Epg.kt` ha un solo indirizzo XMLTV, abbinato per nome.

- ☑ **Più fonti**: quella globale delle impostazioni più quelle di ogni estensione (`guide()`), ognuna con i suoi
  file e il suo aggiornamento giornaliero.
- ☑ **Priorità**: prima le fonti dell'estensione del canale, poi la globale.
- ☐ **Abbinamento**: `epgId` esatto, poi nome normalizzato (maiuscole, accenti, parole di qualità HD/FHD/4K, suffissi
  come `.it`), come fa OMG Premium TV.
- ☐ Spazio su disco: solo le ore che servono (come oggi), più i giorni del catch-up; limite totale e pulizia delle
  fonti non più usate.
- ☑ Una guida per indirizzo (`Epg`), quelle di `guide()` lette una volta al giorno in background; nelle righe dei
  canali di un'estensione prima le sue, poi quella globale; abbinamento per `epgId`, poi per nome.
- ☐ Nel player (banner del canale) e nella Guida ancora solo quella globale.

## Fase 7 — `zephyr.m3u`, le playlist lette da Zephyr TV ☑

- ☑ **Lettore M3U a flusso** (senza caricare tutto in memoria): `#EXTM3U` con `url-tvg`/`x-tvg-url`, `#EXTINF` con
  `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `catchup*`; `#EXTVLCOPT`, `#EXTHTTP` (header); `#KODIPROP`
  `inputstream.adaptive.license_key` (ClearKey).
- ☑ **Doppioni**: stesso `tvg-id`, o stesso nome a parte la qualità → un canale con più stream (regole di
  `m3u-parser.ts` di OMG Premium TV).
- ◐ **Indice su disco**: oggi un file con un canale per riga, caricato in memoria nel processo `:extensions` (le ultime 3
  playlist) per pagine e ricerca. SQLite se le liste molto grandi pesano troppo.
- ☑ API: `load(nome, { url, headers, maxAge })`, `channels(nome, { group, search, skip, limit })`,
  `channel(nome, id)`, `forget(nome)`; id dei canali stabili.
- ☑ Le playlist appartengono all'estensione: si cancellano con lei.

## Fase 8 — Le impostazioni delle estensioni ☑

- ☑ Riusare le pagine delle opzioni dei plugin Nuvio (`Plugins.kt`: `toggle`, `select`, `text`, `info`). Un campo `text` apre `fromPhoneText` (la stessa pagina QR di TMDB, EPG, cookie).
- ☑ **Tipo `list`**: elenco degli elementi (nominati con `itemLabel`), Aggiungi, e per ognuno Modifica (i campi di
  `item`), Sposta su/giù, Rimuovi; valori come array di oggetti.
- ☑ **Dal telefono**: un modulo con tutti i campi di un elemento in una volta (per esempio server, utente e password
  Xtream), non solo un testo alla volta.
- ☑ `configurationRequired`: dopo l'installazione si aprono le opzioni.
- ☑ Alla modifica: `manifest()`, `guide()`, righe ricaricate.
- ☐ Etichette da `locales/`.
- ☑ Pagine comuni in `OptionPages.kt` (`formPage`), usate da plugin ed estensioni; valori in `ext_options` (nel
  backup), passati come `args.settings` e `SCRAPER_SETTINGS`.
- ☐ Etichette da `locales/` (oggi quelle di `settings()`), `guide()` riletta (fase 6).

## Fase 9 — Documentazione e test

- ☐ Test unitari: validazione del manifest, decompressione sicura, `sha256`, `manifest-zephyrtv.json` (indirizzi
  relativi), `requires`, chiamate agli handler, cache, modello del catch-up, lettore M3U (con le playlist di prova di
  OMG Premium TV), doppioni, tipo `list`.
- ☐ L'estensione minima dello standard (sezione 11) come test: si installa e il suo canale si riproduce.
- ☐ Guida utente (`guida.md`, `guide.md`): capitolo **Estensioni**.
- ☐ Standard: togliere "bozza" quando la fase 10 funziona su un TV vero.
- ☐ Changelog di ogni rilascio.

## Fase 10 — OMG Premium TV come estensione ◐

Nel repository `mccoy88f/OMG-Premium-TV`, accanto all'add-on.

- ☑ **Build**: un comando (esbuild) che da `src/` produce `main.js`, con `manifest.json`, icona e `locales/`, lo
  `.zip` e il `manifest-zephyrtv.json` (con `sha256`); pubblicati nelle release di GitHub.
- ◐ **Riuso del codice**: le regole di `xtream.ts` e `proxy.ts` sono riportate in `zephyr/main.js` (JavaScript puro,
  senza build); il parser M3U è in Zephyr (`zephyr.m3u`) con le stesse regole di `m3u-parser.ts`.
- ☑ **Impostazioni**: Playlist M3U (`list`: nome, indirizzo), Account Xtream (`list`: nome, server, utente, password),
  Proxy (indirizzo, password, solo tramite proxy), EPG della playlist sì/no.
- ☑ **Canali**: da `zephyr.m3u` e dalle API Xtream (`get_live_categories`, `get_live_streams`); un catalogo `tv` per
  playlist o account, gruppi come generi; `epgId`, loghi della playlist.
- ☑ **Film e serie Xtream**: cataloghi `movie` e `series`, `meta` e `stream` con id propri, collegati a IMDb dove
  possibile.
- ☑ **Proxy**: riconoscimento MediaFlow / EasyProxy (`/api/info`), indirizzi degli stream riscritti, "solo tramite
  proxy".
- ☐ **Catch-up**: `catchup` dalle playlist e dal timeshift di Xtream.
- ☑ **Guida**: `guide()` con gli `url-tvg` delle playlist e l'EPG di Xtream (`xmltv.php`).
- ☑ In Zephyr: proposta nella pagina Estensioni, installata dalle release di OMG (Action `zephyr-extension.yml`,
  tag `zephyr-v<versione>`).

---

## Decisioni ancora aperte

- **Indice delle playlist**: SQLite (proposto) o file propri come per l'EPG.
- **Migrazione dei plugin Nuvio** sul motore condiviso: sì nella fase 0, comportamento invariato.
- **Visibilità delle app** per `externalUrl`: dichiarare in `<queries>` le azioni `VIEW` per `http`, `https` e gli
  schemi più comuni, o elencare i pacchetti.
- **Limite delle estensioni installate** e dello spazio su disco totale.
- **In arrivo** (sezione 12 dello standard): dopo la fase 10, in quest'ordine proposto: `subtle`, `subtitles`,
  `progress`, `auth`, `webview`, `resolver`, `repository`, `trailers`, `schedule`, `widevine`.
- ☑ Provato sul TV (allora preinstallata), playlist pubblica nelle opzioni → tile "OMG Premium TV" con le sue
  righe (25 gruppi, 333 canali) e un canale in riproduzione nel player. Test strumentato `OmgExtensionTest`.
- ☐ Account Xtream reale, proxy reale, catch-up: non provati (nessun account di prova).
- ☑ Release di OMG con `manifest-zephyrtv.json`: `zephyr-v1.0.0`, costruita dall'Action.
