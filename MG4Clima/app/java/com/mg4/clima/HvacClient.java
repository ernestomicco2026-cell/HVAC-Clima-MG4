package com.mg4.clima;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * Lettura e comandi del clima, uguali per tutti i modelli. Il collegamento all'auto lo fa il {@link Backend} del modello:
 * <ul>
 *   <li>{@link SaicBackend}: SWI68 e SWI133 (servizio veicolo SAIC, IAirConditionService);</li>
 *   <li>{@link CarAdapterBackend}: SWI69 e SWI132 (servizio caradapter, ICarHvacService).</li>
 * </ul>
 * I comandi qui sono "logici" (accensione, temperatura, …): ogni backend li traduce nei codici del suo servizio.
 */
public final class HvacClient {

    // comandi logici ("PRESS" = come premere il tasto dell'app originale)
    public static final int SET_POWER_PRESS = 1, SET_AUTO_PRESS = 2, SET_AC_PRESS = 3, SET_LOOP_PRESS = 4,
            SET_ECON_PRESS = 5, SET_BLOWER_MODE = 6, SET_AIR_VOLUME = 7, SET_DRV_SEAT_PRESS = 8,
            SET_PSG_SEAT_PRESS = 9, SET_DRV_TEMP = 10, SET_WHEEL_PRESS = 11,
            FRONT_DEFROST_OPEN = 12, FRONT_DEFROST_CLOSE = 13, REAR_DEFROST_OPEN = 14, REAR_DEFROST_CLOSE = 15;

    /** Ventola in automatico (come le app clima originali). */
    public static final int FAN_AUTO = 15;

    /** Collegamento al servizio clima di un modello. */
    interface Backend {
        /** Collega il servizio; onConnected si chiama a collegamento avvenuto. */
        void bind(Runnable onConnected);

        void unbind();

        /** Legge tutto lo stato (thread del clima). */
        void read(State s) throws Exception;

        /** 1 acceso, 0 spento, -1 non leggibile. */
        int readPower();

        void send(int cmd, Integer arg) throws Exception;

        /** Stato vuoto con i limiti del modello. */
        State empty();

        /** Avviso immediato a ogni cambio del clima (se il servizio lo permette); null per toglierlo. */
        default void setOnChange(Runnable r) {
        }
    }

    /** Stato letto dall'auto (-1 = non letto). */
    public static final class State {
        public boolean connected;
        /** Servizio caradapter (SWI69/SWI132): il clima risponde solo ad auto accesa, niente accensione da leggere. */
        public boolean caradapter;
        public int acAvailable = -1, ignition = -1, seatConfig = -1;
        public int power = -1, temp = -1, fan = -1, fanMin = 1, fanMax = 12;
        public int auto = -1, ac = -1, econ = -1, blowerMode = -1, loop = -1;
        public int frontDefrost = -1, rearDefrost = -1, wheel = -1, seatDrv = -1, seatPsg = -1;
        public float outTemp = Float.NaN, speed = Float.NaN;
        /** Temperatura: valore minimo = "LO", massimo = "HI". */
        public int tempMin = 17, tempMax = 33;
        public boolean hasWheel = true, hasSeats = true;
        public String model = "", error = "";

        /** Il clima si può usare. */
        public boolean usable() {
            if (caradapter) return connected && power >= 0;
            return connected && acAvailable == 1 && (ignition == 2 || ignition == 3);
        }

        public boolean on() {
            return usable() && power == 1;
        }

        /** Direzioni dell'aria dal codice 0..6 dell'originale: {vetro, viso, piedi}. */
        public boolean[] directions() {
            switch (blowerMode) {
                case 0: return new boolean[]{false, true, false};
                case 1: return new boolean[]{false, true, true};
                case 2: return new boolean[]{false, false, true};
                case 3: return new boolean[]{true, false, true};
                case 4: return new boolean[]{true, false, false};
                case 5: return new boolean[]{true, true, true};
                case 6: return new boolean[]{true, true, false};
                default: return new boolean[]{false, false, false};
            }
        }

        /** Sedili riscaldati presenti (l'originale SWI68 li nasconde con configurazione 2). */
        public boolean hasSeatHeat() {
            return hasSeats && seatConfig != 2;
        }

        public String dump() {
            return model + " · collegato " + connected + (caradapter ? " · servizio caradapter"
                    : " · clima disponibile " + acAvailable + " · accensione " + ignition)
                    + "\nacceso " + power + " · temperatura " + temp + " (" + tempMin + "=LO, " + tempMax + "=HI)"
                    + " · ventola " + fan + " (" + fanMin + "–" + fanMax + ")"
                    + "\nAUTO " + auto + " · A/C " + ac + " · ECO " + econ + " · direzione " + blowerMode + " · ricircolo " + loop
                    + "\nparabrezza " + frontDefrost + " · lunotto " + rearDefrost
                    + (hasWheel ? " · volante " + wheel : "")
                    + (hasSeats ? "\nsedili " + seatDrv + " / " + seatPsg + " (configurazione " + seatConfig + ")" : "")
                    + " · esterna " + outTemp + " · velocità " + speed
                    + (error.isEmpty() ? "" : "\nerrore: " + error);
        }
    }

