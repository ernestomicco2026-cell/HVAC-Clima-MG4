package com.mg4.clima;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 1.4.5 (issue #9): MG4 Clima come pannello sovrapposto, solo con Android Auto o CarPlay davanti, quando l'avvio della
 * schermata vera non resta in primo piano (AllGo si riprende lo schermo). Stessa vista e stessi comandi della schermata
 * ({@link ClimaView}, {@link Comandi}), in una finestra del servizio di accessibilità: 2017 (STATUS_BAR_SUB_PANEL) e,
 * se rifiutata, 2032 (ACCESSIBILITY_OVERLAY). Si chiude con ✕, toccando fuori, con la chiusura automatica o quando
 * la proiezione non è più davanti; sotto resta Android Auto/CarPlay, quindi il ritorno è già lì.
 */
final class Pannello {
    private static final int TYPE_SUB_PANEL = 2017;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static ClimaView view;
    private static HvacClient client;
    private static String type = "-";
    /** 1.4.7: app sotto il pannello (per la riga clima.chiusura). */
    private static String under = "-";
    private static int shown;
    private static final Runnable autoClose = () -> hide("chiusura automatica dopo " + Store.autoClose(ctx()) + " s senza tocchi");
    private static ClimaGuard guard;

    private Pannello() {
    }

    private static Context ctx() {
        return guard;
    }

    /** 1.4.6: ultima chiusura del pannello (elapsedRealtime), per non riaprirlo da solo subito dopo. */
    private static volatile long hiddenAt;

    static boolean closedRecently(long ms) {
        return hiddenAt > 0 && android.os.SystemClock.elapsedRealtime() - hiddenAt < ms;
    }

    static boolean showing() {
        return view != null;
    }

    /** Mostra il pannello sopra la proiezione. Thread principale. true = mostrato. */
    static boolean show(ClimaGuard g, String under) {
        if (view != null) return true;
        guard = g;
        Pannello.under = under == null ? "-" : under;
        WindowManager wm = (WindowManager) g.getSystemService(Context.WINDOW_SERVICE);
        StringBuilder refused = new StringBuilder();
        for (int t : new int[]{TYPE_SUB_PANEL, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY}) {
            ClimaView v = build(g);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, t,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("MG4 Clima pannello");
            try {
                wm.addView(v, lp);
                view = v;
                type = t == TYPE_SUB_PANEL ? "STATUS_BAR_SUB_PANEL (2017)" : "ACCESSIBILITY_OVERLAY (2032)";
                shown++;
                startClient(g, v);
                armAutoClose();
                // 1.4.6: cambia tema anche mentre è aperto (come la schermata)
                Tema.panelListener = () -> {
                    ClimaView pv = view;
                    if (pv == null) return;
                    pv.theme();
                    pv.setBackgroundColor(pv.BG);
                };
                Store.event(g, "clima.pannello: mostrato sopra " + under + " · finestra " + type
                        + (refused.length() > 0 ? " (rifiutate: " + refused + ")" : ""));
                return true;
            } catch (Throwable e) {
                refused.append(refused.length() > 0 ? ", " : "").append(t);
                Store.event(g, "clima.pannello: finestra " + t + " rifiutata (" + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")");
                try {
                    wm.removeViewImmediate(v);
                } catch (Throwable ignored) {
                }
            }
        }
        type = "nessuna (rifiutate " + refused + ")";
        return false;
    }

    private static ClimaView build(ClimaGuard g) {
        ClimaView v = new ClimaView(g);
        v.setRightHand(Store.rightHand(g));
        v.setAnimated(Store.animatedAir(g));
        v.setDashStyle(Store.dashStyle(g));
        v.setDefrostTiles(Store.defrostTiles(g));
        v.setShowLabels(Store.showLabels(g));
        v.theme();
        v.setBackgroundColor(v.BG);
        HvacClient.State s = ClimaGuard.lastState;
        if (s != null) v.setState(s);
        v.setOnTouchListener((x, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) armAutoClose();
            return false;
        });
        return v;
    }

    private static void startClient(ClimaGuard g, ClimaView v) {
        client = new HvacClient(g);
        Comandi cmd = new Comandi(g, client, s -> Toast.makeText(g, s, Toast.LENGTH_SHORT).show(), null);
        v.setListener(id -> {
            if ("chiudi".equals(id)) hide("tasto chiudi");
            else if ("fuori".equals(id)) hide("tocco fuori dal pannello");
            else if ("impostazioni".equals(id)) Toast.makeText(g, L.s(R.string.p_settings_later), Toast.LENGTH_SHORT).show();
            else cmd.command(id);
        });
        client.start(s -> {
            ClimaGuard.lastState = s;
            cmd.onState(s);
            if (view != null) view.setState(s);
        });
    }

    private static void armAutoClose() {
        main.removeCallbacks(autoClose);
        Context c = ctx();
        int sec = c == null ? 0 : Store.autoClose(c);
        if (sec > 0 && view != null) main.postDelayed(autoClose, sec * 1000L);
    }

    /** Toglie il pannello (thread principale). */
    static void hide(String why) {
        ClimaView v = view;
        view = null;
        if (v != null) hiddenAt = android.os.SystemClock.elapsedRealtime();
        Tema.panelListener = null;
        main.removeCallbacks(autoClose);
        if (v == null) return;
        HvacClient c = client;
        client = null;
        if (c != null) c.stop();
        ClimaGuard g = guard;
        try {
            ((WindowManager) g.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Throwable ignored) {
        }
        Store.event(g, "clima.pannello: tolto (" + why + ")");
        // 1.4.7: il pannello non apre niente: chiudendolo resta davanti l'app che era sotto
        Store.setRitorno(g, "aperta da " + under + " (pannello) · tornata a " + under + " (era sotto il pannello)");
    }

    /** Dal servizio a ogni cambio di app davanti: senza proiezione davanti il pannello non serve. */
    static void onForeground(String pkg) {
        if (view != null && pkg != null && !ProjectionZone.isProjection(pkg)) hide("davanti " + pkg);
    }

    static String stateText() {
        return (view != null ? "mostrato" : "non mostrato") + " · finestra " + type + " · volte " + shown;
    }
}
