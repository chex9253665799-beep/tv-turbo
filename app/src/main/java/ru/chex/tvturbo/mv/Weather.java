package ru.chex.tvturbo.mv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Настоящая погода: Open-Meteo (бесплатно, без ключа). Город — по интернет-адресу ТВ
 * (только после согласия пользователя) или выбранный вручную. Ничего не выдумываем:
 * нет данных — так и показываем.
 */
public final class Weather {

    public static final class Now {
        public String city = "";
        public double temp, feels, wind, humidity, pressureMm;
        public int code, windDir;
        public final List<double[]> hours = new ArrayList<>();   // {час, темп, код}
        public final List<double[]> days = new ArrayList<>();    // {деньНедели, мин, макс, код}
        public long fetchedAt;
    }

    public static final class City { public String name, region; public double lat, lon; }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("mv_weather", Context.MODE_PRIVATE); }

    /** null — ещё не спрашивали; true — можно определять автоматически; false — только вручную. */
    public static Boolean autoAllowed(Context c) {
        return sp(c).contains("auto") ? sp(c).getBoolean("auto", false) : null;
    }
    public static void setAuto(Context c, boolean v) { sp(c).edit().putBoolean("auto", v).apply(); }
    public static String cityName(Context c) { return sp(c).getString("city", ""); }
    public static boolean hasCity(Context c) { return sp(c).contains("lat"); }

    public static void setCity(Context c, City city) {
        sp(c).edit().putString("city", city.name).putFloat("lat", (float) city.lat).putFloat("lon", (float) city.lon)
                .putBoolean("manual", true).apply();
    }

    /** Получить погоду (в фоне). Бросает исключение с понятным текстом при ошибке. */
    public static Now fetch(Context c) throws Exception {
        SharedPreferences p = sp(c);
        if (!p.contains("lat") || (!p.getBoolean("manual", false) && Boolean.TRUE.equals(autoAllowed(c))
                && System.currentTimeMillis() - p.getLong("locAt", 0) > 12 * 3600_000L)) {
            if (!Boolean.TRUE.equals(autoAllowed(c))) {
                if (!p.contains("lat")) throw new IllegalStateException("Выбери город в настройках погоды");
            } else locate(c);
        }
        double lat = p.getFloat("lat", 0), lon = p.getFloat("lon", 0);
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure"
                + "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=6&wind_speed_unit=ms";
        JSONObject o = new JSONObject(get(url));
        JSONObject cur = o.getJSONObject("current");
        Now n = new Now();
        n.city = p.getString("city", "");
        n.temp = cur.getDouble("temperature_2m");
        n.feels = cur.getDouble("apparent_temperature");
        n.humidity = cur.getDouble("relative_humidity_2m");
        n.code = cur.getInt("weather_code");
        n.wind = cur.getDouble("wind_speed_10m");
        n.windDir = cur.optInt("wind_direction_10m");
        n.pressureMm = cur.optDouble("surface_pressure", 0) * 0.750062;
        String nowIso = cur.getString("time");          // местное время «2026-11-04T16:15»
        JSONObject h = o.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time"), hv = h.getJSONArray("temperature_2m"), hc = h.getJSONArray("weather_code");
        int start = 0;
        for (int i = 0; i < ht.length(); i++) if (ht.getString(i).compareTo(nowIso.substring(0, 13)) >= 0) { start = i; break; }
        for (int i = start; i < Math.min(ht.length(), start + 12); i++)
            n.hours.add(new double[]{Integer.parseInt(ht.getString(i).substring(11, 13)), hv.getDouble(i), hc.getInt(i)});
        JSONObject d = o.getJSONObject("daily");
        JSONArray dt = d.getJSONArray("time"), dmin = d.getJSONArray("temperature_2m_min"),
                dmax = d.getJSONArray("temperature_2m_max"), dc = d.getJSONArray("weather_code");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int i = 0; i < dt.length(); i++) {
            cal.setTime(f.parse(dt.getString(i)));
            n.days.add(new double[]{cal.get(java.util.Calendar.DAY_OF_WEEK), dmin.getDouble(i), dmax.getDouble(i), dc.getInt(i),
                    cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH)});
        }
        n.fetchedAt = System.currentTimeMillis();
        return n;
    }

    /** Город по интернет-адресу ТВ (два сервиса на случай, если один недоступен). */
    private static void locate(Context c) throws Exception {
        String name; double lat, lon;
        try {
            JSONObject o = new JSONObject(get("https://ipwho.is/?lang=ru&fields=success,city,latitude,longitude"));
            if (!o.optBoolean("success", true)) throw new IllegalStateException();
            name = o.getString("city"); lat = o.getDouble("latitude"); lon = o.getDouble("longitude");
        } catch (Throwable t) {
            JSONObject o = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
            name = o.optString("city"); lat = o.getDouble("latitude"); lon = o.getDouble("longitude");
        }
        // название по-русски через геокодер Open-Meteo
        try {
            List<City> l = search(name);
            if (!l.isEmpty()) name = l.get(0).name;
        } catch (Throwable ignored) { }
        sp(c).edit().putString("city", name).putFloat("lat", (float) lat).putFloat("lon", (float) lon)
                .putLong("locAt", System.currentTimeMillis()).putBoolean("manual", false).apply();
    }

    /** Поиск города для ручного выбора. */
    public static List<City> search(String q) throws Exception {
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=ru&name="
                + URLEncoder.encode(q, "UTF-8")));
        List<City> out = new ArrayList<>();
        JSONArray r = o.optJSONArray("results");
        if (r != null) for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.getJSONObject(i);
            City c = new City();
            c.name = x.getString("name");
            c.region = x.optString("admin1", "") + (x.has("country") ? ", " + x.optString("country") : "");
            c.lat = x.getDouble("latitude"); c.lon = x.getDouble("longitude");
            out.add(c);
        }
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000); con.setReadTimeout(12000);
        con.setRequestProperty("User-Agent", "MiVisionTV/1.0");
        int code = con.getResponseCode();
        if (code != 200) throw new IllegalStateException("сервер погоды ответил " + code);
        try (InputStream in = con.getInputStream()) { return new String(Img.readAll(in), "UTF-8"); }
    }

    // ------------------------------------------------------------------ коды погоды WMO

    public static String emoji(int c) {
        if (c == 0) return "☀";
        if (c <= 2) return "⛅";
        if (c == 3) return "☁";
        if (c == 45 || c == 48) return "🌫";
        if (c >= 51 && c <= 67) return "🌧";
        if (c >= 71 && c <= 77) return "🌨";
        if (c >= 80 && c <= 82) return "🌦";
        if (c >= 85 && c <= 86) return "🌨";
        if (c >= 95) return "⛈";
        return "☁";
    }

    public static String text(int c) {
        if (c == 0) return "Ясно";
        if (c == 1) return "Преимущественно ясно";
        if (c == 2) return "Облачно с прояснениями";
        if (c == 3) return "Пасмурно";
        if (c == 45 || c == 48) return "Туман";
        if (c >= 51 && c <= 57) return "Морось";
        if (c >= 61 && c <= 67) return "Дождь";
        if (c >= 71 && c <= 77) return "Снег";
        if (c >= 80 && c <= 82) return "Ливень";
        if (c >= 85 && c <= 86) return "Снегопад";
        if (c >= 95) return "Гроза";
        return "Облачно";
    }

    public static String windDir(int deg) {
        String[] d = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
        return d[Math.round(((deg % 360) / 45f)) % 8];
    }

    public static String t(double v) {
        long r = Math.round(v);
        return (r > 0 ? "+" : "") + r + "°";
    }

    private Weather() { }
}
