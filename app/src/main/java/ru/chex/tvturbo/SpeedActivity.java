package ru.chex.tvturbo;

import java.util.List;

/** Ускорение: каждый пункт — готовая настройка, применяется по нажатию. */
public class SpeedActivity extends ListScreen {

    @Override
    protected void setup() {
        title.setText("⚡ Ускорение ТВ");
        hint.setText("Нажми на пункт, чтобы применить. Всё обратимо.");
        List<Speed.Tweak> all = Speed.all();
        for (Speed.Tweak t : all) {
            Ui.Row r = new Ui.Row();
            r.mark = "›";
            r.name = t.title;
            r.sub = t.desc;
            r.tag = t;
            adapter.rows.add(r);
        }
        adapter.notifyDataSetChanged();
        list.requestFocus();
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) {
        Speed.Tweak t = (Speed.Tweak) r.tag;
        Ui.confirm(this, t.title.replaceAll("^[^\\p{L}]+", ""), t.desc, () ->
                adb.runAll(t.cmds, (ok, out) -> Ui.toast(this, (ok ? "✔ " : "⚠ ") + out)));
    }
}
