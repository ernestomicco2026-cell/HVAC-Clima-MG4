package com.mg4.clima;

import android.content.Context;
import android.os.SystemClock;
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
import java.util.regex.Pattern;

/**
 * Invio della diagnostica nel repository privato dei log, attraverso il ponte (1.4.7):
 *   PUT &lt;ponte&gt;/repos/ernestomicco2026-cell/mg4-launcher-logs/contents/CLIMA-&lt;modello&gt;/&lt;id&gt;/&lt;aaaa-mm-gg&gt;/&lt;hh-mm-ss&gt;-&lt;motivo&gt;.txt
 * Il ponte imita l'API di GitHub e accetta solo PUT di file .txt con questi percorsi.
 * Indirizzo del ponte e chiave NON sono nel codice: build.sh genera LogConfig da tools/log-relay.txt e
 * tools/log-token.txt (esclusi da git). La chiave serve solo a consegnare log, mai ad altro.
 * Senza rete resta in coda (al massimo 20) e parte con l'invio successivo. La coda delle versioni vecchie
 * (stessa cartella, stessi nomi) viene inviata anche lei attraverso il ponte.
 */
public final class LogSend {
    private static final String REPO = "ernestomicco2026-cell/mg4-launcher-logs";
    private static final int MAX_QUEUE = 20;
    /** Il ponte accetta circa 1 MB: si resta sotto con margine. */
    static final int MAX_BYTES = 900 * 1024;
    /** Dopo un 401 (chiave rifiutata) niente nuovi tentativi per un'ora. */
    private static final long KEY_PAUSE_MS = 60 * 60_000L;
    private static final Pattern PATH = Pattern.compile(
            "CLIMA-(SWI68|SWI69|SWI132|SWI133|DBI68)/[0-9a-f]{6,16}/[0-9]{4}-[0-9]{2}-[0-9]{2}/[A-Za-z0-9._-]{1,76}\\.txt");

    private static final Object LOCK = new Object();
    private static volatile long keyRefusedAt;

    private LogSend() {
    }

    public static boolean configured() {
        return LogConfig.token().length() > 10 && LogConfig.relay().startsWith("https://");
    }

    /** Esito di un PUT. */
    static final int SENT = 0, RETRY = 1, BAD_KEY = 2, DROP = 3;

    /** Come trattare la risposta del ponte. */
    static int outcome(int http) {
        if (http == 201 || http == 200 || http == 422) return SENT;   // 422 = già presente
        if (http == 401) return BAD_KEY;
        if (http == 413 || http == 400) return DROP;                  // troppo grande / percorso rifiutato: inutile riprovare
        return RETRY;
    }

