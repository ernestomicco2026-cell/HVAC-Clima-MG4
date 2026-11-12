package com.mg4.clima;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tema giorno/notte uguale a quello del launcher. Fonti, in ordine:
 * <ol>
 *   <li>1.4.4: il tema scritto nel sistema dal launcher MG4 (come faceva il launcher SAIC):
 *       SWI68/SWI133 {@code UiModeManager.getNightMode()} (non Configuration.uiMode, che sulla SWI133 resta su "notte");
 *       SWI69/SWI132 Settings.System {@code SKIN_THEME_CONFIG} (0 = scuro, 1 = chiaro), con il broadcast
 *       {@code com.saicmotor.changeSkin}. Vale se è affidabile (ha detto "notte" o è cambiato nelle ultime 72 ore) e non è
 *       bloccato (fermo da 12 ore di accensione e contrario al sole, o "notte" alla prima accensione col sole sopra +10°).
 *       Quando cambia si applica subito;</li>
 *   <li>alba e tramonto calcolati senza internet dall'ultima posizione nota (se manca, fuso orario e Italia), con 60 s
 *       di conferma (regola del launcher 1.0.3, senza i fari).</li>
 * </ol>
 */
final class Tema {
    static final String SKIN_KEY = "SKIN_THEME_CONFIG", SKIN_ACTION = "com.saicmotor.changeSkin";
    private static final long CONFIRM_MS = 60_000, TICK_MS = 20_000;
    private static final long RELIABLE_WINDOW_MS = 72L * 60 * 60 * 1000, UI_STUCK_ON_MS = 12L * 60 * 60 * 1000;
    private static final double ITALY_LAT = 42.5, ITALY_LON = 12.5;
    private static final String K_SYS_NIGHT_AT = "temaNotteSistemaAlle", K_LAT = "temaUltimaLat", K_LON = "temaUltimaLon",
            K_UI_LAST = "temaUiUltimo", K_UI_CHANGED_AT = "temaUiCambiatoAlle", K_UI_ON_MS = "temaUiAccesoMs",
            K_UI_STUCK = "temaUiBloccato", K_UI_STUCK_WHY = "temaUiBloccatoPerche";

    private static Boolean applied, pending;
    private static long pendingSince;
    private static String source = "—", detail = "", lastChange = "—";
    private static int lastUiNight = -1;
    private static long onTickAt, onPending, onSavedAt;
    private static Handler work;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Schermata aperta da ridisegnare quando il tema cambia (thread principale). */
    static volatile Runnable listener;

