package com.mg4.clima;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Pannello clima del SystemUI MG (SWI68 e SWI133): è la "tendina" che si apre quando il clima si accende o col tasto
 * fisico del clima. Il SystemUI espone il servizio pubblico StartActivityService:
 * <ul>
 *   <li>SWI68: IPageService, openHvac() = 1, closeHvac() = 2;</li>
 *   <li>SWI133: IStartActivityService, startHvac() = 1, stopHVAC() = 2.</li>
 * </ul>
 * Sono gli stessi comandi che usa il SystemUI per aprirlo e chiuderlo.
 */
final class SysUiPanel {
    private static final String PKG = "com.android.systemui";
    private static final String CLS = "com.android.systemui.StartActivityService";
    private static final String ACTION = "com.android.systemui.saicmotor.action.StartActivity";
    private static final int OPEN_HVAC = 1, CLOSE_HVAC = 2;

    private static String desc(Context c) {
        return Model.SWI133.equals(Model.get(c)) ? "com.android.systemui.IStartActivityService"
                : "com.saicmotor.sdk.external.IPageService";
    }

    private static volatile IBinder binder;
    private static boolean binding;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SysUiPanel() {
    }

    private static synchronized void bind(Context c) {
        if (binding || (binder != null && binder.isBinderAlive())) return;
        binding = true;
        try {
            Intent i = new Intent(ACTION).setComponent(new ComponentName(PKG, CLS));
            boolean ok = c.getApplicationContext().bindService(i, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    binder = b;
                    binding = false;
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    binder = null;
                    binding = false;
                }
            }, Context.BIND_AUTO_CREATE);
            if (!ok) {
                binding = false;
                Store.event(c, "pannello clima di sistema: servizio non disponibile");
            }
        } catch (Throwable e) {
            binding = false;
            Store.event(c, "pannello clima di sistema: " + e);
        }
    }

    /** Collegamento anticipato, così la chiusura è immediata. */
    static void prepare(Context c) {
        bind(c);
    }

    /** Chiude il pannello clima del SystemUI. Restituisce l'esito (per il log). */
    static String close(Context c) {
        IBinder b = binder;
        if (b == null || !b.isBinderAlive()) {
            bind(c);
            return "servizio in collegamento";
        }
        try {
            Rpc.call(b, desc(c), CLOSE_HVAC, Rpc.NONE);
            return "chiuso";
        } catch (Throwable e) {
            binder = null;
            return "errore " + e;
        }
    }

    /** Apre il pannello clima originale (SWI133: è l'unico clima originale). Restituisce false se non si può. */
    static boolean open(Context c) {
        final Context ctx = c.getApplicationContext();
        IBinder b = binder;
        if (b != null && b.isBinderAlive()) {
            try {
                Rpc.call(b, desc(ctx), OPEN_HVAC, Rpc.NONE);
                return true;
            } catch (Throwable e) {
                binder = null;
            }
        }
        bind(ctx);
        main.postDelayed(() -> {
            IBinder bb = binder;
            try {
                if (bb != null) Rpc.call(bb, desc(ctx), OPEN_HVAC, Rpc.NONE);
                else Store.event(ctx, "pannello clima originale: servizio non collegato");
            } catch (Throwable e) {
                Store.event(ctx, "pannello clima originale: " + e);
            }
        }, 400);
        return true;
    }

    /**
     * Chiude più volte di seguito: il SystemUI apre il pannello poco dopo il cambio di stato del clima. 1.0.2: si parte
     * subito e si ripete fitto nel primo secondo, così il pannello si vede il meno possibile.
     */
    static void closeSoon(Context c, String why) {
        bind(c);
        final Context ctx = c.getApplicationContext();
        final String[] res = new String[1];
        long[] at = {0, 80, 200, 350, 550, 800, 1200, 1800, 2600};
        for (int k = 0; k < at.length; k++) {
            final boolean last = k == at.length - 1;
            main.postDelayed(() -> {
                String r = close(ctx);
                if (res[0] == null || !"chiuso".equals(res[0])) res[0] = r;
                if (last) Store.event(ctx, "pannello clima di sistema " + res[0] + " (" + why + ")");
            }, at[k]);
        }
    }
}