    /** Motivo nel nome del file: solo lettere, cifre, punto, trattino e trattino basso. */
    static String cleanName(String s) {
        String r = s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "-");
        if (r.isEmpty()) r = "log";
        return r.length() > 40 ? r.substring(0, 40) : r;
    }

    /** Cifre non latine (es. ٢٠٢٦ scritto dalle versioni vecchie con il sistema in arabo) → 0-9. */
    static String asciiDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch > 127 && Character.isDigit(ch)) sb.append((char) ('0' + Character.digit(ch, 10)));
            else sb.append(ch);
        }
        return sb.toString();
    }

    static boolean validPath(String p) {
        return p != null && PATH.matcher(p).matches();
    }

    /** Percorso di un file in coda (anche delle versioni vecchie), sistemato; null se non si può sistemare. */
    static String fixQueued(String p) {
        String s = asciiDigits(p);
        int slash = s.lastIndexOf('/');
        if (slash < 0 || !s.endsWith(".txt")) return null;
        String name = s.substring(slash + 1, s.length() - 4).replaceAll("[^A-Za-z0-9._-]", "-");
        if (name.length() > 76) name = name.substring(0, 76);
        String r = s.substring(0, slash + 1) + name + ".txt";
        return validPath(r) ? r : null;
    }

    /** Taglia un testo troppo grande tenendo la parte finale (la più recente). */
    static byte[] fit(byte[] data) {
        if (data.length <= MAX_BYTES) return data;
        byte[] head = "[log tagliato: tenuta la parte finale]\n".getBytes();
        byte[] r = new byte[MAX_BYTES];
        System.arraycopy(head, 0, r, 0, head.length);
        System.arraycopy(data, data.length - (MAX_BYTES - head.length), r, head.length, MAX_BYTES - head.length);
        return r;
    }

    /** Invia la diagnostica attuale (in un thread a parte). done riceve "ok" o l'errore; può essere null. */
    public static void send(Context c, String why, java.util.function.Consumer<String> done) {
        Context ctx = c.getApplicationContext();
        String text = Store.text(ctx);
        Store.markSent();
        new Thread(() -> {
            String r;
            try {
                r = sendNow(ctx, why, text);
            } catch (Throwable e) {
                r = "non inviato (" + e.getClass().getSimpleName() + ")";
            }
            android.util.Log.i("MG4Clima", "log: " + r);
            if (done != null) done.accept(r);
        }, "mg4-clima-log").start();
    }

    private static String sendNow(Context ctx, String why, String text) throws Exception {
        Date now = new Date();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now);
        String time = new SimpleDateFormat("HH-mm-ss", Locale.US).format(now);
        String path = "CLIMA-" + Model.get(ctx) + "/" + Store.installId(ctx) + "/" + day + "/" + time + "-" + cleanName(why) + ".txt";
        byte[] data = fit(text.getBytes("UTF-8"));
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!validPath(path)) {
            Store.event(ctx, "log.invio: via ponte · " + name + " · percorso non valido, non inviato");
            return "percorso non valido";
        }
        synchronized (LOCK) {
            if (!configured()) {   // 1.4.7: APK senza chiave o senza ponte: il log resta in coda per la versione dopo
                enqueue(ctx, path, data);
                return "invio log non configurato in questa versione (log in coda)";
            }
            if (keyPaused()) {
                enqueue(ctx, path, data);
                Store.event(ctx, "log.invio: via ponte · " + name + " · in coda (chiave rifiutata da poco)");
                return "chiave rifiutata dal ponte, riprovo più tardi";
            }
            int http;
            try {
                http = put(path, data);
            } catch (Exception e) {
                enqueue(ctx, path, data);
                Store.event(ctx, "log.invio: via ponte · " + name + " · errore " + e.getClass().getSimpleName() + ", resta in coda");
                return "non inviato (rete), riprovo al prossimo invio";
            }
            int o = outcome(http);
            if (o == SENT) {
                Store.event(ctx, "log.invio: via ponte · " + name + " · " + http + (http == 422 ? " già presente" : " inviato"));
                String q = flush(ctx);
                if (q != null) Store.event(ctx, "log.invio: via ponte · coda: " + q);
                return "ok";
            }
            if (o == BAD_KEY) {
                keyRefusedAt = SystemClock.elapsedRealtime();
                enqueue(ctx, path, data);
                Store.event(ctx, "log.invio: via ponte · " + name + " · 401 chiave rifiutata, resta in coda, niente tentativi per 1 ora");
                return "chiave rifiutata dal ponte (401)";
            }
            if (o == DROP) {
                Store.event(ctx, "log.invio: via ponte · " + name + " · " + http + (http == 413 ? " troppo grande" : " rifiutato") + ", scartato");
                return "rifiutato dal ponte (HTTP " + http + ")";
            }
            enqueue(ctx, path, data);
            Store.event(ctx, "log.invio: via ponte · " + name + " · HTTP " + http + ", resta in coda");
            return "non inviato (HTTP " + http + "), riprovo al prossimo invio";
        }
    }

    private static boolean keyPaused() {
        long at = keyRefusedAt;
        return at > 0 && SystemClock.elapsedRealtime() - at < KEY_PAUSE_MS;
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

    /** Invia la coda (chiamato con LOCK preso). Ritorna un riassunto, o null se la coda era vuota. */
    private static String flush(Context c) {
        File[] files = queueDir(c).listFiles();
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        int sent = 0, dropped = 0;
        String stop = null;
        for (File f : files) {
            String p = fixQueued(f.getName().replace('~', '/'));
            if (p == null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                dropped++;
                continue;
            }
            int o;
            try {
                o = outcome(put(p, fit(readAll(new FileInputStream(f)))));
            } catch (Exception e) {
                stop = "rete";
                break;
            }
            if (o == SENT || o == DROP) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                if (o == SENT) sent++;
                else dropped++;
            } else {
                if (o == BAD_KEY) keyRefusedAt = SystemClock.elapsedRealtime();
                stop = o == BAD_KEY ? "401" : "errore";
                break;
            }
        }
        return sent + " inviati, " + dropped + " scartati" + (stop != null ? ", fermata (" + stop + ")" : "");
    }

    /** PUT al ponte. Ritorna il codice HTTP; eccezione solo se la rete non risponde. */
    private static int put(String path, byte[] data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", "log " + path);
        body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        HttpURLConnection c = (HttpURLConnection) new URL(LogConfig.relay() + "/repos/" + REPO + "/contents/" + path).openConnection();
        try {
            c.setRequestMethod("PUT");
            c.setInstanceFollowRedirects(false);   // la chiave non deve seguire un rinvio verso altri indirizzi
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
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
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
