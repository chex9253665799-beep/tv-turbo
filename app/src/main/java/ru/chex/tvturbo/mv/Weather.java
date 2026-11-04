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
        public String source = "";
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
        Throwable first = null;
        int start = p.getInt("wsrc", 0);   // сначала источник, который сработал в прошлый раз
        for (int k = 0; k < 3; k++) {
            int src = (start + k) % 3;
            try {
                Now n = src == 0 ? openMeteo(lat, lon) : src == 1 ? metNo(lat, lon) : wttr(lat, lon);
                n.city = p.getString("city", "");
                n.fetchedAt = System.currentTimeMillis();
                p.edit().putInt("wsrc", src).apply();
                return n;
            } catch (Throwable t) { if (first == null) first = t; }
        }
        throw new IllegalStateException("серверы погоды недоступны");
    }

    private static Now openMeteo(double lat, double lon) throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure"
                + "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=6&wind_speed_unit=ms";
        JSONObject o = new JSONObject(get(url));
        JSONObject cur = o.getJSONObject("current");
        Now n = new Now();
        n.source = "Open-Meteo";
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
        return n;
    }

    /** Норвежский метеоинститут (met.no) — бесплатный, работает в России. */
    private static Now metNo(double lat, double lon) throws Exception {
        String url = String.format(java.util.Locale.US, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.3f&lon=%.3f", lat, lon);
        JSONArray ts = new JSONObject(get(url)).getJSONObject("properties").getJSONArray("timeseries");
        Now n = new Now();
        n.source = "met.no";
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        java.util.Map<String, double[]> days = new java.util.LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            long t = iso.parse(e.getString("time")).getTime();
            JSONObject d = e.getJSONObject("data");
            JSONObject in = d.getJSONObject("instant").getJSONObject("details");
            double temp = in.getDouble("air_temperature");
            String sym = "";
            if (d.has("next_1_hours")) sym = d.getJSONObject("next_1_hours").getJSONObject("summary").getString("symbol_code");
            else if (d.has("next_6_hours")) sym = d.getJSONObject("next_6_hours").getJSONObject("summary").getString("symbol_code");
            int code = symToWmo(sym);
            if (n.hours.isEmpty() && t + 3600_000 > now) {
                n.temp = temp; n.code = code;
                n.wind = in.optDouble("wind_speed", 0); n.windDir = (int) in.optDouble("wind_from_direction", 0);
                n.humidity = in.optDouble("relative_humidity", 0);
                n.pressureMm = in.optDouble("air_pressure_at_sea_level", 0) * 0.750062;
                n.feels = feels(temp, n.wind, n.humidity);
            }
            cal.setTimeInMillis(t);
            if (t + 3600_000 > now && n.hours.size() < 12 && d.has("next_1_hours"))
                n.hours.add(new double[]{cal.get(java.util.Calendar.HOUR_OF_DAY), temp, code});
            String key = cal.get(java.util.Calendar.YEAR) + "-" + cal.get(java.util.Calendar.DAY_OF_YEAR);
            double[] day = days.get(key);
            if (day == null) days.put(key, day = new double[]{cal.get(java.util.Calendar.DAY_OF_WEEK), temp, temp, code,
                    cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH)});
            day[1] = Math.min(day[1], temp); day[2] = Math.max(day[2], temp);
            if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= 11 && cal.get(java.util.Calendar.HOUR_OF_DAY) <= 14 && !sym.isEmpty()) day[3] = code;
        }
        for (double[] d : days.values()) if (n.days.size() < 6) n.days.add(d);
        if (n.hours.isEmpty()) throw new IllegalStateException("met.no: нет данных");
        return n;
    }

    /** Запасной источник: wttr.in. */
    private static Now wttr(double lat, double lon) throws Exception {
        JSONObject o = new JSONObject(get(String.format(java.util.Locale.US, "https://wttr.in/%.3f,%.3f?format=j1&lang=ru", lat, lon)));
        JSONObject c = o.getJSONArray("current_condition").getJSONObject(0);
        Now n = new Now();
        n.source = "wttr.in";
        n.temp = c.getDouble("temp_C"); n.feels = c.getDouble("FeelsLikeC");
        n.humidity = c.getDouble("humidity"); n.wind = c.getDouble("windspeedKmph") / 3.6;
        n.windDir = c.optInt("winddirDegree"); n.pressureMm = c.optDouble("pressure", 0) * 0.750062;
        n.code = wwoToWmo(c.optInt("weatherCode"));
        JSONArray w = o.getJSONArray("weather");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hourNow = cal.get(java.util.Calendar.HOUR_OF_DAY);
        for (int i = 0; i < w.length(); i++) {
            JSONObject d = w.getJSONObject(i);
            cal.setTime(f.parse(d.getString("date")));
            JSONArray hr = d.getJSONArray("hourly");
            n.days.add(new double[]{cal.get(java.util.Calendar.DAY_OF_WEEK), d.getDouble("mintempC"), d.getDouble("maxtempC"),
                    wwoToWmo(hr.getJSONObject(Math.min(4, hr.length() - 1)).optInt("weatherCode")),
                    cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH)});
            for (int j = 0; j < hr.length() && n.hours.size() < 8; j++) {
                JSONObject x = hr.getJSONObject(j);
                int hh = x.getInt("time") / 100;
                if (i == 0 && hh + 3 <= hourNow) continue;
                n.hours.add(new double[]{hh, x.getDouble("tempC"), wwoToWmo(x.optInt("weatherCode"))});
            }
        }
        return n;
    }

    /** «Ощущается как»: ветро-холодовой индекс / жара — стандартные формулы. */
    private static double feels(double t, double windMs, double hum) {
        double v = windMs * 3.6;
        if (t <= 10 && v > 4.8) return 13.12 + 0.6215 * t - 11.37 * Math.pow(v, 0.16) + 0.3965 * t * Math.pow(v, 0.16);
        if (t >= 27) return t + 0.33 * (hum / 100 * 6.105 * Math.exp(17.27 * t / (237.7 + t))) - 4;
        return t;
    }

    private static int symToWmo(String s) {
        if (s.startsWith("clearsky")) return 0;
        if (s.startsWith("fair")) return 1;
        if (s.startsWith("partlycloudy")) return 2;
        if (s.startsWith("cloudy")) return 3;
        if (s.startsWith("fog")) return 45;
        if (s.contains("thunder")) return 95;
        if (s.contains("snow") || s.contains("sleet")) return 71;
        if (s.contains("showers")) return 80;
        if (s.contains("rain")) return 61;
        return 3;
    }

    private static int wwoToWmo(int c) {
        if (c == 113) return 0;
        if (c == 116) return 2;
        if (c == 119 || c == 122) return 3;
        if (c == 143 || c == 248 || c == 260) return 45;
        if (c >= 386) return 95;
        if ((c >= 179 && c <= 230) || (c >= 323 && c <= 377)) return 71;
        if (c >= 176) return 61;
        return 3;
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
        try { return searchOpenMeteo(q); } catch (Throwable t) { return searchOsm(q); }
    }

    /** Запасной геокодер — OpenStreetMap. */
    private static List<City> searchOsm(String q) throws Exception {
        JSONArray r = new JSONArray(get("https://nominatim.openstreetmap.org/search?format=json&limit=8&accept-language=ru&featuretype=city&q="
                + URLEncoder.encode(q, "UTF-8")));
        List<City> out = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.getJSONObject(i);
            City c = new City();
            String dn = x.optString("display_name");
            c.name = dn.contains(",") ? dn.substring(0, dn.indexOf(',')) : dn;
            c.region = dn.contains(",") ? dn.substring(dn.indexOf(',') + 1).trim() : "";
            c.lat = Double.parseDouble(x.getString("lat")); c.lon = Double.parseDouble(x.getString("lon"));
            out.add(c);
        }
        return out;
    }

    private static List<City> searchOpenMeteo(String q) throws Exception {
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
        con.setConnectTimeout(5000); con.setReadTimeout(12000);
        con.setRequestProperty("User-Agent", "MiVisionTV/1.0");
        int code = con.getResponseCode();
        if (code != 200) throw new IllegalStateException("сервер погоды ответил " + code);
        try (InputStream in = con.getInputStream()) { return new String(Img.readAll(in), "UTF-8"); }
    }

    // ------------------------------------------------------------------ коды погоды WMO

    public static int icon(int c) {
        if (c == 0) return ru.chex.tvturbo.R.drawable.wx_sun;
        if (c <= 2) return ru.chex.tvturbo.R.drawable.wx_cloudsun;
        if (c == 3) return ru.chex.tvturbo.R.drawable.wx_cloud;
        if (c == 45 || c == 48) return ru.chex.tvturbo.R.drawable.wx_fog;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return ru.chex.tvturbo.R.drawable.wx_snow;
        if (c >= 95) return ru.chex.tvturbo.R.drawable.wx_storm;
        if (c >= 51) return ru.chex.tvturbo.R.drawable.wx_rain;
        return ru.chex.tvturbo.R.drawable.wx_cloud;
    }

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
