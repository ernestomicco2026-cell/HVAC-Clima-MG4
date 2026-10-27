package com.mg4.clima;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Schermata clima in stile Tesla (grafica approvata): cruscotto MG4 2022 stilizzato al centro, comandi sul lato
 * guida (accensione, temperatura, ventola), tasti in basso. Disegnata su una tela 1920×720 e scalata allo schermo.
 * Con la guida a destra tutto si specchia; i testi restano leggibili.
 */
public final class ClimaView extends View {
    public interface Listener {
        void onTap(String id);
    }

    private static final float DW = 1920, DH = 720, W = 1880;

    private HvacClient.State st = new HvacClient.State();
    private boolean rhd, day;
    private Listener listener;

    // colori (tema notte/giorno del launcher: nessun accento, attivo = colore del testo)
    int BG, PANEL, CHIP, TRACK, TEXT, SUB;
    private int ONTEXT, HEAT, HEATBG, FLOW, FLOW2;
    private int GLASS1, GLASS2, TOP1, TOP2, FACE1, FACE2, PILLAR, LINE, DARK, BEZEL;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final List<Object[]> hits = new ArrayList<>();   // {id, RectF} in coordinate del pannello
    private final Matrix toDesign = new Matrix();
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    public ClimaView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        theme();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ---- aria animata (1.1.0): circa 25 ridisegni al secondo solo con clima acceso, una direzione attiva e lo
    // schermo visibile (con la vista nascosta Android non chiama onDraw e il ciclo si ferma da solo)
    private boolean animated = true;
    private float phase, warmth = -1;
    private long animStart;

    public void setAnimated(boolean on) {
        animated = on;
        invalidate();
    }

