package com.mg4.clima;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * SystemUI corretto per MG4 Clima (SWI68 R46, R59, R63, R67/R69 e SWI133).
 * <p>
 * È il SystemUI originale con tre agganci in più (Mg4Hook): con l'opzione accesa il pannello clima di sistema non si
 * apre più da solo quando il clima si accende, e quando lo si chiede (tasto fisico del clima, barra di stato) si apre
 * MG4 Clima. Non è dentro l'APK: si scarica da un repository GitHub privato (token di sola lettura, generato da build.sh
 * in SysUiConfig) solo quando lo si attiva, si controlla
 * l'impronta SHA-256 scritta qui sotto e si installa come aggiornamento del SystemUI (stessa firma di piattaforma).
 * Spegnendo l'opzione l'aggiornamento si toglie e torna il SystemUI di fabbrica.
 * <p>
 * Sicurezze: solo per le versioni di fabbrica qui elencate (ognuna analizzata e verificata); se dopo un aggiornamento
 * del firmware la versione di fabbrica cambia, all'accensione l'aggiornamento si toglie da solo. Se MG4 Clima viene
 * disinstallata il SystemUI corretto si toglie da solo. Con l'opzione spenta gli agganci non fanno niente.
 */
final class SysUiPatch {
    static final String PKG = "com.android.systemui";
    /** Repository privato dei SystemUI corretti (non pubblico: contiene il SystemUI MG modificato). */
    static final String API_URL = "https://api.github.com/repos/ernestomicco2026-cell/clima/contents/";
    static final String RAW_URL = "https://raw.githubusercontent.com/ernestomicco2026-cell/clima/main/";

    /** {modello, versione del SystemUI di fabbrica, file, nome, dimensione, SHA-256}. */
    private static final String[][] VERSIONS = {
            {Model.SWI68, "SystemUI.45091", "SystemUI-SWI68-R46-mg4clima.apk", "R46", "18561518",
                    "2a1188b9fe6b28334b1c883214dbe0a517b2454c3ea48922da2ea4ebdd5cb9cc"},
            {Model.SWI68, "SystemUI.4C131", "SystemUI-SWI68-R59-mg4clima.apk", "R59", "18894954",
                    "bcb208773dee9bc82a0f7f78af39b46b118e9ec43dba0639bde7125258f91cf5"},
            {Model.SWI68, "1.0.0_EH32_SOP_2407251339", "SystemUI-SWI68-R63-mg4clima.apk", "R63", "18998080",
                    "0ce45fa9d375cf9c7fcbc47128f23578cbfc43690689e27e386af60dbae21f02"},
            {Model.SWI68, "1.0.0_EH32_SOP_2502201113", "SystemUI-SWI68-R69-mg4clima.apk", "R67/R69", "19022656",   // R67 = R69
                    "50bb3c13d623c430273b8e6e8c87fc28e68cea652082bd11a89847ce252194f9"},
            {Model.SWI133, "1.0.0_EH32_MCE24_SOP_20240227_2502201117", "SystemUI-SWI133-mg4clima.apk", "SWI133", "11488298",
                    "4d502440d687651d8d8807216ebc3635fb23f707cde3c58465a53c53db30dbf4"},
    };
    private static final String KEY = "mg4clima_pannello";
    private static final String ACTION_RESULT = "com.mg4.clima.SYSUI_RESULT";
    private static final int MATCH_FACTORY_ONLY = 0x00200000;

    interface Result {
        void done(boolean ok, String msg);
    }

    interface Progress {
        void at(String text);
    }

    private SysUiPatch() {
    }

    /** Versioni supportate del modello in uso (es. "R46, R59, R63, R67/R69"). */
    static String supportedList(Context c) {
        String m = Model.get(c);
        StringBuilder sb = new StringBuilder();
        for (String[] v : VERSIONS) if (v[0].equals(m)) sb.append(sb.length() == 0 ? "" : ", ").append(v[3]);
        return sb.toString();
    }

    private static String[] entry(Context c) {
        String f = factoryVersion(c);
        for (String[] v : VERSIONS) if (v[1].equals(f)) return v;
        return null;
    }

    /** Versione riconosciuta (es. "R69"), null se non supportata. */
    static String versionName(Context c) {
        String[] e = entry(c);
        return e == null ? null : e[3];
    }

    /** Dimensione da scaricare in MB (0 se non supportato). */
    static int sizeMb(Context c) {
        String[] e = entry(c);
        return e == null ? 0 : (int) Math.round(Long.parseLong(e[4]) / 1048576.0);
    }

