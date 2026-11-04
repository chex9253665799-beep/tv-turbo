package ru.chex.tvturbo;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Доктор ТВ»: ищет программные неисправности (вылеты, зависания, перезагрузки, перегрев, износ памяти,
 * сеть, время, отключённые службы) и объясняет, что делать. Плюс «Паспорт ТВ» — всё железо и коды прошивки.
 * Только в потоке Adb.
 */
public final class Doctor {

    public static final int INFO = -1, OK = 0, WARN = 1, BAD = 2;

    public static final class Item {
        public int level;
        public String title, detail;
        public String fixLabel;                       // кнопка исправления (null — нечего чинить командой)
        public final List<String> fixCmds = new ArrayList<>();
        public final List<String> enablePkgs = new ArrayList<>();   // вернуть отключённые пакеты
        public String pkg;                            // приложение, к которому относится (для вылетов)
        Item(int level, String title, String detail) { this.level = level; this.title = title; this.detail = detail; }
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        /** {раздел, название, значение} */
        public final List<String[]> passport = new ArrayList<>();
        public String summary = "";

        public String passportText() {
            StringBuilder sb = new StringBuilder();
            String group = "";
            for (String[] r : passport) {
                if (!r[0].equals(group)) { group = r[0]; sb.append("\n== ").append(group).append(" ==\n"); }
                sb.append(r[1]).append(": ").append(r[2]).append('\n');
            }
            return sb.toString().trim();
        }
    }

    private static final long WEEK = 7L * 24 * 3600 * 1000;

    public static Result run(Context c) {
        Adb adb = Adb.get(c);
        Result res = new Result();
        Map<String, String> props = props(adb);
        passport(c, adb, props, res);
        List<Item> found = new ArrayList<>();
        system(c, adb, props, found);
        crashes(c, adb, found);
        // сначала серьёзное
        for (int lvl = BAD; lvl >= INFO; lvl--) for (Item i : found) if (i.level == lvl) res.items.add(i);
        int bad = 0, warn = 0;
        for (Item i : res.items) { if (i.level == BAD) bad++; if (i.level == WARN) warn++; }
        res.summary = bad + warn == 0 ? "✅ Неисправностей не найдено. Ниже — что проверено."
                : (bad > 0 ? "🔴 Проблем: " + bad : "") + (bad > 0 && warn > 0 ? ",  " : "") + (warn > 0 ? "🔶 Внимание: " + warn : "")
                + ". Нажми на пункт — объясню и предложу исправление.";
        return res;
    }

    // ============================================================ системные проверки

