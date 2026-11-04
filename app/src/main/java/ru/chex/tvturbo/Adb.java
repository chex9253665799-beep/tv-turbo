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
    public String getLog() { synchronized (log) { if (log.length() == 0) loadLog(); return log.toString(); } }

    // журнал хранится в файле — переживает перезагрузку ТВ
    private File logFile() { return new File(app.getFilesDir(), "journal.txt"); }

    private void loadLog() {
        try (java.io.FileInputStream in = new java.io.FileInputStream(logFile())) {
            byte[] b = new byte[(int) Math.min(logFile().length(), 60_000)];
            int n = in.read(b);
            if (n > 0) log.append(new String(b, 0, n, "UTF-8"));
        } catch (Throwable ignored) { }
    }

    private void saveLog() {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(logFile())) { o.write(log.toString().getBytes("UTF-8")); }
        catch (Throwable ignored) { }
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public String host() { return prefs().getString("host", "127.0.0.1"); }
    public int port() { return prefs().getInt("port", 5555); }
    public void setTarget(String host, int port) { prefs().edit().putString("host", host).putInt("port", port).apply(); }

    private SharedPreferences prefs() { return app.getSharedPreferences("adb", Context.MODE_PRIVATE); }

    // ---------- подключение ----------

    /** Подключение с повторами (после включения ТВ отладка поднимается не сразу). Только из worker. */
    public boolean connectWithRetry(int attempts, int pauseMs) {
        for (int i = 0; i < attempts; i++) {
            if (dadb != null || connectNow() == null) return true;
            try { Thread.sleep(pauseMs); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

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

    /**
     * Синхронная установка APK. Только из потока worker.
     * Отдельное соединение без тайм-аута: большой APK (20+ МБ) телевизор ставит минутами,
     * и обычное соединение с тайм-аутом 90 c обрывалось («ошибка соединения»).
     * Файл кладём в /data/local/tmp и ставим через pm — так надёжнее на любых прошивках.
     */
    public Result installSync(File apk) {
        if (dadb == null && connectNow() != null) return new Result(false, "нет подключения");
        String remote = "/data/local/tmp/tvturbo_" + System.currentTimeMillis() + ".apk";
        Dadb d = null;
        try {
            File priv = new File(app.getFilesDir(), "adbkey");
            File pub = new File(app.getFilesDir(), "adbkey.pub");
            AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
            d = Dadb.Companion.create(host(), port(), keys, 10_000, 0);
            logLine("Передаю " + apk.getName() + " (" + (apk.length() / 1048576) + " МБ) …");
            d.push(apk, remote, 420 /* 0644 */, System.currentTimeMillis());
            logLine("Устанавливаю … (на слабых ТВ это может занять пару минут)");
            AdbShellResponse r = d.shell("pm install -r -g " + remote + " 2>&1 || pm install -r " + remote + " 2>&1; rm -f " + remote);
            String out = r.getAllOutput().trim();
            if (out.contains("Success")) { logLine("✔ Установлено: " + apk.getName()); return new Result(true, "Установлено"); }
            logLine("✖ Ошибка установки: " + out);
            return new Result(false, out.isEmpty() ? "неизвестная ошибка" : out);
        } catch (Throwable t) {
            String m = t.getClass().getSimpleName() + ": " + t.getMessage();
            logLine("✖ Ошибка установки: " + m);
            return new Result(false, m);
        } finally {
            if (d != null) try { d.close(); } catch (Throwable ignored) { }
        }
    }

    /** Установить APK (асинхронно). */
    public void install(File apk, Callback cb) {
        worker.execute(() -> {
            Result r = installSync(apk);
            post(cb, r.ok, r.out);
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
                if (ok) try { Profile.observe(app, cmd); } catch (Throwable ignored) { }
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
        String stamped = new java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + line;
        synchronized (log) {
            if (log.length() == 0) loadLog();
            log.append(stamped).append('\n');
            if (log.length() > 60_000) log.delete(0, log.length() - 40_000);
            saveLog();
        }
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onLog(line); });
    }
}