    /** Già scaricato e controllato (non si riscarica). */
    static boolean downloaded(Context c) {
        String[] e = entry(c);
        if (e == null) return false;
        File f = new File(dir(c), e[2]);
        return f.length() == Long.parseLong(e[4]);
    }

    static String factoryVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(PKG, MATCH_FACTORY_ONLY);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    /** Il SystemUI di fabbrica è una delle versioni analizzate (solo SWI68 e SWI133). */
    static boolean supported(Context c) {
        return Model.sysUiPanel(c) && entry(c) != null;
    }

    /** C'è un aggiornamento del SystemUI installato (il nostro). */
    static boolean installed(Context c) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(PKG, 0);
            return (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean enabled(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), KEY, 0) == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /** SystemUI corretto installato e attivo. */
    static boolean active(Context c) {
        return installed(c) && enabled(c);
    }

    private static void setEnabled(Context c, boolean on) {
        try {
            Settings.Global.putInt(c.getContentResolver(), KEY, on ? 1 : 0);
        } catch (Throwable e) {
            Store.event(c, "SystemUI corretto: impostazione non scritta " + e);
        }
    }

    static String state(Context c) {
        if (installed(c)) return enabled(c) ? "attivo" : "installato, spento";
        return supported(c) ? "non installato" : "non disponibile per SystemUI " + factoryVersion(c);
    }

