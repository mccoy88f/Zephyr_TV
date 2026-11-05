# Piano: torrent in streaming (motore "Zephyr Torrent")

Questo documento serve a riprendere il lavoro da un'altra sessione, umana o di Claude. Contiene:

- le decisioni già prese;
- dove mettere le mani nel codice;
- le fasi con le caselle da spuntare;
- le regole del progetto da rispettare.

**Stato:** solo il piano, nessun codice scritto. L'ultima release è la 4.1.0, con il download di film ed episodi, il file locale come prima sorgente e la scelta della cartella dei download.

---

## 1. Obiettivo

Riprodurre in streaming gli stream torrent degli add-on Stremio (`infoHash` + `fileIdx`) e poterli scaricare. Il motore scarica per primi i pezzi del file che servono al player, cioè quelli del punto in cui si trova o in cui salta.

## 2. Decisioni prese

| Tema | Decisione | Perché |
|---|---|---|
| Libreria | **libtorrent4j**: libreria Java sopra libtorrent-rasterbar | Mantenuta, la usa LibreTorrent (aggiornato a luglio 2026). TorrentStream-Android è archiviato da maggio 2023 e la sua v3 si appoggiava comunque a libtorrent4j. |
| Logica di streaming | **Scritta da noi**, prendendo spunto da TorrentStream-Android | Sono poche centinaia di righe. Se si copia codice, verificare prima la licenza. |
| Dove gira il motore | **APK separato "Zephyr Torrent"**, nello stesso repository e firmato con la stessa chiave | L'APK principale resta leggero (il motore pesa diversi MB per tipo di processore). Si installa solo se serve, si aggiorna da solo, e un suo crash non tocca la home. |
| Ruolo del player | **Solo un client sottile**: riceve un indirizzo `http://127.0.0.1:<porta>/<token>/...`, mostra lo stato del caricamento e ferma il torrent all'uscita | `PlayerActivity` gira già in un processo a sé (`:player`) e non va appesantita con la gestione dei pezzi. |
| Installazione | Da **Impostazioni → Contenuti → Motore torrent**, con PackageInstaller come fa già `Updater.kt` | L'app ha già il permesso `REQUEST_INSTALL_PACKAGES` e l'installazione con sessione. |
| Default | Seeding **spento**; cache con limite (**2 GB**, regolabile) e **svuotata all'arresto** | I TV hanno poca memoria. Esporsi il meno possibile. |

Alternativa scartata: motore dentro Zephyr TV in un processo `:torrent`, come `PluginService`/`ExtensionService`. È più semplice, ma tutti si troverebbero l'APK più pesante, anche chi non usa i torrent.

## 3. Come funziona lo streaming

1. Zephyr TV chiama il servizio di Zephyr Torrent con `start(infoHash, fileIdx, trackers, name)`. Il servizio risponde con un indirizzo locale.
2. Il motore aggiunge il torrent, cioè il magnet costruito da infoHash + tracker. Tutti i file hanno priorità 0 tranne `fileIdx`.
3. Un server HTTP raggiungibile solo da `127.0.0.1` serve quel file e gestisce le richieste a intervalli (`Range`). Il percorso contiene un token casuale, così le altre app del TV non possono usarlo.
4. Per ogni richiesta, l'offset in byte diventa il numero di un pezzo. Quel pezzo e i successivi N, da regolare sulla velocità, ricevono una scadenza con `set_piece_deadline` e la massima priorità. La lettura aspetta che i pezzi arrivino con `read_piece` o dal file su disco, con un timeout.
5. All'avvio si scaricano per primi anche gli **ultimi** pezzi: l'indice degli MP4 (`moov`) e le informazioni finali degli MKV.
6. ExoPlayer vede un normale indirizzo http: salti, buffering e la verifica dello stream funzionano.

## 4. Dove toccare il codice (Zephyr TV)

Percorsi relativi a `app/src/main/java/link/mccoy88f/zephyr/`.

