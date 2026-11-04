package ru.chex.tvturbo;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")

/**
 * Сервисное (заводское) меню ТВ: найти среди системных приложений, открыть на пару секунд,
 * считать с экрана текст (uiautomator) и снимок, закрыть «Назад». В меню ничего не нажимается.
 */
public final class ServiceMenu {

    public static final class Found {
        public String pkg, cls, label;
        @Override public String toString() { return label; }
    }

    public static final class Grab {
        public Found menu;
        public final List<String> lines = new ArrayList<>();   // весь текст с экрана
        public final List<String[]> facts = new ArrayList<>(); // {название, значение} — важное
        public Bitmap shot;                                     // главный снимок (страница с данными)
        public String error;
    }

    /** Пакеты, похожие на заводское меню. */
    private static final Pattern PKG_HINT = Pattern.compile(
            "(?i)(factory|servicemenu|service\\.menu|engineer|designmenu|hiddenmenu|fmenu|aging|"
                    + "mstar|mtk|mediatek|mitv|xiaomi|tpv|philips|tvsettings|tv\\.settings|realtek|rtk|amlogic|hisense|tcl|skyworth|haier)");
    /** Активности, похожие на заводское меню. */
    private static final Pattern ACT_HINT = Pattern.compile("(?i)(factory|designmenu|design_menu|servicemenu|service_menu|engineer|hiddenmenu|hidden_menu|aging|fmenu)");
    /** Страницы, которые что-то делают (обновление, сброс, прогон) — не открываем никогда. */
    private static final Pattern DANGER = Pattern.compile("(?i)(update|upgrade|reset|restore|burn|write|erase|clear|wipe|shell|aging|ota|flash|install|calib|adjust|setmac|key)");
    /** Информационные пункты внутри меню, которые можно открыть. */
    private static final Pattern INFO_ITEM = Pattern.compile("(?i)^(panel ?info|ursa ?info|board ?info|system ?info|version ?info|sw ?info|information)$");
    /** Что считаем важным на экране меню. */
    private static final Pattern KEY = Pattern.compile("(?i)(panel|матриц|lcd|board|chassis|platform|model|project|customer|odm|sw ?ver|software|firmware|version|boot|mboot|build|date|hour|time|backlight|t-?con|pq|ini|serial|key|hdcp|widevine|playready|netflix|drm|ci\\+)");

    /** Поиск кандидатов. Можно в любом потоке. */
    public static List<Found> find(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Found> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<PackageInfo> all;
        try { all = pm.getInstalledPackages(PackageManager.GET_DISABLED_COMPONENTS); } catch (Throwable t) { return out; }
        for (PackageInfo p : all) {
            if (!PKG_HINT.matcher(p.packageName).find()) continue;
            PackageInfo full;
            try { full = pm.getPackageInfo(p.packageName, PackageManager.GET_ACTIVITIES | PackageManager.GET_DISABLED_COMPONENTS); }
            catch (Throwable t) { continue; }
            if (full.activities == null) continue;
            boolean pkgIsMenu = ACT_HINT.matcher(p.packageName).find();
            for (ActivityInfo a : full.activities) {
                boolean match = ACT_HINT.matcher(a.name).find() || (pkgIsMenu && a.exported);
                if (!match || !a.exported || DANGER.matcher(a.name).find() || !seen.add(a.packageName + "/" + a.name)) continue;
                Found f = new Found();
                f.pkg = a.packageName;
                f.cls = a.name;
                String app = "";
                try { app = String.valueOf(pm.getApplicationLabel(full.applicationInfo)); } catch (Throwable ignored) { }
                String shortCls = a.name.substring(a.name.lastIndexOf('.') + 1);
                f.label = (app.isEmpty() || app.equals(a.packageName) ? a.packageName : app) + " · " + shortCls;
                out.add(f);
            }
        }
        // сначала «заводские» (factory/design), потом остальные
        List<Found> sorted = new ArrayList<>();
        for (Found f : out) if (f.cls.toLowerCase(Locale.ROOT).matches(".*(design|factory).*")) sorted.add(f);
        for (Found f : out) if (!sorted.contains(f)) sorted.add(f);
        return sorted;
    }

