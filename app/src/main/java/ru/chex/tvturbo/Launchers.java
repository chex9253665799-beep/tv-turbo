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

    /** Встроенный главный экран ТВ Турбо — ставить не нужно. */
    public static final String BUILTIN = "home";

    public static final List<L> ALL = Arrays.asList(
        new L(BUILTIN, "MiVision TV", "ru.chex.tvturbo", "", "",
            "https://raw.githubusercontent.com/chex9253665799-beep/tv-turbo/main/docs/mivision.png",
            "Наш главный экран в стиле Xiaomi TV — бесплатно, без рекламы и подписок. Фильмы и сериалы с обложками "
                + "из твоих приложений (КИОН, Иви, Wink…), только установленные приложения, настоящая погода, ТВ-каналы, поиск. "
                + "Работает на любом Android TV.",
            "Стиль Xiaomi · фильмы из твоих приложений"),
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
            "Самый лёгкий · 30–40 МБ")
    );

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
        return byId(c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("choice", BUILTIN));
    }

    public static void choose(Context c, String id) {
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("choice", id).apply();
    }

    private Launchers() { }
}