- **`Stremio.kt`**
  - `streams(answer, vod, external)`, verso la riga 555: oggi scarta gli stream senza indirizzo web, quindi anche i torrent (vedi il commento verso la riga 549). Va letto anche `infoHash`, con `fileIdx` e `sources` (tracker `tracker:` e `dht:`).
  - `Stremio.Stream`, verso la riga 123: aggiungere `infoHash: String?`, `fileIdx: Int?` e `trackers: List<String>`, con default che non rompono le chiamate esistenti.
  - `Stremio.probe`, verso la riga 624: per un torrent non c'è niente da verificare, quindi risponde `Probe(true, null)`. Lo fa già per gli indirizzi non http.
- **`PlayerActivity.kt`**
  - `streamJson` e `streamOf`, verso la riga 2268: aggiungere i campi torrent.
  - `start(s, format, …)`, verso la riga 1865: se `s.infoHash != null`, collegarsi al servizio, chiamare `start`, aspettare l'indirizzo e poi riprodurre con l'indirizzo http. Mostrare lo stato (peer, velocità, buffer) nella riga di stato esistente (`say(...)`).
  - Chiamare `stop` all'uscita, al cambio di stream o di episodio, e in `next()` quando lo stream fallisce.
  - Se il motore non è installato, saltare lo stream torrent e passare al successivo.
- **`TitlePages.kt`**
  - `pickStream`: mostrare gli stream torrent solo con il motore installato; altrimenti una riga "Installa il motore torrent".
  - `downloadableStream`, in `Downloads.kt`: oggi un torrent è escluso perché non è http. Nella fase 4 va accettato e mandato al motore.
- **`Downloads.kt`** e **`DownloadFiles.kt`**: oggi `Downloads.Entry` conosce solo i download fatti dal download manager di Android. Nella fase 4 serve un tipo di voce "torrent": file, stato e percentuale li dà il motore. Il resto (file locale come prima sorgente, condividi, cartella) deve funzionare uguale.
- **`Updater.kt`** e **`Updates.kt`**: `Updater.install` scarica `ZephyrTV.apk` dalla release. Va generalizzato al nome dell'APK, così da installare anche `ZephyrTorrent.apk` con la stessa sessione PackageInstaller. La versione installata si legge con `packageManager.getPackageInfo("link.mccoy88f.zephyr.torrent", 0)`.
- **`SettingsPanel.kt`** e **`SettingsRows.kt`**: nuova riga in Contenuti, dopo "Cartella dei download" (oggi `DOWNLOAD_FOLDER_ROW = WATCH_SECTION * SECTION + 13`). Gli indici delle righe sono scritti a mano: aggiornarli tutti.
- **`.github/workflows/release.yml`**: costruire anche il modulo `torrent` e pubblicare `ZephyrTorrent.apk` nella stessa release (`gh release create ... ZephyrTV.apk ZephyrTorrent.apk`).

## 5. Le fasi

### Fase 0: prova (decide se procedere)
- [ ] App minima con libtorrent4j (AAR arm64-v8a e armeabi-v7a), che apre un magnet di prova legale (per esempio Big Buck Bunny) e lo serve su 127.0.0.1.
- [ ] Misurare su un TV vero:
  - peso dell'APK per tipo di processore;
  - tempo dal magnet al primo fotogramma;
  - RAM e CPU su un TV economico con 1,5–2 GB di RAM e processore a 32 bit;
  - tenuta in 1080p e in 4K.
- [ ] Verificare che il traffico passi da WARP quando è attivo (`Warp.kt`, `Privacy.kt`).

### Fase 1: APK Zephyr Torrent
- [ ] Nuovo modulo Gradle `torrent/`, con applicationId `link.mccoy88f.zephyr.torrent`, stessa chiave di firma e stessi `keystore.properties`.
- [ ] Servizio esportato **solo** con un permesso `signature` dichiarato da Zephyr Torrent e richiesto da Zephyr TV. Comunicazione tramite Messenger o AIDL: `start`, `status`, `stop`, `download`, `cancel`.
- [ ] Server HTTP locale con `Range`, token casuale e una sola sessione attiva in streaming.
- [ ] Priorità ai pezzi come descritto nella sezione 3, con gestione dei salti (le scadenze precedenti vengono annullate).
- [ ] Cache nel cacheDir di Zephyr Torrent, con limite configurabile e svuotata all'arresto. Seeding spento di default.
- [ ] Notifica o servizio in primo piano solo durante un download completo (fase 4). Per lo streaming vive finché il player è collegato.

