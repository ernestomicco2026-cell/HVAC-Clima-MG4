package com.mg4.clima;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 1.4.5: comandi del clima (tocchi sulla schermata) in una classe sola, usata dalla schermata {@link ClimaActivity} e
 * dal pannello sovrapposto {@link Pannello} (Android Auto/CarPlay). Il comportamento è quello della 1.4.4, spostato qui
 * senza cambiare niente.
 */
final class Comandi {
    interface Toaster {
        void toast(String s);
    }

    private final Context ctx;
    private final HvacClient client;
    private final Toaster toaster;
    private final Runnable other;
    private final Handler h = new Handler(Looper.getMainLooper());
    private HvacClient.State last = new HvacClient.State();

    Comandi(Context ctx, HvacClient client, Toaster toaster, Runnable other) {
        this.ctx = ctx;
        this.client = client;
        this.toaster = toaster;
        this.other = other;
    }

    private void toast(String s) {
        toaster.toast(s);
    }

    private int pendTemp = -1, pendFan = -1, lastManualFan = 4;
    private long pendTempAt, pendFanAt;

    void onState(HvacClient.State s) {
        last = s;
        if (s.fan >= 1 && s.fan <= 11) lastManualFan = s.fan;
    }

    void command(String id) {
        HvacClient.State s = last;
        if (!s.connected) {
            toast(L.s(R.string.a_not_connected));
            return;
        }
        if (!s.usable()) {
            toast(L.s(R.string.a_switch_on));
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        switch (id) {
            case "accensione":
                press("accensione", HvacClient.SET_POWER_PRESS);
                // all'accensione il SystemUI MG apre il suo pannello clima: si richiude subito (non serve col SystemUI corretto)
                if (Model.sysUiPanel(ctx) && Store.closeShade(ctx) && s.power != 1)
                    SysUiPanel.closeSoon(ctx, "accensione da MG4 Clima");
                break;
            case "temp-":
            case "temp+": {
                int base = now - pendTempAt < 1500 && pendTemp > 0 ? pendTemp : s.temp;
                if (base < s.tempMin || base > s.tempMax) base = 22;
                // 1.4.0: senza LO / HI comandabili (verifica) ci si ferma al minimo / massimo numerico
                int tt = id.equals("temp+") ? Math.min(s.setMax(), base + 1) : Math.max(s.setMin(), base - 1);
                if (tt == base) return;
                pendTemp = tt;
                pendTempAt = now;
                send("temperatura", HvacClient.SET_DRV_TEMP, tt);
                break;
            }
            case "fan-":
            case "fan+": {
                int min = Math.max(1, s.fanMin), max = Math.min(11, s.fanMax);
                // 1.4.2, come l'app originale: "ventola +" a clima spento lo riaccende col comando di accensione (l'auto
                // torna com'era), "ventola −" alla velocità minima spegne il clima
                if (!s.on()) {
                    if (id.equals("fan+")) {
                        press("accensione (ventola +)", HvacClient.SET_POWER_PRESS);
                        if (Model.sysUiPanel(ctx) && Store.closeShade(ctx))
                            SysUiPanel.closeSoon(ctx, "accensione da MG4 Clima");
                    }
                    break;
                }
                int base = now - pendFanAt < 1500 && pendFan > 0 ? pendFan
                        : s.fan >= min && s.fan <= max ? s.fan : lastManualFan;   // da AUTO si parte dall'ultimo livello
                boolean fromAuto = s.fan == HvacClient.FAN_AUTO && now - pendFanAt >= 1500;
                if (!fromAuto && id.equals("fan-") && base <= min && now - pendFanAt >= 1500) {
                    press("spegnimento (ventola − al minimo)", HvacClient.SET_POWER_PRESS);
                    break;
                }
                int f = fromAuto ? base : id.equals("fan+") ? Math.min(max, base + 1) : Math.max(min, base - 1);
                if (f == base && !fromAuto) return;
                pendFan = f;
                pendFanAt = now;
                send("ventola", HvacClient.SET_AIR_VOLUME, f);
                break;
            }
            case "auto":
                // 1.2.0: con AUTO già acceso il tasto cambia l'intensità, come nell'originale dalle impostazioni:
                // bassa → media → alta → bassa (si esce da AUTO toccando la ventola, come nell'app originale)
                if (s.on() && s.auto == 1 && s.autoLevel >= 0) {
                    int next = s.autoLevel == HvacClient.AUTO_LOW ? HvacClient.AUTO_MEDIUM
                            : s.autoLevel == HvacClient.AUTO_MEDIUM ? HvacClient.AUTO_HIGH : HvacClient.AUTO_LOW;
                    send("intensità AUTO", HvacClient.SET_AUTO_LEVEL, next);
                    toast("AUTO · " + ClimaView.autoLevelName(next));
                } else press("AUTO", HvacClient.SET_AUTO_PRESS);
                break;
            case "ac": press("A/C", HvacClient.SET_AC_PRESS); break;
            case "eco": press("ECO", HvacClient.SET_ECON_PRESS); break;
            case "ricircolo": press("ricircolo", HvacClient.SET_LOOP_PRESS); break;
            case "volante": press("volante", HvacClient.SET_WHEEL_PRESS); break;
            case "sedileGuida": press("sedile guidatore", HvacClient.SET_DRV_SEAT_PRESS); break;
            case "sedilePasseggero": press("sedile passeggero", HvacClient.SET_PSG_SEAT_PRESS); break;
            case "vetro":
            case "viso":
            case "piedi":
                direction(id, s);
                break;
            case "parabrezza":
                checked("parabrezza", s.frontDefrost == 1 ? HvacClient.FRONT_DEFROST_CLOSE : HvacClient.FRONT_DEFROST_OPEN,
                        st -> st.frontDefrost, s.frontDefrost);
                break;
            case "lunotto":
                checked("lunotto", s.rearDefrost == 1 ? HvacClient.REAR_DEFROST_CLOSE : HvacClient.REAR_DEFROST_OPEN,
                        st -> st.rearDefrost, s.rearDefrost);
                break;
            default:
                Store.event(ctx, "tocco su " + id + " → clima originale");
                if (other != null) other.run();
        }
    }

    /** Direzione dell'aria come l'app originale: si accende/spegne una delle tre e si calcola il codice 0..6. */
    private void direction(String id, HvacClient.State s) {
        boolean[] d = s.blowerMode >= 0 && s.blowerMode <= 6 ? s.directions() : new boolean[3];
        boolean def = d[0], face = d[1], foot = d[2];
        if (id.equals("vetro")) def = !def;
        else if (id.equals("viso")) face = !face;
        else foot = !foot;
        int mode;
        if (face && !def && !foot) mode = 0;
        else if (face && foot && !def) mode = 1;
        else if (foot && !def && !face) mode = 2;
        else if (def && foot && !face) mode = 3;
        else if (def && !face && !foot) mode = 4;
        else if (face && def && foot) mode = 5;
        else if (face && def) mode = 6;
        else return;   // almeno una direzione deve restare (come l'originale)
        send("direzione", HvacClient.SET_BLOWER_MODE, mode);
    }

    private void press(String what, int code) {
        send(what, code, 1);
    }

    void send(String what, int code, Integer arg) {
        client.command(what, code, arg, err -> {
            if (err != null) toast(L.f(R.string.a_cmd_failed, what));
        });
    }

    /** Comandi non provati dal test (parabrezza, lunotto): se dopo 3 s non cambia nulla lo dice. */
    private void checked(String what, int code, java.util.function.ToIntFunction<HvacClient.State> f, int before) {
        send(what, code, null);
        h.postDelayed(() -> {
            if (f.applyAsInt(last) == before) {
                Store.event(ctx, what + ": nessun cambio dopo il comando");
                toast(L.f(R.string.a_use_button, L.s("parabrezza".equals(what) ? R.string.t_front : R.string.t_rear)));
            }
        }, 3000);
    }

}
