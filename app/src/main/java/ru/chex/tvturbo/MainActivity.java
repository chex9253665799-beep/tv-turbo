package ru.chex.tvturbo;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends BaseActivity implements Adb.Listener {

    private Adb adb;
    private TextView status, ram, ramHint;
    private ProgressBar ramBar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        adb = Adb.get(this);
        status = findViewById(R.id.status);
        ram = findViewById(R.id.ram);
        ramHint = findViewById(R.id.ramHint);
        ramBar = findViewById(R.id.ramBar);
        ((TextView) findViewById(R.id.model)).setText("📺  " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE);

        Button diag = findViewById(R.id.btnDiag);
        diag.setOnClickListener(v -> open(DiagActivity.class));
        zoomOnFocus(diag, 1.04f);

        LinearLayout g1 = findViewById(R.id.grid1), g2 = findViewById(R.id.grid2);
        View auto = tile(g1, "⚡", "Авто-режим", "Всё сразу в одно нажатие", 0xFFFFB020, 0xFFFF5A5F, () -> open(AutoActivity.class));
        tile(g1, "▶", "YouTube", "Без тормозов в России", 0xFFFF3B30, 0xFFB00020, () -> open(YouTubeActivity.class));
        tile(g1, "🎨", "Главный экран", "Красивые лаунчеры с превью", 0xFF00B8D9, 0xFF7B4DFF, () -> open(LauncherActivity.class));
        tile(g1, "🧹", "Очистка", "Реклама, телеметрия, мусор", 0xFF3DDC84, 0xFF14B8A6, () -> open(CleanActivity.class));
        tile(g2, "🚀", "Ускорение", "Анимации, кэш, память, DNS", 0xFF2E6BFF, 0xFF7B4DFF, () -> open(SpeedActivity.class));
        tile(g2, "⬇", "Установка", "RuStore и полезное", 0xFFE040FB, 0xFF7B4DFF, () -> open(InstallActivity.class));
        tile(g2, "📦", "Приложения", "Отключить, удалить, фон", 0xFF00B8D9, 0xFF2E6BFF, () -> open(AppsActivity.class));
        tile(g2, "↩", "Вернуть всё", "Откатить изменения", 0xFF8A93A6, 0xFF4B5563, this::restoreAll);

        LinearLayout bottom = findViewById(R.id.bottom);
        small(bottom, "🔌  Подключиться", this::connect).setOnLongClickListener(v -> { askTarget(); return true; });
        small(bottom, "🛡  Страж", this::guardDialog);
        small(bottom, "📜  Журнал", this::showLog);
        small(bottom, "🔍  Размер интерфейса", this::chooseScale);
        small(bottom, "🔄  Обновления · v" + Updater.currentName(this), this::updatesDialog);
        small(bottom, "❓  Помощь", () -> Ui.info(this, "Как включить отладку", HELP));

        if (Guard.enabled(this)) Guard.schedule(this);
        auto.requestFocus();
        if (!adb.isConnected()) connect();
        else Updater.checkAndInstall(this, false, this::updateStatus);
    }

    // ---------- плитки ----------

    private View tile(LinearLayout row, String icon, String title, String sub, int c1, int c2, Runnable click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackgroundResource(R.drawable.tile_bg);
        t.setFocusable(true);
        t.setClickable(true);
        int p = Ui.dp(this, 14);
        t.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(this, 12));
        t.setLayoutParams(lp);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        circle.setShape(GradientDrawable.OVAL);
        ic.setBackground(circle);
        int s = Ui.dp(this, 46);
        t.addView(ic, new LinearLayout.LayoutParams(s, s));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.setMarginStart(Ui.dp(this, 12));
        t.addView(col, clp);

        TextView tt = new TextView(this);
        tt.setText(title);
        tt.setTextSize(18);
        tt.setTextColor(0xFFECEFF4);
        tt.setTypeface(tt.getTypeface(), android.graphics.Typeface.BOLD);
        tt.setSingleLine(true);
        col.addView(tt);

        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(13);
        st.setTextColor(0xFF8A93A6);
        st.setMaxLines(2);
        col.addView(st);

        t.setOnClickListener(v -> click.run());
        zoomOnFocus(t, 1.05f);
        row.addView(t);
        return t;
    }

    private Button small(LinearLayout row, String text, Runnable click) {
        Button b = new Button(this, null, 0, R.style.BigButton);
        b.setText(text);
        b.setTextSize(15);
        int p = Ui.dp(this, 10);
        b.setPadding(Ui.dp(this, 18), p, Ui.dp(this, 18), p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 10));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> click.run());
        row.addView(b);
        return b;
    }

    /** Плавное увеличение при наведении пультом. */
    static void zoomOnFocus(View v, float scale) {
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(150).start();
            view.setElevation(has ? 12f : 0f);
        });
    }

    // ---------- действия ----------

    @Override protected void onResume() {
        super.onResume();
        adb.addListener(this);
        onState(adb.isConnected());
        updateRam();
    }

    @Override protected void onPause() { super.onPause(); adb.removeListener(this); }

    private void open(Class<?> c) {
        if (!Ui.needAdb(this)) return;
        startActivity(new Intent(this, c));
    }

    private void connect() {
        status.setText("Подключаюсь…");
        adb.connect((ok, out) -> {
            onState(ok);
            if (!ok) Ui.info(this, "Нет подключения", out + "\n\n" + HELP_SHORT);
            else {
                // чтобы ТВ не усыплял «Стража» и разрешал ему работать в фоне
                adb.run("dumpsys deviceidle whitelist +" + getPackageName() + "; cmd appops set " + getPackageName() + " RUN_ANY_IN_BACKGROUND allow", null);
                Updater.checkAndInstall(this, false, this::updateStatus);
            }
        });
    }

    private void guardDialog() {
        boolean on = Guard.enabled(this), boot = Guard.openOnBoot(this);
        long last = Guard.lastRun(this);
        String when = last == 0 ? "ещё не было" : new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(new java.util.Date(last));
        String msg = "Страж сам запускается при включении ТВ и каждые 30 минут: возвращает всё, что слетело, и следит за памятью.\n\n"
                + "Последняя проверка: " + when + " — " + Guard.lastResult(this) + "\n\n"
                + "Что он охраняет:\n" + Profile.summary(this);
        new AlertDialog.Builder(this)
                .setTitle("🛡 Страж — " + (on ? "включён" : "выключен"))
                .setMessage(msg + "\n\nОткрывать ТВ Турбо при включении ТВ: " + (boot ? "да" : "нет"))
                .setPositiveButton("Проверить сейчас", (d, w) -> {
                    if (!Ui.needAdb(this)) return;
                    Ui.toast(this, "Проверяю…");
                    adb.background(() -> {
                        String r = Guard.check(this, false);
                        adb.ui(() -> { updateStatus("🛡 " + r); Ui.info(this, "🛡 Страж", r); });
                    });
                })
                .setNeutralButton(on ? "Выключить стража" : "Включить стража", (d, w) -> {
                    Guard.setEnabled(this, !on);
                    Ui.toast(this, !on ? "Страж включён" : "Страж выключен");
                })
                .setNegativeButton(boot ? "Не открывать при вкл." : "Открывать при вкл. ТВ", (d, w) -> {
                    Guard.setOpenOnBoot(this, !boot);
                    Ui.toast(this, !boot ? "Будет открываться при включении ТВ" : "Не будет открываться при включении");
                })
                .show();
    }

    private void updateStatus(String text) {
        status.setText(text);
        status.setTextColor(0xFF7FB2FF);
    }

    private void updatesDialog() {
        boolean auto = Updater.isAuto(this);
        String[] items = {
                "🔄  Проверить сейчас",
                (auto ? "☑" : "☐") + "  Обновлять автоматически при запуске"
        };
        new AlertDialog.Builder(this)
                .setTitle("Версия " + Updater.currentName(this))
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        if (!Ui.needAdb(this)) return;
                        Ui.toast(this, "Проверяю…");
                        Updater.checkAndInstall(this, true, t -> { updateStatus(t); Ui.toast(this, t); });
                    } else {
                        Updater.setAuto(this, !auto);
                        Ui.toast(this, !auto ? "Автообновление включено" : "Автообновление выключено");
                    }
                })
                .setNegativeButton("Закрыть", null).show();
    }

    private void askTarget() {
        EditText e = new EditText(this);
        e.setText(adb.host() + ":" + adb.port());
        new AlertDialog.Builder(this)
                .setTitle("Адрес отладки (обычно 127.0.0.1:5555)")
                .setView(e)
                .setPositiveButton("Подключиться", (d, w) -> {
                    String s = e.getText().toString().trim();
                    int i = s.lastIndexOf(':');
                    try {
                        adb.setTarget(i > 0 ? s.substring(0, i) : s, i > 0 ? Integer.parseInt(s.substring(i + 1)) : 5555);
                        connect();
                    } catch (NumberFormatException ex) { Ui.toast(this, "Неверный порт"); }
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void showLog() {
        TextView t = new TextView(this);
        t.setText(adb.getLog().isEmpty() ? "Пока пусто" : adb.getLog());
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextColor(0xFFB8C0D0);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this).setTitle("Журнал команд").setView(sv).setPositiveButton("Закрыть", null).show();
    }

    private void chooseScale() {
        new AlertDialog.Builder(this)
                .setTitle("Размер интерфейса")
                .setSingleChoiceItems(BaseActivity.SCALE_NAMES, BaseActivity.scaleIndex(this), (d, which) -> {
                    BaseActivity.setScaleIndex(this, which);
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    private void restoreAll() {
        if (!Ui.needAdb(this)) return;
        List<Pkgs.P> ps = Pkgs.restorable(this);
        List<String> cmds = Pkgs.restoreCommandsFor(ps);
        for (String e : Pkgs.changes(this)) if (e.startsWith("b:")) cmds.add(Pkgs.cmdAllowBackground(e.substring(2)));
        if (cmds.isEmpty()) { Ui.info(this, "Вернуть всё", "Отключённых или удалённых приложений нет — возвращать нечего."); return; }

        StringBuilder names = new StringBuilder();
        int n = 0;
        for (Pkgs.P p : ps) {
            if (n++ < 12) names.append("• ").append(p.label).append(p.installed ? "" : " (удалено)").append('\n');
        }
        if (ps.size() > 12) names.append("…и ещё ").append(ps.size() - 12).append('\n');

        String[] items = {"Вернуть всё (" + ps.size() + " шт.)", "Выбрать, что вернуть"};
        new AlertDialog.Builder(this)
                .setTitle("Вернуть отключённое")
                .setMessage(names.toString() + "\nАнимации, DNS и YouTube-обход возвращаются в «Ускорении» и «YouTube».")
                .setPositiveButton(items[0], (d, w) -> adb.runAll(cmds, (ok, out) -> { Pkgs.clearChanges(this); Profile.clearPackages(this); Ui.toast(this, out); }))
                .setNeutralButton(items[1], (d, w) -> open(AppsActivity.class, true))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void open(Class<?> c, boolean disabledOnly) {
        if (!Ui.needAdb(this)) return;
        startActivity(new Intent(this, c).putExtra("disabled_only", disabledOnly));
    }

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long free = mi.availMem / 1048576, total = mi.totalMem / 1048576;
        int freePct = (int) (100 * free / Math.max(1, total));
        ram.setText("Свободно " + free + " МБ из " + total + " МБ");
        ramBar.setProgress(100 - freePct);
        ramHint.setText(freePct < 20 ? "🔴 Очень мало памяти — ТВ будет тормозить. Запусти «Авто-режим»."
                : freePct < 35 ? "🔶 Памяти маловато. Стоит почистить."
                : "✅ Памяти достаточно.");
    }

    @Override public void onLog(String line) { updateRam(); }

    @Override public void onState(boolean connected) {
        status.setText(connected ? "● Подключено к ТВ" : "● Не подключено — нажми «Подключиться»");
        status.setTextColor(connected ? 0xFF3DDC84 : 0xFFFFB020);
    }

    static final String HELP_SHORT =
            "Настройки → Настройки устройства → Об устройстве → 7 раз нажми «Сборка».\n" +
            "Затем «Для разработчиков» → включи «Отладка по USB» и «Отладка по сети» (ADB по сети).";

    static final String HELP =
            "1. Настройки → Настройки устройства → Об устройстве.\n" +
            "2. Нажми 7 раз на «Сборка» — появится «Вы стали разработчиком».\n" +
            "3. Назад → «Для разработчиков».\n" +
            "4. Включи «Отладка по USB» и, если есть, «Отладка по сети» / «ADB по сети».\n" +
            "5. Вернись в ТВ Турбо и нажми «Подключиться».\n" +
            "6. На экране появится «Разрешить отладку?» — отметь «Всегда разрешать» и нажми OK.\n\n" +
            "Если пункта «Отладка по сети» нет: один раз подключи ТВ к компьютеру и выполни\n" +
            "adb tcpip 5555\n" +
            "— после этого приложение подключится само (до перезагрузки ТВ).\n\n" +
            "Подключиться (долгое нажатие) — сменить адрес.\n" +
            "Всё, что отключает приложение, можно вернуть кнопкой «Вернуть всё».";
}