    private static void system(Context c, Adb adb, Map<String, String> p, List<Item> out) {
        // --- дата и время: из-за них ошибки сертификатов, Google Play, YouTube
        long net = netTime();
        long diff = net > 0 ? Math.abs(System.currentTimeMillis() - net) : 0;
        String autoTime = sh(adb, "settings get global auto_time").trim();
        if (net > 0 && diff > 5 * 60_000L) {
            Item i = new Item(BAD, "Неверные дата и время (расхождение " + human(diff) + ")",
                    "Из-за этого не открываются сайты и приложения: «ошибка сертификата», Google Play DF-DFERH-01, YouTube и онлайн-кино. "
                            + "Исправление включит автоматическое время из интернета.");
            i.fixLabel = "Включить автоматическое время";
            i.fixCmds.add("settings put global auto_time 1");
            i.fixCmds.add("settings put global auto_time_zone 1");
            out.add(i);
        } else if ("0".equals(autoTime)) {
            Item i = new Item(WARN, "Автоматическое время выключено",
                    "Сейчас время верное, но может сбиться после отключения света — и тогда перестанут работать приложения.");
            i.fixLabel = "Включить автоматическое время";
            i.fixCmds.add("settings put global auto_time 1");
            out.add(i);
        } else out.add(new Item(OK, "Дата и время верные", net > 0 ? "Сверено с интернетом." : "Автоматическое время включено."));

        // --- отключённые важные службы
        Map<String, Pkgs.P> pk = Pkgs.map(c);
        String[][] vital = {
                {"com.google.android.gms", "Сервисы Google Play", "без них не работают Google Play, YouTube, вход в аккаунт и многие приложения"},
                {"com.google.android.gsf", "Google Services Framework", "без него Google Play выдаёт ошибки"},
                {"com.android.vending", "Google Play Маркет", "не обновляются приложения"},
                {"com.google.android.webview", "Android System WebView", "вылетают приложения, которые показывают веб-страницы (вход, оплата, онлайн-кино)"},
                {"com.android.webview", "Android System WebView", "вылетают приложения, которые показывают веб-страницы"},
                {"com.android.providers.downloads", "Диспетчер загрузок", "не скачиваются приложения и обновления"},
                {"com.android.packageinstaller", "Установщик пакетов", "не устанавливаются APK"},
                {"com.google.android.packageinstaller", "Установщик пакетов", "не устанавливаются APK"},
        };
        for (String[] v : vital) {
            Pkgs.P s = pk.get(v[0]);
            if (s == null || !s.installed || s.enabled) continue;
            Item i = new Item(BAD, v[1] + " отключено", "Из-за этого " + v[2] + ".");
            i.fixLabel = "Включить обратно";
            i.enablePkgs.add(v[0]);
            out.add(i);
        }

        // --- опасные настройки разработчика
        if ("1".equals(sh(adb, "settings get global always_finish_activities").trim())) {
            Item i = new Item(BAD, "Включено «Не сохранять действия»",
                    "Настройка разработчика: приложения теряют состояние при каждом переходе, вылетают и перезапускаются.");
            i.fixLabel = "Выключить";
            i.fixCmds.add("settings put global always_finish_activities 0");
            out.add(i);
        }

        // --- место на диске
        long[] df = df(adb, "/data");
        if (df != null) {
            long freeMb = df[1] / 1024, totalMb = df[0] / 1024;
            int pct = (int) (100 * df[1] / Math.max(1, df[0]));
            if (freeMb < 300 || pct < 5) {
                Item i = new Item(BAD, "Почти нет места: свободно " + freeMb + " МБ из " + totalMb + " МБ",
                        "Приложения не обновляются и вылетают, ТВ тормозит. Очистка кэша освободит место без потери данных.");
                i.fixLabel = "Очистить кэш";
                i.fixCmds.add("pm trim-caches 999G");
                out.add(i);
            } else if (freeMb < 800 || pct < 12) {
                Item i = new Item(WARN, "Мало места: свободно " + freeMb + " МБ из " + totalMb + " МБ", "Стоит очистить кэш и удалить ненужное.");
                i.fixLabel = "Очистить кэш";
                i.fixCmds.add("pm trim-caches 999G");
                out.add(i);
            } else out.add(new Item(OK, "Место на диске: свободно " + freeMb + " МБ из " + totalMb + " МБ", ""));
        }

        // --- оперативная память
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int memPct = (int) (100 * mi.availMem / Math.max(1, mi.totalMem));
        if (memPct < 15) {
            Item i = new Item(WARN, "Мало оперативной памяти: свободно " + memPct + "%",
                    "Отсюда зависания и «приложение не отвечает». Освободить сейчас: закрыть фоновые. Надолго: Очистка + лёгкий главный экран.");
            i.fixLabel = "Освободить память";
            i.fixCmds.add("am kill-all");
            out.add(i);
        } else out.add(new Item(OK, "Оперативная память: свободно " + memPct + "%", ""));

        // --- температура
        int maxT = 0; String maxZone = "";
        String th = sh(adb, "for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat $z/type 2>/dev/null)=$(cat $z/temp 2>/dev/null)\"; done");
        for (String line : th.split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq < 0) continue;
            try {
                int t = Integer.parseInt(line.substring(eq + 1).trim());
                if (t > 1000) t /= 1000;
                if (t > 0 && t < 150 && t > maxT) { maxT = t; maxZone = line.substring(0, eq); }
            } catch (Throwable ignored) { }
        }
        if (maxT >= 90) out.add(new Item(BAD, "Перегрев процессора: " + maxT + "°C",
                "Из-за перегрева ТВ тормозит и сам перезагружается. Проверь, что вентиляционные отверстия сзади не закрыты и ТВ не стоит в нише. Если греется в покое — нужна чистка/термопаста (сервис)."));
        else if (maxT >= 78) out.add(new Item(WARN, "Процессор горячий: " + maxT + "°C",
                "Пока терпимо, но под нагрузкой может тормозить. Проверь вентиляцию ТВ."));
        else if (maxT > 0) out.add(new Item(OK, "Температура процессора: " + maxT + "°C", maxZone.isEmpty() ? "" : "датчик: " + maxZone));

        // --- износ флеш-памяти (eMMC)
        String life = sh(adb, "cat /sys/block/mmcblk0/device/life_time 2>/dev/null").trim();
        String eol = sh(adb, "cat /sys/block/mmcblk0/device/pre_eol_info 2>/dev/null").trim();
        int wear = 0;
        for (String part : life.split("\\s+")) try { wear = Math.max(wear, Integer.decode(part)); } catch (Throwable ignored) { }
        int eolV = 0;
        try { if (!eol.isEmpty()) eolV = Integer.decode(eol); } catch (Throwable ignored) { }
        if (wear > 0 || eolV > 0) {
            String wearTxt = wear >= 0x0B ? "ресурс исчерпан" : (wear - 1) * 10 + "–" + wear * 10 + "%";
            if (wear >= 0x0A || eolV >= 3) out.add(new Item(BAD, "Флеш-память изношена (" + wearTxt + ")",
                    "Это железо: память скоро начнёт сбоить — зависания на логотипе, слетающие настройки, ошибки записи. Сохрани «Паспорт ТВ» — понадобится для ремонта/перепрошивки."));
            else if (wear >= 0x08 || eolV == 2) out.add(new Item(WARN, "Флеш-память заметно изношена (" + wearTxt + ")",
                    "Пока работает, но ресурс подходит к концу. Меньше ставь/удаляй приложений, не забивай память под завязку."));
            else out.add(new Item(OK, "Флеш-память: износ " + wearTxt, "Состояние нормальное."));
        }

