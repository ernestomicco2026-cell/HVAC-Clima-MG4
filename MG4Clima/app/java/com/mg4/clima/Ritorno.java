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
 *   <li>altrimenti (dalla home, dal launcher, nessuna sessione, task non trovato): home, come prima.</li>
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
     * Chiusura: se era stata aperta da Android Auto/CarPlay e la sessione c'è ancora, riporta davanti quel task;
     * altrimenti la home. Restituisce il testo per il log.
     */
    static String back(Activity a) {
        String opened = from;
        String result = "home";
        if (fromProjection()) {
            int ses = session();
            if (ses == 0) result = "home (nessuna sessione AllGo)";
            else {
                int task = taskOf(a, opened);
                if (task < 0) result = "home (task di " + opened + " non trovato)";
                else {
                    try {
                        ((ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE)).moveTaskToFront(task, 0);
                        result = opened + " (task " + task + (ses < 0 ? ", AllGo non risponde" : "") + ")";
                    } catch (Throwable e) {
                        result = "home (moveTaskToFront non riuscito: " + e.getClass().getSimpleName() + ")";
                    }
                }
            }
        }
        if (result.startsWith("home")) {
            try {
                a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
        backAt = android.os.SystemClock.elapsedRealtime();
        String line = "aperto da " + opened + " (" + via + ") · tornato a " + result;
        Store.setRitorno(a, line);
        from = "-";
        via = "-";
        return line;
    }

    @SuppressWarnings("deprecation")
    private static int taskOf(Context c, String pkg) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks =
                    ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(30);
            for (ActivityManager.RunningTaskInfo t : tasks) {
                if ((t.baseActivity != null && pkg.equals(t.baseActivity.getPackageName()))
                        || (t.topActivity != null && pkg.equals(t.topActivity.getPackageName()))) return t.id;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
