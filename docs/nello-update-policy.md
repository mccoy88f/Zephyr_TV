# Aggiornamenti automatici Zephyr personalizzato

La regola del proprietario è persistente in AGENTS.md nei due repository.

- Sorgente ufficiale: mccoy88f/Zephyr_TV, tutte le release pubblicate con APK, beta comprese.
- Fork con modifiche: Pieropapamonello/Zephyr_TV, ramo feat/android7-firetv-home.
- GitHub Actions: Nello upstream releases, controllo ai minuti 7,22,37,52 di ogni ora; avvio manuale con force per una nuova revisione delle sole personalizzazioni.
- Firma: chiave esistente in un secret Actions del solo fork; package .debug fisso, Android 7 conservato. Non viene generata una firma diversa se il secret manca.
- Integrazione: merge dei sorgenti ufficiali, test, lint, contratto delle funzioni Nello e controllo certificato. Errori e conflitti fermano il processo prima di sostituire l'APK pubblico.
- Pubblicazione: release GPL con APK ZephyrTV-Nello-Android7.apk e sorgenti del tag. Il contatore versionCode cresce anche per revisioni della stessa versione.
- Catalogo: la stessa scheda del Covo segue entrambe le tipologie di release esclusivamente dal fork, con followAllReleases=true; il normale monitor GitHub continua a separare stabile/beta per le altre app.
- Un'interruzione tra tag e caricamento dell'asset viene recuperata ricompilando il tag già verificato, senza una seconda versione.
- Playlist, token, configurazione della TV e dati personali non entrano nei sorgenti, nei secret di compilazione o nell'APK pubblico.

Il monitor del Covo ha una frequenza obiettivo di 15 minuti, su scheduler a cinque minuti, e dipende dalla disponibilità Render. I controlli GitHub possono essere ritardati dalla piattaforma: questa non è una promessa di aggiornamento istantaneo.