    /** Cambi rispetto alla lettura precedente, es. "temperatura 22→20, ventola 0→3" ("" se niente). */
    static String diff(State a, State b) {
        if (a == null || !a.connected || !b.connected) return "";
        StringBuilder sb = new StringBuilder();
        d(sb, "accensione auto", a.ignition, b.ignition);
        d(sb, "clima acceso", a.power, b.power);
        d(sb, "temperatura", a.temp, b.temp);
        d(sb, "ventola", a.fan, b.fan);
        d(sb, "AUTO", a.auto, b.auto);
        d(sb, "A/C", a.ac, b.ac);
        d(sb, "ECO", a.econ, b.econ);
        d(sb, "direzione", a.blowerMode, b.blowerMode);
        d(sb, "ricircolo", a.loop, b.loop);
        d(sb, "parabrezza", a.frontDefrost, b.frontDefrost);
        d(sb, "lunotto", a.rearDefrost, b.rearDefrost);
        d(sb, "volante", a.wheel, b.wheel);
        d(sb, "sedile guida", a.seatDrv, b.seatDrv);
        d(sb, "sedile passeggero", a.seatPsg, b.seatPsg);
        return sb.toString();
    }

    private static void d(StringBuilder sb, String name, int x, int y) {
        if (x == y) return;
        sb.append(sb.length() == 0 ? "" : ", ").append(name).append(' ').append(x).append('→').append(y);
    }

    public interface Listener {
        void onState(State s);
    }

    public interface Result {
        void done(String error);   // null = comando mandato
    }

    private final Context ctx;
    private final Backend backend;
    private final String model;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private Listener listener;
    private boolean running;
    private State prev;
    /** Durante il test automatico i cambi non si registrano come "cambiato a mano". */
    volatile boolean testing;

    public HvacClient(Context c) {
        ctx = c.getApplicationContext();
        model = Model.get(ctx);
        backend = Model.saic(ctx) ? new SaicBackend(ctx, Model.SWI133.equals(model)) : new CarAdapterBackend(ctx);
    }

    private void begin(String threadName, boolean poll) {
        running = true;
        thread = new HandlerThread(threadName);
        thread.start();
        bg = new Handler(thread.getLooper());
        backend.bind(() -> {
            Handler b = bg;
            if (listener != null && b != null) b.post(this::read);
        });
        if (poll) bg.post(tick);
    }

    public void start(Listener l) {
        listener = l;
        if (running) return;
        begin("mg4-clima", true);
    }

    /** Solo collegamento al servizio, senza letture periodiche (per ClimaGuard: legge solo l'accensione). */
    public void startLight() {
        if (running) return;
        begin("mg4-clima-light", false);
    }

    public void stop() {
        running = false;
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        backend.unbind();
    }

    public boolean running() {
        return running;
    }

    /** Avviso immediato a ogni cambio del clima (SWI68/SWI133: ascoltatore del servizio clima, come il SystemUI). */
    public void setOnChange(Runnable r) {
        backend.setOnChange(r);
    }

    /** Clima acceso (1), spento (0), -1 se non leggibile. */
    public int readPower() {
        return backend.readPower();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            read();
            if (bg != null) bg.postDelayed(this, 1000);
        }
    };

    /** Manda un comando (thread del clima o test automatico). arg null = senza argomenti. */
    public void send(int cmd, Integer arg) throws Exception {
        backend.send(cmd, arg);
    }

    private void read() {
        State s = readNow();
        String ch = diff(prev, s);
        if (!ch.isEmpty()) Store.event(ctx, (testing ? "  · letto: " : "cambiato: ") + ch);
        prev = s;
        final State st = s;
        Store.diag(ctx, st.dump());
        main.post(() -> {
            if (listener != null) listener.onState(st);
        });
    }

    /** Comando dalla schermata: sul thread del clima, poi rilettura veloce dello stato. */
    public void command(String what, int code, Integer arg, Result r) {
        Handler b = bg;
        if (b == null) return;
        b.post(() -> {
            String err = null;
            try {
                send(code, arg);
                Store.event(ctx, "comando: " + what + (arg == null ? "" : " (" + arg + ")"));
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                Store.event(ctx, "comando non riuscito: " + what + " · " + err);
            }
            b.postDelayed(this::read, 250);
            b.postDelayed(this::read, 700);
            final String fe = err;
            if (r != null) main.post(() -> r.done(fe));
        });
    }

    /** Legge subito tutto lo stato (da qualsiasi thread). */
    public State readNow() {
        State s = backend.empty();
        s.model = model;
        try {
            backend.read(s);
        } catch (Throwable e) {
            s.error = e.toString();
        }
        return s;
    }
}
