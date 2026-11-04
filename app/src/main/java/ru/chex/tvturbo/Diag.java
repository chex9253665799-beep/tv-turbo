package ru.chex.tvturbo;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Диагностика: замеряет, что тормозит ТВ, и выдаёт рекомендации с готовыми командами.
 * Запускается в очереди Adb (фоновый поток).
 */
public final class Diag {

    public static final int OK = 0, WARN = 1, BAD = 2;

    public static final class Rec {
        public int level; public String title, detail, action;
        public final List<String> cmds = new ArrayList<>();
        public Class<?> open;          // открыть экран вместо команд
        public String rememberPkg;     // запомнить изменение для «Вернуть всё»
        public char rememberKind;
        Rec(int level, String title, String detail) { this.level = level; this.title = title; this.detail = detail; }
    }

    public static final class Report {
        public final List<Rec> recs = new ArrayList<>();
        public String summary = "";
    }

    private static final Pattern MEM_LINE = Pattern.compile("^\\s*([\\d,]+)K: (\\S+) \\(pid");
    private static final Pattern CPU_LINE = Pattern.compile("^\\s*([\\d.]+)% \\d+/([^:\\s]+):");

    public static Report run(Context c) {
        Adb adb = Adb.get(c);
        Report rep = new Report();
        Map<String, Pkgs.P> pkgs = Pkgs.map(c);
        int bad = 0, warn = 0;

        // ---- 1. оперативная память ----
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long totalMb = mi.totalMem / 1048576, freeMb = mi.availMem / 1048576;
        int freePct = (int) (100 * mi.availMem / Math.max(1, mi.totalMem));
        Rec ram = new Rec(freePct < 20 ? BAD : freePct < 35 ? WARN : OK,
                "Свободная память: " + freeMb + " из " + totalMb + " МБ (" + freePct + "%)",
                freePct < 35 ? "Мало памяти — приложения выгружаются и перезапускаются, отсюда подтормаживания."
                             : "Памяти хватает.");
        if (freePct < 35) {
            ram.action = "Освободить память (закрыть фоновые)";
            ram.cmds.add("am kill-all");
        }
        rep.recs.add(ram);

        // ---- 2. тяжёлые процессы ----
        String mem = adb.execSync("dumpsys meminfo", false).out;
        List<String[]> heavy = new ArrayList<>(); // {pkg, mb}
        boolean inPss = false;
        for (String line : mem.split("\n")) {
            if (line.contains("Total PSS by process")) { inPss = true; continue; }
            if (inPss) {
                if (line.trim().isEmpty() || line.contains("by OOM adjustment")) break;
                Matcher m = MEM_LINE.matcher(line);
                if (m.find()) {
                    long kb = Long.parseLong(m.group(1).replace(",", ""));
                    String proc = m.group(2);
                    String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
                    if (kb >= 60 * 1024 && pkgs.containsKey(pkg)) heavy.add(new String[]{pkg, String.valueOf(kb / 1024)});
                }
            }
        }
        String home = Pkgs.currentHome(c);
        int shown = 0;
        for (String[] h : heavy) {
            String pkg = h[0];
            if (pkg.equals(c.getPackageName()) || Bloat.isCritical(pkg) || pkg.equals(home)) continue;
            if (shown++ >= 6) break;
            Pkgs.P p = pkgs.get(pkg);
            Bloat.Item known = Bloat.find(pkg);
            boolean bloat = (known != null && known.level == Bloat.SAFE) || Bloat.isSuspicious(pkg);
            Rec r = new Rec(bloat ? BAD : WARN,
                    p.label + " занимает " + h[1] + " МБ памяти",
                    pkg + (bloat ? "\nЭто мусор/реклама — лучше отключить." : "\nЕсли не пользуешься постоянно — запрети работу в фоне."));
            if (bloat) {
                r.action = "Отключить";
                r.cmds.add(Pkgs.cmdDisable(pkg));
                r.rememberPkg = pkg; r.rememberKind = 'd';
            } else {
                r.action = "Запретить работу в фоне";
                r.cmds.add(Pkgs.cmdNoBackground(pkg));
                r.rememberPkg = pkg; r.rememberKind = 'b';
            }
            rep.recs.add(r);
        }

        // ---- 3. нагрузка на процессор ----
        String cpu = adb.execSync("dumpsys cpuinfo", false).out;
        for (String line : cpu.split("\n")) {
            Matcher m = CPU_LINE.matcher(line);
            if (!m.find()) continue;
            double pct = Double.parseDouble(m.group(1));
            String proc = m.group(2);
            String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
            if (pct < 15) break; // список отсортирован по убыванию
            if (!pkgs.containsKey(pkg) || Bloat.isCritical(pkg) || pkg.equals(c.getPackageName())) continue;
            Pkgs.P p = pkgs.get(pkg);
            Rec r = new Rec(pct >= 30 ? BAD : WARN,
                    p.label + " грузит процессор на " + Math.round(pct) + "%",
                    pkg + "\nПостоянная нагрузка в фоне — частая причина тормозов.");
            r.action = "Остановить и запретить фон";
            r.cmds.add(Pkgs.cmdNoBackground(pkg));
            r.rememberPkg = pkg; r.rememberKind = 'b';
            rep.recs.add(r);
        }

        // ---- 4. мусор и реклама, которые ещё включены ----
        int junk = 0;
        for (Bloat.Item i : Bloat.KNOWN) {
            Pkgs.P p = pkgs.get(i.pkg);
            if (p != null && p.installed && p.enabled && i.level == Bloat.SAFE) junk++;
        }
        int sus = 0;
        for (Pkgs.P p : pkgs.values()) if (p.system && p.installed && p.enabled && Bloat.find(p.pkg) == null && Bloat.isSuspicious(p.pkg)) sus++;
        if (junk + sus > 0) {
            Rec r = new Rec(junk >= 3 ? BAD : WARN,
                    "Включено лишнее: " + junk + " известных + " + sus + " подозрительных пакетов",
                    "Реклама, рекомендации, телеметрия и ненужные предустановки висят в фоне и едят память.");
            r.action = "Открыть мастер очистки";
            r.open = CleanActivity.class;
            rep.recs.add(r);
        } else rep.recs.add(new Rec(OK, "Мусорных пакетов не найдено", ""));

        // ---- 5. анимации ----
        double anim = parseD(adb.execSync("settings get global window_animation_scale", false).out, 1.0);
        if (anim >= 1.0) {
            Rec r = new Rec(WARN, "Анимации на 100%", "Если уменьшить до 50%, меню станет заметно отзывчивее.");
            r.action = "Поставить 0.5×";
            r.cmds.addAll(Speed.animations("0.5"));
            rep.recs.add(r);
        } else rep.recs.add(new Rec(OK, "Анимации уже ускорены (" + anim + "×)", ""));

        // ---- 6. реклама через DNS ----
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            String mode = adb.execSync("settings get global private_dns_mode", false).out.trim();
            String host = adb.execSync("settings get global private_dns_specifier", false).out.trim();
            if (!"hostname".equals(mode)) {
                Rec r = new Rec(WARN, "Блокировка рекламы через DNS выключена",
                        "AdGuard DNS режет рекламу и трекеры во всех приложениях на уровне сети.");
                r.action = "Включить AdGuard DNS";
                r.cmds.addAll(Speed.adblockDns(true));
                rep.recs.add(r);
            } else rep.recs.add(new Rec(OK, "Личный DNS включён: " + host, ""));
        }