    private Tema() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("mg4clima_tema2", Context.MODE_PRIVATE);
    }

    /** Tema da usare adesso (true = giorno). Alla prima chiamata si calcola subito. */
    static synchronized boolean isDay(Context c) {
        if (applied == null) evaluate(c.getApplicationContext(), true);
        return applied == null || applied;
    }

    /** Dal servizio (sempre attivo): controllo ogni 20 s e ascolto della chiave del launcher. */
    static synchronized void start(Context c) {
        final Context ctx = c.getApplicationContext();
        if (work != null) return;
        HandlerThread t = new HandlerThread("mg4-clima-tema");
        t.start();
        work = new Handler(t.getLooper());
        work.post(new Runnable() {
            @Override
            public void run() {
                evaluate(ctx, false);
                work.postDelayed(this, TICK_MS);
            }
        });
        try {   // SWI69/SWI132: il launcher scrive SKIN_THEME_CONFIG e manda com.saicmotor.changeSkin
            ctx.getContentResolver().registerContentObserver(Settings.System.getUriFor(SKIN_KEY), false,
                    new ContentObserver(work) {
                        @Override
                        public void onChange(boolean self) {
                            evaluate(ctx, false);
                        }
                    });
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context x, android.content.Intent i) {
                    work.post(() -> evaluate(ctx, false));
                }
            }, new android.content.IntentFilter(SKIN_ACTION), null, work);
        } catch (Throwable ignored) {
        }
        new Thread(() -> ensureLocationPermission(ctx), "mg4-clima-posizione").start();
    }

    /** Il sistema ha cambiato uiMode: si rivaluta subito e dopo il tempo di conferma. */
    static void onConfigurationChanged(Context c) {
        final Context ctx = c.getApplicationContext();
        Handler w = work;
        if (w == null) {
            evaluate(ctx, false);
            return;
        }
        w.post(() -> evaluate(ctx, false));
        w.postDelayed(() -> evaluate(ctx, false), CONFIRM_MS + 1000);
    }

    /** Valuta le fonti; il tema del launcher si applica subito, gli altri dopo 60 s di conferma (o subito all'avvio). */
    static synchronized void evaluate(Context c, boolean immediate) {
        boolean target;
        boolean fromLauncher;
        try {
            target = compute(c);
            fromLauncher = fromSystem;   // tema scritto dal launcher: già deciso da lui, si applica subito
        } catch (Throwable e) {
            source = "errore " + e.getClass().getSimpleName();
            return;
        }
        if (applied == null) {
            applyNow(c, target, "avvio");
            return;
        }
        if (applied == target) {
            pending = null;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (fromLauncher || immediate) {
            pending = null;
            applyNow(c, target, fromLauncher ? "dal sistema" : "subito");
        } else if (pending == null || pending != target) {
            pending = target;
            pendingSince = now;
        } else if (now - pendingSince >= CONFIRM_MS) {
            pending = null;
            applyNow(c, target, "confermato dopo 60 s");
        }
    }

    private static void applyNow(Context c, boolean day, String how) {
        boolean changed = applied != null && applied != day;
        applied = day;
        lastChange = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(new Date()) + " (" + how + ")";
        Store.event(c, "tema = fonte " + source + " · applicato " + (day ? "giorno" : "notte") + " (" + how + ")");
        if (changed) {
            Runnable l = listener;
            if (l != null) main.post(l);
        }
    }

    // ---- regola del launcher 1.0.3, senza i fari ------------------------------------------------------------------

    private static boolean fromSystem;
    private static String sysName = "—";

    /** 1.4.4: tema scritto nel sistema dal launcher: 1 = notte, 0 = giorno, -1 = non c'è. */
    /** Solo per i test e gli screenshot: tema di sistema simulato (-2 = no). */
    static volatile int testNight = -2;

    static int systemNight(Context c) {
        if (testNight != -2) {
            sysName = "test";
            return testNight;
        }
        if (Model.saic(c)) {   // SWI68 / SWI133
            sysName = "UiModeManager";
            try {
                android.app.UiModeManager um = (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
                int m = um.getNightMode();
                return m == android.app.UiModeManager.MODE_NIGHT_YES ? 1 : m == android.app.UiModeManager.MODE_NIGHT_NO ? 0 : -1;
            } catch (Throwable e) {
                return -1;
            }
        }
        sysName = SKIN_KEY;   // SWI69 / SWI132
        try {
            int v = Settings.System.getInt(c.getContentResolver(), SKIN_KEY, -1);
            return v == 0 ? 1 : v == 1 ? 0 : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static boolean compute(Context c) {
        SharedPreferences sp = sp(c);
        fromSystem = false;
        int uiNight = systemNight(c);
        String uiTxt = uiNight == 1 ? "notte" : uiNight == 0 ? "giorno" : "non c'è";
        boolean sysNight = uiNight == 1;
        long wall = System.currentTimeMillis();
        trackUiChanges(sp, uiNight, wall);
        long prevSeen = sp.getLong(K_SYS_NIGHT_AT, 0);
        boolean changed = lastUiNight >= 0 && uiNight >= 0 && uiNight != lastUiNight;
        if ((sysNight || changed) && wall - prevSeen > 60 * 60_000L) sp.edit().putLong(K_SYS_NIGHT_AT, wall).apply();
        if (uiNight >= 0) lastUiNight = uiNight;
        long seen = sp.getLong(K_SYS_NIGHT_AT, 0);
        boolean reliable = seen > 0 && wall - seen < RELIABLE_WINDOW_MS && wall >= seen;

        double[] loc = location(c, sp);
        Sun sun = sun(loc[0], loc[1], wall);
        String sunTxt = "alba " + sun.rise + " · tramonto " + sun.set + " · sole a " + Math.round(sun.elevation) + "° ("
                + (loc[2] == 1 ? "posizione" : loc[2] == 2 ? "ultima posizione salvata" : "fuso orario/Italia") + ")";

        String stuckTxt = "";
        if (uiNight >= 0) {
            boolean contradicts = uiNight == 1 ? sun.elevation > 6 : sun.elevation < -6;
            long onMs = sp.getLong(K_UI_ON_MS, 0) + onPending;
            boolean noHistory = sp.getLong(K_UI_CHANGED_AT, 0) == 0 && onMs < UI_STUCK_ON_MS;
            if (!sp.getBoolean(K_UI_STUCK, false)) {
                if (onMs >= UI_STUCK_ON_MS && contradicts) markStuck(sp, "mai cambiato da 12 h, contraddice il sole");
                else if (noHistory && uiNight == 1 && sun.elevation > 10)
                    markStuck(sp, "prima accensione, notte con il sole a " + Math.round(sun.elevation) + "°");
            }
            if (sp.getBoolean(K_UI_STUCK, false)) {
                stuckTxt = sysName + " " + uiTxt + " (" + sp.getString(K_UI_STUCK_WHY, "bloccato") + ": ignorato)";
                reliable = false;
            }
        }
        long at = sp.getLong(K_UI_CHANGED_AT, 0);
        String hist = "ultimo cambio " + sysName + " " + (at == 0 ? "mai visto" : new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date(at)))
                + " (" + (sp.getLong(K_UI_ON_MS, 0) + onPending) / 3_600_000L + " h di accensione da allora)";
        detail = (stuckTxt.isEmpty() ? "" : stuckTxt + " · ") + hist + " · " + sysName + " " + uiTxt + " · " + sunTxt;
        if (reliable && uiNight >= 0) {
            source = "sistema (" + sysName + ", scritto dal launcher)";
            fromSystem = true;
            return !sysNight;
        }
        source = "orario (alba/tramonto)" + (uiNight < 0 ? ", " + sysName + " non c'è" : ", " + sysName + " non affidabile");
        return sun.elevation >= -0.833;
    }

    private static void trackUiChanges(SharedPreferences sp, int uiNight, long wall) {
        long now = SystemClock.elapsedRealtime();
        if (onTickAt > 0) onPending += Math.max(0, Math.min(now - onTickAt, 3 * TICK_MS));
        onTickAt = now;
        if (uiNight < 0) return;
        int last = sp.getInt(K_UI_LAST, -1);
        SharedPreferences.Editor e = null;
        if (last != uiNight) {
            e = sp.edit().putInt(K_UI_LAST, uiNight);
            if (last >= 0) {
                e.putLong(K_UI_CHANGED_AT, wall).putLong(K_UI_ON_MS, 0).putBoolean(K_UI_STUCK, false).remove(K_UI_STUCK_WHY);
                onPending = 0;
                onSavedAt = now;
            }
        }
        if (onPending > 0 && (now - onSavedAt > 5 * 60_000L || e != null)) {
            if (e == null) e = sp.edit();
            e.putLong(K_UI_ON_MS, sp.getLong(K_UI_ON_MS, 0) + onPending);
            onPending = 0;
            onSavedAt = now;
        }
        if (e != null) e.apply();
    }

    private static void markStuck(SharedPreferences sp, String why) {
        sp.edit().putBoolean(K_UI_STUCK, true).putString(K_UI_STUCK_WHY, why).apply();
    }

    /** Riga "tema" della diagnostica. */
    static synchronized String stateText(Context c) {
        if (applied == null) evaluate(c.getApplicationContext(), true);
        return "fonte " + source + " · applicato " + (applied == null ? "—" : applied ? "giorno" : "notte")
                + (pending != null ? " (in conferma: " + (pending ? "giorno" : "notte") + ")" : "")
                + " · ultimo cambio " + lastChange + (detail.isEmpty() ? "" : " · " + detail);
    }

    // ---- posizione ---------------------------------------------------------------------------------------------

    private static void ensureLocationPermission(Context c) {
        String perm = "android.permission.ACCESS_COARSE_LOCATION";
        if (c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, c.getPackageName(), perm, android.os.Process.myUserHandle());
        } catch (Throwable ignored) {
        }
    }

    /** {lat, lon, tipo}: tipo 1 = posizione attuale, 2 = ultima salvata, 0 = fuso orario/Italia. */
    private static double[] location(Context c, SharedPreferences sp) {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            if (lm != null) {
                for (String prov : lm.getProviders(false)) {
                    try {
                        android.location.Location l = lm.getLastKnownLocation(prov);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (best != null && (best.getLatitude() != 0 || best.getLongitude() != 0)) {
                sp.edit().putFloat(K_LAT, (float) best.getLatitude()).putFloat(K_LON, (float) best.getLongitude()).apply();
                return new double[]{best.getLatitude(), best.getLongitude(), 1};
            }
        } catch (Throwable ignored) {
        }
        if (sp.contains(K_LAT)) return new double[]{sp.getFloat(K_LAT, 0), sp.getFloat(K_LON, 0), 2};
        TimeZone tz = TimeZone.getDefault();
        String id = tz.getID();
        if (id == null || id.equals("Europe/Rome") || id.equals("GMT") || id.equals("UTC")) return new double[]{ITALY_LAT, ITALY_LON, 0};
        return new double[]{ITALY_LAT, tz.getRawOffset() / 3_600_000.0 * 15.0, 0};
    }

    // ---- sole (formule NOAA semplificate, precisione di qualche minuto) -------------------------------------------

    static final class Sun {
        double elevation;
        String rise = "—", set = "—";
    }

    static Sun sun(double lat, double lon, long wallMs) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(wallMs);
        int doy = u.get(Calendar.DAY_OF_YEAR);
        double hour = u.get(Calendar.HOUR_OF_DAY) + u.get(Calendar.MINUTE) / 60.0 + u.get(Calendar.SECOND) / 3600.0;
        double g = 2 * Math.PI / 365.0 * (doy - 1 + (hour - 12) / 24.0);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double tst = hour * 60 + eq + 4 * lon;
        double ha = Math.toRadians(tst / 4.0 - 180);
        double la = Math.toRadians(lat);
        double cosZ = Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(ha);
        Sun s = new Sun();
        s.elevation = 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZ))));
        double cc = Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl);
        if (cc >= -1 && cc <= 1) {
            double ha0 = Math.toDegrees(Math.acos(cc));
            s.rise = localTime(u, 720 - 4 * (lon + ha0) - eq);
            s.set = localTime(u, 720 - 4 * (lon - ha0) - eq);
        }
        return s;
    }

    private static String localTime(Calendar utcDay, double minutesUtc) {
        Calendar c = (Calendar) utcDay.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long ms = c.getTimeInMillis() + Math.round(minutesUtc * 60_000);
        return new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(ms));
    }

    /** Per i test e gli screenshot: azzera lo stato. */
    static synchronized void resetForTest() {
        applied = null;
        pending = null;
        source = "—";
        detail = "";
    }
}
