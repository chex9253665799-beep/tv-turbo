package ru.chex.tvturbo;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Конструктор приложений: каталог (catalog.json), галочки, установка выбранного в один клик.
 * Каталог берётся из репозитория на GitHub (можно менять без пересборки), при отсутствии сети — встроенный.
 * Плюс установка любого APK по ссылке или с флешки.
 */
public class InstallActivity extends ListScreen {

    public static final String DEFAULT_CATALOG =
            "https://raw.githubusercontent.com/chex9253665799-beep/tv-turbo/main/catalog.json";

    private static final class App { String name, pkg, desc, url, github, asset, tag, group; }

    @Override
    protected void setup() {
        title.setText("⬇ Установка приложений");
        hint.setText("Загружаю каталог…");
        Ui.action(this, actions, "▶  YouTube в России", v -> youtube());
        Ui.action(this, actions, "⬇  Установить отмеченные", v -> installChecked());
        Ui.action(this, actions, "🎨  Лаунчеры", v -> startActivity(new android.content.Intent(this, LauncherActivity.class)));
        Ui.action(this, actions, "🔗  APK по ссылке", v -> askUrl());
        Ui.action(this, actions, "💾  APK с флешки", v -> fromUsb());
        Ui.action(this, actions, "🏠  Главный экран", v -> chooseHome());
        Ui.action(this, actions, "⚙  Каталог", v -> askCatalogUrl());
        loadCatalog();
    }

    private SharedPreferences sp() { return getSharedPreferences("install", MODE_PRIVATE); }

    // ---------- каталог ----------

    private void loadCatalog() {
        new Thread(() -> {
            String json = null, src = "из интернета";
            try { json = new String(download(sp().getString("catalog", DEFAULT_CATALOG)), "UTF-8"); }
            catch (Throwable ignored) { }
            if (json == null) {
                src = "встроенный";
                try (InputStream in = getAssets().open("catalog.json")) { json = new String(readAll(in), "UTF-8"); }
                catch (Throwable t) { json = "{\"apps\":[]}"; }
            }
            List<App> apps = parse(json);
            String s = src;
            runOnUiThread(() -> show(apps, s));
        }).start();
    }

