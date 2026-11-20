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

    /** Only our launcher and the existing Apple-style fallback are offered. Other apps are not uninstalled. */
    public static final List<L> ALL = Arrays.asList(
        new L("cyt", "CleanYourTV (Apple TV)", "com.gathin.tvhome",
            "release:CleanYourTV.apk", "",
            "https://raw.githubusercontent.com/Gathin23/cleanyourtv/main/docs/focus-2-up-netflix.png",
            "Проверенный существующий главный экран в стиле Apple TV. Остаётся установленным.",
            "Текущий главный экран · резервный"),
        new L("turbo", "Turbo Home", "ru.chex.turbohome",
            "release:TurboHome.apk", "",
            "",
            "Наш собственный лёгкий главный экран: приложения, избранное, динамический фон, темы и настройки. Без оптимизатора внутри оболочки.",
            "Новый · тестовый")
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
        return byId(c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("choice", "cyt"));
    }

    public static void choose(Context c, String id) {
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("choice", id).apply();
    }

    private Launchers() { }
}
