# Zephyr TV per webOS

App per webOS TV 3 (2016, Chromium 38) e per le versioni successive, nello stesso pacchetto. Non sostituisce la Home di LG. Il file dell’app è uno script classico: Chromium 38 non legge i moduli.

## Cosa fa

- Home con telecomando: frecce, OK, Back (tasto 461).
- Add-on Stremio su HTTP. Cinemeta è già presente e mostra i cataloghi. Gli stream arrivano da un add-on che ne pubblica. Si aggiunge un manifest da Impostazioni.
- Scheda di un titolo, episodi, primo stream che parte. HLS e MP4 usano il player della TV. Su webOS 3 quell’HLS è la versione 3. DASH usa Shaka da webOS 5. Su webOS 3 e 4 passa dal proxy. hls.js entra solo su un browser senza HLS nativo e con Chromium 47 o più nuovo.
- Un indirizzo MediaFlow o EasyProxy, facoltativo. Uno stream con intestazioni proprie, un DASH o un ClearKey passa dal proxy. Gli altri restano diretti. Un DASH su MediaFlow chiede `remux_to_ts`, cioè una playlist HLS versione 3 in MPEG-TS.
- In evidenza TMDB, con una chiave inserita dall’utente.
- Continua la riproduzione, utenti, tema chiaro, scuro, Vetro e automatico, modalità notte, meteo, orologio.
- Su una TV, le app installate compaiono nei Preferiti e si aprono.

## Cosa non fa

La Home di sistema, le righe delle altre app, il Watch Next delle altre app, il sintonizzatore, RTSP, RTMP, UDP, la decodifica software di Dolby e DTS, la ricerca vocale, WARP, il picture-in-picture e l’importazione da Piume. Nessuna versione di webOS le offre a un’app.

Uno stream parte solo se il server manda le intestazioni CORS. L’app Android non ha questo limite.

## Pacchetto

```sh
cd webos
npm install
npm test
npm run build
ares-package dist
ares-install --device <tv> link.mccoy88f.zephyr_4.0.0_all.ipk
```

`dist/` è la cartella che webOS installa. Il build la ricrea: non va nel repository. Servono la modalità sviluppatore e la CLI `ares`.

Per provare la pagina in un browser: `npx serve dist` e le frecce della tastiera. Esc fa da Back.

La licenza è la GPL-3.0 del repository. Il player usa [Shaka Player](https://github.com/shaka-project/shaka-player) 4.3.16 e [hls.js](https://github.com/video-dev/hls.js) 1.5.20, entrambi Apache-2.0. Shaka entra solo per i DASH. hls.js entra solo se la pagina non legge HLS da sola.
