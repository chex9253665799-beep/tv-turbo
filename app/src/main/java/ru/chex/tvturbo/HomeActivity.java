package ru.chex.tvturbo;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.chex.tvturbo.mv.Catalog;
import ru.chex.tvturbo.mv.Img;
import ru.chex.tvturbo.mv.Kinopoisk;
import ru.chex.tvturbo.mv.Kit;
import ru.chex.tvturbo.mv.Program;
import ru.chex.tvturbo.mv.Tv;
import ru.chex.tvturbo.mv.Weather;

/**
 * MiVision TV — главный экран (лаунчер) в стиле Xiaomi TV.
 * Фильмы и сериалы — из подборок установленных приложений, приложения — только реально установленные,
 * погода — настоящая (Open-Meteo), каналы — из подключённого плейлиста. Работает с пульта на любом Android TV.
 */
public class HomeActivity extends BaseActivity {

    // страницы
    static final String HOME = "Главная", MOVIES = "Фильмы", SERIES = "Сериалы", APPS = "Приложения",
            TV = "ТВ-каналы", SETTINGS = "Настройки", SEARCH = "Поиск", WEATHER = "Погода", FAV = "Избранное";
    private static final String[] NAV = {HOME, MOVIES, SERIES, APPS, TV, SETTINGS};

    static final class App { String pkg, label, cat; Intent launch; Drawable banner, icon; }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>();
    private final Map<String, App> byPkg = new HashMap<>();
    private Catalog catalog = new Catalog();
    private Weather.Now weather;
    private String weatherErr = "";
    private List<Tv.Channel> channels = new ArrayList<>();
    private String tvErr = "";

    private FrameLayout root, content;
    private LinearLayout nav, side;
    private TextView clock, date, wxTemp, wxCity;
    private ImageView wxIcon;
    private String page = HOME;
    private final Map<String, String> lastFocus = new HashMap<>();   // страница → ключ карточки
    private Runnable bannerTick;
    private int acc;

