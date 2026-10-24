package com.mg4.clima;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Schermata MG4 Clima (SWI69 / SWI132): mostra lo stato reale del clima e i tasti comandano l'auto come l'app originale.
 * Dalle impostazioni: app originale, test automatico dei comandi (auto ferma) e invio della diagnostica nei log.
 */
public final class ClimaActivity extends Activity {
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    private static final long BYPASS_MS = 2 * 60_000L;

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
        if (!ClimaGuard.originalKnown(this)) {
            Toast.makeText(this, BuildInfo.t("App clima originale " + ClimaGuard.originalVersion(this)
                            + " non verificata: MG4 Clima non la sostituisce.",
                    "Original climate app " + ClimaGuard.originalVersion(this) + " not verified: MG4 Climate won't replace it."),
                    Toast.LENGTH_LONG).show();
        }
        Store.event(this, "schermata aperta");
        fromLauncher(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        fromLauncher(i);
    }

    /** Aperta dal launcher MG4: con la sostituzione spenta si va all'app originale. */
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
        if (Store.closeShade(this)) shadeBlock(true);
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
            Toast.makeText(this, BuildInfo.t("Test automatico in corso…", "Automatic test running…"), Toast.LENGTH_SHORT).show();
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
            toast(BuildInfo.t("Servizio clima non ancora collegato", "Climate service not connected yet"));
            return;
        }
        if (!s.usable()) {
            toast(BuildInfo.t("Accendi l'auto per usare il clima", "Switch the car on to use the climate"));
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        switch (id) {
            case "accensione":
                press("accensione", HvacClient.SET_POWER_PRESS);
                break;
            case "temp-":
            case "temp+": {
                int base = now - pendTempAt < 1500 && pendTemp > 0 ? pendTemp : s.temp;
                if (base < s.tempMin || base > s.tempMax) base = 22;
                int t = id.equals("temp+") ? Math.min(s.tempMax, base + 1) : Math.max(s.tempMin, base - 1);
                if (t == base) return;
                pendTemp = t;
                pendTempAt = now;
                send("temperatura", HvacClient.SET_DRV_TEMP, t);
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
                Store.event(this, "tocco su " + id + " → app clima originale");
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
            if (err != null) toast(BuildInfo.t("Comando non riuscito: ", "Command failed: ") + what);
        });
    }

    /** Comandi non provati dal test (parabrezza, lunotto): se dopo 3 s non cambia nulla lo dice. */
    private void checked(String what, int code, java.util.function.ToIntFunction<HvacClient.State> f, int before) {
        send(what, code, null);
        h.postDelayed(() -> {
            if (f.applyAsInt(last) == before) {
                Store.event(this, what + ": nessun cambio dopo il comando");
                toast(BuildInfo.t(what + ": usa il tasto fisico", what + ": use the physical button"));
            }
        }, 3000);
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    private void openOriginal() {
        Store.bypass(this, BYPASS_MS);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(ClimaGuard.HVAC_PKG, ClimaGuard.HVAC_MAIN))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (Throwable e) {
            Toast.makeText(this, BuildInfo.t("App clima originale non disponibile", "Original climate app not available"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private Sheet sheet;
    private FrameLayout root;

    private void settings() {
        if (sheet == null) sheet = new Sheet(this, root, view);
        boolean on = Store.replace(this), rh = Store.rightHand(this), shade = Store.closeShade(this);
        Sheet s = sheet.open(BuildInfo.t("Impostazioni", "Settings"), "MG4 Clima " + BuildInfo.version(this)
                + " · " + BuildInfo.t("app clima originale ", "original climate app ") + ClimaGuard.originalVersion(this));

        s.label(BuildInfo.t("Clima", "Climate"));
        android.widget.LinearLayout c1 = s.card();
        s.row(c1, BuildInfo.t("Sostituisci l'app clima originale", "Replace the original climate app"),
                ClimaGuard.originalKnown(this) ? BuildInfo.t("Quando si apre il clima compare MG4 Clima", "MG4 Climate opens instead of the original")
                        : BuildInfo.t("Versione dell'app originale non verificata: non viene sostituita", "Original app version not verified: not replaced"),
                s.toggle(on, v -> {
                    Store.setReplace(this, v);
                    Store.event(this, "sostituzione " + (v ? "attivata" : "spenta"));
                }), false);
        s.row(c1, BuildInfo.t("Lato di guida", "Driving side"),
                BuildInfo.t("Comandi e cruscotto dal lato del guidatore", "Controls and dashboard on the driver's side"),
                s.choices(new String[]{BuildInfo.t("Sinistra", "Left"), BuildInfo.t("Destra", "Right")}, rh ? 1 : 0, i -> {
                    Store.setRightHand(this, i == 1);
                    view.setRightHand(i == 1);
                    settings();
                }), false);
        s.row(c1, BuildInfo.t("Niente menù a tendina col clima", "No pull-down menu with the climate"),
                BuildInfo.t("Il sistema lo apre quando il clima si accende o si spegne: da qui non scende, dal tasto fisico si richiude subito",
                        "The system opens it when the climate turns on or off: blocked here, closed at once from the physical button"),
                s.toggle(shade, v -> {
                    Store.setCloseShade(this, v);
                    Store.event(this, "blocco menù a tendina " + (v ? "attivato" : "spento"));
                    shadeBlock(v);
                }), true);

        s.label(BuildInfo.t("Strumenti", "Tools"));
        android.widget.LinearLayout c2 = s.card();
        s.row(c2, BuildInfo.t("App clima originale", "Original climate app"),
                BuildInfo.t("Si apre e per 2 minuti non viene sostituita", "Opens and is not replaced for 2 minutes"),
                s.button(BuildInfo.t("Apri", "Open"), v -> openOriginal()), false);
        s.row(c2, BuildInfo.t("Test automatico dei comandi", "Automatic command test"),
                BuildInfo.t("Solo ad auto ferma: prova tutti i comandi, rimette tutto com'era e invia il log",
                        "Car stopped only: tries every command, restores everything and sends the log"),
                s.button(BuildInfo.t("Avvia", "Start"), v -> confirmTest()), false);
        s.row(c2, BuildInfo.t("Diagnostica", "Diagnostics"), BuildInfo.t("Stato letto dall'auto ed eventi", "State read from the car and events"),
                s.button(BuildInfo.t("Mostra", "Show"), v -> diagnostics()), false);
        s.row(c2, BuildInfo.t("Invia la diagnostica", "Send diagnostics"), BuildInfo.t("Nel repository privato dei log", "To the private log repository"),
                s.button(BuildInfo.t("Invia", "Send"), v -> LogSend.send(this, "manuale", r -> h.post(() -> toast(
                        "ok".equals(r) ? BuildInfo.t("Diagnostica inviata", "Diagnostics sent") : r)))), true);
    }

    private void confirmTest() {
        sheet.confirm(BuildInfo.t("Test automatico", "Automatic test"),
                BuildInfo.t("Con l'auto FERMA e accesa, MG4 Clima prova da solo i comandi uno alla volta, come se "
                                + "premessi i tasti dell'app originale: accensione, temperatura, ventola, direzione dell'aria, A/C, ECO, "
                                + "ricircolo, AUTO, sedili e volante. Dura circa 2 minuti, poi rimette tutto com'era e invia il log.\n\n"
                                + "Si ferma da solo se l'auto si muove o se esci da questa schermata. Non toccare il clima durante il test.",
                        "With the car STOPPED and on, MG4 Climate tries the commands one at a time like the original app's "
                                + "buttons: power, temperature, fan, air direction, A/C, ECO, recirculation, AUTO, seats and wheel. "
                                + "It takes about 2 minutes, then restores everything and sends the log.\n\n"
                                + "It stops by itself if the car moves or you leave this screen. Don't touch the climate during the test."),
                BuildInfo.t("Annulla", "Cancel"), BuildInfo.t("Avvia", "Start"), () -> {
                    sheet.close();
                    runTest();
                });
    }

    private void runTest() {
        testRunning = true;
        banner.setText(BuildInfo.t("Test automatico: avvio…", "Automatic test: starting…"));
        banner.setVisibility(View.VISIBLE);
        Store.event(this, "test automatico avviato");
        AutoTest.start(this, client,
                t -> h.post(() -> banner.setText(BuildInfo.t("Test automatico · ", "Automatic test · ") + t)),
                r -> h.post(() -> {
                    banner.setText(BuildInfo.t("Test finito: ", "Test finished: ") + r + BuildInfo.t(" · invio log…", " · sending log…"));
                    LogSend.send(this, "test", res -> h.post(() -> {
                        testRunning = false;
                        banner.setText(BuildInfo.t("Test finito: ", "Test finished: ") + r + " · log "
                                + ("ok".equals(res) ? BuildInfo.t("inviato ✓", "sent ✓") : res));
                        banner.setOnClickListener(v -> banner.setVisibility(View.GONE));
                        h.postDelayed(() -> banner.setVisibility(View.GONE), 60_000);
                    }));
                }));
    }

    private void diagnostics() {
        Sheet s = sheet.open(BuildInfo.t("Diagnostica", "Diagnostics"), "MG4 Clima " + BuildInfo.version(this));
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
