package com.mg4.clima;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/**
 * Test automatico dei comandi del clima (avviato a mano dalle impostazioni, solo ad auto ferma).
 * <p>
 * Manda i comandi uno alla volta come l'app clima originale e controlla che lo stato letto cambi come previsto.
 * Alla fine rimette tutto com'era (temperatura, ventola, direzione, tasti premuti due volte, clima spento se era spento).
 * Si ferma subito se l'auto si muove, si spegne, si esce dalla schermata o un comando dà errore.
 * Ogni passo va negli eventi della diagnostica, che poi viene inviata nei log.
 */
public final class AutoTest {
    public interface Progress {
        void step(String text);
    }

    private final HvacClient h;
    private final Progress progress;
    private final android.content.Context ctx;
    private final String kind;
    private int ok, bad, step;
    private final List<String> fails = new ArrayList<>();
    /** 1.3.3: parti già fatte (ripresa dopo un'interruzione) e valori di partenza da rimettere alla fine. */
    private final java.util.Set<String> done = new java.util.LinkedHashSet<>();
    private int t0 = -1, f0 = -1, m0 = -1, l0 = -1, auto0 = -1, power0 = -1;
    private boolean poweredOn;

    private AutoTest(android.content.Context c, HvacClient h, Progress p, String kind) {
        ctx = c;
        this.h = h;
        progress = p;
        this.kind = kind;
    }

    /** Messaggio di interruzione per auto in movimento (riconosciuto dalla schermata). */
    static final String MOVING = "auto in movimento";

    private static volatile boolean running;

    /** 1.4.2: test o verifica in corso (niente aggiornamento forzato nel frattempo). */
    static boolean running() {
        return running;
    }

    /** Avvia il test su un thread a parte; done riceve il riassunto. kind: "test" o "verifica" (per la ripresa). */
    public static void start(android.content.Context c, HvacClient h, Progress p, String kind,
                             java.util.function.Consumer<String> done) {
        new Thread(() -> {
            AutoTest t = new AutoTest(c, h, p, kind);
            String r;
            h.testing = true;
            running = true;
            try {
                t.load();
                r = t.run();
                Store.setTestProgress(c, kind, "");   // finito: niente da riprendere
            } catch (Abort a) {
                r = "interrotto: " + a.getMessage() + " (" + t.ok + " ok, " + t.bad + " non riusciti)";
                Store.event(c, "TEST " + r);
            } catch (Throwable e) {
                r = "errore: " + e + " (" + t.ok + " ok, " + t.bad + " non riusciti)";
                Store.event(c, "TEST " + r);
            } finally {
                h.testing = false;
                running = false;
            }
            done.accept(r);
        }, "mg4-clima-test").start();
    }

    private static final class Abort extends RuntimeException {
        Abort(String m) {
            super(m);
        }
    }

    private void log(String s) {
        Store.event(ctx, "TEST " + s);
    }

    private HvacClient.State safe() {
        if (!h.running()) throw new Abort("schermata chiusa");
        HvacClient.State s = h.readNow();
        if (!s.connected) throw new Abort("servizio clima non collegato " + s.error);
        if (!(s.ignition == 2 || s.ignition == 3)) throw new Abort("auto non accesa (" + s.ignition + ")");
        if (!Float.isNaN(s.speed) && s.speed >= 1f) throw new Abort(MOVING + " (" + s.speed + " km/h)");
        return s;
    }

    private void send(String what, int code, Integer arg) {
        safe();
        step++;
        progress.step("passo " + step + " · " + what);
        try {
            h.send(code, arg);
        } catch (Exception e) {
            log("✗ " + what + ": errore nel comando " + e);
            throw new Abort("comando rifiutato (" + what + ")");
        }
    }

    /** Aspetta (max 5 s) che il valore soddisfi la condizione; restituisce l'ultimo valore letto. */
    private int waitFor(ToIntFunction<HvacClient.State> f, IntPredicate cond) {
        long end = SystemClock.elapsedRealtime() + 5000;
        int v;
        do {
            SystemClock.sleep(400);
            v = f.applyAsInt(safe());
            if (cond.test(v)) return v;
        } while (SystemClock.elapsedRealtime() < end);
        return v;
    }

    private void check(String what, boolean good, String detail) {
        if (good) ok++;
        else {
            bad++;
            fails.add(what);
        }
        log((good ? "✓ " : "✗ ") + what + " → " + detail);
    }

    /** Comando con valore preciso (temperatura, ventola, direzione). */
    private void setValue(String what, int code, int value, ToIntFunction<HvacClient.State> f) {
        send(what + " = " + value, code, value);
        int v = waitFor(f, x -> x == value);
        check(what + " = " + value, v == value, "letto " + v);
        SystemClock.sleep(600);
    }

