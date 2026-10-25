package com.mg4.clima;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Schermata MG4 Clima (una sola app per SWI68, SWI69, SWI132 e SWI133, il modello si riconosce da solo): mostra lo
 * stato reale del clima e i tasti comandano l'auto come l'app originale. Dalle impostazioni: apertura del clima,
 * SystemUI corretto (SWI68, SWI133), aggiornamenti, modello, clima originale, test automatico e diagnostica.
 */
public final class ClimaActivity extends Activity {
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    private static final long BYPASS_MS = 2 * 60_000L;
    private static final String SETTINGS = "impostazioni", DIAG = "diagnostica";

    private ClimaView view;
    private TextView banner;
    private boolean testRunning;
    private long lastSend;
    private HvacClient client;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        view = new ClimaView(this);
        view.setRightHand(Store.rightHand(this));
        view.setListener(this::onTap);
        root = new FrameLayout(this);
        root.addView(view);
        banner = new TextView(this);
        banner.setTextSize(22);
        banner.setTextColor(0xFFFFFFFF);
        banner.setBackgroundColor(0xE0123A6B);
        banner.setPadding(40, 18, 40, 18);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        client = new HvacClient(this);
        // al primo avvio dopo l'installazione attiva la sostituzione dell'app clima
        ClimaGuard.ensureEnabled(this);
        if (ClimaGuard.hasOriginalApp(this) && !ClimaGuard.originalKnown(this)) {
            toastLong(t("App clima originale " + ClimaGuard.originalVersion(this) + " non verificata: MG4 Clima non la sostituisce.",
                    "Original climate app " + ClimaGuard.originalVersion(this) + " not verified: MG4 Climate won't replace it."));
        }
        Store.event(this, "schermata aperta · " + Model.get(this) + (Model.manual(this) ? " (scelto a mano)" : ""));
        fromLauncher(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        fromLauncher(i);
    }

    /** Aperta dal launcher MG4: con la sostituzione spenta si va al clima originale. */
    private void fromLauncher(Intent i) {
        if (i != null && "com.mg4.clima.APRI".equals(i.getAction()) && !Store.replace(this)) openOriginal();
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.start(this::onState);
        h.post(clock);
    }

    @Override
    protected void onStop() {
        // diagnostica nei log quando si esce (se ci sono eventi nuovi, al massimo ogni 2 minuti)
        if (Store.unsent() && System.currentTimeMillis() - lastSend > 120_000) {
            lastSend = System.currentTimeMillis();
            LogSend.send(this, "uscita", null);
        }
        client.stop();
        h.removeCallbacks(clock);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        if (Store.closeShade(this)) {
            shadeBlock(true);
            if (Model.sysUiPanel(this)) SysUiPanel.prepare(this);
        }
    }