        // --- сеть
        String wifi = sh(adb, "dumpsys wifi | grep -m1 'mWifiInfo'");
        Matcher rs = Pattern.compile("RSSI: (-?\\d+)").matcher(wifi);
        Matcher ls = Pattern.compile("Link speed: (\\d+)").matcher(wifi);
        if (rs.find()) {
            int rssi = Integer.parseInt(rs.group(1));
            String speed = ls.find() ? ", скорость связи " + ls.group(1) + " Мбит/с" : "";
            if (rssi <= -80) out.add(new Item(BAD, "Очень слабый Wi-Fi (" + rssi + " дБм" + speed + ")",
                    "Видео тормозит и буферизуется. Поставь роутер ближе, используй 5 ГГц или подключи ТВ кабелем."));
            else if (rssi <= -70) out.add(new Item(WARN, "Слабый Wi-Fi (" + rssi + " дБм" + speed + ")",
                    "Для 4K и YouTube может не хватать. Лучше кабель или роутер поближе."));
            else out.add(new Item(OK, "Wi-Fi: " + rssi + " дБм" + speed, ""));
        }
        boolean dns = resolves("google.com") || resolves("ya.ru");
        boolean inet = http("https://ya.ru/") || http("https://www.google.com/generate_204");
        if (!dns) {
            Item i = new Item(BAD, "Не работает DNS — ТВ не находит сайты",
                    "Интернет может быть, но адреса не определяются. Если включена DNS-блокировка рекламы — её сервер может быть недоступен.");
            i.fixLabel = "Выключить DNS-блокировку";
            i.fixCmds.add("settings put global private_dns_mode off");
            out.add(i);
        } else if (!inet) out.add(new Item(BAD, "Нет интернета", "Wi-Fi подключён, но сайты не открываются. Перезагрузи роутер; проверь, оплачен ли интернет."));
        else {
            boolean play = http("https://play.googleapis.com/generate_204") || http("https://android.clients.google.com/generate_204");
            boolean rustore = http("https://www.rustore.ru/");
            out.add(new Item(play ? OK : WARN, play ? "Интернет и сервисы Google доступны" : "Сервисы Google не отвечают",
                    play ? "" : "Google Play может не загружать приложения. Ставь через RuStore" + (rustore ? " — он доступен." : ".")));
        }

