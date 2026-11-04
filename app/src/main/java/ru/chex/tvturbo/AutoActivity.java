package ru.chex.tvturbo;

import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Авто-режим: одна кнопка — ускорение, блокировка рекламы, отключение мусора,
 * очистка, установка лаунчера без рекламы и назначение его главным экраном.
 * Всё записывается в журнал изменений и откатывается кнопкой «Вернуть всё».
 */
public class AutoActivity extends ListScreen {

    private static final String[] STEPS = {
            "Ускорить анимации (0.5×)",
            "Включить блокировку рекламы (AdGuard DNS)",
            "Отключить рекламу, телеметрию и мусор",
            "Выключить заставку и фоновый поиск Wi-Fi/BT",
            "Скачать красивый лаунчер без рекламы",  // имя лаунчера подставляется в onResume
            "Сделать его главным экраном",
            "Настроить YouTube: обход замедления + SmartTube",
            "Очистить кэш и освободить память"
    };

    private boolean running;

    @Override
    protected void setup() {
        title.setText("⚡ Авто-режим");
        hint.setText("Сделаю всё сам: ускорю ТВ, уберу рекламу и мусор, поставлю красивый главный экран.\n"
                + "Трогаю только безопасное. Всё можно вернуть кнопкой «Вернуть всё».");
        // сначала — выбор лаунчера с картинками, потом сразу запуск
        Ui.action(this, actions, "⚡  Запустить", v -> {
            if (running || !Ui.needAdb(this)) return;
            startActivityForResult(new Intent(this, LauncherActivity.class)
                    .putExtra(LauncherActivity.EXTRA_CHOOSE_ONLY, true), REQ_LAUNCHER);
        });
        Ui.action(this, actions, "Без смены лаунчера", v -> start(false));
        for (String s : STEPS) {
            Ui.Row r = new Ui.Row();
            r.mark = "○";
            r.name = s;
            adapter.rows.add(r);
        }
        adapter.notifyDataSetChanged();
        actions.getChildAt(0).requestFocus();
    }

    private static final int REQ_LAUNCHER = 7;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LAUNCHER && res == RESULT_OK) start(true);
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) { }

    @Override
    protected void onResume() {
        super.onResume();
        if (!running && adapter.rows.size() > 4) {
            adapter.rows.get(4).name = "Поставить лаунчер без рекламы: " + Launchers.chosen(this).name;
            adapter.notifyDataSetChanged();
        }
    }

    private void mark(int i, String mark, String badge, int color, String sub) {
        adb.ui(() -> {
            Ui.Row r = adapter.rows.get(i);
            r.mark = mark; r.badge = badge; r.badgeColor = color;
            if (sub != null) r.sub = sub;
            adapter.notifyDataSetChanged();
            list.setSelection(i);
        });
    }

    private void start(boolean withLauncher) {
        if (running) return;
        if (!Ui.needAdb(this)) return;
        running = true;
        hint.setText("Работаю… Не выключай ТВ.");
        adb.background(() -> {
            int done = 0;

            // 1. анимации
            mark(0, "⏳", "", 0, null);
            done += step(0, Speed.animations("0.5"), null);

            // 2. DNS
            mark(1, "⏳", "", 0, null);
            if (android.os.Build.VERSION.SDK_INT >= 28) done += step(1, Speed.adblockDns(true), null);
            else mark(1, "–", "нужен Android 9+", 0xFF8A93A6, null);

            // 3. мусор
            mark(2, "⏳", "", 0, null);
            Map<String, Pkgs.P> pkgs = Pkgs.map(this);
            String home = Pkgs.currentHome(this);
            List<String> cmds = new ArrayList<>();
            for (Bloat.Item i : Bloat.KNOWN) {
                Pkgs.P p = pkgs.get(i.pkg);
                if (p == null || !p.installed || !p.enabled || i.level != Bloat.SAFE || i.pkg.equals(home)) continue;
                cmds.add(Pkgs.cmdDisable(i.pkg));
                Pkgs.remember(this, 'd', i.pkg);
            }
            if (cmds.isEmpty()) { mark(2, "✔", "уже чисто", 0xFF3DDC84, null); done++; }
            else done += step(2, cmds, "отключено пакетов: " + cmds.size());

            // 4. заставка и сканирование
            mark(3, "⏳", "", 0, null);
            List<String> misc = new ArrayList<>();
            misc.add("settings put secure screensaver_enabled 0");
            misc.add("settings put global wifi_scan_always_enabled 0");
            misc.add("settings put global ble_scan_always_enabled 0");
            done += step(3, misc, null);

            // 5–6. лаунчер
            if (withLauncher) done += launcher();
            else { mark(4, "–", "пропущено", 0xFF8A93A6, null); mark(5, "–", "пропущено", 0xFF8A93A6, null); }

            // 7. YouTube
            mark(6, "⏳", "", 0, null);
            boolean yt = YouTube.setup(this, adb, (st, state, text) ->
                    mark(6, "⏳", (st == 0 ? "ByeByeDPI: " : st == 1 ? "SmartTube: " : "") + (text.length() > 30 ? text.substring(0, 30) : text), 0xFF8A93A6, null));
            if (yt) { mark(6, "✔", "YouTube работает", 0xFF3DDC84, null); done++; }
            else mark(6, "⚠", "частично — открой «YouTube»", 0xFFFFB020, null);

            // 8. кэш и память
            mark(7, "⏳", "", 0, null);
            List<String> clean = new ArrayList<>();
            clean.add("pm trim-caches 999G");
            clean.add("am kill-all");
            done += step(7, clean, null);

            int total = withLauncher ? STEPS.length : STEPS.length - 2;
            int d = done;
            adb.ui(() -> {
                running = false;
                hint.setText(d >= total ? "✔ Готово! ТВ ускорен, реклама убрана. Нажми «Домой», чтобы увидеть новый главный экран."
                        : "Готово с замечаниями: выполнено " + d + " из " + total + ". Подробности — в журнале на главном экране.");
            });
        });
    }

    /** Выполнить команды шага; 1 = успех. Вызывается в потоке worker. */
    private int step(int i, List<String> cmds, String okText) {
        int ok = 0;
        for (String c : cmds) if (adb.execSync(c, true).ok) ok++;
        boolean good = ok == cmds.size();
        mark(i, good ? "✔" : "⚠", good ? (okText == null ? "готово" : okText) : ok + " из " + cmds.size(),
                good ? 0xFF3DDC84 : 0xFFFFB020, null);
        return good ? 1 : 0;
    }

    /** Скачать, установить и назначить выбранный лаунчер. Возвращает число успешных шагов (0–2). */
    private int launcher() {
        Launchers.L l = Launchers.chosen(this);
        mark(4, "⏳", "ищу " + l.name, 0xFF8A93A6, null);
        String res = LauncherActivity.installAndSetHome(this, adb, l, t -> mark(4, "⏳", t.length() > 40 ? t.substring(0, 40) + "…" : t, 0xFF8A93A6, null));
        if (res.startsWith("✖")) {
            mark(4, "✖", "не получилось", 0xFFFF5A5F, res);
            mark(5, "–", "пропущено", 0xFF8A93A6, null);
            return 0;
        }
        mark(4, "✔", l.name + " установлен", 0xFF3DDC84, null);
        if (res.contains("стал главным")) { mark(5, "✔", "готово", 0xFF3DDC84, null); return 2; }
        mark(5, "⚠", "выбери вручную", 0xFFFFB020, "Нажми «Домой» и выбери " + l.name + " → «Всегда»");
        return 1;
    }

    private Set<String> homePackages() {
        Set<String> s = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0))
            if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
        return s;
    }
}
