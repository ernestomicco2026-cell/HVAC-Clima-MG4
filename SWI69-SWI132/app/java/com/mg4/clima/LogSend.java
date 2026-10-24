package com.mg4.clima;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Invio della diagnostica nel repository privato dei log (come il launcher):
 *   CLIMA-SWI69/<codice installazione>/<aaaa-mm-gg>/<hh-mm-ss>-<motivo>.txt
 * Senza rete resta in coda (al massimo 20) e parte con l'invio successivo.
 * Il token NON è nel codice: build.sh genera LogConfig da tools/log-token.txt (escluso da git).
 */
public final class LogSend {
    private static final String REPO = "ernestomicco2026-cell/mg4-launcher-logs";
    private static final String FOLDER = "CLIMA-SWI69";
    private static final int MAX_QUEUE = 20;

    private LogSend() {
    }

    public static boolean configured() {
        return LogConfig.token().length() > 10;
    }

    /** Invia la diagnostica attuale (in un thread a parte). done riceve "ok" o l'errore; può essere null. */
    public static void send(Context c, String why, java.util.function.Consumer<String> done) {
        Context ctx = c.getApplicationContext();
        String text = Store.text(ctx);
        Store.markSent();
        new Thread(() -> {
            String r;
            if (!configured()) r = "invio log non configurato in questa versione";
            else {
                String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
                String time = new SimpleDateFormat("HH-mm-ss", Locale.ROOT).format(new Date());
                String path = FOLDER + "/" + Store.installId(ctx) + "/" + day + "/" + time + "-" + why + ".txt";
                byte[] data;
                try {
                    data = text.getBytes("UTF-8");
                } catch (Exception e) {
                    data = text.getBytes();
                }
                try {
                    put(path, data);
                    r = "ok";
                    flush(ctx);
                } catch (Exception e) {
                    r = "non inviato (" + e.getMessage() + "), riprovo al prossimo invio";
                    enqueue(ctx, path, data);
                }
            }
            android.util.Log.i("MG4Clima", "log: " + r);
            if (done != null) done.accept(r);
        }, "mg4-clima-log").start();
    }

    private static File queueDir(Context c) {
        File d = new File(c.createDeviceProtectedStorageContext().getFilesDir(), "log-coda");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static void enqueue(Context c, String path, byte[] data) {
        File dir = queueDir(c);
        File[] old = dir.listFiles();
        if (old != null && old.length >= MAX_QUEUE) {
            Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i <= old.length - MAX_QUEUE; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
        }
        try (OutputStream o = new FileOutputStream(new File(dir, path.replace('/', '~')))) {
            o.write(data);
        } catch (Exception ignored) {
        }
    }

    private static void flush(Context c) {
        File[] files = queueDir(c).listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            try {
                put(f.getName().replace('~', '/'), readAll(new FileInputStream(f)));
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Exception e) {
                return;
            }
        }
    }

    private static void put(String path, byte[] data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", "log " + path);
        body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/contents/" + path).openConnection();
        c.setRequestMethod("PUT");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + LogConfig.token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "MG4Clima");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code == 422) return;   // già presente
        if (code != 200 && code != 201) throw new Exception("HTTP " + code);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
