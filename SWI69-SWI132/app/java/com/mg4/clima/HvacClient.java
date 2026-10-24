package com.mg4.clima;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Clima della SWI69 / SWI132: servizio "caradapter" (com.saicmotor.caradapter, ICarHvacService), lo stesso usato dall'app
 * clima originale (com.saicmotor.hvac, A9_Hvac) e dalla barra clima del launcher MG4 SWI69 (già verificata sulle auto).
 * <p>
 * I comandi sono quelli dell'app originale: accensione, A/C, AUTO, ECO, ricircolo e sbrinamenti sono "cambia stato"
 * (senza argomenti), temperatura, ventola e direzione dell'aria hanno un valore. Sedili e volante riscaldati non ci sono
 * sulle MG4 con SWI69 (vengono nascosti).
 */
public final class HvacClient {
    private static final String TAG = "MG4Clima";
    private static final String PKG = "com.saicmotor.caradapter";
    private static final String ACTION = "com.saicmotor.caradapter.CarAdapterService";
    private static final String HUB = "com.saicmotor.carapi.ICarAdapterService";
    private static final int HUB_QUERY_CLIENT = 1, BIND_HVAC = 7;

    private static final String AC = "com.saicmotor.carapi.hvac.ICarHvacService";
    private static final int GET_POWER = 6, GET_AC = 8, GET_AUTO = 10, GET_ECO = 14, GET_LOOP = 16, GET_DIRECTION = 18,
            GET_FAN = 20, GET_DRV_TEMP = 22, GET_OUT_TEMP = 25, GET_FRONT_DEFROST = 28, GET_REAR_DEFROST = 30;

    // comandi (codici del servizio caradapter); "PRESS" = cambia stato, senza argomenti
    public static final int SET_POWER_PRESS = 5, SET_AC_PRESS = 7, SET_AUTO_PRESS = 9, SET_ECON_PRESS = 13,
            SET_LOOP_PRESS = 15, SET_BLOWER_MODE = 17, SET_AIR_VOLUME = 19, SET_DRV_TEMP = 21,
            FRONT_DEFROST_OPEN = 27, FRONT_DEFROST_CLOSE = 27, REAR_DEFROST_OPEN = 29, REAR_DEFROST_CLOSE = 29,
            // non presenti sulle MG4 SWI69: mai usati (sedili e volante nascosti)
            SET_DRV_SEAT_PRESS = -1, SET_PSG_SEAT_PRESS = -2, SET_WHEEL_PRESS = -3;

    /** Ventola in automatico (come l'app clima originale). */
    public static final int FAN_AUTO = 15;
    /**
     * MG4 EU/UK su SWI69: 16..30 °C, l'auto usa 15,5 = LO e 30,5 = HI (come la barra clima del launcher SWI69).
     * Qui: 15 = LO, 31 = HI.
     */
    public static final int TEMP_MIN = 15, TEMP_MAX = 31;
    private static final float CAR_LO = 15.5f, CAR_HI = 30.5f;

    static int tempFromCar(float f) {
        if (Float.isNaN(f) || f <= 0) return -1;
        if (f <= CAR_LO + 0.01f) return TEMP_MIN;
        if (f >= CAR_HI - 0.01f) return TEMP_MAX;
        return Math.max(TEMP_MIN + 1, Math.min(TEMP_MAX - 1, Math.round(f)));
    }

    static float tempToCar(int v) {
        if (v <= TEMP_MIN) return CAR_LO;
        if (v >= TEMP_MAX) return CAR_HI;
        return v;
    }

    /** Stato letto dall'auto (stessi significati della SWI68; -1 = non letto). */
    public static final class State {
        public boolean connected;
        public int acAvailable = -1, ignition = -1, seatConfig = 2;
        public int power = -1, temp = -1, fan = -1, fanMin = 1, fanMax = 11;
        public int auto = -1, ac = -1, econ = -1, blowerMode = -1, loop = -1;
        public int frontDefrost = -1, rearDefrost = -1, wheel = -1, seatDrv = -1, seatPsg = -1;
        public float outTemp = Float.NaN, speed = Float.NaN;
        public int tempMin = TEMP_MIN, tempMax = TEMP_MAX;
        public boolean hasWheel = false;
        public String error = "";

        /** Il clima si può usare: servizio collegato (sulla SWI69 il clima risponde solo ad auto accesa). */
        public boolean usable() {
            return connected && power >= 0;
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

        public boolean hasSeatHeat() {
            return false;
        }

        public String dump() {
            return "collegato " + connected + " · servizio caradapter"
                    + "\nacceso " + power + " · temperatura " + temp + " (" + tempMin + "=LO, " + tempMax + "=HI) · ventola " + fan
                    + "\nAUTO " + auto + " · A/C " + ac + " · ECO " + econ + " · direzione " + blowerMode + " · ricircolo " + loop
                    + "\nparabrezza " + frontDefrost + " · lunotto " + rearDefrost
                    + " · esterna " + outTemp + " · velocità " + speed
                    + (error.isEmpty() ? "" : "\nerrore: " + error);
        }
    }

