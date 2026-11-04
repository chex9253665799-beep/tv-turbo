package ru.chex.tvturbo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Подключение к отладке самого телевизора по сети (127.0.0.1:5555) и выполнение shell-команд.
 * Через ADB-shell у приложения появляются права, которых у обычного приложения нет:
 * отключать системные пакеты, менять глобальные настройки и т.д. Root не нужен.
 * Все команды выполняются по очереди в одном фоновом потоке.
 */
public final class Adb {

    public interface Callback { void done(boolean ok, String output); }
    public interface Listener { void onLog(String line); void onState(boolean connected); }

    public static final class Result {
        public final boolean ok; public final String out;
        Result(boolean ok, String out) { this.ok = ok; this.out = out; }
    }

    private static Adb instance;
    public static synchronized Adb get(Context c) {
        if (instance == null) instance = new Adb(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();
    private volatile Dadb dadb;

    private Adb(Context app) { this.app = app; }

    public boolean isConnected() { return dadb != null; }
    public String getLog() { synchronized (log) { return log.toString(); } }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public String host() { return prefs().getString("host", "127.0.0.1"); }
    public int port() { return prefs().getInt("port", 5555); }
    public void setTarget(String host, int port) { prefs().edit().putString("host", host).putInt("port", port).apply(); }

    private SharedPreferences prefs() { return app.getSharedPreferences("adb", Context.MODE_PRIVATE); }

    // ---------- подключение ----------

    public void connect(Callback cb) {
        worker.execute(() -> {
            String err = connectNow();
            post(cb, err == null, err == null ? "Подключено" : err);
        });
    }

    /** null = успех, иначе текст ошибки. Только из фонового потока. */
    private String connectNow() {
        closeQuietly();
        try {
            File priv = new File(app.getFilesDir(), "adbkey");
            File pub = new File(app.getFilesDir(), "adbkey.pub");
            if (!priv.isFile() || !pub.isFile()) AdbKeyPair.Companion.generate(priv, pub);
            AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
            logLine("Подключаюсь к " + host() + ":" + port() + " … (если на ТВ появился запрос «Разрешить отладку?» — отметь «Всегда» и нажми OK)");
            // socketTimeout 90 c: время на ответ пользователя в окне разрешения
            Dadb d = Dadb.Companion.create(host(), port(), keys, 5_000, 90_000);
            AdbShellResponse r = d.shell("echo ok");
            if (!r.getAllOutput().contains("ok")) throw new IllegalStateException("неожиданный ответ: " + r.getAllOutput());
            dadb = d;
            logLine("✔ Подключено");
            notifyState(true);
            return null;
        } catch (Throwable t) {
            dadb = null;
            notifyState(false);
            String msg = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            logLine("✖ Не удалось подключиться: " + msg);
            return "Не удалось подключиться.\n\n" + msg + "\n\nПроверь, что в «Для разработчиков» включена «Отладка по сети» (ADB по сети, порт 5555).";
        }
    }

    private void closeQuietly() {
        Dadb d = dadb;
        dadb = null;
        if (d != null) try { d.close(); } catch (Throwable ignored) { }
    }

    // ---------- команды ----------

    /** Синхронно, только из фонового потока. При обрыве — одно переподключение и повтор. */
    private Result execNow(String cmd) { return execNow(cmd, true); }

    /** Для фоновых задач (диагностика): выполнить и получить вывод. Только из потока worker. */
    public Result execSync(String cmd, boolean logIt) { return execNow(cmd, logIt); }

    /** Запустить задачу в очереди команд. */
    public void background(Runnable r) { worker.execute(r); }

    /** Выполнить в главном потоке. */
    public void ui(Runnable r) { main.post(r); }

    /** Синхронная установка APK. Только из потока worker. */
    public Result installSync(File apk) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (dadb == null && connectNow() != null) return new Result(false, "нет подключения");
            try {
                dadb.install(apk);
                logLine("✔ Установлено: " + apk.getName());
                return new Result(true, "Установлено");
            } catch (Throwable t) {
                String m = String.valueOf(t.getMessage());
                if (m.contains("INSTALL_") || m.contains("Failure")) { logLine("✖ Ошибка установки: " + m); return new Result(false, m); }
                closeQuietly();
            }
        }
        return new Result(false, "ошибка соединения");
    }

    /** Установить APK. */
    public void install(File apk, Callback cb) {
        worker.execute(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (dadb == null && connectNow() != null) { post(cb, false, "нет подключения"); return; }
                try {
                    logLine("Устанавливаю " + apk.getName() + " …");
                    dadb.install(apk);
                    logLine("✔ Установлено: " + apk.getName());
                    post(cb, true, "Установлено");
                    return;
                } catch (Throwable t) {
                    String m = String.valueOf(t.getMessage());
                    if (m.contains("INSTALL_") || m.contains("Failure")) {
                        logLine("✖ Ошибка установки: " + m);
                        post(cb, false, m);
                        return;
                    }
                    logLine("… соединение оборвалось, переподключаюсь");
                    closeQuietly();
                }
            }
            post(cb, false, "ошибка соединения");
        });
    }

    private Result execNow(String cmd, boolean logIt) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (dadb == null) {
                String err = connectNow();
                if (err != null) return new Result(false, "нет подключения");
            }
            try {
                AdbShellResponse r = dadb.shell(cmd);
                String out = r.getAllOutput().trim();
                boolean ok = r.getExitCode() == 0
                        && !out.contains("Exception") && !out.startsWith("Error") && !out.contains("Failure")
                        && !out.contains("Unknown package");
                if (logIt) logLine((ok ? "$ " : "✖ ") + cmd + (out.isEmpty() ? "" : "\n   " + out.replace("\n", "\n   ")));
                return new Result(ok, out);
            } catch (Throwable t) {
                logLine("… соединение оборвалось, переподключаюсь");
                closeQuietly();
                notifyState(false);
            }
        }
        return new Result(false, "ошибка соединения");
    }

    public void run(String cmd, Callback cb) {
        worker.execute(() -> {
            Result r = execNow(cmd);
            post(cb, r.ok, r.out);
        });
    }

    /** Выполнить список команд по очереди. В ответе — сколько прошло успешно. */
    public void runAll(List<String> cmds, Callback cb) {
        worker.execute(() -> {
            int ok = 0;
            for (String c : cmds) if (execNow(c).ok) ok++;
            post(cb, ok == cmds.size(), "Готово: " + ok + " из " + cmds.size());
        });
    }

    // ---------- служебное ----------

    private void post(Callback cb, boolean ok, String out) {
        if (cb != null) main.post(() -> cb.done(ok, out));
    }

    private void notifyState(boolean connected) {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onState(connected); });
    }

    void logLine(String line) {
        synchronized (log) {
            log.append(line).append('\n');
            if (log.length() > 20_000) log.delete(0, log.length() - 15_000);
        }
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onLog(line); });
    }
}
