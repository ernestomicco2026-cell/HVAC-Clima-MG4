# MG4 Clima — regole del progetto

## Che cos'è
- MG4 Clima è un'**unica app** per i software SWI68, SWI69, SWI132, SWI133 e per il modello del mercato turco DBI68
  (riconosciuto come SWI68). Il modello viene riconosciuto in automatico (`Model.java`).
- Sostituisce l'app clima originale **solo dopo la verifica automatica** (`AutoTest.java`) riuscita sulla versione
  dell'app clima di quell'auto. Se la verifica non c'è o non riesce, resta l'app originale e il clima funziona come prima.
- In `releases/latest.json` l'APK sta dentro l'oggetto `"MG4Clima"`, con i campi `version`, `apk`, `size`, `sha256`
  e `notes`.

## Come rispondere
- Rispondere sempre in italiano, in modo leggibile, con spiegazioni semplici.
- Se manca qualcosa (un file, un APK da analizzare, uno strumento), fermarsi e chiedere a Ernesto. Non inventare.

## Git e GitHub
- Niente va su GitHub (codice, APK, README, `releases/latest.json`) senza l'ok esplicito di Ernesto.
  Prima si consegna l'APK da provare, poi, solo dopo l'ok, si fa commit e push.
- Messaggio di commit sempre generico: `Aggiornamento`.
  Autore: `ernestomicco2026-cell <ernesto.micco@gmail.com>`.
  I dettagli delle modifiche vanno solo nel changelog del README.
- Non toccare gli APK vecchi in `releases/` né le Release di GitHub: restano tutti.
  La Release su GitHub la crea Ernesto.

## File segreti ed esclusi da git
- `tools/android.jar`, `tools/d8.jar`, `tools/apksig.jar`, `tools/log-token.txt` e ogni token sono esclusi da git
  (vedi `.gitignore`): mai committarli, mai stamparli, mai scriverli in file del repository.
- Il token dei log si prende da `tools/log-token.txt` oppure dalla variabile d'ambiente `MG4_LOG_TOKEN`.

## README
- Changelog in italiano e in inglese (sezioni "Versioni" e "Changelog").
- Solo numeri di versione, niente date.

## A ogni nuova versione
- Alzare `VERSION_CODE` e `VERSION_NAME` in `MG4Clima/version.properties`.
- In `releases/latest.json` aggiornare `version`, `apk`, `notes` e anche `size` e `sha256` del nuovo APK.
- Aggiornare il changelog del README (IT + EN).

## Clima originale e grafica
- L'app clima originale deve continuare a funzionare: la sostituzione avviene solo dopo una verifica riuscita.
- La grafica resta come quella attuale (icone ISO, opzione delle scritte sui pulsanti).
- Se cambia qualcosa di visibile, aggiornare gli screenshot in `docs/`.

## Come compilare
```bash
cd MG4Clima && ./build.sh
```
- Serve Java (JDK 17 o più recente), `python3`, `zip`, `unzip`, `curl`.
- La versione viene letta da `MG4Clima/version.properties`.
- La cartella `tools/` è nella radice del repository (da `build.sh` è `../tools`); la chiave di firma è
  `keys/platform.p12`, anche questa nella radice.
- Se mancano `tools/android.jar` o `tools/d8.jar`, `build.sh` li scarica da dl.google.com
  (piattaforma Android API 28 `platform-28_r06` e R8/D8 8.5.35 da Google Maven) e controlla l'hash SHA-256.
  Se d8.jar non contiene la libreria di firma, scarica anche `tools/apksig.jar` (apksig 8.5.2, Google Maven).
  Se lo scaricamento non riesce, si ferma e dice quale file allegare.
- Se manca il token (né `tools/log-token.txt` né `MG4_LOG_TOKEN`), compila lo stesso ma avvisa
  che l'APK non invierà i log. Il sorgente generato col token viene cancellato subito dopo la compilazione.
- Passaggi: aapt2 (risorse) → javac (bytecode 8) → D8 → firma v1+v2 con `keys/platform.p12`.
- L'APK esce in `MG4Clima/build/MG4Clima-<VERSION_NAME>.apk` (la cartella `build/` è esclusa da git).
  Va copiato in `releases/` solo per una nuova versione, e solo con l'ok di Ernesto.
