package com.mg4.clima;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Aggiornamento di MG4 Clima dalle impostazioni, come MG4 Launcher: legge releases/latest.json del repository GitHub
 * (voce "MG4Clima", un solo APK per tutti i modelli), scarica l'APK, controlla dimensione, impronta SHA-256 (se c'è),
 * pacchetto e versione, e lo installa con PackageInstaller (firma di piattaforma: nessuna conferma).
 */
final class Updater {
    private static final String REPO = "ernestomicco2026-cell/HVAC-Clima-MG4";
    private static final String API = "https://api.github.com/repos/" + REPO + "/contents/";
    private static final String RAW = "https://raw.githubusercontent.com/" + REPO + "/main/";
    private static final String KEY = "MG4Clima";
    private static final String ACTION_RESULT = "com.mg4.clima.UPDATE_RESULT";

    static final class Release {
        String version = "", notes = "", apk = "", sha256 = "";
        long size;
        boolean newer;
    }

    interface Listener {
        /** Sul thread principale. busy = operazione in corso. */
        void status(String text, Release r, boolean busy);
    }

    private static Updater instance;
    private final Context ctx;
    private final Handler bg, main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile boolean busy;
    private String lastText = "";
    private Release last;

    static synchronized Updater get(Context c) {
        if (instance == null) instance = new Updater(c.getApplicationContext());
        return instance;
    }

    private Updater(Context c) {
        ctx = c;
        HandlerThread t = new HandlerThread("mg4-clima-update");
        t.start();
        bg = new Handler(t.getLooper());
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean busy() {
        return busy;
    }

    String lastText() {
        return lastText;
    }

    Release last() {
        return last;
    }

    private void status(String s, Release r, boolean b) {
        busy = b;
        lastText = s;
        main.post(() -> {
            if (listener != null) listener.status(s, r, b);
        });
    }

    /** Controlla se c'è una versione nuova. */
    void check() {
        if (busy) return;
        status(L.s(R.string.u_checking), null, true);
        bg.post(() -> {
            try {
                Release r = latest();
                last = r;
                if (r.apk.isEmpty()) status(L.s(R.string.u_none), r, false);
                else if (r.newer) status(L.f(R.string.u_available, r.version), r, false);
                else status(L.f(R.string.u_latest, BuildInfo.version(ctx)), r, false);
                Store.event(ctx, "aggiornamenti: ultima " + (r.version.isEmpty() ? "nessuna" : r.version) + (r.newer ? " (nuova)" : ""));
            } catch (Exception e) {
                status(L.f(R.string.u_check_failed, msg(e)), null, false);
                Store.event(ctx, "aggiornamenti: controllo non riuscito " + e);
            }
        });
    }

    /** Scarica e installa la versione trovata. */
    void install(Release r) {
        if (busy || r == null || r.apk.isEmpty()) return;
        status(L.s(R.string.u_downloading), r, true);
        bg.post(() -> {
            File f = new File(ctx.getCacheDir(), "aggiornamento.apk");
            try {
                try {
                    download(RAW + r.apk, "application/octet-stream", f, r);
                    if (!good(f, r)) throw new Exception("file diverso");
                } catch (Exception first) {
                    Store.event(ctx, "aggiornamenti: download diretto non riuscito (" + first.getMessage() + "), provo l'API");
                    download(API + r.apk, "application/vnd.github.raw", f, r);
                }
                if (!good(f, r)) throw new Exception(L.s(R.string.u_bad_file));
                PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                if (pi == null || !ctx.getPackageName().equals(pi.packageName)) throw new Exception(L.s(R.string.u_not_app));
                status(L.f(R.string.u_installing, pi.versionName), r, true);
                Store.event(ctx, "aggiornamenti: installo " + pi.versionName);
                installApk(f);
            } catch (Exception e) {
                status(L.f(R.string.u_failed, msg(e)), r, false);
                Store.event(ctx, "aggiornamenti: non riuscito " + e);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        });
    }

    private static boolean good(File f, Release r) {
        if (f.length() < 1024) return false;
        if (r.size > 0 && f.length() != r.size) return false;
        return r.sha256.isEmpty() || r.sha256.equalsIgnoreCase(sha256(f));
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private Release latest() throws Exception {
        String json;
        try {
            json = text(API + "releases/latest.json", "application/vnd.github.raw");
        } catch (Exception e) {
            json = text(RAW + "releases/latest.json", "application/json");
        }
        JSONObject o = new JSONObject(json).optJSONObject(KEY);
        Release r = new Release();
        if (o == null) return r;
        r.version = o.optString("version", "");
        r.notes = o.optString("notes", "");
        r.apk = o.optString("apk", "");
        r.sha256 = o.optString("sha256", "");
        r.size = o.optLong("size", 0);
        r.newer = !r.apk.isEmpty() && compare(r.version, BuildInfo.version(ctx)) > 0;
        return r;
    }

    private static HttpURLConnection open(String url, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "MG4Clima");
        return c;
    }

    private static String text(String url, String accept) throws Exception {
        HttpURLConnection c = open(url, accept);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void download(String url, String accept, File out, Release r) throws Exception {
        HttpURLConnection c = open(url, accept);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new Exception(code == 404 ? L.s(R.string.u_apk_missing) : "HTTP " + code);
        }
        long total = r.size > 0 ? r.size : c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            long done = 0;
            int last = -1, n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) Math.min(100, done * 100 / total);
                    if (pct / 10 != last / 10) {
                        last = pct;
                        status(L.f(R.string.u_download_pct, pct), r, true);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private void installApk(File f) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        p.setSize(f.length());
        // memoria interna: il firmware MG blocca le app sull'archivio "automatico" (come adb install -r -f)
        p.setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        try {
            java.lang.reflect.Field fl = PackageInstaller.SessionParams.class.getField("installFlags");
            fl.setInt(p, fl.getInt(p) | 0x2 | 0x10);
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
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    }
                    try {
                        ctx.unregisterReceiver(this);
                    } catch (Throwable ignored) {
                    }
                    String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    if (st == PackageInstaller.STATUS_SUCCESS) status(L.s(R.string.u_updated), null, false);
                    else status(L.f(R.string.u_install_failed, m == null ? "status " + st : m), null, false);
                    Store.event(ctx, "aggiornamenti: installazione " + (st == PackageInstaller.STATUS_SUCCESS ? "ok" : "stato " + st + " " + m));
                }
            }, new IntentFilter(ACTION_RESULT));
            PendingIntent cb = PendingIntent.getBroadcast(ctx, id, new Intent(ACTION_RESULT).setPackage(ctx.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(cb.getIntentSender());
        } catch (Exception e) {
            try {
                pi.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /** Confronta versioni tipo "1.0.0" e "0.4.3-beta" numero per numero. */
    static int compare(String a, String b) {
        int[] x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int[] nums(String v) {
        String core = v == null ? "" : v.split("-")[0];
        String[] parts = core.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
