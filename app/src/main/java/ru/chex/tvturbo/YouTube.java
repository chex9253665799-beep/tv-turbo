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
        boolean useVpn = hasVpn(c) && installed(c, VPN_PKG) && "vpn".equals(active(c));
        boolean started = useVpn ? startVpn(c, adb) : startDpi(c, adb);
        st.on(3, started ? "ok" : "warn", started ? (useVpn ? "свой VPN работает только для YouTube" : "обход работает только для YouTube")
                : "не запустился — нажми «Настроить всё» ещё раз");
        if (!started) ok = false;

        // 4. постоянный VPN — обход поднимается сам после перезагрузки
        st.on(4, "run", "");
        Adb.Result a = adb.execSync("settings put secure always_on_vpn_app " + (useVpn ? VPN_PKG : DPI_PKG) + "; settings put secure always_on_vpn_lockdown 0", true);
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
        String out = adb.execSync("dumpsys activity services " + DPI_PKG, false).out;
        if (out.contains("ByeDpiVpnService") || out.contains("ByeDpiProxyService")) return true;
        String net = adb.execSync("ls /sys/class/net 2>/dev/null", false).out;
        return net.contains("tun") && !isVpnRunning(adb);   // туннель есть, и он не от «ТВ Турбо VPN»
    }

    public static void stop(Context c, Adb adb) {
        adb.execSync("settings put secure always_on_vpn_app ''", true);
        adb.execSync("am start -n " + TOGGLE + " --ez only_stop true", true);
        if (installed(c, VPN_PKG)) { vpnControl(adb, "stop"); adb.execSync("am force-stop " + VPN_PKG, false); }
    }

    // ===================================================== свой VPN-сервер (только YouTube)

    /** «ТВ Турбо VPN» — v2rayNG, в котором через твой сервер идёт только YouTube. */
    public static final String VPN_PKG = "ru.chex.tvturbo.vpn";
    private static final String VPN_CTL = "com.v2ray.ang.ui.TvTurboActivity";
    private static final String VPN_CFG = "com.v2ray.ang.ui.TvTurboConfigActivity";
    /** Пока свой VPN в тесте — берём из тестового релиза. */
    public static final String VPN_RELEASE = "vpn";
    public static final String[] APPS = {
            "com.google.android.youtube.tv", "com.google.android.youtube.tvkids", "com.google.android.youtube.tvmusic",
            "com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.google.android.apps.youtube.music"
    };
    static final int PORT_DPI = 1080, PORT_VPN = 10808;

    public static String vpnUrl() {
        boolean arm64 = false;
        for (String a : android.os.Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(a)) arm64 = true;
        return "https://github.com/" + Updater.REPO + "/releases/download/" + VPN_RELEASE + "/TvTurboVPN-" + (arm64 ? "arm64" : "arm") + ".apk";
    }

    /** Ключ сервера хранится только здесь, на телевизоре (в репозиторий и APK он не попадает). */
    public static String vpnLink(Context c) { return sp(c).getString("vpnLink", ""); }
    public static boolean hasVpn(Context c) { return !vpnLink(c).isEmpty(); }
    public static void saveVpnLink(Context c, String link) { sp(c).edit().putString("vpnLink", link == null ? "" : link.trim()).apply(); }

    /** Что сейчас используется для YouTube: "vpn" — свой сервер, "dpi" — обход без сервера. */
    public static String active(Context c) { return sp(c).getString("active", hasVpn(c) ? "vpn" : "dpi"); }
    public static void setActive(Context c, String a) { sp(c).edit().putString("active", a).apply(); }

    /** Короткое описание сервера для экрана (без ключа). */
    public static String vpnLabel(Context c) {
        String l = vpnLink(c);
        if (l.isEmpty()) return "не задан";
        String proto = l.contains("://") ? l.substring(0, l.indexOf("://")) : "?";
        String name = l.contains("#") ? android.net.Uri.decode(l.substring(l.lastIndexOf('#') + 1)) : "";
        String host = "";
        try { host = android.net.Uri.parse(l).getHost(); } catch (Throwable ignored) { }
        if (proto.startsWith("http")) return "подписка " + (host == null ? "" : host);
        return proto + (name.isEmpty() ? "" : " · " + name) + (host == null || host.isEmpty() ? "" : " · " + mask(host));
    }

    private static String mask(String h) {
        if (h.length() <= 6) return h;
        return h.substring(0, 3) + "…" + h.substring(h.length() - 3);
    }

    public static boolean looksLikeKey(String s) {
        s = s.trim().toLowerCase();
        return s.startsWith("vless://") || s.startsWith("vmess://") || s.startsWith("trojan://") || s.startsWith("ss://")
                || s.startsWith("hy2://") || s.startsWith("hysteria2://") || s.startsWith("tuic://") || s.startsWith("wireguard://")
                || s.startsWith("socks://") || s.startsWith("https://") || s.startsWith("http://");
    }

    /** Установить «ТВ Турбо VPN». null = успех. Только в потоке Adb. */
    public static String installVpn(Context c, Adb adb, Progress pr) {
        if (installed(c, VPN_PKG)) return null;
        return installUrl(c, adb, "ТВ Турбо VPN", vpnUrl(), pr);
    }

    public static void vpnGrant(Adb adb) {
        adb.execSync("cmd appops set " + VPN_PKG + " ACTIVATE_VPN allow 2>/dev/null; appops set " + VPN_PKG + " ACTIVATE_VPN allow 2>/dev/null; true", false);
        adb.execSync("cmd appops set " + VPN_PKG + " RUN_ANY_IN_BACKGROUND allow 2>/dev/null; cmd appops set " + VPN_PKG + " RUN_IN_BACKGROUND allow 2>/dev/null; true", false);
        adb.execSync("pm grant " + VPN_PKG + " android.permission.POST_NOTIFICATIONS 2>/dev/null; true", false);
        adb.execSync("dumpsys deviceidle whitelist +" + VPN_PKG + " >/dev/null 2>&1; true", false);
    }

    /** Команда клиенту VPN: start / stop / apply (ключ так не передаётся — только через окно настройки). */
    static void vpnControl(Adb adb, String action) {
        adb.execSync("am start -n " + VPN_PKG + "/" + VPN_CTL + " --es action " + action, false);
    }

    /** Окно настройки клиента: передать ключ и список приложений. Запускать через startActivityForResult. */
    public static android.content.Intent vpnConfigIntent(Context c, String link) {
        return new android.content.Intent().setClassName(VPN_PKG, VPN_CFG)
                .putExtra("link", link).putExtra("apps", APPS).putExtra("start", false);
    }

    public static boolean isVpnRunning(Adb adb) {
        return adb.execSync("dumpsys activity services " + VPN_PKG, false).out.contains("CoreVpnService");
    }

    /** Включить свой VPN (обход без сервера выключается — на ТВ может работать только один VPN). Только в потоке Adb. */
    public static boolean startVpn(Context c, Adb adb) {
        vpnGrant(adb);
        adb.execSync("am start -n " + TOGGLE + " --ez only_stop true 2>/dev/null; true", false);
        adb.execSync("am force-stop " + DPI_PKG + " 2>/dev/null; true", false);
        adb.execSync("settings put secure always_on_vpn_app " + VPN_PKG + "; settings put secure always_on_vpn_lockdown 0", true);
        vpnControl(adb, "start");
        for (int t = 0; t < 15; t += 2) { sleep(2000); if (isVpnRunning(adb)) { setActive(c, "vpn"); return true; } }
        return false;
    }

    /** Включить обход без сервера (свой VPN выключается). Только в потоке Adb. */
    public static boolean startDpi(Context c, Adb adb) {
        if (installed(c, VPN_PKG)) { vpnControl(adb, "stop"); sleep(1500); adb.execSync("am force-stop " + VPN_PKG, false); }
        adb.execSync("settings put secure always_on_vpn_app " + DPI_PKG + "; settings put secure always_on_vpn_lockdown 0", true);
        boolean ok = start(c, adb, strategy(c));
        if (ok) setActive(c, "dpi");
        return ok;
    }

    /** Есть ли вообще интернет (напрямую). */
    public static boolean internet() { return fetch(null, "https://ya.ru/", 200); }

    /** Открывается ли YouTube через то, что сейчас включено (через локальный прокси обхода/VPN). */
    public static boolean youtubeWorks(String active) {
        java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress("127.0.0.1", "vpn".equals(active) ? PORT_VPN : PORT_DPI));
        return fetch(p, "https://www.youtube.com/generate_204", 204)
                && fetch(p, "https://redirector.googlevideo.com/report_mapping?di=no", 200);
    }

    private static boolean fetch(java.net.Proxy proxy, String url, int want) {
        java.net.HttpURLConnection h = null;
        try {
            java.net.URL u = new java.net.URL(url);
            h = (java.net.HttpURLConnection) (proxy == null ? u.openConnection() : u.openConnection(proxy));
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setInstanceFollowRedirects(false);
            int code = h.getResponseCode();
            return code == want || (want == 200 && code >= 200 && code < 400);
        } catch (Throwable t) {
            return false;
        } finally { if (h != null) h.disconnect(); }
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
