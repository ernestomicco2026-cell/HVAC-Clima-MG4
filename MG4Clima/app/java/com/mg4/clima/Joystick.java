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

        /**
         * 1.4.0: tasto come lo rigira la SystemUI MG alle altre app (SWI68: com.android.systemui.ACTION_HARD_KEY_EVENT).
         * 1–5 = musica e volume (volume +/-, muto, brano indietro/avanti); 6–10 = seconda funzione; 11–15 = joystick in
         * modalità clima dell'auto (tasto ★ impostato su Clima: il clima lo comanda l'auto, la musica non cambia).
         */
        default void onSystemKey(int sysCode, boolean down) {
        }
    }

    /** 1.4.0: broadcast della SystemUI MG con il tasto già convertito (codici 1–16). */
    static final String SYS_ACTION = "com.android.systemui.ACTION_HARD_KEY_EVENT";

    /** Modo del joystick secondo la SystemUI: 0 musica/volume, 1 seconda funzione, 2 clima dell'auto, -1 non visto. */
    static int sysMode(int sysCode) {
        if (sysCode >= 1 && sysCode <= 5) return 0;
        if (sysCode >= 6 && sysCode <= 10) return 1;
        if (sysCode >= 11 && sysCode <= 15) return 2;
        return -1;
    }

    static String sysModeName(int m) {
        return m == 0 ? "musica e volume" : m == 1 ? "seconda funzione" : m == 2 ? "clima dell'auto (tasto ★)" : "non visto";
    }

    private BroadcastReceiver sysReceiver;

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
        // 1.4.0: anche il tasto come lo rigira la SystemUI (per sapere se va alla musica o al clima dell'auto)
        sysReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || listener == null) return;
                int k = i.getIntExtra("KEY_CODE", -1);
                if (k >= 0) listener.onSystemKey(k, i.getBooleanExtra("DOWN", false));
            }
        };
        try {
            ctx.registerReceiver(sysReceiver, new IntentFilter(SYS_ACTION));
        } catch (Throwable e) {
            sysReceiver = null;
        }
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
        if (sysReceiver != null) {
            try {
                ctx.unregisterReceiver(sysReceiver);
            } catch (Throwable ignored) {
            }
            sysReceiver = null;
        }
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


    // ---- 1.4.0: esclusiva dei tasti ---------------------------------------------------------------------------------
    // Il broadcast non si può bloccare: le altre app (radio, musica, volume) lo ricevono comunque. L'unico modo pulito è
    // il filtro dei tasti del servizio di accessibilità (ClimaGuard.onKeyEvent), acceso solo con la schermata in primo
    // piano: se l'auto genera il broadcast DOPO il filtro, consumando il tasto lì non arriva più a nessuno.
    // Si misura in auto: con il filtro attivo, se il filtro vede il tasto e il broadcast non arriva → esclusiva;
    // se il broadcast arriva lo stesso → non possibile (le altre app lo ricevono). Il risultato si salva per software.

    private static final long MATCH_MS = 450;
    private final long[] lastFilter = new long[512], lastCast = new long[512];
    private int filterOnly, both, castNoFilter;
    private boolean decided;

    /** Il filtro dell'accessibilità ha visto (e consumato) il tasto: tra poco si controlla se è arrivato il broadcast. */
    void sawFilter(int code, android.os.Handler h) {
        if (code < 0 || code >= lastFilter.length) return;
        final long t = android.os.SystemClock.uptimeMillis();
        lastFilter[code] = t;
        if (lastCast[code] != 0 && t - lastCast[code] < MATCH_MS) {   // il broadcast era già arrivato
            both++;
            check();
            return;
        }
        h.postDelayed(() -> {
            if (lastCast[code] != 0 && lastCast[code] >= t - MATCH_MS && lastCast[code] <= t + MATCH_MS) return;   // contato da sawCast
            filterOnly++;
            check();
        }, MATCH_MS + 50);
    }

    /** È arrivato il broadcast del tasto mentre il filtro è attivo. */
    void sawCast(int code, android.os.Handler h) {
        if (code < 0 || code >= lastCast.length) return;
        final long t = android.os.SystemClock.uptimeMillis();
        lastCast[code] = t;
        if (lastFilter[code] != 0 && t - lastFilter[code] < MATCH_MS) {   // il filtro l'aveva già visto
            both++;
            check();
            return;
        }
        h.postDelayed(() -> {
            if (lastFilter[code] != 0 && lastFilter[code] >= t - MATCH_MS && lastFilter[code] <= t + MATCH_MS) return;   // contato da sawFilter
            castNoFilter++;
            check();
        }, MATCH_MS + 50);
    }

    /** 1.4.0: joystick nella modalità clima dell'auto (tasto ★): la misura dell'esclusiva non ha senso. */
    volatile boolean carClimaMode;

    private void check() {
        if (decided || carClimaMode) return;
        String counts = "tasti visti solo dal filtro " + filterOnly + ", anche dal broadcast " + both
                + ", broadcast senza filtro " + castNoFilter;
        if (filterOnly >= 3 && both == 0) {
            decided = true;
            Store.setJoyExclusive(ctx, 1, counts);
            Store.event(ctx, "joystick.esclusiva = filtro dell'accessibilità · riuscita · le altre app non ricevono i tasti (" + counts + ")");
            LogSend.send(ctx, "joystick", null);
        } else if (both >= 3 || (castNoFilter >= 5 && filterOnly == 0 && both == 0)) {
            decided = true;
            String why = both >= 3 ? "il broadcast arriva lo stesso: le altre app ricevono i tasti"
                    : "il filtro non riceve i tasti del volante";
            Store.setJoyExclusive(ctx, 0, why + " (" + counts + ")");
            Store.event(ctx, "joystick.esclusiva = filtro dell'accessibilità · non possibile · " + why + " (" + counts + ")");
            if (onDecided != null) onDecided.run();
            LogSend.send(ctx, "joystick", null);
        }
    }

    Runnable onDecided;

    /** La SystemUI ha rigirato il tasto alla musica (1–5) mentre il filtro è attivo: le altre app lo ricevono. */
    void sawSystemMusic() {
        both++;
        check();
    }

    /** Riga della diagnostica: metodo, esito e se i tasti arrivano ancora alle altre app. */
    static String exclusiveLine(Context c) {
        int v = Store.joyExclusive(c);
        String d = Store.joyExclusiveDetail(c);
        return "filtro tasti dell'accessibilità · " + (v < 0 ? "non ancora provata" : v == 1 ? "riuscita · alle altre app: no"
                : "non possibile · alle altre app: sì") + (d.isEmpty() || v < 0 ? "" : " (" + d + ")")
                + " · filtro ora " + (ClimaGuard.keyFilterOn() ? "attivo" : "spento");
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
