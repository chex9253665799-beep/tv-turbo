package ru.chex.tvturbo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * YouTube в России «под ключ»: ставит «ТВ Турбо Обход» (ByeByeDPI с белым списком — обход работает
 * ТОЛЬКО для YouTube, остальные приложения ходят в интернет напрямую и VPN не видят),
 * сам выдаёт разрешение VPN (без окна), включает обход и делает его постоянным.
 */
public final class YouTube {

    public static final String DPI_PKG = "ru.chex.tvturbo.dpi";
    public static final String DPI_URL = "https://github.com/" + Updater.REPO + "/releases/download/latest/TvTurboDPI.apk";
    /** Официальный ByeByeDPI пускает через VPN весь ТВ — заменяем его своим. */
    public static final String OLD_DPI_PKG = "io.github.romanvht.byedpi";
    public static final String YOUTUBE_PKG = "com.google.android.youtube.tv";
    private static final String TOGGLE = DPI_PKG + "/io.github.romanvht.byedpi.activities.ToggleActivity";

    /** Стратегии обхода: первая — стандартная ByeByeDPI, дальше — из его же списка тестов. */
    public static final String[] STRATEGIES = {
            "-o1 -a1 -r-5+se",
            "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
            "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s -a1",
            "-o1 -d1 -a1 -At,r,s,c -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -At,r,s,c -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1",
            "-d1 -s1 -q1 -a1 -At,r,s,c -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
            "-d1+s -s50+s -a1 -At,r,s,c -f20 -r2+s -a1 -At,r,s,c -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1"
    };