    /** L'ora sul quadro strumenti stilizzato. */
    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            view.invalidate();
            h.postDelayed(this, 30_000);
        }
    };

    private void onTap(String id) {
        if (testRunning) {
            toast(t("Test automatico in corso…", "Automatic test running…"));
            return;
        }
        if ("impostazioni".equals(id)) {
            settings();
            return;
        }
        command(id);
    }

    // ---------------------------------------------------------------------------------- comandi

    private HvacClient.State last = new HvacClient.State();
    private int pendTemp = -1, pendFan = -1, lastManualFan = 4;
    private long pendTempAt, pendFanAt;

    private void onState(HvacClient.State s) {
        last = s;
        if (s.fan >= 1 && s.fan <= 11) lastManualFan = s.fan;
        view.setState(s);
    }

    private void command(String id) {
        HvacClient.State s = last;
        if (!s.connected) {
            toast(t("Servizio clima non ancora collegato", "Climate service not connected yet"));
            return;
        }
        if (!s.usable()) {
            toast(t("Accendi l'auto per usare il clima", "Switch the car on to use the climate"));
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        switch (id) {
            case "accensione":
                press("accensione", HvacClient.SET_POWER_PRESS);
                // all'accensione il SystemUI MG apre il suo pannello clima: si richiude subito (non serve col SystemUI corretto)
                if (Model.sysUiPanel(this) && Store.closeShade(this) && s.power != 1 && !SysUiPatch.active(this))
                    SysUiPanel.closeSoon(this, "accensione da MG4 Clima");
                break;
            case "temp-":
            case "temp+": {
                int base = now - pendTempAt < 1500 && pendTemp > 0 ? pendTemp : s.temp;
                if (base < s.tempMin || base > s.tempMax) base = 22;
                int tt = id.equals("temp+") ? Math.min(s.tempMax, base + 1) : Math.max(s.tempMin, base - 1);
                if (tt == base) return;
                pendTemp = tt;
                pendTempAt = now;
                send("temperatura", HvacClient.SET_DRV_TEMP, tt);
                break;
            }
            case "fan-":
            case "fan+": {
                int min = Math.max(1, s.fanMin), max = Math.min(11, s.fanMax);
                int base = now - pendFanAt < 1500 && pendFan > 0 ? pendFan
                        : s.fan >= min && s.fan <= max ? s.fan : lastManualFan;   // da AUTO si parte dall'ultimo livello
                boolean fromAuto = s.fan == HvacClient.FAN_AUTO && now - pendFanAt >= 1500;
                int f = fromAuto ? base : id.equals("fan+") ? Math.min(max, base + 1) : Math.max(min, base - 1);
                if (f == base && !fromAuto) return;
                pendFan = f;
                pendFanAt = now;
                send("ventola", HvacClient.SET_AIR_VOLUME, f);
                break;
            }
            case "auto": press("AUTO", HvacClient.SET_AUTO_PRESS); break;
            case "ac": press("A/C", HvacClient.SET_AC_PRESS); break;
            case "eco": press("ECO", HvacClient.SET_ECON_PRESS); break;
            case "ricircolo": press("ricircolo", HvacClient.SET_LOOP_PRESS); break;
            case "volante": press("volante", HvacClient.SET_WHEEL_PRESS); break;
            case "sedileGuida": press("sedile guidatore", HvacClient.SET_DRV_SEAT_PRESS); break;
            case "sedilePasseggero": press("sedile passeggero", HvacClient.SET_PSG_SEAT_PRESS); break;
            case "vetro":
            case "viso":
            case "piedi":
                direction(id, s);
                break;
            case "parabrezza":
                checked("parabrezza", s.frontDefrost == 1 ? HvacClient.FRONT_DEFROST_CLOSE : HvacClient.FRONT_DEFROST_OPEN,
                        st -> st.frontDefrost, s.frontDefrost);
                break;
            case "lunotto":
                checked("lunotto", s.rearDefrost == 1 ? HvacClient.REAR_DEFROST_CLOSE : HvacClient.REAR_DEFROST_OPEN,
                        st -> st.rearDefrost, s.rearDefrost);
                break;
            default:
                Store.event(this, "tocco su " + id + " → clima originale");
                openOriginal();
        }
    }

    /** Direzione dell'aria come l'app originale: si accende/spegne una delle tre e si calcola il codice 0..6. */
    private void direction(String id, HvacClient.State s) {
        boolean[] d = s.blowerMode >= 0 && s.blowerMode <= 6 ? s.directions() : new boolean[3];
        boolean def = d[0], face = d[1], foot = d[2];
        if (id.equals("vetro")) def = !def;
        else if (id.equals("viso")) face = !face;
        else foot = !foot;
        int mode;
        if (face && !def && !foot) mode = 0;
        else if (face && foot && !def) mode = 1;
        else if (foot && !def && !face) mode = 2;
        else if (def && foot && !face) mode = 3;
        else if (def && !face && !foot) mode = 4;
        else if (face && def && foot) mode = 5;
        else if (face && def) mode = 6;
        else return;   // almeno una direzione deve restare (come l'originale)
        send("direzione", HvacClient.SET_BLOWER_MODE, mode);
    }

    private void press(String what, int code) {
        send(what, code, 1);
    }

    private void send(String what, int code, Integer arg) {
        client.command(what, code, arg, err -> {
            if (err != null) toast(t("Comando non riuscito: ", "Command failed: ") + what);
        });
    }

    /** Comandi non provati dal test (parabrezza, lunotto): se dopo 3 s non cambia nulla lo dice. */
    private void checked(String what, int code, java.util.function.ToIntFunction<HvacClient.State> f, int before) {
        send(what, code, null);
        h.postDelayed(() -> {
            if (f.applyAsInt(last) == before) {
                Store.event(this, what + ": nessun cambio dopo il comando");
                toast(t(what + ": usa il tasto fisico", what + ": use the physical button"));
            }
        }, 3000);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static String t(String it, String en) {
        return BuildInfo.t(it, en);
    }

    private long lastOriginalAt;

    /** Clima originale: app clima (SWI68, SWI69, SWI132) o pannello del SystemUI (SWI133). */
    private void openOriginal() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOriginalAt < 3000) return;   // niente rimbalzi tra MG4 Clima e il clima originale
        lastOriginalAt = now;
        Store.bypass(this, BYPASS_MS);
        try {
            if (ClimaGuard.hasOriginalApp(this)) {
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName(ClimaGuard.hvacPkg(this), ClimaGuard.hvacMain(this)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            } else {
                // col SystemUI corretto attivo il pannello aprirebbe di nuovo MG4 Clima: agganci sospesi per un attimo
                SysUiPatch.suspend(this, 4000);
                SysUiPanel.open(this);
                moveTaskToBack(true);
            }
        } catch (Throwable e) {
            toast(t("Clima originale non disponibile", "Original climate not available"));
        }
    }

    // ---------------------------------------------------------------------------------- impostazioni

    private Sheet sheet;
    private FrameLayout root;
    private String currentSheet = "";
    private boolean updateChecked;
    /** Avanzamento del SystemUI corretto (null = niente in corso). */
    private String sysUiBusy;
    private TextView sysUiText, updText;

    private boolean settingsShowing() {
        return sheet != null && sheet.showing() && SETTINGS.equals(currentSheet);
    }

    private void settings() {
        if (sheet == null) sheet = new Sheet(this, root, view);
        String model = Model.get(this);
        boolean panelModel = Model.sysUiPanel(this), app = ClimaGuard.hasOriginalApp(this);
        Sheet s = sheet.open(t("Impostazioni", "Settings"), "MG4 Clima " + BuildInfo.version(this) + " · " + Model.label(model));
        currentSheet = SETTINGS;

        // ---- schermata
        s.label(t("Schermata", "Screen"));
        LinearLayout c0 = s.card();
        boolean rh = Store.rightHand(this);
        s.row(c0, t("Lato di guida", "Driving side"),
                t("Comandi e cruscotto dal lato del guidatore", "Controls and dashboard on the driver's side"),
                s.choices(new String[]{t("Sinistra", "Left"), t("Destra", "Right")}, rh ? 1 : 0, i -> {
                    Store.setRightHand(this, i == 1);
                    view.setRightHand(i == 1);
                    settings();
                }), true);

        // ---- apertura del clima
        s.label(t("Apertura del clima", "Opening the climate"));
        LinearLayout c1 = s.card();
        if (app) {
            boolean known = ClimaGuard.originalKnown(this);
            s.row(c1, t("MG4 Clima al posto dell'app originale", "MG4 Climate instead of the original app"),
                    known ? t("Quando si apre il clima dell'auto compare MG4 Clima (anche dal launcher)",
                            "When the car's climate opens, MG4 Climate appears (also from the launcher)")
                            : t("App clima originale " + ClimaGuard.originalVersion(this) + " non ancora verificata: resta l'originale",
                            "Original climate app " + ClimaGuard.originalVersion(this) + " not verified yet: the original stays"),
                    s.toggle(Store.replace(this), v -> {
                        Store.setReplace(this, v);
                        Store.event(this, "sostituzione " + (v ? "attivata" : "spenta"));
                    }), false);
        } else {
            s.row(c1, t("Il tasto clima del launcher apre MG4 Clima", "The launcher climate button opens MG4 Climate"),
                    t("Spento: apre il pannello clima originale", "Off: it opens the original climate panel"),
                    s.toggle(Store.replace(this), v -> {
                        Store.setReplace(this, v);
                        Store.event(this, "apertura dal launcher " + (v ? "attivata" : "spenta"));
                    }), false);
        }
        s.row(c1, panelModel ? t("Chiudi il pannello clima di sistema", "Close the system climate panel")
                        : t("Niente menù a tendina col clima", "No pull-down menu with the climate"),
                panelModel ? t("Se il sistema lo apre quando il clima si accende, MG4 Clima lo richiude subito",
                        "If the system opens it when the climate turns on, MG4 Climate closes it at once")
                        : t("Il sistema lo apre quando il clima si accende o si spegne: MG4 Clima lo richiude subito",
                        "The system opens it when the climate turns on or off: MG4 Climate closes it at once"),
                s.toggle(Store.closeShade(this), v -> {
                    Store.setCloseShade(this, v);
                    Store.event(this, "chiusura pannello/tendina " + (v ? "attivata" : "spenta"));
                    shadeBlock(v);
                }), true);

        // ---- SystemUI corretto (solo SWI68 e SWI133)
        sysUiText = null;
        if (panelModel) {
            s.label(t("Pannello clima di sistema", "System climate panel"));
            LinearLayout c2 = s.card();
            sysUiText = s.row(c2, t("SystemUI corretto", "Fixed SystemUI"), sysUiSubtitle(), sysUiButtons(s), true);
        }

        // ---- aggiornamenti
        s.label(t("Aggiornamenti", "Updates"));
        LinearLayout c3 = s.card();
        Updater up = Updater.get(this);
        Updater.Release rel = up.last();
        String ut = up.lastText().isEmpty() ? t("Versione installata: ", "Installed version: ") + BuildInfo.version(this) : up.lastText();
        if (rel != null && rel.newer && !rel.notes.isEmpty() && !up.busy()) ut += "\n" + rel.notes;
        View ub = up.busy() ? s.busyButton("…")
                : rel != null && rel.newer ? s.primary(t("Aggiorna", "Update"), v -> confirmUpdate(rel))
                : s.button(t("Controlla", "Check"), v -> up.check());
        updText = s.row(c3, "MG4 Clima " + BuildInfo.version(this), ut, ub, true);
        up.setListener((text, r, busy) -> {
            if (!settingsShowing()) return;
            if (busy && updText != null) updText.setText(text);
            else settings();
        });
        if (!updateChecked && !up.busy()) {
            updateChecked = true;
            up.check();
        }

        // ---- auto
        s.label(t("Auto", "Car"));
        LinearLayout c4 = s.card();
        String sw = Model.software();
        String how = Model.manual(this) ? t("Scelto a mano · riconosciuto: ", "Chosen by hand · detected: ") + Model.detected(this)
                : t("Riconosciuto in automatico", "Detected automatically") + (sw.isEmpty() ? "" : " · " + sw);
        String[] names = {"Auto", "68", "69", "132", "133"};
        int sel = 0;
        if (Model.manual(this)) for (int i = 0; i < Model.ALL.length; i++) if (Model.ALL[i].equals(model)) sel = i + 1;
        s.row(c4, Model.label(model), how, s.choices(names, sel, i -> confirmModel(i == 0 ? "" : Model.ALL[i - 1])), true);

        // ---- strumenti
        s.label(t("Strumenti", "Tools"));
        LinearLayout c5 = s.card();
        s.row(c5, app ? t("App clima originale", "Original climate app") : t("Pannello clima originale", "Original climate panel"),
                app ? t("Si apre e per 2 minuti non viene sostituita", "Opens and is not replaced for 2 minutes")
                        : t("Il pannello clima di sistema dell'auto", "The car's system climate panel"),
                s.button(t("Apri", "Open"), v -> openOriginal()), false);
        s.row(c5, t("Test automatico dei comandi", "Automatic command test"),
                t("Solo ad auto ferma: prova tutti i comandi, rimette tutto com'era e invia il log",
                        "Car stopped only: tries every command, restores everything and sends the log"),
                s.button(t("Avvia", "Start"), v -> confirmTest()), false);
        s.row(c5, t("Diagnostica", "Diagnostics"), t("Stato letto dall'auto ed eventi", "State read from the car and events"),
                s.button(t("Mostra", "Show"), v -> diagnostics()), false);
        s.row(c5, t("Invia la diagnostica", "Send diagnostics"), t("Nel repository privato dei log", "To the private log repository"),
                s.button(t("Invia", "Send"), v -> LogSend.send(this, "manuale", r -> h.post(() -> toast(
                        "ok".equals(r) ? t("Diagnostica inviata", "Diagnostics sent") : r)))), true);
    }

    private String sysUiSubtitle() {
        if (sysUiBusy != null) return sysUiBusy;
        String ver = SysUiPatch.versionName(this);
        if (!SysUiPatch.supported(this)) {
            String list = SysUiPatch.supportedList(this);
            return t("Non disponibile per questo SystemUI (", "Not available for this SystemUI (") + SysUiPatch.factoryVersion(this) + ")"
                    + (list.isEmpty() ? "" : t(". Supportati: ", ". Supported: ") + list);
        }
        if (SysUiPatch.active(this)) {
            return t("Attivo (" + ver + "): il pannello non si apre più da solo e il tasto fisico del clima apre MG4 Clima. "
                            + "Se disinstalli MG4 Clima si toglie da solo.",
                    "Active (" + ver + "): the panel no longer opens by itself and the physical climate button opens MG4 Climate. "
                            + "If you uninstall MG4 Climate it removes itself.");
        }
        if (SysUiPatch.installed(this)) {
            return t("Installato ma spento (" + ver + "): il SystemUI si comporta come l'originale",
                    "Installed but off (" + ver + "): the SystemUI behaves like the original");
        }
        boolean dl = SysUiPatch.downloaded(this);
        return t("Il pannello clima di sistema non si apre più e il tasto fisico del clima apre MG4 Clima. Si scarica da GitHub ("
                        + ver + ", " + SysUiPatch.sizeMb(this) + " MB" + (dl ? ", già scaricato" : "") + ") e si toglie quando vuoi.",
                "The system climate panel no longer opens and the physical climate button opens MG4 Climate. Downloaded from GitHub ("
                        + ver + ", " + SysUiPatch.sizeMb(this) + " MB" + (dl ? ", already downloaded" : "") + ") and removable at any time.");
    }

    private View sysUiButtons(Sheet s) {
        if (sysUiBusy != null) return s.busyButton("…");
        if (!SysUiPatch.supported(this)) return null;
        if (SysUiPatch.active(this)) return s.button(t("Rimuovi", "Remove"), v -> sysUiRemove());
        if (SysUiPatch.installed(this))
            return s.group(s.button(t("Rimuovi", "Remove"), v -> sysUiRemove()), s.primary(t("Attiva", "Turn on"), v -> sysUiInstall()));
        return s.primary(t("Installa", "Install"), v -> confirmSysUi());
    }

    private void sysUiProgress(String text) {
        sysUiBusy = text;
        if (sysUiText != null && settingsShowing()) sysUiText.setText(text);
    }

    private void sysUiDone(boolean ok, String okText, String msg) {
        sysUiBusy = null;
        toast(ok ? okText : t("Non riuscito: ", "Failed: ") + msg);
        if (settingsShowing()) settings();
    }

    private void confirmSysUi() {
        String v = SysUiPatch.versionName(this);
        sheet.confirm(t("SystemUI corretto", "Fixed SystemUI"),
                t("MG4 Clima scarica da GitHub il SystemUI della tua auto (" + v + ") con due piccole modifiche: il pannello "
                                + "clima non si apre più da solo e il tasto fisico del clima apre MG4 Clima. Il file viene controllato "
                                + "prima di installarlo.\n\nBarra in alto e dock spariscono per qualche secondo mentre si installa. "
                                + "Con \"Rimuovi\", o disinstallando MG4 Clima, torna il SystemUI originale.\n\n"
                                + "Fallo ad auto ferma, con la connessione attiva.",
                        "MG4 Climate downloads your car's SystemUI (" + v + ") from GitHub with two small changes: the climate "
                                + "panel no longer opens by itself and the physical climate button opens MG4 Climate. The file is "
                                + "checked before installing.\n\nThe top bar and dock disappear for a few seconds while it installs. "
                                + "\"Remove\", or uninstalling MG4 Climate, brings back the original SystemUI.\n\n"
                                + "Do it with the car stopped and online."),
                t("Annulla", "Cancel"), t("Installa", "Install"), this::sysUiInstall);
    }

    private void sysUiInstall() {
        sysUiProgress(t("Preparazione…", "Preparing…"));
        settings();
        SysUiPatch.enable(this, this::sysUiProgress,
                (ok, msg) -> sysUiDone(ok, t("SystemUI corretto attivo", "Fixed SystemUI active"), msg));
    }

    private void sysUiRemove() {
        sysUiProgress(t("Rimozione…", "Removing…"));
        settings();
        SysUiPatch.disable(this, (ok, msg) -> sysUiDone(ok, t("SystemUI originale ripristinato", "Original SystemUI restored"), msg));
    }

    private void confirmUpdate(Updater.Release r) {
        sheet.confirm(t("Aggiornamento", "Update"),
                t("Installo MG4 Clima " + r.version + "? La schermata si chiude per qualche secondo.",
                        "Install MG4 Climate " + r.version + "? The screen closes for a few seconds.")
                        + (r.notes.isEmpty() ? "" : "\n\n" + r.notes),
                t("Annulla", "Cancel"), t("Aggiorna", "Update"), () -> Updater.get(this).install(r));
    }

    private void confirmModel(String m) {
        String name = m.isEmpty() ? t("il riconoscimento automatico (", "automatic detection (") + Model.detected(this) + ")" : Model.label(m);
        sheet.confirm(t("Modello", "Model"),
                t("Uso " + name + "? Scegli a mano solo se il modello riconosciuto è sbagliato: con un modello sbagliato "
                                + "i comandi non funzionano. MG4 Clima si riavvia.",
                        "Use " + name + "? Only choose by hand if the detected model is wrong: with the wrong model the "
                                + "commands don't work. MG4 Climate restarts."),
                t("Annulla", "Cancel"), t("Cambia", "Change"), () -> {
                    Store.setModel(this, m);
                    Store.event(this, "modello " + (m.isEmpty() ? "automatico" : "scelto a mano: " + m));
                    restart();
                });
    }

    /** Riavvia MG4 Clima: col modello cambiano servizi e app originale, si riparte puliti. */
    private void restart() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1,
                    new Intent(this, ClimaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT);
            am.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 600, pi);
        } catch (Throwable ignored) {
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void confirmTest() {
        sheet.confirm(t("Test automatico", "Automatic test"),
                t("Con l'auto FERMA e accesa, MG4 Clima prova da solo i comandi uno alla volta, come se "
                                + "premessi i tasti dell'app originale: accensione, temperatura, ventola, direzione dell'aria, A/C, ECO, "
                                + "ricircolo, AUTO, sedili e volante (dove ci sono). Dura circa 2 minuti, poi rimette tutto com'era e "
                                + "invia il log.\n\nSi ferma da solo se l'auto si muove o se esci da questa schermata. Non toccare il "
                                + "clima durante il test.",
                        "With the car STOPPED and on, MG4 Climate tries the commands one at a time like the original app's "
                                + "buttons: power, temperature, fan, air direction, A/C, ECO, recirculation, AUTO, seats and wheel "
                                + "(where fitted). It takes about 2 minutes, then restores everything and sends the log.\n\n"
                                + "It stops by itself if the car moves or you leave this screen. Don't touch the climate during the test."),
                t("Annulla", "Cancel"), t("Avvia", "Start"), () -> {
                    sheet.close();
                    runTest();
                });
    }

    private void runTest() {
        testRunning = true;
        banner.setText(t("Test automatico: avvio…", "Automatic test: starting…"));
        banner.setVisibility(View.VISIBLE);
        Store.event(this, "test automatico avviato");
        AutoTest.start(this, client,
                x -> h.post(() -> banner.setText(t("Test automatico · ", "Automatic test · ") + x)),
                r -> h.post(() -> {
                    banner.setText(t("Test finito: ", "Test finished: ") + r + t(" · invio log…", " · sending log…"));
                    LogSend.send(this, "test", res -> h.post(() -> {
                        testRunning = false;
                        banner.setText(t("Test finito: ", "Test finished: ") + r + " · log "
                                + ("ok".equals(res) ? t("inviato ✓", "sent ✓") : res));
                        banner.setOnClickListener(v -> banner.setVisibility(View.GONE));
                        h.postDelayed(() -> banner.setVisibility(View.GONE), 60_000);
                    }));
                }));
    }

    private void diagnostics() {
        Sheet s = sheet.open(t("Diagnostica", "Diagnostics"), "MG4 Clima " + BuildInfo.version(this));
        currentSheet = DIAG;
        s.body(s.card(), Store.text(this));
    }

    // ---------------------------------------------------------------------------------- menù a tendina

    /**
     * Blocca l'apertura del menù a tendina di sistema (StatusBarManager.disable, permesso STATUS_BAR della firma
     * di sistema) mentre MG4 Clima è in primo piano: il sistema lo aprirebbe a ogni accensione/spegnimento del clima.
     * Il blocco è legato a questa app: se si chiude o va in crash il sistema lo toglie da solo.
     */
    private void shadeBlock(boolean on) {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("disable", int.class).invoke(sb, on ? 0x00010000 : 0);   // DISABLE_EXPAND
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Store.event(this, "blocco menù a tendina non riuscito: " + c);
        }
    }

    @Override
    protected void onPause() {
        shadeBlock(false);
        super.onPause();
    }

    /** Chiudendo si torna alla home (sotto c'è l'app clima originale, che non si vuole rivedere). */
    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.showing()) {
            sheet.close();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
        finish();
    }
}
