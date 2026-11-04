package ru.chex.tvturbo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Команды ускорения ТВ. */
public final class Speed {

    public static final String ADGUARD = "dns.adguard-dns.com";

    public static List<String> animations(String scale) {
        return Arrays.asList(
                "settings put global window_animation_scale " + scale,
                "settings put global transition_animation_scale " + scale,
                "settings put global animator_duration_scale " + scale);
    }

    public static List<String> adblockDns(boolean on) {
        if (!on) return Arrays.asList("settings put global private_dns_mode off");
        return Arrays.asList(
                "settings put global private_dns_specifier " + ADGUARD,
                "settings put global private_dns_mode hostname");
    }

    public static final class Tweak {
        public final String title, desc; public final List<String> cmds;
        Tweak(String title, String desc, List<String> cmds) { this.title = title; this.desc = desc; this.cmds = cmds; }
    }

    public static List<Tweak> all() {
        List<Tweak> t = new ArrayList<>();
        List<String> turbo = new ArrayList<>();
        turbo.addAll(animations("0.5"));
        turbo.add("settings put secure screensaver_enabled 0");
        turbo.add("settings put global wifi_scan_always_enabled 0");
        turbo.add("settings put global ble_scan_always_enabled 0");
        turbo.add("pm trim-caches 999G");
        turbo.add("am kill-all");
        t.add(new Tweak("🚀  Всё сразу (рекомендуется)", "Анимации 0.5×, без заставки, без фонового поиска Wi-Fi/BT, очистка кэша и памяти", turbo));
        t.add(new Tweak("Анимации 0.5×", "Меню отзывчивее, плавность сохраняется", animations("0.5")));
        t.add(new Tweak("Анимации выключить", "Максимально быстро, но без плавности", animations("0")));
        t.add(new Tweak("Анимации как было (1×)", "Вернуть стандарт", animations("1")));
        t.add(new Tweak("🛡  Блокировка рекламы (AdGuard DNS)", "Режет рекламу и трекеры во всех приложениях. Нужен Android 9+", adblockDns(true)));
        t.add(new Tweak("Выключить AdGuard DNS", "Если какой-то сервис перестал работать", adblockDns(false)));
        t.add(new Tweak("Очистить кэш всех приложений", "Освобождает место, данные и входы в аккаунты не трогает", Arrays.asList("pm trim-caches 999G")));
        t.add(new Tweak("Закрыть фоновые приложения", "Освобождает оперативную память прямо сейчас", Arrays.asList("am kill-all")));
        t.add(new Tweak("Выключить заставку", "Ambient-режим и фото-заставки грузят память и сеть",
                Arrays.asList("settings put secure screensaver_enabled 0")));
        t.add(new Tweak("Выключить фоновый поиск Wi-Fi и Bluetooth", "Меньше фоновой активности",
                Arrays.asList("settings put global wifi_scan_always_enabled 0", "settings put global ble_scan_always_enabled 0")));
        t.add(new Tweak("Перезагрузить ТВ", "Помогает, если ТВ неделями был в режиме ожидания", Arrays.asList("reboot")));
        return t;
    }

    private Speed() { }
}
