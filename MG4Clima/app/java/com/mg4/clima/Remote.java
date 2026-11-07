package com.mg4.clima;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 1.4.2: interventi da remoto, come nei launcher. Lo avvia il servizio di accessibilità (sempre attivo):
 * <ul>
 *   <li>controllo di releases/latest.json circa 2 minuti dopo il collegamento e poi ogni 6 ore (thread a parte);</li>
 *   <li>aggiornamento forzato se "minima" (dentro "MG4Clima") è più nuova della versione installata: solo ad auto
 *       ferma (velocità letta e uguale a 0), solo con size e sha256 presenti, stessa firma, al massimo 3 tentativi
 *       per versione; se la velocità non si legge non forza e resta l'avviso;</li>
 *   <li>avvisi da releases/avvisi.json: per versione, nella lingua dell'app (riserva inglese), una volta sola per id;
 *       "importante" si rimostra a ogni accensione finché non si preme OK.</li>
 * </ul>
 * Solo indirizzi HTTPS raw.githubusercontent.com del repository. Se un file manca o non si legge, non succede niente.
 */
final class Remote {
    static final String RAW = "https://raw.githubusercontent.com/ernestomicco2026-cell/HVAC-Clima-MG4/main/releases/";
    private static final long FIRST_MS = 2 * 60_000L, EVERY_MS = 6 * 3600_000L, RETRY_STOPPED_MS = 5 * 60_000L;
    static final int MAX_TRIES = 3;

    private static Handler bg;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile String updState = "non ancora controllato", noticeState = "non ancora controllati";
    private static volatile Updater.Release latest;
    /** Avviso da mostrare all'apertura della schermata (null = nessuno). */
    private static volatile Notice pending;
    /** Importanti già confermati con OK in questa accensione o per sempre (id). */
    private static final java.util.Set<String> shownThisBoot = new java.util.HashSet<>();

    static final class Notice {
        String id, text;
        boolean important, updateButton;
    }

