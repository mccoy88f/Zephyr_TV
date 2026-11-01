# Estensioni di Zephyr TV — standard, versione 1

***[🇬🇧 Read in English](extensions.md)***

> **Stato: bozza.** La versione 1 si sta definendo insieme alla sua prima estensione, OMG Premium TV. Finché non è
> segnata come definitiva, i dettagli possono cambiare senza un nuovo numero di versione.

Un'**estensione** fa quello che fa un add-on Stremio (cataloghi di film, serie e canali TV, pagine dei titoli,
stream) ma gira **sul TV**, dentro Zephyr TV, come un plugin Nuvio. Non c'è un server da installare o tenere acceso:
il codice dell'estensione prende da internet quello che gli serve e risponde direttamente a Zephyr TV.

Richieste e risposte sono **gli oggetti del protocollo degli add-on Stremio**. Gli handler di un add-on Stremio
diventano un'estensione con poche modifiche, e dallo stesso codice si può pubblicare anche un add-on Stremio.
In più rispetto al protocollo, un'estensione può avere **impostazioni** che Zephyr TV mostra sul TV, una **guida TV**,
dati che conserva tra un'esecuzione e l'altra, e i **servizi** di Zephyr TV per i lavori troppo grandi per uno script
(playlist con decine di migliaia di canali, guide da centinaia di megabyte).

Indice:

