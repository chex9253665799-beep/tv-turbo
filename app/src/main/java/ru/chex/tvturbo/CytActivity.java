package ru.chex.tvturbo;

import android.app.AlertDialog;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** «CleanYourTV»: проверенный список отключения именно для этой модели ТВ (проект CleanYourTV, MIT). */
public class CytActivity extends ListScreen {

    private boolean busy;
    private Cyt.Model model;
    private boolean patchwall;   // на этом ТВ есть настройка patchwall_enable

    /** То, без чего ТВ или ТВ Турбо не работают, — никогда не отключаем, даже если есть в списке. */
    private static final String[] NEVER = {
            "com.google.android.youtube.tv", "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.webview", "com.android.webview", "com.android.providers.downloads",
            YouTube.DPI_PKG, "ru.chex.tvturbo", "ru.chex.tvturbo.vpn", "com.gathin.tvhome"
    };

    @Override
    protected void setup() {
        title.setText("✨ CleanYourTV — очистка по модели");
        Ui.action(this, actions, "🧹  Отключить отмеченные", v -> apply());
        Ui.action(this, actions, "📺  Выбрать модель", v -> chooseModel(null));
        Ui.action(this, actions, "☐  Снять все", v -> setAll(false));
        load();
    }

    private void load() {
        if (!Ui.needAdb(this)) return;
        busy = true;
        hint.setText("Ищу проверенный список для " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")…");
        adb.background(() -> {
            try {
                List<Cyt.Model> all = Cyt.index();
                List<Cyt.Model> found = Cyt.match(all);
                patchwall = !adb.execSync("settings get global patchwall_enable", false).out.trim().matches("(?i)|null");
                adb.ui(() -> {
                    busy = false;
                    if (found.size() == 1 || (found.size() > 1 && found.get(0).score > found.get(1).score)) open(found.get(0));
                    else if (!found.isEmpty()) chooseModel(found);
                    else {
                        List<Cyt.Model> brand = Cyt.sameBrand(all);
                        hint.setText("Точного списка для этой модели нет. " + (brand.isEmpty() ? "Для этого бренда тоже нет." : "Выбери похожую модель того же бренда — с осторожностью."));
                        if (!brand.isEmpty()) chooseModel(brand);
                    }
                });
            } catch (Throwable t) {
                adb.ui(() -> { busy = false; hint.setText("✖ Не загрузился список моделей: " + t.getMessage() + ". Проверь интернет."); });
            }
        });
    }

    private void chooseModel(List<Cyt.Model> options) {
        if (busy) return;
        if (options == null) {
            busy = true;
            adb.background(() -> {
                try {
                    List<Cyt.Model> all = Cyt.index();
                    List<Cyt.Model> b = Cyt.sameBrand(all);
                    List<Cyt.Model> list = b.isEmpty() ? all : b;
                    adb.ui(() -> { busy = false; chooseModel(list); });
                } catch (Throwable t) { adb.ui(() -> { busy = false; Ui.toast(this, "Нет интернета"); }); }
            });
            return;
        }
        String[] names = new String[options.size()];
        for (int i = 0; i < names.length; i++) {
            String t = options.get(i).tv;
            names[i] = options.get(i).folder + "\n" + (t.length() > 110 ? t.substring(0, 110) + "…" : t);
        }
        new AlertDialog.Builder(this).setTitle("Какая модель? (сверху — самая похожая)")
                .setItems(names, (d, w) -> open(options.get(w)))
                .setNegativeButton("Отмена", null).show();
    }

    private void open(Cyt.Model m) {
        model = m;
        busy = true;
        hint.setText("Загружаю список для " + m.folder + "…");
        adb.background(() -> {
            try {
                List<Cyt.Entry> entries = Cyt.list(m.folder);
                adb.ui(() -> { busy = false; show(m, entries); });
            } catch (Throwable t) {
                adb.ui(() -> { busy = false; hint.setText("✖ Список не загрузился: " + t.getMessage()); });
            }
        });
    }

