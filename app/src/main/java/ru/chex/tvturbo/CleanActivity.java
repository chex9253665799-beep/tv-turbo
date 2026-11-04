package ru.chex.tvturbo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Мастер очистки: известные лишние пакеты + найденные по названию. Отключение, не удаление. */
public class CleanActivity extends ListScreen {

    @Override
    protected void setup() {
        title.setText("🧹 Мастер очистки");
        hint.setText("Зелёные отмечены сами — их безопасно отключать. Жёлтые и красные — только если понимаешь, что это.\nПакеты отключаются (не удаляются) и возвращаются кнопкой «Вернуть всё».");
        Ui.action(this, actions, "🧹  Отключить отмеченные", v -> apply());
        Ui.action(this, actions, "Снять все", v -> { for (Ui.Row r : adapter.rows) r.checked = false; adapter.notifyDataSetChanged(); });
        load();
    }

    private void load() {
        adapter.rows.clear();
        Map<String, Pkgs.P> pkgs = Pkgs.map(this);
        String home = Pkgs.currentHome(this);

        for (Bloat.Item i : Bloat.KNOWN) {
            Pkgs.P p = pkgs.get(i.pkg);
            if (p == null || !p.installed || !p.enabled) continue;
            int level = i.level;
            String why = i.why;
            if (i.pkg.equals(home)) { level = Bloat.RISKY; why = "Это твой текущий главный экран! Сначала поставь другой лаунчер."; }
            adapter.rows.add(row(i.name, i.pkg, why, level, level == Bloat.SAFE));
        }
        for (Pkgs.P p : pkgs.values()) {
            if (!p.system || !p.installed || !p.enabled) continue;
            if (Bloat.find(p.pkg) != null || p.pkg.equals(home) || !Bloat.isSuspicious(p.pkg)) continue;
            adapter.rows.add(row(p.label, p.pkg, "Найдено по названию (похоже на рекламу/телеметрию) — проверь сам", Bloat.CAUTION, false));
        }
        if (adapter.rows.isEmpty()) hint.setText("✔ Лишнего не найдено — всё уже чисто.");
        adapter.notifyDataSetChanged();
        list.requestFocus();
    }

    private Ui.Row row(String name, String pkg, String why, int level, boolean checked) {
        Ui.Row r = new Ui.Row();
        r.checkable = true;
        r.checked = checked;
        r.name = name;
        r.sub = pkg + " — " + why;
        r.badge = Bloat.levelLabel(level);
        r.badgeColor = Bloat.levelColor(level);
        r.tag = pkg;
        return r;
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) { toggle(r); }

    private void apply() {
        List<String> cmds = new ArrayList<>();
        List<String> sel = new ArrayList<>();
        boolean risky = false;
        for (Ui.Row r : adapter.rows) if (r.checked) {
            String pkg = (String) r.tag;
            sel.add(pkg);
            cmds.add(Pkgs.cmdDisable(pkg));
            if ("Риск".equals(r.badge)) risky = true;
        }
        if (cmds.isEmpty()) { Ui.toast(this, "Ничего не отмечено"); return; }
        String msg = "Отключить " + cmds.size() + " пакетов?" + (risky ? "\n\n⚠ Среди них есть красные (лаунчер). Без другого главного экрана кнопка «Домой» перестанет работать!" : "");
        Ui.confirm(this, "Отключить", msg, () -> {
            for (String p : sel) Pkgs.remember(this, 'd', p);
            adb.runAll(cmds, (ok, out) -> { Ui.toast(this, out); load(); });
        });
    }
}
