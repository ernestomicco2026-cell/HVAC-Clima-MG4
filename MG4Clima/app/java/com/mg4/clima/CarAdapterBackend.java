package com.mg4.clima;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

/**
 * SWI69 e SWI132: servizio "caradapter" (com.saicmotor.caradapter, ICarHvacService), lo stesso dell'app clima originale
 * (com.saicmotor.hvac, A9_Hvac) e della barra clima del launcher SWI69 (già verificata sulle auto).
 * Accensione, A/C, AUTO, ECO, ricircolo e sbrinamenti sono "cambia stato" (senza argomenti); temperatura, ventola e
 * direzione dell'aria hanno un valore. Sedili e volante riscaldati non ci sono (vengono nascosti).
 */
final class CarAdapterBackend implements HvacClient.Backend {
    private static final String TAG = "MG4Clima";
    private static final String PKG = "com.saicmotor.caradapter";
    private static final String ACTION = "com.saicmotor.caradapter.CarAdapterService";
    private static final String HUB = "com.saicmotor.carapi.ICarAdapterService";
    private static final int HUB_QUERY_CLIENT = 1, BIND_HVAC = 7;

    private static final String AC = "com.saicmotor.carapi.hvac.ICarHvacService";
    private static final int GET_POWER = 6, GET_AC = 8, GET_AUTO = 10, GET_ECO = 14, GET_LOOP = 16, GET_DIRECTION = 18,
            GET_FAN = 20, GET_DRV_TEMP = 22, GET_OUT_TEMP = 25, GET_FRONT_DEFROST = 28, GET_REAR_DEFROST = 30;
    private static final int SET_DRV_TEMP = 21, SET_DIRECTION = 17, SET_FAN = 19;
    /** Impostazioni dell'app clima originale SWI69 (ICarHvacService, verificati su più build SWI69). */
    private static final int SET_AUTO_LEVEL = 39, GET_AUTO_LEVEL = 40, SET_ECON_LINK = 68, GET_ECON_LINK = 69;

    private static int code(int cmd) {
        switch (cmd) {
            case HvacClient.SET_POWER_PRESS: return 5;
            case HvacClient.SET_AC_PRESS: return 7;
            case HvacClient.SET_AUTO_PRESS: return 9;
            case HvacClient.SET_ECON_PRESS: return 13;
            case HvacClient.SET_LOOP_PRESS: return 15;
            case HvacClient.SET_BLOWER_MODE: return SET_DIRECTION;
            case HvacClient.SET_AIR_VOLUME: return SET_FAN;
            case HvacClient.SET_DRV_TEMP:
            case HvacClient.SET_DRV_TEMP_RAW: return SET_DRV_TEMP;
            case HvacClient.FRONT_DEFROST_OPEN:
            case HvacClient.FRONT_DEFROST_CLOSE: return 27;
            case HvacClient.REAR_DEFROST_OPEN:
            case HvacClient.REAR_DEFROST_CLOSE: return 29;
            default: return -1;   // sedili e volante: non presenti
        }
    }

    /**
     * MG4 EU/UK: 16..30 °C, l'auto usa 15,5 = LO e 30,5 = HI. Qui: 15 = LO, 31 = HI (riserva).
     * 1.4.2: il servizio non dice i limiti, li misura la verifica (alcune auto SWI69/SWI132 usano 17 = LO e 33 = HI,
     * e su SWI69 R33 / SWI132 R25 15,5 torna come 31). Vedi AutoTest.calibrateLow/High.
     */
    private static final int TEMP_MIN = 15, TEMP_MAX = 31;
    private static final float CAR_LO = 15.5f, CAR_HI = 30.5f;

    /** Intervallo in uso: LO e HI come si mandano e come l'auto li rilegge; tMin/tMax = LO/HI nella schermata. */
    private static volatile float loSend = CAR_LO, loRead = CAR_LO, hiSend = CAR_HI, hiRead = CAR_HI;
    private static volatile int tMin = TEMP_MIN, tMax = TEMP_MAX, fanMaxCal = -1;
    private static volatile boolean noLo, noHi;
    private static volatile String source = "riserva";

    static int tempFromCar(float f) {
        if (Float.isNaN(f) || f <= 0) return -1;
        if (f <= loRead + 0.01f) return tMin;
        if (f >= hiRead - 0.01f) return tMax;
        return Math.max(tMin + 1, Math.min(tMax - 1, Math.round(f)));
    }

