package com.mg4.clima;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * Velocità dell'auto dal CarService (PERF_VEHICLE_SPEED, m/s) per il test automatico: si prova solo ad auto ferma.
 * Tutto per riflessione (libreria android.car facoltativa): se non si legge, la velocità resta "non disponibile".
 */
final class Speed {
    private static final int PERF_VEHICLE_SPEED = 291504647;
    private final Context ctx;
    private Object car, pm;
    private volatile boolean connected;

    Speed(Context c) {
        ctx = c;
    }

    synchronized void connect() {
        if (car != null) return;
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    connected = true;
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    connected = false;
                    pm = null;
                }
            };
            car = carCls.getMethod("createCar", Context.class, ServiceConnection.class).invoke(null, ctx, conn);
            carCls.getMethod("connect").invoke(car);
        } catch (Throwable ignored) {
        }
    }

    /** km/h oppure NaN se non leggibile. */
    float kmh() {
        try {
            if (pm == null && car != null && connected) pm = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
            if (pm == null) return Float.NaN;
            Object v = pm.getClass().getMethod("getFloatProperty", int.class, int.class).invoke(pm, PERF_VEHICLE_SPEED, 0);
            return v instanceof Float ? Math.abs((Float) v) * 3.6f : Float.NaN;
        } catch (Throwable e) {
            return Float.NaN;
        }
    }
}
