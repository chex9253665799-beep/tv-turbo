package ru.chex.tvturbo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Профиль ТВ — как всё должно быть настроено. Заполняется сам: каждая успешная команда,
 * которую выполняет приложение (анимации, DNS, отключение пакетов, лаунчер, обход YouTube),
 * записывается сюда. «Страж» потом сверяет ТВ с профилем и возвращает то, что слетело.
 */
public final class Profile {

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("profile", Context.MODE_PRIVATE); }

    public static String anim(Context c) { return sp(c).getString("anim", null); }
    public static String dns(Context c) { return sp(c).getString("dns", null); }          // "on" / "off" / null
    public static String home(Context c) { return sp(c).getString("home", null); }
    public static boolean saverOff(Context c) { return sp(c).getBoolean("saverOff", false); }
    public static boolean youtube(Context c) { return sp(c).getBoolean("youtube", false); }
    public static Set<String> disabled(Context c) { return new HashSet<>(sp(c).getStringSet("disabled", new HashSet<>())); }
    public static Set<String> noBg(Context c) { return new HashSet<>(sp(c).getStringSet("nobg", new HashSet<>())); }

    private static final Pattern ANIM = Pattern.compile("settings put global window_animation_scale ([\\d.]+)");
    private static final Pattern DISABLE = Pattern.compile("pm disable-user --user 0 (\\S+)");
    private static final Pattern UNINST = Pattern.compile("pm uninstall -k --user 0 (\\S+)");
    private static final Pattern ENABLE = Pattern.compile("pm enable (\\S+)");
    private static final Pattern REINST = Pattern.compile("pm install-existing (\\S+)");
    private static final Pattern HOME = Pattern.compile("cmd package set-home-activity (\\S+)");
    private static final Pattern NOBG = Pattern.compile("RUN_ANY_IN_BACKGROUND ignore");
    private static final Pattern ALLOWBG = Pattern.compile("RUN_ANY_IN_BACKGROUND allow");
    private static final Pattern APPOPS_PKG = Pattern.compile("cmd appops set (\\S+) ");

    /** Вызывается после каждой успешной команды. */
    public static void observe(Context c, String cmd) {
        SharedPreferences.Editor e = sp(c).edit();
        Matcher m;
        if ((m = ANIM.matcher(cmd)).find()) e.putString("anim", m.group(1));
        if (cmd.contains("private_dns_mode hostname")) e.putString("dns", "on");
        if (cmd.contains("private_dns_mode off")) e.putString("dns", "off");
        if (cmd.contains("screensaver_enabled 0")) e.putBoolean("saverOff", true);
        if (cmd.contains("screensaver_enabled 1")) e.putBoolean("saverOff", false);
        if (cmd.contains("always_on_vpn_app " + YouTube.DPI_PKG) || cmd.contains("always_on_vpn_app " + YouTube.OLD_DPI_PKG)
                || cmd.contains("always_on_vpn_app " + YouTube.VPN_PKG)) e.putBoolean("youtube", true);
        if (cmd.contains("always_on_vpn_app ''")) e.putBoolean("youtube", false);
        if ((m = HOME.matcher(cmd)).find()) e.putString("home", m.group(1));

        Set<String> dis = disabled(c);
        boolean disChanged = false;
        if ((m = DISABLE.matcher(cmd)).find()) disChanged |= dis.add(m.group(1));
        if ((m = UNINST.matcher(cmd)).find()) disChanged |= dis.add(m.group(1));
        if ((m = ENABLE.matcher(cmd)).find()) disChanged |= dis.remove(m.group(1));
        if ((m = REINST.matcher(cmd)).find()) disChanged |= dis.remove(m.group(1));
        if (disChanged) e.putStringSet("disabled", dis);

        Matcher pm = APPOPS_PKG.matcher(cmd);
        if (pm.find()) {
            Set<String> nb = noBg(c);
            if (NOBG.matcher(cmd).find() && nb.add(pm.group(1))) e.putStringSet("nobg", nb);
            if (ALLOWBG.matcher(cmd).find() && nb.remove(pm.group(1))) e.putStringSet("nobg", nb);
        }
        e.apply();
    }

    /** Убрать пакеты из «должны быть отключены» (чтобы Страж не отключал их снова). */
    public static void allow(Context c, String... pkgs) {
        Set<String> dis = disabled(c);
        boolean ch = false;
        for (String p : pkgs) ch |= dis.remove(p);
        SharedPreferences.Editor e = sp(c).edit();
        if (ch) e.putStringSet("disabled", dis);
        String h = home(c);
        if (h != null) for (String p : pkgs) if (h.startsWith(p + "/")) e.remove("home");
        e.apply();
    }

    public static void clearHome(Context c) { sp(c).edit().remove("home").apply(); }

    /** Сброс профиля (после «Вернуть всё»). */
    public static void clearPackages(Context c) {
        sp(c).edit().remove("disabled").remove("nobg").apply();
    }

    public static String summary(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("Отключено пакетов: ").append(disabled(c).size()).append('\n');
        s.append("Анимации: ").append(anim(c) == null ? "не менялись" : anim(c) + "×").append('\n');
        s.append("Блокировка рекламы (DNS): ").append("on".equals(dns(c)) ? "вкл" : "off".equals(dns(c)) ? "выкл" : "не менялась").append('\n');
        s.append("Заставка: ").append(saverOff(c) ? "выключена" : "не менялась").append('\n');
        s.append("Обход YouTube: ").append(youtube(c) ? "вкл" : "выкл").append('\n');
        s.append("Главный экран: ").append(home(c) == null ? "не менялся" : home(c).substring(0, home(c).indexOf('/') > 0 ? home(c).indexOf('/') : home(c).length()));
        return s.toString();
    }

    private Profile() { }
}