    private static int mix(int a, int b, float t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    // ---- cruscotto a immagini (1.2.0): "Disegnato" (vettoriale) o "Realistico" (foto), a strati: fondo, aria, volante
    private int style = Store.STYLE_DRAWN;
    private boolean defrostTiles;
    private Bitmap imgBase, imgWheel, imgWheelHot;
    private int loadedStyle = -1;
    private String loadedPre = "";
    private boolean imgFailed;
    private int flowColor;   // colore attuale dell'aria (passa gradualmente a quello nuovo)
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** Posizione del cruscotto (1420×520) nella tela 1920×720 (coordinate del pannello). */
    private static final float IX = 450, IY = 2, IW = 1420, IH = 520;

    public void setDashStyle(int s) {
        style = s;
        invalidate();
    }

    public void setDefrostTiles(boolean on) {
        defrostTiles = on;
        invalidate();
    }

    private Bitmap asset(String name) {
        try (java.io.InputStream in = getContext().getAssets().open("cruscotto/" + name + ".webp")) {
            return BitmapFactory.decodeStream(in);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Carica gli strati dello stile scelto (solo quello: l'altro si libera). */
    private boolean ensureImages() {
        String pre = style == Store.STYLE_PHOTO ? "realistico" : day ? "disegnato_chiaro" : "disegnato";
        if (loadedStyle == style && loadedPre.equals(pre) && imgBase != null) return true;
        if (imgFailed && loadedStyle == style && loadedPre.equals(pre)) return false;
        Bitmap b = asset(pre + "_base"), w = asset(pre + "_volante"), h = asset(pre + "_volante_caldo");
        loadedStyle = style;
        loadedPre = pre;
        if (b == null || w == null || h == null) {
            imgFailed = true;
            Store.event(getContext(), "cruscotto " + pre + ": immagini non caricate, uso il disegno classico");
            return false;
        }
        imgFailed = false;
        imgBase = b;
        imgWheel = w;
        imgWheelHot = h;
        return true;
    }

    /** Geometria di ogni stile, in pixel dell'immagine 1420×520. */
    private static final class Geo {
        float[][] face;     // {x0, x1, y, verso}
        float[][] feet;     // {x, y}
        float glassY0, glassY1, glassMid, glassSpread, glassSkip0, glassSkip1;
        float barX, barY, barW, clockX, clockY, clockSize;
        float tempX, tempY, tempSize, outX, outY, outSize;
        float dotX, dotY, dotR, l1X, l1Y, l1W, l2Y, l2W;
    }

    private static final Geo DRAWN = new Geo(), PHOTO = new Geo();

    static {
        Geo g = DRAWN;
        g.face = new float[][]{{20, 180, 312, -1}, {520, 900, 312, 0}, {1240, 1400, 312, 1}};
        g.feet = new float[][]{{300, 400}, {1180, 392}};
        g.glassY0 = 146; g.glassY1 = 18; g.glassMid = 700; g.glassSpread = 560; g.glassSkip0 = 500; g.glassSkip1 = 920;
        g.barX = 252; g.barY = 146; g.barW = 56; g.clockX = 330; g.clockY = 180; g.clockSize = 30;
        g.tempX = 548; g.tempY = 112; g.tempSize = 44; g.outX = 550; g.outY = 150; g.outSize = 18;
        g.dotX = 760; g.dotY = 96; g.dotR = 20; g.l1X = 794; g.l1Y = 86; g.l1W = 84; g.l2Y = 102; g.l2W = 56;
        g = PHOTO;
        g.face = new float[][]{{20, 120, 250, -1}, {565, 830, 262, 0}, {1205, 1370, 262, 1}};
        g.feet = new float[][]{{270, 330}, {1120, 300}};
        g.glassY0 = 96; g.glassY1 = 8; g.glassMid = 700; g.glassSpread = 600; g.glassSkip0 = 540; g.glassSkip1 = 860;
        g.barX = 290; g.barY = 132; g.barW = 50; g.clockX = 352; g.clockY = 196; g.clockSize = 26;
        g.tempX = 592; g.tempY = 132; g.tempSize = 40; g.outX = 594; g.outY = 164; g.outSize = 16;
        g.dotX = 735; g.dotY = 118; g.dotR = 17; g.l1X = 760; g.l1Y = 110; g.l1W = 48; g.l2Y = 124; g.l2W = 34;
    }

    /** Aria: riscalda (impostata oltre 3° sopra l'esterna), raffresca (oltre 3° sotto) o mantiene. */
    static final int MODE_KEEP = 0, MODE_HEAT = 1, MODE_COOL = 2;
    static final int C_HEAT = 0xFFFF8A4D, C_COOL = 0xFF7CC4FF, C_KEEP = 0xFFE8EDF3;

    /** Temperatura impostata → intensità del colore: -1 blu pieno (16 °), 0 neutro (21 °), +1 arancio pieno (26 °). Graduale. */
    static float heatFactor(HvacClient.State s) {
        if (s.temp < 0) return 0f;
        float f = (s.temp - 21f) / 5f;
        return f < -1f ? -1f : f > 1f ? 1f : f;
    }

    /** Colore della temperatura e dell'aria: scorre gradualmente da blu a neutro ad arancio. */
    static int tempColor(HvacClient.State s) {
        float f = heatFactor(s);
        return f < 0 ? mix(C_KEEP, C_COOL, -f) : mix(C_KEEP, C_HEAT, f);
    }

    /** Parola (riscalda / mantiene / raffresca), coerente col colore. */
    static int thermalMode(HvacClient.State s) {
        float f = heatFactor(s);
        return f > .25f ? MODE_HEAT : f < -.25f ? MODE_COOL : MODE_KEEP;
    }

    private static int modeColor(int m) {
        return m == MODE_HEAT ? C_HEAT : m == MODE_COOL ? C_COOL : C_KEEP;
    }

    /** Un soffio: traccia tenue fissa + tratti che scorrono. */
    private void stream(Canvas c, Path pa, float w, int color, float[] dash, float offset) {
        stroke(color, w);
        p.setAlpha(56);
        c.drawPath(pa, p);
        stroke(color, w);
        p.setAlpha(animated ? 242 : 200);
        p.setPathEffect(new DashPathEffect(dash, animated ? offset : 0));
        c.drawPath(pa, p);
        p.setPathEffect(null);
    }

    /** Cruscotto a immagini: tutto in pixel dell'immagine, specchiato con la guida a destra. */
    private void drawImageDash(Canvas c, boolean on, boolean[] dir, int fan, int col, boolean frontDef, boolean wheelHot) {
        Geo g = style == Store.STYLE_PHOTO ? PHOTO : DRAWN;
        c.save();
        if (rhd) {
            c.translate(W, 0);
            c.scale(-1, 1);
        }
        c.translate(IX, IY);
        r.set(0, 0, IW, IH);
        path.reset();
        path.addRoundRect(r, 26, 26, Path.Direction.CW);
        c.clipPath(path);
        r.set(0, 0, IW, IH);
        c.drawBitmap(imgBase, null, r, bp);
        float sp = 70 + fan * 22;
        // parabrezza: aria che sale dal cruscotto (dietro agli schermi) e righe calde se lo sbrinamento è acceso
        if (on && dir[0]) for (int k = 0; k < 10; k++) {
            float t = (k - 4.5f) / 4.5f, x0 = g.glassMid + t * g.glassSpread;
            if (x0 > g.glassSkip0 && x0 < g.glassSkip1) continue;
            float x2 = x0 + t * 70;
            path.reset();
            path.moveTo(x0, g.glassY0);
            path.cubicTo(x0, g.glassY0 - (g.glassY0 - g.glassY1) * .28f, x2 - t * 30, g.glassY1 + (g.glassY0 - g.glassY1) * .3f, x2, g.glassY1);
            stream(c, path, 4, col, new float[]{22, 30}, -(phase * sp * .8f) + k * 17);
        }
        if (frontDef) for (int k = 0; k < 9; k++) {
            float x = 160 + k * 135;
            if (x > g.glassSkip0 - 20 && x < g.glassSkip1 + 20) continue;
            path.reset();
            path.moveTo(x, g.glassY0 - 6);
            path.rQuadTo(8, -10, 0, -20); path.rQuadTo(-8, -10, 0, -20); path.rQuadTo(8, -10, 0, -20);
            stroke(HEAT, 3);
            p.setAlpha(217);
            c.drawPath(path, p);
        }
        // schermi: solo le nostre indicazioni
        rr(c, g.barX, g.barY, g.barW, 7, 3.5f, 0xFF34C77B, 0, 0);
        String time = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
        int scr = day ? 0xFF111318 : 0xFFF2F5FA;
        flatText(c, time, g.clockX, g.clockY, g.clockSize, scr, Paint.Align.CENTER);
        flatText(c, tempText(), g.tempX, g.tempY, g.tempSize, scr, Paint.Align.LEFT);
        if (!Float.isNaN(st.outTemp)) flatText(c, L.f(R.string.v_out_short, Math.round(st.outTemp)), g.outX, g.outY, g.outSize, 0xFF9BA1A9, Paint.Align.LEFT);
        fill(0xFF262A30);
        c.drawCircle(g.dotX, g.dotY, g.dotR, p);
        rr(c, g.l1X, g.l1Y, g.l1W, 7, 3.5f, 0xFF3A3F47, 0, 0);
        rr(c, g.l1X, g.l2Y, g.l2W, 6, 3, 0xFF262A30, 0, 0);
        // viso: dalle bocchette vere, dietro al volante
        if (on && dir[1]) {
            int n = 3 + Math.round(fan / 3f);
            for (float[] v : g.face) for (int k = 0; k < n; k++) {
                float t = (k - (n - 1) / 2f) / Math.max(1, (n - 1) / 2f);
                float x = (v[0] + v[1]) / 2 + t * (v[1] - v[0]) / 2 * .85f, y = v[2], len = 90 + fan * 9;
                float x2 = x + t * 70 + v[3] * 90, y2 = y + len;
                path.reset();
                path.moveTo(x, y);
                path.cubicTo(x, y + len * .35f, x2 - t * 25, y2 - len * .45f, x2, y2);
                stream(c, path, 3.5f + (1 - Math.abs(t)) * 3, col, new float[]{30, 38}, -(phase * sp) + k * 23 + v[0] * .37f);
            }
        }
        if (on && dir[2]) for (float[] f : g.feet) for (int k = 0; k < 5; k++) {
            float off = (k - 2) * 28, y0 = f[1];
            path.reset();
            path.moveTo(f[0] + off, y0);
            path.cubicTo(f[0] + off, y0 + 40, f[0] + off * 1.4f, y0 + (IH - y0) * .6f, f[0] + off * 1.9f, IH);
            stream(c, path, 4.5f, col, new float[]{3, 12}, -(phase * sp * .45f) + k * 5);
        }
        // volante (normale o riscaldato) sopra l'aria
        r.set(0, 0, IW, IH);
        c.drawBitmap(wheelHot ? imgWheelHot : imgWheel, null, r, bp);
        c.restore();
    }

    /** Testo dentro il cruscotto specchiato: resta leggibile (con la guida a destra l'allineamento si inverte). */
    private void flatText(Canvas c, String s, float x, float y, float size, int color, Paint.Align a) {
        if (rhd) {
            c.save();
            c.scale(-1, 1, x, 0);
            if (a == Paint.Align.LEFT) a = Paint.Align.RIGHT;
            else if (a == Paint.Align.RIGHT) a = Paint.Align.LEFT;
        }
        text(c, s, x, y, size, color, a, regular);
        if (rhd) c.restore();
    }

    public void setRightHand(boolean on) {
        rhd = on;
        invalidate();
    }

    public void setState(HvacClient.State s) {
        st = s;
        invalidate();
    }

    /** Tema giorno/notte come il launcher: giorno = colori chiari del launcher, notte = tinte blu. */
    public void theme() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        day = night == Configuration.UI_MODE_NIGHT_NO;
        if (day) {
            BG = 0xFFE6E8EB; PANEL = 0xFFFFFFFF; CHIP = 0xFFF1F3F5; TRACK = 0xFFE1E4E8; TEXT = 0xFF111318; SUB = 0xFF555C66;
            ONTEXT = 0xFFFFFFFF; HEAT = 0xFFE1621F; HEATBG = 0xFFFFF1EA; FLOW = 0xFF7D8793; FLOW2 = 0xFFB9C0C8;
            GLASS1 = 0xFFE9EEF4; GLASS2 = 0xFFD5DCE5; TOP1 = 0xFFD9DDE2; TOP2 = 0xFFC9CED4; FACE1 = 0xFFCDD2D8; FACE2 = 0xFFBFC5CC;
            PILLAR = 0xFFB8BEC6; LINE = 0xFFB0B6BE; DARK = 0xFF2A2E35; BEZEL = 0xFF8C939C;
        } else {
            BG = 0xFF080A10; PANEL = 0xFF11151D; CHIP = 0xFF1A202B; TRACK = 0xFF232B38; TEXT = 0xFFF2F5FA; SUB = 0xFF9AA5B5;
            ONTEXT = 0xFF0E1218; HEAT = 0xFFFF7A3D; HEATBG = 0xFF2E241F; FLOW = 0xFFD6E2F0; FLOW2 = 0xFF8E9BAE;
            GLASS1 = 0xFF121823; GLASS2 = 0xFF1E2634; TOP1 = 0xFF2A3443; TOP2 = 0xFF1D2431; FACE1 = 0xFF1A212C; FACE2 = 0xFF121821;
            PILLAR = 0xFF243040; LINE = 0xFF2C3746; DARK = 0xFF090C12; BEZEL = 0xFF334055;
        }
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        theme();
    }

    // ======================================================================================== disegno

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth() / DW, getHeight() / DH);
        float ox = (getWidth() - DW * s) / 2, oy = (getHeight() - DH * s) / 2;
        c.drawColor(BG);
        c.save();
        c.translate(ox, oy);
        c.scale(s, s);
        c.translate(20, 20);
        toDesign.reset();
        toDesign.postTranslate(-ox, -oy);
        toDesign.postScale(1 / s, 1 / s);
        toDesign.postTranslate(-20, -20);
        hits.clear();
        fill(PANEL);
        r.set(0, 0, W, 680);
        c.drawRoundRect(r, 28, 28, p);

        boolean usable = st.usable(), on = st.on();
        boolean[] dir = st.directions();
        int fan = st.fan == HvacClient.FAN_AUTO ? Math.max(1, st.fanMax / 2) : Math.max(0, st.fan);
        boolean warm = st.temp >= 25;
        float target = warm ? 1f : 0f;
        if (warmth < 0 || !animated) warmth = target;
        else if (Math.abs(warmth - target) > .02f) warmth += (target - warmth) * .12f;
        else warmth = target;
        int fl = mix(FLOW, 0xFFFFB27A, warmth), fl2 = mix(FLOW2, HEAT, warmth);
        // 1.2.0: colore dall'impostata rispetto all'esterna, con passaggio graduale
        int want = tempColor(st);
        boolean colorMoving = flowColor != want;
        if (flowColor == 0 || !animated) flowColor = want;
        else if (colorMoving) {
            int nc = mix(flowColor, want, .15f);
            flowColor = nc == flowColor ? want : nc;
        }
        long nowMs = android.os.SystemClock.uptimeMillis();
        if (animStart == 0) animStart = nowMs;
        phase = (nowMs - animStart) / 1000f;
        float cx = rhd ? 750 : 1130;

        // ---- cruscotto: a immagini (1.2.0); se le immagini non si caricano, il disegno classico di prima
        if (ensureImages()) {
            drawImageDash(c, on, dir, fan, flowColor, st.frontDefrost == 1, st.wheel == 3);
        } else {
            c.save();
            if (rhd) {
                c.translate(2 * cx, 0);
                c.scale(-1, 1);
            }
            drawDash(c, cx, on, dir, fan, fl, fl2, st.frontDefrost == 1, st.wheel == 3);
            c.restore();
            drawDashTexts(c, cx);
        }

        // ---- colonna lato guida
        drawDriverColumn(c, usable, on);

        // ---- chiudi (nell'angolo) e impostazioni accanto (lato passeggero)
        float xx = rhd ? 40 : W - 40 - 72;
        float gx = rhd ? 40 + 72 + 16 : W - 40 - 72 - 16 - 72;
        tile(c, xx, 40, 72, 72, 22, CHIP);
        icon(c, R.drawable.ic_close, xx + 9, 40 + 16, 54, 40, SUB);
        hit("chiudi", xx, 40, 72, 72);
        tile(c, gx, 40, 72, 72, 22, CHIP);
        icon(c, R.drawable.ic_gear, gx + 9, 40 + 16, 54, 40, SUB);
        hit("impostazioni", gx, 40, 72, 72);
        // sotto la colonna dei comandi: solo la direzione automatica (la versione è nelle impostazioni, 1.2.0)
        if (on && st.blowerMode == 7) text(c, L.s(R.string.v_dir_auto), rhd ? W - 40 : 40, 500, 17, SUB, rhd ? Paint.Align.RIGHT : Paint.Align.LEFT, regular);

        // ---- tasti in basso
        drawTiles(c, usable, on, dir);
        c.restore();
        // aria animata: prossimo fotogramma tra 40 ms se c'è aria che esce o se il colore sta ancora cambiando
        if (animated && (on && (dir[0] || dir[1] || dir[2]) || Math.abs(warmth - (warm ? 1f : 0f)) > .001f || colorMoving)) postInvalidateDelayed(40);
    }

    private void drawDash(Canvas c, float cx, boolean on, boolean[] dir, int fan, int fl, int fl2, boolean frontDef, boolean wheelHot) {
        // parabrezza e montanti
        path.reset();
        path.moveTo(cx - 505, 150); path.lineTo(cx - 405, 34); path.lineTo(cx + 405, 34); path.lineTo(cx + 505, 150); path.close();
        grad(0, 34, 0, 150, GLASS1, GLASS2);
        c.drawPath(path, p);
        p.setShader(null);
        stroke(PILLAR, 18);
        c.drawLine(cx - 505, 150, cx - 405, 34, p);
        c.drawLine(cx + 505, 150, cx + 405, 34, p);
        if (on && dir[0]) for (int k = 0; k < 11; k++) {
            float x = cx - 420 + k * 84, sp = (k - 5) * 10;
            path.reset();
            path.moveTo(x, 146);
            path.cubicTo(x + sp * .3f, 118, x + sp * .7f, 92, x + sp, 62 + Math.abs(k - 5) * 5);
            stroke(fl, 5);
            p.setAlpha(128);
            c.drawPath(path, p);
        }
        if (frontDef) for (int k = 0; k < 9; k++) {
            float x = cx - 340 + k * 85, y = 132 - Math.abs(k - 4) * 3;
            path.reset();
            path.moveTo(x, y);
            path.rQuadTo(8, -10, 0, -20); path.rQuadTo(-8, -10, 0, -20); path.rQuadTo(8, -10, 0, -20);
            stroke(HEAT, 3);
            p.setAlpha(217);
            c.drawPath(path, p);
        }
        // piano e frontale del cruscotto
        path.reset();
        path.moveTo(cx - 520, 205); path.quadTo(cx - 512, 152, cx - 462, 148); path.lineTo(cx + 462, 148);
        path.quadTo(cx + 512, 152, cx + 520, 205); path.close();
        grad(0, 148, 0, 205, TOP1, TOP2);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(cx - 520, 205); path.lineTo(cx + 520, 205); path.lineTo(cx + 506, 338);
        path.quadTo(cx, 352, cx - 506, 338); path.close();
        grad(0, 205, 0, 345, FACE1, FACE2);
        c.drawPath(path, p);
        p.setShader(null);
        stroke(PILLAR, 2);
        c.drawPath(path, p);
        stroke(LINE, 2);
        c.drawLine(cx - 516, 224, cx + 516, 224, p);
        // bocchette come la MG4: laterale guidatore, due centrali sotto lo schermo, laterale passeggero
        vent(c, cx - 508, cx - 404);
        vent(c, cx - 140, cx - 14);
        vent(c, cx + 14, cx + 140);
        vent(c, cx + 404, cx + 508);
        // quadro strumenti dietro il volante
        // aria al parabrezza (1.1.0): sale dal cruscotto verso il vetro, dietro agli schermi
        if (on && dir[0]) {
            float sp = (70 + fan * 22) * .8f;
            for (int k = 0; k < 9; k++) {
                float t = (k - 4) / 4f;
                float x0 = cx - 20 + t * 400, y0 = 176, x2 = x0 + t * 80, y2 = 46;
                path.reset();
                path.moveTo(x0, y0);
                path.cubicTo(x0, y0 - 40, x2 - t * 40, y2 + 40, x2, y2);
                if (animated) {
                    stroke(0xFF000000, 4);
                    p.setShader(new LinearGradient(x0, y0, x2, y2, (fl & 0xFFFFFF) | 0x33000000, fl2 & 0xFFFFFF, Shader.TileMode.CLAMP));
                    c.drawPath(path, p);
                    p.setPathEffect(new DashPathEffect(new float[]{22, 30}, -(phase * sp) + k * 17));
                }
                stroke(0xFF000000, 4.5f);
                p.setShader(new LinearGradient(x0, y0, x2, y2, (fl & 0xFFFFFF) | (animated ? 0xE6000000 : 0x99000000),
                        fl2 & 0xFFFFFF, Shader.TileMode.CLAMP));
                c.drawPath(path, p);
                p.setPathEffect(null);
                p.setShader(null);
            }
        }
        float wx = cx - 270, wy = 330;
        rr(c, wx - 92, 146, 184, 70, 12, DARK, BEZEL, 2);
        r.set(wx - 85, 153, wx + 85, 209);
        grad(wx - 85, 153, wx + 85, 209, 0xFF22262D, 0xFF0F1114);
        c.drawRoundRect(r, 8, 8, p);
        p.setShader(null);
        rr(c, wx - 74, 190, 40, 7, 3.5f, 0xFF262A30, 0, 0);
        rr(c, wx - 74, 190, 30, 7, 3.5f, 0xFF34C77B, 0, 0);
        rr(c, wx + 44, 168, 28, 28, 6, 0xFF1E2126, 0, 0);
        // schermo centrale flottante con supporto
        float sx = cx - 150, sw = 300;
        path.reset();
        path.moveTo(sx + sw / 2 - 26, 214); path.lineTo(sx + sw / 2 + 26, 214); path.lineTo(sx + sw / 2 + 36, 150);
        path.lineTo(sx + sw / 2 - 36, 150); path.close();
        fill(0xFF1A1C20);
        c.drawPath(path, p);
        rr(c, sx, 84, sw, 132, 14, DARK, BEZEL, 2.5f);
        r.set(sx + 8, 92, sx + sw - 8, 196);
        grad(sx + 8, 92, sx + sw - 8, 196, 0xFF22262D, 0xFF0F1114);
        c.drawRoundRect(r, 8, 8, p);
        p.setShader(null);
        float c1 = (sw - 40) * .45f, c2 = (sw - 40) * .55f;
        rr(c, sx + 16, 100, c1, 88, 6, 0xFF15171B, 0, 0);
        rr(c, sx + 24 + c1, 100, c2, 88, 6, 0xFF15171B, 0, 0);
        fill(0xFF262A30);
        c.drawCircle(sx + 50 + c1, 132, 14, p);
        rr(c, sx + 72 + c1, 124, 70, 7, 3.5f, 0xFF3A3F47, 0, 0);
        rr(c, sx + 72 + c1, 137, 46, 6, 3, 0xFF262A30, 0, 0);
        rr(c, sx + 8, 200, sw - 16, 10, 4, 0xFF15171B, 0, 0);
        for (int i = 0; i < 8; i++) rr(c, sx + 30 + i * 32, 203, 14, 4, 2, 0xFF3A3F47, 0, 0);
        // flussi verso il viso (1.1.0: animati, una traccia tenue e i soffi che scorrono; velocità dalla ventola)
        float speed = 70 + fan * 22;
        if (on && dir[1]) {
            float[] outlets = {cx - 456, cx - 77, cx + 77, cx + 456};
            int n = 3 + Math.round(fan / 3f);
            for (float o : outlets) {
                float sgn = (o - cx) / 520;
                for (int k = 0; k < n; k++) {
                    float t = (k - (n - 1) / 2f) / Math.max(1, (n - 1) / 2f);
                    float x0 = o + t * 34, y0 = 262, len = 110 + fan * 8;
                    float x2 = x0 + t * 80 + sgn * 110, y2 = y0 + len;
                    path.reset();
                    path.moveTo(x0, y0);
                    path.cubicTo(x0, y0 + len * .35f, x2 - t * 30, y2 - len * .45f, x2, y2);
                    float w = 3.5f + (1 - Math.abs(t)) * 3;
                    if (animated) {
                        stroke(0xFF000000, w);
                        p.setShader(new LinearGradient(x0, y0, x2, y2, (fl & 0xFFFFFF) | 0x40000000, fl2 & 0xFFFFFF, Shader.TileMode.CLAMP));
                        c.drawPath(path, p);
                        p.setPathEffect(new DashPathEffect(new float[]{34, 40}, -(phase * speed) + k * 23 + o * .37f));
                    }
                    stroke(0xFF000000, w);
                    p.setShader(new LinearGradient(x0, y0, x2, y2, (fl & 0xFFFFFF) | (animated ? 0xF2000000 : 0xD9000000),
                            fl2 & 0xFFFFFF, Shader.TileMode.CLAMP));
                    c.drawPath(path, p);
                    p.setPathEffect(null);
                    p.setShader(null);
                }
            }
        }
        // console sospesa: manopola rotante cromata a destra, tasti, piano liscio, profilo cromato, vano sotto
        drawConsole(c, sx + sw / 2);
        // aria ai piedi
        if (on && dir[2]) {
            float[][] feet = {{wx, 460}, {cx + 330, 350}};
            for (float[] f : feet) for (int k = 0; k < 5; k++) {
                float off = (k - 2) * 26, y0 = f[1];
                path.reset();
                path.moveTo(f[0] + off, y0);
                path.cubicTo(f[0] + off, y0 + 20, f[0] + off * 1.4f, y0 + (500 - y0) * .7f, f[0] + off * 1.8f, 500);
                stroke(fl, 5);
                p.setAlpha(204);
                p.setPathEffect(new DashPathEffect(new float[]{3, 11}, animated ? -(phase * speed * .45f) + k * 5 : 0));
                c.drawPath(path, p);
                p.setPathEffect(null);
            }
        }
        // piantone e volante
        rr(c, wx - 34, wy + 70, 68, 90, 12, day ? 0xFF8C939C : 0xFF1A1F29, 0, 0);
        drawWheel(c, wx, wy, wheelHot);
    }

    private void vent(Canvas c, float x1, float x2) {
        rr(c, x1, 240, x2 - x1, 20, 8, DARK, BEZEL, 1.5f);
        stroke(0xFF23262B, 3);
        for (float x = x1 + 12; x < x2 - 8; x += 14) c.drawLine(x, 244, x, 256, p);
        r.set(x1, 266, x2, 270);
        grad(0, 266, 0, 270, 0xFFC9CED6, 0xFF6E747D);
        c.drawRoundRect(r, 2, 2, p);
        p.setShader(null);
    }

    private void drawConsole(Canvas c, float mid) {
        float tw = 104, bw = 126, yT = 346, yP = 430, yB = 508;
        path.reset();
        path.moveTo(mid - tw + 14, yT); path.lineTo(mid + tw - 14, yT); path.quadTo(mid + tw, yT, mid + tw + 2, yT + 14);
        path.lineTo(mid + tw + 10, yP); path.lineTo(mid - tw - 10, yP); path.lineTo(mid - tw - 2, yT + 14);
        path.quadTo(mid - tw, yT, mid - tw + 14, yT); path.close();
        fill(0xFF24272C);
        c.drawPath(path, p);
        stroke(0xFF3A3F47, 2);
        c.drawPath(path, p);
        rr(c, mid - tw + 6, yT + 14, tw - 8, yP - yT - 30, 8, 0xFF1C1F23, 0xFF2E3238, 1.5f);
        float kx = mid + 34, ky = yT + 46;
        fill(0xFF14161A);
        r.set(kx - 28, ky + 17, kx + 28, ky + 35);
        c.drawOval(r, p);
        path.reset();
        path.moveTo(kx - 26, ky); path.lineTo(kx - 26, ky + 22);
        r.set(kx - 26, ky + 14, kx + 26, ky + 30);
        path.arcTo(r, 180, -180);
        path.lineTo(kx + 26, ky); path.close();
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(kx - 26, 0, kx + 26, 0, new int[]{0xFF6E747D, 0xFFE3E7EC, 0xFF9AA0A8, 0xFF5E636B},
                new float[]{0, .35f, .6f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        stroke(0x33000000, 1);
        for (int i = -22; i <= 22; i += 4) {
            float h = (float) Math.sqrt(Math.max(0, 1 - Math.pow(i / 26f, 2))) * 8;
            c.drawLine(kx + i, ky + 2, kx + i, ky + 20 + h, p);
        }
        r.set(kx - 26, ky - 8, kx + 26, ky + 8);
        fill(0xFF2A2E35);
        c.drawOval(r, p);
        stroke(0xFFC9CED6, 2);
        c.drawOval(r, p);
        r.set(kx - 17, ky - 5, kx + 17, ky + 5);
        fill(0xFF1E2126);
        c.drawOval(r, p);
        rr(c, kx + 36, ky - 10, 26, 14, 4, 0xFF2E3238, 0xFF3E434B, 1);
        rr(c, kx + 36, ky + 10, 26, 14, 4, 0xFF2E3238, 0xFF3E434B, 1);
        path.reset();
        path.moveTo(mid - tw - 6, yP - 26); path.quadTo(mid - tw - 12, yP, mid - tw + 14, yP + 2); path.lineTo(mid + tw + 8, yP + 2);
        stroke(0xFF000000, 5);
        p.setShader(new LinearGradient(0, yP - 26, 0, yP + 4, 0xFFC9CED6, 0xFF6E747D, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        path.reset();
        path.moveTo(mid - tw - 6, yP + 8); path.lineTo(mid + tw + 6, yP + 8); path.lineTo(mid + bw, yB); path.lineTo(mid - bw, yB); path.close();
        fill(day ? 0xFFB4BAC1 : 0xFF111620);
        c.drawPath(path, p);
        stroke(day ? 0xFF9AA1A9 : 0xFF2A3342, 3);
        c.drawLine(mid - tw + 4, yP + 44, mid + tw - 4, yP + 44, p);
        stroke(0xFF2C3036, 3);
        c.drawLine(mid - tw - 4, yP + 8, mid - bw + 2, yB, p);
        c.drawLine(mid + tw + 4, yP + 8, mid + bw - 2, yB, p);
    }

    /** Corona tonda con un tratto piatto corto sopra e sotto (volante MG4 2022). */
    private void wheelShape(Path pa, float wx, float wy, float hw, float hh, float f) {
        pa.moveTo(wx - f, wy - hh);
        pa.lineTo(wx + f, wy - hh);
        r.set(wx + f - (hw - f), wy - hh, wx + f + (hw - f), wy + hh);
        pa.arcTo(r, -90, 180);
        pa.lineTo(wx - f, wy + hh);
        r.set(wx - f - (hw - f), wy - hh, wx - f + (hw - f), wy + hh);
        pa.arcTo(r, 90, 180);
        pa.close();
    }

    private void drawWheel(Canvas c, float wx, float wy, boolean hot) {
        if (hot) {
            path.reset();
            wheelShape(path, wx, wy, 121, 106, 34);
            stroke(HEAT, 34);
            p.setAlpha(40);
            c.drawPath(path, p);
            stroke(HEAT, 22);
            p.setAlpha(70);
            c.drawPath(path, p);
        }
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        wheelShape(path, wx, wy, 130, 114, 36);
        wheelShape(path, wx, wy, 111, 96, 30);
        grad(0, wy - 114, 0, wy + 114, hot ? 0xFFFF8A4C : 0xFF3B4048, hot ? 0xFFB9562A : 0xFF24272C);
        c.drawPath(path, p);
        p.setShader(null);
        stroke(hot ? 0xFF8A4524 : 0xFF454A52, 2);
        c.drawPath(path, p);
        path.setFillType(Path.FillType.WINDING);
        for (int sd = -1; sd <= 1; sd += 2) {
            path.reset();
            path.moveTo(wx + sd * 44, wy - 12); path.lineTo(wx + sd * 114, wy - 6); path.lineTo(wx + sd * 114, wy + 22);
            path.lineTo(wx + sd * 44, wy + 28); path.close();
            fill(0xFF26292E);
            c.drawPath(path, p);
            stroke(0xFF3A3F47, 1.5f);
            c.drawPath(path, p);
            rr(c, wx + sd * 78 - 16, wy - 6, 32, 26, 9, 0xFF1A1C20, 0xFF3A3F47, 1.5f);
            fill(0xFF3A3F47);
            c.drawCircle(wx + sd * 78, wy + 7, 5, p);
        }
        path.reset();
        path.moveTo(wx - 22, wy + 36); path.lineTo(wx + 22, wy + 36); path.lineTo(wx + 14, wy + 86); path.lineTo(wx - 14, wy + 86); path.close();
        fill(0xFF26292E);
        c.drawPath(path, p);
        stroke(0xFF3A3F47, 1.5f);
        c.drawPath(path, p);
        r.set(wx - 4, wy + 46, wx + 4, wy + 80);
        grad(0, wy + 46, 0, wy + 80, 0xFFC9CED6, 0xFF6E747D);
        c.drawRoundRect(r, 3, 3, p);
        p.setShader(null);
        r.set(wx - 50, wy - 40, wx + 50, wy + 40);
        grad(0, wy - 40, 0, wy + 40, 0xFF2E3238, 0xFF1D2025);
        c.drawRoundRect(r, 26, 26, p);
        p.setShader(null);
        stroke(0xFF3A3F47, 2);
        c.drawRoundRect(r, 26, 26, p);
        rr(c, wx - 18, wy - 9, 36, 18, 8, 0xFF33373E, 0, 0);
    }

    private void drawDashTexts(Canvas c, float cx) {
        float wx = cx - 270, sx = cx - 150;
        String time = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
        text(c, time, x(cx, wx), 192, 24, 0xFFE6E9ED, Paint.Align.CENTER, regular);
        text(c, tempText(), x(cx, sx + 24), 136, 30, 0xFFF2F3F5, rhd ? Paint.Align.RIGHT : Paint.Align.LEFT, regular);
        if (!Float.isNaN(st.outTemp)) {
            text(c, L.f(R.string.v_out_short, Math.round(st.outTemp)), x(cx, sx + 24), 176, 14, 0xFF9BA1A9,
                    rhd ? Paint.Align.RIGHT : Paint.Align.LEFT, regular);
        }
    }

    static String autoLevelName(int lv) {
        return L.s(lv == HvacClient.AUTO_LOW ? R.string.v_level_low : lv == HvacClient.AUTO_HIGH ? R.string.v_level_high : R.string.v_level_medium);
    }

    private float x(float cx, float v) {
        return rhd ? 2 * cx - v : v;
    }

    private String tempText() {
        if (st.temp < 0) return "–";
        if (st.temp <= st.tempMin) return "LO";
        if (st.temp >= st.tempMax) return "HI";
        return st.temp + "°";
    }

    private void drawDriverColumn(Canvas c, boolean usable, boolean on) {
        float cx0 = rhd ? W - 40 - 380 : 40, top = 34;
        // accensione grande
        float pcx = cx0 + 52, pcy = top + 52;
        if (on) {
            fill((TEXT & 0xFFFFFF) | 0x1F000000);
            c.drawCircle(pcx, pcy, 58, p);
        }
        fill(on ? TEXT : CHIP);
        c.drawCircle(pcx, pcy, 52, p);
        icon(c, R.drawable.ic_power, pcx - 22, pcy - 22, 44, 44, on ? ONTEXT : SUB);
        hit("accensione", cx0, top, 104, 104);
        String title = !usable ? L.s(R.string.v_unavailable) : on ? L.s(R.string.v_on) : L.s(R.string.v_off);
        String sub = !st.connected ? L.s(R.string.v_waiting)
                : !usable ? L.s(R.string.v_switch_on)
                : Float.isNaN(st.outTemp) ? "" : L.f(R.string.v_outside, Math.round(st.outTemp));
        // 1.2.0: il cruscotto inizia a 450, i testi lunghi (altre lingue) si rimpiccioliscono per starci
        text(c, title, cx0 + 122, top + 48, fit(title, 30, 255, bold), TEXT, Paint.Align.LEFT, bold);
        text(c, sub, cx0 + 122, top + 78, fit(sub, 18, 255, regular), SUB, Paint.Align.LEFT, regular);
        // temperatura
        float y = top + 104 + 26;
        String tl = L.s(R.string.v_temperature).toUpperCase(L.locale());
        text(c, tl, cx0, y + 14, 17, SUB, Paint.Align.LEFT, regular);
        y += 22;
        tile(c, cx0, y, 380, 118, 24, CHIP);
        icon(c, R.drawable.ic_left, cx0 + 26, y + 39, 40, 40, usable ? TEXT : SUB);
        icon(c, R.drawable.ic_right, cx0 + 380 - 66, y + 39, 40, 40, usable ? TEXT : SUB);
        hit("temp-", cx0, y, 190, 118);
        hit("temp+", cx0 + 190, y, 190, 118);
        String t = st.temp < 0 ? "–" : st.temp <= st.tempMin ? "LO" : st.temp >= st.tempMax ? "HI" : String.valueOf(st.temp);
        text(c, t, cx0 + 190, y + 92, 92, on ? TEXT : SUB, Paint.Align.CENTER, light);
        if (Character.isDigit(t.charAt(0))) {
            p.setTextSize(92);
            float tw = p.measureText(t);
            text(c, "°", cx0 + 190 + tw / 2 + 4, y + 40, 28, SUB, Paint.Align.LEFT, regular);
        }
        // ventola
        y += 118 + 20;
        boolean fanAuto = st.fan == HvacClient.FAN_AUTO || st.auto == 1;
        String fanLabel = L.s(R.string.v_fan);
        if (fanAuto && on) fanLabel += st.autoLevel >= 0 ? " · AUTO · " + autoLevelName(st.autoLevel) : " · " + L.s(R.string.v_fan_auto);
        fanLabel = fanLabel.toUpperCase(L.locale());
        text(c, fanLabel, cx0, y + 14, fit(fanLabel, 17, 380, regular), SUB, Paint.Align.LEFT, regular);
        y += 22;
        tile(c, cx0, y, 380, 100, 24, CHIP);
        hit("fan-", cx0, y, 190, 100);
        hit("fan+", cx0 + 190, y, 190, 100);
        icon(c, R.drawable.ic_left, cx0 + 16, y + 30, 40, 40, usable ? TEXT : SUB);
        icon(c, R.drawable.ic_right, cx0 + 380 - 56, y + 30, 40, 40, usable ? TEXT : SUB);
        icon(c, R.drawable.ic_fan, cx0 + 74, y + 30, 40, 40, on ? TEXT : SUB);
        String f = !on ? "–" : st.fan == HvacClient.FAN_AUTO ? "A" : st.fan <= 0 ? "OFF" : String.valueOf(st.fan);
        text(c, f, cx0 + 122, y + 64, 40, on ? TEXT : SUB, Paint.Align.LEFT, regular);
        int lv = on && st.fan > 0 && st.fan != HvacClient.FAN_AUTO ? st.fan : 0;
        int n = Math.max(1, st.fanMax);
        float bx = cx0 + 176, bw = 380 - 176 - 70, gap = 4, sw = (bw - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            float h = 14 + i * (36f / Math.max(1, n - 1));
            rr(c, bx + i * (sw + gap), y + 74 - h, sw, h, 4, i < lv ? TEXT : TRACK, 0, 0);
        }
    }

    private void drawTiles(Canvas c, boolean usable, boolean on, boolean[] dir) {
        String[] ids = {"auto", "ac", "eco", "vetro", "viso", "piedi", "ricircolo", "parabrezza", "lunotto", "volante",
                "sedileGuida", "sedilePasseggero"};
        List<String> order = new ArrayList<>();
        for (String id : ids) {
            if (id.startsWith("sedile") && !st.hasSeatHeat()) continue;
            if (id.equals("volante") && !st.hasWheel) continue;
            if ((id.equals("parabrezza") || id.equals("lunotto")) && !defrostTiles) continue;
            order.add(id);
        }
        if (rhd) {
            List<String> lead = new ArrayList<>(order.subList(0, 3));
            List<String> rest = new ArrayList<>(order.subList(3, order.size()));
            java.util.Collections.reverse(rest);
            java.util.Collections.reverse(lead);
            order.clear();
            order.addAll(rest);
            order.addAll(lead);
        }
        int count = order.size();
        float tw = (float) Math.floor((W - 80 - (count - 1) * 12) / count), gap = (W - 80 - tw * count) / (count - 1);
        float x = 40, top = 530, h = 126;
        for (String id : order) {
            boolean active, hot = false;
            int ic = 0, level = -1;
            String big = null, label;
            switch (id) {
                case "auto": active = on && st.auto == 1; big = "AUTO"; label = L.s(R.string.t_auto); break;
                case "ac": active = on && st.ac == 1; big = "A/C"; label = L.s(R.string.t_ac); break;
                case "eco": active = on && st.econ == 1; big = "ECO"; label = L.s(R.string.t_eco); break;
                case "vetro": active = on && dir[0]; ic = R.drawable.ic_screen; label = L.s(R.string.t_glass); break;
                case "viso": active = on && dir[1]; ic = R.drawable.ic_face; label = L.s(R.string.t_face); break;
                case "piedi": active = on && dir[2]; ic = R.drawable.ic_feet; label = L.s(R.string.t_feet); break;
                case "ricircolo":
                    active = on && (st.loop == 0 || st.loop == 2); ic = R.drawable.ic_loop;
                    label = L.s(st.loop == 0 ? R.string.t_recirc : st.loop == 2 ? R.string.t_recirc_auto : R.string.t_fresh);
                    break;
                case "parabrezza": hot = st.frontDefrost == 1; active = hot; ic = R.drawable.ic_front_def; label = L.s(R.string.t_front); break;
                case "lunotto": hot = st.rearDefrost == 1; active = hot; ic = R.drawable.ic_rear_def; label = L.s(R.string.t_rear); break;
                case "volante": hot = st.wheel == 3; active = hot; ic = R.drawable.ic_wheel; label = L.s(R.string.t_wheel); break;
                case "sedileGuida":
                    level = st.seatDrv >= 0 && st.seatDrv <= 3 ? st.seatDrv : 0; hot = level > 0; active = hot; ic = R.drawable.ic_seat;
                    label = L.s(R.string.t_driver);
                    break;
                default:
                    level = st.seatPsg >= 0 && st.seatPsg <= 3 ? st.seatPsg : 0; hot = level > 0; active = hot; ic = R.drawable.ic_seat;
                    label = L.s(R.string.t_passenger);
                    break;
            }
            int bg = hot ? HEATBG : active ? TEXT : CHIP;
            int fg = hot ? HEAT : active ? ONTEXT : SUB;
            int lab = hot ? TEXT : active ? ONTEXT : SUB;
            tile(c, x, top, tw, h, 22, bg);
            hit(id, x, top, tw, h);
            float mx = x + tw / 2;
            if (big != null) {
                boolean dashes = id.equals("auto") && st.autoLevel >= 0;
                text(c, big, mx, dashes ? top + 56 : top + 62, 34, fg, Paint.Align.CENTER, bold);
                text(c, label, mx, dashes ? top + 88 : top + 98, fit(label, 16, tw - 14, regular), lab, Paint.Align.CENTER, regular);
                if (dashes) {   // 1.2.0: intensità di AUTO (bassa / media / alta), si cambia toccando AUTO
                    int lit = 3 - st.autoLevel;
                    for (int i = 0; i < 3; i++)
                        rr(c, mx - 41 + i * 30, top + 104, 22, 5, 2.5f, i < lit ? fg : (fg & 0xFFFFFF) | 0x40000000, 0, 0);
                }
            } else {
                boolean seat = level >= 0;
                float iy = seat ? top + 16 : top + 26;
                boolean flip = seat && (id.equals("sedilePasseggero") != rhd);   // solo i sedili: quello a destra guarda a sinistra
                if (flip) {
                    c.save();
                    c.scale(-1, 1, mx, 0);
                }
                icon(c, ic, mx - 27, iy, 54, 40, fg);
                if (flip) c.restore();
                if (seat) for (int i = 0; i < 3; i++) rr(c, mx - 33 + i * 26, top + 66, 20, 6, 3, i < level ? HEAT : TRACK, 0, 0);
                text(c, label, mx, seat ? top + 104 : top + 98, fit(label, 16, tw - 14, regular), lab, Paint.Align.CENTER, regular);
            }
            x += tw + gap;
        }
    }

    // ======================================================================================== tocchi

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP) return super.onTouchEvent(e);
        float[] pt = {e.getX(), e.getY()};
        toDesign.mapPoints(pt);
        for (Object[] h : hits) {
            if (((RectF) h[1]).contains(pt[0], pt[1])) {
                if (listener != null) listener.onTap((String) h[0]);
                return true;
            }
        }
        return true;
    }

    // ======================================================================================== aiuti

    private void hit(String id, float x, float y, float w, float h) {
        hits.add(new Object[]{id, new RectF(x, y, x + w, y + h)});
    }

    private void fill(int color) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
    }

    private void stroke(int color, float w) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    private void grad(float x0, float y0, float x1, float y1, int a, int b) {
        fill(0xFF000000);
        p.setShader(new LinearGradient(x0, y0, x1, y1, a, b, Shader.TileMode.CLAMP));
    }

    private void rr(Canvas c, float x, float y, float w, float h, float rad, int fillColor, int strokeColor, float sw) {
        r.set(x, y, x + w, y + h);
        fill(fillColor);
        c.drawRoundRect(r, rad, rad, p);
        if (sw > 0) {
            stroke(strokeColor, sw);
            c.drawRoundRect(r, rad, rad, p);
        }
    }

    private void tile(Canvas c, float x, float y, float w, float h, float rad, int color) {
        rr(c, x, y, w, h, rad, color, 0, 0);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, Typeface tf) {
        if (s == null || s.isEmpty()) return;
        fill(color);
        p.setTextSize(size);
        p.setTypeface(tf);
        p.setTextAlign(align);
        c.drawText(s, x, y, p);
    }

    /** Dimensione del testo perché stia in maxW (al minimo il 60%). */
    private float fit(String s, float size, float maxW, Typeface tf) {
        if (s == null || s.isEmpty()) return size;
        p.setTypeface(tf);
        p.setTextSize(size);
        float w = p.measureText(s);
        return w <= maxW ? size : Math.max(size * .6f, size * maxW / w);
    }

    private void icon(Canvas c, int res, float x, float y, float w, float h, int color) {
        Drawable d = getContext().getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        d.setTint(color);
        d.setBounds(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h));
        d.draw(c);
    }
}
