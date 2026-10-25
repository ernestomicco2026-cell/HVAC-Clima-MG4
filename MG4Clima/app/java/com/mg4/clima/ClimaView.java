package com.mg4.clima;

import android.content.Context;
import android.content.res.Configuration;
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

    public void setRightHand(boolean on) {
        rhd = on;
        invalidate();
    }

    public void setState(HvacClient.State s) {
        st = s;
        invalidate();
    }

    public void theme() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        day = night == Configuration.UI_MODE_NIGHT_NO;
        if (day) {
            BG = 0xFFE6E8EB; PANEL = 0xFFFFFFFF; CHIP = 0xFFF1F3F5; TRACK = 0xFFE1E4E8; TEXT = 0xFF111318; SUB = 0xFF555C66;
            ONTEXT = 0xFFFFFFFF; HEAT = 0xFFE1621F; HEATBG = 0xFFFFF1EA; FLOW = 0xFF7D8793; FLOW2 = 0xFFB9C0C8;
            GLASS1 = 0xFFE9EEF4; GLASS2 = 0xFFD5DCE5; TOP1 = 0xFFD9DDE2; TOP2 = 0xFFC9CED4; FACE1 = 0xFFCDD2D8; FACE2 = 0xFFBFC5CC;
            PILLAR = 0xFFB8BEC6; LINE = 0xFFB0B6BE; DARK = 0xFF2A2E35; BEZEL = 0xFF8C939C;
        } else {
            BG = 0xFF0A0B0D; PANEL = 0xFF15171B; CHIP = 0xFF1E2126; TRACK = 0xFF262A30; TEXT = 0xFFF2F3F5; SUB = 0xFF9BA1A9;
            ONTEXT = 0xFF111318; HEAT = 0xFFFF7A3D; HEATBG = 0xFF2E241F; FLOW = 0xFFDDE4EC; FLOW2 = 0xFF9BA1A9;
            GLASS1 = 0xFF181C22; GLASS2 = 0xFF252A32; TOP1 = 0xFF2E3239; TOP2 = 0xFF202328; FACE1 = 0xFF1E2126; FACE2 = 0xFF15171B;
            PILLAR = 0xFF2A2E35; LINE = 0xFF30343B; DARK = 0xFF0B0C0E; BEZEL = 0xFF3A3F47;
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
        int fl = warm ? 0xFFFFB27A : FLOW, fl2 = warm ? HEAT : FLOW2;
        float cx = rhd ? 750 : 1130;

        // ---- cruscotto (disegnato come guida a sinistra, specchiato se a destra)
        c.save();
        if (rhd) {
            c.translate(2 * cx, 0);
            c.scale(-1, 1);
        }
        drawDash(c, cx, on, dir, fan, fl, fl2, st.frontDefrost == 1, st.wheel == 3);
        c.restore();
        drawDashTexts(c, cx);

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
        // sotto la colonna dei comandi (in alto finirebbe sul montante del parabrezza)
        text(c, "MG4 Clima " + BuildInfo.version(getContext()) + (on && st.blowerMode == 7 ? BuildInfo.t(" · direzione aria automatica", " · automatic air direction") : ""),
                rhd ? W - 40 : 40, 500, 17, SUB, rhd ? Paint.Align.RIGHT : Paint.Align.LEFT, regular);

        // ---- tasti in basso
        drawTiles(c, usable, on, dir);
        c.restore();
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
        // flussi verso il viso
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
                    stroke(0xFF000000, 3.5f + (1 - Math.abs(t)) * 3);
                    p.setShader(new LinearGradient(x0, y0, x2, y2, (fl & 0xFFFFFF) | 0xD9000000, fl2 & 0xFFFFFF, Shader.TileMode.CLAMP));
                    c.drawPath(path, p);
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
                p.setPathEffect(new DashPathEffect(new float[]{3, 11}, 0));
                c.drawPath(path, p);
                p.setPathEffect(null);
            }
        }
        // piantone e volante
        rr(c, wx - 34, wy + 70, 68, 90, 12, day ? 0xFF8C939C : 0xFF1A1C20, 0, 0);
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
        fill(day ? 0xFFB4BAC1 : 0xFF111316);
        c.drawPath(path, p);
        stroke(day ? 0xFF9AA1A9 : 0xFF2A2E35, 3);
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
            text(c, Math.round(st.outTemp) + "° " + BuildInfo.t("est.", "out"), x(cx, sx + 24), 176, 14, 0xFF9BA1A9,
                    rhd ? Paint.Align.RIGHT : Paint.Align.LEFT, regular);
        }
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
        String title = !usable ? BuildInfo.t("Clima non disponibile", "Climate unavailable")
                : on ? BuildInfo.t("Clima acceso", "Climate on") : BuildInfo.t("Clima spento", "Climate off");
        String sub = !st.connected ? BuildInfo.t("In attesa del servizio veicolo…", "Waiting for the vehicle service…")
                : !usable ? BuildInfo.t("Accendi l'auto", "Switch the car on")
                : Float.isNaN(st.outTemp) ? "" : Math.round(st.outTemp) + "° " + BuildInfo.t("esterni", "outside");
        text(c, title, cx0 + 122, top + 48, 30, TEXT, Paint.Align.LEFT, bold);
        text(c, sub, cx0 + 122, top + 78, 18, SUB, Paint.Align.LEFT, regular);
        // temperatura
        float y = top + 104 + 26;
        text(c, BuildInfo.t("TEMPERATURA", "TEMPERATURE"), cx0, y + 14, 17, SUB, Paint.Align.LEFT, regular);
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
        text(c, BuildInfo.t("VENTOLA", "FAN") + (fanAuto && on ? BuildInfo.t(" · AUTOMATICA", " · AUTOMATIC") : ""), cx0, y + 14, 17, SUB,
                Paint.Align.LEFT, regular);
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
                case "auto": active = on && st.auto == 1; big = "AUTO"; label = BuildInfo.t("Automatico", "Automatic"); break;
                case "ac": active = on && st.ac == 1; big = "A/C"; label = BuildInfo.t("Aria condiz.", "Air cond."); break;
                case "eco": active = on && st.econ == 1; big = "ECO"; label = BuildInfo.t("Risparmio", "Economy"); break;
                case "vetro": active = on && dir[0]; ic = R.drawable.ic_screen; label = BuildInfo.t("Aria al vetro", "Windscreen"); break;
                case "viso": active = on && dir[1]; ic = R.drawable.ic_face; label = BuildInfo.t("Aria al viso", "Face"); break;
                case "piedi": active = on && dir[2]; ic = R.drawable.ic_feet; label = BuildInfo.t("Aria ai piedi", "Feet"); break;
                case "ricircolo":
                    active = on && (st.loop == 0 || st.loop == 2); ic = R.drawable.ic_loop;
                    label = st.loop == 0 ? BuildInfo.t("Ricircolo", "Recirculation") : st.loop == 2 ? BuildInfo.t("Ricircolo auto", "Auto recirc.")
                            : BuildInfo.t("Aria esterna", "Fresh air");
                    break;
                case "parabrezza": hot = st.frontDefrost == 1; active = hot; ic = R.drawable.ic_front_def; label = BuildInfo.t("Parabrezza", "Windscreen"); break;
                case "lunotto": hot = st.rearDefrost == 1; active = hot; ic = R.drawable.ic_rear_def; label = BuildInfo.t("Lunotto", "Rear window"); break;
                case "volante": hot = st.wheel == 3; active = hot; ic = R.drawable.ic_wheel; label = BuildInfo.t("Volante", "Wheel"); break;
                case "sedileGuida":
                    level = st.seatDrv >= 0 && st.seatDrv <= 3 ? st.seatDrv : 0; hot = level > 0; active = hot; ic = R.drawable.ic_seat;
                    label = BuildInfo.t("Guidatore", "Driver");
                    break;
                default:
                    level = st.seatPsg >= 0 && st.seatPsg <= 3 ? st.seatPsg : 0; hot = level > 0; active = hot; ic = R.drawable.ic_seat;
                    label = BuildInfo.t("Passeggero", "Passenger");
                    break;
            }
            int bg = hot ? HEATBG : active ? TEXT : CHIP;
            int fg = hot ? HEAT : active ? ONTEXT : SUB;
            int lab = hot ? TEXT : active ? ONTEXT : SUB;
            tile(c, x, top, tw, h, 22, bg);
            hit(id, x, top, tw, h);
            float mx = x + tw / 2;
            if (big != null) {
                text(c, big, mx, top + 62, 34, fg, Paint.Align.CENTER, bold);
                text(c, label, mx, top + 98, 16, lab, Paint.Align.CENTER, regular);
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
                text(c, label, mx, seat ? top + 104 : top + 98, 16, lab, Paint.Align.CENTER, regular);
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

    private void icon(Canvas c, int res, float x, float y, float w, float h, int color) {
        Drawable d = getContext().getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        d.setTint(color);
        d.setBounds(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h));
        d.draw(c);
    }
}
