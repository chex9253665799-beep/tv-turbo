package ru.chex.tvturbo.mv;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.chex.tvturbo.Adb;

/**
 * Каталог фильмов и сериалов из УСТАНОВЛЕННЫХ приложений.
 * Онлайн-кинотеатры (КИОН, Иви, Wink…) сами публикуют для главного экрана Android TV свои подборки:
 * постер, название, описание, ссылку «открыть в приложении». Обычному лаунчеру Android их не отдаёт,
 * поэтому читаем через ADB (shell) из системного TvProvider. Ничего не выдумываем и не качаем со стороны.
 */
public final class Catalog {

    public final List<Program> all = new ArrayList<>();
    public final List<Program> watchNext = new ArrayList<>();
    /** Ряды как их назвали сами приложения: «Иви: Рекомендованные» и т.п. */
    public final Map<String, List<Program>> channels = new LinkedHashMap<>();
    public String status = "";
    public boolean available;

    private static final String[] PREVIEW_COLS = {"channel_id", "package_name", "title", "short_description",
            "poster_art_uri", "intent_uri", "type", "genre", "content_rating", "release_date", "duration_millis"};
    private static final String[] WATCH_COLS = {"package_name", "title", "short_description", "poster_art_uri",
            "intent_uri", "type", "genre", "content_rating", "release_date", "duration_millis",
            "last_playback_position_millis", "last_engagement_time_utc_millis"};
    private static final String[] CH_COLS = {"_id", "package_name", "display_name", "type"};

    // ------------------------------------------------------------------ загрузка

    /** Прочитать подборки всех приложений. Сеть не нужна. Вызывать в фоне. */
    public static Catalog load(Context c) {
        Catalog cat = loadApps(c);
        if (!cat.all.isEmpty()) return cat;
        // приложения подборок не отдали — берём каталог Кинопоиска (если введён ключ)
        if (!Kinopoisk.key(c).isEmpty()) {
            File f = file(c);
            Catalog old = cached(c);
            if (old.fromKp && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L && !old.all.isEmpty()) {
                old.watchNext.addAll(cat.watchNext);
                return old;
            }
            Catalog kp = new Catalog();
            kp.watchNext.addAll(cat.watchNext);
            try {
                Kinopoisk.fill(c, kp);
                kp.available = true;
                kp.fromKp = true;
                kp.status = "Кинопоиск: " + kp.all.size();
                save(c, kp);
                return kp;
            } catch (Throwable t) {
                if (!old.all.isEmpty()) return old;
                cat.status = "Каталог Кинопоиска: " + t.getMessage();
                return cat;
            }
        }
        if (cat.available) cat.status = "NOKEY";
        return cat;
    }

    public boolean fromKp;

    /** Подборки, опубликованные установленными приложениями (TvProvider). */
    private static Catalog loadApps(Context c) {
        Catalog cat = new Catalog();
        Adb adb = Adb.get(c);
        if (!adb.isConnected()) { cat.status = "нет подключения к отладке"; return cat; }

        Map<String, String> chName = new HashMap<>();
        for (Map<String, String> r : query(adb, "channel", CH_COLS)) {
            String t = r.get("type");
            if (t != null && !t.contains("PREVIEW")) continue;
            chName.put(r.get("_id"), r.get("display_name"));
        }

        PackageManager pm = c.getPackageManager();
        Map<String, String> labels = new HashMap<>();

        List<Map<String, String>> prev = query(adb, "preview_program", PREVIEW_COLS);
        List<Map<String, String>> wn = query(adb, "watch_next_program", WATCH_COLS);
        if (prev == null && wn == null) {
            cat.status = "ТВ не даёт доступ к подборкам приложений";
            return cat;
        }
        if (prev != null) for (Map<String, String> r : prev) {
            Program p = build(r, pm, labels);
            if (p == null) continue;
            String ch = chName.get(r.get("channel_id"));
            p.channel = (ch == null || ch.isEmpty()) ? p.app : (ch.contains(p.app) ? ch : p.app + ": " + ch);
            cat.all.add(p);
            List<Program> l = cat.channels.get(p.channel);
            if (l == null) cat.channels.put(p.channel, l = new ArrayList<>());
            l.add(p);
        }
        if (wn != null) for (Map<String, String> r : wn) {
            Program p = build(r, pm, labels);
            if (p == null) continue;
            p.watchNext = true;
            p.positionMs = num(r.get("last_playback_position_millis"));
            p.engagedAt = num(r.get("last_engagement_time_utc_millis"));
            cat.watchNext.add(p);
        }
        Collections.sort(cat.watchNext, (a, b) -> Long.compare(b.engagedAt, a.engagedAt));
        cat.available = true;
        cat.status = "фильмов и сериалов: " + (cat.all.size() + cat.watchNext.size());
        if (!cat.all.isEmpty()) save(c, cat);
        return cat;
    }

