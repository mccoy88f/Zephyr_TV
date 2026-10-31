# Risultati YouTube nella ricerca Zephyr

La build Nello.3 basata su Zephyr TV 3.5.3 aggiunge una riga **YouTube** alla
ricerca di film e serie. Serve SmartTube installato: vengono riconosciuti i
pacchetti Stable, Beta e F-Droid, in questo ordine. Non serve una chiave API
YouTube e Zephyr non interroga un server del Covo di Nello.

La ricerca usa il content provider esportato da SmartTube, documentato nel suo
[VideoContentProvider](https://github.com/yuliskov/SmartTube/blob/master/leanbackassistant/src/main/java/com/liskovsoft/leanbackassistant/search/VideoContentProvider.java).
L'indirizzo locale è `content://<pacchetto>/search/search_suggest_query/<testo>`.
SmartTube effettua le richieste alla propria sorgente, usando la sua
configurazione. Si tratta di risultati disponibili dal suo servizio globale,
non della garanzia che ogni voce dell'interfaccia SmartTube venga restituita.

## Utilizzo

- Cercare con la tastiera, dal telefono o con il bridge vocale già installato.
- Nel filtro **Tutti** vengono mantenute le righe film, serie e canali, con una
  nuova riga **YouTube · testo cercato**. È inclusa anche nel filtro Video.
- Se si sceglie esplicitamente un filtro Film, Serie, TV o Radio, la riga YouTube
  non viene aggiunta. È esclusa anche nella ricerca riservata ai soli contenuti
  adulti.
- La riga contiene al massimo 20 video, con titolo, miniatura e descrizione.
  I video sono deduplicati; le voci senza titolo, senza identificativo video
  valido o con durata zero non live (per esempio alcuni mix) vengono scartate.
- Selezionando un video si apre **direttamente SmartTube**, usando un componente
  esplicito: non passa dal bridge YouTube e non torna a cercare in Zephyr.
- **Tutti i risultati in SmartTube** continua la stessa ricerca dentro SmartTube.
  Rimane disponibile anche quando il provider non restituisce video o fallisce.

Il caricamento YouTube ha un worker distinto da film e serie. Dopo dieci secondi
si abbandona l'attesa e si mostra la possibilità di continuare in SmartTube.
Le ricerche precedenti vengono annullate e le loro risposte non sostituiscono
quelle della ricerca corrente. L'assenza di SmartTube lascia il comportamento
precedente della ricerca.

Gli intent ricevuti dal provider non vengono eseguiti. Zephyr ricava solo un
identificativo YouTube valido da miniature su domini riconosciuti e costruisce
il link video e l'intent verso il pacchetto SmartTube selezionato. Non usa gli ID
temporanei della cache globale SmartTube, che possono cambiare tra ricerche.

È stata corretta anche la classificazione dei risultati dei canali: un canale TV
di un'estensione non viene più chiamato Radio soltanto perché l'estensione può
essere instradata a un raccoglitore radio.

## Verifica

116 test JVM, compilazione e lint completati (con avvisi). Prove su Fire TV API 25:

- Film e serie rimangono presenti cercando `music`, con video nella riga YouTube.
- Cercando `inception trailer`, selezione della scheda del trailer Warner Bros.
  Italia: SmartTube PlaybackActivity aperta e stato della sessione `PLAYING`.
- Il caso di risposta vuota dal provider non genera video inventati: rimane il
  comando che apre la ricerca completa in SmartTube.

Il test di apertura/riproduzione è stato eseguito prima della correzione finale
della classificazione TV/Radio; il percorso di apertura SmartTube non è cambiato.
