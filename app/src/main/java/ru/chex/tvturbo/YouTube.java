package ru.chex.tvturbo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * YouTube в России «под ключ»: ставит ByeByeDPI (обход замедления на самом ТВ) и SmartTube,
 * сам выдаёт разрешение VPN (без окна), включает обход и делает его постоянным —
 * он поднимается сам после каждой перезагрузки. Работает для всех приложений, включая обычный YouTube.
 */
public final class YouTube {

    public static final String DPI_PKG = "io.github.romanvht.byedpi";
    public static final String DPI_REPO = "romanvht/ByeByeDPI";
    public static final String TUBE_PKG = "com.teamsmart.videomanager.tv";
    public static final String TUBE_REPO = "yuliskov/SmartTube";
    private static final String TOGGLE = DPI_PKG + "/.activities.ToggleActivity";

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

    /** Полная настройка. Шаги: 0 ByeByeDPI, 1 SmartTube, 2 разрешение VPN, 3 запуск, 4 автозапуск. Только в потоке Adb. */
    public static boolean setup(Context c, Adb adb, Step st) {
        boolean ok = true;

        // 0. ByeByeDPI
        if (installed(c, DPI_PKG)) st.on(0, "ok", "уже установлен");
        else {
            String r = install(c, adb, "ByeByeDPI", DPI_REPO, "", "", t -> st.on(0, "run", t));
            if (r != null) { st.on(0, "bad", r); return false; }
            st.on(0, "ok", "установлен");
        }

        // 1. SmartTube
        if (installed(c, TUBE_PKG)) st.on(1, "ok", "уже установлен");
        else {
            String r = install(c, adb, "SmartTube", TUBE_REPO, "stable", "latest", t -> st.on(1, "run", t));
            if (r != null) { st.on(1, "warn", r); ok = false; }
            else st.on(1, "ok", "установлен");
        }

        // 2. разрешение VPN без окна подтверждения
        st.on(2, "run", "");
        adb.execSync("cmd appops set " + DPI_PKG + " ACTIVATE_VPN allow; appops set " + DPI_PKG + " ACTIVATE_VPN allow", true);
        adb.execSync("pm grant " + DPI_PKG + " android.permission.POST_NOTIFICATIONS", false);
        adb.execSync("dumpsys deviceidle whitelist +" + DPI_PKG, false); // не усыплять обход
        st.on(2, "ok", "выдано");

        // 3. запуск с текущей стратегией
        st.on(3, "run", "");
        boolean started = start(c, adb, strategy(c));
        st.on(3, started ? "ok" : "warn", started ? "обход работает" : "открой ByeByeDPI и нажми «Подключить»");
        if (!started) ok = false;

        // 4. постоянный VPN — обход поднимается сам после перезагрузки
        st.on(4, "run", "");
        Adb.Result a = adb.execSync("settings put secure always_on_vpn_app " + DPI_PKG + "; settings put secure always_on_vpn_lockdown 0", true);
        st.on(4, a.ok ? "ok" : "warn", a.ok ? "включится сам после перезагрузки" : "не удалось");
        return ok;
    }

    /** Включить (или перезапустить) обход со стратегией №i. */
    public static boolean start(Context c, Adb adb, int i) {
        String s = STRATEGIES[Math.max(0, Math.min(i, STRATEGIES.length - 1))];
        sp(c).edit().putInt("strategy", i).apply();
        // ToggleActivity: если обход выключен — включит, если включён и стратегия новая — перезапустит
        adb.execSync("am start -n " + TOGGLE + " --es strategy '" + s + "' --ez only_update true", true);
        if (isRunning(adb)) {                      // новая стратегия применяется перезапуском
            adb.execSync("am start -n " + TOGGLE + " --ez only_stop true", true);
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        }
        adb.execSync("am start -n " + TOGGLE + " --ez only_start true", true);
        try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
        return isRunning(adb);
    }

    public static boolean isRunning(Adb adb) {
        String out = adb.execSync("dumpsys activity services " + DPI_PKG, false).out;
        return out.contains("ByeDpiVpnService") || out.contains("ByeDpiProxyService");
    }

    public static void stop(Context c, Adb adb) {
        adb.execSync("settings put secure always_on_vpn_app ''", true);
        adb.execSync("am start -n " + TOGGLE + " --ez only_stop true", true);
    }

    interface Progress { void on(String t); }

    /** Скачать последний релиз с GitHub и установить. null = успех, иначе текст ошибки. */
    static String install(Context c, Adb adb, String name, String repo, String asset, String tag, Progress pr) {
        File f = new File(c.getCacheDir(), "yt_" + name + ".apk");
        try {
            pr.on("ищу последнюю версию");
            String url = InstallActivity.latestGithubApk(repo, asset, tag);
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