    /** Dal servizio, una volta per collegamento: primo controllo dopo 2 minuti, poi ogni 6 ore. */
    static synchronized void start(Context c) {
        final Context ctx = c.getApplicationContext();
        if (bg != null) return;
        HandlerThread t = new HandlerThread("mg4-clima-remote");
        t.start();
        bg = new Handler(t.getLooper());
        bg.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkNow(ctx);
                bg.postDelayed(this, EVERY_MS);
            }
        }, FIRST_MS);
    }

    /** Controllo completo (thread di sfondo). */
    static void checkNow(Context ctx) {
        checkUpdate(ctx);
        checkNotices(ctx);
    }

    static Updater.Release latest() {
        return latest;
    }

    static Notice pending() {
        return pending;
    }

    // ---- aggiornamenti --------------------------------------------------------------------------------------------

    private static void checkUpdate(Context ctx) {
        String when = Store.ms(System.currentTimeMillis());
        try {
            JSONObject o = new JSONObject(text(RAW + "latest.json")).optJSONObject("MG4Clima");
            if (o == null) {
                updState = "controllato alle " + when + " · voce MG4Clima assente";
                return;
            }
            Updater.Release r = Updater.parse(ctx, o);
            latest = r;
            String mine = BuildInfo.version(ctx);
            boolean forced = !r.minima.isEmpty() && Updater.compare(mine, r.minima) < 0;
            String base = "controllato alle " + when + " · installata " + mine + " · ultima " + (r.version.isEmpty() ? "-" : r.version)
                    + (r.minima.isEmpty() ? "" : " · minima " + r.minima);
            if (!r.newer) {
                updState = base + " · aggiornata";
                return;
            }
            if (!forced) {
                updState = base + " · nuova versione disponibile (avviso)";
                return;
            }
            forced(ctx, r, base);
        } catch (Throwable e) {
            updState = "controllo alle " + when + " non riuscito: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : "") + " (nessun intervento)";
        }
    }

    /** Aggiornamento forzato: auto ferma, file completo e verificato, al massimo 3 tentativi per versione. */
    private static void forced(Context ctx, Updater.Release r, String base) {
        int tries = Store.forcedTries(ctx, r.version);
        if (tries >= MAX_TRIES) {
            updState = base + " · forzato: fermo dopo " + tries + " tentativi non riusciti (resta l'avviso)";
            return;
        }
        if (r.size <= 0 || r.sha256.isEmpty()) {
            updState = base + " · forzato: size o sha256 mancanti, non forzo (resta l'avviso)";
            return;
        }
        float v = speedNow(ctx);
        if (Float.isNaN(v)) {
            updState = base + " · forzato: velocità non leggibile, non forzo (resta l'avviso)";
            return;
        }
        if (v > 0.5f || ClimaActivity.resumed || AutoTest.running()) {
            updState = base + " · forzato: in attesa (" + (v > 0.5f ? "auto in movimento" : "MG4 Clima aperta")
                    + "), riprovo tra 5 minuti";
            if (bg != null) bg.postDelayed(() -> checkUpdate(ctx), RETRY_STOPPED_MS);
            return;
        }
        Store.setForcedTries(ctx, r.version, tries + 1);
        updState = base + " · forzato: tentativo " + (tries + 1) + "/" + MAX_TRIES + " ad auto ferma";
        Store.event(ctx, "aggiornamento forzato alla " + r.version + " (minima " + r.minima + "): tentativo " + (tries + 1)
                + "/" + MAX_TRIES);
        main.post(() -> Updater.get(ctx).install(r));
    }

    /** Velocità dell'auto in km/h (NaN se non si legge). */
    private static float speedNow(Context ctx) {
        try {
            HvacClient.State s = ClimaGuard.readState();
            if (s == null || !s.connected) return Float.NaN;
            return s.speed;
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    static String updateState() {
        return updState;
    }

    // ---- avvisi -------------------------------------------------------------------------------------------------

    private static void checkNotices(Context ctx) {
        String when = Store.ms(System.currentTimeMillis());
        String json;
        try {
            json = text(RAW + "avvisi.json");
        } catch (Throwable e) {
            noticeState = "controllati alle " + when + " · file non letto (" + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : "") + "): nessun avviso";
            return;
        }
        try {
            Notice n = pick(ctx, json, BuildInfo.version(ctx), L.lang());
            pending = n;
            int count = new JSONObject(json).optJSONArray("avvisi") == null ? 0 : new JSONObject(json).optJSONArray("avvisi").length();
            noticeState = "controllati alle " + when + " · " + count + " nel file · "
                    + (n == null ? "nessuno da mostrare" : "da mostrare: " + n.id + (n.important ? " (importante)" : ""))
                    + " · già visti: " + Store.noticesSeen(ctx);
        } catch (Throwable e) {
            noticeState = "controllati alle " + when + " · file non valido: " + e.getClass().getSimpleName() + ": nessun avviso";
        }
    }

    /**
     * Sceglie l'avviso da mostrare: versioni vuote o assenti = tutte; un avviso normale una volta sola (id ricordato),
     * uno "importante" a ogni accensione finché non si preme OK. Testo nella lingua dell'app, riserva inglese.
     */
    static Notice pick(Context ctx, String json, String version, String lang) throws Exception {
        JSONArray a = new JSONObject(json).optJSONArray("avvisi");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            JSONArray vs = o.optJSONArray("versioni");
            if (vs != null && vs.length() > 0) {
                boolean match = false;
                for (int k = 0; k < vs.length(); k++) if (version.equals(vs.optString(k))) match = true;
                if (!match) continue;
            }
            boolean important = o.optBoolean("importante", false);
            if (Store.noticeConfirmed(ctx, id)) continue;               // OK premuto: mai più
            if (!important && Store.noticeShown(ctx, id)) continue;     // normale: una volta sola
            synchronized (shownThisBoot) {
                if (important && shownThisBoot.contains(id)) continue;  // importante: una volta per accensione
            }
            JSONObject t = o.optJSONObject("testo");
            String text = t == null ? "" : t.optString(lang, t.optString("en", ""));
            if (text.trim().isEmpty()) continue;
            Notice n = new Notice();
            n.id = id;
            n.text = text.trim();
            n.important = important;
            n.updateButton = "aggiorna".equals(o.optString("pulsante", ""));
            return n;
        }
        return null;
    }

    /** La schermata ha mostrato l'avviso. */
    static void shown(Context ctx, Notice n) {
        Store.setNoticeShown(ctx, n.id);
        synchronized (shownThisBoot) {
            shownThisBoot.add(n.id);
        }
        if (pending == n) pending = null;
        Store.event(ctx, "avviso " + n.id + " mostrato" + (n.important ? " (importante)" : ""));
    }

    /** OK premuto: l'avviso non si mostra più. */
    static void confirmed(Context ctx, Notice n) {
        Store.setNoticeConfirmed(ctx, n.id);
        Store.event(ctx, "avviso " + n.id + ": OK");
    }

    static String noticeState() {
        return noticeState;
    }

    private static String text(String url) throws Exception {
        if (!url.startsWith("https://raw.githubusercontent.com/ernestomicco2026-cell/HVAC-Clima-MG4/")) throw new SecurityException("indirizzo non ammesso");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "MG4Clima");
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    bo.write(b, 0, n);
                    if (bo.size() > 256 * 1024) throw new Exception("file troppo grande");
                }
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** Per i test: azzera lo stato. */
    static void resetForTest() {
        pending = null;
        latest = null;
        synchronized (shownThisBoot) {
            shownThisBoot.clear();
        }
    }
}