    /** Sospende gli agganci per qualche secondo (per aprire apposta il pannello clima originale). */
    static void suspend(Context c, long ms) {
        Context app = c.getApplicationContext();
        if (!enabled(app)) return;
        setEnabled(app, false);
        new Handler(Looper.getMainLooper()).postDelayed(() -> setEnabled(app, true), ms);
    }

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().createDeviceProtectedStorageContext().getFilesDir(), "systemui");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Accende: scarica (se serve) e controlla il SystemUI corretto, poi lo installa. */
    static void enable(Context c, Progress prog, Result r) {
        Context app = c.getApplicationContext();
        String[] e = entry(app);
        if (!Model.sysUiPanel(app) || e == null) {
            r.done(false, "SystemUI " + factoryVersion(app) + " non supportato");
            return;
        }
        if (installed(app)) {
            setEnabled(app, true);
            Store.event(app, "SystemUI corretto: già installato, opzione accesa");
            r.done(true, "già installato");
            return;
        }
        new Thread(() -> {
            try {
                File f = fetch(app, e, prog);
                post(() -> prog.at(BuildInfo.t("Installazione…", "Installing…")));
                setEnabled(app, true);
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(PKG);
                p.setSize(f.length());
                try {   // come "adb install -r": sostituisce
                    java.lang.reflect.Field fl = PackageInstaller.SessionParams.class.getField("installFlags");
                    fl.setInt(p, fl.getInt(p) | 0x2);
                } catch (Throwable ignored) {
                }
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream os = s.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        s.fsync(os);
                    }
                    s.commit(callback(app, id, "installazione", (ok, msg) -> {
                        if (!ok) setEnabled(app, false);
                        r.done(ok, msg);
                    }).getIntentSender());
                }
                Store.event(app, "SystemUI corretto " + e[3] + ": installazione inviata");
            } catch (Throwable ex) {
                setEnabled(app, false);
                Store.event(app, "SystemUI corretto: non installato " + ex);
                String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                post(() -> r.done(false, m));
            }
        }, "mg4-sysui-install").start();
    }

    /** File scaricato e controllato (si tiene: la seconda volta non si riscarica). */
    private static File fetch(Context app, String[] e, Progress prog) throws Exception {
        File dir = dir(app);
        File f = new File(dir, e[2]);
        long size = Long.parseLong(e[4]);
        if (f.length() == size && e[5].equals(sha256(f))) return f;
        File[] old = dir.listFiles();   // file di altre versioni: via
        if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
            o.delete();
        File tmp = new File(dir, e[2] + ".part");
        Exception first = null;
        if (SysUiConfig.token().length() < 10) throw new Exception(BuildInfo.t("download non configurato in questa versione",
                "download not configured in this version"));
        try {
            download(API_URL + e[2], "application/vnd.github.raw", tmp, size, prog);
        } catch (Exception ex) {
            first = ex;
        }
        if (first != null || tmp.length() != size || !e[5].equals(sha256(tmp))) {
            Store.event(app, "SystemUI: download dall'API non riuscito (" + (first != null ? first.getMessage() : "file diverso")
                    + "), provo raw.githubusercontent.com");
            download(RAW_URL + e[2], "application/octet-stream", tmp, size, prog);
        }
        if (tmp.length() != size) throw new Exception(BuildInfo.t("file incompleto", "incomplete file") + " (" + tmp.length() + " byte)");
        if (!e[5].equals(sha256(tmp))) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new Exception(BuildInfo.t("il file scaricato non è quello giusto: non installato",
                    "the downloaded file is not the right one: not installed"));
        }
        if (!tmp.renameTo(f)) throw new Exception("file non salvato");
        Store.event(app, "SystemUI " + e[3] + " scaricato e controllato (SHA-256 ok)");
        return f;
    }

    private static void download(String url, String accept, File out, long size, Progress prog) throws Exception {
        HttpURLConnection c = null;
        int code = 0;
        for (int hops = 0; hops < 5; hops++) {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("User-Agent", "MG4Clima");
            // token solo verso GitHub (non verso lo storage a cui GitHub può rimandare)
            String host = c.getURL().getHost();
            if ("api.github.com".equals(host) || "raw.githubusercontent.com".equals(host))
                c.setRequestProperty("Authorization", "Bearer " + SysUiConfig.token());
            code = c.getResponseCode();
            if (code / 100 != 3) break;
            url = c.getHeaderField("Location");
            c.disconnect();
            if (url == null) throw new Exception("redirect senza indirizzo");
        }
        if (code != 200) {
            c.disconnect();
            throw new Exception(code == 404 ? BuildInfo.t("file non trovato su GitHub", "file not found on GitHub")
                    : code == 401 || code == 403 ? BuildInfo.t("accesso negato (token scaduto?)", "access denied (token expired?)")
                    : "HTTP " + code);
        }
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            long done = 0;
            int lastPct = -1, n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                int pct = (int) Math.min(100, done * 100 / Math.max(1, size));
                if (pct / 5 != lastPct / 5) {
                    lastPct = pct;
                    final String t = BuildInfo.t("Download ", "Downloading ") + pct + "%";
                    post(() -> prog.at(t));
                }
            }
        } finally {
            c.disconnect();
        }
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** Spegne: l'opzione torna a 0 (comportamento originale subito) e si toglie l'aggiornamento del SystemUI. */
    static void disable(Context c, Result r) {
        Context app = c.getApplicationContext();
        setEnabled(app, false);
        if (!installed(app)) {
            r.done(true, "SystemUI originale");
            return;
        }
        try {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            android.content.IntentSender is = callback(app, 7001, "rimozione", r).getIntentSender();
            try {
                // come "togli aggiornamenti": DELETE_ALL_USERS (0x2), così si torna al SystemUI di fabbrica per tutti
                PackageInstaller.class.getMethod("uninstall", android.content.pm.VersionedPackage.class, int.class,
                        android.content.IntentSender.class).invoke(pi,
                        new android.content.pm.VersionedPackage(PKG, -1 /* VERSION_CODE_HIGHEST */), 0x2, is);
            } catch (NoSuchMethodException e) {
                pi.uninstall(PKG, is);
            }
            Store.event(app, "SystemUI corretto: rimozione inviata");
        } catch (Throwable e) {
            Store.event(app, "SystemUI corretto: rimozione non riuscita " + e);
            r.done(false, e.toString());
        }
    }

    /** All'accensione: se il firmware è cambiato (SystemUI di fabbrica non più tra quelli analizzati) l'aggiornamento si toglie. */
    static void bootCheck(Context c) {
        Context app = c.getApplicationContext();
        if (!installed(app)) return;
        String f = factoryVersion(app);
        if (entry(app) != null) return;
        Store.event(app, "SystemUI di fabbrica cambiato (" + f + "): tolgo il SystemUI corretto");
        disable(app, (ok, msg) -> Store.event(app, "SystemUI corretto tolto dopo l'aggiornamento del firmware: " + msg));
    }

    private static PendingIntent callback(Context app, int id, String what, Result r) {
        String action = ACTION_RESULT + "." + id;
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
                try {
                    app.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                boolean ok = st == PackageInstaller.STATUS_SUCCESS;
                String msg = ok ? "fatto" : "stato " + st + ": " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Store.event(app, "SystemUI corretto, " + what + ": " + msg);
                post(() -> r.done(ok, msg));
            }
        }, new IntentFilter(action));
        return PendingIntent.getBroadcast(app, id, new Intent(action).setPackage(app.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
