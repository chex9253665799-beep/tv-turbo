package ru.chex.tvturbo.mv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Оформление MiVision: цвета, свечение фокуса, карточки, кнопки. */
public final class Kit {

    public static final int TEXT = 0xFFEAF0FF, MUTED = 0xFF9FB0D8, CARD = 0x14FFFFFF, CARD_LINE = 0x1AFFFFFF;
    public static final int[] ACCENTS = {0xFF2E6BFF, 0xFF7B4DFF, 0xFFE040FB, 0xFF14B8A6, 0xFFF59E0B, 0xFFEF4444};
    public static final String[] ACCENT_NAMES = {"Синий", "Фиолетовый", "Розовый", "Бирюзовый", "Янтарный", "Красный"};
    /** Обои: пары цветов градиента. */
    public static final int[][] WALLS = {
            {0xFF071029, 0xFF0A1640, 0xFF120E3A},
            {0xFF04131F, 0xFF0B2A4A, 0xFF0B1B3A},
            {0xFF12071F, 0xFF2A0B4A, 0xFF3A0B2A},
            {0xFF04140F, 0xFF06302A, 0xFF0B1F2A},
            {0xFF000000, 0xFF05070C, 0xFF0A0A14},
    };
    public static final String[] WALL_NAMES = {"Ночное небо", "Океан", "Аметист", "Изумруд", "Чёрный (OLED)"};

    public static SharedPreferences sp(Context c) { return c.getSharedPreferences("mv", Context.MODE_PRIVATE); }
    public static int accent(Context c) { return ACCENTS[Math.min(ACCENTS.length - 1, sp(c).getInt("accent", 0))]; }
    public static int[] wall(Context c) { return WALLS[Math.min(WALLS.length - 1, sp(c).getInt("wall", 0))]; }

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    public static TextView line(Context c, String s, float sp, int color, boolean bold) {
        TextView t = text(c, s, sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable grad(GradientDrawable.Orientation o, int[] colors, float radiusPx) {
        GradientDrawable g = new GradientDrawable(o, colors);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Голубая рамка, свечение и плавное увеличение при фокусе. */
    public static void glow(View v, GradientDrawable frame, float scale, Runnable onFocus) {
        int acc = accent(v.getContext());
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(150).start();
            view.setElevation(has ? dp(view.getContext(), 10) : 0);
            if (frame != null) frame.setStroke(has ? dp(view.getContext(), 2.5f) : 0, lighten(acc));
            if (has && onFocus != null) onFocus.run();
        });
    }

    public static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 70), g = Math.min(255, ((c >> 8) & 0xFF) + 70), b = Math.min(255, (c & 0xFF) + 40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int alpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    /** Кнопка-«таблетка». primary — с градиентом и свечением. */
    public static TextView pill(Context c, String s, boolean primary, View.OnClickListener l) {
        TextView t = text(c, s, 14, Color.WHITE, primary);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 18), dp(c, 9), dp(c, 18), dp(c, 9));
        t.setFocusable(true);
        t.setClickable(true);
        int acc = accent(c);
        GradientDrawable bg = primary
                ? grad(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{acc, lighten(acc)}, dp(c, 100))
                : round(0x24FFFFFF, dp(c, 100));
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(130).start();
            bg.setStroke(has ? dp(c, 2) : 0, Color.WHITE);
            if (!primary) bg.setColor(has ? alpha(acc, 0xCC) : 0x24FFFFFF);
        });
        t.setOnClickListener(l);
        return t;
    }

    /** Заголовок ряда, справа — ссылка «Все →». */
    public static LinearLayout rowTitle(Context c, String title, String more, View.OnClickListener onMore) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(c, 12), dp(c, 8), dp(c, 6));
        r.addView(text(c, title, 17, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (more != null) {
            TextView m = text(c, more + "  →", 12, lighten(accent(c)), false);
            m.setFocusable(onMore != null);
            m.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
            if (onMore != null) {
                GradientDrawable g = round(0, dp(c, 100));
                m.setBackground(g);
                m.setOnClickListener(onMore);
                m.setOnFocusChangeListener((v, h) -> g.setColor(h ? alpha(accent(c), 0x66) : 0));
            }
            r.addView(m);
        }
        return r;
    }

    /** Подложка-карточка. */
    public static FrameLayout card(Context c, float radiusDp) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = round(CARD, dp(c, radiusDp));
        g.setStroke(1, CARD_LINE);
        f.setBackground(g);
        f.setClipToOutline(true);
        return f;
    }

    public static ImageView image(Context c, ImageView.ScaleType st) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(st);
        return iv;
    }

    // ------------------------------------------------------------------ избранное и недавние

    public static List<String> list(Context c, String key) {
        List<String> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(sp(c).getString(key, "[]")); for (int i = 0; i < a.length(); i++) l.add(a.getString(i)); }
        catch (Throwable ignored) { }
        return l;
    }

    public static void saveList(Context c, String key, List<String> l) {
        sp(c).edit().putString(key, new JSONArray(l).toString()).apply();
    }

    private Kit() { }
}
