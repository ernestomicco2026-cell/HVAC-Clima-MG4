package com.mg4.clima;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Schermata MG4 Clima (una sola app per SWI68, SWI69, SWI132 e SWI133, il modello si riconosce da solo): mostra lo
 * stato reale del clima e i tasti comandano l'auto come l'app originale. Dalle impostazioni: apertura del clima,
 * chiusura del pannello clima di sistema, aggiornamenti, modello, clima originale, test automatico e diagnostica.
 */
public final class ClimaActivity extends Activity {
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    private static final long BYPASS_MS = 2 * 60_000L;
    private static final String SETTINGS = "impostazioni", DIAG = "diagnostica";

    private ClimaView view;
    private TextView banner;
    private boolean testRunning;
    private long lastSend;
    private HvacClient client;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this, Store.language(this));
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        view = new ClimaView(this);
        view.setRightHand(Store.rightHand(this));
        view.setAnimated(Store.animatedAir(this));
        view.setDashStyle(Store.dashStyle(this));
        view.setDefrostTiles(Store.defrostTiles(this));
        view.setListener(this::onTap);
        root = new FrameLayout(this);
        root.addView(view);
        banner = new TextView(this);
        banner.setTextSize(22);
        banner.setTextColor(0xFFFFFFFF);
        banner.setBackgroundColor(0xE0123A6B);
        banner.setPadding(40, 18, 40, 18);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        client = new HvacClient(this);
        // al primo avvio dopo l'installazione attiva la sostituzione dell'app clima
        ClimaGuard.ensureEnabled(this);
        if (ClimaGuard.hasOriginalApp(this) && !ClimaGuard.originalKnown(this)) {
            toastLong(L.f(R.string.a_original_unverified, ClimaGuard.originalVersion(this)));
        }
        Store.event(this, "schermata aperta · " + Model.get(this) + (Model.manual(this) ? " (scelto a mano)" : ""));
        fromLauncher(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        fromLauncher(i);
    }

    /** Aperta dal launcher MG4: con la sostituzione spenta si va al clima originale. */
    private void fromLauncher(Intent i) {
        if (i != null && "com.mg4.clima.APRI".equals(i.getAction()) && !Store.replace(this)) openOriginal();
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.start(this::onState);
        h.post(clock);
    }

    @Override
    protected void onStop() {
        // diagnostica nei log quando si esce (se ci sono eventi nuovi, al massimo ogni 2 minuti)
        if (Store.unsent() && System.currentTimeMillis() - lastSend > 120_000) {
            lastSend = System.currentTimeMillis();
            LogSend.send(this, "uscita", null);
        }
        client.stop();
        h.removeCallbacks(clock);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (L.init(this, Store.language(this))) view.invalidate();   // lingua dell'auto cambiata
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        if (Store.closeShade(this)) {
            shadeBlock(true);
            if (Model.sysUiPanel(this)) SysUiPanel.prepare(this);
        }
    }

    /** L'ora sul quadro strumenti stilizzato. */
    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            view.invalidate();
            h.postDelayed(this, 30_000);
        }
    };

    private void onTap(String id) {
        if ("chiudi".equals(id)) {
            onBackPressed();
            return;
        }
        if (testRunning) {
            toast(L.s(R.string.a_test_running));
            return;
        }
        if ("impostazioni".equals(id)) {
            settings();
            return;
        }
        command(id);
    }

    // ---------------------------------------------------------------------------------- comandi

    private HvacClient.State last = new HvacClient.State();
    private int pendTemp = -1, pendFan = -1, lastManualFan = 4;
    private long pendTempAt, pendFanAt;

    private void onState(HvacClient.State s) {
        last = s;
        if (s.fan >= 1 && s.fan <= 11) lastManualFan = s.fan;
        view.setState(s);
    }

    private void command(String id) {
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
                if (Model.sysUiPanel(this) && Store.closeShade(this) && s.power != 1)
                    SysUiPanel.closeSoon(this, "accensione da MG4 Clima");
                break;
            case "temp-":
            case "temp+": {
                int base = now - pendTempAt < 1500 && pendTemp > 0 ? pendTemp : s.temp;
                if (base < s.tempMin || base > s.tempMax) base = 22;
                int tt = id.equals("temp+") ? Math.min(s.tempMax, base + 1) : Math.max(s.tempMin, base - 1);
                if (tt == base) return;
                pendTemp = tt;
                pendTempAt = now;
                send("temperatura", HvacClient.SET_DRV_TEMP, tt);
                break;
            }
            case "fan-":
            case "fan+": {
                int min = Math.max(1, s.fanMin), max = Math.min(11, s.fanMax);
                int base = now - pendFanAt < 1500 && pendFan > 0 ? pendFan
                        : s.fan >= min && s.fan <= max ? s.fan : lastManualFan;   // da AUTO si parte dall'ultimo livello
                boolean fromAuto = s.fan == HvacClient.FAN_AUTO && now - pendFanAt >= 1500;
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
                Store.event(this, "tocco su " + id + " → clima originale");
                openOriginal();
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

    private void send(String what, int code, Integer arg) {
        client.command(what, code, arg, err -> {
            if (err != null) toast(L.f(R.string.a_cmd_failed, what));
        });
    }

    /** Comandi non provati dal test (parabrezza, lunotto): se dopo 3 s non cambia nulla lo dice. */
    private void checked(String what, int code, java.util.function.ToIntFunction<HvacClient.State> f, int before) {
        send(what, code, null);
        h.postDelayed(() -> {
            if (f.applyAsInt(last) == before) {
                Store.event(this, what + ": nessun cambio dopo il comando");
                toast(L.f(R.string.a_use_button, L.s("parabrezza".equals(what) ? R.string.t_front : R.string.t_rear)));
            }
        }, 3000);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }


    private long lastOriginalAt;

    /** Clima originale: app clima (SWI68, SWI69, SWI132) o pannello del SystemUI (SWI133). */
    private void openOriginal() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOriginalAt < 3000) return;   // niente rimbalzi tra MG4 Clima e il clima originale
        lastOriginalAt = now;
        Store.bypass(this, BYPASS_MS);
        try {
            if (ClimaGuard.hasOriginalApp(this)) {
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName(ClimaGuard.hvacPkg(this), ClimaGuard.hvacMain(this)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            } else {
                SysUiPanel.open(this);
                moveTaskToBack(true);
            }
        } catch (Throwable e) {
            toast(L.s(R.string.a_original_missing));
        }
    }

    // ---------------------------------------------------------------------------------- impostazioni

    private Sheet sheet;
    private FrameLayout root;
    private String currentSheet = "";
    private boolean updateChecked;
    private TextView updText;

    private boolean settingsShowing() {
        return sheet != null && sheet.showing() && SETTINGS.equals(currentSheet);
    }

    private void settings() {
        if (sheet == null) sheet = new Sheet(this, root, view);
        String model = Model.get(this);
        boolean panelModel = Model.sysUiPanel(this), app = ClimaGuard.hasOriginalApp(this);
        Sheet s = sheet.open(L.s(R.string.s_title), "MG4 Clima " + BuildInfo.version(this) + " · " + Model.label(model));
        currentSheet = SETTINGS;

        // ---- schermata
        s.label(L.s(R.string.s_screen));
        LinearLayout c0 = s.card();
        boolean rh = Store.rightHand(this);
        s.row(c0, L.s(R.string.s_side), L.s(R.string.s_side_sub),
                s.choices(new String[]{L.s(R.string.s_left), L.s(R.string.s_right)}, rh ? 1 : 0, i -> {
                    Store.setRightHand(this, i == 1);
                    view.setRightHand(i == 1);
                    settings();
                }), false);
        int ds = Store.dashStyle(this);
        s.row(c0, L.s(R.string.s_dash), L.s(R.string.s_dash_sub),
                s.choices(new String[]{L.s(R.string.s_dash_drawn), L.s(R.string.s_dash_photo)}, ds == Store.STYLE_PHOTO ? 1 : 0, i -> {
                    int st = i == 1 ? Store.STYLE_PHOTO : Store.STYLE_DRAWN;
                    Store.setDashStyle(this, st);
                    view.setDashStyle(st);
                    Store.event(this, "cruscotto " + (i == 1 ? "realistico" : "disegnato"));
                    settings();
                }), false);
        s.row(c0, L.s(R.string.s_anim), L.s(R.string.s_anim_sub),
                s.toggle(Store.animatedAir(this), v -> {
                    Store.setAnimatedAir(this, v);
                    view.setAnimated(v);
                    Store.event(this, "aria animata " + (v ? "accesa" : "spenta"));
                }), false);
        s.row(c0, L.s(R.string.s_defrost_tiles), L.s(R.string.s_defrost_tiles_sub),
                s.toggle(Store.defrostTiles(this), v -> {
                    Store.setDefrostTiles(this, v);
                    view.setDefrostTiles(v);
                    Store.event(this, "tasti parabrezza/lunotto " + (v ? "mostrati" : "nascosti"));
                }), false);
        String lp = Store.language(this);
        s.row(c0, L.s(R.string.s_language), L.AUTO.equals(lp) ? L.f(R.string.s_language_auto, L.nameOf(L.resolve(L.AUTO))) : L.nameOf(lp),
                s.button(L.s(R.string.b_change), v -> languages()), true);

        // ---- clima automatico: le impostazioni dell'app clima originale, lette e scritte nell'auto
        s.label(L.s(R.string.s_auto_section));
        LinearLayout ca = s.card();
        HvacClient.State st = last;
        boolean any = st.autoLevel >= 0 || st.econLink >= 0 || st.defrostLink >= 0;
        if (!any) {
            s.row(ca, L.s(R.string.s_auto_none),
                    Model.SWI132.equals(model) ? L.s(R.string.s_auto_none_model) : L.s(R.string.s_auto_none_sub), null, true);
        } else {
            if (st.autoLevel >= 0) {
                int sel = st.autoLevel == HvacClient.AUTO_LOW ? 0 : st.autoLevel == HvacClient.AUTO_MEDIUM ? 1 : 2;
                s.row(ca, L.s(R.string.s_auto_level), L.s(R.string.s_auto_level_sub),
                        s.choices(new String[]{L.s(R.string.v_level_low), L.s(R.string.v_level_medium), L.s(R.string.v_level_high)}, sel, i -> {
                            int v = i == 0 ? HvacClient.AUTO_LOW : i == 1 ? HvacClient.AUTO_MEDIUM : HvacClient.AUTO_HIGH;
                            setting("intensità AUTO", HvacClient.SET_AUTO_LEVEL, v);
                        }), st.econLink < 0 && st.defrostLink < 0);
            }
            if (st.econLink >= 0) {
                s.row(ca, L.s(R.string.s_econ_link), L.s(R.string.s_econ_link_sub),
                        s.toggle(st.econLink == 1, v -> setting("segui ECO", HvacClient.SET_ECON_LINK, v ? 1 : 2)), st.defrostLink < 0);
            }
            if (st.defrostLink >= 0) {
                s.row(ca, L.s(R.string.s_defrost_link), L.s(R.string.s_defrost_link_sub),
                        s.toggle(st.defrostLink == 0, v -> setting("lunotto automatico", HvacClient.SET_DEFROST_LINK, v ? 0 : 1)), true);
            }
        }

        // ---- apertura del clima
        s.label(L.s(R.string.s_opening));
        LinearLayout c1 = s.card();
        if (app) {
            boolean known = ClimaGuard.originalKnown(this);
            s.row(c1, L.s(R.string.s_replace),
                    known ? L.s(R.string.s_replace_sub) : L.f(R.string.s_replace_unverified, ClimaGuard.originalVersion(this)),
                    s.toggle(Store.replace(this), v -> {
                        Store.setReplace(this, v);
                        Store.event(this, "sostituzione " + (v ? "attivata" : "spenta"));
                    }), false);
        } else {
            s.row(c1, L.s(R.string.s_launcher_button), L.s(R.string.s_launcher_button_sub),
                    s.toggle(Store.replace(this), v -> {
                        Store.setReplace(this, v);
                        Store.event(this, "apertura dal launcher " + (v ? "attivata" : "spenta"));
                    }), false);
        }
        if (panelModel) {
            int pm = Store.panelMode(this);
            s.row(c1, L.s(R.string.s_panel), L.s(R.string.s_panel_sub),
                    s.choices(new String[]{L.s(R.string.s_panel_always), L.s(R.string.s_panel_with), L.s(R.string.s_panel_leave)},
                            pm == Store.PANEL_ALWAYS ? 0 : pm == Store.PANEL_WITH_APP ? 1 : 2, i -> {
                                int m = i == 0 ? Store.PANEL_ALWAYS : i == 1 ? Store.PANEL_WITH_APP : Store.PANEL_LEAVE;
                                Store.setPanelMode(this, m);
                                Store.event(this, "pannello clima di sistema: modo " + m);
                                shadeBlock(m != Store.PANEL_LEAVE);
                                settings();
                            }), true);
        } else s.row(c1, L.s(R.string.s_shade), L.s(R.string.s_shade_sub),
                s.toggle(Store.closeShade(this), v -> {
                    Store.setCloseShade(this, v);
                    Store.event(this, "chiusura pannello/tendina " + (v ? "attivata" : "spenta"));
                    shadeBlock(v);
                }), true);

        // ---- versione e aggiornamenti
        s.label(L.s(R.string.s_updates));
        LinearLayout c3 = s.card();
        Updater up = Updater.get(this);
        Updater.Release rel = up.last();
        String ut = up.lastText().isEmpty() ? L.f(R.string.s_installed, BuildInfo.version(this)) : up.lastText();
        if (rel != null && rel.newer && !rel.notes.isEmpty() && !up.busy()) ut += "\n" + rel.notes;
        View ub = up.busy() ? s.busyButton("…")
                : rel != null && rel.newer ? s.primary(L.s(R.string.b_update), v -> confirmUpdate(rel))
                : s.button(L.s(R.string.b_check), v -> up.check());
        updText = s.row(c3, L.f(R.string.s_version, BuildInfo.version(this)), ut, ub, true);
        up.setListener((text, r, busy) -> {
            if (!settingsShowing()) return;
            if (busy && updText != null) updText.setText(text);
            else settings();
        });
        if (!updateChecked && !up.busy()) {
            updateChecked = true;
            up.check();
        }

        // ---- auto
        s.label(L.s(R.string.s_car));
        LinearLayout c4 = s.card();
        String sw = Model.software();
        String how = Model.manual(this) ? L.f(R.string.s_model_manual, Model.detected(this))
                : L.s(R.string.s_model_auto) + (sw.isEmpty() ? "" : " · " + sw);
        String[] names = {"Auto", "68", "69", "132", "133"};
        int sel = 0;
        if (Model.manual(this)) for (int i = 0; i < Model.ALL.length; i++) if (Model.ALL[i].equals(model)) sel = i + 1;
        s.row(c4, Model.label(model), how, s.choices(names, sel, i -> confirmModel(i == 0 ? "" : Model.ALL[i - 1])), true);

        // ---- strumenti
        s.label(L.s(R.string.s_tools));
        LinearLayout c5 = s.card();
        s.row(c5, L.s(app ? R.string.s_orig_app : R.string.s_orig_panel), L.s(app ? R.string.s_orig_app_sub : R.string.s_orig_panel_sub),
                s.button(L.s(R.string.b_open), v -> openOriginal()), false);
        s.row(c5, L.s(R.string.s_test), L.s(R.string.s_test_sub), s.button(L.s(R.string.b_start), v -> confirmTest()), false);
        s.row(c5, L.s(R.string.s_diag), L.s(R.string.s_diag_sub), s.button(L.s(R.string.b_show), v -> diagnostics()), false);
        s.row(c5, L.s(R.string.s_send_diag), L.s(R.string.s_send_diag_sub),
                s.button(L.s(R.string.b_send), v -> LogSend.send(this, "manuale", r -> h.post(() -> toast(
                        "ok".equals(r) ? L.s(R.string.a_diag_sent) : r)))), true);
    }

    /** Impostazione dell'app clima originale: si manda all'auto e la pagina si ridisegna appena l'auto risponde. */
    private void setting(String what, int code, int value) {
        if (!last.connected) {
            toast(L.s(R.string.a_not_connected));
            return;
        }
        send(what, code, value);
        h.postDelayed(() -> {
            if (settingsShowing()) settings();
        }, 1200);
    }

    /** Pagina delle lingue: automatica (quella dell'auto) o una delle 8 tradotte. */
    private void languages() {
        Sheet s = sheet.open(L.s(R.string.s_language), null);
        currentSheet = "lingua";
        LinearLayout c = s.card();
        String cur = Store.language(this);
        String[] codes = new String[L.CODES.length + 1];
        codes[0] = L.AUTO;
        System.arraycopy(L.CODES, 0, codes, 1, L.CODES.length);
        for (int i = 0; i < codes.length; i++) {
            final String code = codes[i];
            String name = L.AUTO.equals(code) ? L.f(R.string.s_language_auto, L.nameOf(L.resolve(L.AUTO))) : L.nameOf(code);
            boolean on = code.equals(cur);
            s.row(c, name, null, on ? s.primary("✓", null) : s.button(L.s(R.string.b_use), v -> {
                Store.setLanguage(this, code);
                L.init(this, code);
                Store.event(this, "lingua: " + code);
                view.invalidate();
                settings();
            }), i == codes.length - 1);
        }
    }

    private void confirmUpdate(Updater.Release r) {
        sheet.confirm(L.s(R.string.c_update), L.f(R.string.c_update_msg, r.version) + (r.notes.isEmpty() ? "" : "\n\n" + r.notes),
                L.s(R.string.b_cancel), L.s(R.string.b_update), () -> Updater.get(this).install(r));
    }

    private void confirmModel(String m) {
        String name = m.isEmpty() ? L.f(R.string.c_model_auto, Model.detected(this)) : Model.label(m);
        sheet.confirm(L.s(R.string.c_model), L.f(R.string.c_model_msg, name),
                L.s(R.string.b_cancel), L.s(R.string.b_change), () -> {
                    Store.setModel(this, m);
                    Store.event(this, "modello " + (m.isEmpty() ? "automatico" : "scelto a mano: " + m));
                    restart();
                });
    }

    /** Riavvia MG4 Clima: col modello cambiano servizi e app originale, si riparte puliti. */
    private void restart() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1,
                    new Intent(this, ClimaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT);
            am.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 600, pi);
        } catch (Throwable ignored) {
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void confirmTest() {
        sheet.confirm(L.s(R.string.s_test), L.s(R.string.c_test_msg),
                L.s(R.string.b_cancel), L.s(R.string.b_start), () -> {
                    sheet.close();
                    runTest();
                });
    }

    private void runTest() {
        testRunning = true;
        banner.setText(L.s(R.string.a_test_start));
        banner.setVisibility(View.VISIBLE);
        Store.event(this, "test automatico avviato");
        AutoTest.start(this, client,
                x -> h.post(() -> banner.setText(L.s(R.string.s_test) + " · " + x)),
                r -> h.post(() -> {
                    banner.setText(L.f(R.string.a_test_done, r) + " · " + L.s(R.string.a_sending_log));
                    LogSend.send(this, "test", res -> h.post(() -> {
                        testRunning = false;
                        banner.setText(L.f(R.string.a_test_done, r) + " · log " + ("ok".equals(res) ? "✓" : res));
                        banner.setOnClickListener(v -> banner.setVisibility(View.GONE));
                        h.postDelayed(() -> banner.setVisibility(View.GONE), 60_000);
                    }));
                }));
    }

    private void diagnostics() {
        Sheet s = sheet.open(L.s(R.string.s_diag), "MG4 Clima " + BuildInfo.version(this));
        currentSheet = DIAG;
        s.body(s.card(), Store.text(this));
    }

    // ---------------------------------------------------------------------------------- menù a tendina

    /**
     * Blocca l'apertura del menù a tendina di sistema (StatusBarManager.disable, permesso STATUS_BAR della firma
     * di sistema) mentre MG4 Clima è in primo piano: il sistema lo aprirebbe a ogni accensione/spegnimento del clima.
     * Il blocco è legato a questa app: se si chiude o va in crash il sistema lo toglie da solo.
     */
    private void shadeBlock(boolean on) {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("disable", int.class).invoke(sb, on ? 0x00010000 : 0);   // DISABLE_EXPAND
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Store.event(this, "blocco menù a tendina non riuscito: " + c);
        }
    }

    @Override
    protected void onPause() {
        shadeBlock(false);
        super.onPause();
    }

    /** Chiudendo si torna alla home (sotto c'è l'app clima originale, che non si vuole rivedere). */
    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.showing()) {
            sheet.close();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
        finish();
    }
}