1. [Il pacchetto](#1-il-pacchetto)
2. [Il manifest](#2-il-manifest) — [cosa fornisce un'estensione](#cosa-fornisce-unestensione), [la sua app](#la-sua-app),
   [i raccoglitori](#i-raccoglitori)
3. [Gli handler](#3-gli-handler)
4. [Gli oggetti](#4-gli-oggetti)
5. [Le impostazioni](#5-le-impostazioni)
6. [L'ambiente di esecuzione](#6-lambiente-di-esecuzione)
7. [I servizi di Zephyr TV](#7-i-servizi-di-zephyr-tv)
8. [Installare, aggiornare, rimuovere](#8-installare-aggiornare-rimuovere)
9. [Sicurezza](#9-sicurezza)
10. [Versioni dello standard](#10-versioni-dello-standard)
11. [Un'estensione minima](#11-unestensione-minima)
12. [In arrivo](#12-in-arrivo)
13. [Esempio: YouYubeZ](#13-esempio-youyubez)

## 1. Il pacchetto

Un'estensione è un solo file **`.zip`**:

```
mia-estensione.zip
├── manifest.json      obbligatorio, nella radice
├── main.js            il codice: un solo file, con le sue dipendenze incluse (vedi 6)
├── icon.png           facoltativa: quadrata, 256×256 o più
└── locales/           facoltativa: i testi del manifest in altre lingue
    ├── it.json
    └── fr.json
```

- Al massimo **10 MB** compresso, **20 MB** decompresso. Percorsi relativi, senza `..`.
- Gli altri file restano nel pacchetto e il codice li legge con `zephyr.asset(percorso)` (vedi 7).
- `locales/<lingua>.json` contiene `{ "name": …, "description": …, "settings": { "<key>": { "label": …, "description": … } } }`:
  Zephyr TV usa il file della lingua del TV, altrimenti i testi del manifest.

## 2. Il manifest

`manifest.json` è un **manifest di add-on Stremio** con alcuni campi propri di Zephyr TV:

```json
{
  "zephyr": 1,
  "id": "org.example.mia-estensione",
  "version": "1.0.0",
  "name": "La mia estensione",
  "description": "Film, serie e canali da …",
  "author": "Nome",
  "icon": "icon.png",
  "main": "main.js",
  "types": ["movie", "series", "tv"],
  "resources": ["catalog", "meta", "stream", "guide"],
  "idPrefixes": ["miaest:"],
  "catalogs": [
    { "type": "tv", "id": "canali", "name": "Canali",
      "extra": [{ "name": "genre", "options": [] }, { "name": "search" }, { "name": "skip" }] }
  ],
  "settings": true,
  "updateUrl": "https://example.org/mia-estensione/manifest-zephyrtv.json",
  "behaviorHints": { "adult": false, "configurationRequired": false }
}
```

| Campo | Obbligatorio | Significato |
|---|---|---|
| `zephyr` | sì | La versione di questo standard per cui l'estensione è scritta: `1`. Zephyr TV rifiuta un'estensione con una versione che non conosce. |
| `id` | sì | Unico e stabile tra le versioni. Si consiglia la notazione a dominio inverso. |
| `version` | sì | La versione dell'estensione, [semver](https://semver.org): `1.4.2`. |
| `name`, `description`, `author` | `name` sì | Come appaiono in Zephyr TV. |
| `icon` | no | Un file del pacchetto, o un indirizzo `https://`. |
| `main` | sì | Il file del codice, nel pacchetto. |
| `types`, `resources`, `idPrefixes`, `catalogs` | come in Stremio | A cosa risponde l'estensione, esattamente come in un [manifest Stremio](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md): una risorsa come nome, o come oggetto con i propri `types` e `idPrefixes`. |
| `settings` | no | `true` quando il codice esporta `settings()` (vedi 5). |
| `updateUrl` | no | L'indirizzo del suo `manifest-zephyrtv.json`, dove si legge l'ultima versione (vedi 8). Consigliato per un'estensione distribuita anche come `.zip`. |
| `behaviorHints.adult` | no | Contenuti V.M. 18: Zephyr TV li mostra solo con i contenuti 18+ sbloccati. |
| `behaviorHints.configurationRequired` | no | Non fa niente finché le impostazioni non sono compilate: Zephyr TV le apre dopo l'installazione. |
| `collectors` | no | I raccoglitori in cui i suoi contenuti possono comparire al posto della sua app: `["movies", "series"]` (vedi [I raccoglitori](#i-raccoglitori)). Un catalogo con `"collector": false` resta nella sua app. |
| `requires` | no | Le funzioni aggiunte a questo standard dopo la 1.0 senza cui l'estensione non funziona: `["libraries"]`. Zephyr TV la rifiuta se gliene manca una (vedi 10). |
| `libraries` | no | Librerie JavaScript **non** nello `.zip`: Zephyr TV le scarica all'installazione (vedi [Librerie](#librerie)). |

Le risorse sono quelle di Stremio, `catalog`, `meta` e `stream`, più `guide` e `featured`, proprie di Zephyr TV (vedi 3).
L'`extra` di un catalogo segue le regole di Stremio: `genre` (con le sue `options`), `search`, `skip`, `isRequired`.
Con `"settings": true` un'estensione può anche ricavare i cataloghi dalle impostazioni (vedi `manifest()` al punto 3).

### Cosa fornisce un'estensione

Un'estensione fornisce una qualsiasi di queste parti, da sola o insieme alle altre, come dicono `resources` e
`catalogs` del suo manifest:

| Fornisce | Con | Esempio |
|---|---|---|
| Solo **cataloghi** | `catalog` (e `meta`) di titoli che riproducono altri | liste di film con id IMDb (`tt…`): gli stream arrivano da add-on, plugin e altre estensioni |
| Solo **stream** | `stream`, per i `types` e gli `idPrefixes` che conosce | link per film e serie di qualsiasi catalogo |
| **Contenuti** completi | `catalog`, `meta` e `stream` dei suoi id | i canali di una playlist, i film e le serie di un account, i video di un sito |
| **Una guida** | `guide` | i programmi dei suoi canali, o di quelli di tutti |

### La sua app

Un'estensione ha **un'app tutta sua** solo se ha **cataloghi elencabili** (righe che si aprono), e solo per i tipi
che non vanno in un raccoglitore. Un catalogo solo di ricerca, i soli stream e la sola guida non fanno un'app.

L'utente sceglie dove compare ogni tipo (**Video**, **Radio**, film…): un'app virtuale che raccoglie tutti, un'app
per add-on o estensione, o da nessuna parte. Sull'estensione, **App virtuale** segue quello (predefinito), oppure
**Mostra** / **Nascondi** la sua tile.

- **Un catalogo**, cioè un'estensione i cui cataloghi elencano titoli che riproducono altri (film e serie con id
  IMDb, senza un suo `stream` per loro), **non ha app**: le sue righe vanno sotto **Film** e **Serie TV**, come quelle
  delle fonti di catalogo. Così anche un'estensione di soli **stream** o di sola **guida**, che non ha righe.
- Un'estensione segnata 18+ ha la sua app solo con i contenuti 18+ sbloccati, o nel posto scelto in **Contenuti per
  adulti**.

### I raccoglitori

Zephyr TV ha dei **raccoglitori**: app virtuali sue, una per ogni tipo di contenuto, che riuniscono le righe di tutte
le estensioni (e gli add-on) che ci mettono quel tipo. Un raccoglitore compare nella riga delle app solo finché
contiene qualcosa.

| Raccoglitore | Valore in `collectors` | Contiene i tipi | In Zephyr TV |
|---|---|---|---|
| **Film** | `"movies"` | `movie`, e i tipi propri di un'estensione | la tile Film, secondo **Dove compaiono film e serie** |
| **Serie TV** | `"series"` | `series` | la tile Serie TV, come sopra |
| **Canali TV** | `"tv"` | `tv` | dove **Dove compaiono i canali TV** mette i canali degli add-on |
| **Radio** | `"radio"` | `radio` | una tile Radio |
| **Video** | `"videos"` | `video`, `channel` | una tile Video |
| **Corsi** | `"courses"` | `course` | una tile Corsi |
| **Podcast** | `"podcasts"` | `podcast` | una tile Podcast |
| **Audiolibri** | `"audiobooks"` | `audiobook` | una tile Audiolibri |
| **Eventi** | `"events"` | `event` | una tile Eventi |

Il manifest dice dove i suoi contenuti **possono** andare, oltre alla sua app:

```json
"collectors": ["movies", "series"]
```

- Senza `collectors`, i contenuti restano nella sua app, se ha cataloghi elencabili.
- Un catalogo del manifest può restarne fuori con `"collector": false`: quel catalogo resta nell'app dell'estensione
  anche quando il resto va nei raccoglitori (una riga che ha senso solo lì, come "Aggiunti di recente al mio
  account").
- Nei raccoglitori, ogni sua riga va nel raccoglitore del suo tipo, se è in `collectors` e quel raccoglitore è un'app
  virtuale. Le righe che restano (tipi non elencati, cataloghi con `"collector": false`) rimangono nella sua app, che
  compare solo finché ne ha.
- **Un catalogo** (vedi sopra) non ha app e non ha bisogno di `collectors`: le sue righe vanno in Film e Serie TV.
- **Le radio** seguono la voce **Radio** di Zephyr TV (Virtual App e Integrazioni): nel raccoglitore Radio da tutte le
  estensioni (predefinito), ognuna nell'app della sua estensione, o da nessuna parte.
- In un raccoglitore, le righe di ogni estensione arrivano nell'ordine delle estensioni (Impostazioni → Funzioni aggiuntive → Estensioni Zephyr),
  dopo quelle degli add-on e delle fonti di catalogo.

### Tipi di contenuto

Ogni contenuto è di uno di questi `types`:

| `type` | Cos'è | La sua pagina |
|---|---|---|
| `movie` | un film | i dettagli, poi Guarda |
| `series` | una serie: `videos` con `season` ed `episode` | stagioni ed episodi |
| `tv` | un canale TV, in diretta: il programma in onda (vedi `guide`), CH+ e CH− nel player | — parte subito |
| `channel` | una raccolta di video (un canale di clip, i contenuti extra di un programma): `videos` senza stagioni | i suoi video, dai più recenti |
| `video` | **Zephyr TV.** Un video a sé (una clip, una registrazione, un intervento) | i dettagli, poi Guarda |
| `course` | **Zephyr TV.** Un corso: le sue lezioni come `videos`, in ordine; una `season` è un modulo | moduli e lezioni; Guarda riprende dalla prima lezione non vista |
| `podcast` | **Zephyr TV.** Un podcast, audio o video: le sue puntate come `videos` | le puntate, dalle più recenti |
| `audiobook` | **Zephyr TV.** Un audiolibro: i suoi capitoli come `videos`, in ordine | i capitoli; Ascolta riprende dove si era rimasti, un capitolo dopo l'altro |
| `radio` | **Zephyr TV.** Una radio, in diretta, solo audio: i suoi cataloghi si elencano come quelli dei canali TV | — parte subito, con logo, nome e brano in onda (ICY o ID3, quando lo stream lo indica) a schermo |
| `event` | **Zephyr TV.** Un evento in diretta a un'ora precisa (una partita, un concerto): `startTime`, `endTime` | prima dell'inizio, il tempo che manca; durante, Guarda; dopo, di nuovo Guarda se ha ancora stream |

- `video`, `channel`, `course`, `podcast`, `audiobook`, `radio` ed `event` sono i **contenuti generici**: compaiono
  nell'app dell'estensione, o nel loro raccoglitore (vedi sopra), ognuno nelle sue righe. Le righe di `event` mostrano prima quelli in
  corso, poi i prossimi, dal più vicino; uno finito da più di un giorno, senza stream, non compare.
- Una `radio` si riproduce come un canale TV (CH+ e CH− passano tra le radio della riga), senza immagine: il suo logo
  su una sfocatura di sé stesso, e il titolo che lo stream invia (metadati ICY), quando lo invia.
- Ogni altro tipo (uno proprio dell'estensione, come `"anime"`) si mostra e si riproduce come un `movie`.
- In un `channel`, `course`, `podcast` o `audiobook`, ognuno dei suoi `videos` si riproduce con `stream` e l'`id` del
  video, come un episodio; alla fine di uno parte da solo il successivo (per un `podcast`, quello precedente nel tempo).
- I contenuti solo audio (un podcast audio, un audiolibro) si riproducono con il `poster`, o il `thumbnail` del
  video, sullo schermo e il tempo in basso; mentre suonano non parte il salvaschermo del TV.
- Quello che si è iniziato va in **Continua a guardare**, come film ed episodi: un corso con la sua lezione, un
  audiolibro con il capitolo e il punto.
- Un `video`, o un video di un `channel`, può dire quanto dura (`runtime`) e quando è stato pubblicato (`released`);
  un video iniziato va in **Continua a guardare**, come film ed episodi.

## 3. Gli handler

`main.js` è un modulo CommonJS. Esporta una funzione per ogni risorsa che offre e, se servono, `manifest()` e
`settings()`. Possono essere tutte `async`. Zephyr TV le chiama con un solo oggetto, `args`:

```js
module.exports = {
  async catalog({ type, id, extra, settings }) { return { metas: [] }; },
  async meta({ type, id, settings })            { return { meta: null }; },
  async stream({ type, id, settings })          { return { streams: [] }; },
  async guide({ settings })                     { return { sources: [] }; },
  async featured({ settings })                  { return { metas: [] }; },
  async manifest({ settings })                  { return { catalogs: [] }; },  // facoltativo
  async settings({ settings })                  { return []; },                // facoltativo, vedi 5
  async verify({ settings })                    { return { cookies: false, loggedIn: false }; }, // facoltativo
};
```

| Handler | Quando | `args` | Risponde |
|---|---|---|---|
| `catalog` | per una riga, una sua pagina, un genere, una ricerca | `type`, `id` (del catalogo), `extra`: `{ genre?, search?, skip? }` | `{ metas: [Meta ridotto] }`; una pagina ha fino a 100 elementi |
| `meta` | quando si apre la pagina di un titolo o di un canale | `type`, `id` | `{ meta: Meta }`, oppure `{ meta: null }` se non lo conosce |
| `stream` | quando qualcosa parte, o se ne elencano gli stream | `type`, `id` (per un episodio `"<id serie>:<stagione>:<episodio>"`); per un programma passato di un canale, `catchup: { start, end }` (vedi 4) | `{ streams: [Stream] }` |
| `guide` | una volta al giorno, e quando cambiano le impostazioni | — | `{ sources: [Fonte della guida] }` (vedi 4) |
| `featured` | per lo Spotlight, e in cima all'app dell'estensione | — | `{ metas: [Meta ridotto] }`, fino a 20, ognuno con un `background` (un'immagine larga) e una `description` |
| `manifest` | quando cambiano le impostazioni, e all'installazione | — | `{ catalogs }`: i cataloghi come sono con queste impostazioni, al posto di quelli del manifest |
| `settings` | quando si apre la pagina delle opzioni | — | i campi (vedi 5) |
| `verify` | dopo il salvataggio di un campo `cookies` | — | `{ cookies, loggedIn, catalogs? }`: se i cookie sembrano validi, se la fonte ha accettato la sessione, e un conteggio per catalogo |

Ogni handler riceve `settings`, i valori scelti dall'utente (vedi 5). Una risposta può avere il `cacheMaxAge` di
Stremio (in secondi): Zephyr TV la conserva per quel tempo. Senza, i cataloghi si conservano 6 ore, `meta` un giorno,
`stream` per niente.

Un handler che va in errore, o che risponde oltre il suo tempo (vedi 6), vale come risposta vuota; l'errore va in
**Impostazioni → Sistema → Problemi recenti**.

Zephyr TV chiede a un'estensione solo quello che il suo manifest dichiara: i `types` e gli `idPrefixes` della risorsa.
Film e serie con id IMDb (`tt…`) si possono chiedere a ogni estensione che dichiara `tt`, come per gli add-on.

## 4. Gli oggetti

Gli oggetti sono [quelli di Stremio](https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api/responses).
Qui c'è quello che Zephyr TV legge, e i pochi campi che aggiunge; il resto viene ignorato.

**Meta ridotto** (in un catalogo) e **Meta** (in una pagina):

| Campo | Note |
|---|---|
| `id`, `type`, `name` | obbligatori |
| `poster`, `background`, `logo` | indirizzi; per un canale, `logo` è il suo logo (senza `logo` si usa `poster`) |
| `description`, `releaseInfo`, `genres`, `imdbRating`, `runtime` | come in Stremio; i `genres` di un canale sono i suoi gruppi (le sue righe) |
| `videos` | gli episodi di una serie: `id`, `season`, `episode`, `title`, `released`, `thumbnail`, `overview` |
| `epgId` | **Zephyr TV.** L'id del canale nella guida (il `tvg-id` di una playlist M3U): vedi la guida qui sotto |
| `catchup` | **Zephyr TV.** I programmi passati del canale si possono rivedere: `{ "days": 7, "url"?: "<modello>" }` (vedi Catch-up qui sotto) |
| `startTime`, `endTime` | **Zephyr TV.** Di un `event`, in ISO 8601 (`"2026-11-02T20:45:00+01:00"`) |

Per film e serie, le immagini mostrate da Zephyr TV vengono dal **catalogo principale** quando conosce il titolo
(dal suo id); quelle dell'estensione sono la riserva.

**Stream**:

| Campo | Note |
|---|---|
| `url` | obbligatorio: HLS, DASH, oppure l'indirizzo di un file o di un MPEG-TS |
| `name`, `title` / `description` | mostrati nella lista degli stream; qualità e lingua si leggono da qui ("1080p", "ITA") |
| `behaviorHints.proxyHeaders.request` | gli header con cui riprodurlo: `{ "User-Agent": …, "Referer": … }` |
| `behaviorHints.bingeGroup` | come in Stremio: l'episodio successivo parte con uno stream dello stesso gruppo |
| `behaviorHints.audioOnly` | **Zephyr TV.** `true`: è solo audio (vedi i tipi qui sopra); un indirizzo `.mp3`, `.m4a`, `.aac`, `.ogg` o `.opus` è audio comunque |
| `behaviorHints.audioUrl` | **Zephyr TV.** Un secondo indirizzo: `url` è solo video, questo è l'audio. Il player li riproduce insieme (formati adaptive di YouTube) |
| `drm` | **Zephyr TV.** `{ "clearkey": { "<key id hex>": "<chiave hex>" } }` per i DASH protetti con ClearKey |
| `externalUrl` | al posto di `url`: un indirizzo che apre un'altra app, cioè il link di un'app di musica o video (`https://…`, o il suo schema, come `spotify:…`). Zephyr TV lo apre con l'app che lo gestisce; Indietro riporta a Zephyr TV |
| `ytId` | al posto di `url`: l'id di un video YouTube; Zephyr TV lo apre nell'app YouTube |
| `behaviorHints.androidPackage` | **Zephyr TV.** Con `externalUrl`: l'app con cui aprirlo per prima (`"com.example.app"`), se è installata |

Uno stream con `externalUrl` o `ytId` compare nella lista con l'icona dell'app che lo apre. Se nessuna app del TV lo
gestisce, non compare. Quello che si riproduce in un'altra app non ha un punto in Continua a guardare.
Gli stream `infoHash` (torrent) non si riproducono (vedi 12).

**Catch-up.** Un canale con `catchup` ha i suoi programmi passati, fino a `days` giorni prima, riproducibili dalla
guida, e **Dall'inizio** per quello in onda. Zephyr TV ottiene lo stream di un programma in uno di due modi:

- Con `url`, un modello: Zephyr TV lo compila e lo riproduce, senza chiedere all'estensione. I segnaposto, come li
  scrivono le playlist M3U: `{utc}` e `{utcend}` (inizio e fine, secondi Unix), `{lutc}` (adesso), `{duration}`
  (secondi), `{offset}` (secondi da quando è iniziato), `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` (l'inizio, nell'ora locale
  del canale).
- Senza `url`: Zephyr TV chiama `stream` con `catchup: { start, end }` (secondi Unix), e riproduce quello che risponde.

`zephyr.m3u` legge `catchup`, `catchup-source` e `catchup-days` di una playlist nello stesso oggetto (vedi 7).

**Fonte della guida** (nella risposta di `guide`): `{ "url": "https://…/guida.xml.gz", "headers"?: {…} }`, un file
[XMLTV](https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd), normale o gzip. Zephyr TV lo scarica e lo legge da
solo. I programmi di un canale vengono prima dalle fonti dell'estensione, abbinati con il suo `epgId`, poi con il nome
(senza badare a maiuscole, accenti e parole di qualità come HD); poi dalla guida impostata nelle impostazioni di
Zephyr TV.

## 5. Le impostazioni

Con `"settings": true`, `settings()` restituisce i campi della pagina delle opzioni, con lo stesso schema dei plugin
Nuvio e un tipo in più, `list`:

| `type` | Mostra | Campi |
|---|---|---|
| `header` | un titolo | `label` |
| `info` | una nota | `label`, `description` |
| `text` | un testo: la pagina **Inserisci dal telefono…** di Zephyr TV (codice QR), oppure scritto con il telecomando | `key`, `label`, `description`, `placeholder`, `defaultValue`, `isPassword` |
| `select` | una scelta da una lista | `key`, `label`, `description`, `options: [{ label, value }]`, `defaultValue` |
| `toggle` | sì o no | `key`, `label`, `description`, `defaultValue` (booleano) |
| `list` | **Zephyr TV.** Elementi che l'utente aggiunge, modifica, sposta e rimuove | `key`, `label`, `description`, `defaultValue` (array di elementi), `item`: i campi di un elemento (senza altre `list`), `itemLabel`: la chiave il cui valore dà il nome a un elemento |

I valori sono JSON: una `list` è un array di oggetti, uno per elemento, con le `key` dei suoi campi. Una `list` senza
valore usa `defaultValue`. L'utente può aggiungere quanti elementi vuole.

```js
async settings() {
  return [
    { type: "list", key: "playlists", label: "Playlist M3U", itemLabel: "name",
      item: [
        { type: "text", key: "name", label: "Nome" },
        { type: "text", key: "url", label: "Indirizzo", placeholder: "https://…/lista.m3u" },
      ] },
    { type: "text", key: "proxy", label: "Proxy", description: "Facoltativo." },
    { type: "toggle", key: "proxyOnly", label: "Solo tramite proxy", defaultValue: false },
  ];
}
```

I valori arrivano a ogni handler come `args.settings` (e come `globalThis.SCRAPER_SETTINGS`, come per i plugin Nuvio).
Quando cambiano, Zephyr TV richiama `manifest()` (se esportato) e `guide()`, e rilegge i cataloghi.
Un campo con `key` `cookies` chiama anche `verify()` (se esportato), poi rilegge i cataloghi.
I valori restano solo sul TV, anche nei backup di Zephyr TV; quelli `isPassword` appaiono nascosti.

## 6. L'ambiente di esecuzione

Il codice gira in **QuickJS** (ES2023: `async`/`await`, classi, `Map`, `Proxy`, …), in un contesto suo a ogni
chiamata. Non è Node.js né un browser; Zephyr TV gli offre:

- `fetch`, `Headers`: come in un browser, ma si possono leggere il `set-cookie` di una risposta e i redirect; le
  richieste seguono i redirect e non hanno limiti CORS.
- `URL`, `URLSearchParams`, `TextEncoder`, `TextDecoder`, `Buffer` (base64, hex, utf-8), `atob`, `btoa`.
- `setTimeout`, `clearTimeout`, `console` (`log`, `warn`, `error`: in Problemi recenti, nelle build di debug).
- `localStorage`: dell'estensione, stringhe per chiave, conservato tra le chiamate (vedi 7).
- `require()` di `cheerio` (l'HTML letto come fa jQuery) e `crypto-js`, e delle [librerie](#librerie) scaricate
  dell'estensione. Altri pacchetti vanno inclusi in `main.js` (per esempio con esbuild:
  `esbuild src/index.ts --bundle --platform=neutral --format=cjs --outfile=main.js`).
- `zephyr`: i servizi di Zephyr TV (vedi 7).

Limiti per ogni chiamata: **25 secondi** (per `stream`; `catalog` e `guide` 60), **15 secondi** per ogni richiesta,
**64 MB** di memoria. Niente file, niente WebSocket, nessun accesso alle altre estensioni o alle impostazioni di
Zephyr TV.

## 7. I servizi di Zephyr TV

L'oggetto globale `zephyr`:

| | |
|---|---|
| `zephyr.version` | la versione di Zephyr TV, `"2.3.0"` |
| `zephyr.standard` | la versione più recente di questo standard che conosce, `1` |
| `zephyr.language`, `zephyr.region` | quelle del TV: `"it"`, `"IT"` |
| `zephyr.asset(percorso)` | un file del pacchetto, come testo (`await`) |
| `zephyr.has(nome)` | se questo Zephyr TV ha una funzione aggiunta dopo la 1.0 (vedi 10) |
| `zephyr.m3u` | playlist M3U, lette da Zephyr TV (qui sotto) |

**`zephyr.m3u`** legge una playlist in modo nativo, così una lista di decine di migliaia di canali non deve mai stare
nella memoria dello script. Zephyr TV la scarica, la legge e la conserva sul TV; il codice ne chiede delle pagine:

```js
// Legge la playlist (di nuovo se è più vecchia di maxAge secondi): i suoi gruppi e quanti canali ha.
const lista = await zephyr.m3u.load("principale", { url, headers, maxAge: 86400 });
// → { channels: 18234, groups: ["News", "Sport", …], guides: ["https://…/epg.xml.gz"] }

// Una pagina dei suoi canali, per gruppo o per parole del nome.
const pagina = await zephyr.m3u.channels("principale", { group: "Sport", search: "", skip: 0, limit: 100 });
// → [{ id, name, radio, group, logo, epgId, catchup?, streams: [{ url, name, headers, clearkey }] }]

// Un canale, dall'id che ha nella playlist.
const canale = await zephyr.m3u.channel("principale", id);
```

- Il primo argomento dà il nome alla playlist (`"principale"`, o uno per ogni elemento di una `list`); appartiene
  all'estensione.
- L'id di un canale resta lo stesso finché la playlist mantiene il suo `tvg-id`, o il suo nome.
- Lo stesso canale in più qualità ("Canale HD", "Canale FHD", stesso `tvg-id`, o stesso nome a parte la qualità) è un
  canale solo con più `streams`.
- Per ogni canale si leggono: `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `#EXTVLCOPT` e `#EXTHTTP` (header),
  `#KODIPROP` `inputstream.adaptive.license_key` (ClearKey). Gli `url-tvg` / `x-tvg-url` della playlist sono le sue
  `guides`.
- Un canale con `radio="true"` nel suo `#EXTINF` è una radio: `radio: true`. `channels` accetta `kind`, `"tv"` o
  `"radio"`, per gli uni o le altre; il riepilogo di `load` li conta a parte (`channels` e `groups` per i canali TV,
  `radios` e `radioGroups` per le radio), così un'estensione dà alle radio cataloghi propri (`type: "radio"`).
- `zephyr.m3u.forget(nome)` cancella una playlist conservata.
- `load` accetta `text` al posto di `url`: una playlist scritta dall'estensione stessa (per esempio dalle API di un
  account), letta e conservata allo stesso modo; viene riletta a ogni chiamata così.

`localStorage` è dell'estensione (`getItem`, `setItem`, `removeItem`, `clear`, `key`, `length`), solo stringhe, al
massimo 1 MB. Resta tra le chiamate e si cancella con l'estensione.

## 8. Installare, aggiornare, rimuovere

**Impostazioni → Funzioni aggiuntive → Estensioni Zephyr**:

- **Aggiungi un'estensione**: l'indirizzo del suo **`manifest-zephyrtv.json`** (qui sotto), scritto o incollato dal
  telefono, oppure il suo `.zip` inviato dal telefono (codice QR). Zephyr TV ne mostra nome, autore, cosa offre e che
  esegue codice scritto da altri, e la installa dopo la conferma.
- Ogni estensione: **Attiva**, **Opzioni**, **Contenuti V.M. - Adulti**, **Sposta su / giù** (le sue righe arrivano
  in quest'ordine), **Aggiorna ora**, **Rimuovi**. Rimuoverla cancella le sue impostazioni, il suo `localStorage`,
  le sue playlist e le sue librerie.
- Durante l'installazione e l'aggiornamento, Zephyr TV mostra **lo stato** (il pacchetto, ogni libreria) e **la
  percentuale** scaricata. Si aspetta la fine: un'estensione con `libraries` non è pronta prima.
- **Aggiungi un'estensione** accetta anche l'indirizzo di un **repository** (il suo `repository-zephyrtv.json`, qui
  sotto): Zephyr TV aggiunge il repository e ne mostra le estensioni.
- Le estensioni dei repository non installate compaiono dopo quelle installate: OK ne installa una dal suo indirizzo.
  Ogni repository ha la sua riga: le sue estensioni, **Rileggi l'elenco**, **Togli il repository** (le estensioni
  installate da lì restano). Zephyr TV non contiene estensioni; ha **il suo repository** fin dall'inizio
  (OMG Premium TV, Webcam dal vivo e le altre di
  [Zephyr-TV-Extensions](https://github.com/mccoy88f/Zephyr-TV-Extensions)), finché l'utente non lo toglie.

### Librerie

Una libreria è un file **JavaScript CommonJS** (`module.exports`) troppo grande o troppo spesso aggiornato per
stare nello `.zip`. Non è un binario, non è `npm install` sul TV, non è Node.js.

```json
"libraries": [
  {
    "name": "youtubei.js",
    "version": "15.0.1",
    "url": "https://example.org/youyubez/youtubei.js",
    "sha256": "ab12…"
  }
]
```

| Campo | Obbligatorio | Significato |
|---|---|---|
| `name` | sì | Quello che il codice passa a `require("youtubei.js")`. Unico nell'elenco. |
| `version` | sì | Semver della libreria, indipendente da quella dell'estensione. |
| `url` | sì | File unico, già bundleato (esbuild `--format=cjs --platform=neutral`). Indirizzo `https://`, o relativo al `manifest-zephyrtv.json`. |
| `sha256` | sì | SHA-256 del file, in esadecimale: un file che non corrisponde viene rifiutato. |

- Non stanno nello `.zip`. Zephyr TV le scarica **dopo** il pacchetto, nella cartella dell'estensione
  (`lib/`), e le lascia lì quando si aggiorna solo lo `.zip`.
- Al massimo **20 MB** ciascuna. Un'estensione che non può farne a meno mette `"requires": ["libraries"]`.
- **Aggiorna ora** (e il controllo giornaliero) confronta `version` e `sha256` del `manifest-zephyrtv.json` con
  quelle sul TV: si può aggiornare [youtubei.js](https://www.npmjs.com/package/youtubei.js) senza un nuovo
  pacchetto dell'estensione.
- Rimuovere l'estensione cancella anche le librerie.

### `manifest-zephyrtv.json`

Un'estensione si pubblica su internet con un file JSON chiamato **`manifest-zephyrtv.json`**, accanto al suo `.zip` o
altrove. Il suo indirizzo è quello che gli utenti aggiungono, e quello che Zephyr TV legge per gli aggiornamenti:

```json
{
  "zephyr": 1,
  "id": "org.example.mia-estensione",
  "version": "1.1.0",
  "name": "La mia estensione",
  "description": "Film, serie e canali da …",
  "author": "Nome",
  "icon": "https://example.org/mia-estensione/icon.png",
  "package": "mia-estensione-1.1.0.zip",
  "sha256": "9f2c…",
  "adult": false,
  "libraries": [
    { "name": "youtubei.js", "version": "15.0.1", "url": "youtubei.js", "sha256": "ab12…" }
  ]
}
```

| Campo | Obbligatorio | Significato |
|---|---|---|
| `zephyr`, `id`, `version` | sì | Come nel `manifest.json` del pacchetto, che deve avere gli stessi valori. |
| `name`, `description`, `author`, `icon` | `name` sì | Mostrati prima dell'installazione, senza scaricare il pacchetto. |
| `package` | sì | Lo `.zip`: un indirizzo, o un percorso relativo all'indirizzo di questo file. |
| `sha256` | sì | Lo SHA-256 del pacchetto, in esadecimale: un pacchetto che non corrisponde viene rifiutato. |
| `adult` | no | Contenuti V.M. 18: quando si aggiunge, la casella 18+ è già spuntata. |
| `libraries` | no | Come nel `manifest.json` del pacchetto. Gli `url` relativi sono rispetto a questo file. **Aggiorna ora** rilegge
  queste voci: una libreria con `version` o `sha256` nuovi si scarica di nuovo, anche se la versione dell'estensione
  è la stessa. |

È fisso solo il nome del file: Zephyr TV accetta un indirizzo che finisce con `manifest-zephyrtv.json`, oppure la
cartella che lo contiene (`https://example.org/mia-estensione/` legge
`https://example.org/mia-estensione/manifest-zephyrtv.json`).

### `repository-zephyrtv.json`

Un **repository** pubblica molte estensioni a un solo indirizzo: un file JSON chiamato **`repository-zephyrtv.json`**
che elenca i loro `manifest-zephyrtv.json`:

```json
{
  "zephyr": 1,
  "name": "Le mie estensioni",
  "extensions": [
    "webcams/manifest-zephyrtv.json",
    "https://example.org/altra/manifest-zephyrtv.json"
  ]
}
```

| Campo | Obbligatorio | Significato |
|---|---|---|
| `zephyr` | sì | `1`. Un repository per una versione più nuova viene rifiutato. |
| `name` | sì | Mostrato in Zephyr TV. |
| `extensions` | sì | Gli indirizzi dei `manifest-zephyrtv.json` delle sue estensioni: assoluti, o relativi a quello del repository. |

Zephyr TV legge un repository una volta al giorno, con il `manifest-zephyrtv.json` di ogni sua estensione (non i
pacchetti). Un'estensione nuova nell'elenco compare tra quelle da installare: nessuna si installa da sola. Quelle 18+
compaiono solo con i contenuti 18+ sbloccati. Un'estensione installata da un repository si aggiorna dal suo
indirizzo, come le altre.

### Link

Un link installa un'estensione con un tocco, su un telefono o su una TV che apre i link:

- `ztv://ext?u=<indirizzo del suo manifest-zephyrtv.json>`
- oppure, per le chat, che rendono cliccabili solo gli `https`:
  `https://mccoy88f.github.io/Zephyr_TV/open/#ext?u=<indirizzo del suo manifest-zephyrtv.json>`

L'indirizzo in `u` va codificato per URL. Zephyr TV mostra cos'è l'estensione e chi l'ha fatta prima di installarla.
Gli stessi link accettano `addon?u=` (il `manifest.json` di un add-on Stremio), `nuvio?u=` (un repository Nuvio) e
`title/<tipo>/<id>?n=<nome>` (la pagina di un film o di una serie); add-on e repository si aggiungono solo dopo la
conferma.

### Aggiornamenti

Zephyr TV legge il `manifest-zephyrtv.json` di un'estensione una volta al giorno: l'indirizzo da cui è stata aggiunta
oppure, per una installata da `.zip`, il suo `updateUrl`. Una `version` più nuova, per uno `zephyr`
che conosce, viene scaricata, verificata con `sha256` e installata al posto della vecchia, mantenendo impostazioni,
dati e librerie. L'`id` non può cambiare. Le `libraries` del file pubblicato si scaricano se mancano o se `version` o
`sha256` sono cambiati, anche a parità di versione dell'estensione. Un'estensione senza nessuno dei due indirizzi si
aggiorna solo aggiungendo un suo nuovo `.zip` (le librerie, se ha un `updateUrl` solo per loro, seguono quel file).

## 9. Sicurezza

Un'estensione è codice scritto da altri. Zephyr TV la esegue separata da tutto il resto: internet, le sue impostazioni
e i suoi dati, i servizi qui sopra, le librerie che ha dichiarato, nient'altro. Non può leggere file, altre app, altre
estensioni, né impostazioni, account e chiavi di Zephyr TV; non può avviare niente. Quello che invia su internet
dipende da lei: installa solo estensioni di cui ti fidi. Un campo per i cookie (YouTube o altro) è l'accesso a
quell'account: resta sul TV, nascosto, e va incollato dall'utente.

## 10. Versioni dello standard

- `zephyr` nel manifest è la versione per cui un'estensione è scritta. Uno Zephyr TV che conosce le versioni fino a N
  installa le estensioni di versione N o precedenti, e rifiuta quelle più nuove, invitando ad aggiornare Zephyr TV.
- Dentro una versione, le modifiche si limitano ad aggiungere: nuovi campi facoltativi, nuovi servizi, nuovi tipi di
  impostazione. Ogni aggiunta ha un **nome** (elencato in **Novità**). Un'estensione che non può farne a meno lo mette
  in `requires`: uno Zephyr TV che non ce l'ha non la installa, e invita ad aggiornare Zephyr TV. Una che può farne a
  meno chiede `zephyr.has("subtitles")` e la usa solo se c'è. La versione 1.0 non ha nomi: uno Zephyr TV che conosce
  la versione 1 conosce tutta la 1.0.
- Quello che romperebbe un'estensione scritta per una versione precedente va in una nuova versione; Zephyr TV continua
  a eseguire le vecchie come prima.
- Ogni versione ha la sua sezione in **Novità**, qui sotto.

### Novità

- **Versione 1.0** (bozza): il pacchetto, il manifest, i tipi `movie`, `series`, `tv`, `channel`, `video`, `course`, `podcast`
  e `audiobook`, gli stream `externalUrl` e `ytId`, gli stream solo audio,
  `manifest-zephyrtv.json`, `catalog`, `meta`, `stream`, `guide`,
  `manifest`, `settings` con `list`, `featured`, `radio`, `event`, i raccoglitori, il catch-up, `requires` e `zephyr.has()`,
  `zephyr.m3u`, `localStorage`, `repository-zephyrtv.json`.
- **Dopo 1.0:** `libraries` — librerie CommonJS scaricate all'installazione, `require()` dal loro `name`, stato e
  percentuale sullo schermo, aggiornamento della sola libreria con **Aggiorna ora**.

## 11. Un'estensione minima

`manifest.json`:

```json
{
  "zephyr": 1, "id": "org.example.ciao", "version": "1.0.0", "name": "Ciao", "main": "main.js",
  "types": ["tv"], "resources": ["catalog", "meta", "stream"], "idPrefixes": ["ciao:"],
  "catalogs": [{ "type": "tv", "id": "ciao", "name": "Ciao" }]
}
```

`main.js`:

```js
const CANALE = { id: "ciao:1", type: "tv", name: "Canale di prova", logo: "https://example.org/logo.png" };

module.exports = {
  async catalog() { return { metas: [CANALE] }; },
  async meta({ id }) { return { meta: id === CANALE.id ? CANALE : null }; },
  async stream({ id }) {
    return { streams: id === CANALE.id ? [{ name: "HD", url: "https://example.org/live/index.m3u8" }] : [] };
  },
};
```

Comprimi i due file (`zip ciao.zip manifest.json main.js`) e aggiungilo da **Estensioni → Aggiungi un'estensione**. Per
pubblicarla, metti online `ciao.zip` con accanto un `manifest-zephyrtv.json`:

```json
{ "zephyr": 1, "id": "org.example.ciao", "version": "1.0.0", "name": "Ciao",
  "package": "ciao.zip", "sha256": "<sha256sum ciao.zip>" }
```

e aggiungi il suo indirizzo: `https://example.org/ciao/manifest-zephyrtv.json`.

## 12. In arrivo

Previsti per le prossime aggiunte alla versione 1. I loro nomi sono **riservati**: un'estensione non li usa per
altro (come handler, `type`, campo o servizio di `zephyr`). Le forme qui sotto sono indicative; ognuna entra nello
standard, con il suo nome per `requires`, quando Zephyr TV la implementa.

| Nome | Cosa sarà | Forma indicativa |
|---|---|---|
| `subtitles` | sottotitoli esterni, come la risorsa di Stremio | handler `subtitles({ type, id, extra })` → `{ subtitles: [{ id, url, lang }] }`, SRT o WebVTT |
| `progress` | quello che si guarda, comunicato all'estensione (riprendere altrove, segnare come visto) | handler `progress({ type, id, position, duration, state })`, `state`: `playing`, `paused`, `stopped`, `ended` |
| `auth` | accesso dal telefono con un codice (device code) | `zephyr.auth.deviceCode({ url, code, expires })`: Zephyr TV mostra il codice e un QR finché l'estensione non dice che è fatto |
| `resolver` | estensioni che trasformano il link di un sito di hosting in uno stream, per le altre | risorsa `resolve({ url })` → `{ streams }`; `zephyr.resolve(url)` per le altre |
| `webview` | pagine dietro un controllo del browser | `zephyr.webview({ url, until })` → `{ cookies, html }`, in una WebView nascosta |
| `trailers` | trailer nella pagina di un film o di una serie | `trailerStreams` del Meta, come in Stremio |
| `schedule` | lavoro in background ogni qualche ora (rileggere una playlist, rinnovare un token) | handler `schedule({ settings })`, con `scheduleHours` nel manifest |
| `subtle` | cifrature e hash veloci | `crypto.subtle` (`digest`, AES, HMAC) e `crypto.getRandomValues` |
| `widevine` | DASH protetti con Widevine | `drm: { "widevine": { "license": "<url>", "headers"?: {…} } }` |

Non previsti: stream torrent (`infoHash`), WebSocket, pacchetti firmati.

## 13. Esempio: YouYubeZ

**YouYubeZ** è un'estensione di esempio per ricerca, playlist e riproduzione YouTube. **Non è affiliata, sostenuta
né collegata a YouTube o a Google.** YouTube è un marchio di Google LLC. Usa la libreria indipendente
[youtubei.js](https://www.npmjs.com/package/youtubei.js) (client InnerTube, metadati ed extractor), scaricata come
`libraries` e non inclusa nello `.zip`.

Scheletro: [Zephyr-TV-Extensions/youyubez](https://github.com/mccoy88f/Zephyr-TV-Extensions/tree/main/youyubez).

- **Riproduci con** (opzioni): **App YouTube nativa** (`ytId`), **Player Zephyr TV** (stream da InnerTube, video più
  audio accoppiato), o **Entrambi** (due voci nella lista degli stream).
- **Cookie**: la pagina **Inserisci dal telefono…** di Zephyr TV (`isPassword`). Servono per **Iscrizioni**,
  **Guarda dopo** e **Cronologia**. Dopo l'incolla, Zephyr TV chiama `verify()` e rilegge le liste.
- **Sezioni visibili** (`list` di `name` + `keywords`): righe di video da una ricerca per parole chiave. **Scopri** e
  **Tendenze** sono le due voci di default; l'utente può cambiarle, toglierle o aggiungerne quante ne vuole. La ricerca
  resta in elenco. Le liste dell'account restano legate ai cookie.
- `requires`: `["libraries"]`. `collectors`: `["videos"]`.
