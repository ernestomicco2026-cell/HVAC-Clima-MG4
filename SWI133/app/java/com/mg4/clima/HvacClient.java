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

import java.util.HashMap;
import java.util.Map;

/**
 * SWI133: stesso servizio e stessi codici di IAirConditionService della SWI68 (verificati su vehiclesettingservice_my24_P);
 * cambia solo il codice di "clima disponibile" (45).
 * <p>
 * Lettura dello stato del clima dal servizio veicolo SAIC (com.saicmotor.service.vehicle), lo stesso usato dall'app
 * clima originale SWI68 (com.saicmotor.hmi.hvac) e dal launcher MG4.
 * <p>
 * Comandi ({@link #command}) come l'app clima originale (codici identici su R46, R59 e R69), verificati sull'auto con il
 * test automatico della 0.2.0.
 */
public final class HvacClient {
    private static final String TAG = "MG4Clima";
    private static final String PKG = "com.saicmotor.service.vehicle";
    private static final String ACTION = "com.saicmotor.service.vehicle.VehicleService";
    private static final String HUB = "com.saicmotor.sdk.vehiclesettings.IHubService";
    private static final int HUB_GET_SERVICE = 1;

    // IAirConditionService (SWI68 R46/R59/R69): solo lettura
    private static final String AC = "com.saicmotor.sdk.vehiclesettings.IAirConditionService";
    private static final int GET_POWER = 53, GET_AUTO = 54, GET_AC = 55, GET_LOOP = 56, GET_ECON = 57,
            GET_BLOWER_MODE = 58, GET_AIR_VOLUME = 59, GET_MAX_AIR = 60, GET_MIN_AIR = 61,
            GET_DRV_SEAT = 77, GET_PSG_SEAT = 79, GET_DRV_TEMP = 81, GET_OUT_TEMP = 88,
            GET_FRONT_DEFROST = 92, GET_REAR_DEFROST = 93, GET_WHEEL_HEAT = 94;

    // IVehicleConditionService: disponibilità del clima, accensione, configurazione
    private static final String COND = "com.saicmotor.sdk.vehiclesettings.IVehicleConditionService";
    private static final int COND_SPEED = 3, COND_IGNITION = 4, COND_SEAT_HEAT_CONFIG = 17, COND_AC_AVAILABLE = 45;   // SWI133 (vehiclesettingservice_my24_P): 45, sulla SWI68 49

    // comandi (come l'app originale: quelli con "1" sono la pressione del tasto, non un valore)
    public static final int SET_POWER_PRESS = 4, SET_AUTO_PRESS = 7, SET_AC_PRESS = 8, SET_LOOP_PRESS = 9,
            SET_ECON_PRESS = 13, SET_BLOWER_MODE = 14, SET_AIR_VOLUME = 15, SET_DRV_SEAT_PRESS = 33,
            SET_PSG_SEAT_PRESS = 37, SET_DRV_TEMP = 41, SET_WHEEL_PRESS = 52,
            FRONT_DEFROST_OPEN = 48, FRONT_DEFROST_CLOSE = 49, REAR_DEFROST_OPEN = 50, REAR_DEFROST_CLOSE = 51;

    /** Ventola in automatico (come l'app clima originale). */
    public static final int FAN_AUTO = 15;
    /** Come l'app clima originale SWI68: 17 = LO, 33 = HI. */
    public static final int TEMP_MIN = 17, TEMP_MAX = 33;

    /** Stato letto dall'auto (valori come l'app originale; -1 = non letto). */
    public static final class State {
        public boolean connected;
        public int acAvailable = -1, ignition = -1, seatConfig = -1;
        public int power = -1, temp = -1, fan = -1, fanMin = 1, fanMax = 12;
        public int auto = -1, ac = -1, econ = -1, blowerMode = -1, loop = -1;
        public int frontDefrost = -1, rearDefrost = -1, wheel = -1, seatDrv = -1, seatPsg = -1;
        public float outTemp = Float.NaN, speed = Float.NaN;
        /** Temperatura: valore minimo = "LO", massimo = "HI" (SWI68: 17..33). Volante riscaldato presente. */
        public int tempMin = TEMP_MIN, tempMax = TEMP_MAX;
        public boolean hasWheel = true;
        public String error = "";

