package com.mg4.clima;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.util.List;

/**
 * 1.4.4: alla chiusura MG4 Clima torna dove era stata aperta (issue #10).
 * <ul>
 *   <li>aperta mentre davanti c'era Android Auto o CarPlay (anche dalla zona sopra la barra): se il servizio AllGo dice
 *       che la sessione è ancora attiva, si riporta davanti il task che c'era (moveTaskToFront), senza riavviare l'app;</li>
 *   <li>altrimenti (1.4.7): torna davanti l'app di prima (vedi {@link #back}). Mai l'intent HOME.</li>
 * </ul>
 * Sessione: servizio com.allgo.rui/.RemoteUIService, interfaccia com.allgo.rui.IRemoteUIService,
 * getActiveRemoteDevice() = transazione 15; la risposta comincia con 0 se non c'è nessun dispositivo in sessione.
 * Del dispositivo non si legge niente (niente nomi nei log).
 */
final class Ritorno {
    static final String EXTRA_FROM = "mg4.clima.da";
    private static final ComponentName ALLGO = new ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService");
    private static final String ALLGO_IFACE = "com.allgo.rui.IRemoteUIService";
    private static final int TX_ACTIVE_DEVICE = 15;

    /** App da cui è stata aperta la schermata attuale (pacchetto) e come. */
    private static volatile String from = "-", via = "-";
    private static volatile IBinder allgo;
    private static boolean binding;

    private Ritorno() {
    }

    /** Dal servizio: collegamento ad AllGo (una volta), così alla chiusura la risposta è immediata. */
    static synchronized void bind(Context c) {
        if (allgo != null || binding) return;
        try {
            c.getPackageManager().getServiceInfo(ALLGO, 0);
        } catch (Throwable e) {
            return;   // AllGo non c'è: niente proiezione
        }
        binding = true;
        try {
            c.getApplicationContext().bindService(new Intent().setComponent(ALLGO), new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    allgo = b;
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    allgo = null;
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            binding = false;
        }
    }

    /** All'apertura della schermata: fromPkg = app che era davanti prima di MG4 Clima. */
    static void onOpen(Context c, String fromPkg, String how) {
        from = fromPkg == null || fromPkg.isEmpty() ? "-" : fromPkg;
        via = how;
    }

    /** 1.4.6: ultima chiusura fatta da MG4 Clima (elapsedRealtime): serve per non scambiarla con AllGo che si riprende lo schermo. */
    private static volatile long backAt;

    static boolean closedRecently(long ms) {
        return backAt > 0 && android.os.SystemClock.elapsedRealtime() - backAt < ms;
    }

    static boolean fromProjection() {
        return ProjectionZone.isProjection(from);
    }

    /** 1 = sessione attiva, 0 = nessuna sessione, -1 = AllGo non risponde. */
    static int session() {
        IBinder b = allgo;
        if (b == null) return -1;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(ALLGO_IFACE);
            if (!b.transact(TX_ACTIVE_DEVICE, d, r, 0)) return -1;
            r.readException();
            return r.readInt() == 0 ? 0 : 1;
        } catch (Throwable e) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /**
     * Chiusura. 1.4.7 (issue #16): MAI l'intent HOME (con il launcher MG4 non predefinito portava alla home SAIC).
     * <ol>
     *   <li>aperta da Android Auto/CarPlay con la sessione ancora attiva: quel task davanti (moveTaskToFront);</li>
     *   <li>altrimenti davanti il task dell'app da cui è stata aperta (launcher MG4, altra app), se c'è ancora;</li>
     *   <li>altrimenti il task più recente che non sia MG4 Clima né l'app clima originale (che sta proprio sotto
     *       quando MG4 Clima l'ha sostituita: non si deve rivedere), né una proiezione senza sessione;</li>
     *   <li>se non si trova niente: il nostro task va dietro (moveTaskToBack).</li>
     * </ol>
     * Dopo, l'attività chiama finish(). Restituisce il testo per il log.
     */
    static String back(Activity a) {
        String opened = from;
        boolean proj = fromProjection();
        int ses = proj ? session() : -1;
        String note = null;
        if (proj && ses == 0) note = "nessuna sessione AllGo";
        String skip = ClimaGuard.hvacPkg(a);
        if (!(proj && ses == 0) && !"-".equals(opened) && !opened.equals(skip) && !opened.equals(a.getPackageName())) {
            int task = taskOf(a, opened, skip);
            if (task >= 0 && toFront(a, task)) {
                backAt = android.os.SystemClock.elapsedRealtime();
                return log(a, opened, opened + " davanti (task " + task + (proj && ses < 0 ? ", AllGo non risponde" : "") + ")");
            }
            if (task < 0) note = "task di " + opened + " non trovato";
        }
        String[] other = new String[1];
        int task = otherTask(a, skip, ses == 0, other);
        if (task >= 0 && toFront(a, task)) {
            backAt = android.os.SystemClock.elapsedRealtime();
            return log(a, opened, other[0] + " davanti (task più recente, " + task + ")" + (note != null ? " · " + note : ""));
        }
        boolean moved;
        try {
            moved = a.moveTaskToBack(true);
        } catch (Throwable e) {
            moved = false;
        }
        backAt = android.os.SystemClock.elapsedRealtime();
        return log(a, opened, (moved ? "app di prima (moveTaskToBack + finish)" : "app di prima (solo finish)")
                + (note != null ? " · " + note : ""));
    }

    private static boolean toFront(Context c, int task) {
        try {
            ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).moveTaskToFront(task, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Task più recente da riportare davanti: non il nostro, non l'app clima originale, non una proiezione senza sessione. */
    @SuppressWarnings("deprecation")
    private static int otherTask(Activity a, String hvac, boolean noSession, String[] pkgOut) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks =
                    ((ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(30);
            int mine = a.getTaskId();
            for (ActivityManager.RunningTaskInfo t : tasks) {
                if (t.id == mine || t.topActivity == null) continue;
                String p = t.topActivity.getPackageName();
                String base = t.baseActivity != null ? t.baseActivity.getPackageName() : p;
                if (p.equals(a.getPackageName()) || p.equals(hvac) || base.equals(hvac)) continue;
                if (noSession && ProjectionZone.isProjection(p)) continue;
                pkgOut[0] = p;
                return t.id;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String log(Activity a, String opened, String result) {
        String line = "aperta da " + opened + " (" + via + ") · tornata a " + result;
        Store.setRitorno(a, line);
        from = "-";
        via = "-";
        return line;
    }

    @SuppressWarnings("deprecation")
    private static int taskOf(Context c, String pkg, String hvac) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks =
                    ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(30);
            for (ActivityManager.RunningTaskInfo t : tasks) {
                // 1.4.7: un task con sopra l'app clima originale non si riporta davanti (si rivedrebbe l'originale)
                if (t.topActivity != null && hvac.equals(t.topActivity.getPackageName())) continue;
                if ((t.baseActivity != null && pkg.equals(t.baseActivity.getPackageName()))
                        || (t.topActivity != null && pkg.equals(t.topActivity.getPackageName()))) return t.id;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
