package ru.chex.tvturbo;

import android.app.AlertDialog;
import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

/** Все приложения: отключить / включить / удалить для пользователя / вернуть / запретить фон. */
public class AppsActivity extends ListScreen {

    private static final String[] FILTERS = {"Все", "Системные", "Установленные", "Отключённые и удалённые"};
    private int filter = 0;
    private Button filterBtn;

    @Override
    protected void setup() {
        title.setText("📦 Все приложения");
        hint.setText("Нажми на приложение, чтобы выбрать действие.");
        filterBtn = Ui.action(this, actions, "Показать: " + FILTERS[filter], v -> {
            filter = (filter + 1) % FILTERS.length;
            filterBtn.setText("Показать: " + FILTERS[filter]);
            load();
        });
        load();
    }

    private void load() {
        adapter.rows.clear();
        for (Pkgs.P p : Pkgs.list(this)) {
            boolean off = !p.installed || !p.enabled;
            if (filter == 1 && !p.system) continue;
            if (filter == 2 && p.system) continue;
            if (filter == 3 && !off) continue;
            Ui.Row r = new Ui.Row();
            r.mark = !p.installed ? "🗑" : !p.enabled ? "⛔" : p.system ? "⚙" : "📱";
            r.name = p.label;
            r.sub = p.pkg;
            Bloat.Item known = Bloat.find(p.pkg);
            if (Bloat.isCritical(p.pkg)) { r.badge = "системное, не трогать"; r.badgeColor = 0xFFFF5A5F; }
            else if (known != null) { r.badge = "мусор: " + Bloat.levelLabel(known.level); r.badgeColor = Bloat.levelColor(known.level); }
            else r.badge = p.state();
            r.tag = p;
            adapter.rows.add(r);
        }
        hint.setText("Приложений: " + adapter.rows.size() + ". Нажми на приложение, чтобы выбрать действие.");
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onRowClick(Ui.Row row, int pos) {
        Pkgs.P p = (Pkgs.P) row.tag;
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();

        if (p.installed && p.enabled) {
            labels.add("⛔  Отключить"); acts.add(() -> act(p, Pkgs.cmdDisable(p.pkg), 'd'));
            labels.add("🌙  Запретить работу в фоне"); acts.add(() -> act(p, Pkgs.cmdNoBackground(p.pkg), 'b'));
            labels.add("⏹  Остановить сейчас"); acts.add(() -> act(p, Pkgs.cmdStop(p.pkg), (char) 0));
            labels.add("🗑  Удалить для пользователя (можно вернуть)"); acts.add(() -> act(p, Pkgs.cmdUninstall(p.pkg), 'u'));
        }
        if (p.installed && !p.enabled) { labels.add("✅  Включить"); acts.add(() -> restore(p, Pkgs.cmdEnable(p.pkg))); }
        if (!p.installed) { labels.add("↩  Вернуть (переустановить)"); acts.add(() -> restore(p, Pkgs.cmdReinstall(p.pkg) + "; " + Pkgs.cmdEnable(p.pkg))); }
        if (p.installed) { labels.add("✅  Разрешить работу в фоне"); acts.add(() -> restore(p, Pkgs.cmdAllowBackground(p.pkg))); }

        new AlertDialog.Builder(this)
                .setTitle(p.label + "\n" + p.pkg)
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void act(Pkgs.P p, String cmd, char kind) {
        Runnable go = () -> {
            if (kind != 0) Pkgs.remember(this, kind, p.pkg);
            adb.run(cmd, (ok, out) -> { Ui.toast(this, (ok ? "✔ " : "⚠ ") + p.label + (out.isEmpty() ? "" : ": " + out)); load(); });
        };
        if (Bloat.isCritical(p.pkg) || p.pkg.equals(Pkgs.currentHome(this))) {
            Ui.confirm(this, "⚠ Опасно", p.label + " — важная системная часть или главный экран.\nПосле этого ТВ может работать неправильно. Точно продолжить?", go);
        } else go.run();
    }

    private void restore(Pkgs.P p, String cmd) {
        adb.run(cmd, (ok, out) -> {
            if (ok) Pkgs.forget(this, p.pkg);
            Ui.toast(this, (ok ? "✔ " : "⚠ ") + p.label + (out.isEmpty() ? "" : ": " + out));
            load();
        });
    }
}