        // --- перезагрузки и аварии ядра за неделю
        long uptimeMs = 0;
        try { uptimeMs = (long) (Double.parseDouble(sh(adb, "cat /proc/uptime").trim().split("\\s+")[0]) * 1000); } catch (Throwable ignored) { }
        String reason = first(p, "sys.boot.reason", "ro.boot.bootreason", "persist.sys.boot.reason", "ro.boot.reboot_reason");
        int panics = countTag(adb, "SYSTEM_LAST_KMSG");
        int watchdog = countTag(adb, "system_server_watchdog");
        int sysCrash = countTag(adb, "system_server_crash");
        if (panics > 0 || watchdog > 0 || sysCrash > 0) {
            StringBuilder d = new StringBuilder("За неделю: ");
            if (panics > 0) d.append("аварий ядра — ").append(panics).append("; ");
            if (watchdog > 0) d.append("зависаний системы — ").append(watchdog).append("; ");
            if (sysCrash > 0) d.append("падений системы — ").append(sysCrash).append("; ");
            d.append("\nОбычные причины: нехватка памяти, перегрев, сбой прошивки. Если повторяется на «чистом» ТВ — питание или флеш-память (железо).");
            if (!reason.isEmpty()) d.append("\nПричина последнего запуска: ").append(reason);
            out.add(new Item(panics > 0 ? BAD : WARN, "ТВ сам перезагружался или зависал", d.toString()));
        } else out.add(new Item(OK, "Аварийных перезагрузок нет",
                "Работает без перезагрузки: " + human(uptimeMs) + (reason.isEmpty() ? "" : ". Причина последнего запуска: " + reason)));
        if (uptimeMs > 14L * 24 * 3600 * 1000) {
            Item i = new Item(INFO, "ТВ не перезагружался " + human(uptimeMs), "Раз в неделю-две полезно перезагрузить — очищается память и временные сбои.");
            i.fixLabel = "Перезагрузить ТВ";
            i.fixCmds.add("reboot");
            out.add(i);
        }
        if (countTag(adb, "SYSTEM_FSCK") > 0)
            out.add(new Item(WARN, "При загрузке находились ошибки файловой системы",
                    "Система их исправила, но это бывает после выключения из розетки на ходу или при износе флеш-памяти."));
    }

    // ============================================================ вылеты и зависания приложений

    private static final String[] CRASH_TAGS = {
            "data_app_crash", "system_app_crash", "data_app_anr", "system_app_anr",
            "data_app_native_crash", "system_app_native_crash", "SYSTEM_TOMBSTONE"
    };
    private static final Pattern HEAD = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) (\\S+) \\(");

    private static final class Crash { String proc, tag, error, sample; int n; long last; }

    private static void crashes(Context c, Adb adb, List<Item> out) {
        Map<String, Crash> by = new LinkedHashMap<>();
        long since = System.currentTimeMillis() - WEEK;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        for (String tag : CRASH_TAGS) {
            String txt = sh(adb, "dumpsys dropbox --print " + tag + " 2>/dev/null | head -c 300000");
            for (String entry : txt.split("\n={20,}\n")) {
                String[] lines = entry.trim().split("\n");
                if (lines.length < 2) continue;
                Matcher h = HEAD.matcher(lines[0]);
                if (!h.find()) continue;
                long t;
                try { t = f.parse(h.group(1)).getTime(); } catch (Throwable e) { continue; }
                if (t < since) continue;
                String proc = "", error = "";
                for (int k = 1; k < lines.length; k++) {
                    String l = lines[k].trim();
                    if (proc.isEmpty() && l.startsWith("Process: ")) proc = l.substring(9).trim();
                    else if (proc.isEmpty() && l.contains(">>> ") && l.contains(" <<<")) proc = l.substring(l.indexOf(">>> ") + 4, l.indexOf(" <<<")).trim();
                    if (error.isEmpty() && l.startsWith("Subject: ")) error = l.substring(9).trim();
                    if (error.isEmpty() && (l.startsWith("signal ") || l.startsWith("Abort message:"))) error = l;
                    if (error.isEmpty() && k > 2 && l.matches("^[a-z][\\w.$]*(Exception|Error)(:.*)?$")) error = l;
                    if (error.isEmpty() && k > 2 && l.startsWith("Caused by: ")) error = l.substring(11);
                }
                if (proc.isEmpty()) continue;
                if (proc.contains(":")) proc = proc.substring(0, proc.indexOf(':'));   // com.app:service → com.app
                boolean anr = tag.contains("anr");
                boolean nat = tag.contains("native") || tag.equals("SYSTEM_TOMBSTONE");
                String kind = anr ? "anr" : nat ? "native" : "crash";
                Crash cr = by.get(kind + "|" + proc);
                if (cr == null) { cr = new Crash(); cr.proc = proc; cr.tag = kind; by.put(kind + "|" + proc, cr); }
                cr.n++;
                if (t >= cr.last) { cr.last = t; cr.error = error; cr.sample = entry.length() > 4000 ? entry.substring(0, 4000) : entry; }
            }
        }
        if (by.isEmpty()) { out.add(new Item(OK, "Вылетов и зависаний приложений за неделю нет", "")); return; }

        Map<String, Pkgs.P> pk = Pkgs.map(c);
        Set<String> disabled = Profile.disabled(c);
        SimpleDateFormat shortF = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
        for (Crash cr : by.values()) {
            Pkgs.P app = pk.get(cr.proc);
            String name = app != null && app.label != null && !app.label.isEmpty() ? app.label : cr.proc;
            String what = cr.tag.equals("anr") ? "зависало (не отвечало)" : cr.tag.equals("native") ? "аварийно закрывалось (системная библиотека)" : "вылетало";
            int level = cr.n >= 3 || isSystem(cr.proc) ? BAD : WARN;
            Item i = new Item(level, name + " — " + what + " " + cr.n + " раз(а)",
                    "Последний раз: " + shortF.format(new Date(cr.last)) + "\nОшибка: " + (cr.error == null || cr.error.isEmpty() ? "—" : cut(cr.error, 160))
                            + "\n\n" + explain(cr));
            i.pkg = cr.proc;
            // приложению нужен отключённый пакет? (упоминается в тексте ошибки)
            for (String d : disabled) {
                Pkgs.P s = pk.get(d);
                if (s != null && s.installed && !s.enabled && cr.sample != null && cr.sample.contains(d)) i.enablePkgs.add(d);
            }
            if (!i.enablePkgs.isEmpty()) {
                i.detail += "\n\n⚠ В ошибке упоминается отключённый пакет: " + android.text.TextUtils.join(", ", i.enablePkgs)
                        + ". Скорее всего, приложение падает из-за этого — верни его.";
                i.fixLabel = "Вернуть нужный пакет";
                i.level = BAD;
            }
            out.add(i);
        }
    }

    private static boolean isSystem(String p) {
        return p.equals("system_server") || p.startsWith("com.android.systemui") || p.equals("com.google.android.gms")
                || p.startsWith("com.google.android.tvlauncher") || p.startsWith("com.android.tv.settings");
    }

    /** Понятное объяснение по тексту ошибки. */
    static String explain(Crash cr) {
        String e = ((cr.error == null ? "" : cr.error) + " " + (cr.sample == null ? "" : cr.sample)).toLowerCase(Locale.ROOT);
        if (cr.tag.equals("anr")) {
            if (e.contains("input dispatching")) return "Что это: приложение зависло и не реагировало на пульт. Обычно — не хватает памяти или процессор занят.\nЧто делать: закрыть лишнее (Освободить память), выключить анимации, перезапустить приложение.";
            if (e.contains("broadcast") || e.contains("service")) return "Что это: фоновая служба приложения зависла.\nЧто делать: перезапустить приложение; если повторяется — запретить ему фон или сбросить данные.";
            return "Что это: приложение зависло.\nЧто делать: перезапустить приложение, освободить память, перезагрузить ТВ.";
        }
        if (e.contains("outofmemory") || e.contains("low on memory")) return "Что это: приложению не хватило оперативной памяти.\nЧто делать: Очистка (отключить лишнее), запрет фона другим приложениям, лёгкий главный экран.";
        if (e.contains("sslhandshake") || e.contains("certpath") || e.contains("trust anchor") || e.contains("certificate"))
            return "Что это: ошибка защищённого соединения. Почти всегда — неверные дата/время.\nЧто делать: проверь пункт «Дата и время» выше.";
        if (e.contains("unknownhost") || e.contains("sockettimeout") || e.contains("connectexception") || e.contains("unable to resolve host"))
            return "Что это: приложение не достучалось до своего сервера (нет сети или сервис заблокирован).\nЧто делать: проверь интернет; для заблокированных сервисов — обход.";
        if (e.contains("sqlite") || e.contains("database disk image is malformed") || e.contains("corrupt"))
            return "Что это: повреждены сохранённые данные приложения.\nЧто делать: «Сбросить данные» — приложение станет как новое (может понадобиться снова войти в аккаунт).";
        if (e.contains("securityexception") || e.contains("permission denial"))
            return "Что это: приложению не хватает разрешения.\nЧто делать: открой настройки приложения → Разрешения, или переустанови его.";
        if (e.contains("activitynotfound") || e.contains("namenotfound") || e.contains("no activity found") || e.contains("unable to find"))
            return "Что это: приложение обращается к другому приложению или службе, которой нет или она отключена.\nЧто делать: если недавно что-то отключал — верни (кнопка ниже или «Вернуть всё»).";
        if (e.contains("webview") || e.contains("chromium"))
            return "Что это: сбой встроенного браузера (WebView), которым пользуется приложение.\nЧто делать: обнови «Android System WebView» и само приложение.";
        if (e.contains("unsatisfiedlink") || e.contains("dlopen failed"))
            return "Что это: версия приложения не подходит к процессору/системе ТВ.\nЧто делать: поставь другую сборку (для своего процессора — см. «Паспорт ТВ») или более старую версию.";
        if (e.contains("deadsystem") || e.contains("deadobject"))
            return "Что это: сбой системной службы ТВ, приложение пострадало вместе с ней.\nЧто делать: перезагрузи ТВ.";
        if (cr.tag.equals("native") || e.contains("sigsegv") || e.contains("sigabrt") || e.contains("signal "))
            return "Что это: авария во встроенной библиотеке — часто видеодекодер или драйвер ТВ.\nЧто делать: обнови приложение; если падают разные плееры — обнови прошивку ТВ или смени качество видео.";
        return "Что это: ошибка в самом приложении.\nЧто делать: обнови приложение; если не помогло — «Сбросить данные» или переустанови.";
    }

    // ============================================================ паспорт ТВ

    private static void passport(Context c, Adb adb, Map<String, String> p, Result r) {
        List<String[]> o = r.passport;
        add(o, "Устройство", "Производитель", first(p, "ro.product.manufacturer"));
        add(o, "Устройство", "Бренд", first(p, "ro.product.brand"));
        add(o, "Устройство", "Модель", first(p, "ro.product.model"));
        add(o, "Устройство", "Кодовое имя (device)", first(p, "ro.product.device"));
        add(o, "Устройство", "Имя продукта", first(p, "ro.product.name"));
        add(o, "Устройство", "Плата (board)", first(p, "ro.product.board", "ro.board.platform"));
        add(o, "Устройство", "Регион", first(p, "ro.product.locale", "persist.sys.country", "ro.product.locale.region"));

        String cpu = sh(adb, "cat /proc/cpuinfo");
        int cores = 0;
        for (String l : cpu.split("\n")) if (l.startsWith("processor")) cores++;
        add(o, "Процессор", "Платформа (SoC)", first(p, "ro.board.platform", "ro.hardware", "ro.boot.hardware"));
        add(o, "Процессор", "Hardware", grab(cpu, "(?m)^Hardware\\s*:\\s*(.+)$"));
        add(o, "Процессор", "Ядро", grab(cpu, "(?m)^(?:model name|Processor)\\s*:\\s*(.+)$"));
        if (cores > 0) add(o, "Процессор", "Ядер", String.valueOf(cores));
        String mhz = sh(adb, "cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null").trim();
        try { add(o, "Процессор", "Частота", (Long.parseLong(mhz) / 1000) + " МГц"); } catch (Throwable ignored) { }
        add(o, "Процессор", "Архитектуры", first(p, "ro.product.cpu.abilist", "ro.product.cpu.abi"));
        add(o, "Процессор", "Графика", grab(sh(adb, "dumpsys SurfaceFlinger | grep -m1 GLES"), "GLES:\\s*(.+)"));

        String mem = sh(adb, "cat /proc/meminfo | head -1");
        try { add(o, "Память", "Оперативная", (Long.parseLong(grab(mem, "(\\d+)")) / 1024) + " МБ"); } catch (Throwable ignored) { }
        long[] df = df(adb, "/data");
        if (df != null) add(o, "Память", "Встроенная (данные)", (df[0] / 1024) + " МБ, свободно " + (df[1] / 1024) + " МБ");
        String mmc = sh(adb, "d=/sys/block/mmcblk0/device; for f in name manfid oemid date type life_time pre_eol_info; do echo \"$f=$(cat $d/$f 2>/dev/null)\"; done");
        Map<String, String> mm = kv(mmc);
        if (!get(mm, "name").isEmpty()) {
            add(o, "Память", "Флеш-память (eMMC)", mm.get("name") + "  · производитель " + vendor(mm.get("manfid")));
            add(o, "Память", "Дата выпуска eMMC", get(mm, "date"));
            add(o, "Память", "Износ eMMC (A/B)", get(mm, "life_time") + "  · резерв: " + get(mm, "pre_eol_info"));
        }
        // объём флеш-памяти: целый диск из /proc/partitions (mmcblk0 / sda), размер в КБ
        long diskKb = 0;
        for (String l : sh(adb, "cat /proc/partitions 2>/dev/null").split("\n")) {
            String[] f = l.trim().split("\\s+");
            if (f.length == 4 && f[3].matches("mmcblk\\d+|sd[a-z]")) try { diskKb = Math.max(diskKb, Long.parseLong(f[2])); } catch (Throwable ignored) { }
        }
        if (diskKb == 0) try { diskKb = Long.parseLong(sh(adb, "cat /sys/block/mmcblk0/size 2>/dev/null").trim()) / 2; } catch (Throwable ignored) { }
        if (diskKb == 0 && android.os.Build.VERSION.SDK_INT >= 26) try {   // Android 8+: общий объём накопителя от системы
            android.app.usage.StorageStatsManager ssm = (android.app.usage.StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
            diskKb = ssm.getTotalBytes(android.os.storage.StorageManager.UUID_DEFAULT) / 1024;
        } catch (Throwable ignored) { }
        if (diskKb > 0) {
            double gb = diskKb * 1024.0 / 1_000_000_000.0;
            int nominal = 4;
            while (nominal < gb * 0.9 && nominal < 1024) nominal *= 2;   // 7.3 → 8 ГБ, 14.6 → 16 ГБ
            add(o, "Память", "Объём флеш-памяти", nominal + " ГБ");
        }

        add(o, "Экран и матрица", "Разрешение интерфейса", grab(sh(adb, "wm size"), "(?:Physical|Override) size:\\s*(\\S+)"));
        add(o, "Экран и матрица", "Плотность", grab(sh(adb, "wm density"), "density:\\s*(\\d+)"));
        String disp = sh(adb, "dumpsys display | grep -m1 DisplayDeviceInfo");
        add(o, "Экран и матрица", "Частота", grab(disp, "fps=([\\d.]+)") + " Гц");
        // матрица: MStar / MediaTek хранят настройки панели в tvconfig
        String panel = sh(adb, "grep -rhiE -m2 'm_pPanelName|PanelName|panel_name|PANEL_NAME' /tvconfig /vendor/tvconfig /tvcustomer /vendor/tvcustomer /product/tvconfig /vendor/etc/tvconfig 2>/dev/null | head -4");
        String panelProps = "";
        for (Map.Entry<String, String> e : p.entrySet())
            if (e.getKey().toLowerCase(Locale.ROOT).contains("panel") && !e.getValue().isEmpty())
                panelProps += e.getKey() + "=" + e.getValue() + "\n";
        String panelFiles = sh(adb, "ls /tvconfig/config/panel /vendor/tvconfig/config/panel 2>/dev/null | head -12").trim();
        String panelName = cleanPanel(panel);
        // открыть активный файл панели и достать из него код самой матрицы (например LVB430QUF7CPSH1T0X)
        String panelCode = "";
        Matcher ap = Pattern.compile("(?i)m_pPanelName\\s*=\\s*\"([^\"]+\\.ini)\"").matcher(panel);
        if (ap.find()) {
            String path = ap.group(1);
            String inner = "";
            for (String cand : new String[]{path, "/vendor/tvconfig" + path, "/tvconfig" + path, path.replace("/config/", "/vendor/tvconfig/config/")})
                if (inner.isEmpty()) inner = sh(adb, "grep -m1 -iE 'm_pPanelName|PanelName' '" + cand + "' 2>/dev/null");
            Matcher pc = Pattern.compile("(?i)PanelName\\s*=\\s*\"?([A-Za-z0-9_.\\-]+)").matcher(inner);
            if (pc.find() && !pc.group(1).toLowerCase(Locale.ROOT).endsWith(".ini")) panelCode = pc.group(1);
        }
        if (!panelCode.isEmpty()) panelName = panelCode + (panelName.isEmpty() ? "" : "  (конфиг " + panelName + ")");
        // Xiaomi пишет матрицу в загрузочные параметры: производитель, диагональ, разрешение
        String pv = first(p, "ro.boot.mi.panel_vendor", "ro.boot.panel_vendor");
        String ps = first(p, "ro.boot.mi.panel_size", "ro.boot.panel_size");
        String pr = first(p, "ro.boot.mi.panel_resolution", "ro.boot.panel_resolution").replace("x", "×");
        String pretty = (pv + (ps.isEmpty() ? "" : " · " + ps + "\"") + (pr.isEmpty() ? "" : " · " + pr)).replaceAll("^ · ", "");
        if (!panelName.isEmpty()) add(o, "Экран и матрица", "Матрица (панель)", panelName + (pretty.isEmpty() ? "" : "  (" + pretty + ")"));
        else if (!pretty.isEmpty()) {
            add(o, "Экран и матрица", "Матрица (панель)", pretty);
            add(o, "Экран и матрица", "Точная модель матрицы", "система не сообщает — номер на наклейке на задней стороне матрицы (видно при разборе)");
        } else add(o, "Экран и матрица", "Матрица (панель)", !panelProps.isEmpty() ? panelProps.trim()
                : "система не сообщает — смотри наклейку на задней стороне матрицы");
        if (!pr.isEmpty()) add(o, "Экран и матрица", "Родное разрешение матрицы", pr + (pr.startsWith("1366") ? " (HD)" : pr.startsWith("1920") ? " (Full HD)" : pr.startsWith("3840") ? " (4K)" : ""));
        String svcPanel = ServiceMenu.savedPanel(c);
        if (!svcPanel.isEmpty()) add(o, "Экран и матрица", "Матрица (из сервисного меню)", svcPanel);
        if (!panel.trim().isEmpty() && !panel.trim().equals(panelName)) add(o, "Экран и матрица", "Запись о панели", cut(panel.trim(), 300));
        if (!panelFiles.isEmpty()) add(o, "Экран и матрица", "Файлы панелей в прошивке", panelFiles.replace("\n", ", "));

        add(o, "Прошивка", "Android", first(p, "ro.build.version.release") + " (API " + first(p, "ro.build.version.sdk") + ")");
        add(o, "Прошивка", "Версия прошивки", first(p, "ro.build.display.id"));
        add(o, "Прошивка", "Инкрементальная", first(p, "ro.build.version.incremental"));
        add(o, "Прошивка", "Дата сборки", first(p, "ro.build.date"));
        add(o, "Прошивка", "Патч безопасности", first(p, "ro.build.version.security_patch"));
        add(o, "Прошивка", "Отпечаток (fingerprint)", first(p, "ro.build.fingerprint"));
        String bl = first(p, "ro.bootloader", "ro.boot.bootloader");
        if (!bl.matches("(?i)|unknown|0+|123456789|none")) add(o, "Прошивка", "Загрузчик", bl);
        add(o, "Прошивка", "Тип сборки", first(p, "ro.build.type") + " / " + first(p, "ro.build.tags"));

        add(o, "Сеть", "Wi-Fi драйвер", sh(adb, "basename $(readlink /sys/class/net/wlan0/device/driver) 2>/dev/null").trim());
        add(o, "Сеть", "Wi-Fi чип (vendor:device)", (sh(adb, "cat /sys/class/net/wlan0/device/vendor 2>/dev/null").trim() + ":"
                + sh(adb, "cat /sys/class/net/wlan0/device/device 2>/dev/null").trim()).replaceAll("^:$", ""));
        add(o, "Сеть", "Ethernet", sh(adb, "ls /sys/class/net 2>/dev/null").contains("eth0") ? "есть (eth0)" : "");

        // сводка одной строкой — сверху паспорта
        StringBuilder sum = new StringBuilder();
        String brand = first(p, "ro.product.brand"), model = first(p, "ro.product.model"), dev = first(p, "ro.product.device");
        sum.append(brand).append(' ').append(model).append(dev.isEmpty() ? "" : " (" + dev + ")");
        String soc = first(p, "ro.board.platform", "ro.hardware");
        if (!soc.isEmpty()) sum.append("\nПроцессор: ").append(soc).append(cores > 0 ? ", " + cores + " ядра" : "")
                .append(mhz.matches("\\d+") ? " × " + (Long.parseLong(mhz) / 1000) + " МГц" : "");
        String ram = valueOf(o, "Оперативная");
        if (!ram.isEmpty()) sum.append("\nПамять: ").append(ram).append(" ОЗУ");
        String flash = valueOf(o, "Объём флеш-памяти");
        if (!flash.isEmpty()) sum.append(", ").append(flash).append(" флеш");
        String mat = valueOf(o, "Матрица (из сервисного меню)");
        if (mat.isEmpty()) mat = valueOf(o, "Матрица (панель)");
        if (!mat.isEmpty() && !mat.startsWith("система")) sum.append("\nМатрица: ").append(mat);
        String board = ServiceMenu.savedValue(c, "Board");
        if (!board.isEmpty()) sum.append("\nПлата: ").append(board);
        String wv = ServiceMenu.savedValue(c, "Widevine Key");
        if (!wv.isEmpty()) sum.append("\nЗащита видео (Widevine): ").append(wv.equalsIgnoreCase("true") ? "есть — HD в онлайн-кино работает" : "НЕТ — онлайн-кино будет в плохом качестве или с ошибкой DRM");
        sum.append("\nПрошивка: Android ").append(first(p, "ro.build.version.release")).append(", ").append(first(p, "ro.build.display.id"));
        o.add(0, new String[]{"Кратко", "Этот телевизор", sum.toString()});

        // считанное из сервисного меню
        for (String[] f : ServiceMenu.savedFacts(c)) add(o, "Сервисное меню", f[0].isEmpty() ? "—" : f[0], f[1]);

        // всё, что пригодится для поиска прошивки на форумах
        Pattern keys = Pattern.compile("(?i)(mitv|xiaomi|mstar|mtk|mediatek|amlogic|realtek|rtk|panel|board|chip|soc|project|customer|odm|oem|model|platform|hardware|tv\\.|region|sku)");
        int n = 0;
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (n >= 60) break;
            if (e.getValue().isEmpty() || e.getKey().contains("serial") || e.getKey().contains("mac") || e.getKey().contains("imei")) continue;
            if (keys.matcher(e.getKey()).find()) { add(o, "Системные коды (для прошивки)", e.getKey(), cut(e.getValue(), 120)); n++; }
        }
    }

    private static String valueOf(List<String[]> o, String key) {
        for (String[] r : o) if (r[1].equals(key)) return r[2];
        return "";
    }

    private static String cleanPanel(String raw) {
        Matcher m = Pattern.compile("(?i)(?:m_pPanelName|PanelName|panel_name|PANEL_NAME)\\s*[=:]\\s*\"?([^\";\\n]+)").matcher(raw);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        if (v.contains("/")) v = v.substring(v.lastIndexOf('/') + 1);
        return v.replaceAll("(?i)\\.ini$", "");
    }

    private static String vendor(String id) {
        if (id == null) return "?";
        Map<String, String> v = new HashMap<>();
        v.put("0x000015", "Samsung"); v.put("0x000090", "SK Hynix"); v.put("0x000013", "Micron"); v.put("0x0000fe", "Micron");
        v.put("0x000011", "Toshiba/Kioxia"); v.put("0x000045", "SanDisk"); v.put("0x000088", "Foresee (Longsys)");
        v.put("0x0000d6", "Foresee (Longsys)"); v.put("0x00009b", "YMTC"); v.put("0x000070", "Kingston"); v.put("0x000032", "Phison");
        v.put("0x0000f4", "Biwin"); v.put("0x00003c", "Netsol/SK");
        String k = id.trim().toLowerCase(Locale.ROOT);
        return v.containsKey(k) ? v.get(k) + " (" + k + ")" : k;
    }

    // ============================================================ помощники

    private static Map<String, String> props(Adb adb) {
        Map<String, String> m = new java.util.TreeMap<>();
        Matcher x = Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$", Pattern.MULTILINE).matcher(sh(adb, "getprop"));
        while (x.find()) m.put(x.group(1), x.group(2));
        return m;
    }

    private static String first(Map<String, String> p, String... keys) {
        for (String k : keys) { String v = p.get(k); if (v != null && !v.trim().isEmpty()) return v.trim(); }
        return "";
    }

    private static void add(List<String[]> o, String g, String k, String v) {
        if (v == null) return;
        v = v.trim();
        if (v.isEmpty() || v.equals("Гц") || v.equals("(API )") || v.equals("/") || v.equals(" / ")) return;
        o.add(new String[]{g, k, v});
    }

    private static String grab(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s == null ? "" : s);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String get(Map<String, String> m, String k) { String v = m.get(k); return v == null ? "" : v; }

    private static Map<String, String> kv(String s) {
        Map<String, String> m = new HashMap<>();
        for (String l : s.split("\n")) { int e = l.indexOf('='); if (e > 0) m.put(l.substring(0, e).trim(), l.substring(e + 1).trim()); }
        return m;
    }

    /** {всего КБ, свободно КБ} */
    private static long[] df(Adb adb, String path) {
        String[] lines = sh(adb, "df -k " + path + " 2>/dev/null").trim().split("\n");
        if (lines.length < 2) return null;
        String[] f = lines[lines.length - 1].trim().split("\\s+");
        try { return new long[]{Long.parseLong(f[1]), Long.parseLong(f[3])}; } catch (Throwable t) { return null; }
    }

    private static int countTag(Adb adb, String tag) {
        String out = sh(adb, "dumpsys dropbox " + tag + " 2>/dev/null");
        long since = System.currentTimeMillis() - WEEK;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        int n = 0;
        Matcher m = Pattern.compile("(?m)^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) " + Pattern.quote(tag) + " ").matcher(out);
        while (m.find()) try { if (f.parse(m.group(1)).getTime() >= since) n++; } catch (Throwable ignored) { }
        return n;
    }

    private static String sh(Adb adb, String cmd) { return adb.execSync(cmd, false).out; }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }

    static String human(long ms) {
        long m = ms / 60_000;
        if (m < 60) return m + " мин";
        long h = m / 60;
        if (h < 48) return h + " ч";
        return (h / 24) + " дн";
    }

    /** Время из интернета (заголовок Date), 0 — не удалось. */
    private static long netTime() {
        for (String u : new String[]{"https://www.google.com/generate_204", "https://ya.ru/"}) {
            java.net.HttpURLConnection h = null;
            try {
                h = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                h.setConnectTimeout(6000);
                h.setReadTimeout(6000);
                h.setRequestMethod("HEAD");
                h.getResponseCode();
                long d = h.getDate();
                if (d > 0) return d;
            } catch (Throwable ignored) {
                // при сбитом времени HTTPS может не открыться — пробуем обычный HTTP
                try {
                    java.net.HttpURLConnection p = (java.net.HttpURLConnection) new java.net.URL("http://connectivitycheck.gstatic.com/generate_204").openConnection();
                    p.setConnectTimeout(6000);
                    p.setReadTimeout(6000);
                    p.getResponseCode();
                    long d = p.getDate();
                    p.disconnect();
                    if (d > 0) return d;
                } catch (Throwable ignored2) { }
            } finally { if (h != null) h.disconnect(); }
        }
        return 0;
    }

    private static boolean resolves(String host) {
        try { return java.net.InetAddress.getByName(host) != null; } catch (Throwable t) { return false; }
    }

    private static boolean http(String url) {
        java.net.HttpURLConnection h = null;
        try {
            h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            h.setConnectTimeout(7000);
            h.setReadTimeout(7000);
            h.setInstanceFollowRedirects(false);
            int code = h.getResponseCode();
            return code > 0 && code < 500;
        } catch (Throwable t) { return false; } finally { if (h != null) h.disconnect(); }
    }

    private Doctor() { }
}
