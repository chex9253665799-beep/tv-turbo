package ru.chex.tvturbo;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверенные списки отключения из проекта CleanYourTV (github.com/Gathin23/cleanyourtv, MIT):
 * 433+ моделей, у каждой — что именно отключили на живом ТВ и проверили после перезагрузки.
 * Индекс моделей собирает наш GitHub (cyt-index.json), сами списки берутся из их репозитория.
 */
public final class Cyt {

    public static final String INDEX_URL = "https://github.com/" + Updater.REPO + "/releases/download/latest/cyt-index.json";
    public static final String RAW = "https://raw.githubusercontent.com/Gathin23/cleanyourtv/main/examples/";
    public static final String WEB = "https://github.com/Gathin23/cleanyourtv/tree/main/examples/";

    public static final class Model {
        public String folder, tv, brand, head;
        int score;
        @Override public String toString() { return tv; }
    }

    /** Строка списка: раздел (pkg == null) или пакет с пояснением. */
    public static final class Entry {
        public String pkg, title, note;
        public boolean ownerChoice;   // «по желанию владельца» — по умолчанию не отмечаем
    }

    public static List<Model> index() throws Exception {
        JSONArray a = new JSONArray(new String(InstallActivity.download(INDEX_URL), "UTF-8"));
        List<Model> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Model m = new Model();
            m.folder = o.optString("folder");
            m.tv = o.optString("tv");
            m.brand = o.optString("brand");
            m.head = o.optString("head");
            out.add(m);
        }
        return out;
    }

    private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); }

    /** Модели, подходящие этому ТВ, лучшие сверху (score > 0). */
    public static List<Model> match(List<Model> all) {
        String model = norm(Build.MODEL), device = Build.DEVICE == null ? "" : Build.DEVICE.toLowerCase(Locale.ROOT);
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase(Locale.ROOT);
        String brand = norm(Build.BRAND), manu = norm(Build.MANUFACTURER);
        List<Model> out = new ArrayList<>();
        for (Model m : all) {
            String f = m.folder.toLowerCase(Locale.ROOT), text = (m.tv + " " + m.head).toLowerCase(Locale.ROOT);
            int s = 0;
            if (model.length() >= 4 && (f.endsWith(model) || f.contains("-" + model))) s += 6;           // xiaomi-mitv-mssp1 ← MiTV-MSSP1
            if (device.length() >= 4 && Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(device) + "(?![a-z0-9_])").matcher(text).find()) s += 3;   // nino, PH0M_EA_T32
            if (product.length() >= 4 && !product.equals(device) && text.contains(product)) s += 1;
            if (!model.isEmpty() && model.length() >= 4 && text.contains(Build.MODEL.toLowerCase(Locale.ROOT))) s += 2;
            if (s > 0 && (f.startsWith(brand) || f.startsWith(manu) || norm(m.brand).equals(brand) || norm(m.brand).equals(manu))) s += 1;
            if (s >= 3) { m.score = s; out.add(m); }
        }
        Collections.sort(out, (x, y) -> y.score - x.score);
        return out;
    }

    /** Все модели того же бренда — для ручного выбора. */
    public static List<Model> sameBrand(List<Model> all) {
        String brand = norm(Build.BRAND), manu = norm(Build.MANUFACTURER);
        List<Model> out = new ArrayList<>();
        for (Model m : all) {
            String b = norm(m.brand), f = m.folder.toLowerCase(Locale.ROOT);
            if (b.equals(brand) || b.equals(manu) || f.startsWith(brand + "-") || f.startsWith(manu + "-")) out.add(m);
        }
        return out;
    }

    private static final Pattern PKG = Pattern.compile("^([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)\\s*(?:#\\s*(.*))?$");

    /** Разобрать debloat.txt: разделы (по комментариям) и пакеты. */
    public static List<Entry> list(String folder) throws Exception {
        String txt = new String(InstallActivity.download(RAW + folder + "/debloat.txt"), "UTF-8");
        List<Entry> out = new ArrayList<>();
        String[] lines = txt.split("\n");
        StringBuilder block = new StringBuilder();   // комментарий перед группой пакетов
        boolean header = true;                        // шапка файла до первой пустой строки — не раздел
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) { header = false; block.setLength(0); continue; }
            if (l.startsWith("#")) {
                if (header) continue;
                String c = l.replaceFirst("^#+\\s*", "").replaceAll("^-+\\s*|\\s*-+$", "").trim();
                if (PKG.matcher(c).matches()) continue;   // закомментированный пакет — авторы оставили включённым
                if (!c.isEmpty()) { if (block.length() > 0) block.append(' '); block.append(c); }
                continue;
            }
            header = false;
            Matcher m = PKG.matcher(l);
            if (!m.matches()) continue;
            if (block.length() > 0) {
                Entry sec = new Entry();
                sec.title = block.length() > 220 ? block.substring(0, 220) + "…" : block.toString();
                out.add(sec);
                block.setLength(0);
            }
            Entry e = new Entry();
            e.pkg = m.group(1);
            e.note = m.group(2) == null ? "" : m.group(2).trim();
            String ctx = (e.note + " " + lastTitle(out)).toLowerCase(Locale.ROOT);
            e.ownerChoice = ctx.matches("(?s).*(owner choice|owner's choice|owners' choice|by choice|does not use|doesn't use|not used by|keep if|if you use|optional|streaming app|preinstalled streaming).*");
            out.add(e);
        }
        return out;
    }

    private static String lastTitle(List<Entry> l) {
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).pkg == null) return l.get(i).title;
        return "";
    }

    /** Заставки: перед их отключением выключаем заставку, иначе настройка «повиснет». */
    public static boolean isDream(String pkg) {
        return pkg.equals("com.google.android.backdrop") || pkg.endsWith(".dream") || pkg.contains(".dreams.") || pkg.endsWith("dreams.basic")
                || pkg.contains("screensaver");
    }

    private Cyt() { }
}
