package ru.chex.tvturbo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ТВ включился — запускаем «Стража»: через 20 секунд проверка и восстановление всех настроек. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        // после самообновления — снова открыть приложение
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Guard.schedule(c);
            return;
        }
        if (!Guard.enabled(c)) return;
        Guard.schedule(c);
        Guard.runSoon(c, 20_000);
    }
}