    /** Открыть меню, считать текст и снимок, закрыть. Только в потоке Adb. */
    public static Grab grab(Context c, Adb adb, Found m) {
        Grab g = new Grab();
        g.menu = m;
        String r = adb.execSync("am start -n " + m.pkg + "/" + m.cls, true).out;
        if (r.contains("Error") || r.contains("Permission Denial") || r.contains("does not exist")) {
            g.error = "ТВ не дал открыть это меню снаружи (" + firstLine(r) + "). Попробуй другое из списка.";
            return g;
        }
        sleep(3000);
        if (!m.pkg.equals(Launchers.foreground(adb))) sleep(2000);
        try {
            Page main = read(adb);
            g.lines.addAll(main.lines);
            g.shot = main.shot;
            addFacts(g, main, false);
            // дочитать строки ниже экрана: «вниз» только двигает подсветку, ничего не меняет
            if (main.nodes.size() >= 6) {
                adb.execSync("for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do input keyevent 20; done", false);
                sleep(1200);
                Page more = read(adb);
                for (String l : more.lines) if (!g.lines.contains(l)) g.lines.add(l);
                int before = g.facts.size();
                addFacts(g, more, false);
                // убрать повторы
                List<String[]> uniq = new ArrayList<>();
                Set<String> keys = new LinkedHashSet<>();
                for (String[] f : g.facts) if (keys.add(f[0] + "\u0001" + f[1])) uniq.add(f);
                g.facts.clear();
                g.facts.addAll(uniq);
                if (more.shot != null && g.facts.size() > before) g.shot = more.shot;
            }
            // открыть только информационные пункты (Panel Info, Ursa Info…) и прочитать их
            for (Node n : main.nodes) {
                if (!INFO_ITEM.matcher(n.text.trim()).matches()) continue;
                adb.execSync("input tap " + n.cx() + " " + n.cy(), false);
                sleep(2000);
                Page sub = read(adb);
                if (sub.lines.equals(main.lines) && focusTo(adb, n)) {   // касание не сработало — пультом, с проверкой фокуса
                    adb.execSync("input keyevent 23", false);
                    sleep(2000);
                    sub = read(adb);
                }
                if (!sub.lines.isEmpty() && !sub.lines.equals(main.lines)) {
                    g.lines.add("— " + n.text + " —");
                    g.lines.addAll(sub.lines);
                    addFacts(g, sub, true);
                    if (sub.shot != null) g.shot = sub.shot;
                    adb.execSync("input keyevent 4", false);   // назад в главное меню
                    sleep(1200);
                }
            }
        } finally {
            // закрыть «Назад» (ничего не меняя), потом на всякий случай остановить
            for (int i = 0; i < 6 && m.pkg.equals(Launchers.foreground(adb)); i++) { adb.execSync("input keyevent 4", false); sleep(700); }
            adb.execSync("am force-stop " + m.pkg, false);
        }
        if (g.lines.isEmpty() && g.shot == null) g.error = "Меню открылось, но прочитать его не удалось."
                + (lastDumpError.isEmpty() ? "" : "\nОтвет системы: " + lastDumpError)
                + "\nПопробуй ещё раз — иногда меню дорисовывается дольше обычного.";
        save(c, g);
        return g;
    }

    // ------------------------------------------------------------ чтение экрана

    static final class Node {
        String text; int x1, y1, x2, y2;
        int cx() { return (x1 + x2) / 2; }
        int cy() { return (y1 + y2) / 2; }
    }

    static final class Page {
        final List<Node> nodes = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        Bitmap shot;
    }

    private static final Pattern NODE = Pattern.compile("<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");

    private static final Pattern FOCUS = Pattern.compile("<node [^>]*?focused=\"true\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");

    /** Где сейчас фокус: самый маленький сфокусированный прямоугольник {x1,y1,x2,y2} или null. */
    private static int[] focus(Adb adb) {
        String xml = dump(adb);
        Matcher m = FOCUS.matcher(xml);
        int[] best = null;
        while (m.find()) {
            int[] r = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
            if (best == null || (r[2] - r[0]) * (r[3] - r[1]) < (best[2] - best[0]) * (best[3] - best[1])) best = r;
        }
        return best;
    }

    /**
     * Довести фокус до пункта n кнопками «вверх/вниз», проверяя после каждого шага.
     * true — фокус точно на нужном пункте (только тогда можно жать OK).
     */
    private static boolean focusTo(Adb adb, Node n) {
        for (int step = 0; step < 25; step++) {
            int[] f = focus(adb);
            if (f == null) return false;
            if (n.cx() >= f[0] && n.cx() <= f[2] && n.cy() >= f[1] && n.cy() <= f[3]) return true;
            int fy = (f[1] + f[3]) / 2;
            adb.execSync("input keyevent " + (fy < n.cy() ? 20 : 19), false);   // 20 вниз, 19 вверх
            sleep(350);
        }
        return false;
    }

    /** Последний ответ системы, если экран не прочитался (для диагностики). */
    static String lastDumpError = "";