    private static Program build(Map<String, String> r, PackageManager pm, Map<String, String> labels) {
        Program p = new Program();
        p.pkg = nz(r.get("package_name"));
        p.title = nz(r.get("title"));
        if (p.title.isEmpty() || p.pkg.isEmpty()) return null;
        p.desc = nz(r.get("short_description"));
        p.poster = nz(r.get("poster_art_uri"));
        p.intent = nz(r.get("intent_uri"));
        p.type = typeOf(nz(r.get("type")));
        p.genre = genreRu(nz(r.get("genre")));
        p.rating = ratingRu(nz(r.get("content_rating")));
        p.year = yearOf(nz(r.get("release_date")));
        p.durationMs = num(r.get("duration_millis"));
        String l = labels.get(p.pkg);
        if (l == null) {
            try { l = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p.pkg, 0))); } catch (Throwable t) { l = p.pkg; }
            labels.put(p.pkg, l);
        }
        p.app = l;
        return p;
    }

    /** «content query» системного TvProvider. null — нет доступа. */
    private static List<Map<String, String>> query(Adb adb, String table, String[] cols) {
        String out = adb.readText("content query --uri content://android.media.tv/" + table
                + " --projection " + android.text.TextUtils.join(":", cols) + " 2>&1");
        if (out.contains("Permission Denial") || out.contains("Exception") || out.startsWith("Error")) return null;
        List<Map<String, String>> rows = new ArrayList<>();
        StringBuilder keys = new StringBuilder();
        for (String k : cols) { if (keys.length() > 0) keys.append('|'); keys.append(Pattern.quote(k)); }
        Pattern split = Pattern.compile(", (?=(?:" + keys + ")=)");
        StringBuilder cur = null;
        List<String> lines = new ArrayList<>();
        for (String line : out.split("\n")) {          // описание может быть многострочным
            if (line.startsWith("Row: ")) { if (cur != null) lines.add(cur.toString()); cur = new StringBuilder(line); }
            else if (cur != null) cur.append(' ').append(line);
        }
        if (cur != null) lines.add(cur.toString());
        Pattern head = Pattern.compile("^Row: \\d+ ");
        for (String line : lines) {
            Matcher m = head.matcher(line);
            String body = m.find() ? line.substring(m.end()) : line;
            Map<String, String> r = new HashMap<>();
            for (String part : split.split(body)) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String v = part.substring(eq + 1).trim();
                r.put(part.substring(0, eq), "NULL".equals(v) ? "" : v);
            }
            rows.add(r);
        }
        return rows;
    }

    // ------------------------------------------------------------------ выборки

    public List<Program> movies() { List<Program> l = new ArrayList<>(); for (Program p : all) if (p.isMovie()) l.add(p); return l; }
    public List<Program> series() { List<Program> l = new ArrayList<>(); for (Program p : all) if (p.isSeries()) l.add(p); return l; }

    public Map<String, List<Program>> byGenre(List<Program> src) {
        Map<String, List<Program>> m = new LinkedHashMap<>();
        for (Program p : src) {
            if (p.genre.isEmpty()) continue;
            for (String g : p.genre.split(", ")) {
                List<Program> l = m.get(g);
                if (l == null) m.put(g, l = new ArrayList<>());
                if (!l.contains(p)) l.add(p);
            }
        }
        return m;
    }

    /** Карусель баннера: по одному лучшему с постером из каждого приложения, потом остальные. */
    public List<Program> featured(int max) {
        List<Program> out = new ArrayList<>();
        for (Program p : all) if (out.size() < max && !p.cover.isEmpty()) out.add(p);      // сначала с широкой обложкой
        Map<String, Integer> perApp = new HashMap<>();
        for (int round = 0; round < 6 && out.size() < max; round++)
            for (Program p : all) {
                if (out.size() >= max) break;
                if (p.poster.isEmpty() || out.contains(p)) continue;
                int n = perApp.containsKey(p.pkg) ? perApp.get(p.pkg) : 0;
                if (n > round && !p.pkg.isEmpty()) continue;
                perApp.put(p.pkg, n + 1);
                out.add(p);
            }
        return out;
    }

    public List<Program> search(String q) {
        String s = q.toLowerCase();
        List<Program> l = new ArrayList<>();
        for (Program p : all) if (p.title.toLowerCase().contains(s) || p.desc.toLowerCase().contains(s)) l.add(p);
        for (Program p : watchNext) if (p.title.toLowerCase().contains(s) && !l.contains(p)) l.add(p);
        return l;
    }

    // ------------------------------------------------------------------ кэш (мгновенный старт)

    private static File file(Context c) { return new File(c.getFilesDir(), "mv_catalog.json"); }

    private static void save(Context c, Catalog cat) {
        try {
            JSONObject o = new JSONObject();
            JSONArray rows = new JSONArray(), w = new JSONArray();
            for (Map.Entry<String, List<Program>> e : cat.channels.entrySet()) {
                JSONArray items = new JSONArray();
                for (Program p : e.getValue()) items.put(p.toJson());
                rows.put(new JSONObject().put("name", e.getKey()).put("items", items));
            }
            for (Program p : cat.watchNext) w.put(p.toJson());
            o.put("rows", rows).put("wn", w).put("kp", cat.fromKp);
            try (FileOutputStream f = new FileOutputStream(file(c))) { f.write(o.toString().getBytes("UTF-8")); }
        } catch (Throwable ignored) { }
    }

    public static Catalog cached(Context c) {
        Catalog cat = new Catalog();
        try (FileInputStream in = new FileInputStream(file(c))) {
            JSONObject o = new JSONObject(new String(Img.readAll(in), "UTF-8"));
            cat.fromKp = o.optBoolean("kp");
            JSONArray rows = o.optJSONArray("rows"), w = o.optJSONArray("wn");
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                JSONArray items = r.getJSONArray("items");
                List<Program> l = new ArrayList<>();
                for (int j = 0; j < items.length(); j++) {
                    Program p = Program.fromJson(items.getJSONObject(j));
                    l.add(p);
                    if (seen.add(p.key())) cat.all.add(p);
                }
                cat.channels.put(r.getString("name"), l);
            }
            if (w != null) for (int i = 0; i < w.length(); i++) cat.watchNext.add(Program.fromJson(w.getJSONObject(i)));
            cat.available = !cat.all.isEmpty();
            cat.status = "из кэша";
        } catch (Throwable ignored) { }
        return cat;
    }

    // ------------------------------------------------------------------ перевод полей

    private static String nz(String s) { return s == null ? "" : s; }
    private static long num(String s) { try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0; } }

    private static int typeOf(String t) {
        try { return Integer.parseInt(t.trim()); } catch (Throwable e) { return -1; }
    }

    private static final Map<String, String> GENRES = new HashMap<>();
    static {
        String[][] g = {{"MOVIES", "Фильмы"}, {"COMEDY", "Комедии"}, {"DRAMA", "Драмы"}, {"FAMILY_KIDS", "Семейные"},
                {"SPORTS", "Спорт"}, {"NEWS", "Новости"}, {"MUSIC", "Музыка"}, {"ENTERTAINMENT", "Развлечения"},
                {"EDUCATION", "Познавательное"}, {"TRAVEL", "Путешествия"}, {"ANIMAL_WILDLIFE", "Природа"},
                {"ARTS", "Искусство"}, {"TECH_SCIENCE", "Наука"}, {"PREMIER", "Премьеры"}, {"GAMING", "Игры"},
                {"LIFE_STYLE", "Стиль жизни"}, {"SHOPPING", "Покупки"}};
        for (String[] e : g) GENRES.put(e[0], e[1]);
    }

    private static String genreRu(String g) {
        if (g.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : g.split("[,|]")) {
            String k = part.trim().replace("\"", "");
            if (k.isEmpty()) continue;
            String v = GENRES.containsKey(k.toUpperCase()) ? GENRES.get(k.toUpperCase()) : k;
            if (sb.indexOf(v) >= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
        }
        return sb.toString();
    }

    private static String ratingRu(String r) {
        Matcher m = Pattern.compile("(\\d{1,2})\\+?").matcher(r);
        return m.find() ? m.group(1) + "+" : "";
    }

    private static String yearOf(String d) {
        Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(d);
        return m.find() ? m.group() : "";
    }
}
