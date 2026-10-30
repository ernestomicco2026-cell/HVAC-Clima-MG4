package com.mg4.clima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

/**
 * Tasti del volante (1.3.3). Il sistema SAIC li manda a tutti col broadcast "com.saic.keyevent.hardkey.report" con il
 * codice del tasto, premuto/rilasciato e pressione lunga (stesso formato che riceve MG4 Voice e il servizio radio).
 * MG4 Clima si registra solo mentre la schermata è aperta e in primo piano (onResume → onPause): fuori dalla schermata
 * i tasti restano alla musica e alle altre funzioni. Se il broadcast è "ordinato" il tasto si consuma (abortBroadcast,
 * priorità massima), altrimenti arriva anche alle altre app: lo si scrive nel log.
 */
final class Joystick {
    static final String ACTION = "com.saic.keyevent.hardkey.report";
    static final String X_CODE = "android.intent.extra.hardkey.keycode";
    static final String X_DOWN = "android.intent.extra.hardkey.down";
    static final String X_LONG = "android.intent.extra.hardkey.longpress";

    /** Azioni del joystick, nell'ordine in cui si associano. */
    static final String[] ACTIONS = {"su", "giu", "sinistra", "destra", "ok"};

    interface Listener {
        void onKey(int code, boolean down, boolean longPress, boolean consumed);
    }

    private final Context ctx;
    private final Listener listener;
    private BroadcastReceiver receiver;

    Joystick(Context c, Listener l) {
        ctx = c;
        listener = l;
    }

    void register() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Bundle b = i == null ? null : i.getExtras();
                if (b == null) return;
                int code = intOf(b.get(X_CODE));
                if (code < 0) return;
                boolean down = boolOf(b.get(X_DOWN), true);
                boolean lp = boolOf(b.get(X_LONG), false);
                boolean consumed = false;
                if (isOrderedBroadcast() && listener != null && wants(code)) {
                    try {
                        abortBroadcast();
                        consumed = true;
                    } catch (Throwable ignored) {
                    }
                }
                if (listener != null) listener.onKey(code, down, lp, consumed);
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        try {
            ctx.registerReceiver(receiver, f);
        } catch (Throwable e) {
            receiver = null;
            Store.event(ctx, "joystick: ricezione dei tasti non riuscita " + e);
        }
    }

    void unregister() {
        if (receiver == null) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiver = null;
    }

    /** Il tasto è uno di quelli associati al joystick scelto (solo quelli si consumano). */
    private boolean wants(int code) {
        int side = Store.joystick(ctx);
        return side != Store.JOY_OFF && actionOf(ctx, side, code) != null;
    }

    /** Azione associata al codice per quel joystick, null se nessuna. */
    static String actionOf(Context c, int side, int code) {
        for (String a : ACTIONS) if (Store.joyCode(c, side, a) == code) return a;
        return null;
    }

    static boolean complete(Context c, int side) {
        for (String a : ACTIONS) if (Store.joyCode(c, side, a) < 0) return false;
        return true;
    }

    static String mapText(Context c, int side) {
        StringBuilder sb = new StringBuilder();
        for (String a : ACTIONS) {
            if (sb.length() > 0) sb.append(", ");
            int k = Store.joyCode(c, side, a);
            sb.append(a).append('=').append(k < 0 ? "?" : String.valueOf(k));
        }
        return sb.toString();
    }

    private static int intOf(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static boolean boolOf(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        return def;
    }
}