    private List<App> parse(String json) {
        List<App> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(json).getJSONArray("apps");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                App a = new App();
                a.name = o.optString("name"); a.pkg = o.optString("pkg");
                a.desc = o.optString("desc"); a.url = o.optString("url");
                a.github = o.optString("github"); a.asset = o.optString("asset"); a.tag = o.optString("tag");
                a.group = o.optString("group", "Приложения");
                if (!a.url.isEmpty() || !a.github.isEmpty()) out.add(a);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void show(List<App> apps, String src) {
        Map<String, Pkgs.P> installed = Pkgs.map(this);
        adapter.rows.clear();
        String lastGroup = null;
        for (App a : apps) {
            if (!a.group.equals(lastGroup)) {
                lastGroup = a.group;
                Ui.Row h = new Ui.Row();
                h.name = "— " + a.group + " —";
                adapter.rows.add(h);
            }
            Ui.Row r = new Ui.Row();
            r.checkable = true;
            boolean have = !a.pkg.isEmpty() && installed.containsKey(a.pkg) && installed.get(a.pkg).installed;
            r.name = a.name;
            r.sub = a.desc;
            r.badge = have ? "установлено" : "";
            r.badgeColor = 0xFF3DDC84;
            r.tag = a;
            adapter.rows.add(r);
        }
        hint.setText("Каталог (" + src + "): " + apps.size() + " шт. Отметь нужные и нажми «Установить отмеченные».\n"
                + "Список меняется в файле catalog.json в репозитории — пересобирать приложение не нужно.");
        adapter.notifyDataSetChanged();
        list.requestFocus();
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) { if (r.checkable) toggle(r); }

    /** В один клик: ByeByeDPI (обход замедления) + SmartTube, затем открыть ByeByeDPI. */
    private void youtube() {
        if (!Ui.needAdb(this)) return;
        List<App> q = new ArrayList<>();
        App dpi = null;
        for (Ui.Row r : adapter.rows) {
            if (!(r.tag instanceof App)) continue;
            App a = (App) r.tag;
            if (a.github.equals("romanvht/ByeByeDPI")) { q.add(0, a); dpi = a; }
            else if (a.github.equals("yuliskov/SmartTube")) q.add(a);
        }
        if (q.isEmpty()) { Ui.toast(this, "В каталоге нет ByeByeDPI/SmartTube"); return; }
        String dpiPkg = dpi != null ? dpi.pkg : "io.github.romanvht.byedpi";
        Ui.confirm(this, "YouTube в России", "Установлю:\n• ByeByeDPI — обход замедления YouTube (работает на самом ТВ)\n• SmartTube — YouTube без рекламы\n\n"
                + "После установки откроется ByeByeDPI: нажми «Подключить» и подтверди запрос VPN (это локальный обход, трафик никуда не уходит). "
                + "Потом открывай SmartTube.", () -> installQueue(q, 0, () ->
                adb.run("monkey -p " + dpiPkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p " + dpiPkg + " 1",
                        (ok, out) -> Ui.info(this, "Почти готово", "В ByeByeDPI нажми «Подключить» и подтверди VPN.\nЗатем открой SmartTube — YouTube должен работать быстро.\n\n"
                                + "Если видео всё равно тормозит — в ByeByeDPI открой настройки и попробуй другую стратегию (режим «desync»)."))));
    }

    private void installChecked() {
        List<App> sel = new ArrayList<>();
        for (Ui.Row r : adapter.rows) if (r.checkable && r.checked) sel.add((App) r.tag);
        if (sel.isEmpty()) { Ui.toast(this, "Ничего не отмечено"); return; }
        installQueue(sel, 0, null);
    }

    private void installQueue(List<App> q, int i, Runnable done) {
        if (i >= q.size()) { Ui.toast(this, "Готово"); loadCatalog(); if (done != null) done.run(); return; }
        App a = q.get(i);
        if (a.github.isEmpty()) { downloadAndInstall(a.name, a.url, () -> installQueue(q, i + 1, done)); return; }
        hint.setText("Ищу последнюю версию " + a.name + " на GitHub…");
        new Thread(() -> {
            String url;
            try { url = latestGithubApk(a.github, a.asset, a.tag); }
            catch (Throwable t) {
                runOnUiThread(() -> { hint.setText("✖ " + a.name + ": " + t.getMessage()); installQueue(q, i + 1, done); });
                return;
            }
            runOnUiThread(() -> downloadAndInstall(a.name, url, () -> installQueue(q, i + 1, done)));
        }).start();
    }

    // ---------- скачивание и установка ----------

    private void downloadAndInstall(String name, String url, Runnable then) {
        hint.setText("Скачиваю " + name + "…");
        new Thread(() -> {
            File f = new File(getCacheDir(), "dl_" + Math.abs(url.hashCode()) + ".apk");
            try {
                byte[] data = download(url, p -> runOnUiThread(() -> hint.setText("Скачиваю " + name + "… " + p + "%")));
                try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
            } catch (Throwable t) {
                runOnUiThread(() -> { hint.setText("✖ Не скачалось: " + name + " — " + t.getMessage()); if (then != null) then.run(); });
                return;
            }
            runOnUiThread(() -> {
                hint.setText("Устанавливаю " + name + "…");
                adb.install(f, (ok, out) -> {
                    f.delete();
                    hint.setText((ok ? "✔ Установлено: " : "✖ Ошибка: ") + name + (ok ? "" : " — " + out));
                    if (then != null) then.run();
                });
            });
        }).start();
    }

    /**
     * Ссылка на APK из релиза репозитория GitHub (последнего или с тегом tag).
     * Выбирает файл под процессор ТВ: universal → arm64 → armeabi-v7a; asset — обязательная часть имени.
     */
    static String latestGithubApk(String repo, String asset, String tag) throws Exception {
        String api = "https://api.github.com/repos/" + repo + "/releases/" + (tag.isEmpty() ? "latest" : "tags/" + tag);
        JSONArray assets = new JSONObject(new String(download(api), "UTF-8")).getJSONArray("assets");
        String best = null; int bestScore = -1;
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String n = a.getString("name").toLowerCase();
            if (!n.endsWith(".apk")) continue;
            if (!asset.isEmpty() && !n.contains(asset.toLowerCase())) continue;
            int score = abiScore(n, abis);
            if (score > bestScore) { bestScore = score; best = a.getString("browser_download_url"); }
        }
        if (best == null || bestScore < 0) throw new IllegalStateException("в релизе нет подходящего APK");
        return best;
    }

    private static int abiScore(String n, String[] abis) {
        boolean anyAbi = n.contains("arm64") || n.contains("armeabi") || n.contains("armv7") || n.contains("v7a")
                || n.contains("x86") || n.contains("aarch64");
        if (n.contains("universal") || !anyAbi) return 50; // универсальный или без указания процессора
        for (int i = 0; i < abis.length; i++) {
            String abi = abis[i];
            boolean match = abi.equals("arm64-v8a") ? (n.contains("arm64") || n.contains("aarch64"))
                    : abi.equals("armeabi-v7a") ? (n.contains("armeabi") || n.contains("armv7") || n.contains("v7a"))
                    : abi.startsWith("x86_64") ? n.contains("x86_64")
                    : abi.startsWith("x86") && n.contains("x86") && !n.contains("x86_64");
            if (match) return 40 - i;
        }
        return -1; // чужой процессор
    }

