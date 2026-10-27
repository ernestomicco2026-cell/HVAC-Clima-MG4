package com.mg4.clima;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Lingua di MG4 Clima (1.2.0, come il launcher MG4). "auto" segue la lingua di sistema dell'auto (se non è tra quelle tradotte si usa l'inglese);
 * altrimenti la lingua scelta nelle impostazioni. I testi sono in res/values-xx/strings.xml
 * (generati da tools/i18n.py).
 */
public final class L {
    public static final String AUTO = "auto";
    /** Lingue tradotte, con il loro nome nella lingua stessa. */
    public static final String[] CODES = {"it", "en", "es", "de", "pt", "fr", "tr", "pl"};
    public static final String[] NAMES = {"Italiano", "English", "Español", "Deutsch", "Português", "Français", "Türkçe", "Polski"};

    private static volatile Resources res;
    private static volatile Locale locale = Locale.ITALY;
    private static String systemTag = "";

    private L() {}

    /** Lingua di sistema dell'auto (quella che "auto" seguirebbe). */
    public static Locale systemLocale() {
        Configuration sys = Resources.getSystem().getConfiguration();
        return sys.getLocales().isEmpty() ? Locale.getDefault() : sys.getLocales().get(0);
    }

    /** Codice effettivo: per "auto" la lingua di sistema se tradotta, altrimenti inglese. */
    public static String resolve(String pref) {
        if (pref != null && !AUTO.equals(pref)) return pref;
        String sys = systemLocale().getLanguage();
        for (String c : CODES) if (c.equals(sys)) return c;
        return "en";
    }

    public static String nameOf(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        return code;
    }

    /** Prepara i testi per la lingua scelta. Restituisce true se la lingua è cambiata. */
    public static synchronized boolean init(Context c, String pref) {
        String code = resolve(pref);
        String tag = code + "|" + systemLocale().toLanguageTag();
        if (res != null && tag.equals(systemTag)) return false;
        systemTag = tag;
        Locale l = forCode(code);
        Configuration cfg = new Configuration(c.getApplicationContext().getResources().getConfiguration());
        cfg.setLocale(l);
        res = c.getApplicationContext().createConfigurationContext(cfg).getResources();
        locale = l;
        return true;
    }

    private static Locale forCode(String code) {
        switch (code) {
            case "it": return Locale.ITALY;
            case "en": return Locale.UK;
            case "es": return new Locale("es", "ES");
            case "de": return Locale.GERMANY;
            case "pt": return new Locale("pt", "PT");
            case "fr": return Locale.FRANCE;
            case "tr": return new Locale("tr", "TR");
            case "pl": return new Locale("pl", "PL");
            default: return new Locale(code);
        }
    }

    public static Locale locale() {
        return locale;
    }

    public static String s(int id) {
        Resources r = res;
        if (r == null) r = Resources.getSystem();
        try {
            return r.getString(id);
        } catch (Throwable e) {
            return "";
        }
    }

    public static String f(int id, Object... args) {
        return String.format(locale, s(id), args);
    }
}
