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
        public Bitmap shot;
        public String error;
    }

    /** Пакеты, похожие на заводское меню. */
    private static final Pattern PKG_HINT = Pattern.compile(
            "(?i)(factory|servicemenu|service\\.menu|engineer|designmenu|hiddenmenu|fmenu|aging|"
                    + "mstar|mtk|mediatek|mitv|xiaomi|tpv|philips|tvsettings|tv\\.settings|realtek|rtk|amlogic|hisense|tcl|skyworth|haier)");
    /** Активности, похожие на заводское меню. */
    private static final Pattern ACT_HINT = Pattern.compile("(?i)(factory|designmenu|design_menu|servicemenu|service_menu|engineer|hiddenmenu|hidden_menu|aging|fmenu)");
    /** Что считаем важным на экране меню. */
    private static final Pattern KEY = Pattern.compile("(?i)(panel|матриц|lcd|board|chassis|platform|model|project|customer|odm|sw ?ver|software|firmware|version|boot|mboot|build|date|hour|time|backlight|t-?con|pq|ini|serial)");

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
                if (!match || !a.exported || !seen.add(a.packageName + "/" + a.name)) continue;
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
        for (Found f : out) if (f.cls.toLowerCase(Locale.ROOT).matches(".*(factory|design).*")) sorted.add(f);
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
            // текст со всех элементов экрана
            String xml = adb.readText("uiautomator dump /data/local/tmp/tvt_ui.xml >/dev/null 2>&1; cat /data/local/tmp/tvt_ui.xml; rm -f /data/local/tmp/tvt_ui.xml");
            Matcher t = Pattern.compile("(?:text|content-desc)=\"([^\"]+)\"").matcher(xml);
            while (t.find()) {
                String s = unescape(t.group(1)).trim();
                if (!s.isEmpty() && (g.lines.isEmpty() || !g.lines.get(g.lines.size() - 1).equals(s))) g.lines.add(s);
            }
            // снимок экрана — на случай, если меню нарисовано так, что текст не читается
            byte[] png = adb.readBytes("screencap -p");
            if (png != null && png.length > 1000) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                g.shot = BitmapFactory.decodeByteArray(png, 0, png.length, o);
            }
        } finally {
            // закрыть «Назад» (ничего не меняя), потом на всякий случай остановить
            for (int i = 0; i < 4 && m.pkg.equals(Launchers.foreground(adb)); i++) { adb.execSync("input keyevent 4", false); sleep(700); }
            adb.execSync("am force-stop " + m.pkg, false);
        }
        // важное: «Название: значение» или название и значение соседними строками
        for (int i = 0; i < g.lines.size(); i++) {
            String s = g.lines.get(i);
            if (!KEY.matcher(s).find()) continue;
            int colon = Math.max(s.indexOf(':'), s.indexOf('='));
            if (colon > 0 && colon < s.length() - 1) g.facts.add(new String[]{s.substring(0, colon).trim(), s.substring(colon + 1).trim()});
            else if (i + 1 < g.lines.size() && s.length() < 40) g.facts.add(new String[]{s, g.lines.get(i + 1)});
            else g.facts.add(new String[]{"", s});
        }
        if (g.lines.isEmpty() && g.shot == null) g.error = "Меню открылось, но прочитать его не удалось — оно рисуется поверх Android. Открой его комбинацией на пульте и сфотографируй.";
        save(c, g);
        return g;
    }

    // ------------------------------------------------------------ хранение (для паспорта)

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("svcmenu", Context.MODE_PRIVATE); }

    private static void save(Context c, Grab g) {
        if (g.facts.isEmpty() && g.lines.isEmpty()) return;
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

    public static String savedAll(Context c) { return sp(c).getString("all", ""); }

    // ------------------------------------------------------------

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", " ").replace("&amp;", "&");
    }

    private static String firstLine(String s) { s = s.trim(); int n = s.indexOf('\n'); return n > 0 ? s.substring(0, n) : s; }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private ServiceMenu() { }
}
