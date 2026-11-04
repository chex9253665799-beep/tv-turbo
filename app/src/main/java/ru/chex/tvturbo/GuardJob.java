package ru.chex.tvturbo;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Фоновая проверка «Стража» (по расписанию и после включения ТВ). */
public class GuardJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Guard.enabled(this)) return false;
        boolean boot = params.getJobId() == Guard.JOB_NOW;
        Adb.get(this).background(() -> {
            String result;
            try { result = Guard.check(this, boot); } catch (Throwable t) { result = "ошибка: " + t.getMessage(); }
            String r = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (boot) {
                    Toast.makeText(getApplicationContext(), "🛡 ТВ Турбо: " + r, Toast.LENGTH_LONG).show();
                    if (Guard.openOnBoot(this)) {
                        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }
                jobFinished(params, false);
            });
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
