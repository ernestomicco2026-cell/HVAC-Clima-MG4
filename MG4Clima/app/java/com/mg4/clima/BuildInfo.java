package com.mg4.clima;

import android.content.Context;

import java.util.Locale;

/** Versione dell'app e lingua (italiano o inglese). */
public final class BuildInfo {
    private BuildInfo() {
    }

    public static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 1.4.0: codice della versione (per il popup "Novità" una volta per versione). */
    @SuppressWarnings("deprecation")
    public static int code(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Testo nella lingua del sistema: italiano se l'auto è in italiano, altrimenti inglese. */
    public static String t(String it, String en) {
        return "it".equals(Locale.getDefault().getLanguage()) ? it : en;
    }
}
