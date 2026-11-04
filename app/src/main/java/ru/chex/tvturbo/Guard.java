package ru.chex.tvturbo;

import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * «Страж»: запускается сам при включении ТВ и каждые 30 минут.
 * Сверяет ТВ с профилем и тихо возвращает то, что слетело: отключённый мусор, анимации,
 * DNS-блокировку рекламы, заставку, обход YouTube, главный экран. Следит за памятью.
 */
public final class Guard {

    public static final int JOB_PERIODIC = 4101, JOB_NOW = 4102;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("guard", Context.MODE_PRIVATE); }
    public static boolean enabled(Context c) { return sp(c).getBoolean("on", true); }
    public static boolean openOnBoot(Context c) { return sp(c).getBoolean("openOnBoot", false); }
    public static void setOpenOnBoot(Context c, boolean v) { sp(c).edit().putBoolean("openOnBoot", v).apply(); }
    public static long lastRun(Context c) { return sp(c).getLong("last", 0); }
    public static String lastResult(Context c) { return sp(c).getString("lastResult", "ещё не запускался"); }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("on", on).apply();
        if (on) schedule(c); else cancel(c);
    }

    /** Периодическая проверка каждые 30 минут (переживает перезагрузку). */
    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_PERIODIC) return;
        js.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, GuardJob.class))
                .setPeriodic(30 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    /** Разовая проверка как можно скорее (после включения ТВ). */
    public static void runSoon(Context c, long delayMs) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(c, GuardJob.class))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 60_000)
                .build());
    }

    public static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) { js.cancel(JOB_PERIODIC); js.cancel(JOB_NOW); }
    }

    /** Проверить и починить. Возвращает итог для сообщения. Только в потоке Adb. */
    public static String check(Context c, boolean boot) {
        Adb adb = Adb.get(c);
        if (!adb.connectWithRetry(boot ? 12 : 2, 10_000)) {
            String r = "нет подключения к отладке";
            save(c, r);
            return r;
        }
        int fixed = 0;
        StringBuilder what = new StringBuilder();
        seed(c, adb);
        Launchers.dropBuiltin(c, adb);

        // 1. отключённые пакеты — ТВ после обновления прошивки может включить их обратно
        Map<String, Pkgs.P> pkgs = Pkgs.map(c);
        String home = Pkgs.currentHome(c);
        for (String p : Profile.disabled(c)) {
            Pkgs.P st = pkgs.get(p);
            if (st == null || !st.installed || !st.enabled || p.equals(home)) continue;
            if (adb.execSync(Pkgs.cmdDisable(p), true).ok) { fixed++; note(what, "мусор снова отключён"); }
        }
        for (String p : Profile.noBg(c)) {
            Adb.Result r = adb.execSync("cmd appops get " + p + " RUN_ANY_IN_BACKGROUND", false);
            if (r.ok && r.out.contains("allow")) { adb.execSync(Pkgs.cmdNoBackground(p), true); fixed++; note(what, "фон запрещён"); }
        }

        // 2. анимации
        String anim = Profile.anim(c);
        if (anim != null) {
            String cur = adb.execSync("settings get global window_animation_scale", false).out.trim();
            if (!same(cur, anim)) { for (String cmd : Speed.animations(anim)) adb.execSync(cmd, true); fixed++; note(what, "анимации"); }
        }

        // 3. DNS-блокировка рекламы
        if ("on".equals(Profile.dns(c)) && android.os.Build.VERSION.SDK_INT >= 28) {
            String mode = adb.execSync("settings get global private_dns_mode", false).out.trim();
            if (!"hostname".equals(mode)) { for (String cmd : Speed.adblockDns(true)) adb.execSync(cmd, true); fixed++; note(what, "блокировка рекламы"); }
        }

        // 4. заставка
        if (Profile.saverOff(c)) {
            String sv = adb.execSync("settings get secure screensaver_enabled", false).out.trim();
            if ("1".equals(sv)) { adb.execSync("settings put secure screensaver_enabled 0", true); fixed++; note(what, "заставка"); }
        }

        // 5. главный экран
        String wantHome = Profile.home(c);
        if (wantHome != null && wantHome.contains("/")) {
            String wantPkg = wantHome.substring(0, wantHome.indexOf('/'));
            if (!wantPkg.equals(home) && pkgs.containsKey(wantPkg) && pkgs.get(wantPkg).installed) {
                if (adb.execSync("cmd package set-home-activity " + wantHome, true).ok) { fixed++; note(what, "главный экран"); }
            }
        }

        // 5б. PatchWall / стандартный лаунчер: отключить, а если уже на экране — уйти на свой
        {
            String fg = Launchers.foreground(adb);
            String curHome = Pkgs.currentHome(c);
            int off = Launchers.disableStock(c, adb);
            if (off > 0) { fixed++; note(what, "PatchWall отключён"); }
            // на экране чужой стартовый экран (PatchWall) — уйти на свой главный
            if (!fg.isEmpty() && !fg.equals(curHome) && (Launchers.isPatchWall(fg) || (Launchers.isStock(fg) && !Launchers.isStock(curHome)))) {
                adb.execSync("input keyevent 3", true);
                fixed++; note(what, "открыт твой главный экран");
            }
        }

        // 6. YouTube: обход без сервера или свой VPN — что работает
        if (Profile.youtube(c)) {
            int[] f = {0};
            String r = youtube(c, adb, f);
            if (f[0] > 0) { fixed += f[0]; note(what, r); }
        }

        // 7. память: если свободно меньше 20% — закрыть фоновые и почистить кэш
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int freePct = (int) (100 * mi.availMem / Math.max(1, mi.totalMem));
        if (freePct < 20) {
            adb.execSync("am kill-all", true);
            adb.execSync("pm trim-caches 999G", false);
            fixed++; note(what, "память освобождена (было " + freePct + "%)");
        }

        String result = fixed == 0 ? "всё в порядке" : "исправлено " + fixed + ": " + what;
        adb.logLine("🛡 Страж" + (boot ? " (включение ТВ)" : "") + ": " + result);
        save(c, result);
        return result;
    }

    /**
     * Следит, чтобы YouTube открывался. Сначала — то, что выбрано (свой VPN, если ключ задан, иначе обход).
     * Если YouTube через него не открывается, а интернет есть — пробует другое: обход ⇄ свой VPN,
     * а без своего сервера — следующую стратегию обхода. Остаётся на том, что заработало.
     */
    private static String youtube(Context c, Adb adb, int[] fixed) {
        // переход со старого ByeByeDPI (VPN на весь ТВ) на «ТВ Турбо Обход» (только YouTube)
        if (!YouTube.installed(c, YouTube.DPI_PKG)) {
            if (YouTube.setup(c, adb, (st, state, text) -> { })) { fixed[0]++; return "обход теперь только для YouTube"; }
        }
        boolean vpnOk = YouTube.hasVpn(c) && YouTube.installed(c, YouTube.VPN_PKG);
        String act = vpnOk ? YouTube.active(c) : "dpi";
        String msg = "";
        if ("vpn".equals(act)) {
            if (!YouTube.isVpnRunning(adb)) { if (YouTube.startVpn(c, adb)) { fixed[0]++; msg = "свой VPN для YouTube включён"; } }
        } else {
            YouTube.grant(adb);
            if (!YouTube.isRunning(adb)) { if (YouTube.startDpi(c, adb)) { fixed[0]++; msg = "обход YouTube включён"; } }
        }
        // проверка: открывается ли YouTube на самом деле
        if (YouTube.youtubeWorks(act) || !YouTube.internet()) { sp(c).edit().putInt("ytFails", 0).apply(); return msg; }
        adb.logLine("🛡 Страж: YouTube не открывается через " + ("vpn".equals(act) ? "свой VPN" : "обход") + " — пробую другое");
        if ("vpn".equals(act)) {
            if (YouTube.startDpi(c, adb) && YouTube.youtubeWorks("dpi")) { fixed[0]++; return "свой VPN не отвечал — включён обход"; }
            YouTube.startVpn(c, adb);
            return msg;
        }
        if (vpnOk) {
            if (YouTube.startVpn(c, adb) && YouTube.youtubeWorks("vpn")) { fixed[0]++; return "обход не пробивал — включён свой VPN"; }
            YouTube.startDpi(c, adb);
            return msg;
        }
        // своего сервера нет — следующая стратегия обхода, но только после двух неудачных проверок подряд
        android.content.SharedPreferences g = sp(c);
        int fails = g.getInt("ytFails", 0) + 1;
        if (fails < 2) { g.edit().putInt("ytFails", fails).apply(); return msg; }
        g.edit().putInt("ytFails", 0).apply();
        int next = (YouTube.strategy(c) + 1) % YouTube.STRATEGIES.length;
        if (YouTube.start(c, adb, next)) { fixed[0]++; return "обход: стратегия " + (next + 1); }
        return msg;
    }

    /**
     * Первый запуск стража: если профиль пуст, а на ТВ уже что-то настроено (старые версии
     * приложения не вели профиль) — берём текущее состояние за образец.
     */
    private static void seed(Context c, Adb adb) {
        if (sp(c).getBoolean("seeded", false)) return;
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("profile", Context.MODE_PRIVATE).edit();
        java.util.Set<String> dis = Profile.disabled(c);
        for (Pkgs.P p : Pkgs.restorable(c)) if (p.installed && Bloat.find(p.pkg) != null) dis.add(p.pkg);
        e.putStringSet("disabled", dis);
        if (Profile.anim(c) == null) {
            String a = adb.execSync("settings get global window_animation_scale", false).out.trim();
            if (a.equals("0.5") || a.equals("0.0") || a.equals("0")) e.putString("anim", a);
        }
        if (Profile.dns(c) == null && "hostname".equals(adb.execSync("settings get global private_dns_mode", false).out.trim())) e.putString("dns", "on");
        String aon = adb.execSync("settings get secure always_on_vpn_app", false).out;
        if (aon.contains(YouTube.DPI_PKG) || aon.contains(YouTube.OLD_DPI_PKG) || aon.contains(YouTube.VPN_PKG)) e.putBoolean("youtube", true);
        e.apply();
        sp(c).edit().putBoolean("seeded", true).apply();
    }

    private static void note(StringBuilder sb, String s) {
        if (sb.indexOf(s) >= 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(s);
    }

    private static boolean same(String a, String b) {
        try { return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 0.01; } catch (Throwable t) { return false; }
    }

    private static void save(Context c, String r) {
        sp(c).edit().putLong("last", System.currentTimeMillis()).putString("lastResult", r).apply();
    }

    private Guard() { }
}
