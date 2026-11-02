package com.mg4.clima;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Impostazioni (in device-protected storage: leggibili anche prima dello sblocco, così non si va mai in crash
 * all'accensione) e diagnostica su file (Android/data/com.mg4.clima/files/diagnostica.txt).
 */
public final class Store {
    private static final String TAG = "MG4Clima";

    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().createDeviceProtectedStorageContext()
                .getSharedPreferences("mg4clima", Context.MODE_PRIVATE);
    }

    /** Sostituire l'app clima originale quando si apre (interruttore). */
    public static boolean replace(Context c) {
        return sp(c).getBoolean("sostituisci", true);
    }

    public static void setReplace(Context c, boolean on) {
        sp(c).edit().putBoolean("sostituisci", on).apply();
    }

    /** Chiudere il menù a tendina che il sistema apre quando si accende/spegne il clima. */
    /** Pannello clima di sistema (1.1.0): 0 lascia, 1 chiudi solo con MG4 Clima aperto, 2 chiudi sempre. */
    public static final int PANEL_LEAVE = 0, PANEL_WITH_APP = 1, PANEL_ALWAYS = 2;

    public static int panelMode(Context c) {
        SharedPreferences s = sp(c);
        if (s.contains("pannelloModo")) return s.getInt("pannelloModo", PANEL_ALWAYS);
        return s.getBoolean("chiudiTendina", true) ? PANEL_ALWAYS : PANEL_LEAVE;   // come prima della 1.1.0
    }

    public static void setPanelMode(Context c, int m) {
        sp(c).edit().putInt("pannelloModo", m).putBoolean("chiudiTendina", m != PANEL_LEAVE).apply();
    }

    /** Lingua (1.2.0): "auto" = quella dell'auto, altrimenti il codice scelto (it, en, …). */
    public static String language(Context c) {
        return sp(c).getString("lingua", L.AUTO);
    }

    public static void setLanguage(Context c, String code) {
        sp(c).edit().putString("lingua", code).apply();
    }

    /** Stile del cruscotto (1.2.0): 0 disegnato, 1 realistico (foto). */
    public static final int STYLE_DRAWN = 0, STYLE_PHOTO = 1;

    public static int dashStyle(Context c) {
        return sp(c).getInt("stileCruscotto", STYLE_DRAWN);
    }

    public static void setDashStyle(Context c, int s) {
        sp(c).edit().putInt("stileCruscotto", s).apply();
    }

    /** Tasti Parabrezza e Lunotto nella fila in basso (1.2.0, spenti di default: ci sono i tasti fisici). */
    public static boolean defrostTiles(Context c) {
        return sp(c).getBoolean("tastiSbrinamento", false);
    }

    public static void setDefrostTiles(Context c, boolean on) {
        sp(c).edit().putBoolean("tastiSbrinamento", on).apply();
    }

    /** Aria animata nella schermata del clima (1.1.0, accesa di default). */
    public static boolean animatedAir(Context c) {
        return sp(c).getBoolean("ariaAnimata", true);
    }

    public static void setAnimatedAir(Context c, boolean on) {
        sp(c).edit().putBoolean("ariaAnimata", on).apply();
    }

    public static boolean closeShade(Context c) {
        return panelMode(c) != PANEL_LEAVE;
    }

    public static void setCloseShade(Context c, boolean on) {
        setPanelMode(c, on ? PANEL_ALWAYS : PANEL_LEAVE);
    }

    /** Modello scelto a mano nelle impostazioni ("" = riconosciuto in automatico). */
    public static String model(Context c) {
        return sp(c).getString("modello", "");
    }

    public static void setModel(Context c, String m) {
        sp(c).edit().putString("modello", m == null ? "" : m).commit();
    }

    /** Guida a destra (la schermata si specchia). */
    public static boolean rightHand(Context c) {
        return sp(c).getBoolean("guidaDestra", false);
    }

    public static void setRightHand(Context c, boolean on) {
        sp(c).edit().putBoolean("guidaDestra", on).apply();
    }

    /**
     * Chiusura con swipe: 0 spenta, 1 dal basso verso l'alto, 2 dall'alto verso il basso, 3 automatica (1.3.1: swipe
     * verticale in entrambe le direzioni e da entrambi i bordi). 1.3.1: automatica di default (prima spenta).
     */
    public static final int SWIPE_OFF = 0, SWIPE_UP = 1, SWIPE_DOWN = 2, SWIPE_AUTO = 3;

    public static int swipeMode(Context c) {
        int m = sp(c).getInt("swipeMode", SWIPE_AUTO);
        return m >= SWIPE_OFF && m <= SWIPE_AUTO ? m : SWIPE_AUTO;
    }

    static String swipeName(int m) {
        return m == SWIPE_OFF ? "spenta" : m == SWIPE_UP ? "verso l'alto" : m == SWIPE_DOWN ? "verso il basso" : "automatica";
    }

    /** Versione per cui è già stata chiesta la verifica all'apertura (1.3.1: una volta per versione). */
    public static String askedVerify(Context c) {
        return sp(c).getString("verificaChiesta", "");
    }

    public static void setAskedVerify(Context c, String v) {
        sp(c).edit().putString("verificaChiesta", v == null ? "" : v).apply();
    }

    /** 1.3.3: parti già fatte del test / della verifica interrotti ("" = niente da riprendere). */
    public static String testProgress(Context c, String kind) {
        return sp(c).getString("ripresa_" + kind, "");
    }

    public static void setTestProgress(Context c, String kind, String json) {
        sp(c).edit().putString("ripresa_" + kind, json == null ? "" : json).apply();
    }

    /**
     * Comandi dal joystick del volante: 0 spenti, 1 accesi (joystick destro). 1.4.0: la scelta "joystick sinistro" non
     * c'è più (nei log manda gli stessi codici del destro): chi l'aveva scelta passa al destro. Di default accesi, ma
     * spenti se su questo software i tasti non si possono tenere in esclusiva (arrivano anche alla musica).
     */
    public static final int JOY_OFF = 0, JOY_RIGHT = 1, JOY_LEFT = 2;

    public static int joystick(Context c) {
        SharedPreferences s = sp(c);
        if (s.contains("joystick")) return s.getInt("joystick", JOY_RIGHT) == JOY_OFF ? JOY_OFF : JOY_RIGHT;
        return joyExclusive(c) == 0 ? JOY_OFF : JOY_RIGHT;
    }

    public static void setJoystick(Context c, int m) {
        sp(c).edit().putInt("joystick", m == JOY_OFF ? JOY_OFF : JOY_RIGHT).apply();
    }

    /** 1.4.0: codici di fabbrica del joystick destro (14 associazioni nei log, SWI68 e SWI69: tutte uguali). */
    static final int[] FACTORY_RIGHT = {297, 298, 299, 300, 301};   // su, giù, sinistra, destra, ok

    /** Codice del tasto associato a un'azione del joystick (su, giu, sinistra, destra, ok), -1 se non associato. */
    public static int joyCode(Context c, int side, String action) {
        int v = sp(c).getInt("joy" + side + "_" + action, -1);
        if (v >= 0 || side != JOY_RIGHT) return v;
        for (int i = 0; i < Joystick.ACTIONS.length; i++) if (Joystick.ACTIONS[i].equals(action)) return FACTORY_RIGHT[i];
        return -1;
    }

    /** L'associazione del joystick destro è quella di fabbrica (nessuna associazione salvata a mano). */
    public static boolean joyFactory(Context c) {
        for (String a : Joystick.ACTIONS) if (sp(c).contains("joy" + JOY_RIGHT + "_" + a)) return false;
        return true;
    }

    public static void setJoyCode(Context c, int side, String action, int code) {
        sp(c).edit().putInt("joy" + side + "_" + action, code).apply();
    }

    public static void clearJoy(Context c, int side) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String a : Joystick.ACTIONS) e.remove("joy" + side + "_" + a);
        e.apply();
    }

    /**
     * 1.4.0: esclusiva dei tasti del volante su questo software dell'auto: -1 non ancora provata, 1 riuscita
     * (con il filtro attivo le altre app non ricevono più i tasti), 0 non possibile (li ricevono lo stesso).
     */
    public static int joyExclusive(Context c) {
        return sp(c).getInt("joyEsclusiva_" + Model.software(), -1);
    }

    public static void setJoyExclusive(Context c, int v, String detail) {
        sp(c).edit().putInt("joyEsclusiva_" + Model.software(), v).putString("joyEsclusivaDettaglio", detail).apply();
    }

    public static String joyExclusiveDetail(Context c) {
        return sp(c).getString("joyEsclusivaDettaglio", "");
    }

    /** 1.4.0: scritte sotto le icone dei pulsanti (accese di default); spente = solo icone più grandi. */
    public static boolean showLabels(Context c) {
        return sp(c).getBoolean("scritte", true);
    }

    public static void setShowLabels(Context c, boolean on) {
        sp(c).edit().putBoolean("scritte", on).apply();
    }

    /**
     * 1.4.0: valore (in decimi di grado) da mandare all'auto per LO e HI sul servizio caradapter, scelto dalla verifica
     * per quella versione dell'app clima originale. NaN = nessun valore funziona: LO / HI nascosti. null = mai provato.
     */
    public static Float tempLimit(Context c, String which, String version) {
        String k = "limite" + which + "_" + version;
        SharedPreferences s = sp(c);
        if (!s.contains(k)) return null;
        return s.getFloat(k, Float.NaN);
    }

    public static void setTempLimit(Context c, String which, String version, float v) {
        sp(c).edit().putFloat("limite" + which + "_" + version, v).apply();
    }

    /** 1.4.0: posizione e misura della schermata sullo schermo (per la finestra pronta che copre il lampeggio). */
    public static int[] screenRect(Context c) {
        String v = sp(c).getString("riquadroSchermata", "");
        String[] p = v.split(",");
        if (p.length != 4) return null;
        try {
            int[] r = new int[4];
            for (int i = 0; i < 4; i++) r[i] = Integer.parseInt(p[i]);
            return r[2] > 100 && r[3] > 100 ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setScreenRect(Context c, int x, int y, int w, int h) {
        String v = x + "," + y + "," + w + "," + h;
        if (!v.equals(sp(c).getString("riquadroSchermata", ""))) sp(c).edit().putString("riquadroSchermata", v).apply();
    }

    /** 1.4.0: finestra pronta all'apertura al posto dell'app originale (accesa di default). */
    public static boolean quickCover(Context c) {
        return sp(c).getBoolean("finestraPronta", true);
    }

    public static void setQuickCover(Context c, boolean on) {
        sp(c).edit().putBoolean("finestraPronta", on).apply();
    }

    /** 1.4.0: ultima versione di cui è stato mostrato il popup "Novità". */
    public static int newsSeen(Context c) {
        return sp(c).getInt("novitaViste", 0);
    }

    public static void setNewsSeen(Context c, int code) {
        sp(c).edit().putInt("novitaViste", code).apply();
    }

    /** Prima installazione (nessuna impostazione salvata): niente popup Novità. Da chiamare all'avvio. */
    public static boolean fresh(Context c) {
        return sp(c).getAll().isEmpty();
    }

    /** Ora con i millisecondi, per misurare il lampeggio (1.3.1). */
    public static String ms(long wall) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY).format(new Date(wall));
    }

    public static void setSwipeMode(Context c, int m) {
        sp(c).edit().putInt("swipeMode", m).apply();
    }

    /** Versioni dell'app clima originale verificate su quest'auto (1.3.0). */
    public static boolean isVerified(Context c, String v) {
        return ("|" + sp(c).getString("verificate", "") + "|").contains("|" + v + "|");
    }

    public static void addVerified(Context c, String v) {
        if (v == null || v.isEmpty() || isVerified(c, v)) return;
        String old = sp(c).getString("verificate", "");
        sp(c).edit().putString("verificate", old.isEmpty() ? v : old + "|" + v).apply();
    }

    /** Versione vista ma non ancora verificata ("" = nessuna). */
    public static String pendingVerify(Context c) {
        return sp(c).getString("daVerificare", "");
    }

    public static void setPendingVerify(Context c, String v) {
        sp(c).edit().putString("daVerificare", v == null ? "" : v).apply();
    }

    /** Fino a quando la sostituzione è sospesa (modalità officina); 0 = mai. */
    public static long bypassUntil(Context c) {
        return sp(c).getLong("bypassFinoA", 0);
    }

    /** Per un po' l'app clima originale non viene sostituita (aperta apposta). */
    public static void bypass(Context c, long ms) {
        sp(c).edit().putLong("bypassFinoA", System.currentTimeMillis() + ms).apply();
    }

    public static boolean bypassed(Context c) {
        return System.currentTimeMillis() < sp(c).getLong("bypassFinoA", 0);
    }

    /** Codice anonimo di questa installazione (per la cartella dei log). */
    public static String installId(Context c) {
        String id = sp(c).getString("id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().substring(0, 8);
            sp(c).edit().putString("id", id).apply();
        }
        return id;
    }

    // ---------------------------------------------------------------------- diagnostica

    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static String lastState = "";
    private static long lastWrite;

    private static int eventCount, sentCount;

    /** Ci sono eventi nuovi dall'ultimo invio dei log. */
    public static synchronized boolean unsent() {
        return eventCount != sentCount;
    }

    public static synchronized void markSent() {
        sentCount = eventCount;
    }

    public static synchronized void event(Context c, String what) {
        eventCount++;
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date()) + "  " + what;
        Log.i(TAG, what);
        EVENTS.addLast(line);
        while (EVENTS.size() > 400) EVENTS.removeFirst();
        write(c, true);
    }

    /** Stato letto dall'auto: si scrive solo se cambia (o al massimo ogni 30 s). */
    public static synchronized void diag(Context c, String state) {
        boolean changed = !state.equals(lastState);
        lastState = state;
        if (changed || System.currentTimeMillis() - lastWrite > 30_000) write(c, changed);
    }

    public static synchronized String text(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("MG4 Clima ").append(BuildInfo.version(c)).append(" · ")
                .append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(new Date())).append('\n');
        sb.append("modello: ").append(Model.get(c)).append(Model.manual(c) ? " (scelto a mano)" : " (riconosciuto: " + Model.how(c) + ")")
                .append(" · software ").append(Model.software().isEmpty() ? "?" : Model.software()).append('\n');
        int pm = panelMode(c);
        sb.append("lingua: ").append(language(c)).append(" · cruscotto: ").append(dashStyle(c) == STYLE_PHOTO ? "realistico" : "disegnato")
                .append(" · tasti parabrezza/lunotto: ").append(defrostTiles(c) ? "sì" : "no").append('\n');
        sb.append("chiusura pannello/tendina: ").append(pm == PANEL_ALWAYS ? "sempre" : pm == PANEL_WITH_APP ? "solo con MG4 Clima aperto" : "spenta")
                .append(Model.sysUiPanel(c) ? " · interfaccia pannello di sistema: " + SysUiPanel.interfaceName() : "").append('\n');
        sb.append("chiusura con swipe: ").append(swipeName(swipeMode(c))).append('\n');
        int js = joystick(c);
        sb.append("comandi dal joystick: ").append(js == JOY_OFF ? "spenti" : "joystick destro")
                .append(" (").append(Joystick.mapText(c, JOY_RIGHT)).append(joyFactory(c) ? ", di fabbrica" : ", associato a mano").append(')')
                .append(joyCode(c, JOY_LEFT, "su") >= 0 ? " · sinistro: " + Joystick.mapText(c, JOY_LEFT) : "").append('\n');
        sb.append("joystick.esclusiva = ").append(Joystick.exclusiveLine(c)).append('\n');
        sb.append("scritte sui pulsanti: ").append(showLabels(c) ? "sì" : "no").append(" · finestra pronta: ")
                .append(quickCover(c) ? "sì" : "no").append('\n');
        sb.append("app clima originale: ").append(ClimaGuard.originalLine(c)).append('\n');
        if (!Model.saic(c)) sb.append("temperatura (verifica): ").append(CarAdapterBackend.limitsText()).append('\n');
        sb.append("sostituzione: ").append(replace(c) ? "attiva" : "spenta")
                .append(bypassed(c) ? " (sospesa: app originale aperta apposta)" : "")
                .append(" · accessibilità: ").append(ClimaGuard.enabledState(c)).append('\n');
        sb.append("\n== STATO LETTO ==\n").append(lastState).append('\n');
        sb.append("\n== EVENTI ==\n");
        for (String e : EVENTS) sb.append(e).append('\n');
        return sb.toString();
    }

    private static void write(Context c, boolean log) {
        lastWrite = System.currentTimeMillis();
        try {
            File dir = c.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) return;
            try (FileOutputStream o = new FileOutputStream(new File(dir, "diagnostica.txt"))) {
                o.write(text(c).getBytes("UTF-8"));
            }
        } catch (Throwable e) {
            if (log) Log.w(TAG, "diagnostica: " + e);
        }
    }
}