    // ================================================================== жизненный цикл

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        acc = Kit.accent(this);
        catalog = Catalog.cached(this);
        buildShell();
    }

    @Override
    protected void onResume() {
        super.onResume();
        int newAcc = Kit.accent(this);
        if (newAcc != acc) { acc = newAcc; buildShell(); }
        applyWall();
        loadApps();
        show(page);
        tick();
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        phone.stop();
        h.removeCallbacksAndMessages(null);
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof String) lastFocus.put(page, (String) f.getTag());
    }

    @Override
    public void onBackPressed() {
        if (!HOME.equals(page)) show(HOME);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_MENU) { show(SETTINGS); return true; }
        if (k == KeyEvent.KEYCODE_SEARCH) { show(SEARCH); return true; }
        return super.onKeyDown(k, e);
    }

    /** Фоновое обновление: каталог из приложений, погода, каналы. */
    private void refreshAll() {
        Adb adb = Adb.get(this);
        Runnable loadCat = () -> new Thread(() -> {
            Catalog c = Catalog.load(this);
            if (c.available || catalog.all.isEmpty()) h.post(() -> { catalog = c; if (isCatalogPage()) show(page); });
        }).start();
        if (adb.isConnected()) loadCat.run();
        else adb.connect((ok, out) -> { if (ok) loadCat.run(); });

        if (Weather.autoAllowed(this) == null && !Weather.hasCity(this)) askWeatherConsent();
        else loadWeather();

        if (!Tv.url(this).isEmpty()) new Thread(() -> {
            try { List<Tv.Channel> l = Tv.load(this, false); h.post(() -> { channels = l; tvErr = ""; if (TV.equals(page)) show(TV); }); }
            catch (Throwable t) { h.post(() -> tvErr = String.valueOf(t.getMessage())); }
        }).start();
    }

    private boolean isCatalogPage() { return HOME.equals(page) || MOVIES.equals(page) || SERIES.equals(page); }

    private void loadWeather() {
        new Thread(() -> {
            try { Weather.Now n = Weather.fetch(this); h.post(() -> { weather = n; weatherErr = ""; updateWeatherChip(); if (WEATHER.equals(page)) show(WEATHER); }); }
            catch (Throwable t) { h.post(() -> { weatherErr = String.valueOf(t.getMessage()); updateWeatherChip(); if (WEATHER.equals(page)) show(WEATHER); }); }
        }).start();
    }

    private void askWeatherConsent() {
        new AlertDialog.Builder(this)
                .setTitle("Погода")
                .setMessage("Определить город автоматически по интернет-адресу телевизора? Можно выбрать город вручную.")
                .setPositiveButton("Определить", (d, w) -> { Weather.setAuto(this, true); loadWeather(); })
                .setNegativeButton("Выбрать вручную", (d, w) -> { Weather.setAuto(this, false); chooseCity(); })
                .setCancelable(false)
                .show();
    }

    // ================================================================== каркас: шапка, боковая панель

    private void buildShell() {
        root = new FrameLayout(this);
        applyWall();


        // шапка
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(26), dp(10), dp(26), dp(6));
        TextView logo = Kit.text(this, "", 20, Color.WHITE, true);
        logo.setText(android.text.Html.fromHtml("Mi<font color='#" + Integer.toHexString(Kit.lighten(acc) & 0xFFFFFF) + "'>Vision</font>TV"));
        top.addView(logo);
        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
        nl.setMarginStart(dp(20));
        top.addView(nav, nl);
        for (String n : NAV) nav.addView(navChip(n));

        LinearLayout wx = new LinearLayout(this);
        wx.setGravity(Gravity.CENTER_VERTICAL);
        wx.setFocusable(true);
        wx.setPadding(dp(8), dp(4), dp(12), dp(4));
        GradientDrawable wxBg = Kit.round(0, dp(100));
        wx.setBackground(wxBg);
        wx.setOnFocusChangeListener((v, f) -> wxBg.setColor(f ? Kit.alpha(acc, 0x66) : 0));
        wx.setOnClickListener(v -> show(WEATHER));
        wxIcon = new ImageView(this);
        wx.addView(wxIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout wxc = new LinearLayout(this);
        wxc.setOrientation(LinearLayout.VERTICAL);
        wxc.setPadding(dp(6), 0, 0, 0);
        wxTemp = Kit.text(this, "", 17, Color.WHITE, true);
        wxCity = Kit.text(this, "", 10, Kit.MUTED, false);
        wxc.addView(wxTemp); wxc.addView(wxCity);
        wx.addView(wxc);
        top.addView(wx);
        View sep = new View(this);
        sep.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(1), dp(30));
        sl.setMargins(dp(10), 0, dp(12), 0);
        top.addView(sep, sl);
        LinearLayout ck = new LinearLayout(this);
        ck.setOrientation(LinearLayout.VERTICAL);
        clock = Kit.text(this, "", 20, Color.WHITE, true);
        date = Kit.text(this, "", 10, Kit.MUTED, false);
        ck.addView(clock); ck.addView(date);
        top.addView(ck);
        ImageView wifi = Kit.icon(this, R.drawable.ic_wifi, Color.WHITE);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(22), dp(22));
        wl.setMarginStart(dp(14));
        top.addView(wifi, wl);
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP));
        updateWeatherChip();

        // боковая панель
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        Object[][] icons = {{R.drawable.ic_search, SEARCH}, {R.drawable.ic_home, HOME}, {R.drawable.ic_movie, MOVIES},
                {R.drawable.ic_tv, TV}, {R.drawable.ic_apps, APPS}, {R.drawable.ic_star, FAV}, {R.drawable.ic_settings, SETTINGS}};
        for (Object[] ic : icons) side.addView(sideIcon((Integer) ic[0], (String) ic[1]));
        // «Источник»: системная кнопка входов (HDMI/антенна) — есть на всех Android TV
        View src = sideIcon(R.drawable.ic_input, "Источник");
        src.setOnClickListener(v -> {
            Adb adb = Adb.get(this);
            if (adb.isConnected()) adb.run("input keyevent 178", null);
            else safeStart(new Intent(android.provider.Settings.ACTION_SETTINGS));
        });
        side.addView(src);
        FrameLayout.LayoutParams sdl = new FrameLayout.LayoutParams(dp(50), -2, Gravity.START | Gravity.TOP);
        sdl.topMargin = dp(80);
        sdl.leftMargin = dp(12);
        root.addView(side, sdl);

        content = new FrameLayout(this);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -1);
        cl.topMargin = dp(58);
        cl.leftMargin = dp(74);
        root.addView(content, cl);
        setContentView(root);
    }

    private void applyWall() {
        if (root != null) root.setBackground(Kit.wallpaper(this));
    }

    private View navChip(String n) {
        TextView t = Kit.text(this, n, 13, Kit.MUTED, false);
        t.setTag("nav:" + n);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setPadding(dp(13), dp(6), dp(13), dp(6));
        t.setOnClickListener(v -> show(n));
        t.setOnFocusChangeListener((v, f) -> styleNav());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    private void styleNav() {
        for (int i = 0; i < nav.getChildCount(); i++) {
            TextView t = (TextView) nav.getChildAt(i);
            String n = ((String) t.getTag()).substring(4);
            boolean on = n.equals(page), f = t.hasFocus();
            GradientDrawable g = on || f ? Kit.grad(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{acc, Kit.lighten(acc)}, dp(100)) : null;
            if (g != null && f && !on) g.setAlpha(150);
            t.setBackground(g);
            t.setTextColor(on || f ? Color.WHITE : Kit.MUTED);
            t.setText(n);
        }
        for (int i = 0; i < side.getChildCount(); i++) {
            View t = side.getChildAt(i);
            boolean on = ((String) t.getTag()).substring(5).equals(page);
            GradientDrawable g = Kit.round(on ? Kit.alpha(acc, 0x44) : 0, dp(12));
            if (on || t.hasFocus()) g.setStroke(dp(1.5f), Kit.lighten(acc));
            if (t.hasFocus()) g.setColor(Kit.alpha(acc, 0x99));
            t.setBackground(g);
        }
    }

    private View sideIcon(int icon, String target) {
        ImageView t = Kit.icon(this, icon, Color.WHITE);
        t.setTag("side:" + target);
        int p = dp(9);
        t.setPadding(p, p, p, p);
        t.setFocusable(true);
        t.setOnClickListener(v -> show(target));
        t.setOnFocusChangeListener((v, f) -> { styleNav(); v.animate().scaleX(f ? 1.1f : 1).scaleY(f ? 1.1f : 1).setDuration(120).start(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.bottomMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private void updateWeatherChip() {
        if (wxTemp == null) return;
        if (weather != null) {
            wxIcon.setImageResource(Weather.icon(weather.code));
            wxTemp.setText(Weather.t(weather.temp));
            wxCity.setText(weather.city);
        } else {
            wxIcon.setImageResource(R.drawable.wx_cloudsun);
            wxTemp.setText("—");
            wxCity.setText(weatherErr.isEmpty() ? "погода" : "нет данных");
        }
    }

    private void tick() {
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        String d = new SimpleDateFormat("EE, d MMMM", new Locale("ru")).format(new Date());
        date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
        h.postDelayed(this::tick, 20_000);
    }

    // ================================================================== навигация

    void show(String p) {
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof String && !((String) f.getTag()).startsWith("nav:") && !((String) f.getTag()).startsWith("side:"))
            lastFocus.put(page, (String) f.getTag());
        page = p;
        if (bannerTick != null) { h.removeCallbacks(bannerTick); bannerTick = null; }
        content.removeAllViews();
        View v;
        switch (p) {
            case MOVIES: v = pageMovies(false); break;
            case SERIES: v = pageMovies(true); break;
            case APPS: v = pageApps(); break;
            case TV: v = pageTv(); break;
            case SETTINGS: v = pageSettings("Внешний вид"); break;
            case SEARCH: v = pageSearch(); break;
            case WEATHER: v = pageWeather(); break;
            case FAV: v = pageFav(); break;
            default: v = pageHome();
        }
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        styleNav();
        restoreFocus(v);
    }

    /** Вернуть фокус на ту же карточку, что была до перехода. */
    private void restoreFocus(View v) {
        String key = lastFocus.get(page);
        v.post(() -> {
            View target = key == null ? null : v.findViewWithTag(key);
            if (target == null) target = firstFocusable(v);
            if (target != null) target.requestFocus();
        });
    }

    private View firstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE && !(v instanceof ScrollView)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View r = firstFocusable(g.getChildAt(i)); if (r != null) return r; }
        }
        return null;
    }

    private ScrollView scroller(LinearLayout inner) {
        ScrollView s = new ScrollView(this);
        s.setVerticalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setPadding(0, 0, 0, dp(30));
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setClipChildren(false);
        inner.setPadding(0, 0, dp(22), 0);
        s.addView(inner);
        return s;
    }

    // ================================================================== ГЛАВНАЯ

    private View pageHome() {
        LinearLayout col = new LinearLayout(this);
        ScrollView s = scroller(col);
        col.addView(banner(catalog.featured(6)));

        col.addView(Kit.rowTitle(this, "Ваши приложения", "Все приложения", v -> show(APPS)));
        List<App> fav = favApps();
        if (fav.isEmpty()) fav = apps.subList(0, Math.min(6, apps.size()));
        LinearLayout ar = hrow(col);
        for (App a : fav.subList(0, Math.min(6, fav.size()))) ar.addView(appTile(a, 116, 54), lpH(116, 54));
        ar.addView(moreTile(), lpH(86, 54));

        if (!catalog.watchNext.isEmpty()) {
            col.addView(Kit.rowTitle(this, "Продолжить просмотр", null, null));
            LinearLayout r = hrow(col);
            for (Program p : catalog.watchNext) r.addView(posterTile(p, 150, 86), lpH(150, 86));
        }
        if (!catalog.all.isEmpty()) {
            if (!catalog.fromKp) {
                col.addView(Kit.rowTitle(this, "Рекомендуем", "из твоих приложений", null));
                LinearLayout r = hrow(col);
                for (Program p : mix(catalog.all, 14)) r.addView(tile(p));
            }
            for (Map.Entry<String, List<Program>> e : catalog.channels.entrySet()) {
                col.addView(Kit.rowTitle(this, e.getKey(), null, null));
                LinearLayout rr = hrow(col);
                for (Program p : e.getValue()) rr.addView(tile(p));
            }
        } else col.addView(catalogHint());
        return s;
    }

    /** Подборки разных приложений вперемешку, чтобы «Рекомендуем» не был одним КИОНом. */
    private List<Program> mix(List<Program> src, int max) {
        Map<String, List<Program>> by = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (Program p : src) { List<Program> l = by.get(p.pkg); if (l == null) { by.put(p.pkg, l = new ArrayList<>()); order.add(p.pkg); } l.add(p); }
        List<Program> out = new ArrayList<>();
        for (int i = 0; out.size() < max; i++) {
            boolean any = false;
            for (String k : order) { List<Program> l = by.get(k); if (i < l.size()) { out.add(l.get(i)); any = true; if (out.size() >= max) break; } }
            if (!any) break;
        }
        return out;
    }

    private View catalogHint() {
        FrameLayout c = Kit.card(this, 16);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(16), dp(20), dp(16));
        l.addView(Kit.text(this, "Фильмы и сериалы появятся здесь сами", 16, Color.WHITE, true));
        boolean noKey = Kinopoisk.key(this).isEmpty();
        TextView t = Kit.text(this, noKey
                ? "Этот телевизор не отдаёт подборки приложений сторонним лаунчерам. Подключи бесплатный каталог Кинопоиска — "
                  + "появятся постеры, новинки, популярные фильмы и сериалы. «Смотреть» откроет фильм в КИОН, Иви, Кинопоиске или Wink."
                : catalog.status, 12, Kit.MUTED, false);
        t.setPadding(0, dp(6), 0, dp(10));
        l.addView(t);
        LinearLayout b = new LinearLayout(this);
        if (noKey) {
            b.addView(Kit.pill(this, R.drawable.ic_plus, "Подключить каталог", true, v -> { show(SETTINGS); settingsAction("Каталог фильмов", null); }));
            LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(-2, -2);
            g.setMarginStart(dp(10));
            b.addView(Kit.pill(this, R.drawable.ic_refresh, "Обновить", false, v -> refreshAll()), g);
        } else b.addView(Kit.pill(this, R.drawable.ic_refresh, "Обновить", true, v -> refreshAll()));
        l.addView(b);
        c.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    // ------------------------------------------------------------------ баннер-карусель

    private View banner(List<Program> items) {
        FrameLayout b = new FrameLayout(this);
        GradientDrawable bg = Kit.grad(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF0B1A4A, 0xFF1D3B8F, Kit.alpha(acc, 0xFF)}, dp(18));
        b.setBackground(bg);
        b.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(176));
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);

        ImageView img = Kit.image(this, ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(dp(560), -1, Gravity.END);
        b.addView(img, il);
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF0B1A4A, 0xE60B1A4A, 0x000B1A4A}));
        b.addView(fade, new FrameLayout.LayoutParams(dp(520), -1, Gravity.START));
        View fadeB = new View(this);
        fadeB.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0}));
        b.addView(fadeB, new FrameLayout.LayoutParams(-1, dp(60), Gravity.BOTTOM));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(26), dp(20), 0, 0);
        TextView src = Kit.text(this, "", 10, Kit.lighten(acc), true);
        TextView title = Kit.line(this, "", 30, Color.WHITE, true);
        TextView desc = Kit.text(this, "", 12, 0xFFC9D6F5, false);
        desc.setMaxLines(3);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(src);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(420), -2);
        tl.topMargin = dp(4);
        txt.addView(title, tl);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(360), -2);
        dl.topMargin = dp(6);
        txt.addView(desc, dl);
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(14), 0, 0);
        txt.addView(btns);
        b.addView(txt, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(10);
        b.addView(dots, dlp);

        if (items.isEmpty()) {
            src.setText("MIVISION TV");
            title.setText("Твоё кино — в одном месте");
            desc.setText("Обложки фильмов и сериалов из КИОН, Иви, Wink и других установленных приложений появятся здесь автоматически.");
            btns.addView(Kit.pill(this, R.drawable.ic_apps, "Мои приложения", true, v -> show(APPS)));
            return b;
        }
        final int[] idx = {0};
        Runnable fill = () -> {
            Program p = items.get(idx[0]);
            src.setText((p.isSeries() ? "СЕРИАЛ" : "ФИЛЬМ") + (p.score.isEmpty() ? "" : " · ★ " + p.score)
                    + (p.kpId != 0 ? "" : " · " + p.app.toUpperCase(new Locale("ru"))));
            title.setText(p.title);
            desc.setText(p.desc.isEmpty() ? meta(p) : p.desc);
            img.setImageDrawable(null);
            Img.load(this, p.wide(), img, dp(560));
            dots.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                View d = new View(this);
                d.setBackground(Kit.round(i == idx[0] ? Color.WHITE : 0x66FFFFFF, dp(9)));
                LinearLayout.LayoutParams dd = new LinearLayout.LayoutParams(dp(i == idx[0] ? 18 : 6), dp(6));
                dd.setMarginEnd(dp(5));
                dots.addView(d, dd);
            }
        };
        TextView watch = Kit.pill(this, R.drawable.ic_play, "Смотреть", true, v -> open(items.get(idx[0])));
        watch.setTag("banner:watch");
        TextView more = Kit.pill(this, R.drawable.ic_info, "Подробнее", false, v -> details(items.get(idx[0])));
        TextView favB = Kit.pill(this, R.drawable.ic_bookmark, "В избранное", false, v -> toggleFav(items.get(idx[0])));
        btns.addView(watch);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMarginStart(dp(10));
        btns.addView(more, gap);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-2, -2);
        gap2.setMarginStart(dp(10));
        btns.addView(favB, gap2);
        fill.run();
        // автосмена, но не пока пользователь на кнопках баннера
        bannerTick = new Runnable() {
            @Override public void run() {
                if (!btns.hasFocus()) { idx[0] = (idx[0] + 1) % items.size(); fill.run(); }
                h.postDelayed(this, 7000);
            }
        };
        h.postDelayed(bannerTick, 7000);
        // влево/вправо на кнопках — листать баннер
        View.OnKeyListener flip = (v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && v == watch) { idx[0] = (idx[0] + items.size() - 1) % items.size(); fill.run(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT && v == favB) { idx[0] = (idx[0] + 1) % items.size(); fill.run(); return true; }
            return false;
        };
        watch.setOnKeyListener(flip);
        favB.setOnKeyListener(flip);
        return b;
    }

    // ------------------------------------------------------------------ карточки

    private LinearLayout hrow(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setPadding(dp(4), dp(6), dp(30), dp(8));
        LinearLayout r = new LinearLayout(this);
        r.setClipChildren(false);
        hs.addView(r);
        parent.addView(hs);
        return r;
    }

    private LinearLayout.LayoutParams lpH(int w, int hgt) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), hgt < 0 ? hgt : dp(hgt));
        lp.setMarginEnd(dp(10));
        return lp;
    }

    private View appTile(App a, int w, int hgt) {
        FrameLayout t = new FrameLayout(this);
        t.setTag("app:" + a.pkg);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable bg = Kit.round(colorFor(a.pkg), dp(12));
        t.setBackground(bg);
        t.setClipToOutline(true);
        ImageView iv = Kit.image(this, ImageView.ScaleType.FIT_CENTER);
        if (a.banner != null) {
            iv.setImageDrawable(a.banner);
            t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setGravity(Gravity.CENTER);
            iv.setImageDrawable(a.icon);
            l.addView(iv, new LinearLayout.LayoutParams(dp(hgt * 0.5f), dp(hgt * 0.5f)));
            TextView n = Kit.line(this, a.label, 10, Color.WHITE, true);
            n.setPadding(dp(4), dp(3), dp(4), 0);
            l.addView(n);
            t.addView(l, new FrameLayout.LayoutParams(-1, -1));
        }
        Kit.glow(t, bg, 1.08f, null);
        t.setOnClickListener(v -> launch(a));
        t.setOnLongClickListener(v -> { appMenu(a); return true; });
        return t;
    }

    private View moreTile() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setTag("app:more");
        t.setFocusable(true);
        GradientDrawable bg = Kit.round(0x24FFFFFF, dp(12));
        t.setBackground(bg);
        t.addView(Kit.icon(this, R.drawable.ic_apps, Color.WHITE), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView more = Kit.text(this, "Ещё", 11, Color.WHITE, false);
        more.setPadding(0, dp(4), 0, 0);
        t.addView(more);
        Kit.glow(t, bg, 1.08f, null);
        t.setOnClickListener(v -> show(APPS));
        return t;
    }

    /** Плитка фильма: из каталога — вертикальный постер, из приложения — широкая карточка. */
    private View tile(Program p) {
        if (p.kpId != 0) { View v = posterTile(p, 100, 150); v.setLayoutParams(lpH(100, 150)); return v; }
        View v = posterTile(p, 136, 86); v.setLayoutParams(lpH(136, 86)); return v;
    }

    private View posterTile(Program p, int w, int hgt) {
        FrameLayout t = new FrameLayout(this);
        t.setTag("prog:" + p.key());
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable bg = Kit.round(colorFor(p.pkg), dp(10));
        t.setBackground(bg);
        t.setClipToOutline(true);
        ImageView iv = Kit.image(this, ImageView.ScaleType.CENTER_CROP);
        t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        Img.load(this, p.poster, iv, dp(w));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xDD000000, 0}));
        t.addView(shade, new FrameLayout.LayoutParams(-1, dp(hgt * 0.6f), Gravity.BOTTOM));
        TextView title = Kit.text(this, p.title, w > 120 ? 13 : 12, Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tl.setMargins(dp(8), 0, dp(8), dp(p.watchNext ? 10 : 7));
        t.addView(title, tl);
        TextView badge = Kit.text(this, p.kpId != 0 ? (p.score.isEmpty() ? "" : "★ " + p.score) : p.app, 9, Color.WHITE, p.kpId != 0);
        if (p.kpId != 0 && p.score.isEmpty()) badge.setVisibility(View.GONE);
        badge.setBackground(Kit.round(0x88000000, dp(100)));
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        bl.setMargins(0, dp(5), dp(5), 0);
        t.addView(badge, bl);
        if (p.watchNext && p.progressPct() > 0) {
            View prog = new View(this);
            prog.setBackgroundColor(Kit.lighten(acc));
            t.addView(prog, new FrameLayout.LayoutParams(dp(w * p.progressPct() / 100f), dp(3), Gravity.BOTTOM));
        }
        Kit.glow(t, bg, 1.07f, null);
        t.setOnClickListener(v -> details(p));
        t.setOnLongClickListener(v -> { open(p); return true; });
        return t;
    }

    /** Вертикальный постер для страниц «Фильмы» и «Сериалы». */
    private View vPoster(Program p) {
        View t = posterTile(p, 112, 168);
        t.setLayoutParams(lpH(112, 168));
        return t;
    }

    // ================================================================== ФИЛЬМЫ / СЕРИАЛЫ

    private String movieCat = "Все";

    private View pageMovies(boolean series) {
        List<Program> src = series ? catalog.series() : catalog.movies();
        LinearLayout wrap = new LinearLayout(this);

        // категории слева
        LinearLayout cats = new LinearLayout(this);
        cats.setOrientation(LinearLayout.VERTICAL);
        cats.setPadding(0, dp(8), dp(14), 0);
        TextView hdr = Kit.text(this, series ? "Сериалы" : "Фильмы", 30, Color.WHITE, true);
        hdr.setPadding(dp(4), 0, 0, dp(10));
        cats.addView(hdr);
        TextView search = Kit.pill(this, R.drawable.ic_search, "Поиск", false, v -> show(SEARCH));
        cats.addView(search);
        Map<String, List<Program>> genres = catalog.byGenre(src);
        List<String> names = new ArrayList<>();
        names.add("Все");
        if (!catalog.watchNext.isEmpty()) names.add("Продолжить");
        names.addAll(genres.keySet());
        for (String app : appsIn(src)) names.add("Из " + app);
        if (!names.contains(movieCat)) movieCat = "Все";
        for (String n : names) {
            TextView c = Kit.line(this, n, 13, n.equals(movieCat) ? Color.WHITE : Kit.MUTED, n.equals(movieCat));
            c.setTag("cat:" + n);
            c.setFocusable(true);
            c.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable g = Kit.round(n.equals(movieCat) ? Kit.alpha(acc, 0x55) : 0, dp(10));
            c.setBackground(g);
            c.setOnFocusChangeListener((v, f) -> g.setColor(f ? Kit.alpha(acc, 0xAA) : n.equals(movieCat) ? Kit.alpha(acc, 0x55) : 0));
            c.setOnClickListener(v -> { movieCat = n; lastFocus.put(page, "cat:" + n); show(page); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(170), -2);
            lp.topMargin = dp(4);
            cats.addView(c, lp);
        }
        ScrollView cs = new ScrollView(this);
        cs.setVerticalScrollBarEnabled(false);
        cs.addView(cats);
        wrap.addView(cs, new LinearLayout.LayoutParams(dp(190), -1));

        // постеры справа
        LinearLayout col = new LinearLayout(this);
        ScrollView s = scroller(col);
        List<Program> list;
        if ("Все".equals(movieCat)) list = src;
        else if ("Продолжить".equals(movieCat)) { list = new ArrayList<>(); for (Program p : catalog.watchNext) if (series == p.isSeries()) list.add(p); }
        else if (movieCat.startsWith("Из ")) { list = new ArrayList<>(); for (Program p : src) if (("Из " + p.app).equals(movieCat)) list.add(p); }
        else list = genres.containsKey(movieCat) ? genres.get(movieCat) : new ArrayList<>();

        if (list.isEmpty()) {
            col.addView(catalogHint());
        } else {
            col.addView(Kit.rowTitle(this, movieCat.equals("Все") ? (series ? "Все сериалы" : "Все фильмы") : movieCat,
                    list.size() + " · из " + appsIn(list).size() + " прил.", null));
            for (int i = 0; i < list.size(); i += 6) {
                LinearLayout r = hrow(col);
                for (Program p : list.subList(i, Math.min(list.size(), i + 6))) r.addView(vPoster(p));
            }
        }
        wrap.addView(s, new LinearLayout.LayoutParams(0, -1, 1));
        return wrap;
    }

    private List<String> appsIn(List<Program> l) {
        List<String> out = new ArrayList<>();
        for (Program p : l) if (!out.contains(p.app)) out.add(p.app);
        return out;
    }

    // ================================================================== ПРИЛОЖЕНИЯ

    private View pageApps() {
        LinearLayout col = new LinearLayout(this);
        ScrollView s = scroller(col);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(8), 0, dp(4));
        head.addView(Kit.text(this, "Все приложения", 30, Color.WHITE, true));
        TextView cnt = Kit.text(this, "  · " + apps.size() + " установлено", 13, Kit.MUTED, false);
        head.addView(cnt, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Kit.pill(this, R.drawable.ic_search, "Поиск приложений", false, v -> show(SEARCH)));
        col.addView(head);

        List<App> fav = favApps();
        if (!fav.isEmpty()) {
            col.addView(Kit.rowTitle(this, "★ Избранное", "удерживай OK — изменить", null));
            LinearLayout r = hrow(col);
            for (App a : fav) r.addView(appTile(a, 128, 72), lpH(128, 72));
        }
        col.addView(Kit.rowTitle(this, "Все", null, null));
        for (int i = 0; i < apps.size(); i += 6) {
            LinearLayout r = hrow(col);
            for (App a : apps.subList(i, Math.min(apps.size(), i + 6))) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setClipChildren(false);
                cell.addView(appTile(a, 128, 72), new LinearLayout.LayoutParams(dp(128), dp(72)));
                TextView n = Kit.line(this, a.label, 11, 0xFFC9D6F5, false);
                n.setGravity(Gravity.CENTER);
                n.setPadding(0, dp(5), 0, 0);
                cell.addView(n, new LinearLayout.LayoutParams(dp(128), -2));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(128), -2);
                lp.setMarginEnd(dp(12));
                r.addView(cell, lp);
            }
        }
        return s;
    }

    // ================================================================== ТВ-КАНАЛЫ

    private String tvGroup = "Все";

    private View pageTv() {
        LinearLayout col = new LinearLayout(this);
        ScrollView s = scroller(col);
        TextView hdr = Kit.text(this, "ТВ-каналы", 30, Color.WHITE, true);
        hdr.setPadding(dp(4), dp(8), 0, dp(4));
        col.addView(hdr);

        // приложения для ТВ, которые уже стоят
        List<App> tvApps = new ArrayList<>();
        for (App a : apps) if (HomeCats.TV.equals(a.cat)) tvApps.add(a);
        if (!tvApps.isEmpty()) {
            col.addView(Kit.rowTitle(this, "ТВ-приложения", null, null));
            LinearLayout r = hrow(col);
            for (App a : tvApps) r.addView(appTile(a, 128, 72), lpH(128, 72));
        }

        if (Tv.url(this).isEmpty() || channels.isEmpty()) {
            FrameLayout c = Kit.card(this, 18);
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(dp(24), dp(20), dp(24), dp(20));
            l.addView(Kit.text(this, "Подключи источник каналов", 20, Color.WHITE, true));
            TextView t = Kit.text(this, tvErr.isEmpty()
                    ? "Вставь адрес плейлиста M3U от своего провайдера или IPTV-сервиса — каналы, логотипы и группы появятся здесь. "
                      + "Смотреть можно и в установленных ТВ-приложениях выше."
                    : "Не получилось открыть плейлист: " + tvErr, 13, Kit.MUTED, false);
            t.setPadding(0, dp(8), 0, dp(14));
            l.addView(t);
            LinearLayout b = new LinearLayout(this);
            b.addView(Kit.pill(this, R.drawable.ic_plus, "Подключить плейлист", true, v -> askPlaylist()));
            l.addView(b);
            c.addView(l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(16);
            col.addView(c, lp);
            return s;
        }

        Map<String, List<Tv.Channel>> groups = Tv.groups(channels);
        LinearLayout gr = hrow(col);
        List<String> gnames = new ArrayList<>();
        gnames.add("Все");
        gnames.addAll(groups.keySet());
        if (!gnames.contains(tvGroup)) tvGroup = "Все";
        for (String g : gnames) {
            TextView p = Kit.pill(this, g, g.equals(tvGroup), v -> { tvGroup = g; lastFocus.put(TV, "tvg:" + g); show(TV); });
            p.setTag("tvg:" + g);
            gr.addView(p, lpH(-2, -2));
        }
        List<Tv.Channel> list = "Все".equals(tvGroup) ? channels : groups.get(tvGroup);
        col.addView(Kit.rowTitle(this, tvGroup + " · " + list.size(), "сменить плейлист", v -> askPlaylist()));
        for (Tv.Channel ch : list) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTag("ch:" + ch.url);
            row.setFocusable(true);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable bg = Kit.round(Kit.CARD, dp(12));
            row.setBackground(bg);
            ImageView logo = Kit.image(this, ImageView.ScaleType.FIT_CENTER);
            logo.setBackground(Kit.round(0x22FFFFFF, dp(8)));
            row.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(38)));
            Img.load(this, ch.logo, logo, dp(64));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(14), 0, 0, 0);
            tx.addView(Kit.line(this, ch.name, 15, Color.WHITE, true));
            tx.addView(Kit.line(this, ch.group, 11, Kit.MUTED, false));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            Kit.glow(row, bg, 1.02f, null);
            row.setOnClickListener(v -> play(ch));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            col.addView(row, lp);
        }
        return s;
    }

    private void askPlaylist() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("https://…/playlist.m3u");
        e.setText(Tv.url(this));
        new AlertDialog.Builder(this)
                .setTitle("Адрес плейлиста M3U")
                .setView(e)
                .setPositiveButton("Подключить", (d, w) -> {
                    Tv.setUrl(this, e.getText().toString());
                    new Thread(() -> {
                        try { List<Tv.Channel> l = Tv.load(this, true); h.post(() -> { channels = l; tvErr = l.isEmpty() ? "в плейлисте нет каналов" : ""; show(TV); }); }
                        catch (Throwable t) { h.post(() -> { channels = new ArrayList<>(); tvErr = String.valueOf(t.getMessage()); show(TV); }); }
                    }).start();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void play(Tv.Channel ch) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(ch.url), "video/*");
        try { startActivity(Intent.createChooser(i, ch.name)); }
        catch (Throwable t) { Ui.toast(this, "Нет плеера для видео — поставь, например, VLC из RuStore"); }
    }

    // ================================================================== ПОИСК

    private String searchTab = "Всё";

    private View pageSearch() {
        LinearLayout col = new LinearLayout(this);
        ScrollView s = scroller(col);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, dp(6));
        EditText q = new EditText(this);
        q.setTag("search:q");
        q.setSingleLine(true);
        q.setHint("Фильм, сериал или приложение");
        q.setHintTextColor(Kit.MUTED);
        q.setTextColor(Color.WHITE);
        q.setTextSize(20);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable qbg = Kit.round(0x1FFFFFFF, dp(14));
        qbg.setStroke(dp(2), Kit.lighten(acc));
        q.setBackground(qbg);
        q.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar.addView(q, new LinearLayout.LayoutParams(0, -2, 1));
        boolean voice = !getPackageManager().queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
        if (voice) {
            TextView mic = Kit.pill(this, R.drawable.ic_mic, "", false, v -> {
                Intent vi = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
                try { startActivityForResult(vi, 42); } catch (Throwable t) { Ui.toast(this, "Голосовой ввод недоступен"); }
            });
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.setMarginStart(dp(10));
            bar.addView(mic, ml);
        }
        col.addView(bar);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, dp(4), 0, dp(4));
        col.addView(tabs);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        col.addView(results);

        Runnable[] run = new Runnable[1];
        run[0] = () -> {
            tabs.removeAllViews();
            for (String t : new String[]{"Всё", "Фильмы", "Сериалы", "Приложения"}) {
                TextView p = Kit.pill(this, t, t.equals(searchTab), v -> { searchTab = t; run[0].run(); });
                p.setTag("stab:" + t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMarginEnd(dp(8));
                tabs.addView(p, lp);
            }
            results.removeAllViews();
            String text = q.getText().toString().trim();
            if (text.isEmpty()) {
                List<String> recent = Kit.list(this, "recent");
                if (!recent.isEmpty()) {
                    results.addView(Kit.rowTitle(this, "Недавние запросы", null, null));
                    LinearLayout r = hrow(results);
                    for (String rq : recent) r.addView(Kit.pill(this, R.drawable.ic_history, rq, false, v -> { q.setText(rq); q.setSelection(rq.length()); }), lpH(-2, -2));
                }
                if (!catalog.all.isEmpty()) {
                    results.addView(Kit.rowTitle(this, "Популярное сейчас", null, null));
                    LinearLayout r = hrow(results);
                    for (Program p : mix(catalog.all, 8)) r.addView(tile(p));
                }
                return;
            }
            List<Program> progs = catalog.search(text);
            List<Program> mv = new ArrayList<>(), sr = new ArrayList<>();
            for (Program p : progs) (p.isSeries() ? sr : mv).add(p);
            List<App> ap = new ArrayList<>();
            for (App a : apps) if (a.label.toLowerCase().contains(text.toLowerCase())) ap.add(a);
            boolean all = "Всё".equals(searchTab);
            int shown = 0;
            if ((all || "Фильмы".equals(searchTab)) && !mv.isEmpty()) {
                results.addView(Kit.rowTitle(this, "Фильмы · " + mv.size(), null, null));
                LinearLayout r = hrow(results);
                for (Program p : mv) r.addView(tile(p));
                shown++;
            }
            if ((all || "Сериалы".equals(searchTab)) && !sr.isEmpty()) {
                results.addView(Kit.rowTitle(this, "Сериалы · " + sr.size(), null, null));
                LinearLayout r = hrow(results);
                for (Program p : sr) r.addView(tile(p));
                shown++;
            }
            if ((all || "Приложения".equals(searchTab)) && !ap.isEmpty()) {
                results.addView(Kit.rowTitle(this, "Приложения · " + ap.size(), null, null));
                LinearLayout r = hrow(results);
                for (App a : ap) r.addView(appTile(a, 116, 54), lpH(116, 54));
                shown++;
            }
            if (shown == 0) {
                TextView none = Kit.text(this, "Ничего не нашлось. Поиск идёт по подборкам установленных приложений и по самим приложениям.", 13, Kit.MUTED, false);
                none.setPadding(dp(4), dp(16), 0, 0);
                results.addView(none);
            }
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s1, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s1, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { run[0].run(); }
        });
        q.setOnEditorActionListener((v, id, e) -> {
            String text = q.getText().toString().trim();
            if (!text.isEmpty() && !Kinopoisk.key(this).isEmpty()) new Thread(() -> {
                try {
                    List<Program> found = Kinopoisk.search(this, text);
                    h.post(() -> {
                        if (found.isEmpty() || !text.equals(q.getText().toString().trim())) return;
                        results.addView(Kit.rowTitle(this, "В Кинопоиске · " + found.size(), null, null));
                        LinearLayout r = hrow(results);
                        for (Program p : found) r.addView(tile(p));
                    });
                } catch (Throwable ignored) { }
            }).start();
            if (!text.isEmpty()) {
                List<String> rec = Kit.list(this, "recent");
                rec.remove(text); rec.add(0, text);
                while (rec.size() > 8) rec.remove(rec.size() - 1);
                Kit.saveList(this, "recent", rec);
            }
            return false;
        });
        searchField = q;
        run[0].run();
        return s;
    }

    private EditText searchField;
    private final ru.chex.tvturbo.mv.PhoneInput phone = new ru.chex.tvturbo.mv.PhoneInput();

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 42 && res == RESULT_OK && data != null && searchField != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) { searchField.setText(r.get(0)); searchField.setSelection(r.get(0).length()); }
        }
    }

    // ================================================================== НАСТРОЙКИ

    private static final String[] SET_MENU = {"Внешний вид", "Обои", "Каталог фильмов", "Погода и регион", "Приложения", "Избранное",
            "Дата и время", "Системные настройки", "ТВ Турбо", "О MiVision TV"};

    private View pageSettings(String section) {
        LinearLayout wrap = new LinearLayout(this);
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(8), dp(18), 0);
        TextView hdr = Kit.text(this, "Настройки", 30, Color.WHITE, true);
        hdr.setPadding(dp(4), 0, 0, dp(12));
        menu.addView(hdr);
        FrameLayout panel = Kit.card(this, 20);
        for (String m : SET_MENU) {
            TextView t = Kit.line(this, m, 14, m.equals(section) ? Color.WHITE : Kit.MUTED, m.equals(section));
            t.setTag("set:" + m);
            t.setFocusable(true);
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable g = Kit.round(m.equals(section) ? Kit.alpha(acc, 0x55) : 0, dp(12));
            t.setBackground(g);
            t.setOnFocusChangeListener((v, f) -> g.setColor(f ? Kit.alpha(acc, 0xAA) : m.equals(section) ? Kit.alpha(acc, 0x55) : 0));
            t.setOnClickListener(v -> settingsAction(m, panel));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), -2);
            lp.topMargin = dp(3);
            menu.addView(t, lp);
        }
        wrap.addView(menu);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, -1, 1);
        pl.setMargins(0, dp(14), dp(22), dp(24));
        wrap.addView(panel, pl);
        fillSettings(section, panel);
        return wrap;
    }

    private void settingsAction(String m, FrameLayout panel) {
        switch (m) {
            case "Приложения": show(APPS); return;
            case "Избранное": show(FAV); return;
            case "Дата и время": safeStart(new Intent(android.provider.Settings.ACTION_DATE_SETTINGS)); return;
            case "Системные настройки": safeStart(new Intent(android.provider.Settings.ACTION_SETTINGS)); return;
            case "ТВ Турбо": safeStart(new Intent(this, MainActivity.class)); return;
            default:
                lastFocus.put(SETTINGS, "set:" + m);
                content.removeAllViews();
                View v = pageSettings(m);
                content.addView(v, new FrameLayout.LayoutParams(-1, -1));
                restoreFocus(v);
        }
    }

    private void fillSettings(String section, FrameLayout panel) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(18), dp(24), dp(18));
        l.addView(Kit.text(this, section, 22, Color.WHITE, true));
        if (!"Каталог фильмов".equals(section)) phone.stop();
        switch (section) {
            case "Каталог фильмов": {
                boolean has = !Kinopoisk.key(this).isEmpty();
                TextView st = Kit.text(this, has ? "Подключён. " + (catalog.fromKp ? catalog.status : "") : "Не подключён", 14, has ? 0xFF3DDC84 : 0xFFFFB020, true);
                st.setPadding(0, dp(10), 0, dp(8));
                l.addView(st);
                TextView how = Kit.text(this, "Постеры, описания, новинки и популярное берутся из бесплатного каталога Кинопоиска "
                        + "(Kinopoisk API Unofficial). Нужен личный ключ — это бесплатно, 2 минуты:\n\n"
                        + "1. На телефоне открой kinopoiskapiunofficial.tech и зарегистрируйся по почте.\n"
                        + "2. В профиле скопируй «API Key».\n"
                        + "3. Открой на телефоне адрес ниже и вставь ключ — он сам придёт на телевизор.", 13, 0xFFC9D6F5, false);
                l.addView(how);
                String url = phone.start(this, "Вставь ключ Kinopoisk API Unofficial", v -> {
                    Kinopoisk.setKey(this, v);
                    new java.io.File(getFilesDir(), "mv_catalog.json").delete();
                    Ui.toast(this, "Ключ получен, загружаю фильмы…");
                    refreshAll();
                    settingsAction("Каталог фильмов", panel);
                });
                TextView addr = Kit.text(this, url == null ? "Нет сети" : url, 22, Kit.lighten(acc), true);
                addr.setPadding(0, dp(14), 0, dp(14));
                l.addView(addr);
                LinearLayout b = new LinearLayout(this);
                b.addView(Kit.pill(this, R.drawable.ic_plus, "Ввести с пульта", false, v -> {
                    EditText e = new EditText(this);
                    e.setSingleLine(true);
                    e.setText(Kinopoisk.key(this));
                    new AlertDialog.Builder(this).setTitle("Ключ Kinopoisk API").setView(e)
                            .setPositiveButton("Сохранить", (d, w) -> { Kinopoisk.setKey(this, e.getText().toString());
                                new java.io.File(getFilesDir(), "mv_catalog.json").delete(); refreshAll(); settingsAction("Каталог фильмов", panel); })
                            .setNegativeButton("Отмена", null).show();
                }));
                if (has) {
                    LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(-2, -2);
                    g.setMarginStart(dp(10));
                    b.addView(Kit.pill(this, R.drawable.ic_refresh, "Обновить фильмы", true, v -> {
                        new java.io.File(getFilesDir(), "mv_catalog.json").delete(); refreshAll(); Ui.toast(this, "Обновляю…"); }), g);
                }
                l.addView(b);
                break;
            }
            case "Обои": {
                l.addView(Kit.rowTitle(this, "Фон главного экрана", null, null));
                LinearLayout r = hrow(l);
                for (int i = 0; i < Kit.WALLS.length; i++) {
                    int idx = i;
                    LinearLayout cell = new LinearLayout(this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    FrameLayout sw = new FrameLayout(this);
                    sw.setTag("wall:" + i);
                    sw.setFocusable(true);
                    GradientDrawable g = Kit.grad(GradientDrawable.Orientation.TL_BR, Kit.WALLS[i], dp(12));
                    if (Kit.sp(this).getInt("wall", 0) == i) g.setStroke(dp(3), Color.WHITE);
                    sw.setBackground(g);
                    Kit.glow(sw, null, 1.08f, null);
                    sw.setOnClickListener(v -> { Kit.sp(this).edit().putInt("wall", idx).apply(); applyWall(); settingsAction("Обои", panel); });
                    cell.addView(sw, new LinearLayout.LayoutParams(dp(130), dp(74)));
                    TextView n = Kit.line(this, Kit.WALL_NAMES[i], 11, Kit.MUTED, false);
                    n.setPadding(0, dp(5), 0, 0);
                    cell.addView(n);
                    r.addView(cell, lpH(130, -2));
                }
                break;
            }
            case "Погода и регион": {
                TextView cur = Kit.text(this, "Сейчас: " + (Weather.cityName(this).isEmpty() ? "город не выбран" : Weather.cityName(this))
                        + (Boolean.TRUE.equals(Weather.autoAllowed(this)) ? " (определяется автоматически)" : ""), 14, Kit.MUTED, false);
                cur.setPadding(0, dp(10), 0, dp(14));
                l.addView(cur);
                LinearLayout b = new LinearLayout(this);
                b.addView(Kit.pill(this, R.drawable.ic_pin, "Определить автоматически", true, v -> {
                    Weather.setAuto(this, true);
                    getSharedPreferences("mv_weather", MODE_PRIVATE).edit().remove("manual").remove("locAt").remove("lat").apply();
                    loadWeather(); Ui.toast(this, "Определяю город…");
                }));
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
                gp.setMarginStart(dp(10));
                b.addView(Kit.pill(this, R.drawable.ic_search, "Выбрать город", false, v -> chooseCity()), gp);
                LinearLayout.LayoutParams gp2 = new LinearLayout.LayoutParams(-2, -2);
                gp2.setMarginStart(dp(10));
                b.addView(Kit.pill(this, R.drawable.wx_cloudsun, "Прогноз", false, v -> show(WEATHER)), gp2);
                l.addView(b);
                break;
            }
            case "О MiVision TV": {
                TextView t = Kit.text(this, "MiVision TV — главный экран ТВ Турбо, версия " + Updater.currentName(this) + ".\n\n"
                        + "• Приложения — только установленные на этом телевизоре.\n"
                        + "• Фильмы и сериалы — из подборок приложений или каталога Кинопоиска; «Смотреть» открывает КИОН, Иви, Кинопоиск, Wink.\n"
                        + "• Погода — Open-Meteo, met.no, wttr.in (что доступно).\n"
                        + "• Каналы — из подключённого тобой плейлиста.\n\n"
                        + "Без рекламы и подписок. Обновляется вместе с ТВ Турбо.", 13, 0xFFC9D6F5, false);
                t.setPadding(0, dp(12), 0, 0);
                l.addView(t);
                break;
            }
            default: { // Внешний вид
                l.addView(Kit.rowTitle(this, "Акцентный цвет", null, null));
                LinearLayout r = hrow(l);
                for (int i = 0; i < Kit.ACCENTS.length; i++) {
                    int idx = i;
                    View c = new View(this);
                    c.setTag("acc:" + i);
                    c.setFocusable(true);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(Kit.ACCENTS[i]);
                    if (Kit.sp(this).getInt("accent", 0) == i) g.setStroke(dp(3), Color.WHITE);
                    c.setBackground(g);
                    Kit.glow(c, null, 1.15f, null);
                    c.setOnClickListener(v -> { Kit.sp(this).edit().putInt("accent", idx).apply(); acc = Kit.accent(this); lastFocus.put(SETTINGS, "acc:" + idx); buildShell(); show(SETTINGS); });
                    r.addView(c, lpH(46, 46));
                }
                l.addView(Kit.rowTitle(this, "Размер интерфейса", null, null));
                LinearLayout rs = hrow(l);
                for (int i = 0; i < BaseActivity.SCALE_NAMES.length; i++) {
                    int idx = i;
                    TextView p = Kit.pill(this, BaseActivity.SCALE_NAMES[i], BaseActivity.scaleIndex(this) == i,
                            v -> { BaseActivity.setScaleIndex(this, idx); recreate(); });
                    rs.addView(p, lpH(-2, -2));
                }
                l.addView(Kit.rowTitle(this, "Главный экран", null, null));
                LinearLayout rh = hrow(l);
                rh.addView(Kit.pill(this, R.drawable.ic_home, "Другие лаунчеры", false, v -> safeStart(new Intent(this, LauncherActivity.class))), lpH(-2, -2));
            }
        }
        panel.addView(l);
    }

    private void chooseCity() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("Например: Калуга");
        new AlertDialog.Builder(this)
                .setTitle("Город для погоды")
                .setView(e)
                .setPositiveButton("Найти", (d, w) -> {
                    String q = e.getText().toString().trim();
                    if (q.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            List<Weather.City> l = Weather.search(q);
                            h.post(() -> {
                                if (l.isEmpty()) { Ui.toast(this, "Город не найден"); return; }
                                String[] names = new String[l.size()];
                                for (int i = 0; i < l.size(); i++) names[i] = l.get(i).name + " — " + l.get(i).region;
                                new AlertDialog.Builder(this).setTitle("Выбери город")
                                        .setItems(names, (d2, w2) -> { Weather.setAuto(this, false); Weather.setCity(this, l.get(w2)); loadWeather(); })
                                        .show();
                            });
                        } catch (Throwable t) { h.post(() -> Ui.toast(this, "Нет связи с сервером погоды")); }
                    }).start();
                })
                .setNegativeButton("Отмена", null).show();
    }

    // ================================================================== ПОГОДА

    private View pageWeather() {
        LinearLayout col = new LinearLayout(this);
        ScrollView s = scroller(col);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(10), 0, 0);
        head.addView(Kit.text(this, (weather != null ? weather.city : Weather.cityName(this)), 18, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Kit.pill(this, R.drawable.ic_pin, "Сменить город", false, v -> chooseCity()));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.setMarginStart(dp(10));
        head.addView(Kit.pill(this, R.drawable.ic_refresh, "", false, v -> { loadWeather(); Ui.toast(this, "Обновляю…"); }), rp);
        col.addView(head);

        if (weather == null) {
            TextView t = Kit.text(this, weatherErr.isEmpty() ? "Загружаю погоду…" : "Нет данных о погоде: " + weatherErr, 16, Kit.MUTED, false);
            t.setPadding(dp(4), dp(30), 0, 0);
            col.addView(t);
            return s;
        }
        LinearLayout big = new LinearLayout(this);
        big.setGravity(Gravity.CENTER_VERTICAL);
        big.setPadding(dp(4), dp(10), 0, dp(6));
        big.addView(Kit.icon(this, Weather.icon(weather.code), 0), new LinearLayout.LayoutParams(dp(110), dp(110)));
        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.setPadding(dp(18), 0, 0, 0);
        bt.addView(Kit.text(this, Weather.t(weather.temp), 64, Color.WHITE, true));
        bt.addView(Kit.text(this, Weather.text(weather.code), 16, 0xFFC9D6F5, false));
        bt.addView(Kit.text(this, "Ощущается как " + Weather.t(weather.feels), 14, Kit.MUTED, false));
        big.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout facts = new LinearLayout(this);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.addView(fact("Ветер", Math.round(weather.wind) + " м/с, " + Weather.windDir(weather.windDir)));
        facts.addView(fact("Влажность", Math.round(weather.humidity) + "%"));
        if (weather.pressureMm > 0) facts.addView(fact("Давление", Math.round(weather.pressureMm) + " мм рт. ст."));
        big.addView(facts, new LinearLayout.LayoutParams(dp(260), -2));
        col.addView(big);

        col.addView(Kit.rowTitle(this, "По часам", null, null));
        LinearLayout hr = hrow(col);
        boolean first = true;
        for (double[] x : weather.hours) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(0, dp(8), 0, dp(8));
            c.setFocusable(true);
            c.setTag("wh:" + (int) x[0]);
            GradientDrawable g = Kit.round(Kit.CARD, dp(12));
            c.setBackground(g);
            Kit.glow(c, g, 1.06f, null);
            c.addView(Kit.text(this, first ? "Сейчас" : String.format(Locale.US, "%02d:00", (int) x[0]), 11, Kit.MUTED, false));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(30), dp(30));
            il.setMargins(0, dp(4), 0, dp(4));
            c.addView(Kit.icon(this, Weather.icon((int) x[2]), 0), il);
            c.addView(Kit.text(this, Weather.t(x[1]), 15, Color.WHITE, true));
            hr.addView(c, lpH(64, -2));
            first = false;
        }
        col.addView(Kit.rowTitle(this, "Прогноз на " + weather.days.size() + " дней", null, null));
        String[] wd = {"", "Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
        String[] mon = {"янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};
        for (int i = 0; i < weather.days.size(); i++) {
            double[] d = weather.days.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(8), dp(14), dp(8));
            r.setFocusable(true);
            r.setTag("wd:" + i);
            GradientDrawable g = Kit.round(Kit.CARD, dp(10));
            r.setBackground(g);
            Kit.glow(r, g, 1.01f, null);
            r.addView(Kit.text(this, i == 0 ? "Сегодня" : wd[(int) d[0]] + ", " + (int) d[4] + " " + mon[(int) d[5]], 14, Color.WHITE, i == 0), new LinearLayout.LayoutParams(dp(140), -2));
            LinearLayout.LayoutParams dl2 = new LinearLayout.LayoutParams(dp(26), dp(26));
            dl2.setMarginEnd(dp(14));
            r.addView(Kit.icon(this, Weather.icon((int) d[3]), 0), dl2);
            r.addView(Kit.text(this, Weather.text((int) d[3]), 13, Kit.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(Kit.text(this, Weather.t(d[1]) + " / " + Weather.t(d[2]), 14, Color.WHITE, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            col.addView(r, lp);
        }
        TextView srcT = Kit.text(this, "Данные: " + weather.source + " · обновлено " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(weather.fetchedAt)), 10, Kit.MUTED, false);
        srcT.setPadding(dp(4), dp(8), 0, 0);
        col.addView(srcT);
        return s;
    }

    private View fact(String a, String b) {
        LinearLayout l = new LinearLayout(this);
        l.setPadding(0, dp(3), 0, dp(3));
        l.addView(Kit.text(this, a, 12, Kit.MUTED, false), new LinearLayout.LayoutParams(dp(110), -2));
        l.addView(Kit.text(this, b, 13, Color.WHITE, true));
        return l;
    }

    // ================================================================== ИЗБРАННОЕ

    private View pageFav() {
        LinearLayout col = new LinearLayout(this);
        ScrollView s = scroller(col);
        TextView hdr = Kit.text(this, "Избранное", 30, Color.WHITE, true);
        hdr.setPadding(dp(4), dp(8), 0, 0);
        col.addView(hdr);
        List<Program> fp = favPrograms();
        List<Program> mv = new ArrayList<>(), sr = new ArrayList<>();
        for (Program p : fp) (p.isSeries() ? sr : mv).add(p);
        if (!mv.isEmpty()) {
            col.addView(Kit.rowTitle(this, "Фильмы · " + mv.size(), "открой карточку, чтобы убрать", null));
            LinearLayout r = hrow(col);
            for (Program p : mv) r.addView(tile(p));
        }
        if (!sr.isEmpty()) {
            col.addView(Kit.rowTitle(this, "Сериалы · " + sr.size(), null, null));
            LinearLayout r = hrow(col);
            for (Program p : sr) r.addView(tile(p));
        }
        List<App> fa = favApps();
        col.addView(Kit.rowTitle(this, "Приложения · " + fa.size(), "удерживай OK — убрать", null));
        if (!fa.isEmpty()) {
            LinearLayout r = hrow(col);
            for (App a : fa) r.addView(appTile(a, 116, 54), lpH(116, 54));
        }
        if (fp.isEmpty() && fa.isEmpty()) {
            TextView t = Kit.text(this, "Пока пусто. Добавляй фильмы кнопкой «В избранное», а приложения — долгим нажатием OK.", 14, Kit.MUTED, false);
            t.setPadding(dp(4), dp(16), 0, 0);
            col.addView(t);
        }
        return s;
    }

    private List<Program> favPrograms() {
        List<Program> l = new ArrayList<>();
        for (String j : Kit.list(this, "favProg")) try { l.add(Program.fromJson(new JSONObject(j))); } catch (Throwable ignored) { }
        return l;
    }

    boolean isFav(Program p) {
        for (Program x : favPrograms()) if (x.key().equals(p.key())) return true;
        return false;
    }

    void toggleFav(Program p) {
        List<String> l = Kit.list(this, "favProg");
        boolean removed = false;
        for (int i = l.size() - 1; i >= 0; i--) {
            try { if (Program.fromJson(new JSONObject(l.get(i))).key().equals(p.key())) { l.remove(i); removed = true; } } catch (Throwable ignored) { }
        }
        if (!removed) l.add(0, p.toJson().toString());
        Kit.saveList(this, "favProg", l);
        Ui.toast(this, removed ? "Убрано из избранного" : "Добавлено в избранное");
    }

    // ================================================================== действия

    /** Открыть фильм в приложении, которое его опубликовало (или выбрать, где смотреть). */
    private void open(Program p) {
        if (p.pkg.isEmpty()) { Watch.choose(this, p); return; }
        Intent i = null;
        try { if (!p.intent.isEmpty()) i = Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME); } catch (Throwable ignored) { }
        if (i == null) {
            i = getPackageManager().getLeanbackLaunchIntentForPackage(p.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(p.pkg);
        }
        if (i == null) { Ui.toast(this, "Приложение " + p.app + " не найдено"); return; }
        safeStart(i);
    }

    private void details(Program p) {
        Intent i = new Intent(this, MvDetailsActivity.class).putExtra("p", p.toJson().toString());
        startActivity(i);
    }

    private void launch(App a) { safeStart(a.launch); }

    private void safeStart(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable t) { Ui.toast(this, "Не открывается: " + t.getMessage()); }
    }

    private void appMenu(App a) {
        List<String> fav = favPkgs();
        boolean isFav = fav.contains(a.pkg);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("▶  Открыть"); acts.add(() -> launch(a));
        if (isFav) {
            int i = fav.indexOf(a.pkg);
            items.add("☆  Убрать из избранного"); acts.add(() -> { fav.remove(a.pkg); Kit.saveList(this, "favApps", fav); show(page); });
            if (i > 0) { items.add("◀  Сдвинуть влево"); acts.add(() -> { Collections.swap(fav, i, i - 1); Kit.saveList(this, "favApps", fav); show(page); }); }
            if (i < fav.size() - 1) { items.add("▶  Сдвинуть вправо"); acts.add(() -> { Collections.swap(fav, i, i + 1); Kit.saveList(this, "favApps", fav); show(page); }); }
        } else {
            items.add("★  В избранное"); acts.add(() -> { fav.add(a.pkg); Kit.saveList(this, "favApps", fav); show(page); });
        }
        items.add("ℹ  О приложении");
        acts.add(() -> safeStart(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))));
        new AlertDialog.Builder(this).setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    // ================================================================== приложения

    @SuppressWarnings("deprecation")
    private void loadApps() {
        apps.clear(); byPkg.clear();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> ris = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        ris.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (byPkg.containsKey(pkg)) continue;
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) li = pm.getLaunchIntentForPackage(pkg);
            if (li == null) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.launch = li;
            try { a.banner = ri.activityInfo.loadBanner(pm); } catch (Throwable ignored) { }
            if (a.banner == null) try { a.banner = pm.getApplicationBanner(pkg); } catch (Throwable ignored) { }
            a.icon = ri.loadIcon(pm);
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            boolean game = (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0
                    || (android.os.Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME);
            a.cat = HomeCats.of(pkg, a.label, game);
            apps.add(a);
            byPkg.put(pkg, a);
        }
        Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    private List<String> favPkgs() {
        List<String> l = Kit.list(this, "favApps");
        if (l.isEmpty() && !Kit.sp(this).contains("favApps")) {
            // по умолчанию — кинотеатры и видео, что стоят на ТВ
            for (App a : apps) if (l.size() < 6 && (HomeCats.MOVIES.equals(a.cat) || HomeCats.VIDEO.equals(a.cat))) l.add(a.pkg);
        }
        return l;
    }

    private List<App> favApps() {
        List<App> out = new ArrayList<>();
        for (String p : favPkgs()) { App a = byPkg.get(p); if (a != null) out.add(a); }
        return out;
    }

    // ================================================================== мелочи

    private int dp(float v) { return Kit.dp(this, v); }

    private static String meta(Program p) {
        StringBuilder s = new StringBuilder();
        if (!p.year.isEmpty()) s.append(p.year);
        if (!p.genre.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(p.genre);
        if (!p.rating.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(p.rating);
        return s.toString();
    }

    static String metaOf(Program p) { return meta(p); }

    private static int colorFor(String pkg) {
        int[] c = {0xFF1E3A8A, 0xFF5B21B6, 0xFF0F766E, 0xFF9A3412, 0xFF1D4ED8, 0xFFBE123C, 0xFF3F6212, 0xFF6D28D9};
        return c[Math.abs(pkg.hashCode()) % c.length];
    }
}
