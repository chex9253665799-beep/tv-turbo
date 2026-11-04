package ru.chex.tvturbo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends BaseActivity implements Adb.Listener {

    private Adb adb;
    private TextView status, log, ram;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        adb = Adb.get(this);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        ram = findViewById(R.id.ram);
        logScroll = findViewById(R.id.logScroll);

        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnConnect).setOnLongClickListener(v -> { askTarget(); return true; });
        findViewById(R.id.btnDiag).setOnClickListener(v -> open(DiagActivity.class));
        findViewById(R.id.btnClean).setOnClickListener(v -> open(CleanActivity.class));
        findViewById(R.id.btnSpeed).setOnClickListener(v -> open(SpeedActivity.class));
        findViewById(R.id.btnApps).setOnClickListener(v -> open(AppsActivity.class));
        findViewById(R.id.btnInstall).setOnClickListener(v -> open(InstallActivity.class));
        findViewById(R.id.btnRestore).setOnClickListener(v -> restoreAll());
        findViewById(R.id.btnHelp).setOnClickListener(v -> Ui.info(this, "Как включить отладку", HELP));
        findViewById(R.id.btnScale).setOnClickListener(v -> chooseScale());
        findViewById(R.id.btnConnect).requestFocus();

        if (!adb.isConnected()) connect();
    }

    @Override protected void onResume() {
        super.onResume();
        adb.addListener(this);
        log.setText(adb.getLog());
        scrollLog();
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

    /** Долгое нажатие на «Подключиться» — сменить адрес/порт. */
    private void askTarget() {
        EditText e = new EditText(this);
        e.setText(adb.host() + ":" + adb.port());
        new android.app.AlertDialog.Builder(this)
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

    private void chooseScale() {
        new android.app.AlertDialog.Builder(this)
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
        if (cmds.isEmpty()) { Ui.info(this, "Вернуть всё", "Это приложение пока ничего не меняло."); return; }
        Ui.confirm(this, "Вернуть всё?", "Включить обратно всё, что было отключено или удалено через ТВ Турбо (" + Pkgs.changes(this).size() + " шт.).", () ->
                adb.runAll(cmds, (ok, out) -> {
                    Pkgs.clearChanges(this);
                    Ui.toast(this, out);
                }));
    }

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long free = mi.availMem / 1048576, total = mi.totalMem / 1048576;
        ram.setText("Память: свободно " + free + " из " + total + " МБ");
    }

    @Override public void onLog(String line) {
        log.append(line + "\n");
        scrollLog();
        updateRam();
    }

    @Override public void onState(boolean connected) {
        status.setText(connected ? "✔ Подключено к " + adb.host() + ":" + adb.port()
                : "✖ Не подключено (долгое нажатие на «Подключиться» — сменить адрес)");
        status.setTextColor(connected ? 0xFF3DDC84 : 0xFFFFB020);
    }

    private void scrollLog() { logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN)); }

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
            "Всё, что отключает приложение, можно вернуть кнопкой «Вернуть всё».";
}
