package ru.chex.tvturbo;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/** Выбор лаунчера с картинкой «как выглядит», установка и назначение главным экраном. */
public class LauncherActivity extends BaseActivity {

    public static final String EXTRA_CHOOSE_ONLY = "choose_only";

    private Adb adb;
    private Ui.RowAdapter adapter;
    private ListView list;
    private ImageView preview;
    private TextView previewText, name, desc, hint;
    private LinearLayout actions;
    private final Map<String, Bitmap> cache = new HashMap<>();
    private Launchers.L current;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_launcher);
        adb = Adb.get(this);
        list = findViewById(R.id.list);
        preview = findViewById(R.id.preview);
        previewText = findViewById(R.id.previewText);
        name = findViewById(R.id.name);
        desc = findViewById(R.id.desc);
        hint = findViewById(R.id.hint);
        actions = findViewById(R.id.actions);

        adapter = new Ui.RowAdapter(this);
        String chosen = Launchers.chosen(this).id;
        String home = Pkgs.currentHome(this);
        Map<String, Pkgs.P> pkgs = Pkgs.map(this);
        for (Launchers.L l : Launchers.ALL) {
            Ui.Row r = new Ui.Row();
            boolean builtin = Launchers.BUILTIN.equals(l.id);
            boolean installed = builtin || (pkgs.containsKey(l.pkg) && pkgs.get(l.pkg).installed);
            boolean isHome = builtin ? homeIsBuiltin() : l.pkg.equals(home);
            r.mark = isHome ? "🏠" : l.id.equals(chosen) ? "★" : "🎨";
            r.name = l.name;
            r.sub = l.plus;
            r.badge = isHome ? "главный экран" : builtin ? "встроен" : installed ? "установлен" : "";
            r.badgeColor = 0xFF3DDC84;
            r.tag = l;
            adapter.rows.add(r);
        }
        list.setAdapter(adapter);
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { show((Launchers.L) adapter.getItem(pos).tag); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            show((Launchers.L) adapter.getItem(pos).tag);
            if (actions.getChildCount() > 0) actions.getChildAt(0).requestFocus();
        });

        boolean chooseOnly = getIntent().getBooleanExtra(EXTRA_CHOOSE_ONLY, false);
        if (!chooseOnly) Ui.action(this, actions, "⬇  Установить и сделать главным", v -> installCurrent());
        if (chooseOnly)
            Ui.action(this, actions, "✔  Выбрать этот и продолжить", v -> {
                Launchers.choose(this, current.id);
                setResult(RESULT_OK);
                finish();
            });
        if (!chooseOnly) Ui.action(this, actions, "↩  Вернуть стандартный", v -> restoreStock());
        if (chooseOnly) hint.setText("Шаг 1 из 2: выбери главный экран — справа видно, как он выглядит. Потом авто-режим всё сделает сам.");

        show(Launchers.chosen(this));
        list.requestFocus();
    }

    private boolean homeIsBuiltin() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
        return ri != null && ri.activityInfo != null && ri.activityInfo.name.endsWith("HomeActivity");
    }

    private void show(Launchers.L l) {
        current = l;
        name.setText(l.name);
        desc.setText(l.desc);
        Bitmap bmp = cache.get(l.id);
        if (bmp != null) { preview.setImageBitmap(bmp); previewText.setVisibility(View.GONE); return; }
        preview.setImageBitmap(null);
        previewText.setVisibility(View.VISIBLE);
        previewText.setText("Загружаю картинку…");
        new Thread(() -> {
            try {
                byte[] data = InstallActivity.download(l.preview);
                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                runOnUiThread(() -> {
                    cache.put(l.id, bm);
                    if (current == l) { preview.setImageBitmap(bm); previewText.setVisibility(View.GONE); }
                });
            } catch (Throwable t) {
                runOnUiThread(() -> { if (current == l) previewText.setText("Нет интернета — картинка не загрузилась"); });
            }
        }).start();
    }

    private void installCurrent() {
        if (busy || !Ui.needAdb(this)) return;
        busy = true;
        Launchers.L l = current;
        Launchers.choose(this, l.id);
        hint.setText("Ищу последнюю версию " + l.name + "…");
        adb.background(() -> {
            String res = installAndSetHome(this, adb, l, t -> adb.ui(() -> hint.setText(t)));
            adb.ui(() -> { busy = false; hint.setText(res); Ui.toast(this, res); });
        });
    }

    private void restoreStock() {
        if (!Ui.needAdb(this)) return;
        String[] stock = Launchers.STOCK;
        adb.background(() -> {
            for (String p : stock) adb.execSync(Pkgs.cmdEnable(p), false);
            String comp = null;
            for (String p : stock) { comp = homeComponent(this, p); if (comp != null) break; }
            String msg = comp != null && adb.execSync("cmd package set-home-activity " + comp, true).ok
                    ? "✔ Стандартный главный экран возвращён" : "Нажми «Домой» и выбери стандартный лаунчер → «Всегда»";
            adb.ui(() -> Ui.toast(this, msg));
        });
    }

    // ---------- общее для авто-режима ----------

    public interface Status { void on(String text); }

    /** Скачать, установить и назначить главным. Возвращает итоговый текст. Только в потоке Adb. */
    static String installAndSetHome(android.content.Context c, Adb adb, Launchers.L l, Status st) {
        if (Launchers.BUILTIN.equals(l.id)) {
            // встроенный — ставить не нужно, только назначить главным
            String comp = c.getPackageName() + "/" + HomeActivity.class.getName();
            if (adb.execSync("cmd package set-home-activity " + comp, true).ok) {
                int off = Launchers.disableStock(c, adb);
                return "✔ ТВ Турбо Дом стал главным экраном" + (off > 0 ? ", PatchWall отключён" : "") + ". Нажми «Домой»!";
            }
            return "✔ Нажми «Домой» и выбери «ТВ Турбо Дом» → «Всегда».";
        }
        File f = new File(c.getCacheDir(), "launcher.apk");
        try {
            String url = InstallActivity.latestGithubApk(l.repo, l.asset, "");
            byte[] data = InstallActivity.download(url, p -> st.on("Скачиваю " + l.name + "… " + p + "%"));
            try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
        } catch (Throwable t) {
            return "✖ " + l.name + " не скачался: " + t.getMessage();
        }
        st.on("Устанавливаю " + l.name + "… (до пары минут)");
        Adb.Result r = adb.installSync(f);
        f.delete();
        if (!r.ok) return "✖ Ошибка установки " + l.name + ": " + r.out;
        st.on("Делаю " + l.name + " главным экраном…");
        String comp = homeComponent(c, l.pkg);
        if (comp != null && adb.execSync("cmd package set-home-activity " + comp, true).ok) {
            int off = Launchers.disableStock(c, adb);   // PatchWall и стандартные лаунчеры больше не всплывут
            return "✔ " + l.name + " установлен и стал главным экраном" + (off > 0 ? ", PatchWall отключён" : "") + ". Нажми «Домой»!";
        }
        return "✔ " + l.name + " установлен. Нажми «Домой» и выбери его → «Всегда».";
    }

    static String homeComponent(android.content.Context c, String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0))
            if (ri.activityInfo != null && ri.activityInfo.packageName.equals(pkg))
                return pkg + "/" + ri.activityInfo.name;
        return null;
    }
}
