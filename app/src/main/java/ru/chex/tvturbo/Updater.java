package ru.chex.tvturbo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Самообновление: берёт релиз «latest» из репозитория на GitHub, сравнивает номер сборки
 * и ставит новую версию поверх через ADB (без вопросов, без удаления данных).
 */
public final class Updater {

    public static final String REPO = "chex9253665799-beep/tv-turbo";

    public interface Listener { void onStatus(String text); }

    public static final class Info {
        public int remoteCode; public String notes = "", apkUrl;
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("update", Context.MODE_PRIVATE); }

    public static boolean isAuto(Context c) { return sp(c).getBoolean("auto", true); }
    public static void setAuto(Context c, boolean on) { sp(c).edit().putBoolean("auto", on).apply(); }

    @SuppressWarnings("deprecation")
    public static int currentCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Throwable t) { return 0; }
    }

    public static String currentName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Throwable t) { return "?"; }
    }

    /** Узнать, что лежит в релизе. Сеть — вызывать не из главного потока. */
    public static Info fetch() throws Exception {
        String json = new String(InstallActivity.download("https://api.github.com/repos/" + REPO + "/releases/tags/latest"), "UTF-8");
        JSONObject rel = new JSONObject(json);
        Info info = new Info();
        String body = rel.optString("body", "");
        Matcher m = Pattern.compile("versionCode=(\\d+)").matcher(body);
        if (m.find()) info.remoteCode = Integer.parseInt(m.group(1));
        info.notes = body.replaceAll("versionCode=\\d+\\s*", "").trim();
        JSONArray assets = rel.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").equals("TvTurbo.apk")) { info.apkUrl = a.getString("browser_download_url"); break; }
        }
        return info;
    }

    /**
     * Проверить и, если есть новая версия, поставить её.
     * force=false — только если включено автообновление. Работает в очереди Adb.
     */
    public static void checkAndInstall(Context ctx, boolean force, Listener l) {
        Context c = ctx.getApplicationContext();
        Adb adb = Adb.get(c);
        if (!force && !isAuto(c)) return;
        new Thread(() -> {
            Info info;
            try { info = fetch(); }
            catch (Throwable t) { say(adb, l, force ? "Не удалось проверить обновления: " + t.getMessage() : null); return; }
            int cur = currentCode(c);
            sp(c).edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            if (info.remoteCode <= cur || info.apkUrl == null) {
                say(adb, l, force ? "✔ У тебя последняя версия (" + currentName(c) + ")" : null);
                return;
            }
            say(adb, l, "⬇ Скачиваю обновление 1." + info.remoteCode + "…");
            File f = new File(c.getCacheDir(), "update.apk");
            try {
                byte[] data = InstallActivity.download(info.apkUrl);
                try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
            } catch (Throwable t) { say(adb, l, "✖ Обновление не скачалось: " + t.getMessage()); return; }
            // файл в релизе мог ещё не смениться (номер версии появляется раньше файла) — не ставим старое по кругу
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || !c.getPackageName().equals(pi.packageName) || pi.versionCode <= cur) {
                f.delete();
                say(adb, l, force ? "Обновление ещё выкладывается — попробуй через пару минут" : null);
                return;
            }
            if (!adb.isConnected()) { say(adb, l, "Обновление скачано — подключись к ТВ, чтобы установить"); return; }
            say(adb, l, "Устанавливаю 1." + info.remoteCode + " — приложение перезапустится…");
            // после установки ТВ закроет старую версию; новая запустится командой ниже
            adb.background(() -> {
                Adb.Result r = adb.installSync(f);
                if (!r.ok) say(adb, l, "✖ Не установилось: " + r.out); // при успехе приложение откроется само (MY_PACKAGE_REPLACED)
            });
        }).start();
    }

    private static void say(Adb adb, Listener l, String text) {
        if (text == null) return;
        adb.logLine(text);
        if (l != null) adb.ui(() -> l.onStatus(text));
    }

    private Updater() { }
}