    /** XML экрана; uiautomator иногда отвечает «could not get idle state», пока меню дорисовывается — повторяем. */
    private static String dump(Adb adb) {
        String out = "";
        for (int i = 0; i < 4; i++) {
            out = adb.readText("uiautomator dump /data/local/tmp/tvt_ui.xml 2>&1; cat /data/local/tmp/tvt_ui.xml 2>/dev/null; rm -f /data/local/tmp/tvt_ui.xml");
            if (out.contains("<node")) { lastDumpError = ""; return out; }
            lastDumpError = out.trim().length() > 200 ? out.trim().substring(0, 200) : out.trim();
            sleep(1500);
        }
        return out;
    }

    private static Page read(Adb adb) {
        Page p = new Page();
        String xml = dump(adb);
        Matcher m = NODE.matcher(xml);
        while (m.find()) {
            String t = unescape(m.group(1)).trim();
            if (t.isEmpty()) continue;
            Node n = new Node();
            n.text = t;
            n.x1 = Integer.parseInt(m.group(2)); n.y1 = Integer.parseInt(m.group(3));
            n.x2 = Integer.parseInt(m.group(4)); n.y2 = Integer.parseInt(m.group(5));
            p.nodes.add(n);
            p.lines.add(t);
        }
        // снимок: сначала в файл, потом прочитать (поток напрямую на части ТВ обрывается)
        byte[] png = adb.readBytes("screencap -p /data/local/tmp/tvt_shot.png >/dev/null 2>&1; cat /data/local/tmp/tvt_shot.png; rm -f /data/local/tmp/tvt_shot.png");
        if (png == null || png.length < 1000) png = adb.readBytes("screencap -p");
        if (png != null && png.length > 1000) try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            p.shot = BitmapFactory.decodeByteArray(png, 0, png.length, o);
        } catch (Throwable ignored) { }
        return p;
    }

    /** Пары «название — значение»: элементы на одной строке экрана (одинаковая высота), слева направо. */
    private static void addFacts(Grab g, Page p, boolean infoPage) {
        List<Node> left = new ArrayList<>(p.nodes);
        while (!left.isEmpty()) {
            Node a = left.remove(0);
            List<Node> row = new ArrayList<>();
            row.add(a);
            for (int i = left.size() - 1; i >= 0; i--) {
                Node b = left.get(i);
                if (Math.abs(b.cy() - a.cy()) <= Math.max(8, (a.y2 - a.y1) / 2)) { row.add(b); left.remove(i); }
            }
            if (row.size() < 2) continue;
            java.util.Collections.sort(row, (x, y) -> x.x1 - y.x1);
            String key = row.get(0).text;
            StringBuilder val = new StringBuilder();
            for (int i = 1; i < row.size(); i++) { if (val.length() > 0) val.append(' '); val.append(row.get(i).text); }
            if (key.length() > 60 || val.length() == 0) continue;
            if (!infoPage && !KEY.matcher(key).find()) continue;   // на главной — только явно важное
            g.facts.add(new String[]{key, val.toString()});
        }
    }

    // ------------------------------------------------------------ хранение (для паспорта)

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("svcmenu", Context.MODE_PRIVATE); }

    private static void save(Context c, Grab g) {
        if (g.lines.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String[] f : g.facts) sb.append(f[0]).append('\t').append(f[1]).append('\n');
        sp(c).edit().putString("facts", sb.toString()).putString("menu", g.menu.label)
                .putString("all", android.text.TextUtils.join("\n", g.lines)).putLong("when", System.currentTimeMillis()).apply();
    }

    /** {название, значение} из последнего считывания. */
    public static List<String[]> savedFacts(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String l : sp(c).getString("facts", "").split("\n")) {
            int t = l.indexOf('\t');
            if (t >= 0) out.add(new String[]{l.substring(0, t), l.substring(t + 1)});
        }
        return out;
    }

    /** Матрица из сервисного меню (если считывалась). */
    public static String savedPanel(Context c) {
        for (String[] f : savedFacts(c))
            if ((f[0] + " " + f[1]).toLowerCase(Locale.ROOT).matches(".*(panel|матриц|lcd).*") && !f[1].trim().isEmpty()) return f[1].trim();
        return "";
    }

    /** Значение из последнего считывания по названию (например «Board»), иначе "". */
    public static String savedValue(Context c, String key) {
        for (String[] f : savedFacts(c)) if (f[0].trim().equalsIgnoreCase(key)) return f[1].trim();
        return "";
    }

    public static String savedAll(Context c) { return sp(c).getString("all", ""); }

    // ------------------------------------------------------------

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", " ").replace("&amp;", "&");
    }

    private static String firstLine(String s) { s = s.trim(); int n = s.indexOf('\n'); return n > 0 ? s.substring(0, n) : s; }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private ServiceMenu() { }
}
