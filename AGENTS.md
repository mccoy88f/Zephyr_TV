# Regola permanente: Zephyr TV personalizzato

Per Zephyr TV del Covo di Nello:
- Monitorare tutte le nuove release ufficiali, comprese beta e prerelease, da https://github.com/mccoy88f/Zephyr_TV/releases.
- Aggiornare i sorgenti del fork preservando TUTTE le personalizzazioni Nello. Non sostituire mai il nostro APK con quello originale.
- Conservare Android 7 / Fire OS 6, package .debug e la stessa firma, Home/bridge vocale, SmartTube, audio italiano/ak e 1080p, schede partite grandi, Nuvio/Continua a guardare e aggiornamento cataloghi.
- Pubblicare il nuovo APK personalizzato solo dopo compilazione e controlli riusciti; aggiornare la medesima variante del catalogo Covo anche quando la release è beta.
- In caso di conflitti o controlli falliti, bloccare la pubblicazione; non eliminare personalizzazioni per far passare la build.
- Playlist, token, dati e configurazioni personali restano sulla Fire TV, fuori da repository, APK pubblici e log.

Automazione nel fork: `.github/workflows/nello-upstream.yml`; stato e contratto in `automation/`.
Il monitor GitHub del Covo usa `followAllReleases: true` solo sulla variante personalizzata, dal suo fork.

- Il pulsante Guarda usa il player interno e i plugin configurati in Zephyr, mai un reindirizzamento obbligatorio all?app Nuvio. Conservare cronologia VOD nativa insieme ai progressi esterni; la sincronizzazione fra app usa Trakt con consenso del proprietario.

- Preservare navigazione DPAD verso Guarda/stagioni/episodi, feed TV legacy limitato alle recommendation SmartTube/Nuvio e zapping senza apertura automatica dei controlli. Supportare gruppi di canali senza perdere i figli. La regola privata partite mantiene eventi iniziati da meno di 100 minuti.
