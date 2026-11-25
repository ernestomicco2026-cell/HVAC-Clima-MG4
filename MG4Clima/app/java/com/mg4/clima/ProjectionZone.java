package com.mg4.clima;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 1.4.2: clima durante Android Auto e CarPlay (opzione, spenta di default). Durante la proiezione il SystemUI MG non
 * apre il clima dalla barra in alto. Con l'opzione accesa, mentre in primo piano c'è Android Auto
 * (com.allgo.app.androidauto) o CarPlay (com.allgo.carplay.service), una zona invisibile al centro della barra in alto
 * apre MG4 Clima. La zona è larga quanto il blocco centrale della barra, alta quanto la barra, non disegna niente e
 * sparisce appena la proiezione non è più in primo piano. Finestra di sistema sopra la barra: 2017, poi 2032, poi 2010
 * (vedi {@link #show}).
 */
final class ProjectionZone {
    static final String[] PROJECTION = {"com.allgo.app.androidauto", "com.allgo.carplay.service"};
    private static final int TYPE_SUB_PANEL = 2017;   // WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL (nascosto)
    private static final int ZONE_W = 360;

    private static View zone;
    private static String type = "-", lastPkg = "-";
    private static int opens;
    private static ClimaGuard service;

    private ProjectionZone() {
    }

    static void attach(ClimaGuard g) {
        service = g;
    }

    static boolean isProjection(String pkg) {
        for (String p : PROJECTION) if (p.equals(pkg)) return true;
        return false;
    }

    /** Dal servizio a ogni cambio di app in primo piano (thread principale). */
    static void onForeground(ClimaGuard g, String pkg) {
        service = g;
        if (pkg == null) return;
        lastPkg = pkg;
        boolean want = Store.projectionZone(g) && isProjection(pkg);
        if (want && zone == null) show(g);
        else if (!want && zone != null && !pkg.equals(g.getPackageName())) hide(g, "davanti " + pkg);
        else if (!want && zone != null) hide(g, "schermata MG4 Clima davanti");   // 1.4.5: solo la schermata vera
    }

    /** Opzione cambiata dalle impostazioni. */
    static void refresh(Context c) {
        ClimaGuard g = service;
        if (g == null) return;
        if (!Store.projectionZone(c)) hide(g, "opzione spenta");
        else if (isProjection(lastPkg) && zone == null) show(g);
    }

    private static int barHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? c.getResources().getDimensionPixelSize(id) : 0;
        return h >= 30 && h <= 160 ? h : 64;
    }

    /**
     * 1.4.4: finestre in ordine 2017 (STATUS_BAR_SUB_PANEL, con STATUS_BAR_SERVICE) → 2032 (ACCESSIBILITY_OVERLAY, dal
     * servizio di accessibilità) → 2010 (SYSTEM_ERROR, con INTERNAL_SYSTEM_WINDOW). Una View nuova a ogni tentativo:
     * nella 1.4.3 la stessa View, rifiutata con 2017, restava "già aggiunta" e 2032 dava IllegalStateException.
     */
    private static final int TYPE_SYSTEM_ERROR = 2010;

    private static void show(ClimaGuard g) {
        // WindowManager del servizio di accessibilità: ha il token che serve per 2032
        WindowManager wm = (WindowManager) g.getSystemService(Context.WINDOW_SERVICE);
        StringBuilder refused = new StringBuilder();
        for (int t : new int[]{TYPE_SUB_PANEL, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, TYPE_SYSTEM_ERROR}) {
            View v = new View(g);
            v.setBackgroundColor(0);   // invisibile
            v.setOnClickListener(x -> open(g));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ZONE_W, barHeight(g), t,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.setTitle("MG4 Clima zona proiezione");
            try {
                wm.addView(v, lp);
                zone = v;
                type = typeName(t);
                ClimaGuard.onZoneShown();   // 1.4.6: riga clima.avvio
                Store.event(g, "clima.proiezione: zona attiva sopra " + lastPkg + " · finestra " + type
                        + " · " + ZONE_W + "×" + lp.height + " px" + (refused.length() > 0 ? " (rifiutate: " + refused + ")" : ""));
                return;
            } catch (Throwable e) {
                refused.append(refused.length() > 0 ? ", " : "").append(t);
                Store.event(g, "clima.proiezione: finestra " + typeName(t) + " rifiutata (" + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")");
                try {
                    wm.removeViewImmediate(v);   // nel caso sia rimasta a metà
                } catch (Throwable ignored) {
                }
            }
        }
        type = "nessuna (rifiutate " + refused + ")";
    }

    private static String typeName(int t) {
        return t == TYPE_SUB_PANEL ? "STATUS_BAR_SUB_PANEL (2017)"
                : t == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY ? "ACCESSIBILITY_OVERLAY (2032)"
                : t == TYPE_SYSTEM_ERROR ? "SYSTEM_ERROR (2010)" : String.valueOf(t);
    }

    static void hide(ClimaGuard g, String why) {
        View v = zone;
        zone = null;
        if (v == null) return;
        try {
            ((WindowManager) g.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Throwable ignored) {
        }
        Store.event(g, "clima.proiezione: zona tolta (" + why + ")");
    }

    private static void open(ClimaGuard g) {
        opens++;
        g.openFromBar("zona sopra la barra");   // 1.4.5: avvio, controllo dopo 1 s, pannello se AllGo riprende lo schermo
    }

    /** Riga clima.proiezione: zona attiva sì/no, tipo di finestra, aperture. */
    static String stateText(Context c) {
        return "opzione " + (Store.projectionZone(c) ? "accesa" : "spenta") + " · zona attiva " + (zone != null ? "sì" : "no")
                + " · finestra " + type + " · aperture " + opens + " · in primo piano " + lastPkg;
    }
}
