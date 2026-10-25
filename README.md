# MG4 Clima — HVAC

🇮🇹 [Italiano](#italiano) · 🇬🇧 [English](#english)

[![Offrimi un caffè con PayPal](https://img.shields.io/badge/Offrimi%20un%20caff%C3%A8-PayPal-0070BA?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/pool/9tv7fQ36nA?sr=wccr) [![Buy me a coffee with PayPal](https://img.shields.io/badge/Buy%20me%20a%20coffee-PayPal-0070BA?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/pool/9tv7fQ36nA?sr=wccr)

Se ti è utile e ti va di contribuire alle ore passate a svilupparlo e provarlo in

auto, puoi lasciare un'offerta: è del tutto **volontaria** e **non c'è un importo minimo**. Anche una stella al repository

o una segnalazione sono un aiuto prezioso. Grazie!

> Progetto amatoriale e **non ufficiale**: non è affiliato né approvato da MG Motor o SAIC Motor.

> I nomi MG / MG4 servono solo a indicare l'auto compatibile. Installalo e usalo a tuo rischio.


> Hobby project, **not official**: not affiliated with or endorsed by MG Motor or SAIC Motor.

> The MG / MG4 names only identify the compatible car. Install and use it at your own risk.

## ⚠️ ATTENZIONE · WARNING ⚠️

> [!CAUTION]
> # 🚨 USI QUESTA APP A TUO RISCHIO E PERICOLO
> **MG4 Clima è un progetto amatoriale e sperimentale.** Comanda il climatizzatore della tua auto e lavora accanto
> ai servizi di sistema dell'infotainment. Un errore o un firmware diverso possono causare **malfunzionamenti
> dell'infotainment** (barra in alto, dock, pannelli, clima).
>
> - **Non è un'app ufficiale** e non è approvata da MG Motor o SAIC Motor.
> - Può far **decadere la garanzia** sull'infotainment.
> - Installala e usala **solo ad auto ferma** e solo se sai usare **adb** per tornare indietro
>   (`adb uninstall com.mg4.clima`).
> - **Nessuna garanzia** e **nessuna responsabilità** dell'autore per qualsiasi danno, guasto, costo o problema
>   all'auto, all'infotainment o a persone.
>
> **Installando l'app dichiari di aver letto e accettato tutto questo.**

> [!CAUTION]
> # 🚨 USE THIS APP ENTIRELY AT YOUR OWN RISK
> **MG4 Clima is an experimental hobby project.** It controls your car's climate and works alongside the
> infotainment's system services. A mistake or a different firmware can cause **infotainment malfunctions**
> (top bar, dock, panels, climate).
>
> - **Not an official app**, not endorsed by MG Motor or SAIC Motor.
> - It may **void the infotainment warranty**.
> - Install and use it **only with the car stopped** and only if you can use **adb** to go back
>   (`adb uninstall com.mg4.clima`).
> - **No warranty** and **no liability** of the author for any damage, fault, cost or problem to the car,
>   the infotainment or people.
>
> **By installing the app you declare that you have read and accepted all of this.**

## Download

| Software dell'auto / Car software | Auto / Car | Versione / Version | Stato / Status | Download |
|---|---|---|---|---|
| **MG4-Clima** | MG4 MY22 · MY23 · MY24 · Luxury MY24 | **1.0.2** | ⚠️ Sperimentale, a tuo rischio / Experimental, at your own risk | [⬇️ MG4Clima-1.0.2.apk](releases/MG4Clima-1.0.2.apk) |

**Un unico APK per tutti i modelli**: il software dell'auto si riconosce da solo. Dalla 1.0.0 gli aggiornamenti si

installano anche da **Impostazioni → Aggiornamenti**. · **One APK for every model**: the car software is detected

by itself. From 1.0.0 updates can also be installed from **Settings → Updates**.

Le versioni precedenti (una per modello) restano nella cartella [`releases/`](releases/). · Older versions (one per

model) are still in the [`releases/`](releases/) folder.

**Come sapere quale software hai / How to check your software:**

`adb shell getprop ro.build.mt2712.version` → `SWI68-…`, `SWI69-…`, `SWI132-…` o/or `SWI133-…`.

---

![MG4 Clima SWI68 — notte (simulazione / mock-up)](docs/swi68-notte.png)

*SWI68, notte: clima in automatico, aria al viso e ai piedi, volante e sedile guidatore riscaldati ·

SWI68, night: automatic climate, air to face and feet, heated wheel and driver seat (mock-up).*

![MG4 Clima SWI68 — inverno (simulazione / mock-up)](docs/swi68-inverno.png)

*Inverno: parabrezza, lunotto, volante e sedili riscaldati · Winter: windscreen, rear window, wheel and seats heated (mock-up).*

![MG4 Clima SWI68 — giorno (simulazione / mock-up)](docs/swi68-giorno.png)

*Tema giorno, come il launcher · Day theme, like the launcher (mock-up).*

![MG4 Clima SWI68 — guida a destra (simulazione / mock-up)](docs/swi68-guida-destra.png)

*Guida a destra: comandi e cruscotto dal lato del guidatore · Right-hand drive: controls and dashboard on the driver's side (mock-up).*

![MG4 Clima SWI69 / SWI132 (simulazione / mock-up)](docs/swi69-swi132.png)

*SWI69 / SWI132: senza sedili e volante riscaldati, temperatura 16–30 °C · SWI69 / SWI132: no heated seats or wheel, 16–30 °C (mock-up).*

![MG4 Clima — auto spenta (simulazione / mock-up)](docs/swi68-auto-spenta.png)

*Auto spenta: la schermata resta in lettura · Car off: the screen only shows the state (mock-up).*

---

## Italiano

### Cos'è e cosa stò facendo

**MG4 Clima** è una nuova schermata del climatizzatore in stile Tesla per l'infotainment della MG4. Sostituisce l'app

clima originale senza modificarla:

- al centro c'è il **cruscotto della MG4 2022 stilizzato**: volante, quadro strumenti, schermo centrale, console con la

  manopola e le **quattro bocchette** (laterale guidatore, due centrali, laterale passeggero). Si vede **dove va l'aria**:

  viso, piedi, parabrezza, con flussi più lunghi quando la ventola gira più forte e colore caldo quando la temperatura è alta;

- **accensione, temperatura e ventola sono dal lato del guidatore** (si specchia tutto con la guida a destra);

- in basso i tasti: **AUTO, A/C, ECO, direzione dell'aria, ricircolo, parabrezza, lunotto, volante e sedili** (sedili

  e volante solo dove ci sono);

- i colori sono gli stessi del **MG4 Launcher**, con tema giorno e notte.

MG4 Clima legge e comanda il clima con **gli stessi servizi dell'auto usati dall'app originale**, con gli stessi comandi

(premere un tasto di MG4 Clima è come premere quello dell'app originale). Quando l'app clima originale si apre, MG4 Clima

compare al suo posto. L'app originale non viene toccata: si può sempre riaprire dalle impostazioni, e se MG4 Clima viene

disinstallata tutto torna come prima.

Lo sviluppo procede **un modello alla volta**: prima la sola lettura dello stato, poi un

**test automatico** che prova ogni comando e invia il log, poi i comandi veri. La SWI68 ha superato il test (22 comandi su

24 al primo colpo, i due rimanenti erano errori del test, corretti). SWI69, SWI132 e SWI133 sono alla prima beta, da provare.

Sulla **SWI133** il clima originale non è un'app ma un pannello del sistema: MG4 Clima non lo sostituisce, si apre dal

tasto del clima di MG4 Launcher (dalla prossima versione del launcher) o dall'elenco delle app; dalle impostazioni si

apre il pannello originale.

### Funzioni

- **Comandi completi**: accensione, temperatura un grado alla volta fino a LO e HI, ventola (da AUTO riparte dall'ultima

  velocità), AUTO, A/C, ECO, ricircolo (interno → esterno → automatico), direzioni combinabili come nell'originale,

  sedili a 3 livelli e volante (SWI68).

- **Menù a tendina**: il sistema lo apre ogni volta che il clima si accende o si spegne. Con MG4 Clima in programmazione;

  dal tasto fisico, fuori da MG4 Clima, si richiude subito. Si può disattivare.

- **Apertura dal launcher**: con le prossime versioni di MG4 Launcher il tasto del clima apre direttamente MG4 Clima,

  senza far vedere per un istante l'app originale.

- **Impostazioni con la grafica del launcher**, divise in Schermata, Apertura del clima,

  Aggiornamenti, Auto e Strumenti.

- **Un'unica app per tutti i modelli** (dalla 1.0.0): riconosce da sola SWI68, SWI69, SWI132 e SWI133 (dal software

  dell'auto o dalle app di sistema); se serve il modello si può scegliere a mano.

- **Aggiornamenti dalle impostazioni** (dalla 1.0.0), come MG4 Launcher: controlla, scarica, verifica e installa.

- **Pannello clima di sistema** (SWI68 e SWI133): quando l'auto lo apre all'accensione del clima o col tasto fisico,
  MG4 Clima lo richiude subito.

- **Test automatico dei comandi** (solo ad auto ferma): prova un comando alla volta, controlla che l'auto risponda,

  rimette tutto com'era e invia il log. Si ferma da solo se l'auto si muove o se si esce dalla schermata.

### Sicurezza

- Sostituisce solo le versioni dell'app clima già analizzate; con una versione sconosciuta lascia l'originale.

- Anti-ciclo: se l'app originale si riapre più di 3 volte in 10 secondi, MG4 Clima si mette in pausa per 2 minuti.

- Nessun dato personale: le impostazioni restano nell'auto, i log diagnostici sono anonimi.

### Installazione

Serve un computer con [adb](https://developer.android.com/tools/releases/platform-tools) collegato all'auto.

Il firmware MG blocca l'installazione normale delle app: l'opzione `-f` la permette.

1. Scarica l'APK del tuo software dalla tabella in alto.

2. Installa:

   ```bash

   adb install -r -f MG4Clima-1.0.2.apk

   ```

3. Apri il clima come al solito (tasto, barra o launcher): al primo avvio MG4 Clima si attiva da sola.

Per toglierla: `adb uninstall com.mg4.clima`. L'app clima originale torna subito quella di sempre.

### Log e segnalazioni

MG4 Clima invia in forma anonima un log diagnostico quando si esce dalla schermata, alla fine del test automatico e con

**Impostazioni → Invia la diagnostica**: servono a capire come risponde ogni auto. In **Impostazioni → Diagnostica** ci

sono lo stato letto dall'auto e gli ultimi eventi: uno screenshot basta per segnalare un problema.

### Sostieni il progetto

MG4 Clima è e resterà **gratuito**. Se ti è utile e ti va di contribuire alle ore passate a svilupparlo e provarlo in

auto, puoi lasciare un'offerta: è del tutto **volontaria** e **non c'è un importo minimo**. Anche una stella al repository

o una segnalazione sono un aiuto prezioso. Grazie!

[![Offrimi un caffè con PayPal](https://img.shields.io/badge/Offrimi%20un%20caff%C3%A8-PayPal-0070BA?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/pool/9tv7fQ36nA?sr=wccr)

### Versioni

#### 1.0.2
- **Tasto per chiudere la schermata** in alto, accanto alle impostazioni.
- **Pannello clima di sistema chiuso più in fretta**: MG4 Clima riceve l'avviso di accensione del clima nello stesso
  istante del sistema (anche dal launcher o dal tasto fisico) e lo richiude subito, più volte nel primo secondo.
- Tolta dalle impostazioni la voce per installare l'integrazione col pannello clima: il sistema dell'auto non la
  accetta.

#### 1.0.1
- Integrazione col pannello clima di sistema: prima di scaricare controlla l'accesso e, se non riesce, dice il
  motivo esatto (connessione, accesso negato) e invia il log.

#### 1.0.0 — app unica (sperimentale)

- Un'unica app per SWI68, SWI69, SWI132 e SWI133: il modello si riconosce da solo (o si sceglie nelle impostazioni).

- Aggiornamenti dalle impostazioni, come MG4 Launcher.

- Integrazione facoltativa col pannello clima di sistema (SWI68 e SWI133): MG4 Clima ne prende il posto, anche dal

  tasto fisico del clima.

- Pannello clima di sistema richiuso subito anche quando si apre col tasto fisico.

- Impostazioni riscritte: Schermata, Apertura del clima, Pannello clima di sistema, Aggiornamenti, Auto, Strumenti.

#### SWI68 0.3.2-beta

- Comandi veri dalla schermata (verificati sull'auto con il test automatico).

- Bocchette come la MG4: laterale guidatore, due centrali, laterale passeggero.

- Impostazioni e conferme con la grafica del launcher.

- Test automatico: HI raggiunto un grado alla volta, AUTO come l'originale, direzione dell'aria rimessa com'era.

- Indicazione della direzione dell'aria automatica.

#### SWI68 0.2.0-beta

- Test automatico dei comandi e invio dei log.

- Registro dei cambi fatti dall'app originale o dai tasti fisici.

#### SWI68 0.1.0-beta — prima versione

- Schermata in sola lettura: stato reale del clima, tocchi che aprono l'app originale.

#### SWI69 / SWI132 0.1.0-beta — prima versione

- Stessa schermata, impostazioni e test automatico della SWI68, con il servizio clima di SWI69 e SWI132.

- Temperatura 16–30 °C con LO e HI; sedili e volante riscaldati nascosti.

#### SWI133 0.1.0-beta — prima versione

- Stessa schermata, comandi, impostazioni e test automatico della SWI68 (stesso servizio clima, codici verificati sul

  firmware della 133).

- Si apre dal launcher o dall'elenco app; il pannello clima originale resta disponibile dalle impostazioni.

### Compilare

Dalla 1.0.0 c'è un'unica app nella cartella `MG4Clima` con `build.sh`; strumenti e chiave in `tools/` e `keys/`.

Servono JDK 17+, `tools/android.jar` (API 28) e `tools/d8.jar` (non inclusi):

```bash

cd MG4Clima && ./build.sh      # → build/MG4Clima-<versione>.apk

```

L'APK è firmato con la chiave di piattaforma di test AOSP (come il firmware MG), necessaria per i permessi di sistema.

---

## English

### What it is and what we're doing

**MG4 Clima** is a new Tesla-style climate screen for the MG4 infotainment. It replaces the original climate app without

modifying it:

- in the middle there is a **stylised MG4 2022 dashboard**: steering wheel, instrument cluster, central screen, console

  with the rotary knob and the **four vents** (driver side, two central, passenger side). You can see **where the air

  goes**: face, feet, windscreen, with longer flows at higher fan speeds and a warm colour at high temperatures;

- **power, temperature and fan are on the driver's side** (everything is mirrored for right-hand drive);

- the buttons at the bottom: **AUTO, A/C, ECO, air direction, recirculation, windscreen, rear window, wheel and seats**

  (seats and wheel only where the car has them);

- the colours are the same as **MG4 Launcher**, with day and night themes.

MG4 Clima reads and controls the climate through **the same car services used by the original app**, with the same

commands (pressing a button in MG4 Clima is like pressing it in the original app). When the original climate app opens,

MG4 Clima appears instead. The original app is never modified: it can always be opened from the settings, and if MG4

Clima is uninstalled everything goes back to how it was.

Development goes **one model at a time, tested on real cars**: first read-only, then an **automatic test** that tries

every command and sends the log, then the real commands. SWI68 passed the test (22 of 24 commands at the first try; the

other two were mistakes in the test itself, now fixed). SWI69, SWI132 and SWI133 are at their first beta, to be tested.

On **SWI133** the original climate is not an app but a system panel: MG4 Clima doesn't replace it, it opens from the

MG4 Launcher climate button (from the next launcher version) or from the app list; the settings open the original panel.

### Features

- **Full controls**: power, temperature one degree at a time up to LO and HI, fan (from AUTO it restarts from the last

  speed), AUTO, A/C, ECO, recirculation (inside → outside → automatic), combinable directions like the original,

  3-level seats and wheel (SWI68).

- **Pull-down menu**: the system opens it every time the climate turns on or off. With MG4 Clima open it doesn't come

  down; from the physical button, outside MG4 Clima, it closes straight away. It can be switched off.

- **Opening from the launcher**: with the next MG4 Launcher versions the climate button opens MG4 Clima directly,

  without briefly showing the original app.

- **Settings with the launcher's look**, split into Screen, Opening the climate, Updates, Car

  and Tools.

- **One app for every model** (from 1.0.0): it detects SWI68, SWI69, SWI132 and SWI133 by itself (from the car software

  or the system apps); the model can also be chosen by hand.

- **Updates from the settings** (from 1.0.0), like MG4 Launcher: check, download, verify and install.

- **System climate panel** (SWI68 and SWI133): when the car opens it as the climate turns on or from the physical
  button, MG4 Clima closes it straight away.

- **Automatic command test** (car stopped only): tries one command at a time, checks that the car responds, restores

  everything and sends the log. It stops by itself if the car moves or you leave the screen.

### Safety

- It only replaces the climate app versions that have been analysed; with an unknown version it leaves the original.

- Anti-loop: if the original app reopens more than 3 times in 10 seconds, MG4 Clima pauses for 2 minutes.

- No personal data: settings stay in the car, diagnostic logs are anonymous.

### Installation

You need a computer with [adb](https://developer.android.com/tools/releases/platform-tools) connected to the car.

The MG firmware blocks normal app installs: the `-f` option allows it.

1. Download the APK for your software from the table at the top.

2. Install:

   ```bash

   adb install -r -f MG4Clima-1.0.2.apk

   ```

3. Open the climate as usual (button, bar or launcher): MG4 Clima activates itself on first start.

To remove it: `adb uninstall com.mg4.clima`. The original climate app is back straight away.

### Logs and reports

MG4 Clima sends an anonymous diagnostic log when you leave the screen, at the end of the automatic test and with

**Settings → Send diagnostics**: they help to understand how each car responds. **Settings → Diagnostics** shows the

state read from the car and the latest events: a screenshot is enough to report a problem.

### Support the project

MG4 Clima is and will stay **free**. If you find it useful and would like to contribute to the hours spent developing

and testing it in the car, you can leave a donation: it is completely **voluntary** and there is **no minimum amount**.

A star on the repository or a bug report also help a lot. Thank you!


[![Buy me a coffee with PayPal](https://img.shields.io/badge/Buy%20me%20a%20coffee-PayPal-0070BA?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/pool/9tv7fQ36nA?sr=wccr)


### Changelog

#### 1.0.2
- **Button to close the screen** at the top, next to the settings.
- **System climate panel closed faster**: MG4 Clima gets the climate-on notice at the same moment as the system
  (also from the launcher or the physical button) and closes it straight away, several times in the first second.
- Removed from the settings the entry to install the system climate panel integration: the car's system doesn't
  accept it.

#### 1.0.1
- System climate panel integration: before downloading it checks access and, if it fails, tells the exact reason
  (connection, access denied) and sends the log.

#### 1.0.0 — one app (experimental)

- One app for SWI68, SWI69, SWI132 and SWI133: the model is detected by itself (or chosen in the settings).

- Updates from the settings, like MG4 Launcher.

- Optional integration with the system climate panel (SWI68 and SWI133): MG4 Clima takes its place, also from the

  physical climate button.

- System climate panel closed at once, also when opened with the physical button.

- Settings rewritten: Screen, Opening the climate, System climate panel, Updates, Car, Tools.

#### SWI68 0.3.2-beta

- Real commands from the screen (verified on the car with the automatic test).

- System pull-down menu blocked while MG4 Clima is open, closed at once from the physical button.

- Vents like the MG4: driver side, two central, passenger side.

- Settings and confirmations with the launcher's look.

- Automatic test: HI reached one degree at a time, AUTO like the original, air direction restored.

- Automatic air direction shown.

#### SWI68 0.2.0-beta

- Automatic command test and log sending.

- Log of the changes made from the original app or the physical buttons.

#### SWI68 0.1.0-beta — first version

- Read-only screen: real climate state, taps open the original app.

#### SWI69 / SWI132 0.1.0-beta — first version

- Same screen, settings and automatic test as SWI68, with the SWI69 and SWI132 climate service.

- Temperature 16–30 °C with LO and HI; heated seats and wheel hidden.

#### SWI133 0.1.0-beta — first version

- Same screen, commands, settings and automatic test as SWI68 (same climate service, codes checked on the 133 firmware).

- Opens from the launcher or the app list; the original climate panel is still available from the settings.

### Building

From 1.0.0 there is one app in the `MG4Clima` folder with `build.sh`; tools and key in `tools/` and `keys/`.

You need JDK 17+, `tools/android.jar` (API 28) and `tools/d8.jar` (not included):

```bash

cd MG4Clima && ./build.sh      # → build/MG4Clima-<version>.apk

```
The APK is signed with the AOSP test platform key (like the MG firmware), required for the system permissions.