    /** Tasto acceso/spento: premuto due volte, deve cambiare e poi tornare com'era. 1.4.4: ripristino sempre. */
    private void toggle(String what, int code, ToIntFunction<HvacClient.State> f) {
        int v0 = f.applyAsInt(safe());
        try {
            send(what + " (premuto)", code, 1);
            int v1 = waitFor(f, x -> x != v0);
            check(what + " premuto", v1 != v0, v0 + " → " + v1);
            SystemClock.sleep(READBACK_MS);
            send(what + " (di nuovo)", code, 1);
            int v2 = waitFor(f, x -> x == v0);
            check(what + " ripremuto", v2 == v0, v1 + " → " + v2);
            SystemClock.sleep(600);
        } finally {
            restore(what, f, v0, code, -1, -1);
        }
    }

    /** 1.4.4: l'auto rilegge sedili, volante e ricircolo con circa 2 s di ritardo; prima si ripremeva troppo presto. */
    static final int READBACK_MS = 2000;

    /**
     * Tasto a più livelli (sedili, ricircolo): premuto finché torna com'era, registra la sequenza.
     * 1.4.4: dopo ogni pressione si aspetta che il valore riletto si fermi (READBACK_MS) prima di premere di nuovo;
     * alla fine, anche se il test si interrompe, si rimette il valore di partenza ({@link #restore}).
     */
    private void cycle(String what, int code, ToIntFunction<HvacClient.State> f, int maxPress, int offCode, int[] direct) {
        int v0 = f.applyAsInt(safe()), v = v0;
        try {
            StringBuilder seq = new StringBuilder().append(v0);
            boolean changed = false;
            for (int i = 0; i < maxPress; i++) {
                send(what + " (pressione " + (i + 1) + ")", code, 1);
                final int before = v;
                waitFor(f, x -> x != before);
                SystemClock.sleep(READBACK_MS);          // valore stabile, non quello di passaggio
                v = f.applyAsInt(safe());
                seq.append(" → ").append(v);
                if (v != before) changed = true;
                else break;   // non cambia: inutile insistere
                if (v == v0) break;
                SystemClock.sleep(600);
            }
            check(what + " a livelli", changed && v == v0, seq + (v == v0 ? "" : " (non è tornato a " + v0 + ")"));
        } finally {
            restore(what, f, v0, code, offCode, direct == null || v0 < 0 || v0 >= direct.length ? -1 : direct[v0]);
        }
    }