### Fase 2: installazione dalle Impostazioni
- [ ] `release.yml` pubblica anche `ZephyrTorrent.apk`.
- [ ] Impostazioni → Contenuti → **Motore torrent**:
  - installa / aggiorna / disinstalla, quest'ultimo con `REQUEST_DELETE_PACKAGES`, già presente;
  - versione installata;
  - spazio massimo della cache;
  - seeding sì/no.
- [ ] Controllo degli aggiornamenti del motore insieme a quello di Zephyr TV (`Updates.kt`).

### Fase 3: torrent tra le sorgenti
- [ ] Lettura di `infoHash`/`fileIdx`/`sources` in `Stremio.kt`.
- [ ] Stream torrent nella scelta automatica e nell'elenco manuale, solo con il motore installato.
- [ ] Player: collegamento al servizio, indirizzo locale, stato, arresto, passaggio al successivo se non parte entro X secondi o con 0 peer.

### Fase 4: Scarica per i torrent
- [ ] Il pulsante Scarica accetta uno stream torrent e lo affida al motore (download completo, non sequenziale) nella cartella scelta (`downloadFolder()`).
- [ ] `Downloads`: voci di tipo torrent. Il file locale è la prima sorgente e condividi file funziona come per gli altri download.

### Fase 5: rifiniture
- [ ] Statistiche nel player (peer, velocità, buffer).
- [ ] Testi in tutte le 9 lingue.
- [ ] `docs/guida.md` e `docs/guide.md`.
- [ ] CHANGELOG.
- [ ] Avviso alla prima attivazione: con i torrent l'IP è visibile agli altri. Consigliare WARP. La responsabilità di cosa si scarica è dell'utente.

## 6. Rischi da tenere d'occhio
- **TV deboli:** libtorrent e il decoder 4K insieme possono non reggere. La fase 0 serve a misurarlo.
- **Torrent con pochi peer:** avvio lento o pause. Serve un messaggio chiaro e il passaggio allo stream successivo.
- **Spazio:** il limite della cache è obbligatorio, e lo svuotamento all'arresto è il default.
- **Sicurezza:** il servizio protetto con il permesso `signature` e il server con il token, altrimenti qualsiasi app del TV potrebbe usarlo.

## 7. Regole del progetto (da rispettare in ogni fase)
- **Stile dei commenti:** frasi brevi, in inglese, che spiegano il perché. Seguire lo stile dei file vicini.
- **Testi dell'interfaccia:** sempre in **9 lingue**: `values`, `-it`, `-es`, `-ca`, `-de`, `-fr`, `-pt`, `-zh`, `-hi`. Gli apostrofi vanno scritti `\'`.
- **CHANGELOG.md:** voci in ASD-STE100 (frasi brevi, attivo, presente), sotto `## Unreleased`. La build si ferma se `versionName` non ha la sua sezione.
- **Compilazione:** nell'ambiente cloud di Claude **non c'è l'Android SDK**. Si verifica con la build CI (`build.yml` gira a ogni push su qualsiasi branch) e gli errori si leggono con:
  `gh api repos/mccoy88f/Zephyr_TV/actions/jobs/<job_id>/logs | grep "e: "`
- **Release:**
  1. alzare `versionCode`/`versionName` in `app/build.gradle.kts`;
  2. rinominare `## Unreleased` nella versione;
  3. fare il push su `main`;
  4. avviare **a mano** il workflow `release.yml` su `main`, perché il push dei tag dall'ambiente cloud viene bloccato dal proxy. Il workflow crea il tag e pubblica l'APK.

## 8. Fonti
- LibreTorrent (usa libtorrent4j): https://f-droid.org/packages/org.proninyaroslav.libretorrent
- TorrentStream-Android (archiviato, da usare come riferimento): https://github.com/sv244/TorrentStream-Android
- Formato degli stream Stremio (`infoHash`, `fileIdx`, `sources`): documentazione dell'SDK degli add-on Stremio, "Stream object"
