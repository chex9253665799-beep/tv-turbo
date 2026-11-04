package ru.chex.tvturbo;

import android.widget.Button;

/** Экран «YouTube в России»: настройка под ключ, смена стратегии, выключение. */
public class YouTubeActivity extends ListScreen {

    private static final String[] STEPS = {
            "Установить «ТВ Турбо Обход» — только для YouTube",
            "Убрать старый обход на весь ТВ (если был)",
            "Выдать разрешение VPN (без окон)",
            "Включить обход",
            "Включать обход сам после перезагрузки"
    };

    private boolean busy;
    private Button nextBtn;

    @Override
    protected void setup() {
        title.setText("▶ YouTube в России");
        hint.setText("Обход работает ТОЛЬКО для YouTube — банки, Кинопоиск, Wink и другие российские приложения "
                + "идут напрямую и VPN не видят. Обход включается сам, в том числе после перезагрузки ТВ.");
        Ui.action(this, actions, "▶  Настроить всё", v -> runSetup());
        nextBtn = Ui.action(this, actions, "", v -> nextStrategy());
        Ui.action(this, actions, "📺  Открыть YouTube", v -> adb.run(
                "monkey -p " + YouTube.YOUTUBE_PKG + " -c android.intent.category.LEANBACK_LAUNCHER 1", null));
        Ui.action(this, actions, "⏹  Выключить обход", v -> Ui.confirm(this, "Выключить обход?",
                "YouTube снова будет тормозить.", () -> adb.background(() -> {
                    YouTube.stop(this, adb);
                    adb.ui(() -> Ui.toast(this, "Обход выключен"));
                })));
        updateNext();
        for (String s : STEPS) {
            Ui.Row r = new Ui.Row();
            r.mark = "○";
            r.name = s;
            adapter.rows.add(r);
        }
        adapter.notifyDataSetChanged();
        actions.getChildAt(0).requestFocus();
        if (getIntent().getBooleanExtra("autostart", false)) runSetup();
    }

    private void updateNext() {
        nextBtn.setText("🔁  Тормозит? Другая стратегия (" + (YouTube.strategy(this) + 1) + "/" + YouTube.STRATEGIES.length + ")");
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) { }

    private void mark(int i, String state, String text) {
        adb.ui(() -> {
            Ui.Row r = adapter.rows.get(i);
            switch (state) {
                case "ok": r.mark = "✔"; r.badgeColor = 0xFF3DDC84; break;
                case "warn": r.mark = "⚠"; r.badgeColor = 0xFFFFB020; break;
                case "bad": r.mark = "✖"; r.badgeColor = 0xFFFF5A5F; break;
                default: r.mark = "⏳"; r.badgeColor = 0xFF8A93A6;
            }
            r.badge = text.length() > 45 ? text.substring(0, 45) + "…" : text;
            if (state.equals("bad") || state.equals("warn")) r.sub = text;
            adapter.notifyDataSetChanged();
        });
    }

    private void runSetup() {
        if (busy || !Ui.needAdb(this)) return;
        busy = true;
        hint.setText("Настраиваю… Установка может занять пару минут, не выключай ТВ.");
        adb.background(() -> {
            boolean ok = YouTube.setup(this, adb, this::mark);
            adb.ui(() -> {
                busy = false;
                hint.setText(ok ? "✔ Готово! Открывай YouTube. Если видео всё равно тормозит — нажми «Другая стратегия»."
                        : "Готово с замечаниями — смотри пункты с ⚠/✖.");
            });
        });
    }

    private void nextStrategy() {
        if (busy || !Ui.needAdb(this)) return;
        if (!YouTube.installed(this, YouTube.DPI_PKG)) { Ui.toast(this, "Сначала нажми «Настроить всё»"); return; }
        busy = true;
        int next = (YouTube.strategy(this) + 1) % YouTube.STRATEGIES.length;
        hint.setText("Переключаю на стратегию " + (next + 1) + "…");
        adb.background(() -> {
            boolean ok = YouTube.start(this, adb, next);
            adb.ui(() -> {
                busy = false;
                updateNext();
                hint.setText(ok ? "✔ Стратегия " + (next + 1) + " включена. Проверь видео в YouTube; если тормозит — нажми ещё раз."
                        : "⚠ Обход не запустился — нажми «Настроить всё».");
            });
        });
    }
}
