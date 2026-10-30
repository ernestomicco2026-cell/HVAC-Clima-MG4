package com.mg4.clima;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Locale;

/**
 * Software dell'infotainment MG4 su cui gira MG4 Clima (stesso riconoscimento di MG4 Launcher).
 * <ul>
 *   <li><b>SWI68</b> (SAIC 1.0+, MG4 MY22) e <b>SWI133</b> (MG4 Luxury MY24): servizio veicolo SAIC
 *       (com.saicmotor.service.vehicle), pannello clima nel SystemUI;</li>
 *   <li><b>SWI69</b> (Color Radio, MY23) e <b>SWI132</b> (MY24): servizio caradapter (com.saicmotor.caradapter),
 *       app clima com.saicmotor.hvac.</li>
 * </ul>
 * Prima fonte la proprietà {@code ro.build.mt2712.version} (es. "SWI68-…-R69"); se manca, le app di sistema presenti e la
 * loro cartella (le MY24 hanno cartelle "..._my24_..."). Dalle impostazioni si può anche scegliere a mano.
 */
public final class Model {
    public static final String SWI68 = "SWI68", SWI69 = "SWI69", SWI132 = "SWI132", SWI133 = "SWI133";
    public static final String[] ALL = {SWI68, SWI69, SWI132, SWI133};

    private static final String[] HMI_APPS = {
            "com.saicmotor.hmi.launcher", "com.saicmotor.hmi.systemsettings", "com.saicmotor.hmi.radio",
            "com.saicmotor.hmi.hvac", "com.saicmotor.hmi.btcall", "com.saicmotor.hmi.vehiclesettings",
            "com.saicmotor.service.vehicle"};
    private static final String MG_HOME = "com.saicmotor.launcher";
    private static final String CARADAPTER = "com.saicmotor.caradapter";

    private static String detected, how, software;

    private Model() {
    }

    /** Modello in uso: scelto a mano nelle impostazioni, altrimenti quello riconosciuto. */
    public static String get(Context c) {
        String m = Store.model(c);
        for (String k : ALL) if (k.equals(m)) return k;
        return detected(c);
    }

    public static boolean manual(Context c) {
        String m = Store.model(c);
        for (String k : ALL) if (k.equals(m)) return true;
        return false;
    }

    /** Servizio veicolo SAIC (SWI68, SWI133); altrimenti caradapter (SWI69, SWI132). */
    public static boolean saic(Context c) {
        String m = get(c);
        return SWI68.equals(m) || SWI133.equals(m);
    }

    /** Il pannello clima è del SystemUI MG (SWI68, SWI133): si chiude col suo comando e c'è il SystemUI corretto. */
    public static boolean sysUiPanel(Context c) {
        return saic(c);
    }

    /** Nome del software dell'auto (ro.build.mt2712.version), "" se non c'è. */
    public static synchronized String software() {
        if (software == null) software = prop("ro.build.mt2712.version");
        return software;
    }

    /** Come è stato riconosciuto (per le impostazioni e la diagnostica). */
    public static synchronized String how(Context c) {
        detected(c);
        return how;
    }

    public static synchronized String detected(Context c) {
        if (detected != null) return detected;
        String sw = software();
        String fromProp = fromSoftware();
        if (fromProp != null) {
            detected = fromProp;
            how = "software " + sw + (family(sw) != null ? " (" + family(sw)[1] + ")" : "");
            return detected;
        }
        PackageManager pm = c.getPackageManager();
        String hmiDir = null;
        for (int i = 0; hmiDir == null && i < HMI_APPS.length; i++) hmiDir = dir(pm, HMI_APPS[i]);
        String mgDir = dir(pm, MG_HOME);
        boolean caradapter = dir(pm, CARADAPTER) != null;
        if (caradapter || (mgDir != null && hmiDir == null)) {
            String d = mgDir != null ? mgDir : dir(pm, "com.saicmotor.hvac");
            detected = my24(d) ? SWI132 : SWI69;
        } else if (hmiDir != null) {
            detected = my24(hmiDir) ? SWI133 : SWI68;
        } else {
            detected = SWI68;   // non è una MG4 conosciuta: si parte dalla più diffusa (scelta a mano nelle impostazioni)
            how = "non riconosciuto";
            return detected;
        }
        how = "app di sistema (" + (caradapter ? "caradapter" : "servizio veicolo SAIC") + ")";
        return detected;
    }

    /**
     * Software noti con un altro nome, ricondotti al modello con lo stesso clima: {prefisso, spiegazione, modello}.
     * 1.3.1: SWI165-29176 (stessa piattaforma 29176 della SWI133) ha però l'app clima com.saicmotor.hmi.hvac e il
     * servizio veicolo SAIC come la SWI68, e il test dei comandi come SWI68 è riuscito (23/23): si usa la SWI68.
     * L'interfaccia del pannello clima di sistema si legge dal SystemUI (SysUiPanel), quindi va bene in entrambi i casi.
     */
    private static final String[][] FAMILY = {
            {"SWI165", "stessa app clima della SWI68", SWI68},
            // 1.3.3: mercato turco (DBI68-29558-1X00RXX-…): app clima com.saicmotor.hmi.hvac EH32_SOP_TUR come la SWI68,
            // test dei comandi come SWI68 riuscito (falliva solo il salto da LO a 32, come sulle altre EH32_SOP)
            {"DBI68", "mercato turco, stessa app clima della SWI68", SWI68},
    };

    private static String[] family(String sw) {
        String u = sw == null ? "" : sw.trim().toUpperCase(Locale.ROOT);
        for (String[] f : FAMILY) if (u.equals(f[0]) || u.startsWith(f[0] + "-") || u.startsWith(f[0] + "_")) return f;
        return null;
    }

    /** Modello riconosciuto dal nome del software (ro.build.mt2712.version), null se il nome non dice niente. */
    public static String fromSoftware() {
        String sw = software();
        String u = sw.trim().toUpperCase(Locale.ROOT);
        for (String k : new String[]{SWI132, SWI133, SWI68, SWI69}) {
            if (u.equals(k) || u.startsWith(k + "-") || u.startsWith(k + "_")) return k;
        }
        String[] f = family(sw);
        return f != null ? f[2] : null;
    }

    /** Nome da mostrare, es. "SWI68 SAIC 1.0+ (MG4 MY22)". */
    public static String label(String m) {
        switch (m) {
            case SWI68: return "SWI68 · MG4 MY22";
            case SWI69: return "SWI69 · MG4 MY23";
            case SWI132: return "SWI132 · MG4 MY24";
            case SWI133: return "SWI133 · MG4 Luxury MY24";
            default: return m;
        }
    }

    private static boolean my24(String dir) {
        return dir != null && dir.toLowerCase(Locale.ROOT).contains("_my24");
    }

    private static String dir(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            String p = ai.sourceDir;
            if (p == null) return "";
            int slash = p.lastIndexOf('/');
            return slash > 0 ? p.substring(0, slash) : p;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String prop(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable e) {
            return "";
        }
    }
}