    private void show(Cyt.Model m, List<Cyt.Entry> entries) {
        Map<String, Pkgs.P> pk = Pkgs.map(this);
        String home = Pkgs.currentHome(this);
        adapter.rows.clear();
        int can = 0;

        if (patchwall) {
            Ui.Row r = new Ui.Row();
            r.checkable = true;
            r.checked = true;
            r.name = "Кнопка MI на пульте не открывает PatchWall";
            r.sub = "settings put global patchwall_enable false — совет CleanYourTV для Xiaomi";
            r.tag = "patchwall";
            adapter.rows.add(r);
        }
        Ui.Row pending = null;
        for (Cyt.Entry e : entries) {
            if (e.pkg == null) {
                pending = new Ui.Row();
                pending.mark = "▸";
                pending.name = e.title;
                continue;
            }
            Pkgs.P st = pk.get(e.pkg);
            if (st == null || !st.installed) continue;          // на этом ТВ такого пакета нет
            if (pending != null) { adapter.rows.add(pending); pending = null; }
            Ui.Row r = new Ui.Row();
            r.name = st.label != null && !st.label.isEmpty() && !st.label.equals(e.pkg) ? st.label : e.pkg;
            r.sub = e.pkg + (e.note.isEmpty() ? "" : " — " + e.note);
            r.tag = e.pkg;
            boolean never = e.pkg.equals(home);
            for (String n : NEVER) if (n.equals(e.pkg)) never = true;
            if (!st.enabled) { r.mark = "✔"; r.badge = "уже отключён"; r.badgeColor = 0xFF3DDC84; }
            else if (never) { r.mark = "🔒"; r.badge = "нужен ТВ — не трогаем"; r.badgeColor = 0xFF8A93A6; }
            else {
                r.checkable = true;
                r.checked = !e.ownerChoice;
                if (e.ownerChoice) { r.badge = "по желанию"; r.badgeColor = 0xFFFFB020; }
                can++;
            }
            adapter.rows.add(r);
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
        hint.setText("Список CleanYourTV: " + m.folder + " — проверен на таком же ТВ. Отключение, не удаление: всё возвращается кнопкой «Вернуть всё». "
                + (can == 0 ? "Здесь всё уже отключено." : "Можно отключить: " + can + ". «По желанию» — сними галочку, если пользуешься."));
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) {
        if (r.checkable) toggle(r);
        else if (r.tag == null && model != null && r.name != null && !r.name.isEmpty()) Ui.info(this, "Пояснение из списка", r.name);
    }

    private void setAll(boolean on) {
        for (Ui.Row r : adapter.rows) if (r.checkable) r.checked = on;
        adapter.notifyDataSetChanged();
    }

    private void apply() {
        if (busy || model == null || !Ui.needAdb(this)) return;
        List<String> cmds = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        boolean dream = false, pw = false;
        for (Ui.Row r : adapter.rows) {
            if (!r.checkable || !r.checked) continue;
            if ("patchwall".equals(r.tag)) { pw = true; continue; }
            String p = (String) r.tag;
            pkgs.add(p);
            if (Cyt.isDream(p)) dream = true;
        }
        if (pkgs.isEmpty() && !pw) { Ui.toast(this, "Ничего не отмечено"); return; }
        if (pw) cmds.add("settings put global patchwall_enable false");
        if (dream) cmds.add("settings put secure screensaver_enabled 0");   // заставку — до отключения её пакета
        for (String p : pkgs) cmds.add(Pkgs.cmdDisable(p));
        String msg = "Отключить: " + pkgs.size() + (pw ? " + кнопка MI без PatchWall" : "") + (dream ? "\nЗаставка будет выключена (её пакет отключается)." : "")
                + "\n\nВсё возвращается кнопкой «Вернуть всё». Страж будет держать их отключёнными.";
        Ui.confirm(this, "Применить список " + model.folder + "?", msg, () -> {
            busy = true;
            hint.setText("Отключаю…");
            for (String p : pkgs) Pkgs.remember(this, 'd', p);
            adb.runAll(cmds, (ok, out) -> {
                busy = false;
                Ui.toast(this, (ok ? "✔ " : "⚠ ") + out);
                open(model);
            });
        });
    }
}
