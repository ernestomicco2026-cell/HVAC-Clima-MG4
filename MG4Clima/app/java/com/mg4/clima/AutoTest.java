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
    private int ok, bad, step;
    private final List<String> fails = new ArrayList<>();

    private AutoTest(android.content.Context c, HvacClient h, Progress p) {
        ctx = c;
        this.h = h;
        progress = p;
    }

    /** Avvia il test su un thread a parte; done riceve il riassunto. */
    public static void start(android.content.Context c, HvacClient h, Progress p, java.util.function.Consumer<String> done) {
        new Thread(() -> {
            AutoTest t = new AutoTest(c, h, p);
            String r;
            h.testing = true;
            try {
                r = t.run();
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
        if (!Float.isNaN(s.speed) && s.speed >= 1f) throw new Abort("auto in movimento (" + s.speed + " km/h)");
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

    private String run() {
        HvacClient.State s0 = safe();
        if (s0.acAvailable != 1) throw new Abort("clima non disponibile (" + s0.acAvailable + ")");
        log("INIZIO · app clima " + ClimaGuard.originalVersion(ctx) + "\n" + s0.dump());

        boolean poweredOn = false;
        if (s0.power != 1) {
            send("accensione clima", HvacClient.SET_POWER_PRESS, 1);
            int p = waitFor(s -> s.power, x -> x == 1);
            check("accensione clima", p == 1, "letto " + p);
            if (p != 1) throw new Abort("il clima non si accende: niente altri comandi");
            poweredOn = true;
            SystemClock.sleep(1500);
        }
        HvacClient.State s1 = safe();

        // temperatura: +2 (o -2), LO, HI, poi com'era
        int t0 = s1.temp;
        int lo = s1.tempMin, hi = s1.tempMax;
        int t1 = t0 > lo && t0 < hi - 2 ? t0 + 2 : 22;
        setValue("temperatura", HvacClient.SET_DRV_TEMP, t1, s -> s.temp);
        setValue("temperatura LO", HvacClient.SET_DRV_TEMP, lo, s -> s.temp);
        // HI: come l'app originale, un grado alla volta (da LO a HI diretto l'auto non lo accetta)
        setValue("temperatura " + (hi - 1), HvacClient.SET_DRV_TEMP, hi - 1, s -> s.temp);
        setValue("temperatura HI", HvacClient.SET_DRV_TEMP, hi, s -> s.temp);
        if (t0 >= lo && t0 <= hi) setValue("temperatura (ripristino)", HvacClient.SET_DRV_TEMP, t0, s -> s.temp);

        // ventola
        int f0 = s1.fan;
        setValue("ventola", HvacClient.SET_AIR_VOLUME, 3, s -> s.fan);
        setValue("ventola", HvacClient.SET_AIR_VOLUME, 6, s -> s.fan);

        // direzione dell'aria (senza le combinazioni col parabrezza, che possono attivare l'A/C)
        int m0 = s1.blowerMode;
        setValue("direzione viso", HvacClient.SET_BLOWER_MODE, 0, s -> s.blowerMode);
        setValue("direzione viso+piedi", HvacClient.SET_BLOWER_MODE, 1, s -> s.blowerMode);
        setValue("direzione piedi", HvacClient.SET_BLOWER_MODE, 2, s -> s.blowerMode);
        if (m0 >= 0 && m0 <= 6) setValue("direzione (ripristino)", HvacClient.SET_BLOWER_MODE, m0, s -> s.blowerMode);

        // tasti
        toggle("A/C", HvacClient.SET_AC_PRESS, s -> s.ac);
        toggle("ECO", HvacClient.SET_ECON_PRESS, s -> s.econ);
        cycle("ricircolo", HvacClient.SET_LOOP_PRESS, s -> s.loop, 3);
        // AUTO non si spegne ripremendolo: si esce cambiando la ventola (ripristino alla fine)
        send("AUTO (premuto)", HvacClient.SET_AUTO_PRESS, 1);
        int au = waitFor(s -> s.auto, x -> x == 1);
        check("AUTO premuto", au == 1, "letto " + au);
        SystemClock.sleep(600);
        if (s1.hasSeatHeat()) {
            cycle("sedile guida", HvacClient.SET_DRV_SEAT_PRESS, s -> s.seatDrv, 4);
            cycle("sedile passeggero", HvacClient.SET_PSG_SEAT_PRESS, s -> s.seatPsg, 4);
        }
        if (s1.hasWheel) toggle("volante", HvacClient.SET_WHEEL_PRESS, s -> s.wheel);

        // ripristino della ventola (se non era in automatico)
        HvacClient.State s2 = safe();
        if (f0 >= 1 && f0 <= 11 && s2.fan != f0) setValue("ventola (ripristino)", HvacClient.SET_AIR_VOLUME, f0, s -> s.fan);
        if (m0 >= 0 && m0 <= 6 && safe().blowerMode != m0)
            setValue("direzione (ripristino dopo AUTO)", HvacClient.SET_BLOWER_MODE, m0, s -> s.blowerMode);
        if (s1.auto == 1 && safe().auto != 1) {
            send("AUTO (ripristino)", HvacClient.SET_AUTO_PRESS, 1);
            waitFor(s -> s.auto, x -> x == 1);
        }
        // 1.3.1: AUTO porta il ricircolo in automatico (1 → 2) e uscendo da AUTO resta così: si rimette com'era
        // premendo il tasto finché torna al valore di partenza (al massimo 3 pressioni). Non conta nel risultato.
        int l0 = s1.loop;
        if (s1.auto != 1 && l0 >= 0 && safe().loop != l0) {
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
