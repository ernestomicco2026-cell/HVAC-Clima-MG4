package com.mg4.clima;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Sostituzione dell'app clima originale e chiusura del pannello clima di sistema, per ogni modello:
 * <ul>
 *   <li>SWI68: app com.saicmotor.hmi.hvac (HvacActivity), pannello clima del SystemUI MG;</li>
 *   <li>SWI69 / SWI132: app com.saicmotor.hvac (MainActivity), menù a tendina normale;</li>
 *   <li>SWI133: il clima originale è solo il pannello del SystemUI (nessuna app da sostituire).</li>
 * </ul>
 * Usa solo gli eventi di cambio finestra (nessun contenuto dello schermo). Quando la schermata principale dell'app
 * clima va in primo piano, apre sopra MG4 Clima. Protezioni:
 * <ul>
 *   <li>solo se la versione dell'app clima è una di quelle analizzate;</li>
 *   <li>solo la schermata principale: popup e finestre di sistema non si toccano;</li>
 *   <li>interruttore nelle impostazioni e sospensione quando l'app originale si apre apposta;</li>
 *   <li>anti-ciclo: più di 3 sostituzioni in 10 s → pausa di 2 minuti.</li>
 * </ul>
 * L'app originale non viene modificata: se MG4 Clima non c'è o va in crash, l'originale resta usabile.
 */
public final class ClimaGuard extends AccessibilityService {
    /** App clima originale del modello: {pacchetto, schermata principale ("-" = nessuna), versioni analizzate…}. */
    private static String[] original(Context c) {
        switch (Model.get(c)) {
            case Model.SWI68:
                return new String[]{"com.saicmotor.hmi.hvac", "com.saicmotor.hmi.hvac.HvacActivity",
                        "HVAC.44041", "HVAC.4C051", "1.0.0_EH32_SOP_2502201112"};
            case Model.SWI69:
            case Model.SWI132:   // SWI69 R45 e SWI132 R43: stesso file
                return new String[]{"com.saicmotor.hvac", "com.saicmotor.hvac.ui.activity.MainActivity", "A9_Hvac_V1.0.1.120"};
            default:   // SWI133: pannello del SystemUI
                return new String[]{"com.android.car.hvac", "-"};
        }
    }

    public static String hvacPkg(Context c) {
        return original(c)[0];
    }

    public static String hvacMain(Context c) {
        return original(c)[1];
    }

    /** C'è un'app clima originale da sostituire (non sulla SWI133). */
    public static boolean hasOriginalApp(Context c) {
        return !"-".equals(hvacMain(c));
    }

    private static final long LOOP_WINDOW_MS = 10_000, LOOP_PAUSE_MS = 2 * 60_000L;
    private static final int LOOP_MAX = 3;
    private final ArrayDeque<Long> seen = new ArrayDeque<>();
    private long pausedUntil = -1;

