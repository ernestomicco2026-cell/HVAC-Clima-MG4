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

    /** Avvia il test su un thread a parte; done riceve il riassunto. kind: "test" o "verifica" (per la ripresa). */
    public static void start(android.content.Context c, HvacClient h, Progress p, String kind,
                             java.util.function.Consumer<String> done) {
        new Thread(() -> {
            AutoTest t = new AutoTest(c, h, p, kind);
            String r;
            h.testing = true;
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

    /** Tasto acceso/spento: premuto due volte, deve cambiare e poi tornare com'era. */
    private void toggle(String what, int code, ToIntFunction<HvacClient.State> f) {
        int v0 = f.applyAsInt(safe());
        send(what + " (premuto)", code, 1);
        int v1 = waitFor(f, x -> x != v0);
        check(what + " premuto", v1 != v0, v0 + " → " + v1);
        SystemClock.sleep(800);
        send(what + " (di nuovo)", code, 1);
        int v2 = waitFor(f, x -> x == v0);
        check(what + " ripremuto", v2 == v0, v1 + " → " + v2);
        SystemClock.sleep(600);
    }

    /** Tasto a più livelli (sedili, ricircolo): premuto finché torna com'era, registra la sequenza. */
    private void cycle(String what, int code, ToIntFunction<HvacClient.State> f, int maxPress) {
        int v0 = f.applyAsInt(safe()), v = v0;
        StringBuilder seq = new StringBuilder().append(v0);
        boolean changed = false;
        for (int i = 0; i < maxPress; i++) {
            send(what + " (pressione " + (i + 1) + ")", code, 1);
            final int before = v;
            v = waitFor(f, x -> x != before);
            seq.append(" → ").append(v);
            if (v != before) changed = true;
            else break;   // non cambia: inutile insistere
            SystemClock.sleep(800);
            if (v == v0) break;
        }
        check(what + " a livelli", changed && v == v0, seq + (v == v0 ? "" : " (non è tornato a " + v0 + ")"));
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
            int t1 = mid + 2;
            setValue("temperatura", HvacClient.SET_DRV_TEMP, t1, s -> s.temp);
            setValue("temperatura LO", HvacClient.SET_DRV_TEMP, lo, s -> s.temp);
            setValue("temperatura (dopo LO)", HvacClient.SET_DRV_TEMP, mid, s -> s.temp);
            stepTemp(hi - 2);
            setValue("temperatura " + (hi - 1), HvacClient.SET_DRV_TEMP, hi - 1, s -> s.temp);
            setValue("temperatura HI", HvacClient.SET_DRV_TEMP, hi, s -> s.temp);
            if (t0 >= lo && t0 <= hi) setValue("temperatura (ripristino)", HvacClient.SET_DRV_TEMP, t0, s -> s.temp);
            did("temperatura");
        }

        if (todo("ventola")) {
            setValue("ventola", HvacClient.SET_AIR_VOLUME, 3, s -> s.fan);
            setValue("ventola", HvacClient.SET_AIR_VOLUME, 6, s -> s.fan);
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
            cycle("ricircolo", HvacClient.SET_LOOP_PRESS, s -> s.loop, 3);
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
            cycle("sedile guida", HvacClient.SET_DRV_SEAT_PRESS, s -> s.seatDrv, 4);
            cycle("sedile passeggero", HvacClient.SET_PSG_SEAT_PRESS, s -> s.seatPsg, 4);
            did("sedili");
        }
        if (s1.hasWheel && todo("volante")) {
            toggle("volante", HvacClient.SET_WHEEL_PRESS, s -> s.wheel);
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
