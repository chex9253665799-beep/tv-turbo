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
        tile(g1, "🧹", "Очистка", "Реклама, телеметрия, мусор", 0xFF3DDC84, 0xFF14B8A6, () -> open(CleanActivity.class));
        tile(g1, "🚀", "Ускорение", "Анимации, кэш, память, DNS", 0xFF2E6BFF, 0xFF7B4DFF, () -> open(SpeedActivity.class));
        tile(g2, "⬇", "Установка", "RuStore, лаунчеры, полезное", 0xFFE040FB, 0xFF7B4DFF, () -> open(InstallActivity.class));
        tile(g2, "📦", "Приложения", "Отключить, удалить, запретить фон", 0xFF00B8D9, 0xFF2E6BFF, () -> open(AppsActivity.class));
        tile(g2, "↩", "Вернуть всё", "Откатить любые изменения", 0xFF8A93A6, 0xFF4B5563, this::restoreAll);

        LinearLayout bottom = findViewById(R.id.bottom);
        small(bottom, "🔌  Подключиться", this::connect).setOnLongClickListener(v -> { askTarget(); return true; });
        small(bottom, "📜  Журнал", this::showLog);
        small(bottom, "🔍  Размер интерфейса", this::chooseScale);
        small(bottom, "❓  Как включить отладку", () -> Ui.info(this, "Как включить отладку", HELP));

        auto.requestFocus();
        if (!adb.isConnected()) connect();
    }

    // ---------- плитки ----------

    private View tile(LinearLayout row, String icon, String title, String sub, int c1, int c2, Runnable click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackgroundResource(R.drawable.tile_bg);
        t.setFocusable(true);
        t.setClickable(true);
        int p = Ui.dp(this, 20);
        t.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(this, 14));
        t.setLayoutParams(lp);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(26);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        circle.setShape(GradientDrawable.OVAL);
        ic.setBackground(circle);
        int s = Ui.dp(this, 56);
        t.addView(ic, new LinearLayout.LayoutParams(s, s));

        TextView tt = new TextView(this);
        tt.setText(title);
        tt.setTextSize(21);
        tt.setTextColor(0xFFECEFF4);
        tt.setTypeface(tt.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 12);
        t.addView(tt, tlp);

        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(13);
        st.setTextColor(0xFF8A93A6);
        t.addView(st);

        t.setOnClickListener(v -> click.run());
        zoomOnFocus(t, 1.06f);
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
        });
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
        List<String> cmds = Pkgs.restoreCommands(this);
        if (cmds.isEmpty()) { Ui.info(this, "Вернуть всё", "Это приложение пока ничего не отключало."); return; }
        Ui.confirm(this, "Вернуть всё?", "Включить обратно всё, что было отключено или удалено через ТВ Турбо (" + Pkgs.changes(this).size() + " шт.).\n"
                + "Анимации и DNS возвращаются в «Ускорении».", () ->
                adb.runAll(cmds, (ok, out) -> { Pkgs.clearChanges(this); Ui.toast(this, out); }));
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
                : freePct < 35 ? "🟡 Памяти маловато. Стоит почистить."
                : "🟢 Памяти достаточно.");
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