    static float tempToCar(int v) {
        if (v <= tMin) return loSend;
        if (v >= tMax) return hiSend;
        return v;
    }

    /** Valori da provare nella verifica (1.4.2): LO scendendo, HI salendo; ci si ferma al primo che l'auto rifiuta. */
    static final float[] LO_TRY = {17f, 16f, 15.5f, 15f}, HI_TRY = {30.5f, 31f, 32f, 33f};

    /** Legge dalle impostazioni l'intervallo misurato per questa versione dell'app clima originale. */
    static void loadLimits(Context c) {
        String ver = ClimaGuard.originalVersion(c);
        float[] r = Store.tempRange(c, ver);
        if (r != null) {
            noLo = Float.isNaN(r[0]);
            noHi = Float.isNaN(r[2]);
            loSend = noLo ? CAR_LO : r[0];
            loRead = noLo ? CAR_LO : r[1];
            hiSend = noHi ? CAR_HI : r[2];
            hiRead = noHi ? CAR_HI : r[3];
            fanMaxCal = (int) r[4];
            source = "verifica";
        } else {   // 1.4.0 / 1.4.1: solo i valori da mandare (letti come LO ≤ 15,5 e HI ≥ 30,5)
            Float lo = Store.tempLimit(c, "LO", ver), hi = Store.tempLimit(c, "HI", ver);
            noLo = lo != null && Float.isNaN(lo);
            noHi = hi != null && Float.isNaN(hi);
            loSend = lo == null || noLo ? CAR_LO : lo;
            hiSend = hi == null || noHi ? CAR_HI : hi;
            loRead = Math.min(loSend, CAR_LO);
            hiRead = Math.max(hiSend, CAR_HI);
            fanMaxCal = -1;
            source = lo != null || hi != null ? "verifica 1.4.0" : "riserva";
        }
        tMin = noLo ? TEMP_MIN : (int) Math.floor(loRead + 0.001f);
        tMax = noHi ? TEMP_MAX : (int) Math.ceil(hiRead - 0.001f);
        if (tMax - tMin < 6) {   // valori assurdi: riserva
            tMin = TEMP_MIN;
            tMax = TEMP_MAX;
            loSend = loRead = CAR_LO;
            hiSend = hiRead = CAR_HI;
            source = "riserva (misura non valida)";
        }
    }

    static String limitsText() {
        return "LO " + (noLo ? "nascosto" : String.valueOf(loSend)) + " · HI " + (noHi ? "nascosto" : String.valueOf(hiSend));
    }

    /** Riga clima.intervallo: minimo e massimo numerici, LO, HI (mandato → riletto) e da dove vengono. */
    static String rangeText() {
        return "minimo " + (tMin + 1) + " · massimo " + (tMax - 1)
                + " · LO " + (noLo ? "nascosto" : tMin + " (" + loSend + " → " + loRead + ")")
                + " · HI " + (noHi ? "nascosto" : tMax + " (" + hiSend + " → " + hiRead + ")")
                + (fanMaxCal > 0 ? " · ventola 1–" + fanMaxCal : "")
                + " · fonte " + source + " (il servizio non dà i limiti)";
    }

    private final Context ctx;
    private final Speed speed;
    private volatile IBinder hub, hvac;
    private boolean bound;
    private Runnable onConnected;

    /** Solo SWI69: sulla SWI132 i codici delle impostazioni non sono verificati, quindi non si usano. */
    private final boolean settings;
    private int settingsFails;
    private boolean settingsBroken;

    CarAdapterBackend(Context c, boolean swi69) {
        settings = swi69;
        ctx = c;
        loadLimits(c);
        speed = new Speed(c);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            hub = service;
            hvac = null;
            Log.i(TAG, "caradapter collegato");
            Runnable r = onConnected;
            if (r != null) r.run();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hub = null;
            hvac = null;
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
        speed.connect();
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
        hvac = null;
    }

    private IBinder ac() throws Exception {
        IBinder b = hvac;
        if (b != null && b.isBinderAlive()) return b;
        IBinder h = hub;
        if (h == null) return null;
        b = Rpc.callBinder(h, HUB, HUB_QUERY_CLIENT, p -> p.writeInt(BIND_HVAC));
        hvac = b;
        return b;
    }