    /** Cambi rispetto alla lettura precedente, es. "temperatura 22→20, ventola 0→3" ("" se niente). */
    static String diff(State a, State b) {
        if (a == null || !a.connected || !b.connected) return "";
        StringBuilder sb = new StringBuilder();
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
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private volatile IBinder hub, hvac;
    private Listener listener;
    private boolean bound, running;
    private State prev;
    private final Speed speed;
    /** Durante il test automatico i cambi non si registrano come "cambiato a mano". */
    volatile boolean testing;

    public HvacClient(Context c) {
        ctx = c.getApplicationContext();
        speed = new Speed(ctx);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            hub = service;
            hvac = null;
            Log.i(TAG, "caradapter collegato");
            if (listener != null && bg != null) bg.post(HvacClient.this::read);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hub = null;
            hvac = null;
        }
    };

    private void bind(String threadName) {
        running = true;
        thread = new HandlerThread(threadName);
        thread.start();
        bg = new Handler(thread.getLooper());
        try {
            bound = ctx.bindService(new Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            bound = false;
            Log.e(TAG, "bind: " + e);
        }
    }

    public void start(Listener l) {
        listener = l;
        if (running) return;
        bind("mg4-clima");
        speed.connect();
        bg.post(tick);
    }

    /** Solo collegamento al servizio, senza letture periodiche (per ClimaGuard: legge solo l'accensione). */
    public void startLight() {
        if (running) return;
        bind("mg4-clima-light");
    }

    public void stop() {
        running = false;
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
        }
        bound = false;
        hub = null;
        hvac = null;
    }

    public boolean running() {
        return running;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            read();
            if (bg != null) bg.postDelayed(this, 1000);
        }
    };

    private IBinder ac() throws Exception {
        IBinder b = hvac;
        if (b != null && b.isBinderAlive()) return b;
        IBinder h = hub;
        if (h == null) return null;
        b = Rpc.callBinder(h, HUB, HUB_QUERY_CLIENT, p -> p.writeInt(BIND_HVAC));
        hvac = b;
        return b;
    }

    /** Clima acceso (1), spento (0), -1 se non leggibile. */
    public int readPower() {
        try {
            IBinder a = ac();
            return a == null ? -1 : Rpc.callBool(a, AC, GET_POWER) ? 1 : 0;
        } catch (Throwable e) {
            hvac = null;
            return -1;
        }
    }

    /** Manda un comando al servizio clima. */
    public void send(int code, Integer arg) throws Exception {
        if (code < 0) throw new IllegalStateException("comando non presente su questa auto");
        IBinder a = ac();
        if (a == null) throw new IllegalStateException("servizio clima non collegato");
        switch (code) {
            case SET_DRV_TEMP:
                Rpc.call(a, AC, code, p -> p.writeFloat(tempToCar(arg == null ? 22 : arg)));
                break;
            case SET_BLOWER_MODE:
            case SET_AIR_VOLUME:
                Rpc.call(a, AC, code, p -> p.writeInt(arg == null ? 0 : arg));
                break;
            default:
                Rpc.call(a, AC, code, Rpc.NONE);   // "cambia stato"
        }
    }

    /** Comando dalla schermata: sul thread del servizio, poi rilettura veloce dello stato. */
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

    /** Legge subito tutto lo stato (da qualsiasi thread). */
    public State readNow() {
        State s = new State();
        try {
            IBinder a = ac();
            if (a != null) {
                s.connected = true;
                s.acAvailable = 1;
                s.ignition = 2;   // la SWI69 non espone l'accensione qui: il clima risponde solo ad auto accesa
                boolean power = Rpc.callBool(a, AC, GET_POWER);
                s.power = power ? 1 : 0;
                s.temp = tempFromCar(Rpc.callFloat(a, AC, GET_DRV_TEMP));
                s.fan = Rpc.callInt(a, AC, GET_FAN);
                s.auto = Rpc.callBool(a, AC, GET_AUTO) ? 1 : 0;
                if (s.auto == 1 && power && s.fan == 0) s.fan = FAN_AUTO;
                s.ac = Rpc.callBool(a, AC, GET_AC) ? 1 : 0;
                s.econ = Rpc.callBool(a, AC, GET_ECO) ? 1 : 0;
                s.blowerMode = Rpc.callInt(a, AC, GET_DIRECTION);
                s.loop = Rpc.callInt(a, AC, GET_LOOP);
                s.frontDefrost = Rpc.callBool(a, AC, GET_FRONT_DEFROST) ? 1 : 0;
                s.rearDefrost = Rpc.callBool(a, AC, GET_REAR_DEFROST) ? 1 : 0;
                float out = Rpc.callFloat(a, AC, GET_OUT_TEMP);
                s.outTemp = out < -100 || out > 100 ? Float.NaN : out;
            }
        } catch (Throwable e) {
            s.error = e.toString();
            hvac = null;
        }
        s.speed = speed.kmh();
        return s;
    }
}