        // ---- 7. лаунчер ----
        if (home.equals("com.google.android.apps.tv.launcherx") || home.equals("com.google.android.tvlauncher")
                || home.equals("com.google.android.leanbacklauncher") || home.startsWith("com.mitv.") || Bloat.isSuspicious(home)) {
            Pkgs.P hp = pkgs.get(home);
            Rec r = new Rec(WARN, "Тяжёлый главный экран: " + (hp != null ? hp.label : home),
                    "Стандартный лаунчер грузит рекламу и рекомендации. Лёгкий лаунчер (например FLauncher) экономит 100–200 МБ.");
            r.action = "Открыть установку приложений";
            r.open = InstallActivity.class;
            rep.recs.add(r);
        }

        // ---- 8. место на диске ----
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long freeDisk = fs.getAvailableBytes() / 1048576, totalDisk = fs.getTotalBytes() / 1048576;
        int diskPct = (int) (100 * fs.getAvailableBytes() / Math.max(1, fs.getTotalBytes()));
        Rec disk = new Rec(diskPct < 10 ? BAD : diskPct < 20 ? WARN : OK,
                "Свободно места: " + freeDisk + " из " + totalDisk + " МБ (" + diskPct + "%)",
                diskPct < 20 ? "Когда памяти мало, система тормозит и не может обновляться." : "Места достаточно.");
        if (diskPct < 30) { disk.action = "Очистить кэш всех приложений"; disk.cmds.add("pm trim-caches 999G"); }
        rep.recs.add(disk);

        // ---- 9. давно без перезагрузки ----
        double up = parseD(adb.execSync("cat /proc/uptime", false).out.split(" ")[0], 0) / 3600.0;
        if (up > 72) {
            Rec r = new Rec(WARN, String.format(Locale.US, "ТВ не перезагружался %.0f ч (%.1f дн.)", up, up / 24),
                    "ТВ в режиме ожидания не перезагружается неделями — копится мусор в памяти.");
            r.action = "Перезагрузить сейчас";
            r.cmds.add("reboot");
            rep.recs.add(r);
        }

        // ---- 10. заставка ----
        String saver = adb.execSync("settings get secure screensaver_enabled", false).out.trim();
        if ("1".equals(saver)) {
            Rec r = new Rec(WARN, "Включена заставка (Ambient/фото)", "Заставка грузит картинки из сети и держит память.");
            r.action = "Выключить заставку";
            r.cmds.add("settings put secure screensaver_enabled 0");
            rep.recs.add(r);
        }

        for (Rec r : rep.recs) { if (r.level == BAD) bad++; else if (r.level == WARN) warn++; }
        Collections.sort(rep.recs, (a, b) -> b.level - a.level);
        rep.summary = bad == 0 && warn == 0 ? "✔ Всё в порядке, тормозить нечему."
                : "Найдено проблем: " + bad + " серьёзных, " + warn + " некритичных. Нажми на пункт, чтобы исправить.";
        return rep;
    }

    private static double parseD(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return def; }
    }

    private Diag() { }
}