    /** Lettura per il ripristino: vale anche con la schermata chiusa o l'auto in movimento (null = auto non leggibile). */
    private HvacClient.State readForRestore() {
        try {
            HvacClient.State s = h.readNow();
            if (!s.connected || !(s.ignition == 2 || s.ignition == 3)) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean moving(HvacClient.State s) {
        return !Float.isNaN(s.speed) && s.speed >= 1f;
    }

    private int waitRestore(ToIntFunction<HvacClient.State> f, int want, long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        int v = Integer.MIN_VALUE;
        do {
            SystemClock.sleep(400);
            HvacClient.State s = readForRestore();
            if (s == null) return Integer.MIN_VALUE;
            v = f.applyAsInt(s);
            if (v == want) return v;
        } while (SystemClock.elapsedRealtime() < end);
        return v;
    }

    /**
     * 1.4.4: rimette il valore di partenza v0. Prima il comando diretto (directCode, che porta proprio a v0; per i sedili
     * offCode = spento), poi, se serve, le pressioni (mai con l'auto in movimento), aspettando il valore stabile.
     * Fino a 3 tentativi; se non riesce è un errore grave nel log.
     */
    private void restore(String what, ToIntFunction<HvacClient.State> f, int v0, int pressCode, int offCode, int directCode) {
        if (v0 < 0) return;
        HvacClient.State s = readForRestore();
        if (s == null) {
            log("✗✗ ERRORE GRAVE: " + what + " NON ripristinato a " + v0 + " (auto spenta o servizio non collegato)");
            return;
        }
        if (s.caradapter) {
            directCode = -1;   // comandi diretti solo sulle SAIC
            offCode = -1;
        }
        int v = f.applyAsInt(s);
        if (v == v0) {   // già com'era (1.4.7: scritto nel log, per vederlo in auto)
            log("✓ " + what + " alla fine a " + v0 + ", come all'inizio");
            return;
        }
        String how = "";
        boolean stopMoving = false;
        for (int attempt = 1; attempt <= 3 && v != v0 && !stopMoving; attempt++) {
            try {
                if (directCode >= 0) {                       // ricircolo 0/1: porta proprio a v0
                    h.send(directCode, null);
                    how = "comando diretto";
                    v = waitRestore(f, v0, 4000);
                } else if (offCode >= 0) {                   // sedile: spento diretto, poi giù da 3 se serve
                    h.send(offCode, null);
                    how = "comando diretto";
                    v = waitRestore(f, 0, 4000);
                    if (v0 > 0 && v == 0) {
                        how = "comando diretto e pressioni";
                        for (int k = 0; k < 4 - v0; k++) {   // spento → 3 → 2 → 1
                            HvacClient.State m = readForRestore();
                            if (m == null) break;
                            if (moving(m)) {
                                stopMoving = true;
                                break;
                            }
                            h.send(pressCode, 1);
                            SystemClock.sleep(READBACK_MS);
                        }
                        HvacClient.State r = readForRestore();
                        v = r == null ? Integer.MIN_VALUE : f.applyAsInt(r);
                    }
                } else {                                      // solo pressioni (volante, A/C, ECO, ricircolo automatico)
                    how = "pressione";
                    for (int k = 0; k < 3 && v != v0; k++) {
                        HvacClient.State m = readForRestore();
                        if (m == null) break;
                        if (moving(m)) {
                            stopMoving = true;
                            break;
                        }
                        h.send(pressCode, 1);
                        SystemClock.sleep(READBACK_MS);
                        HvacClient.State r = readForRestore();
                        if (r == null) break;
                        v = f.applyAsInt(r);
                    }
                }
            } catch (Throwable e) {
                how = "errore " + e.getClass().getSimpleName();
            }
            if (v != v0 && !stopMoving) SystemClock.sleep(800);
        }
        if (v == v0) log("✓ " + what + " ripristinato a " + v0 + " (" + how + ")");
        else {
            log("✗✗ ERRORE GRAVE: " + what + " NON ripristinato a " + v0 + " (letto " + (v == Integer.MIN_VALUE ? "—" : v)
                    + (stopMoving ? ", auto in movimento: niente pressioni" : "") + (how.isEmpty() ? "" : ", " + how) + ")");
            bad++;
            fails.add("ripristino " + what);
        }
    }

    // ---- 1.4.4: prove fallite non ripetute -----------------------------------------------------------------------
    // Sedili, volante e ricircolo: se una prova non riesce, per la stessa versione di MG4 Clima e dell'app clima
    // originale non si rifà (niente pressioni a ogni verifica); si usa il risultato salvato.

    private boolean tryPart(String part) {
        String saved = Store.partFailed(ctx, BuildInfo.version(ctx), version(), part);
        if (saved.isEmpty()) return true;
        bad++;
        fails.add(part);
        log("✗ " + part + " → non riprovato: già fallito con MG4 Clima " + BuildInfo.version(ctx) + " e app clima "
                + version() + " (risultato salvato: " + saved + ")");
        return false;
    }

    private void partDone(String part, int failsBefore) {
        if (fails.size() > failsBefore)
            Store.setPartFailed(ctx, BuildInfo.version(ctx), version(), part,
                    String.join(", ", fails.subList(failsBefore, fails.size())));
    }

    // ---- 1.3.3: ripresa da dove si era fermato ------------------------------------------------------------------
    // Dopo ogni parte riuscita si salvano parti fatte, conteggi e valori di partenza (per la stessa versione dell'app
    // clima). Se il test si interrompe (es. l'auto si muove) la volta dopo riparte dalla prima parte non fatta.

    private String version() {
        return ClimaGuard.originalVersion(ctx);
    }

    private void load() {
        String raw = Store.testProgress(ctx, kind);
        if (raw.isEmpty()) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            if (!version().equals(o.optString("versione"))) return;
            org.json.JSONArray d = o.getJSONArray("fatte");
            for (int i = 0; i < d.length(); i++) done.add(d.getString(i));
            ok = o.optInt("ok");
            bad = o.optInt("ko");
            org.json.JSONArray f = o.optJSONArray("falliti");
            if (f != null) for (int i = 0; i < f.length(); i++) fails.add(f.getString(i));
            t0 = o.optInt("t0", -1);
            f0 = o.optInt("f0", -1);
            m0 = o.optInt("m0", -1);
            l0 = o.optInt("l0", -1);
            auto0 = o.optInt("auto0", -1);
            power0 = o.optInt("power0", -1);
            log("RIPRESA · già fatte: " + String.join(", ", done) + " (" + ok + " ok, " + bad + " non riusciti)");
        } catch (Throwable e) {
            done.clear();
        }
    }

    private void save() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("versione", version());
            o.put("fatte", new org.json.JSONArray(done));
            o.put("ok", ok).put("ko", bad).put("falliti", new org.json.JSONArray(fails));
            o.put("t0", t0).put("f0", f0).put("m0", m0).put("l0", l0).put("auto0", auto0).put("power0", power0);
            Store.setTestProgress(ctx, kind, o.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Ci sono parti già fatte di questo tipo di test, per la versione attuale dell'app clima. */
    static boolean resumable(android.content.Context c, String kind) {
        String raw = Store.testProgress(c, kind);
        if (raw.isEmpty()) return false;
        try {
            return ClimaGuard.originalVersion(c).equals(new org.json.JSONObject(raw).optString("versione"));
        } catch (Throwable e) {
            return false;
        }
    }

    /** true se la parte è da fare; altrimenti la salta (già fatta prima dell'interruzione). */
    private boolean todo(String part) {
        if (!done.contains(part)) return true;
        log("· " + part + ": già fatto, saltato");
        return false;
    }

    private void did(String part) {
        done.add(part);
        save();
    }

    /** Sale o scende di un grado alla volta fino a target (come il tasto + dell'app originale). */
    /**
     * 1.4.6: rimette una temperatura dopo LO/HI. Sulle SWI69/SWI132 un grado alla volta (dopo la discesa a 18 o la
     * salita a HI il salto diretto può essere rifiutato), sulle SAIC come prima.
     */
    private void setTempBack(String what, boolean car, int target) {
        if (!car) {
            setValue(what, HvacClient.SET_DRV_TEMP, target, s -> s.temp);
            return;
        }
        stepTemp(target);
        int v = safe().temp;
        check(what + " = " + target, v == target, "letto " + v + " (un grado alla volta)");
    }

    private void stepTemp(int target) {
        int cur = safe().temp;
        int guard = 0;
        while (cur != target && guard++ < 20) {
            int next = cur < target ? cur + 1 : cur - 1;
            send("temperatura " + next + " (un grado alla volta)", HvacClient.SET_DRV_TEMP, next);
            final int n = next;
            int v = waitFor(s -> s.temp, x -> x == n);
            if (v != n) {
                check("temperatura " + n + " (un grado alla volta)", false, "letto " + v);
                return;
            }
            cur = v;
            SystemClock.sleep(300);
        }
    }

    // ---- 1.4.2: intervallo di temperatura reale dell'auto ------------------------------------------------------------
    // LO = il valore più basso che l'auto accetta, HI = il più alto. Si prova scendendo (LO) e salendo (HI) e ci si
    // ferma al primo valore che l'auto rifiuta: un "LO" che torna come HI (SWI69 R33, SWI132 R25: 15,5 → 31) non va
    // mai bene. SWI68/SWI133/DBI68: si parte da getMinTemp/getMaxTemp dell'auto (DBI68 si ferma a 31).
    // SWI69/SWI132: il servizio non dà i limiti; LO tra 17, 16, 15,5 e 15, HI tra 30,5, 31, 32 e 33.
    // Il risultato si salva per versione dell'app clima (Store.setTempRange) e lo usano i backend.
    private float calLoSend = Float.NaN, calLoRead = Float.NaN, calHiSend = Float.NaN, calHiRead = Float.NaN;
    private int calFanMax = -1;
    /** 1.4.6: SWI69/SWI132: minimo numerico misurato scendendo un grado alla volta (0 = non misurato). */
    private int calMinNum, descended;

    /** Temperatura come la manda l'auto: grezza (caradapter, decimali) o intera (SAIC). */
    private float raw(HvacClient.State s) {
        return s.caradapter ? s.tempRaw : s.temp;
    }

    private float waitRaw(java.util.function.DoublePredicate cond) {
        long end = SystemClock.elapsedRealtime() + 5000;
        float v;
        do {
            SystemClock.sleep(400);
            v = raw(safe());
            if (cond.test(v)) return v;
        } while (SystemClock.elapsedRealtime() < end);
        return v;
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    /** LO: dal più alto al più basso dei valori possibili; si tiene l'ultimo che l'auto rilegge uguale. */
    private void calibrateLow(boolean car, int lo, int mid) {
        float[] tries;
        if (car) tries = CarAdapterBackend.LO_TRY;
        else {   // SAIC: interi, dal più basso tra 17 e il minimo dichiarato fino a 2 gradi sopra
            int from = Math.min(lo, 17), to = lo + 2;
            tries = new float[to - from + 1];
            for (int i = 0; i < tries.length; i++) tries[i] = from + i;
        }
        StringBuilder tried = new StringBuilder();
        if (car) {
            // 1.4.6: prima si scende un grado alla volta fino a 18, come per HI: dai log della 1.4.5 la SWI69 rifiuta il
            // salto diretto (25 → 17 riletto 25) e LO restava nascosto. Se l'auto si ferma prima, quello è il minimo
            // numerico vero (la schermata non scende oltre) e LO resta nascosto.
            descended = 0;
            int cur = Math.round(raw(safe())), lastOk = cur;
            boolean blocked = false;
            while (cur > 18) {
                final int n = cur - 1;
                send("temperatura " + n + " (giù un grado alla volta)", HvacClient.SET_DRV_TEMP, n);
                float r = waitRaw(x -> same((float) x, n));
                if (!same(r, n)) {   // riprova una volta: può essere solo un ritardo
                    send("temperatura " + n + " (giù un grado alla volta, di nuovo)", HvacClient.SET_DRV_TEMP, n);
                    r = waitRaw(x -> same((float) x, n));
                }
                if (!same(r, n)) {
                    // ferma davvero solo se rilegge il gradino di prima; un valore diverso = non si sa: niente minimo salvato
                    blocked = same(r, lastOk);
                    log("· temperatura giù un grado alla volta: " + n + " → riletto " + r
                            + (blocked ? ", l'auto si ferma a " + lastOk : ", risposta non chiara: minimo non salvato"));
                    if (!blocked) {
                        SystemClock.sleep(600);
                        return;
                    }
                    break;
                }
                cur = n;
                lastOk = n;
            }
            if (blocked) {
                calMinNum = lastOk;
                log("· temperatura LO NASCOSTO: minimo numerico misurato " + lastOk + " (la schermata non scende oltre)");
                SystemClock.sleep(600);
                return;
            }
            log("· temperatura giù un grado alla volta fino a " + cur + " ✓");
            descended = cur;
        }
        if (car) {   // caradapter: si scende, ci si ferma al primo rifiutato
            float okSend = Float.NaN, okRead = descended > 0 ? descended : Float.NaN;   // 1.4.6: si parte dal gradino raggiunto
            boolean minProved = false;   // l'auto ha "bloccato" il valore più basso su quello accettato: è il suo minimo
            boolean all = true;
            for (float t : tries) {
                send("temperatura LO prova " + t, HvacClient.SET_DRV_TEMP_RAW, Math.round(t * 10));
                final float tt = t;
                float r = waitRaw(x -> same((float) x, tt) || (tt < 16 && x <= tt + 0.01 && x >= 10));
                boolean good = same(r, t) || (t < 16 && r <= t + 0.01f && r >= 10);
                tried.append(tried.length() > 0 ? ", " : "").append(t).append(" → ").append(r);
                log("· prova LO = " + t + " → riletto " + r + (good ? " ✓" : r >= 29 ? " (letto come HI) ✗" : " ✗"));
                if (!good) {
                    all = false;
                    minProved = !Float.isNaN(okRead) && same(r, okRead);
                    break;
                }
                okSend = t;
                okRead = r;
                SystemClock.sleep(500);
            }
            // LO vero: un valore sotto 16 (15,5 / 15, il LO delle auto UE), oppure l'ultimo accettato se l'auto si è
            // fermata lì. Se invece il valore dopo è "saltato" altrove (15,5 → 31) LO resta nascosto, minimo numerico.
            if (!Float.isNaN(okSend) && (okRead < 16 || minProved || all)) {
                calLoSend = okSend;
                calLoRead = okRead;
            } else if (Float.isNaN(okSend) && minProved && descended > 0) {
                calMinNum = descended;   // 1.4.6: già il primo LO rifiutato e l'auto resta sul gradino: minimo vero
                log("· temperatura LO NASCOSTO: l'auto resta a " + descended + " (minimo numerico misurato)");
            }
        } else {   // SAIC: si sale, il primo che l'auto rilegge uguale è LO
            for (float t : tries) {
                send("temperatura LO prova " + (int) t, HvacClient.SET_DRV_TEMP, (int) t);
                final float tt = t;
                float r = waitRaw(x -> same((float) x, tt));
                tried.append(tried.length() > 0 ? ", " : "").append((int) t).append(" → ").append((int) r);
                log("· prova LO = " + (int) t + " → riletto " + (int) r + (same(r, t) ? " ✓" : " ✗"));
                if (same(r, t)) {
                    calLoSend = calLoRead = t;
                    break;
                }
                SystemClock.sleep(500);
            }
        }
        if (!Float.isNaN(calLoSend)) {
            check("temperatura LO", true, "LO dell'auto " + calLoSend + " (riletto " + calLoRead + ") · provati: " + tried);
        } else if (car) {
            log("· temperatura LO NASCOSTO: nessun valore riletto come LO (provati: " + tried
                    + ") · temperatura fino al minimo numerico, la sostituzione resta possibile");
        } else {
            check("temperatura LO", false, "nessun valore accettato (provati: " + tried + ")");
        }
        SystemClock.sleep(600);
    }

    /**
     * HI: si sale un grado alla volta (le app EH32_SOP non accettano i salti) fino al massimo numerico che l'auto
     * accetta, poi si provano i valori di HI. Se l'auto si ferma prima (DBI68 a 31, varianti 16–28) quel valore è HI.
     */
    private void calibrateHigh(boolean car, int hi) {
        int top = car ? 30 : Math.max(hi, 33);
        int cur = Math.round(raw(safe()));
        int last = cur;
        boolean clamped = true;   // l'auto si è fermata sull'ultimo valore accettato (non è "saltata" altrove)
        while (cur < top) {
            int next = cur + 1;
            send("temperatura " + next + " (un grado alla volta)", HvacClient.SET_DRV_TEMP, next);
            final int n = next;
            float r = waitRaw(x -> same((float) x, n));
            if (!same(r, n)) {
                clamped = same(r, last);
                log("· temperatura " + n + " (un grado alla volta) → letto " + r
                        + (clamped ? ": l'auto si ferma a " + last : ": valore inatteso"));
                break;
            }
            last = cur = n;
            SystemClock.sleep(300);
        }
        StringBuilder tried = new StringBuilder();
        if (car && last >= 30) {   // HI della SWI69/SWI132: 30,5 / 31 / 32 / 33, ci si ferma al primo intero rifiutato
            float okSend = Float.NaN, okRead = Float.NaN;
            boolean maxProved = false, stopped = false;
            for (float t : CarAdapterBackend.HI_TRY) {
                send("temperatura HI prova " + t, HvacClient.SET_DRV_TEMP_RAW, Math.round(t * 10));
                final float tt = t;
                float r = waitRaw(x -> same((float) x, tt) || (tt < 31 && x >= tt - 0.01 && x <= 34));
                boolean good = same(r, t) || (t < 31 && r >= t - 0.01f && r <= 34);
                tried.append(tried.length() > 0 ? ", " : "").append(t).append(" → ").append(r);
                log("· prova HI = " + t + " → riletto " + r + (good ? " ✓" : " ✗"));
                if (good) {
                    okSend = t;
                    okRead = r;
                } else if (t >= 31) {   // 30,5 può non andare e 31 sì (SWI69 R33)
                    maxProved = !Float.isNaN(okRead) && same(r, okRead);
                    stopped = true;
                    break;
                }
                SystemClock.sleep(500);
            }
            // HI vero: 30,5 (HI delle auto UE), l'ultimo della lista (33), o l'ultimo accettato se l'auto si è
            // fermata lì. Se il valore dopo è "saltato" altrove, HI resta nascosto.
            if (!Float.isNaN(okSend) && (okSend < 31 || maxProved || !stopped)) {
                calHiSend = okSend;
                calHiRead = okRead;
            }
        } else if (!car && last >= 28 && clamped) {   // SAIC: HI = il valore più alto accettato
            calHiSend = calHiRead = last;
            tried.append("salito fino a ").append(last);
        } else if (car && last >= 26 && clamped) {   // variante con temperatura più bassa (es. 16–28)
            calHiSend = calHiRead = last;
            tried.append("salito fino a ").append(last);
            log("· l'auto non accetta più di " + last + "°: intervallo più piccolo (variante 16–28?)");
        }
        if (!Float.isNaN(calHiSend)) {
            boolean exp = car || (int) calHiRead == hi;
            check("temperatura HI", true, "HI dell'auto " + calHiSend + " (riletto " + calHiRead + ")"
                    + (exp ? "" : " · l'auto dichiarava " + hi) + " · " + tried);
        } else if (car) {
            log("· temperatura HI NASCOSTO: nessun valore riletto come HI (provati: " + tried
                    + ") · temperatura fino al massimo numerico, la sostituzione resta possibile");
        } else {
            check("temperatura HI", false, "l'auto si ferma a " + last);
        }
        SystemClock.sleep(600);
    }

    /** Salva l'intervallo misurato e lo fa usare subito al backend. */
    private void saveRange(boolean car) {
        Store.setTempRange(ctx, version(), calLoSend, calLoRead, calHiSend, calHiRead, calFanMax);
        if (car) Store.setMinNumeric(ctx, version(), Float.isNaN(calLoSend) ? calMinNum : 0);   // 1.4.6
        if (car) CarAdapterBackend.loadLimits(ctx);
        else SaicBackend.loadLimits(ctx);
        log("· clima.intervallo = " + (car ? CarAdapterBackend.rangeText() : SaicBackend.rangeText()));
    }

    private String run() {
        HvacClient.State s0 = safe();
        if (s0.acAvailable != 1) throw new Abort("clima non disponibile (" + s0.acAvailable + ")");
        log("INIZIO · app clima " + ClimaGuard.originalVersion(ctx) + "\n" + s0.dump());
        if (power0 < 0) power0 = s0.power == 1 ? 1 : 0;   // alla ripresa: com'era la prima volta

        if (s0.power != 1) {
            send("accensione clima", HvacClient.SET_POWER_PRESS, 1);
            int p = waitFor(s -> s.power, x -> x == 1);
            if (!done.contains("accensione")) check("accensione clima", p == 1, "letto " + p);
            if (p != 1) throw new Abort("il clima non si accende: niente altri comandi");
            poweredOn = true;
            SystemClock.sleep(1500);
        }
        if (!done.contains("accensione")) did("accensione");
        poweredOn = power0 == 0;   // era spento: alla fine si rispegne
        HvacClient.State s1 = safe();
        // valori di partenza: alla ripresa restano quelli della prima volta
        if (t0 < 0) t0 = s1.temp;
        if (f0 < 0) f0 = s1.fan;
        if (m0 < 0) m0 = s1.blowerMode;
        if (l0 < 0) l0 = s1.loop;
        if (auto0 < 0) auto0 = s1.auto;
        save();
        int lo = s1.tempMin, hi = s1.tempMax;
        int mid = t0 >= lo + 2 && t0 <= hi - 4 ? t0 : 22;

        // temperatura: +2, LO, poi (1.3.3) di nuovo a metà e fino a HI un grado alla volta: da LO a 32 diretto le
        // app EH32_SOP non lo accettano (SWI68 R63/R69, mercato turco), dalla metà sì
        if (todo("temperatura")) {
            boolean car = s1.caradapter;
            int t1 = mid + 2;
            setValue("temperatura", HvacClient.SET_DRV_TEMP, t1, s -> s.temp);
            calibrateLow(car, lo, mid);   // 1.4.2: LO reale dell'auto
            setTempBack("temperatura (dopo LO)", car, mid);   // 1.4.6: a gradini sulle SWI69/SWI132
            calibrateHigh(car, hi);       // 1.4.2: HI reale dell'auto (un grado alla volta)
            saveRange(car);
            HvacClient.State s2 = safe();
            if (t0 >= s2.tempMin && t0 <= s2.tempMax) setTempBack("temperatura (ripristino)", car, t0);
            else setTempBack("temperatura (ripristino)", car, mid);
            did("temperatura");
        }

        if (todo("ventola")) {
            setValue("ventola", HvacClient.SET_AIR_VOLUME, 3, s -> s.fan);
            setValue("ventola", HvacClient.SET_AIR_VOLUME, 6, s -> s.fan);
            if (s1.caradapter) {   // 1.4.2: ventola fino a 11 o fino a 8 (variante)?
                send("ventola = 11 (massimo)", HvacClient.SET_AIR_VOLUME, 11);
                int v = waitFor(s -> s.fan, x -> x == 11);
                if (v == 11) log("· ventola massima 11 ✓");
                else if (v >= 6 && v <= 10) {
                    calFanMax = v;
                    log("· ventola massima: l'auto si ferma a " + v + " (auto con ventola 1–" + v + ")");
                } else log("· ventola 11 → letto " + v + ": massimo non misurato");
                float[] r0 = Store.tempRange(ctx, version());   // alla ripresa la temperatura è già misurata
                if (Float.isNaN(calLoSend) && Float.isNaN(calHiSend) && r0 != null)
                    Store.setTempRange(ctx, version(), r0[0], r0[1], r0[2], r0[3], calFanMax);
                else Store.setTempRange(ctx, version(), calLoSend, calLoRead, calHiSend, calHiRead, calFanMax);
                CarAdapterBackend.loadLimits(ctx);
                SystemClock.sleep(600);
            }
            did("ventola");
        }

        // direzione dell'aria (senza le combinazioni col parabrezza, che possono attivare l'A/C)
        if (todo("direzione")) {
            setValue("direzione viso", HvacClient.SET_BLOWER_MODE, 0, s -> s.blowerMode);
            setValue("direzione viso+piedi", HvacClient.SET_BLOWER_MODE, 1, s -> s.blowerMode);
            setValue("direzione piedi", HvacClient.SET_BLOWER_MODE, 2, s -> s.blowerMode);
            if (m0 >= 0 && m0 <= 6) setValue("direzione (ripristino)", HvacClient.SET_BLOWER_MODE, m0, s -> s.blowerMode);
            did("direzione");
        }

        // tasti
        if (todo("A/C")) {
            toggle("A/C", HvacClient.SET_AC_PRESS, s -> s.ac);
            did("A/C");
        }
        if (todo("ECO")) {
            toggle("ECO", HvacClient.SET_ECON_PRESS, s -> s.econ);
            did("ECO");
        }
        if (todo("ricircolo")) {
            if (tryPart("ricircolo")) {
                int fb = fails.size();
                // ripristino diretto: 0 = ricircolo interno, 1 = aria esterna (2 = automatico: solo pressioni)
                cycle("ricircolo", HvacClient.SET_LOOP_PRESS, s -> s.loop, 3, -1,
                        new int[]{HvacClient.LOOP_INNER, HvacClient.LOOP_OUTSIDE, -1});
                partDone("ricircolo", fb);
            }
            did("ricircolo");
        }
        // AUTO non si spegne ripremendolo: si esce cambiando la ventola (ripristino alla fine)
        if (todo("AUTO")) {
            send("AUTO (premuto)", HvacClient.SET_AUTO_PRESS, 1);
            int au = waitFor(s -> s.auto, x -> x == 1);
            check("AUTO premuto", au == 1, "letto " + au);
            SystemClock.sleep(600);
            did("AUTO");
        }
        if (s1.hasSeatHeat() && todo("sedili")) {
            if (tryPart("sedili")) {
                int fb = fails.size();
                cycle("sedile guida", HvacClient.SET_DRV_SEAT_PRESS, s -> s.seatDrv, 4, HvacClient.DRV_SEAT_OFF, null);
                cycle("sedile passeggero", HvacClient.SET_PSG_SEAT_PRESS, s -> s.seatPsg, 4, HvacClient.PSG_SEAT_OFF, null);
                partDone("sedili", fb);
            }
            did("sedili");
        }
        if (s1.hasWheel && todo("volante")) {
            if (tryPart("volante")) {
                int fb = fails.size();
                toggle("volante", HvacClient.SET_WHEEL_PRESS, s -> s.wheel);
                partDone("volante", fb);
            }
            did("volante");
        }

        // ripristino della ventola (se non era in automatico)
        HvacClient.State s2 = safe();
        if (f0 >= 1 && f0 <= 11 && s2.fan != f0) setValue("ventola (ripristino)", HvacClient.SET_AIR_VOLUME, f0, s -> s.fan);
        if (m0 >= 0 && m0 <= 6 && safe().blowerMode != m0)
            setValue("direzione (ripristino dopo AUTO)", HvacClient.SET_BLOWER_MODE, m0, s -> s.blowerMode);
        if (auto0 == 1 && safe().auto != 1) {
            send("AUTO (ripristino)", HvacClient.SET_AUTO_PRESS, 1);
            waitFor(s -> s.auto, x -> x == 1);
        }
        // 1.3.1: AUTO porta il ricircolo in automatico (1 → 2) e uscendo da AUTO resta così: si rimette com'era
        // premendo il tasto finché torna al valore di partenza (al massimo 3 pressioni). Non conta nel risultato.
        if (auto0 != 1 && l0 >= 0 && safe().loop != l0) {
            int lv = safe().loop;
            StringBuilder seq = new StringBuilder().append(lv);
            for (int i = 0; i < 3 && lv != l0; i++) {
                SystemClock.sleep(600);
                send("ricircolo (ripristino " + (i + 1) + ")", HvacClient.SET_LOOP_PRESS, 1);
                final int before = lv;
                lv = waitFor(s -> s.loop, x -> x != before);
                seq.append(" → ").append(lv);
                if (lv == before) break;
            }
            log((lv == l0 ? "✓" : "✗") + " ricircolo (ripristino) " + seq + (lv == l0 ? "" : " (non è tornato a " + l0 + ")"));
        }
        if (poweredOn) {
            SystemClock.sleep(800);
            send("spegnimento clima (era spento)", HvacClient.SET_POWER_PRESS, 1);
            int p = waitFor(s -> s.power, x -> x == 0);
            check("spegnimento clima", p != 1, "letto " + p);
        }

        String diff = HvacClient.diff(s0, safe());
        String r = ok + " ok, " + bad + " non riusciti" + (fails.isEmpty() ? "" : " (" + String.join(", ", fails) + ")")
                + (diff.isEmpty() ? " · tutto rimesso com'era" : " · differenze finali: " + diff);
        log("FINE · " + r);
        return r;
    }
}