    public interface Step { void on(int step, String state, String text); }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("youtube", Context.MODE_PRIVATE); }
    public static int strategy(Context c) { return sp(c).getInt("strategy", 0); }

    public static boolean installed(Context c, String pkg) {
        Map<String, Pkgs.P> m = Pkgs.map(c);
        return m.containsKey(pkg) && m.get(pkg).installed && m.get(pkg).enabled;
    }

    /** Полная настройка. Шаги: 0 обход, 1 убрать старый ByeByeDPI, 2 разрешения, 3 запуск, 4 автозапуск. Только в потоке Adb. */
    public static boolean setup(Context c, Adb adb, Step st) {
        boolean ok = true;

        // 0. «ТВ Турбо Обход»
        if (installed(c, DPI_PKG)) st.on(0, "ok", "уже установлен");
        else {
            String r = installUrl(c, adb, "ТВ Турбо Обход", DPI_URL, t -> st.on(0, "run", t));
            if (r != null) { st.on(0, "bad", r); return false; }
            st.on(0, "ok", "установлен");
        }

        // 1. официальный ByeByeDPI включал VPN на весь ТВ — удалить
        Map<String, Pkgs.P> m = Pkgs.map(c);
        if (m.containsKey(OLD_DPI_PKG) && m.get(OLD_DPI_PKG).installed) {
            st.on(1, "run", "удаляю");
            adb.execSync("settings put secure always_on_vpn_app ''", false);
            adb.execSync("am force-stop " + OLD_DPI_PKG + "; pm uninstall " + OLD_DPI_PKG, true);
            st.on(1, "ok", "удалён — VPN больше не на весь ТВ");
        } else st.on(1, "ok", "не нужно");

        // 2. разрешение VPN без окна подтверждения
        st.on(2, "run", "");
        grant(adb);
        st.on(2, "ok", "выдано");

        // 3. запуск с текущей стратегией
        st.on(3, "run", "");
        boolean started = start(c, adb, strategy(c));
        st.on(3, started ? "ok" : "warn", started ? "обход работает только для YouTube" : "не запустился — нажми «Настроить всё» ещё раз");
        if (!started) ok = false;

        // 4. постоянный VPN — обход поднимается сам после перезагрузки
        st.on(4, "run", "");
        Adb.Result a = adb.execSync("settings put secure always_on_vpn_app " + DPI_PKG + "; settings put secure always_on_vpn_lockdown 0", true);
        st.on(4, a.ok ? "ok" : "warn", a.ok ? "включится сам после перезагрузки" : "не удалось");
        return ok;
    }

    /** Все разрешения обходу: VPN без окна, работа в фоне, не усыплять. Повторять можно сколько угодно. */
    public static void grant(Adb adb) {
        adb.execSync("cmd appops set " + DPI_PKG + " ACTIVATE_VPN allow 2>/dev/null; appops set " + DPI_PKG + " ACTIVATE_VPN allow 2>/dev/null; true", false);
        adb.execSync("cmd appops set " + DPI_PKG + " RUN_ANY_IN_BACKGROUND allow 2>/dev/null; cmd appops set " + DPI_PKG + " RUN_IN_BACKGROUND allow 2>/dev/null; true", false);
        adb.execSync("pm grant " + DPI_PKG + " android.permission.POST_NOTIFICATIONS 2>/dev/null; true", false);
        adb.execSync("dumpsys deviceidle whitelist +" + DPI_PKG + " >/dev/null 2>&1; true", false);
    }

    /** Включить (или перезапустить) обход со стратегией №i. */
    public static boolean start(Context c, Adb adb, int i) {
        String s = STRATEGIES[Math.max(0, Math.min(i, STRATEGIES.length - 1))];
        sp(c).edit().putInt("strategy", i).apply();
        grant(adb);
        adb.execSync("am start -n " + TOGGLE + " --es strategy '" + s + "' --ez only_update true", true);
        if (isRunning(adb)) {                      // новая стратегия применяется перезапуском
            adb.execSync("am start -n " + TOGGLE + " --ez only_stop true", true);
            sleep(2000);
        }
        adb.execSync("am start -n " + TOGGLE + " --ez only_start true", true);
        if (waitRunning(adb, 12)) return true;

        // запасной путь: открыть ByeByeDPI и «нажать» кнопку подключения пультом
        adb.logLine("Обход не поднялся сам — открываю ByeByeDPI и нажимаю «Подключить»");
        adb.execSync("monkey -p " + DPI_PKG + " -c android.intent.category.LEANBACK_LAUNCHER 1", false);
        sleep(4000);
        adb.execSync("input keyevent 23", false);   // OK на пульте
        sleep(1500);
        String dlg = adb.execSync("dumpsys window windows | grep -i mCurrentFocus", false).out;
        if (dlg.contains("vpndialogs") || dlg.contains("ConfirmDialog")) {   // системное окно VPN — подтвердить
            adb.execSync("input keyevent 22; input keyevent 23", false);
            sleep(1500);
        }
        boolean ok = waitRunning(adb, 8);
        adb.execSync("input keyevent 3", false);    // «Домой», чтобы не оставлять ByeByeDPI на экране
        return ok;
    }

    private static boolean waitRunning(Adb adb, int seconds) {
        for (int t = 0; t < seconds; t += 2) {
            sleep(2000);
            if (isRunning(adb)) return true;
        }
        return false;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    public static boolean isRunning(Adb adb) {
        String net = adb.execSync("ls /sys/class/net 2>/dev/null", false).out;
        if (net.contains("tun")) return true;   // VPN-туннель поднят
        String out = adb.execSync("dumpsys activity services " + DPI_PKG, false).out;
        return out.contains("ByeDpiVpnService") || out.contains("ByeDpiProxyService");
    }

    public static void stop(Context c, Adb adb) {
        adb.execSync("settings put secure always_on_vpn_app ''", true);
        adb.execSync("am start -n " + TOGGLE + " --ez only_stop true", true);
    }

    interface Progress { void on(String t); }

    /** Скачать APK по ссылке и установить. null = успех, иначе текст ошибки. */
    static String installUrl(Context c, Adb adb, String name, String url, Progress pr) {
        File f = new File(c.getCacheDir(), "dpi.apk");
        try {
            byte[] data = InstallActivity.download(url, p -> pr.on("скачиваю " + p + "%"));
            try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
        } catch (Throwable t) { return "не скачался: " + t.getMessage(); }
        pr.on("устанавливаю…");
        Adb.Result r = adb.installSync(f);
        f.delete();
        return r.ok ? null : "ошибка установки: " + r.out;
    }

    private YouTube() { }
}
