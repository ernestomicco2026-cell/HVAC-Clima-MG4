# MG4 Clima — HVAC

🇮🇹 [Italiano](#italiano) · 🇬🇧 [English](#english)

[![Offrimi un caffè con PayPal](https://img.shields.io/badge/Offrimi%20un%20caff%C3%A8-PayPal-0070BA?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/pool/9tv7fQ36nA?sr=wccr) [![Buy me a coffee with PayPal](https://img.shields.io/badge/Buy%20me%20a%20coffee-PayPal-0070BA?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/pool/9tv7fQ36nA?sr=wccr)

Se ti è utile e ti va di contribuire alle ore passate a svilupparlo e provarlo in
auto, puoi lasciare un'offerta: è del tutto **volontaria** e **non c'è un importo minimo**. Anche una stella al repository
o una segnalazione sono un aiuto prezioso. Grazie!


> Progetto amatoriale e **non ufficiale**: non è affiliato né approvato da MG Motor o SAIC Motor.
> I nomi MG / MG4 servono solo a indicare l'auto compatibile. Installalo e usalo a tuo rischio.
>
> Hobby project, **not official**: not affiliated with or endorsed by MG Motor or SAIC Motor.
> The MG / MG4 names only identify the compatible car. Install and use it at your own risk.

## Download

| Software dell'auto / Car software | Auto / Car | Versione / Version | Stato / Status | Download |
|---|---|---|---|---|
| **SWI68 SAIC 1.0+** | MG4 MY22 | **0.3.2-beta** | 🧪 beta, provata in auto / beta, tested in the car | [⬇️ MG4Clima-SWI68-0.3.2-beta.apk](releases/MG4Clima-SWI68-0.3.2-beta.apk) |
| **SWI69 Color Radio** | MG4 MY23 | **0.1.0-beta** | 🧪 prima beta, da provare / first beta, to be tested | [⬇️ MG4Clima-SWI69-SWI132-0.1.0-beta.apk](releases/MG4Clima-SWI69-SWI132-0.1.0-beta.apk) |
| **SWI132** | MG4 MY24 | **0.1.0-beta** | 🧪 prima beta, da provare / first beta, to be tested (stesso file della SWI69 / same file as SWI69) | [⬇️ MG4Clima-SWI69-SWI132-0.1.0-beta.apk](releases/MG4Clima-SWI69-SWI132-0.1.0-beta.apk) |
| **SWI133** | MG4 Luxury MY24 | **0.1.0-beta** | 🧪 prima beta, da provare / first beta, to be tested | [⬇️ MG4Clima-SWI133-0.1.0-beta.apk](releases/MG4Clima-SWI133-0.1.0-beta.apk) |

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
- **Impostazioni con la grafica del launcher**: sostituzione dell'app originale, lato di guida, menù a tendina, app
  originale, test automatico, diagnostica, invio dei log.
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
   adb install -r -f MG4Clima-SWI68-0.3.2-beta.apk
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

#### SWI68 0.3.2-beta
- Comandi veri dalla schermata (verificati sull'auto con il test automatico).
- Menù a tendina di sistema bloccato con MG4 Clima aperta, richiuso subito dal tasto fisico.
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

Ogni modello ha la sua cartella (`SWI68`, `SWI69-SWI132`, `SWI133`) con `build.sh`; strumenti e chiave in comune in `tools/` e `keys/`.
Servono JDK 17+, `tools/android.jar` (API 28) e `tools/d8.jar` (non inclusi):

```bash
cd SWI68 && ./build.sh      # → build/MG4Clima-SWI68-<versione>.apk
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
- **Settings with the launcher's look**: original app replacement, driving side, pull-down menu, original app,
  automatic test, diagnostics, log sending.
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
   adb install -r -f MG4Clima-SWI68-0.3.2-beta.apk
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

Each model has its own folder (`SWI68`, `SWI69-SWI132`, `SWI133`) with `build.sh`; shared tools and key in `tools/` and `keys/`.
You need JDK 17+, `tools/android.jar` (API 28) and `tools/d8.jar` (not included):

```bash
cd SWI68 && ./build.sh      # → build/MG4Clima-SWI68-<version>.apk
```

The APK is signed with the AOSP test platform key (like the MG firmware), required for the system permissions.