    @Override
    public HvacClient.State empty() {
        HvacClient.State s = new HvacClient.State();
        s.caradapter = true;
        s.seatConfig = 2;
        s.fanMin = 1;
        s.fanMax = fanMaxCal >= 4 && fanMaxCal <= 11 ? fanMaxCal : 11;   // 1.4.2: auto con ventola 1–8
        s.tempMin = tMin;
        s.tempMax = tMax;
        s.noLo = noLo;
        s.noHi = noHi;
        s.hasWheel = false;
        s.hasSeats = false;
        return s;
    }

    @Override
    public int readPower() {
        try {
            IBinder a = ac();
            return a == null ? -1 : Rpc.callBool(a, AC, GET_POWER) ? 1 : 0;
        } catch (Throwable e) {
            hvac = null;
            return -1;
        }
    }

    @Override
    public void send(int cmd, Integer arg) throws Exception {
        if (cmd == HvacClient.SET_AUTO_LEVEL || cmd == HvacClient.SET_ECON_LINK) {
            if (!settings) throw new IllegalStateException("impostazione non presente su questa auto");
            IBinder a = ac();
            if (a == null) throw new IllegalStateException("servizio clima non collegato");
            int val = arg == null ? 0 : arg;
            Rpc.call(a, AC, cmd == HvacClient.SET_AUTO_LEVEL ? SET_AUTO_LEVEL : SET_ECON_LINK, p -> p.writeInt(val));
            return;
        }
        int code = code(cmd);
        if (code < 0) throw new IllegalStateException("comando non presente su questa auto");
        IBinder a = ac();
        if (a == null) throw new IllegalStateException("servizio clima non collegato");
        switch (code) {
            case SET_DRV_TEMP:
                if (cmd == HvacClient.SET_DRV_TEMP_RAW) {   // 1.4.0: valore grezzo (decimi di grado), solo verifica
                    final float raw = (arg == null ? 220 : arg) / 10f;
                    Rpc.call(a, AC, code, p -> p.writeFloat(raw));
                } else {
                    Rpc.call(a, AC, code, p -> p.writeFloat(tempToCar(arg == null ? 22 : arg)));
                }
                break;
            case SET_DIRECTION:
            case SET_FAN:
                Rpc.call(a, AC, code, p -> p.writeInt(arg == null ? 0 : arg));
                break;
            default:
                Rpc.call(a, AC, code, Rpc.NONE);   // "cambia stato"
        }
    }

    @Override
    public void read(HvacClient.State s) throws Exception {
        try {
            IBinder a = ac();
            if (a != null) {
                s.connected = true;
                s.acAvailable = 1;
                s.ignition = 2;   // qui l'accensione non si legge: il clima risponde solo ad auto accesa
                boolean power = Rpc.callBool(a, AC, GET_POWER);
                s.power = power ? 1 : 0;
                s.tempRaw = Rpc.callFloat(a, AC, GET_DRV_TEMP);
                s.temp = tempFromCar(s.tempRaw);
                s.fan = Rpc.callInt(a, AC, GET_FAN);
                s.auto = Rpc.callBool(a, AC, GET_AUTO) ? 1 : 0;
                if (s.auto == 1 && power && s.fan == 0) s.fan = HvacClient.FAN_AUTO;
                s.ac = Rpc.callBool(a, AC, GET_AC) ? 1 : 0;
                s.econ = Rpc.callBool(a, AC, GET_ECO) ? 1 : 0;
                s.blowerMode = Rpc.callInt(a, AC, GET_DIRECTION);
                s.loop = Rpc.callInt(a, AC, GET_LOOP);
                s.frontDefrost = Rpc.callBool(a, AC, GET_FRONT_DEFROST) ? 1 : 0;
                s.rearDefrost = Rpc.callBool(a, AC, GET_REAR_DEFROST) ? 1 : 0;
                float out = Rpc.callFloat(a, AC, GET_OUT_TEMP);
                s.outTemp = out < -100 || out > 100 ? Float.NaN : out;
                if (settings && !settingsBroken) {
                    try {
                        int lv = Rpc.callInt(a, AC, GET_AUTO_LEVEL), ec = Rpc.callInt(a, AC, GET_ECON_LINK);
                        s.autoLevel = lv >= 0 && lv <= 2 ? lv : -1;
                        s.econLink = ec == 1 || ec == 2 ? ec : -1;
                    } catch (Throwable t) {
                        if (++settingsFails >= 5) settingsBroken = true;
                        Store.event(ctx, "impostazioni del clima originale non leggibili: " + t);
                    }
                }
            }
        } catch (Exception e) {
            hvac = null;
            throw e;
        } finally {
            s.speed = speed.kmh();
        }
    }
}
