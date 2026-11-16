package ru.chex.tvturbo;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Закрыть приложения, которые висят в фоне и не открывались дольше заданного времени — освобождает память.
 * «am kill» закрывает только фоновые процессы: данные и вход в аккаунты не теряются, при открытии приложение просто запустится заново.
 * Не трогаем: то, что на экране, главный экран, клавиатуру, обход/VPN, ТВ Турбо, системные службы,
 * и всё, что работает «на переднем плане» в фоне (музыка, загрузки, трансляция).
 */
public final class Idle {

    private static final String[] NEVER = {
            "ru.chex.tvturbo", YouTube.DPI_PKG, "ru.chex.tvturbo.vpn", YouTube.OLD_DPI_PKG,
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
            "com.android.systemui", "com.android.tv.settings", "com.android.settings", "com.google.android.katniss",
    };

    public static boolean enabled(Context c) { return c.getSharedPreferences("guard", Context.MODE_PRIVATE).getBoolean("closeIdle", true); }
    public static void setEnabled(Context c, boolean on) { c.getSharedPreferences("guard", Context.MODE_PRIVATE).edit().putBoolean("closeIdle", on).apply(); }

    /** Закрывает неиспользуемые; возвращает их названия. Только в потоке Adb. */
    public static List<String> close(Context c, Adb adb, long idleMs) {
        List<String> closed = new ArrayList<>();
        // право читать статистику использования приложений (выдаём себе через отладку)
        adb.execSync("appops set " + c.getPackageName() + " GET_USAGE_STATS allow 2>/dev/null; true", false);

        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return closed;
        long now = System.currentTimeMillis();
        Map<String, UsageStats> usage;
        try { usage = usm.queryAndAggregateUsageStats(now - 3L * 24 * 3600 * 1000, now); } catch (Throwable t) { return closed; }
        if (usage == null || usage.isEmpty()) return closed;   // статистика недоступна — ничего не трогаем

        // что запущено
        Set<String> running = new HashSet<>();
        String ps = adb.execSync("ps -A -o NAME 2>/dev/null || ps", false).out;
        for (String l : ps.split("\n")) {
            String[] f = l.trim().split("\\s+");
            String name = f[f.length - 1];
            if (!name.contains(".") || name.startsWith("/")) continue;
            if (name.contains(":")) name = name.substring(0, name.indexOf(':'));
            running.add(name);
        }

        // что работает «на переднем плане» в фоне — музыка, загрузки, VPN: не трогаем
        Set<String> fgService = new HashSet<>();
        String cur = null;
        Matcher rec = Pattern.compile("ServiceRecord\\{[^}]*? u\\d+ ([\\w.]+)/").matcher("");
        for (String l : adb.execSync("dumpsys activity services 2>/dev/null | grep -E 'ServiceRecord\\{|isForeground=true'", false).out.split("\n")) {
            rec.reset(l);
            if (rec.find()) cur = rec.group(1);
            else if (l.contains("isForeground=true") && cur != null) fgService.add(cur);
        }

        Set<String> keep = new HashSet<>();
        for (String n : NEVER) keep.add(n);
        keep.add(Launchers.foreground(adb));
        keep.add(Pkgs.currentHome(c));
        String ime = adb.execSync("settings get secure default_input_method", false).out.trim();
        if (ime.contains("/")) keep.add(ime.substring(0, ime.indexOf('/')));

        PackageManager pm = c.getPackageManager();
        for (String p : running) {
            if (keep.contains(p) || fgService.contains(p) || p.startsWith("android") || p.startsWith("com.android.")) continue;
            ApplicationInfo ai;
            try { ai = pm.getApplicationInfo(p, 0); } catch (Throwable t) { continue; }
            if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
            // только приложения, которые человек открывает сам (есть значок)
            boolean userApp = pm.getLaunchIntentForPackage(p) != null
                    || (android.os.Build.VERSION.SDK_INT >= 21 && pm.getLeanbackLaunchIntentForPackage(p) != null);
            if (!userApp) continue;
            UsageStats us = usage.get(p);
            long last = us == null ? 0 : us.getLastTimeUsed();
            if (last > 0 && now - last < idleMs) continue;   // открывали недавно
            if (adb.execSync("am kill " + p, false).ok) {
                CharSequence label = pm.getApplicationLabel(ai);
                closed.add(label == null ? p : label.toString());
            }
        }
        if (!closed.isEmpty())
            adb.logLine("🧹 Страж: закрыты неиспользуемые больше " + (idleMs / 60000) + " мин: " + android.text.TextUtils.join(", ", closed));
        return closed;
    }

    private Idle() { }
}
