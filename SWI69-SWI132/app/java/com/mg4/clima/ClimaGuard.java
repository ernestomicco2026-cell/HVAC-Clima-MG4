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
 * Sostituzione dell'app clima originale SWI69 / SWI132 (com.saicmotor.hvac, A9_Hvac).
 * <p>
 * Usa solo gli eventi di cambio finestra dell'app clima (nessun contenuto dello schermo). Quando la schermata
 * principale dell'app clima (HvacActivity) va in primo piano, apre sopra MG4 Clima. Protezioni:
 * <ul>
 *   <li>solo se la versione dell'app clima è una di quelle verificate (R46, R59, R69);</li>
 *   <li>solo la schermata principale: popup e finestre di sistema non si toccano;</li>
 *   <li>interruttore nelle impostazioni e sospensione quando l'app originale si apre apposta;</li>
 *   <li>anti-ciclo: più di 3 sostituzioni in 10 s → pausa di 2 minuti.</li>
 * </ul>
 * L'app originale non viene modificata: se MG4 Clima non c'è o va in crash, l'originale resta usabile.
 */
public final class ClimaGuard extends AccessibilityService {
    public static final String HVAC_PKG = "com.saicmotor.hvac";
    public static final String HVAC_MAIN = "com.saicmotor.hvac.ui.activity.MainActivity";
    /** Versioni dell'app clima analizzate (SWI69 R45 e SWI132: stesso file). */
    private static final String[] KNOWN = {"A9_Hvac_V1.0.1.120"};

    private static final long LOOP_WINDOW_MS = 10_000, LOOP_PAUSE_MS = 2 * 60_000L;
    private static final int LOOP_MAX = 3;
    private final ArrayDeque<Long> seen = new ArrayDeque<>();
    private long pausedUntil = -1;

    public static String originalVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(HVAC_PKG, 0).versionName;
        } catch (Exception e) {
            return "assente";
        }
    }

    public static boolean originalKnown(Context c) {
        String v = originalVersion(c);
        for (String k : KNOWN) if (k.equals(v)) return true;
        return false;
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
                        hvac.startLight();
                    } else {
                        int p = hvac.readPower();
                        long now = SystemClock.elapsedRealtime();
                        if (p >= 0 && knownPower >= 0 && p != knownPower) {
                            powerChangedAt = now;
                            // il menù si è aperto un attimo prima che ce ne accorgessimo
                            if (now - sysUiAt < 1500) collapse("clima " + (p == 1 ? "acceso" : "spento"));
                        }
                        if (p >= 0) knownPower = p;
                    }
                }
            } catch (Throwable t) {
                Store.event(ClimaGuard.this, "controllo accensione: " + t);
            }
            bg.postDelayed(this, 1000);
        }
    };

    private static boolean isSysUi(CharSequence pkg) {
        return pkg != null && pkg.toString().toLowerCase(java.util.Locale.ROOT).contains("systemui");
    }

    /** Evento del menù a tendina: si legge subito l'accensione; se è appena cambiata il menù l'ha aperto il clima. */
    private void onSysUi(AccessibilityEvent e) {
        sysUiAt = SystemClock.elapsedRealtime();
        String what = e.getPackageName() + "/" + e.getClassName();
        if (seenSysUi.size() < 20 && seenSysUi.add(what)) Store.event(this, "finestra di sistema: " + what);
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

    private void collapse(String why) {
        if (!Store.closeShade(this)) return;
        String r;
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("collapsePanels").invoke(sb);
            r = "chiuso";
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            r = "non chiuso: " + c;
        }
        Store.event(this, "menù a tendina " + r + " (" + why + ")");
        long now = SystemClock.elapsedRealtime();
        if (now - lastSend > 10 * 60_000L) {
            lastSend = now;
            LogSend.send(this, "tendina", null);
        }
    }

    @Override
    protected void onServiceConnected() {
        Store.event(this, "servizio collegato · app clima " + originalVersion(this)
                + (originalKnown(this) ? " (verificata)" : " (non verificata)"));
        if (bg == null) {
            android.os.HandlerThread th = new android.os.HandlerThread("mg4-clima-guard");
            th.start();
            bg = new android.os.Handler(th.getLooper());
            bg.postDelayed(powerPoll, 20_000);
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
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (isSysUi(e.getPackageName())) {
            onSysUi(e);
            return;
        }
        if (e.getPackageName() == null || !HVAC_PKG.contentEquals(e.getPackageName())) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !HVAC_MAIN.contentEquals(cls)) return;   // solo la schermata principale
        if (!Store.replace(this)) return;
        if (Store.bypassed(this)) {
            Store.event(this, "app clima originale lasciata (aperta apposta)");
            return;
        }
        if (!originalKnown(this)) {
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
