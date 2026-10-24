package com.mg4.clima;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Pannelli a schermo intero con la stessa grafica delle impostazioni del launcher MG4: intestazione con titolo e X,
 * etichette in maiuscolo, schede arrotondate con righe "titolo + spiegazione / comando a destra", interruttori e
 * pulsanti con i colori del tema (giorno/notte).
 */
final class Sheet {
    interface OnToggle {
        void changed(boolean on);
    }

    private final Context c;
    private final FrameLayout host;
    private final int bg, panel, chip, track, text, sub;
    private FrameLayout root;
    private LinearLayout col;

    Sheet(Context c, FrameLayout host, ClimaView v) {
        this.c = c;
        this.host = host;
        bg = v.BG;
        panel = v.PANEL;
        chip = v.CHIP;
        track = v.TRACK;
        text = v.TEXT;
        sub = v.SUB;
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    boolean showing() {
        return root != null && root.getParent() != null;
    }

    void close() {
        if (root != null && root.getParent() != null) ((ViewGroup) root.getParent()).removeView(root);
        root = null;
    }

    /** Nuovo pannello vuoto (sostituisce quello aperto). */
    Sheet open(String title, String subtitle) {
        close();
        root = new FrameLayout(c);
        root.setBackgroundColor(bg);
        root.setClickable(true);   // i tocchi non passano alla schermata sotto
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(40), dp(26), dp(40), 0);
        // intestazione
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tt = new LinearLayout(c);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(tv(title, 34, text, true));
        if (subtitle != null && !subtitle.isEmpty()) tt.addView(tv(subtitle, 17, sub, false), margins(-2, -2, 0, dp(4), 0, 0));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView x = new ImageView(c);
        x.setImageDrawable(tint(R.drawable.ic_close, text));
        x.setPadding(dp(18), dp(18), dp(18), dp(18));
        x.setBackground(pressable(chip, dp(22)));
        x.setOnClickListener(v -> close());
        head.addView(x, new LinearLayout.LayoutParams(dp(72), dp(72)));
        page.addView(head);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(40));
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));
        return this;
    }

    void label(String s) {
        TextView t = tv(s.toUpperCase(Locale.getDefault()), 15, sub, true);
        t.setLetterSpacing(0.08f);
        col.addView(t, margins(-2, -2, 0, dp(30), 0, 0));
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(chip, dp(22)));
        l.setClipToOutline(true);
        col.addView(l, margins(-1, -2, 0, dp(14), 0, 0));
        return l;
    }

    /** Riga: titolo + spiegazione a sinistra, comando a destra; separatore sotto se non è l'ultima. */
    void row(LinearLayout card, String title, String subtitle, View right, boolean last) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(96));
        r.setPadding(dp(28), dp(14), dp(28), dp(14));
        if (!last) {
            LayerDrawable ld = new LayerDrawable(new Drawable[]{new ColorDrawable(track), new ColorDrawable(chip)});
            ld.setLayerInset(1, 0, 0, 0, dp(1));
            r.setBackground(ld);
        }
        LinearLayout tx = new LinearLayout(c);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(tv(title, 24, text, false));
        if (subtitle != null && !subtitle.isEmpty()) tx.addView(tv(subtitle, 17, sub, false), margins(-2, -2, 0, dp(6), 0, 0));
        r.addView(tx, margins(0, -2, 0, 0, dp(20), 0, 1));
        if (right != null) r.addView(right);
        card.addView(r);
    }

    /** Testo libero dentro una scheda (diagnostica). */
    void body(LinearLayout card, String s) {
        TextView t = tv(s, 17, text, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(28), dp(22), dp(28), dp(22));
        card.addView(t);
    }

    TextView button(String label, View.OnClickListener l) {
        TextView b = tv(label, 20, text, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(26), 0, dp(26), 0);
        b.setSingleLine(true);
        b.setBackground(pressable(panel, dp(16)));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(60)));
        return b;
    }

    /** Scelte a pulsanti: quella selezionata a colori invertiti (come tema e lingua nel launcher). */
    LinearLayout choices(String[] names, int sel, java.util.function.IntConsumer l) {
        LinearLayout r = new LinearLayout(c);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView b = tv(names[i], 20, i == sel ? panel : text, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(22), 0, dp(22), 0);
            b.setSingleLine(true);
            b.setBackground(i == sel ? round(text, dp(16)) : pressable(panel, dp(16)));
            b.setOnClickListener(v -> {
                if (idx != sel) l.accept(idx);
            });
            r.addView(b, margins(-2, dp(60), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        return r;
    }

    View toggle(boolean on, OnToggle l) {
        Toggle t = new Toggle(on, l);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(44)));
        return t;
    }

    /** Conferma nello stesso stile: scheda centrale con testo e due pulsanti. */
    void confirm(String title, String msg, String cancel, String ok, Runnable onOk) {
        FrameLayout dim = new FrameLayout(c);
        dim.setBackgroundColor(0xB3000000);
        dim.setClickable(true);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(chip, dp(26)));
        box.setPadding(dp(36), dp(30), dp(36), dp(28));
        box.addView(tv(title, 28, text, true));
        box.addView(tv(msg, 19, sub, false), margins(-2, -2, 0, dp(14), 0, 0));
        LinearLayout btns = new LinearLayout(c);
        btns.setGravity(Gravity.END);
        btns.addView(button(cancel, v -> host.removeView(dim)));
        TextView okb = button(ok, v -> {
            host.removeView(dim);
            onOk.run();
        });
        okb.setTextColor(panel);
        okb.setBackground(round(text, dp(16)));
        btns.addView(okb, margins(-2, dp(60), dp(12), 0, 0, 0));
        box.addView(btns, margins(-1, -2, 0, dp(24), 0, 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(820), -2, Gravity.CENTER);
        dim.addView(box, lp);
        host.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    // ------------------------------------------------------------------------------------ aiuti

    private TextView tv(String s, float size, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private Drawable pressable(int color, float radius) {
        return new RippleDrawable(ColorStateList.valueOf((text & 0xFFFFFF) | 0x33000000), round(color, radius), round(0xFF000000, radius));
    }

    private Drawable tint(int res, int color) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }

    /** Interruttore con i colori del tema (acceso = colore del testo, spento = binario), come nel launcher. */
    private final class Toggle extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Toggle(boolean on, OnToggle l) {
            super(c);
            this.on = on;
            setClickable(true);
            setOnClickListener(v -> {
                this.on = !this.on;
                invalidate();
                l.changed(this.on);
            });
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), pad = h * 4f / 44f, d = h - 2 * pad;
            r.set(0, 0, w, h);
            p.setColor(on ? text : track);
            cv.drawRoundRect(r, h / 2, h / 2, p);
            p.setColor(on ? panel : sub);
            cv.drawCircle(on ? w - pad - d / 2 : pad + d / 2, h / 2, d / 2, p);
        }
    }
}
