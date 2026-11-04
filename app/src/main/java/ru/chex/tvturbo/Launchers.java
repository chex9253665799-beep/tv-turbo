package ru.chex.tvturbo;

import android.content.Context;

import java.util.Arrays;
import java.util.List;

/** Лаунчеры (главные экраны) на выбор: открытый код, без рекламы, APK из официальных релизов GitHub. */
public final class Launchers {

    public static final class L {
        public final String id, name, pkg, repo, asset, preview, desc, plus;
        L(String id, String name, String pkg, String repo, String asset, String preview, String desc, String plus) {
            this.id = id; this.name = name; this.pkg = pkg; this.repo = repo; this.asset = asset;
            this.preview = preview; this.desc = desc; this.plus = plus;
        }
    }

    /** Встроенный главный экран больше не предлагается (оставлено для совместимости). */
    public static final String BUILTIN = "home";

    public static final List<L> ALL = Arrays.asList(
        new L("ltv", "LTvLauncher", "com.leanbitlab.ltvL",
            "leanbitlab-org/LtvLauncher", "universal",
            "https://raw.githubusercontent.com/leanbitlab-org/LtvLauncher/master/fastlane/metadata/android/en-US/images/tvScreenshots/screenshot_1.png",
            "Красивый минималистичный главный экран на основе FLauncher. Категории приложений, обои, часы, "
                + "виджет трафика Wi-Fi, заставка для OLED. Без рекламы и рекомендаций.",
            "Красивый · категории · обои"),
        new L("light", "Lightweight TV Launcher", "com.tvlauncher",
            "wiiguy/lightweight_androidtv_launcher", "",
            "https://raw.githubusercontent.com/wiiguy/lightweight_androidtv_launcher/main/fastlane/metadata/android/en-US/images/tvScreenshots/01_home_screen.png",
            "Сверхлёгкий главный экран: занимает всего 30–40 МБ памяти. Тёмная тема, до 15 приложений на главном, "
                + "поиск по приложениям, обои по желанию. Лучший выбор для слабых ТВ с 1–1.5 ГБ памяти.",
            "Самый лёгкий · 30–40 МБ"),
        new L("flauncher", "FLauncher", "me.efesser.flauncher",
            "fdroid:me.efesser.flauncher", "", "",
            "Знаменитый открытый лаунчер для Android TV: категории приложений, свои обои, часы, без рекламы. "
                + "На нём основан LTvLauncher. Ставится из каталога F-Droid.",
            "Классика · открытый код"),
        new L("projectivy", "Projectivy Launcher", "com.spocky.projengmenu",
            "play:com.spocky.projengmenu", "", "",
            "Самый популярный сторонний лаунчер для Android TV: красивый, с превью каналов, обоями и гибкой настройкой. "
                + "Бесплатный, ставится из Google Play (откроется его страница).",
            "Самый популярный · Google Play"),
        new L("atv", "ATV Launcher", "ca.dstudio.atvlauncher.free",
            "play:ca.dstudio.atvlauncher.free", "", "",
            "Простой и быстрый лаунчер с крупными плитками и группами. Бесплатный, ставится из Google Play.",
            "Простой · Google Play"),
        new L("wolf", "Wolf Launcher", "com.wolf.firelauncher",
            "play:com.wolf.firelauncher", "", "",
            "Лёгкий настраиваемый лаунчер: свои ряды, обои, скрытие приложений. Бесплатный, ставится из Google Play.",
            "Настраиваемый · Google Play")
    );

    /** Заводские Google-лаунчеры (без PatchWall). */
    public static final String[] GOOGLE = {"com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher"};

    /**
     * Откат MiVision: вернуть заводской главный экран и выключить встроенный лаунчер ТВ Турбо.
     * Встроенный выключаем ТОЛЬКО когда заводской точно включён — иначе ТВ останется без «Домой».
     * Только в потоке Adb.
     */
    public static void dropBuiltin(Context c, Adb adb) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        android.content.ComponentName mine = new android.content.ComponentName(c, ru.chex.tvturbo.HomeActivity.class);
        if (pm.getComponentEnabledSetting(mine) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return;
        Profile.allow(c, GOOGLE);
        Profile.allow(c, c.getPackageName());
        boolean stockOk = false;
        for (String g : GOOGLE) {
            if (adb.isConnected()) adb.execSync(Pkgs.cmdEnable(g) + " 2>/dev/null", false);
            try { if (pm.getApplicationInfo(g, 0).enabled) stockOk = true; } catch (Throwable ignored) { }
        }
        // есть ли другой главный экран, кроме нашего
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0))
            if (ri.activityInfo != null && !ri.activityInfo.packageName.equals(c.getPackageName())
                    && !"android".equals(ri.activityInfo.packageName) && !isPatchWall(ri.activityInfo.packageName)) stockOk = true;
        if (!stockOk) return;   // нечем заменить — оставляем, попробуем при следующем подключении
        if (adb.isConnected()) for (String g : GOOGLE) {
            String comp = ru.chex.tvturbo.LauncherActivity.homeComponent(c, g);
            if (comp != null) { adb.execSync("cmd package set-home-activity " + comp, true); break; }
        }
        pm.setComponentEnabledSetting(mine, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
        Profile.clearHome(c);
    }

    /** Стандартные главные экраны: PatchWall Xiaomi (с платными подписками), Google TV, Android TV. */
    public static final String[] STOCK = {
            "com.mitv.tvhome.atv", "com.mitv.tvhome", "com.mitv.tvhome.michannel",
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher"
    };

    public static boolean isStock(String pkg) {
        for (String s : STOCK) if (s.equals(pkg)) return true;
        return pkg.startsWith("com.mitv.tvhome");
    }

    /** Что сейчас на экране (пакет). Только в потоке Adb. */
    public static String foreground(Adb adb) {
        String out = adb.execSync("dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity'", false).out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("u0 ([\\w.]+)/").matcher(out);
        return m.find() ? m.group(1) : "";
    }

    /** PatchWall Xiaomi — платные подписки, выскакивает при включении ТВ поверх главного экрана. */
    public static boolean isPatchWall(String pkg) {
        return pkg.startsWith("com.mitv.tvhome") || pkg.toLowerCase().contains("patchwall");
    }

    /**
     * PatchWall отключаем всегда, когда главный экран — не он (заводской Android TV или наш лаунчер).
     * Стандартные Google-лаунчеры отключаем, только если главным стоит сторонний лаунчер.
     * Главный экран не трогаем никогда (иначе не будет «Домой»). Возвращает, сколько отключено.
     */
    public static int disableStock(Context c, Adb adb) {
        String home = Pkgs.currentHome(c);
        if (home.isEmpty() || isPatchWall(home)) return 0;
        boolean customHome = !isStock(home);
        java.util.Map<String, Pkgs.P> pkgs = Pkgs.map(c);
        int n = 0;
        for (Pkgs.P p : pkgs.values()) {
            if (!p.installed || !p.enabled || p.pkg.equals(home)) continue;
            boolean off = isPatchWall(p.pkg) || (customHome && isStock(p.pkg));
            if (!off) continue;
            adb.execSync("am force-stop " + p.pkg, false);
            if (adb.execSync(Pkgs.cmdDisable(p.pkg), true).ok) { Pkgs.remember(c, 'd', p.pkg); n++; }
        }
        return n;
    }

    public static L byId(String id) {
        for (L l : ALL) if (l.id.equals(id)) return l;
        return ALL.get(0);
    }

    public static L chosen(Context c) {
        return byId(c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("choice", "ltv"));
    }

    public static void choose(Context c, String id) {
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("choice", id).apply();
    }

    private Launchers() { }
}