        /** Il clima si può usare (come l'app originale: clima disponibile e auto accesa). */
        public boolean usable() {
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

        /** Sedili riscaldati presenti (l'originale li nasconde con configurazione 2). */
        public boolean hasSeatHeat() {
            return seatConfig != 2;
        }

        public String dump() {
            return "collegato " + connected + " · clima disponibile " + acAvailable + " · accensione " + ignition
                    + "\nacceso " + power + " · temperatura " + temp + " · ventola " + fan + " (" + fanMin + "–" + fanMax + ")"
                    + "\nAUTO " + auto + " · A/C " + ac + " · ECO " + econ + " · direzione " + blowerMode + " · ricircolo " + loop
                    + "\nparabrezza " + frontDefrost + " · lunotto " + rearDefrost + " · volante " + wheel
                    + "\nsedili " + seatDrv + " / " + seatPsg + " (configurazione " + seatConfig + ")"
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

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private volatile IBinder hub;
    private final Map<String, IBinder> subs = new HashMap<>();
    private Listener listener;
    private boolean bound, running;
    private int fanMin = -1, fanMax = -1;
    private State prev;
    /** Durante il test automatico i cambi non si registrano come "cambiato a mano". */
    volatile boolean testing;

    public HvacClient(Context c) {
        ctx = c.getApplicationContext();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            hub = service;
            synchronized (subs) {
                subs.clear();
            }
            Log.i(TAG, "servizio veicolo collegato");
            if (listener != null) poll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hub = null;
            synchronized (subs) {
                subs.clear();
            }
            Log.w(TAG, "servizio veicolo scollegato");
        }
    };

    public void start(Listener l) {
        listener = l;
        if (running) return;
        running = true;
        thread = new HandlerThread("mg4-clima");
        thread.start();
        bg = new Handler(thread.getLooper());
        try {
            bound = ctx.bindService(new Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            bound = false;
            Log.e(TAG, "bind: " + e);
        }
        bg.post(tick);
    }

    /** Solo collegamento al servizio, senza letture periodiche (per ClimaGuard: legge solo l'accensione). */
    public void startLight() {
        if (running) return;
        running = true;
        thread = new HandlerThread("mg4-clima-light");
        thread.start();
        bg = new Handler(thread.getLooper());
        try {
            bound = ctx.bindService(new Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            bound = false;
        }
    }

    /** Clima acceso (1), spento (0), -1 se non leggibile. */
    public int readPower() {
        try {
            IBinder a = sub("aircondition");
            return a == null ? -1 : Rpc.callInt(a, AC, GET_POWER);
        } catch (Throwable e) {
            synchronized (subs) {
                subs.clear();
            }
            return -1;
        }
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
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            read();
            if (bg != null) bg.postDelayed(this, 1000);
        }
    };

    private void poll() {
        if (bg != null) bg.post(this::read);
    }

    private IBinder sub(String name) throws Exception {
        synchronized (subs) {
            IBinder b = subs.get(name);
            if (b != null && b.isBinderAlive()) return b;
        }
        IBinder h = hub;
        if (h == null) return null;
        IBinder b = Rpc.callBinder(h, HUB, HUB_GET_SERVICE, p -> p.writeString(name));
        if (b != null) {
            synchronized (subs) {
                subs.put(name, b);
            }
        }
        return b;
    }

    public boolean running() {
        return running;
    }

    /** Manda un comando al servizio clima (solo il test automatico). arg null = senza argomenti. */
    public void send(int code, Integer arg) throws Exception {
        IBinder a = sub("aircondition");
        if (a == null) throw new IllegalStateException("servizio clima non collegato");
        Rpc.call(a, AC, code, arg == null ? Rpc.NONE : p -> p.writeInt(arg));
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

    public interface Result {
        void done(String error);   // null = comando mandato
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

    /** Legge subito tutto lo stato (da qualsiasi thread). */
    public State readNow() {
        State s = new State();
        try {
            IBinder c = sub("vehiclecondition");
            IBinder a = sub("aircondition");
            if (a != null) {
                s.connected = true;
                if (c != null) {
                    s.acAvailable = Rpc.callInt(c, COND, COND_AC_AVAILABLE);
                    s.ignition = Rpc.callInt(c, COND, COND_IGNITION);
                    s.seatConfig = Rpc.callInt(c, COND, COND_SEAT_HEAT_CONFIG);
                    s.speed = Rpc.callFloat(c, COND, COND_SPEED);
                }
                if (fanMax < 0) {
                    int mn = Rpc.callInt(a, AC, GET_MIN_AIR), mx = Rpc.callInt(a, AC, GET_MAX_AIR);
                    if (mn >= 1 && mx > mn && mx < FAN_AUTO) {
                        fanMin = mn;
                        fanMax = mx;
                    } else {
                        fanMin = 1;
                        fanMax = 12;
                    }
                }
                s.fanMin = fanMin;
                s.fanMax = fanMax;
                s.power = Rpc.callInt(a, AC, GET_POWER);
                s.temp = Rpc.callInt(a, AC, GET_DRV_TEMP);
                s.fan = Rpc.callInt(a, AC, GET_AIR_VOLUME);
                s.auto = Rpc.callInt(a, AC, GET_AUTO);
                s.ac = Rpc.callInt(a, AC, GET_AC);
                s.econ = Rpc.callInt(a, AC, GET_ECON);
                s.blowerMode = Rpc.callInt(a, AC, GET_BLOWER_MODE);
                s.loop = Rpc.callInt(a, AC, GET_LOOP);
                s.frontDefrost = Rpc.callInt(a, AC, GET_FRONT_DEFROST);
                s.rearDefrost = Rpc.callInt(a, AC, GET_REAR_DEFROST);
                s.wheel = Rpc.callInt(a, AC, GET_WHEEL_HEAT);
                s.seatDrv = Rpc.callInt(a, AC, GET_DRV_SEAT);
                s.seatPsg = Rpc.callInt(a, AC, GET_PSG_SEAT);
                float out = Rpc.callFloat(a, AC, GET_OUT_TEMP);
                s.outTemp = out < -100 || out > 100 ? Float.NaN : out;   // -10000 = non disponibile
            }
        } catch (Throwable e) {
            s.error = e.toString();
            synchronized (subs) {
                subs.clear();
            }
        }
        return s;
    }
}
