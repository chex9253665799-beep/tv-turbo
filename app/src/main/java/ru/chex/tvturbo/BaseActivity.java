package ru.chex.tvturbo;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * Подгонка интерфейса под любой ТВ (24"–65", 720p / 768p / 1080p / 4K).
 * Android не знает дюймы, только разрешение и плотность. Поэтому приводим ширину экрана
 * к одинаковым 960 «точкам» — интерфейс выглядит одинаково на всех ТВ — и умножаем
 * на размер, выбранный пользователем (мелкий / обычный / крупный / очень крупный).
 */
public abstract class BaseActivity extends Activity {

    public static final float[] SCALES = {0.85f, 1.0f, 1.15f, 1.3f};
    public static final String[] SCALE_NAMES = {"Мелкий", "Обычный", "Крупный", "Очень крупный"};
    private static final int BASE_WIDTH_DP = 960;

    public static int scaleIndex(Context c) {
        return c.getSharedPreferences("ui", MODE_PRIVATE).getInt("scale", 1);
    }

    public static void setScaleIndex(Context c, int i) {
        c.getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("scale", i).apply();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(scaled(base));
    }

    static Context scaled(Context base) {
        DisplayMetrics dm = base.getResources().getDisplayMetrics();
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int widthPx = Math.max(dm.widthPixels, dm.heightPixels);
        int idx = Math.max(0, Math.min(SCALES.length - 1, scaleIndex(base)));
        // какая плотность нужна, чтобы ширина экрана была ровно BASE_WIDTH_DP
        float targetDpi = widthPx * 160f / BASE_WIDTH_DP * SCALES[idx];
        int dpi = Math.round(targetDpi);
        if (dpi < 80 || dpi > 1200) return base; // защита от странных значений
        cfg.densityDpi = dpi;
        cfg.fontScale = 1.0f;
        cfg.screenWidthDp = Math.round(widthPx * 160f / dpi);
        cfg.screenHeightDp = Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 160f / dpi);
        cfg.smallestScreenWidthDp = cfg.screenHeightDp;
        return base.createConfigurationContext(cfg);
    }
}