    public static String originalVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(hvacPkg(c), 0).versionName;
        } catch (Exception e) {
            return "assente";
        }
    }

    public static boolean originalKnown(Context c) {
        String v = originalVersion(c);
        String[] o = original(c);
        for (int i = 2; i < o.length; i++) if (o[i].equals(v)) return true;
        return Store.isVerified(c, v);   // verificata su quest'auto (1.3.0)
    }

    /** Attiva il servizio in modo permanente (WRITE_SECURE_SETTINGS, firma platform), senza toccare gli altri. */
    public static void ensureEnabled(Context c) {
        try {
            String me = new ComponentName(c, ClimaGuard.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = false;
            if (!TextUtils.isEmpty(cur)) for (String s : cur.split(":")) if (s.equalsIgnoreCase(me)) listed = true;
            if (!listed) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        TextUtils.isEmpty(cur) ? me : cur + ":" + me);
                Store.event(c, "accessibilità attivata");
            }
            if (Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Throwable e) {
            Store.event(c, "accessibilità: errore " + e);
        }
    }

    public static String enabledState(Context c) {
        try {
            String me = new ComponentName(c, ClimaGuard.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return cur != null && cur.toLowerCase().contains(me.toLowerCase()) ? "attiva" : "non attiva";
        } catch (Throwable e) {
            return "?";
        }
    }

    // ---- menù a tendina: il sistema lo apre quando il clima si accende/spegne (anche dal tasto fisico) -----------

    private HvacClient hvac;
    private android.os.Handler bg;
    private volatile int knownPower = -1;
    private volatile long powerChangedAt, sysUiAt, lastSend;
    private final Set<String> seenSysUi = new HashSet<>();

    private final Runnable powerPoll = new Runnable() {
        @Override
        public void run() {
            try {
                android.os.UserManager um = (android.os.UserManager) getSystemService(USER_SERVICE);
                if (um == null || um.isUserUnlocked()) {
                    if (hvac == null) {
                        hvac = new HvacClient(ClimaGuard.this);
                        // 1.0.2: avviso immediato dal servizio clima (SWI68/SWI133), oltre al controllo periodico
                        hvac.setOnChange(() -> {
                            android.os.Handler b = bg;
                            if (b != null) b.postAtFrontOfQueue(ClimaGuard.this::checkPower);
                        });
                        hvac.startLight();
                    } else {
                        checkPower();
                    }
                }
            } catch (Throwable t) {
                Store.event(ClimaGuard.this, "controllo accensione: " + t);
            }
            // 1.0.2: ogni 300 ms (prima 1 s), così l'accensione del clima si vede prima e il pannello si richiude subito
            bg.postDelayed(this, 300);
        }
    };

    /** Legge l'accensione; se il clima si è appena acceso richiude il pannello del SystemUI. */
    private void checkPower() {
        HvacClient c = hvac;
        if (c == null) return;
        int p = c.readPower();
        long now = SystemClock.elapsedRealtime();
        if (p >= 0 && knownPower >= 0 && p != knownPower) {
            powerChangedAt = now;
            // SWI68/SWI133: il SystemUI apre il pannello clima quando il clima si accende (segnale 4001);
            // SWI69/SWI132: il menù si è aperto un attimo prima che ce ne accorgessimo
            if ((panel && p == 1) || now - sysUiAt < 1500) collapse("clima " + (p == 1 ? "acceso" : "spento"));
        }
        if (p >= 0) knownPower = p;
    }

    private static boolean isSysUi(CharSequence pkg) {
        return pkg != null && pkg.toString().toLowerCase(java.util.Locale.ROOT).contains("systemui");
    }

    /** App in primo piano (dall'ultimo cambio di finestra che non è del SystemUI). */
    private volatile String fgPkg = "";

    /**
     * Finestra del SystemUI: si legge subito l'accensione; se è appena cambiata il pannello l'ha aperto il clima.
     * Con MG4 Clima in primo piano il pannello clima di sistema non serve mai (es. tasto fisico del clima): si chiude.
     */
    private void onSysUi(AccessibilityEvent e) {
        sysUiAt = SystemClock.elapsedRealtime();
        String what = e.getPackageName() + "/" + e.getClassName();
        if (seenSysUi.size() < 20 && seenSysUi.add(what)) Store.event(this, "finestra di sistema: " + what);
        if (panel && getPackageName().equals(fgPkg)) {
            collapse("MG4 Clima aperta");
            return;
        }
        if (bg == null || hvac == null) return;
        bg.post(() -> {
            int p = hvac.readPower();
            long now = SystemClock.elapsedRealtime();
            boolean changed = (p >= 0 && knownPower >= 0 && p != knownPower) || now - powerChangedAt < 2000;
            if (p >= 0 && knownPower >= 0 && p != knownPower) powerChangedAt = now;
            if (p >= 0) knownPower = p;
            if (changed) collapse("clima " + (p == 1 ? "acceso" : "spento"));
        });
    }

    private boolean hvacPageShown;

    /**
     * 1.1.0 (idea di NoHvacPopup): la finestra del pannello clima del SystemUI si chiama "hvac page". Appena compare
     * si chiude col comando del sistema, se sopra c'è MG4 Clima o se il clima si è appena acceso/spento (il tasto
     * fisico del clima, sopra le altre app, continua ad aprirlo).
     */
    private void onWindows() {
        boolean shown = false;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    CharSequence t = w == null ? null : w.getTitle();
                    if (t != null && t.toString().toLowerCase(java.util.Locale.ROOT).contains("hvac page")) {
                        shown = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (shown == hvacPageShown) return;
        hvacPageShown = shown;
        if (!shown) return;
        boolean ours = getPackageName().equals(fgPkg);
        boolean justChanged = SystemClock.elapsedRealtime() - powerChangedAt < 2500;
        Store.event(this, "finestra \"hvac page\" vista (sopra " + fgPkg + (justChanged ? ", clima appena cambiato" : "") + ")");
        if (ours || justChanged) {
            lastCollapse = 0;   // subito, anche se c'è appena stata un'altra serie di chiusure
            collapse(ours ? "finestra vista sopra MG4 Clima" : "finestra vista dopo l'accensione");
        }
    }

    private long lastCollapse;
    /** Modello col pannello clima nel SystemUI MG (SWI68, SWI133). */
    private volatile boolean panel;
    private volatile String hvacPkg = "-", hvacMain = "-";

    /** SWI68/SWI133: comando del SystemUI MG (closeHvac / stopHVAC); SWI69/SWI132: chiusura del menù a tendina. */
    private void collapse(String why) {
        int mode = Store.panelMode(this);
        if (mode == Store.PANEL_LEAVE) return;
        // 1.1.0: "solo con MG4 Clima aperto" → sopra le altre app il pannello si lascia
        if (mode == Store.PANEL_WITH_APP && !getPackageName().equals(fgPkg)) return;
        long t = SystemClock.elapsedRealtime();
        if (t - lastCollapse < 2500) return;   // una serie di chiusure alla volta
        lastCollapse = t;
        if (panel) {
            SysUiPanel.closeSoon(this, why);
        } else {
            String r;
            try {
                Object sb = getSystemService("statusbar");
                sb.getClass().getMethod("collapsePanels").invoke(sb);
                r = "chiuso";
            } catch (Throwable e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                r = "non chiuso: " + c;
            }
            Store.event(this, "menù a tendina " + r + " (" + why + ")");
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastSend > 10 * 60_000L) {
            lastSend = now;
            LogSend.send(this, "tendina", null);
        }
    }

    @Override
    protected void onServiceConnected() {
        panel = Model.sysUiPanel(this);
        hvacPkg = hvacPkg(this);
        hvacMain = hvacMain(this);
        Store.event(this, "servizio collegato · " + Model.get(this) + " · app clima " + originalVersion(this)
                + (originalKnown(this) ? " (verificata)" : " (non verificata)"));
        if (bg == null) {
            android.os.HandlerThread th = new android.os.HandlerThread("mg4-clima-guard");
            th.start();
            bg = new android.os.Handler(th.getLooper());
            bg.postDelayed(powerPoll, 20_000);
            if (panel) bg.postDelayed(() -> SysUiPanel.prepare(this), 20_000);
        }
    }

    @Override
    public void onDestroy() {
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (hvac != null) hvac.stop();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e != null && e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (panel) onWindows();
            return;
        }
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (isSysUi(e.getPackageName())) {
            onSysUi(e);
            return;
        }
        if (e.getPackageName() != null) fgPkg = e.getPackageName().toString();
        if (e.getPackageName() == null || !hvacPkg.contentEquals(e.getPackageName())) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !hvacMain.contentEquals(cls)) return;   // solo la schermata principale
        if (!Store.replace(this)) return;
        if (Store.bypassed(this)) {
            Store.event(this, "app clima originale lasciata (aperta apposta)");
            return;
        }
        if (!originalKnown(this)) {
            Store.setPendingVerify(this, originalVersion(this));   // da verificare alla prossima apertura di MG4 Clima
            Store.event(this, "app clima " + originalVersion(this) + " non verificata: niente sostituzione");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pausedUntil > now) return;
        seen.addLast(now);
        while (!seen.isEmpty() && now - seen.peekFirst() > LOOP_WINDOW_MS) seen.removeFirst();
        if (seen.size() > LOOP_MAX) {
            pausedUntil = now + LOOP_PAUSE_MS;
            seen.clear();
            Store.event(this, "sostituzione sospesa 2 minuti (l'app clima si è riaperta troppe volte)");
            return;
        }
        try {
            startActivity(new Intent(this, ClimaActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            Store.event(this, "app clima originale sostituita");
        } catch (Throwable t) {
            Store.event(this, "sostituzione non riuscita: " + t);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
