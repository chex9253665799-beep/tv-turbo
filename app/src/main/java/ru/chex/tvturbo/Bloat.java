package ru.chex.tvturbo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** База известных лишних пакетов Android TV / Google TV и производителей + список того, что трогать нельзя. */
public final class Bloat {

    public static final int SAFE = 0, CAUTION = 1, RISKY = 2;

    public static final class Item {
        public final String pkg, name, why; public final int level;
        Item(String pkg, int level, String name, String why) { this.pkg = pkg; this.level = level; this.name = name; this.why = why; }
    }

    public static final List<Item> KNOWN = new ArrayList<>(Arrays.asList(
        // ---- реклама и рекомендации ----
        new Item("com.google.android.tvrecommendations", SAFE, "Рекомендации Google TV", "Рекламные ряды на главном экране, постоянно в фоне"),
        new Item("com.google.android.leanbacklauncher.recommendations", SAFE, "Рекомендации лаунчера", "Подгружает рекламные карточки"),
        new Item("com.google.android.apps.tv.launcherx.ads", SAFE, "Реклама Google TV", "Рекламные баннеры главного экрана"),
        new Item("com.google.android.partnersetup", CAUTION, "Partner Setup", "Партнёрские предустановки и промо"),
        new Item("com.mitv.tvhome.atv", CAUTION, "Xiaomi PatchWall (реклама)", "Рекламный контент Xiaomi"),
        new Item("com.xm.webcontent", SAFE, "Xiaomi веб-контент", "Промо-контент Xiaomi"),
        new Item("com.xiaomi.mitv.handbook", SAFE, "Xiaomi справочник", "Не нужен"),

        // ---- телеметрия ----
        new Item("com.google.android.feedback", SAFE, "Google Feedback", "Отправка отзывов/отчётов в Google"),
        new Item("com.google.android.tv.bugreportsender", SAFE, "Отправка баг-репортов", "Телеметрия"),
        new Item("com.miui.tv.analytics", SAFE, "Xiaomi Analytics", "Телеметрия Xiaomi"),
        new Item("com.xiaomi.statistic", SAFE, "Xiaomi Statistic", "Телеметрия Xiaomi"),
        new Item("com.xiaomi.mitv.tvmanager", CAUTION, "Xiaomi TV Manager", "Фоновый «оптимизатор» Xiaomi"),
        new Item("com.google.android.apps.tv.dreamx", SAFE, "Ambient-режим Google TV", "Заставка с фото, ест память"),
        new Item("com.google.android.backdrop", SAFE, "Ambient Mode / заставка", "Заставка с фото, ест память и трафик"),

        // ---- предустановленные приложения ----
        new Item("com.google.android.play.games", SAFE, "Google Play Игры", "Редко нужен, висит в фоне"),
        new Item("com.google.android.videos", SAFE, "Google TV / Play Фильмы", "Магазин фильмов"),
        new Item("com.google.android.youtube.tvmusic", SAFE, "YouTube Music", "Если не пользуешься"),
        new Item("com.google.android.marvin.talkback", SAFE, "TalkBack", "Озвучка экрана для незрячих"),
        new Item("com.android.printspooler", SAFE, "Печать", "На ТВ не нужно"),
        new Item("com.android.camera2", SAFE, "Камера", "На ТВ без камеры не нужно"),
        new Item("com.android.dreams.basic", SAFE, "Заставка «Цвета»", "Не нужна"),
        new Item("com.android.dreams.phototable", SAFE, "Заставка «Фото»", "Не нужна"),
        new Item("com.android.wallpaper.livepicker", SAFE, "Живые обои", "На ТВ не нужно"),
        new Item("com.netflix.ninja", CAUTION, "Netflix", "Предустановлен, в РФ не работает"),
        new Item("com.amazon.amazonvideo.livingroom", CAUTION, "Prime Video", "Предустановлен"),
        new Item("com.google.android.apps.youtube.kids", CAUTION, "YouTube Kids", "Если не нужен детям"),

        // ---- голос и каст (могут быть нужны) ----
        new Item("com.google.android.katniss", CAUTION, "Google для ТВ (голосовой поиск)", "Отключит голосовой поиск с пульта"),
        new Item("com.google.android.speech.pumpkin", CAUTION, "Распознавание речи офлайн", "Нужно для голосового поиска"),
        new Item("com.google.android.apps.mediashell", CAUTION, "Chromecast built-in", "Отключит трансляцию с телефона"),
        new Item("com.google.android.tts", CAUTION, "Синтез речи Google", "Нужно для озвучки"),

        // ---- лаунчеры: только если поставлен другой главный экран ----
        new Item("com.google.android.tvlauncher", RISKY, "Стандартный лаунчер Android TV", "Отключать ТОЛЬКО после установки другого лаунчера"),
        new Item("com.google.android.leanbacklauncher", RISKY, "Старый лаунчер Android TV", "Отключать ТОЛЬКО после установки другого лаунчера"),
        new Item("com.google.android.apps.tv.launcherx", RISKY, "Лаунчер Google TV", "Отключать ТОЛЬКО после установки другого лаунчера")
    ));

    /** Никогда не трогать: без них ТВ перестаёт работать нормально. */
    public static final Set<String> CRITICAL = new HashSet<>(Arrays.asList(
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.android.providers.tv", "com.android.providers.settings", "com.android.providers.media",
        "com.android.providers.downloads", "com.android.providers.contacts", "com.android.providers.telephony",
        "com.android.phone", "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.inputdevices", "com.android.bluetooth", "com.android.externalstorage", "com.android.keychain",
        "com.android.webview", "com.google.android.webview", "com.android.wifi.resources", "com.android.networkstack",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.tv.remote.service", "com.google.android.tvsetup", "com.google.android.tungsten.setupwraith",
        "com.google.android.inputmethod.latin", "com.google.android.leanback.ime",
        "com.android.tv", "com.droidlogic.tvinput", "com.mediatek.tvinput", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.server.telecom", "com.android.location.fused"
    ));

    private static final Pattern CRITICAL_PART = Pattern.compile(
        "(inputmethod|\\.ime|keyboard|bluetooth|remote|tvinput|\\.tv\\.input|\\.provider|providers\\.|systemui|setupwizard|webview|permission|framework|\\.hdmi|audioservice)",
        Pattern.CASE_INSENSITIVE);

    /** Похоже по названию на рекламу/телеметрию — показываем, но не отмечаем по умолчанию. */
    private static final Pattern SUSPICIOUS = Pattern.compile(
        "(analytic|statist|telemetr|advert|\\.ads\\b|\\.ad\\.|adservice|adsdk|bugreport|feedback|crashreport|tracker|tracking|promo|recommend|userexperience|\\.uxp|logcollect|datacollect|\\.report)",
        Pattern.CASE_INSENSITIVE);

    public static boolean isCritical(String pkg) {
        return CRITICAL.contains(pkg) || CRITICAL_PART.matcher(pkg).find();
    }

    public static boolean isSuspicious(String pkg) {
        return !isCritical(pkg) && SUSPICIOUS.matcher(pkg).find();
    }

    public static Item find(String pkg) {
        for (Item i : KNOWN) if (i.pkg.equals(pkg)) return i;
        return null;
    }

    public static String levelLabel(int level) {
        return level == SAFE ? "Безопасно" : level == CAUTION ? "Осторожно" : "Риск";
    }

    public static int levelColor(int level) {
        return level == SAFE ? 0xFF3DDC84 : level == CAUTION ? 0xFFFFB020 : 0xFFFF5A5F;
    }

    private Bloat() { }
}
