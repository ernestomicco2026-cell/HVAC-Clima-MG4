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
    /** Istante (ora, ms) in cui è comparsa l'app clima originale sostituita (1.3.1, per misurare il lampeggio). */
    static final String EXTRA_ORIGINAL_AT = "com.mg4.clima.ORIGINALE_AT";
    private long originalAt;

    private ClimaView view;
    private TextView banner;
    private boolean testRunning;
    private long lastSend;
    private HvacClient client;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);   // 1.3.1: senza animazione, copre subito l'app originale
        L.init(this, Store.language(this));
        // 1.3.1: modello scelto a mano uguale a quello che dice il nome del software → si torna al riconoscimento automatico
        if (Model.manual(this) && Store.model(this).equals(Model.fromSoftware())) {
            Store.setModel(this, "");
            Store.event(this, "modello " + Model.fromSoftware() + " riconosciuto dal software " + Model.software() + ": torna automatico");
        }
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
        swipe = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(android.view.MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                return onSwipe(e1, e2, vy);
            }
        });
        client = new HvacClient(this);
        joy = new Joystick(this, (code, down, lp, consumed) -> h.post(() -> onWheelKey(code, down, lp, consumed)));
        // al primo avvio dopo l'installazione attiva la sostituzione dell'app clima
        ClimaGuard.ensureEnabled(this);
        if (ClimaGuard.hasOriginalApp(this) && !ClimaGuard.originalKnown(this)) {
            toastLong(L.f(R.string.a_original_unverified, ClimaGuard.originalVersion(this)));
        }
        Store.event(this, "schermata aperta · " + Model.get(this) + (Model.manual(this) ? " (scelto a mano)" : ""));
        fromGuard(getIntent());
        fromLauncher(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        overridePendingTransition(0, 0);
        fromGuard(i);
        fromLauncher(i);
    }

    /** Aperta al posto dell'app clima originale: si ricorda quando è comparsa l'originale (1.3.1). */
    private void fromGuard(Intent i) {
        long at = i == null ? 0 : i.getLongExtra(EXTRA_ORIGINAL_AT, 0);
        if (at > 0) originalAt = at;
    }

    /** 1.3.1: la nostra è davvero sullo schermo: nel log quanto è rimasta visibile l'originale. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || originalAt <= 0) return;
        long now = System.currentTimeMillis();
        Store.event(this, "MG4 Clima aperta alle " + Store.ms(now) + " · originale comparsa alle " + Store.ms(originalAt)
                + " (+" + Math.max(0, now - originalAt) + " ms)");
        originalAt = 0;
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
        h.postDelayed(this::maybeAskVerify, 2500);
        if (L.init(this, Store.language(this))) view.invalidate();   // lingua dell'auto cambiata
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        if (Store.closeShade(this)) {
            shadeBlock(true);
            if (Model.sysUiPanel(this)) SysUiPanel.prepare(this);
        }
        edges();
        joy.register();   // 1.3.3: tasti del volante solo con la schermata aperta e in primo piano
    }

    /** Strisce sui bordi per lo swipe dal bordo, solo mentre MG4 Clima è in primo piano (1.3.1). */
    private void edges() {
        int m = Store.swipeMode(this);
        ClimaGuard.edges(this, m == Store.SWIPE_OFF ? 0 : m == Store.SWIPE_UP ? 1 : m == Store.SWIPE_DOWN ? 2 : 3);
        // 1.3.3: se dopo qualche secondo il servizio di accessibilità non si è collegato, lo si dice una volta
        if (m != Store.SWIPE_OFF && !a11yWarned) h.postDelayed(() -> {
            if (a11yWarned || ClimaGuard.connected() || isFinishing()) return;
            a11yWarned = true;
            Store.event(this, "avviso: servizio di accessibilità non collegato (mostrato all'utente)");
            toastLong(L.s(R.string.a_a11y_off));
        }, 6000);
    }

    private static boolean a11yWarned;

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
            close("tasto chiudi");
            return;
        }
        if ("fuori".equals(id)) {
            close("tocco fuori dal pannello");
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
        // 1.3.1: se l'auto non le fornisce (es. SWI132: -1) la sezione non si mostra; ogni voce solo se c'è il valore
        HvacClient.State st = last;
        boolean any = st.autoLevel >= 0 || st.econLink >= 0 || st.defrostLink >= 0;
        if (any) {
            s.label(L.s(R.string.s_auto_section));
            LinearLayout ca = s.card();
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
        // 1.3.1: Spento / Automatico (entrambe le direzioni) / Verso l'alto / Verso il basso
        final int[] swModes = {Store.SWIPE_OFF, Store.SWIPE_AUTO, Store.SWIPE_UP, Store.SWIPE_DOWN};
        int swSel = 1;
        for (int i = 0; i < swModes.length; i++) if (swModes[i] == Store.swipeMode(this)) swSel = i;
        s.row(c1, L.s(R.string.s_swipe), L.s(R.string.s_swipe_sub),
                s.choices(new String[]{L.s(R.string.s_swipe_off), L.s(R.string.s_swipe_auto), L.s(R.string.s_swipe_bottom),
                        L.s(R.string.s_swipe_top)}, swSel, i -> {
                    Store.setSwipeMode(this, swModes[i]);
                    Store.event(this, "chiusura con swipe: " + Store.swipeName(swModes[i]));
                    edges();
                }), false);
        // 1.3.3: comandi dal joystick del volante (solo con la schermata aperta)
        int js = Store.joystick(this);
        s.row(c1, L.s(R.string.s_joy), js != Store.JOY_OFF && !Joystick.complete(this, js) ? L.s(R.string.s_joy_unset) : L.s(R.string.s_joy_sub),
                s.choices(new String[]{L.s(R.string.s_swipe_off), L.s(R.string.s_joy_right), L.s(R.string.s_joy_left)}, js, i -> {
                    Store.setJoystick(this, i);
                    Store.event(this, "comandi dal joystick: " + (i == 0 ? "spenti" : i == 1 ? "joystick destro" : "joystick sinistro")
                            + (i == 0 ? "" : " (" + Joystick.mapText(this, i) + ")"));
                    settings();
                }), false);
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
        if (ClimaGuard.hasOriginalApp(this) && !ClimaGuard.originalKnown(this))
            s.row(c5, L.s(R.string.s_verify), L.s(R.string.s_verify_sub), s.button(L.s(AutoTest.resumable(this, "verifica")
                    ? R.string.b_resume : R.string.b_start), v -> confirmVerify()), false);
        long oUntil = Store.bypassUntil(this);
        if (System.currentTimeMillis() < oUntil) {
            s.row(c5, L.s(R.string.s_officina), L.f(R.string.s_officina_on,
                    new java.text.SimpleDateFormat("dd/MM", java.util.Locale.ITALY).format(new java.util.Date(oUntil))),
                    s.button(L.s(R.string.b_cancel), v -> {
                        Store.bypass(this, 0);
                        Store.event(this, "modalità officina annullata");
                        settings();
                    }), false);
        } else {
            s.row(c5, L.s(R.string.s_officina), L.s(R.string.s_officina_sub),
                    s.choices(new String[]{L.s(R.string.s_officina_1), L.s(R.string.s_officina_3), L.s(R.string.s_officina_7)}, -1,
                            i -> confirmOfficina(new int[]{1, 3, 7}[i])), false);
        }
        s.row(c5, L.s(R.string.s_keys), L.s(R.string.s_keys_sub), s.button(L.s(R.string.b_open), v -> keyTest()), false);
        s.row(c5, L.s(R.string.s_test), L.s(R.string.s_test_sub), s.button(L.s(AutoTest.resumable(this, "test")
                ? R.string.b_resume : R.string.b_start), v -> confirmTest()), false);
        s.row(c5, L.s(R.string.s_uninstall), L.s(R.string.s_uninstall_sub),
                s.button(L.s(R.string.b_uninstall), v -> confirmUninstall()), false);
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

    /** Modalità officina (1.3.0): la sostituzione resta sospesa per N giorni, poi torna da sola. */
    private void confirmOfficina(int days) {
        sheet.confirm(L.s(R.string.s_officina), L.f(R.string.c_officina_msg, days),
                L.s(R.string.b_cancel), L.s(R.string.b_activate), () -> {
                    sheet.close();
                    Store.bypass(this, days * 24L * 3600_000L);
                    Store.event(this, "modalità officina per " + days + " giorni");
                    toast(L.f(R.string.s_officina_on, new java.text.SimpleDateFormat("dd/MM", java.util.Locale.ITALY)
                            .format(new java.util.Date(Store.bypassUntil(this)))));
                });
    }

    /** Verifica di una versione nuova dell'app clima originale (1.3.0): stessi comandi del test automatico. */
    private void confirmVerify() {
        sheet.confirm(L.s(R.string.s_verify), L.s(R.string.c_verify_msg),
                L.s(R.string.b_cancel), L.s(R.string.b_start), () -> {
                    sheet.close();
                    runVerify();
                });
    }

    private void runVerify() {
        final String ver = ClimaGuard.originalVersion(this);
        testRunning = true;
        banner.setText(L.s(R.string.s_verify));
        banner.setVisibility(View.VISIBLE);
        Store.event(this, "verifica app clima " + ver + (AutoTest.resumable(this, "verifica") ? " ripresa" : " avviata"));
        AutoTest.start(this, client,
                x -> h.post(() -> banner.setText(L.s(R.string.s_verify) + " · " + x)), "verifica",
                r -> h.post(() -> {
                    boolean ok = !r.startsWith("interrotto") && !r.startsWith("errore") && r.contains(", 0 non riusciti");
                    boolean moving = r.contains(AutoTest.MOVING);
                    if (ok) Store.addVerified(this, ver);
                    if (ok) Store.setPendingVerify(this, "");
                    Store.event(this, "verifica " + ver + (ok ? ": riuscita" : moving ? ": interrotta (auto in movimento, si può riprendere)"
                            : ": non riuscita") + " · " + r);
                    LogSend.send(this, "verifica", res -> h.post(() -> {
                        testRunning = false;
                        banner.setText((ok ? L.f(R.string.a_verify_ok, ver) : moving ? L.s(R.string.a_test_moving)
                                : L.f(R.string.a_verify_fail, ver)) + " · log " + ("ok".equals(res) ? "✓" : res));
                        banner.setOnClickListener(v -> banner.setVisibility(View.GONE));
                        h.postDelayed(() -> banner.setVisibility(View.GONE), 60_000);
                    }));
                }));
    }

    /** All'apertura: se c'è una versione sconosciuta in attesa, chiede la verifica (solo clima spento, auto ferma). */
    private void maybeAskVerify() {
        if (!ClimaGuard.hasOriginalApp(this) || ClimaGuard.originalKnown(this)) return;
        String ver = ClimaGuard.originalVersion(this);
        // 1.3.1: si chiede una volta per versione (prima solo dopo che l'originale era stata aperta)
        boolean pending = ver.equals(Store.pendingVerify(this));
        if (!pending && ver.equals(Store.askedVerify(this))) return;
        if (System.currentTimeMillis() < Store.bypassUntil(this)) return;
        if (testRunning || (sheet != null && sheet.showing())) return;
        // 1.3.1: senza velocità (SWI69: NaN) si chiede lo stesso, la conferma dice di stare fermi; il test si ferma da solo
        if (!last.connected || last.on() || (!Float.isNaN(last.speed) && last.speed > 0f)) return;
        Store.setAskedVerify(this, ver);
        Store.event(this, "verifica di " + ver + " proposta all'apertura");
        if (sheet == null) sheet = new Sheet(this, root, view);
        confirmVerify();
    }

    private void runTest() {
        testRunning = true;
        banner.setText(L.s(R.string.a_test_start));
        banner.setVisibility(View.VISIBLE);
        Store.event(this, "test automatico " + (AutoTest.resumable(this, "test") ? "ripreso" : "avviato"));
        AutoTest.start(this, client,
                x -> h.post(() -> banner.setText(L.s(R.string.s_test) + " · " + x)), "test",
                r -> h.post(() -> {
                    final String shown = r.contains(AutoTest.MOVING) ? L.s(R.string.a_test_moving) : L.f(R.string.a_test_done, r);
                    banner.setText(shown + " · " + L.s(R.string.a_sending_log));
                    LogSend.send(this, "test", res -> h.post(() -> {
                        testRunning = false;
                        banner.setText(shown + " · log " + ("ok".equals(res) ? "✓" : res));
                        banner.setOnClickListener(v -> banner.setVisibility(View.GONE));
                        h.postDelayed(() -> banner.setVisibility(View.GONE), 60_000);
                    }));
                }));
    }

    // ---------------------------------------------------------------------------------- joystick del volante (1.3.3)

    private Joystick joy;
    private boolean consumeLogged;
    private final java.util.ArrayDeque<String> keysSeen = new java.util.ArrayDeque<>();
    /** Associazione in corso dalla schermata Prova tasti: joystick (1/2) e passo (indice in Joystick.ACTIONS). */
    private int assignSide, assignStep = -1;
    private static final String KEYS = "tasti";

    private void onWheelKey(int code, boolean down, boolean lp, boolean consumed) {
        if (!consumeLogged && down) {
            consumeLogged = true;
            Store.event(this, "joystick: tasti del volante " + (consumed ? "consumati da MG4 Clima (le altre app non li ricevono)"
                    : "ricevuti anche dalle altre app (il sistema non permette di bloccarli)"));
        }
        // schermata Prova tasti: si mostra e si scrive ogni tasto, niente comandi
        if (sheet != null && sheet.showing() && KEYS.equals(currentSheet)) {
            String line = code + (down ? " premuto" : " rilasciato") + (lp ? " (lungo)" : "");
            Store.event(this, "tasto volante: codice " + line);
            keysSeen.addFirst(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()) + "  " + line);
            while (keysSeen.size() > 12) keysSeen.removeLast();
            if (down && !lp && assignStep >= 0) {
                String a = Joystick.ACTIONS[assignStep];
                Store.setJoyCode(this, assignSide, a, code);
                assignStep++;
                if (assignStep >= Joystick.ACTIONS.length) {
                    assignStep = -1;
                    Store.event(this, "joystick " + (assignSide == Store.JOY_RIGHT ? "destro" : "sinistro") + " associato: "
                            + Joystick.mapText(this, assignSide));
                    LogSend.send(this, "joystick", null);
                }
            }
            keyTest();
            return;
        }
        if (sheet != null && sheet.showing()) return;   // impostazioni aperte: i tasti non comandano il clima
        int side = Store.joystick(this);
        if (side == Store.JOY_OFF) return;
        String action = Joystick.actionOf(this, side, code);
        if (action == null) return;
        if (!down) {
            stopRepeat();
            return;
        }
        if (lp && repeatAction != null) return;   // già in ripetizione col nostro timer
        joyAction(code, action, false);
        if (!"ok".equals(action)) startRepeat(code, action);
    }

    /** Esegue l'azione: OK accende (in AUTO) o spegne, su/giù temperatura, sinistra/destra ventola. */
    private void joyAction(int code, String action, boolean repeat) {
        HvacClient.State s = last;
        String id, what, esito = "inviato";
        switch (action) {
            case "ok":
                id = s.on() ? "accensione" : "auto";
                what = s.on() ? "spegni clima" : "accendi clima (AUTO)";
                break;
            case "su":
                id = "temp+";
                what = "temperatura +";
                if (s.temp >= s.tempMax) esito = "già al massimo (HI)";
                break;
            case "giu":
                id = "temp-";
                what = "temperatura -";
                if (s.temp <= s.tempMin) esito = "già al minimo (LO)";
                break;
            case "sinistra":
                id = "fan-";
                what = "ventola -";
                if (s.fan >= 1 && s.fan <= 11 && s.fan <= Math.max(1, s.fanMin)) esito = "già al minimo";
                break;
            default:
                id = "fan+";
                what = "ventola +";
                if (s.fan >= 1 && s.fan <= 11 && s.fan >= Math.min(11, s.fanMax)) esito = "già al massimo";
                break;
        }
        if (!s.connected) esito = "servizio clima non collegato";
        else if (!s.usable()) esito = "auto non accesa";
        if (!repeat || !"inviato".equals(esito)) Store.event(this, "joystick: codice " + code + " → " + what + " → " + esito);
        view.flash("auto".equals(id) ? "accensione" : id);
        if ("inviato".equals(esito)) command(id);
        else if (repeat) stopRepeat();
    }

    private String repeatAction;
    private int repeatCode, repeatCount;
    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (repeatAction == null) return;
            if (++repeatCount > 30) {   // al massimo 30 ripetizioni (se il rilascio non arrivasse)
                stopRepeat();
                return;
            }
            joyAction(repeatCode, repeatAction, true);
            h.postDelayed(this, 300);
        }
    };

    /** Tenendo premuto su/giù/sinistra/destra il comando si ripete (dopo mezzo secondo, ogni 0,3 s). */
    private void startRepeat(int code, String action) {
        stopRepeat();
        repeatAction = action;
        repeatCode = code;
        repeatCount = 0;
        h.postDelayed(repeater, 500);
    }

    private void stopRepeat() {
        repeatAction = null;
        h.removeCallbacks(repeater);
    }

    /** Schermata Prova tasti del volante: mostra i codici premuti e permette di associare il joystick. */
    private void keyTest() {
        if (sheet == null) sheet = new Sheet(this, root, view);
        Sheet s = sheet.open(L.s(R.string.s_keys), L.s(R.string.s_keys_hint));
        currentSheet = KEYS;
        LinearLayout c = s.card();
        String prompt = assignStep >= 0 ? L.f(R.string.k_press, L.s(assignSide == Store.JOY_RIGHT ? R.string.s_joy_right : R.string.s_joy_left),
                L.s(actionName(Joystick.ACTIONS[assignStep]))) : L.s(R.string.k_idle);
        s.row(c, prompt, keysSeen.isEmpty() ? L.s(R.string.k_none) : String.join("\n", keysSeen), null, true);
        LinearLayout c2 = s.card();
        for (int side : new int[]{Store.JOY_RIGHT, Store.JOY_LEFT}) {
            final int sd = side;
            s.row(c2, L.s(side == Store.JOY_RIGHT ? R.string.s_joy_right : R.string.s_joy_left), Joystick.mapText(this, side),
                    s.button(L.s(R.string.b_assign), v -> {
                        Store.clearJoy(this, sd);
                        assignSide = sd;
                        assignStep = 0;
                        Store.event(this, "associazione joystick " + (sd == Store.JOY_RIGHT ? "destro" : "sinistro") + " avviata");
                        keyTest();
                    }), side == Store.JOY_LEFT);
        }
    }

    private static int actionName(String a) {
        switch (a) {
            case "su": return R.string.k_up;
            case "giu": return R.string.k_down;
            case "sinistra": return R.string.k_left;
            case "destra": return R.string.k_right;
            default: return R.string.k_ok;
        }
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
        ClimaGuard.edges(this, 0);
        joy.unregister();
        stopRepeat();
        super.onPause();
    }

    /** Il manifest dichiara uiMode in configChanges: l'activity non si ricrea, quindi avvisa la vista del nuovo tema (1.3.0). */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (view != null) view.theme();
    }

    private android.view.GestureDetector swipe;
    private boolean swiped;
    private long lastMissAt;

    /** Disinstallazione di MG4 Clima (1.3.0): come nel launcher, ma senza toccare l'accessibilità (Android la toglie col pacchetto). */
    private void confirmUninstall() {
        sheet.confirm(L.s(R.string.s_uninstall), L.s(R.string.c_uninstall_msg), L.s(R.string.b_cancel), L.s(R.string.b_uninstall), () -> {
            sheet.close();
            uninstallSelf();
        });
    }

    private void uninstallSelf() {
        Store.event(this, "disinstallazione richiesta");
        final String action = "com.mg4.clima.UNINSTALL_RESULT";
        try {
            android.content.pm.PackageInstaller inst = getPackageManager().getPackageInstaller();
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, Intent i) {
                    int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,
                            android.content.pm.PackageInstaller.STATUS_FAILURE);
                    String msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Store.event(ctx, "disinstallazione esito " + st + (msg == null ? "" : " " + msg));
                    if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                ctx.startActivity(confirm);
                                return;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS)
                        h.post(() -> toast(L.f(R.string.a_uninstall_failed, msg == null ? "codice " + st : msg)));
                }
            }, new android.content.IntentFilter(action));
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 0,
                    new Intent(action).setPackage(getPackageName()), android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            inst.uninstall(getPackageName(), pi.getIntentSender());
        } catch (Throwable e) {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable e2) {
                toast(L.f(R.string.a_uninstall_failed, e2.getClass().getSimpleName()));
            }
        }
    }

    /**
     * Chiusura con swipe dentro il pannello (1.3.1, come MG4 Swipe): GestureDetector.onFling, almeno 100 px in
     * verticale e velocità 100, ovunque parta. Osserva il tocco senza consumarlo, così i tasti restano uguali; solo
     * lo swipe riconosciuto consuma il sollevamento del dito (niente tocco su un tasto). Con le impostazioni aperte no.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int mode = Store.swipeMode(this);
        if (mode != Store.SWIPE_OFF && swipe != null && (sheet == null || !sheet.showing())) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                swiped = false;
                downY = e.getRawY();
                downX = e.getRawX();
            }
            swipe.onTouchEvent(e);
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                if (swiped) {
                    close(swipeHow);
                    return true;
                }
                // gesto verticale lungo ma non valido (troppo lento o storto): nel log, al massimo ogni 3 s
                float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                long now = android.os.SystemClock.elapsedRealtime();
                if (Math.abs(dy) >= 60 && Math.abs(dy) > Math.abs(dx) && now - lastMissAt > 3000) {
                    lastMissAt = now;
                    Store.event(this, "chiusura: swipe " + (dy < 0 ? "verso l'alto" : "verso il basso") + " nel pannello ("
                            + Math.round(Math.abs(dy)) + " px) non riconosciuto (modo " + Store.swipeName(mode) + ")");
                }
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private float downX, downY;
    private String swipeHow = "";

    private boolean onSwipe(android.view.MotionEvent e1, android.view.MotionEvent e2, float vy) {
        if (e1 == null) return false;
        float dy = e2.getRawY() - e1.getRawY(), dx = e2.getRawX() - e1.getRawX();
        int mode = Store.swipeMode(this);
        boolean up = dy < 0;
        boolean dirOk = mode == Store.SWIPE_AUTO || (mode == Store.SWIPE_UP && up) || (mode == Store.SWIPE_DOWN && !up);
        // 1.3.3: 60 px (prima 100): i tocchi brevi sono 3–13 px, quindi non si confondono
        boolean ok = Math.abs(dy) > 60 && Math.abs(vy) > 100 && Math.abs(dy) > Math.abs(dx) * 1.5f && dirOk;
        String g = "swipe " + (up ? "verso l'alto" : "verso il basso") + " nel pannello (" + Math.round(Math.abs(dy))
                + " px, velocità " + Math.round(Math.abs(vy)) + ")";
        if (!ok) {
            // 1.3.3: i tocchi brevi (sotto i 30 px) non sono swipe: non vanno nel log
            if (Math.abs(dy) >= 30) {
                lastMissAt = android.os.SystemClock.elapsedRealtime();
                Store.event(this, "chiusura: " + g + " non riconosciuto (modo " + Store.swipeName(mode) + ")");
            }
            return false;
        }
        swiped = true;
        swipeHow = g;
        return true;
    }

    /** Chiudendo si torna alla home (sotto c'è l'app clima originale, che non si vuole rivedere). */
    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.showing()) {
            assignStep = -1;   // associazione del joystick lasciata a metà: si ricomincia da "Associa"
            sheet.close();
            return;
        }
        boolean edge = android.os.SystemClock.elapsedRealtime() - ClimaGuard.edgeBackAt < 1500;
        ClimaGuard.edgeBackAt = 0;
        close(edge ? "swipe dal bordo" : "tasto indietro");
    }

    /** 1.3.1: ogni chiusura va nel log con il modo in cui è avvenuta. */
    private void close(String how) {
        if (sheet != null && sheet.showing()) sheet.close();
        Store.event(this, "chiusura: " + how + " → chiusa");
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
        finish();
    }
}
