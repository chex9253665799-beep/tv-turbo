package ru.chex.tvturbo.mv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Каталог фильмов и сериалов из Kinopoisk API Unofficial (kinopoiskapiunofficial.tech).
 * Нужен бесплатный личный ключ пользователя — вводится в Настройках → «Каталог фильмов».
 * Только постеры и описания; сами фильмы смотрятся в установленных приложениях.
 */
public final class Kinopoisk {

    private static final String API = "https://kinopoiskapiunofficial.tech/api";

    public static String key(Context c) { return Kit.sp(c).getString("kpKey", ""); }
    public static void setKey(Context c, String k) { Kit.sp(c).edit().putString("kpKey", k.trim()).apply(); }

    /** Подборки: название ряда → тип коллекции API. */
    static final String[][] COLLECTIONS = {
            {"Популярные фильмы", "TOP_POPULAR_MOVIES"},
            {"Популярные сериалы", "POPULAR_SERIES"},
            {"Скоро и новинки", "CLOSES_RELEASES"},
            {"Семейное кино", "FAMILY"},
            {"Лучшие фильмы", "TOP_250_MOVIES"},
            {"Лучшие сериалы", "TOP_250_TV_SHOWS"},
    };

    /** Загрузить подборки в каталог. Бросает исключение с понятным текстом. В фоне. */
    static void fill(Context c, Catalog cat) throws Exception {
        String k = key(c);
        if (k.isEmpty()) throw new IllegalStateException("нет ключа каталога");
        for (String[] col : COLLECTIONS) {
            JSONObject o = new JSONObject(get(k, API + "/v2.2/films/collections?page=1&type=" + col[1]));
            JSONArray items = o.optJSONArray("items");
            if (items == null) continue;
            List<Program> row = new ArrayList<>();
            for (int i = 0; i < items.length() && row.size() < 20; i++) {
                Program p = fromItem(items.getJSONObject(i));
                if (p == null) continue;
                p.channel = col[0];
                row.add(p);
                if (!contains(cat.all, p)) cat.all.add(p);
            }
            if (!row.isEmpty()) cat.channels.put(col[0], row);
        }
        // широкие обложки и описания — только для баннера (экономим лимит ключа)
        int n = 0;
        for (Program p : cat.all) {
            if (n >= 6) break;
            if (!p.cover.isEmpty() || p.kpId == 0) continue;
            try { details(k, p); n++; } catch (Throwable ignored) { }
        }
    }

    public static List<Program> search(Context c, String q) throws Exception {
        List<Program> out = new ArrayList<>();
        String k = key(c);
        if (k.isEmpty() || q.trim().length() < 2) return out;
        JSONObject o = new JSONObject(get(k, API + "/v2.1/films/search-by-keyword?page=1&keyword=" + URLEncoder.encode(q, "UTF-8")));
        JSONArray f = o.optJSONArray("films");
        if (f != null) for (int i = 0; i < f.length(); i++) {
            JSONObject x = f.getJSONObject(i);
            Program p = new Program();
            p.kpId = x.optLong("filmId");
            p.title = x.optString("nameRu", x.optString("nameEn", ""));
            if (p.title.isEmpty() || "null".equals(p.title)) continue;
            p.poster = x.optString("posterUrlPreview");
            p.year = x.optString("year").replace("null", "");
            p.desc = x.optString("description").replace("null", "");
            p.type = typeOf(x.optString("type"));
            p.genre = genres(x.optJSONArray("genres"));
            p.app = "Кинопоиск";
            out.add(p);
        }
        return out;
    }

    /** Подробности для карточки: описание, возраст, длительность, широкая обложка. */
    public static void details(String key, Program p) throws Exception {
        JSONObject o = new JSONObject(get(key, API + "/v2.2/films/" + p.kpId));
        String d = o.optString("description").replace("null", "");
        if (d.isEmpty()) d = o.optString("shortDescription").replace("null", "");
        if (!d.isEmpty()) p.desc = d;
        String age = o.optString("ratingAgeLimits").replace("null", "").replace("age", "");
        if (!age.isEmpty()) p.rating = age + "+";
        int len = o.optInt("filmLength", 0);
        if (len > 0) p.durationMs = len * 60_000L;
        String cover = o.optString("coverUrl").replace("null", "");
        if (!cover.isEmpty()) p.cover = cover;
        if (p.poster.isEmpty()) p.poster = o.optString("posterUrl").replace("null", "");
    }

    private static Program fromItem(JSONObject x) {
        Program p = new Program();
        p.kpId = x.optLong("kinopoiskId");
        p.title = x.optString("nameRu").replace("null", "");
        if (p.title.isEmpty()) p.title = x.optString("nameOriginal").replace("null", "");
        if (p.title.isEmpty()) return null;
        p.poster = x.optString("posterUrlPreview").replace("null", "");
        int y = x.optInt("year", 0);
        p.year = y > 0 ? String.valueOf(y) : "";
        double r = x.optDouble("ratingKinopoisk", 0);
        if (r > 0) p.score = String.format(java.util.Locale.US, "%.1f", r);
        p.type = typeOf(x.optString("type"));
        p.genre = genres(x.optJSONArray("genres"));
        p.app = "Кинопоиск";
        return p;
    }

    private static int typeOf(String t) {
        return t.contains("SERIES") || t.contains("TV_SHOW") ? 1 : 0;
    }

    private static String genres(JSONArray g) {
        if (g == null) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < g.length() && i < 3; i++) {
            String v = g.optJSONObject(i) == null ? "" : g.optJSONObject(i).optString("genre");
            if (v.isEmpty()) continue;
            if (s.length() > 0) s.append(", ");
            s.append(Character.toUpperCase(v.charAt(0))).append(v.substring(1));
        }
        return s.toString();
    }

    private static boolean contains(List<Program> l, Program p) {
        for (Program x : l) if (x.kpId == p.kpId && p.kpId != 0) return true;
        return false;
    }

    private static String get(String key, String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000); con.setReadTimeout(15000);
        con.setRequestProperty("X-API-KEY", key);
        con.setRequestProperty("Accept", "application/json");
        int code = con.getResponseCode();
        if (code == 401) throw new IllegalStateException("ключ каталога неверный");
        if (code == 402 || code == 429) throw new IllegalStateException("лимит запросов ключа на сегодня исчерпан");
        if (code != 200) throw new IllegalStateException("каталог ответил " + code);
        try (InputStream in = con.getInputStream()) { return new String(Img.readAll(in), "UTF-8"); }
    }

    static int month() { return Calendar.getInstance().get(Calendar.MONTH); }

    private Kinopoisk() { }
}
