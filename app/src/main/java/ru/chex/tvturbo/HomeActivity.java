package ru.chex.tvturbo;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * «ТВ Турбо Дом» — главный экран в стиле PatchWall, но бесплатный: без рекламы, подписок и
 * рекомендаций, только твои приложения. Разделы (Кино, ТВ, Видео, Детям…) раскладываются сами.
 * Работает на любом Android TV: приложения берутся стандартным способом, без привязки к бренду.
 */
public class HomeActivity extends BaseActivity {

    static final class App {
        String pkg, label, cat; Intent launch; Drawable banner, icon;
    }

    private static final String[] DEFAULT_FAV = {
            "com.google.android.youtube.tv", "ru.kinopoisk.tv", "ru.ivi.client", "ru.okko.tv", "ru.rt.video.app.tv",
            "ru.kion.tv", "com.vk.tvvideo", "ru.rutube.app.tv", "ru.vk.store.tv", "com.android.vending"
    };
    private static final String TAB_HOME = "Главная", TAB_ALL = "Все приложения";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>();
    private final Map<String, App> byPkg = new HashMap<>();
    private LinearLayout rows, tabs;
    private ScrollView scroll;
    private TextView heroTitle, heroSub, clock, date;
    private ImageView heroImg;
    private String tab = TAB_HOME;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        render();
        tick();
    }

    @Override
    protected void onPause() { super.onPause(); h.removeCallbacksAndMessages(null); }

    /** Главный экран: «Назад» никуда не уводит. */
    @Override
    public void onBackPressed() {
        if (!TAB_HOME.equals(tab)) { tab = TAB_HOME; render(); }
        else scroll.smoothScrollTo(0, 0);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openTurbo(); return true; }
        return super.onKeyDown(keyCode, e);
    }

    // ------------------------------------------------------------------ интерфейс

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.bg_gradient);

        // большая картинка выбранного приложения справа сверху
        heroImg = new ImageView(this);
        heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroImg.setAlpha(0.45f);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(dp(560), dp(315), Gravity.END | Gravity.TOP);
        root.addView(heroImg, hl);
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF0B0E14, 0xCC0B0E14, 0x000B0E14}));
        root.addView(fade, new FrameLayout.LayoutParams(dp(560), dp(315), Gravity.END | Gravity.TOP));
        View fadeB = new View(this);
        fadeB.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF0D1220, 0x000D1220}));
        FrameLayout.LayoutParams fb = new FrameLayout.LayoutParams(dp(560), dp(120), Gravity.END | Gravity.TOP);
        fb.topMargin = dp(195);
        root.addView(fadeB, fb);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(44), dp(22), dp(44), 0);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // верхняя строка: вкладки + часы + кнопки
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabsScroll.addView(tabs);
        top.addView(tabsScroll, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(iconBtn("📺", "Источник", v -> inputs()));
        top.addView(iconBtn("⚡", "ТВ Турбо", v -> openTurbo()));
        top.addView(iconBtn("⚙", "Настройки", v -> safeStart(new Intent(android.provider.Settings.ACTION_SETTINGS))));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setGravity(Gravity.END);
        clock = text("", 26, Color.WHITE, true);
        date = text("", 12, 0xFF8A93A6, false);
        tcol.addView(clock); tcol.addView(date);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.setMarginStart(dp(14));
        top.addView(tcol, tl);
        col.addView(top);

        // шапка с выбранным приложением
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(4), dp(26), 0, dp(14));
        heroTitle = text("ТВ Турбо Дом", 34, Color.WHITE, true);
        heroSub = text("Твои приложения — без рекламы и подписок", 15, 0xFFB8C0D0, false);
        hero.addView(heroTitle); hero.addView(heroSub);
        col.addView(hero);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(40));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void render() {
        // вкладки
        tabs.removeAllViews();
        Map<String, List<App>> cats = byCategory();
        List<String> names = new ArrayList<>();
        names.add(TAB_HOME);
        for (String c : HomeCats.ORDER) if (cats.containsKey(c)) names.add(c);
        names.add(TAB_ALL);
        View focusTab = null;
        for (String n : names) {
            boolean sel = n.equals(tab);
            TextView t = text(n, 16, sel ? 0xFF0B0E14 : Color.WHITE, sel);
            t.setPadding(dp(16), dp(7), dp(16), dp(7));
            t.setSingleLine(true);
            t.setFocusable(true);
            t.setBackground(tabBg(sel ? 0xFFECEFF4 : 0));
            t.setOnClickListener(v -> { tab = n; render(); });
            t.setOnFocusChangeListener((v, f) -> {
                t.setBackground(tabBg(f ? 0xFF2E6BFF : sel ? 0xFFECEFF4 : 0));
                t.setTextColor(f ? Color.WHITE : sel ? 0xFF0B0E14 : Color.WHITE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(6));
            tabs.addView(t, lp);
            if (n.equals(tab)) focusTab = t;
        }

        // ряды
        rows.removeAllViews();
        View first = null;
        if (TAB_HOME.equals(tab)) {
            List<App> fav = favorites();
            if (!fav.isEmpty()) first = row("★ Любимые", fav, true);
            for (String c : HomeCats.ORDER) {
                List<App> l = cats.get(c);
                if (l == null) continue;
                View f = row(c, l, false);
                if (first == null) first = f;
            }
        } else {
            List<App> l = TAB_ALL.equals(tab) ? apps : cats.get(tab);
            if (l == null) l = new ArrayList<>();
            for (int i = 0; i < l.size(); i += 6) {
                View f = row(i == 0 ? tab : "", l.subList(i, Math.min(l.size(), i + 6)), false);
                if (first == null) first = f;
            }
        }
        View target = first != null ? first : focusTab;
        rows.post(() -> { if (target != null) target.requestFocus(); });
        if (apps.isEmpty()) rows.addView(text("Приложения не найдены", 18, 0xFF8A93A6, false));
    }

    /** Ряд плиток. Возвращает первую плитку. */
    private View row(String title, List<App> list, boolean fav) {
        if (!title.isEmpty()) {
            TextView t = text(title, 17, 0xFFDDE3EE, true);
            t.setPadding(dp(4), dp(10), 0, dp(6));
            rows.addView(t);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setPadding(dp(6), dp(8), dp(40), dp(10));
        LinearLayout line = new LinearLayout(this);
        line.setClipChildren(false);
        hs.addView(line);
        View first = null;
        for (App a : list) {
            View tile = tile(a, fav);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), dp(118));
            lp.setMarginEnd(dp(14));
            line.addView(tile, lp);
            if (first == null) first = tile;
        }
        rows.addView(hs);
        return first;
    }

    private View tile(App a, boolean fav) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);

        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(colorFor(a.pkg));
        card.setBackground(bg);
        card.setClipToOutline(true);
        ImageView iv = new ImageView(this);
        if (a.banner != null) {
            iv.setImageDrawable(a.banner);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            iv.setImageDrawable(a.icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(iv, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        }
        t.addView(card, new LinearLayout.LayoutParams(-1, dp(94)));

        TextView name = text(a.label, 13, 0xFFB8C0D0, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(4), dp(5), dp(4), 0);
        t.addView(name);

        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
            v.setElevation(has ? 16f : 0f);
            GradientDrawable g = (GradientDrawable) card.getBackground();
            g.setStroke(has ? dp(3) : 0, Color.WHITE);
            name.setTextColor(has ? Color.WHITE : 0xFFB8C0D0);
            if (has) hero(a);
        });
        t.setOnClickListener(v -> launch(a));
        t.setOnLongClickListener(v -> { menu(a, fav); return true; });
        return t;
    }

    private void hero(App a) {
        heroTitle.setText(a.label);
        heroSub.setText(a.cat + "   ·   OK — открыть   ·   удерживай OK — меню");
        heroImg.setImageDrawable(a.banner != null ? a.banner : a.icon);
    }

    private View iconBtn(String icon, String hint, View.OnClickListener l) {
        TextView t = text(icon, 20, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setContentDescription(hint);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x33FFFFFF);
        t.setBackground(g);
        t.setOnFocusChangeListener((v, f) -> {
            g.setColor(f ? 0xFF2E6BFF : 0x33FFFFFF);
            v.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(120).start();
            if (f) { heroTitle.setText(hint); heroSub.setText(""); }
        });
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMarginStart(dp(10));
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable tabBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(100));
        g.setColor(color);
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void tick() {
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        date.setText(new SimpleDateFormat("EEE, d MMMM", new Locale("ru")).format(new Date()));
        h.postDelayed(this::tick, 20_000);
    }

    // ------------------------------------------------------------------ данные

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
            if (pkg.equals(getPackageName()) && ri.activityInfo.name.endsWith("HomeActivity")) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) li = pm.getLaunchIntentForPackage(pkg);
            if (li == null) continue;
            a.launch = li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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

    private Map<String, List<App>> byCategory() {
        Map<String, List<App>> m = new LinkedHashMap<>();
        for (App a : apps) {
            List<App> l = m.get(a.cat);
            if (l == null) m.put(a.cat, l = new ArrayList<>());
            l.add(a);
        }
        return m;
    }

    private SharedPreferences sp() { return getSharedPreferences("home", MODE_PRIVATE); }

    private List<String> favPkgs() {
        String s = sp().getString("fav", null);
        List<String> l = new ArrayList<>();
        if (s == null) {
            for (String p : DEFAULT_FAV) if (byPkg.containsKey(p)) l.add(p);
            if (l.size() < 4) for (App a : apps) if (l.size() < 8 && !l.contains(a.pkg) && !HomeCats.OTHER.equals(a.cat)) l.add(a.pkg);
        } else if (!s.isEmpty()) l.addAll(Arrays.asList(s.split(",")));
        return l;
    }

    private void saveFav(List<String> l) { sp().edit().putString("fav", android.text.TextUtils.join(",", l)).apply(); }

    private List<App> favorites() {
        List<App> out = new ArrayList<>();
        for (String p : favPkgs()) { App a = byPkg.get(p); if (a != null) out.add(a); }
        return out;
    }

    // ------------------------------------------------------------------ действия

    private void launch(App a) { safeStart(a.launch); }

    private void safeStart(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable t) { Ui.toast(this, "Не открывается: " + t.getMessage()); }
    }

    private void openTurbo() { safeStart(new Intent(this, MainActivity.class)); }

    /** «Источник»: системная кнопка входов ТВ — работает на всех Android TV. */
    private void inputs() {
        Adb adb = Adb.get(this);
        if (adb.isConnected()) adb.run("input keyevent 178", null);
        else safeStart(new Intent(android.provider.Settings.ACTION_SETTINGS));
    }

    private void menu(App a, boolean inFav) {
        List<String> fav = favPkgs();
        boolean isFav = fav.contains(a.pkg);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("▶  Открыть"); acts.add(() -> launch(a));
        if (isFav) {
            items.add("☆  Убрать из любимых"); acts.add(() -> { fav.remove(a.pkg); saveFav(fav); render(); });
            int i = fav.indexOf(a.pkg);
            if (i > 0) { items.add("◀  Сдвинуть влево"); acts.add(() -> { Collections.swap(fav, i, i - 1); saveFav(fav); render(); }); }
            if (i < fav.size() - 1) { items.add("▶  Сдвинуть вправо"); acts.add(() -> { Collections.swap(fav, i, i + 1); saveFav(fav); render(); }); }
        } else {
            items.add("★  Добавить в любимые"); acts.add(() -> { fav.add(a.pkg); saveFav(fav); render(); });
        }
        items.add("ℹ  О приложении"); acts.add(() -> safeStart(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))));
        if (!a.pkg.equals(getPackageName())) {
            items.add("⛔  Спрятать (отключить)"); acts.add(() -> {
                Adb adb = Adb.get(this);
                if (!adb.isConnected()) { Ui.toast(this, "Сначала подключи ТВ Турбо к отладке"); return; }
                adb.run(Pkgs.cmdDisable(a.pkg), (ok, out) -> { if (ok) Pkgs.remember(this, 'd', a.pkg); loadApps(); render(); });
            });
        }
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    // ------------------------------------------------------------------ мелочи

    private int dp(int v) { return Ui.dp(this, v); }

    /** Свой цвет фона плитки для каждого приложения (если нет баннера). */
    private static int colorFor(String pkg) {
        int[] c = {0xFF1E3A8A, 0xFF7C2D92, 0xFF0F766E, 0xFF9A3412, 0xFF1D4ED8, 0xFFBE123C, 0xFF4D7C0F, 0xFF6D28D9};
        return c[Math.abs(pkg.hashCode()) % c.length];
    }
}
