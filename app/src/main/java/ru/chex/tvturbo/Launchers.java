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
            "Самый лёгкий · 30–40 МБ")
    );

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
