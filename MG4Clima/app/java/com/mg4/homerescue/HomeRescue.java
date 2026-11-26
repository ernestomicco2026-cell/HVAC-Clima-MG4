package com.mg4.homerescue;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Soccorso della home MG originale (MG4 Launcher, issue #29). Classe autonoma: nessuna dipendenza dal launcher.
 * <p>
 * Il launcher, con "Avvio sicuro", disattiva la schermata principale della home MG. Se il launcher viene tolto dal
 * menu App di Android non ha modo di riaccenderla e l'auto resta su "Smartphone starting". Questa classe, inclusa in
 * un'altra app MG4 firmata con la stessa chiave platform (MG4 Store, MG4 Clima, MG4 Voice, MG4 Ripristino Home):
 * <ul>
 *   <li>ascolta PACKAGE_FULLY_REMOVED (esente dai limiti dei broadcast impliciti) per com.mg4.launcher, e
 *       BOOT_COMPLETED / MY_PACKAGE_REPLACED;</li>
 *   <li>se com.mg4.launcher non è installato e la schermata della home MG è disattivata, la riattiva
 *       (COMPONENT_ENABLED_STATE_DEFAULT) e rimette la home MG come predefinita;</li>
 *   <li>scrive una riga nel log dell'app che la contiene ({@link #sink}) e la conserva in SharedPreferences
 *       "mg4homerescue" (chiave "ultimo"), così l'app può metterla nei suoi log.</li>
 * </ul>
 * Permessi (concessi dalla firma platform): CHANGE_COMPONENT_ENABLED_STATE, SET_PREFERRED_APPLICATIONS.
 */
public final class HomeRescue extends BroadcastReceiver {
    public static final String LAUNCHER = "com.mg4.launcher";
    public static final String TAG = "HomeRescue";

    /** Home MG conosciute: {pacchetto, attività principale, firmware}. */
    public static final String[][] HOMES = {
            {"com.saicmotor.hmi.launcher", "com.saicmotor.hmi.launcher.ui.MainActivity", "SWI68 / SWI133"},
            {"com.saicmotor.launcher", "com.saicmotor.launcher.view.MainActivity", "SWI69 / SWI132"},
    };

    /** Dove finisce la riga di log: l'app che contiene la classe può collegarlo al suo log. */
    public interface Sink {
        void line(String key, String value);
    }

    public static volatile Sink sink;

    @Override
    public void onReceive(Context c, Intent i) {
        String action = i == null ? null : i.getAction();
        if (action == null) return;
        if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action) || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
            if (!LAUNCHER.equals(pkg)) return;
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;   // aggiornamento, non rimozione
        }
        PendingResult pr = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                rescueIfOrphan(app, action);
            } finally {
                pr.finish();
            }
        }, "mg4-homerescue").start();
    }

    /** Soccorso automatico: solo se il launcher NON è installato (con il launcher presente comanda lui). */
    public static String rescueIfOrphan(Context c, String why) {
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(why);
        if (installed(c, LAUNCHER)) {
            String r = "launcher presente: niente da fare (" + why + ")";
            if (!boot) log(c, r);
            return r;
        }
        // un altro launcher (es. MG4Dash) gestisce da sé la home MG: non lo si ostacola
        String other = otherLauncher(c);
        if (other != null) {
            String r = "c'è un altro launcher (" + other + "): niente da fare (" + why + ")";
            if (!boot) log(c, r);
            return r;
        }
        if (boot && mgHomeOk(c) && !anyMgDisabled(c)) return "home MG già attiva";   // accensione normale: nessuna riga
        return rescue(c, why);
    }

    /** Un'altra app home installata e attiva (non MG, non Android, non la nostra app di soccorso), oppure null. */
    public static String otherLauncher(Context c) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.startsWith("com.saicmotor") || p.startsWith("com.android") || p.equals("android")
                        || p.equals(c.getPackageName()) || p.equals(LAUNCHER)) continue;
                return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean anyMgDisabled(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String[] h : HOMES) {
            ComponentName cn = new ComponentName(h[0], h[1]);
            if (exists(pm, cn) && disabled(pm, cn)) return true;
        }
        return false;
    }

    /**
     * Riattiva la home MG su tutti i firmware conosciuti (e ogni altra home com.saicmotor rimasta disattivata) e la
     * rimette come predefinita. Restituisce l'esito in una riga. Da un thread di lavoro.
     */
    public static String rescue(Context c, String why) {
        PackageManager pm = c.getPackageManager();
        List<ComponentName> homes = new ArrayList<>();
        for (String[] h : HOMES) {
            ComponentName cn = new ComponentName(h[0], h[1]);
            if (exists(pm, cn)) homes.add(cn);
        }
        // firmware non ancora conosciuti: qualunque schermata HOME di com.saicmotor rimasta disattivata
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                if (cn.getPackageName().startsWith("com.saicmotor") && !homes.contains(cn)) homes.add(cn);
            }
        } catch (Throwable ignored) {
        }
        if (homes.isEmpty()) {
            String r = "nessuna home MG trovata (" + why + ")";
            log(c, r);
            return r;
        }
        StringBuilder sb = new StringBuilder();
        boolean allOn = true;
        ComponentName main = null;
        for (ComponentName cn : homes) {
            boolean was = disabled(pm, cn);
            if (was) {
                try {
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
                } catch (Throwable e) {
                    sb.append(cn.getPackageName()).append(": non riattivata (").append(e.getClass().getSimpleName()).append(") · ");
                }
            }
            boolean on = !disabled(pm, cn);
            allOn &= on;
            if (on && main == null && isKnown(cn)) main = cn;
            sb.append(cn.getPackageName()).append(was ? (on ? " riattivata" : " ancora disattivata") : " già attiva").append(" · ");
        }
        if (main == null) for (ComponentName cn : homes) if (!disabled(pm, cn)) {
            main = cn;
            break;
        }
        sb.append("predefinita: ").append(main == null ? "nessuna" : setDefault(pm, main));
        String r = (allOn ? "home MG ok" : "home MG NON ripristinata") + " (" + why + ") · " + sb;
        log(c, r);
        return r;
    }

    /** true se almeno una home MG conosciuta esiste ed è attiva. */
    public static boolean mgHomeOk(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String[] h : HOMES) {
            ComponentName cn = new ComponentName(h[0], h[1]);
            if (exists(pm, cn) && !disabled(pm, cn)) return true;
        }
        return false;
    }

    /** Apre la home MG (dopo il soccorso), true se partita. */
    public static boolean openMgHome(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String[] h : HOMES) {
            ComponentName cn = new ComponentName(h[0], h[1]);
            if (!exists(pm, cn) || disabled(pm, cn)) continue;
            try {
                c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isKnown(ComponentName cn) {
        for (String[] h : HOMES) if (h[0].equals(cn.getPackageName()) && h[1].equals(cn.getClassName())) return true;
        return false;
    }

    private static boolean exists(PackageManager pm, ComponentName cn) {
        try {
            pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean disabled(PackageManager pm, ComponentName cn) {
        try {
            return pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Home predefinita = target (replacePreferredActivity, API nascosta: reflection). */
    private static String setDefault(PackageManager pm, ComponentName target) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo cur = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            String curPkg = cur != null && cur.activityInfo != null ? cur.activityInfo.packageName : "";
            if (target.getPackageName().equals(curPkg)) return target.getPackageName() + " (già)";
            List<ResolveInfo> all = pm.queryIntentActivities(home, 0);
            List<ComponentName> set = new ArrayList<>();
            int best = 0;
            for (ResolveInfo ri : all) {
                set.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                best = Math.max(best, ri.match);
            }
            if (!set.contains(target)) set.add(target);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            PackageManager.class.getMethod("replacePreferredActivity", IntentFilter.class, int.class, ComponentName[].class, ComponentName.class)
                    .invoke(pm, f, best != 0 ? best : IntentFilter.MATCH_CATEGORY_EMPTY, set.toArray(new ComponentName[0]), target);
            ResolveInfo now = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            String nowPkg = now != null && now.activityInfo != null ? now.activityInfo.packageName : "nessuna";
            return target.getPackageName().equals(nowPkg) ? nowPkg + " (rimessa)" : "non cambiata (" + nowPkg + ")";
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            return "errore " + t.getClass().getSimpleName();
        }
    }

    private static void log(Context c, String line) {
        Log.i(TAG, line);
        try {
            SharedPreferences sp = c.getSharedPreferences("mg4homerescue", Context.MODE_PRIVATE);
            String at = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            sp.edit().putString("ultimo", at + " " + line).apply();
        } catch (Throwable ignored) {
        }
        Sink s = sink;
        if (s != null) {
            try {
                s.line("homeRescue", line);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Ultima riga scritta (con data e ora), per il log dell'app; null se non è mai successo niente. */
    public static String last(Context c) {
        try {
            return c.getSharedPreferences("mg4homerescue", Context.MODE_PRIVATE).getString("ultimo", null);
        } catch (Throwable e) {
            return null;
        }
    }
}
