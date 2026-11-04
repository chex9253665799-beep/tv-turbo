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

    /** Лаунчер, который ставит авто-режим (открытый код, без рекламы). */
    static final String LAUNCHER_REPO = "leanbitlab-org/LtvLauncher";
    static final String LAUNCHER_ASSET = "universal";
    static final String LAUNCHER_NAME = "LTvLauncher";

    private static final String[] STEPS = {
            "Ускорить анимации (0.5×)",
            "Включить блокировку рекламы (AdGuard DNS)",
            "Отключить рекламу, телеметрию и мусор",
            "Выключить заставку и фоновый поиск Wi-Fi/BT",
            "Скачать красивый лаунчер без рекламы",
            "Сделать его главным экраном",
            "Очистить кэш и освободить память"
    };

    private boolean running;

    @Override
    protected void setup() {
        title.setText("⚡ Авто-режим");
        hint.setText("Сделаю всё сам: ускорю ТВ, уберу рекламу и мусор, поставлю красивый главный экран.\n"
                + "Трогаю только безопасное. Всё можно вернуть кнопкой «Вернуть всё».");
        Ui.action(this, actions, "⚡  Запустить", v -> start(true));
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

    @Override
    protected void onRowClick(Ui.Row r, int pos) { }

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

            // 7. кэш и память
            mark(6, "⏳", "", 0, null);
            List<String> clean = new ArrayList<>();
            clean.add("pm trim-caches 999G");
            clean.add("am kill-all");
            done += step(6, clean, null);

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

    /** Скачать, установить и назначить лаунчер. Возвращает число успешных шагов (0–2). */
    private int launcher() {
        mark(4, "⏳", "ищу последнюю версию", 0xFF8A93A6, null);
        Set<String> before = homePackages();
        File f = new File(getCacheDir(), "launcher.apk");
        try {
            String url = InstallActivity.latestGithubApk(LAUNCHER_REPO, LAUNCHER_ASSET, "");
            byte[] data = InstallActivity.download(url, p -> mark(4, "⏳", "скачиваю " + p + "%", 0xFF8A93A6, null));
            try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
        } catch (Throwable t) {
            mark(4, "✖", "не скачалось", 0xFFFF5A5F, String.valueOf(t.getMessage()));
            mark(5, "–", "пропущено", 0xFF8A93A6, null);
            return 0;
        }
        mark(4, "⏳", "устанавливаю", 0xFF8A93A6, null);
        android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
        String newPkg = pi != null ? pi.packageName : null;
        Adb.Result r = adb.installSync(f);
        f.delete();
        if (!r.ok) {
            mark(4, "✖", "ошибка установки", 0xFFFF5A5F, r.out);
            mark(5, "–", "пропущено", 0xFF8A93A6, null);
            return 0;
        }
        mark(4, "✔", LAUNCHER_NAME + " установлен", 0xFF3DDC84, null);

        // найти новый главный экран и назначить его
        mark(5, "⏳", "", 0, null);
        String comp = null;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            boolean match = newPkg != null ? pkg.equals(newPkg) : (!before.contains(pkg) && !"android".equals(pkg));
            if (match) { comp = pkg + "/" + ri.activityInfo.name; break; }
        }
        if (comp == null) {
            mark(5, "⚠", "выбери вручную", 0xFFFFB020, "Нажми «Домой» и выбери " + LAUNCHER_NAME + " → «Всегда»");
            return 1;
        }
        Adb.Result h = adb.execSync("cmd package set-home-activity " + comp, true);
        if (h.ok) { mark(5, "✔", "готово", 0xFF3DDC84, null); return 2; }
        mark(5, "⚠", "выбери вручную", 0xFFFFB020, "Нажми «Домой» и выбери " + LAUNCHER_NAME + " → «Всегда»");
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
