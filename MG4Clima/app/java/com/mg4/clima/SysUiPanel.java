package com.mg4.clima;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Pannello clima del SystemUI MG (SWI68, SWI133 e software simili): è la "tendina" che si apre quando il clima si
 * accende o col tasto fisico del clima. Il SystemUI espone il servizio pubblico StartActivityService con una di due
 * interfacce, che dipende dalla build del SystemUI e non dal modello:
 * <ul>
 *   <li>IPageService (SystemUI eh32_eu, SWI68): openHvac() = 1, closeHvac() = 2;</li>
 *   <li>IStartActivityService (SystemUI eh32_P, SWI68 software vecchio e SWI133): startHvac() = 1, stopHVAC() = 2.</li>
 * </ul>
 * 1.3.1: l'interfaccia si chiede al servizio collegato (getInterfaceDescriptor), così la chiamata è sempre quella che
 * il SystemUI in esecuzione implementa davvero; il modello serve solo come riserva prima del collegamento. Se il
 * servizio espone un'interfaccia sconosciuta il pannello di sistema non si usa (nessuna chiamata a caso).
 */
final class SysUiPanel {
    private static final String PKG = "com.android.systemui";
    private static final String CLS = "com.android.systemui.StartActivityService";
    private static final String ACTION = "com.android.systemui.saicmotor.action.StartActivity";
    private static final int OPEN_HVAC = 1, CLOSE_HVAC = 2;
    static final String PAGE = "com.saicmotor.sdk.external.IPageService";
    static final String START = "com.android.systemui.IStartActivityService";

    private static volatile IBinder binder;
    /** Interfaccia letta dal servizio collegato ("" = non ancora letta, null = sconosciuta). */
    private static volatile String iface = "";
    private static boolean binding;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SysUiPanel() {
    }

    /** Riserva prima del collegamento: dal modello, come nella 1.3.0. */
    private static String fallback(Context c) {
        return Model.SWI133.equals(Model.get(c)) ? START : PAGE;
    }

    /** Interfaccia da usare: quella dichiarata dal servizio, oppure la riserva. null = sconosciuta, non chiamare. */
    private static String desc(Context c) {
        String i = iface;
        return i != null && i.isEmpty() ? fallback(c) : i;
    }

    /** Per la diagnostica. */
    static String interfaceName() {
        String i = iface;
        if (i == null) return "sconosciuta";
        if (i.isEmpty()) return "non ancora letta";
        return i.substring(i.lastIndexOf('.') + 1);
    }

    private static void readInterface(Context c, IBinder b) {
        String d;
        try {
            d = b.getInterfaceDescriptor();
        } catch (Throwable e) {
            d = "";
        }
        String used;
        if (PAGE.equals(d) || START.equals(d)) used = d;
        else if (d == null || d.isEmpty()) used = "";   // non letta: si resta sulla riserva
        else used = null;
        iface = used;
        Store.event(c, "pannello clima di sistema: interfaccia " + (d == null || d.isEmpty() ? "non letta (riserva "
                + fallback(c).substring(fallback(c).lastIndexOf('.') + 1) + ")" : d.substring(d.lastIndexOf('.') + 1)
                + (used == null ? " (sconosciuta: pannello di sistema non usato)" : "")));
    }

    private static synchronized void bind(Context c) {
        if (binding || (binder != null && binder.isBinderAlive())) return;
        if (iface == null) return;   // interfaccia sconosciuta: non si riprova a ogni chiamata
        binding = true;
        final Context app = c.getApplicationContext();
        try {
            Intent i = new Intent(ACTION).setComponent(new ComponentName(PKG, CLS));
            boolean ok = app.bindService(i, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    readInterface(app, b);
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
        if (iface == null) return "non usato (interfaccia sconosciuta)";
        IBinder b = binder;
        if (b == null || !b.isBinderAlive()) {
            bind(c);
            return "servizio in collegamento";
        }
        String d = desc(c);
        try {
            Rpc.call(b, d, CLOSE_HVAC, Rpc.NONE);
            return "chiuso";
        } catch (Throwable e) {
            binder = null;
            return "errore " + e + " (interfaccia " + d.substring(d.lastIndexOf('.') + 1) + ")";
        }
    }

    /** Apre il pannello clima originale (SWI133: è l'unico clima originale). Restituisce false se non si può. */
    static boolean open(Context c) {
        final Context ctx = c.getApplicationContext();
        if (iface == null) {
            Store.event(ctx, "pannello clima originale: interfaccia sconosciuta, non aperto");
            return false;
        }
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
            String d = desc(ctx);
            try {
                if (bb != null && d != null) Rpc.call(bb, d, OPEN_HVAC, Rpc.NONE);
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
        if (iface == null) return;
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
