package ru.chex.tvturbo.mv;

import org.json.JSONObject;

/** Фильм / сериал / серия, который опубликовало установленное приложение (КИОН, Иви, Wink…). */
public final class Program {
    public long id;
    public String pkg = "", app = "", title = "", desc = "", poster = "", intent = "",
            genre = "", rating = "", year = "", channel = "";
    public int type = -1;          // 0 фильм, 1 сериал, 2 сезон, 3 серия, 4 клип …
    public long durationMs, positionMs, engagedAt;
    public boolean watchNext;      // из «Продолжить просмотр»

    public boolean isSeries() { return type == 1 || type == 2 || type == 3; }
    public boolean isMovie() { return type == 0 || (type < 0 && !isSeries()); }

    public int progressPct() {
        return durationMs > 0 ? (int) Math.min(100, 100 * positionMs / durationMs) : 0;
    }

    public String key() { return pkg + "|" + title; }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("pkg", pkg).put("app", app).put("title", title).put("desc", desc).put("poster", poster)
             .put("intent", intent).put("genre", genre).put("rating", rating).put("year", year)
             .put("channel", channel).put("type", type).put("dur", durationMs).put("pos", positionMs)
             .put("eng", engagedAt).put("wn", watchNext);
        } catch (Throwable ignored) { }
        return o;
    }

    public static Program fromJson(JSONObject o) {
        Program p = new Program();
        p.pkg = o.optString("pkg"); p.app = o.optString("app"); p.title = o.optString("title");
        p.desc = o.optString("desc"); p.poster = o.optString("poster"); p.intent = o.optString("intent");
        p.genre = o.optString("genre"); p.rating = o.optString("rating"); p.year = o.optString("year");
        p.channel = o.optString("channel"); p.type = o.optInt("type", -1); p.durationMs = o.optLong("dur");
        p.positionMs = o.optLong("pos"); p.engagedAt = o.optLong("eng"); p.watchNext = o.optBoolean("wn");
        return p;
    }
}