    // ---------- главный экран ----------

    private void chooseHome() {
        android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME);
        List<android.content.pm.ResolveInfo> ris = getPackageManager().queryIntentActivities(home, 0);
        List<String> names = new ArrayList<>(), comps = new ArrayList<>();
        String cur = Pkgs.currentHome(this);
        for (android.content.pm.ResolveInfo ri : ris) {
            if (ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) continue;
            String label = String.valueOf(ri.loadLabel(getPackageManager()));
            names.add((ri.activityInfo.packageName.equals(cur) ? "● " : "○ ") + label + "\n" + ri.activityInfo.packageName);
            comps.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
        }
        if (comps.isEmpty()) { Ui.toast(this, "Лаунчеры не найдены"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Главный экран (кнопка «Домой»)")
                .setItems(names.toArray(new String[0]), (d, w) -> adb.run("cmd package set-home-activity " + comps.get(w),
                        (ok, out) -> Ui.toast(this, ok ? "✔ Главный экран изменён. Нажми «Домой», чтобы проверить." : "⚠ " + out)))
                .setNegativeButton("Отмена", null).show();
    }

    interface Progress { void on(int pct); }

    static byte[] download(String url) throws Exception { return download(url, null); }

    static byte[] download(String url, Progress pr) throws Exception {
        String u = url;
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "TvTurbo/1.0");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            long len = c.getContentLength();
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                long got = 0; int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    got += n;
                    if (pr != null && len > 0) {
                        int p = (int) (100 * got / len);
                        if (p != last) { last = p; pr.on(p); }
                    }
                }
                return bo.toByteArray();
            }
        }
        throw new IllegalStateException("слишком много перенаправлений");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    // ---------- APK по ссылке ----------

    private void askUrl() {
        EditText e = new EditText(this);
        e.setHint("https://…/app.apk");
        e.setText(sp().getString("lastUrl", "https://"));
        new AlertDialog.Builder(this)
                .setTitle("Ссылка на APK")
                .setView(e)
                .setPositiveButton("Установить", (d, w) -> {
                    String url = e.getText().toString().trim();
                    if (!url.startsWith("http")) { Ui.toast(this, "Нужна ссылка, начинающаяся с http"); return; }
                    sp().edit().putString("lastUrl", url).apply();
                    String name = url.substring(url.lastIndexOf('/') + 1);
                    downloadAndInstall(name.isEmpty() ? "APK" : name, url, null);
                })
                .setNegativeButton("Отмена", null).show();
    }

    // ---------- APK с флешки ----------

    private void fromUsb() {
        hint.setText("Ищу APK на флешках и в памяти ТВ…");
        adb.background(() -> {
            String out = adb.execSync("find /storage /sdcard -maxdepth 4 -iname '*.apk' 2>/dev/null | grep -v '/Android/' | sort -u", false).out;
            List<String> files = new ArrayList<>();
            for (String l : out.split("\n")) if (l.trim().toLowerCase().endsWith(".apk")) files.add(l.trim());
            adb.ui(() -> {
                if (files.isEmpty()) {
                    hint.setText("APK не найдено. Скопируй .apk на флешку, вставь в ТВ и нажми ещё раз.");
                    return;
                }
                hint.setText("Найдено APK: " + files.size());
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1) + "\n" + files.get(i);
                new AlertDialog.Builder(this)
                        .setTitle("Выбери APK")
                        .setItems(names, (d, w) -> installPath(files.get(w)))
                        .setNegativeButton("Отмена", null).show();
            });
        });
    }

    private void installPath(String path) {
        String q = "'" + path.replace("'", "'\\''") + "'";
        hint.setText("Устанавливаю " + path + "…");
        // pm не всегда может читать флешку напрямую — передаём файл через stdin
        adb.run("cat " + q + " | pm install -r -S $(stat -c %s " + q + ")", (ok, out) ->
                hint.setText((ok && out.contains("Success") ? "✔ Установлено: " : "✖ Ошибка: ") + path + "\n" + out));
    }

    // ---------- адрес каталога ----------

    private void askCatalogUrl() {
        EditText e = new EditText(this);
        e.setText(sp().getString("catalog", DEFAULT_CATALOG));
        new AlertDialog.Builder(this)
                .setTitle("Адрес catalog.json")
                .setView(e)
                .setPositiveButton("Сохранить", (d, w) -> { sp().edit().putString("catalog", e.getText().toString().trim()).apply(); loadCatalog(); })
                .setNeutralButton("По умолчанию", (d, w) -> { sp().edit().remove("catalog").apply(); loadCatalog(); })
                .setNegativeButton("Отмена", null).show();
    }
}
