package ru.chex.tvturbo;

import android.content.Intent;

/** Диагностика: что тормозит, с рекомендациями и исправлением в одно нажатие. */
public class DiagActivity extends ListScreen {

    @Override
    protected void setup() {
        title.setText("🔍 Диагностика");
        Ui.action(this, actions, "🔄  Проверить заново", v -> scan());
        Ui.action(this, actions, "✅  Исправить всё безопасное", v -> fixAll());
        scan();
    }

    private void scan() {
        hint.setText("Проверяю память, процессор, диск, рекламу, анимации…");
        adapter.rows.clear();
        adapter.notifyDataSetChanged();
        adb.background(() -> {
            Diag.Report rep;
            try { rep = Diag.run(this); }
            catch (Throwable t) { adb.ui(() -> hint.setText("Ошибка диагностики: " + t)); return; }
            Diag.Report r = rep;
            adb.ui(() -> show(r));
        });
    }

    private void show(Diag.Report rep) {
        hint.setText(rep.summary);
        adapter.rows.clear();
        for (Diag.Rec rec : rep.recs) {
            Ui.Row row = new Ui.Row();
            row.mark = rec.level == Diag.BAD ? "🔴" : rec.level == Diag.WARN ? "🔶" : "✅";
            row.name = rec.title;
            row.sub = rec.detail == null ? "" : rec.detail;
            row.badge = rec.action == null ? "" : rec.action + "  ›";
            row.badgeColor = rec.level == Diag.BAD ? 0xFFFF5A5F : 0xFFFFB020;
            row.tag = rec;
            adapter.rows.add(row);
        }
        adapter.notifyDataSetChanged();
        list.requestFocus();
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) {
        Diag.Rec rec = (Diag.Rec) r.tag;
        if (rec.action == null) return;
        if (rec.open != null) { startActivity(new Intent(this, rec.open)); return; }
        Ui.confirm(this, rec.action + "?", rec.title + "\n\n" + rec.detail, () -> apply(rec, true));
    }

    private void apply(Diag.Rec rec, boolean rescan) {
        if (rec.rememberPkg != null) Pkgs.remember(this, rec.rememberKind, rec.rememberPkg);
        adb.runAll(rec.cmds, (ok, out) -> {
            Ui.toast(this, (ok ? "✔ " : "⚠ ") + rec.action + ": " + out);
            if (rescan && !rec.cmds.contains("reboot")) scan();
        });
    }

    /** Применить все рекомендации, кроме перезагрузки и тех, что открывают другой экран. */
    private void fixAll() {
        int n = 0;
        for (Ui.Row r : adapter.rows) {
            Diag.Rec rec = (Diag.Rec) r.tag;
            if (rec.action != null && rec.open == null && !rec.cmds.contains("reboot")) n++;
        }
        if (n == 0) { Ui.toast(this, "Исправлять нечего"); return; }
        Ui.confirm(this, "Исправить всё?", "Будет применено рекомендаций: " + n + ".\nПерезагрузка и очистка мусора — отдельно.\nВсё можно вернуть кнопкой «Вернуть всё».", () -> {
            for (Ui.Row r : adapter.rows) {
                Diag.Rec rec = (Diag.Rec) r.tag;
                if (rec.action != null && rec.open == null && !rec.cmds.contains("reboot")) apply(rec, false);
            }
            adb.background(() -> adb.ui(this::scan)); // повторная проверка после всех команд в очереди
        });
    }
}
