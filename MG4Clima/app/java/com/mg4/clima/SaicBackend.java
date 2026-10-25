package com.mg4.clima;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * SWI68 e SWI133: servizio veicolo SAIC (com.saicmotor.service.vehicle), lo stesso dell'app clima originale SWI68
 * (com.saicmotor.hmi.hvac) e del launcher. Codici di IAirConditionService identici su SWI68 R46, R59, R63, R69 e SWI133
 * (vehiclesettingservice_my24_P); cambia solo il codice di "clima disponibile" (SWI68 49, SWI133 45).
 * Comandi verificati sull'auto (SWI68) col test automatico.
 */
final class SaicBackend implements HvacClient.Backend {
    private static final String TAG = "MG4Clima";
    private static final String PKG = "com.saicmotor.service.vehicle";
    private static final String ACTION = "com.saicmotor.service.vehicle.VehicleService";
    private static final String HUB = "com.saicmotor.sdk.vehiclesettings.IHubService";
    private static final int HUB_GET_SERVICE = 1;

    private static final String AC = "com.saicmotor.sdk.vehiclesettings.IAirConditionService";
    private static final int GET_POWER = 53, GET_AUTO = 54, GET_AC = 55, GET_LOOP = 56, GET_ECON = 57,
            GET_BLOWER_MODE = 58, GET_AIR_VOLUME = 59, GET_MAX_AIR = 60, GET_MIN_AIR = 61,
            GET_DRV_SEAT = 77, GET_PSG_SEAT = 79, GET_DRV_TEMP = 81, GET_OUT_TEMP = 88,
            GET_FRONT_DEFROST = 92, GET_REAR_DEFROST = 93, GET_WHEEL_HEAT = 94;

    private static final String COND = "com.saicmotor.sdk.vehiclesettings.IVehicleConditionService";
    private static final int COND_SPEED = 3, COND_IGNITION = 4, COND_SEAT_HEAT_CONFIG = 17;
    private final int condAcAvailable;

    /** Comandi dell'app originale: quelli "PRESS" vogliono 1 (la pressione del tasto, non un valore). */
    private static int code(int cmd) {
        switch (cmd) {
            case HvacClient.SET_POWER_PRESS: return 4;
            case HvacClient.SET_AUTO_PRESS: return 7;
            case HvacClient.SET_AC_PRESS: return 8;
            case HvacClient.SET_LOOP_PRESS: return 9;
            case HvacClient.SET_ECON_PRESS: return 13;
            case HvacClient.SET_BLOWER_MODE: return 14;
            case HvacClient.SET_AIR_VOLUME: return 15;
            case HvacClient.SET_DRV_SEAT_PRESS: return 33;
            case HvacClient.SET_PSG_SEAT_PRESS: return 37;
            case HvacClient.SET_DRV_TEMP: return 41;
            case HvacClient.FRONT_DEFROST_OPEN: return 48;
            case HvacClient.FRONT_DEFROST_CLOSE: return 49;
            case HvacClient.REAR_DEFROST_OPEN: return 50;
            case HvacClient.REAR_DEFROST_CLOSE: return 51;
            case HvacClient.SET_WHEEL_PRESS: return 52;
            default: return -1;
        }
    }

    private final Context ctx;
    private volatile IBinder hub;
    private final Map<String, IBinder> subs = new HashMap<>();
    private boolean bound;
    private int fanMin = -1, fanMax = -1;
    private Runnable onConnected;

    // ---- ascoltatore dei cambi del clima (1.0.2) ---------------------------------------------------
    // Il servizio clima avvisa i suoi ascoltatori (addAirConditionListener = 1) a ogni cambio: è così che il SystemUI sa
    // che il clima si è acceso e apre il suo pannello. MG4 Clima si registra allo stesso modo e lo sa nello stesso istante.
    private static final String LISTENER = "com.saicmotor.sdk.vehiclesettings.IAirConditionListener";
    private static final int ADD_LISTENER = 1;
    private volatile Runnable onChange;
    private volatile IBinder listenedOn;
    private final android.os.Binder listener = new android.os.Binder() {
        @Override
        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LISTENER);
                return true;
            }
            if (code == 1 || code == 2) {   // onChangeEvent, onErrorEvent
                Runnable r = onChange;
                if (r != null) {
                    try {
                        r.run();
                    } catch (Throwable ignored) {
                    }
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return false;
        }
    };

    @Override
    public void setOnChange(Runnable r) {
        onChange = r;
    }

    /** Registra l'ascoltatore sul servizio clima (una volta per collegamento). */
    private void ensureListener(IBinder a) {
        if (onChange == null || a == null || a == listenedOn) return;
        try {
            Rpc.call(a, AC, ADD_LISTENER, p -> p.writeStrongBinder(listener));
            listenedOn = a;
            Store.event(ctx, "ascolto dei cambi del clima attivo");
        } catch (Throwable e) {
            listenedOn = a;   // non riprovare a ogni lettura
            Store.event(ctx, "ascolto dei cambi del clima non disponibile: " + e);
        }
    }

    SaicBackend(Context c, boolean swi133) {
        ctx = c;
        condAcAvailable = swi133 ? 45 : 49;
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            hub = service;
            synchronized (subs) {
                subs.clear();
            }
            Log.i(TAG, "servizio veicolo collegato");
            Runnable r = onConnected;
            if (r != null) r.run();
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

    @Override
    public void bind(Runnable onConnected) {
        this.onConnected = onConnected;
        try {
            bound = ctx.bindService(new Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            bound = false;
            Log.e(TAG, "bind: " + e);
        }
    }

    @Override
    public void unbind() {
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
        }
        bound = false;
        hub = null;
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

    private void reset() {
        synchronized (subs) {
            subs.clear();
        }
    }

    @Override
    public HvacClient.State empty() {
        HvacClient.State s = new HvacClient.State();
        s.tempMin = 17;   // come l'app originale: 17 = LO, 33 = HI
        s.tempMax = 33;
        s.hasWheel = true;
        s.hasSeats = true;
        return s;
    }

    @Override
    public int readPower() {
        try {
            IBinder a = sub("aircondition");
            ensureListener(a);
            return a == null ? -1 : Rpc.callInt(a, AC, GET_POWER);
        } catch (Throwable e) {
            reset();
            return -1;
        }
    }

    @Override
    public void send(int cmd, Integer arg) throws Exception {
        int code = code(cmd);
        if (code < 0) throw new IllegalStateException("comando non presente su questa auto");
        IBinder a = sub("aircondition");
        if (a == null) throw new IllegalStateException("servizio clima non collegato");
        Rpc.call(a, AC, code, arg == null ? Rpc.NONE : p -> p.writeInt(arg));
    }

    @Override
    public void read(HvacClient.State s) throws Exception {
        try {
            IBinder c = sub("vehiclecondition");
            IBinder a = sub("aircondition");
            if (a == null) return;
            s.connected = true;
            if (c != null) {
                s.acAvailable = Rpc.callInt(c, COND, condAcAvailable);
                s.ignition = Rpc.callInt(c, COND, COND_IGNITION);
                s.seatConfig = Rpc.callInt(c, COND, COND_SEAT_HEAT_CONFIG);
                s.speed = Rpc.callFloat(c, COND, COND_SPEED);
            }
            if (fanMax < 0) {
                int mn = Rpc.callInt(a, AC, GET_MIN_AIR), mx = Rpc.callInt(a, AC, GET_MAX_AIR);
                if (mn >= 1 && mx > mn && mx < HvacClient.FAN_AUTO) {
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
        } catch (Exception e) {
            reset();
            throw e;
        }
    }
}
