package ru.chex.tvturbo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

/**
 * Лёгкий «сторож пробуждения». Кнопка питания на пульте не выключает Android TV, а усыпляет —
 * при пробуждении система НЕ шлёт сигнал загрузки, и Страж не узнал бы, что ТВ включили.
 * Этот сервис ловит «экран включился» и через 10 секунд запускает Стража (с перезапуском обхода YouTube).
 * Висит в фоне как служба переднего плана — на ТВ её уведомление не видно.
 */
public class WatchService extends Service {

    private static final String CH = "guard";
    private BroadcastReceiver screen;
    private long lastWake;

    public static void start(Context c) {
        if (!Guard.enabled(c)) return;
        Intent i = new Intent(c, WatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) { }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, WatchService.class)); } catch (Throwable ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, "Страж", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        startForeground(4103, b.setSmallIcon(R.drawable.ic_tv).setContentTitle("ТВ Турбо: Страж").setContentText("Следит за включением ТВ").build());

        screen = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long now = System.currentTimeMillis();
                if (now - lastWake < 120_000) return;   // не чаще раза в 2 минуты
                lastWake = now;
                Adb.get(c).logLine("📺 ТВ проснулся — через 10 секунд Страж проверит всё и перезапустит обход");
                Guard.runSoon(c, 10_000);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Guard.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (screen != null) unregisterReceiver(screen); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
