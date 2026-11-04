package ru.chex.tvturbo;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

import ru.chex.tvturbo.mv.Img;
import ru.chex.tvturbo.mv.Kit;
import ru.chex.tvturbo.mv.Program;

/** Карточка фильма/сериала: большой постер, описание, «Смотреть» в приложении-источнике, «В избранное». */
public class MvDetailsActivity extends BaseActivity {

    private Program p;
    private TextView favBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { p = Program.fromJson(new JSONObject(getIntent().getStringExtra("p"))); } catch (Throwable t) { finish(); return; }
        int acc = Kit.accent(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, Kit.wall(this)));
        ImageView bg = Kit.image(this, ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0.5f);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        Img.load(this, p.poster, bg, dp(960));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF2050B1F, 0xCC050B1F, 0x55050B1F}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(60), dp(50), dp(50), dp(30));
        FrameLayout poster = new FrameLayout(this);
        poster.setBackground(Kit.round(0xFF1E3A8A, dp(16)));
        poster.setClipToOutline(true);
        poster.setElevation(dp(14));
        ImageView pi = Kit.image(this, ImageView.ScaleType.CENTER_CROP);
        poster.addView(pi, new FrameLayout.LayoutParams(-1, -1));
        Img.load(this, p.poster, pi, dp(300));
        row.addView(poster, new LinearLayout.LayoutParams(dp(250), dp(360)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(36), dp(10), 0, 0);
        info.addView(Kit.text(this, ((p.isSeries() ? "СЕРИАЛ" : "ФИЛЬМ") + " · ИЗ ПРИЛОЖЕНИЯ " + p.app).toUpperCase(), 11, Kit.lighten(acc), true));
        TextView title = Kit.text(this, p.title, 38, Color.WHITE, true);
        title.setPadding(0, dp(8), 0, dp(10));
        info.addView(title);
        String meta = HomeActivity.metaOf(p);
        if (p.durationMs > 0) meta += (meta.isEmpty() ? "" : " · ") + (p.durationMs / 3600000 > 0 ? (p.durationMs / 3600000) + " ч " : "") + ((p.durationMs / 60000) % 60) + " мин";
        if (!meta.isEmpty()) info.addView(Kit.text(this, meta, 14, 0xFFC9D6F5, false));
        if (!p.desc.isEmpty()) {
            TextView d = Kit.text(this, p.desc, 15, 0xFFDCE5FA, false);
            d.setLineSpacing(0, 1.25f);
            d.setMaxLines(7);
            d.setPadding(0, dp(16), 0, 0);
            info.addView(d, new LinearLayout.LayoutParams(dp(520), -2));
        }
        if (p.watchNext && p.progressPct() > 0) {
            TextView pr = Kit.text(this, "Просмотрено " + p.progressPct() + "%", 12, Kit.MUTED, false);
            pr.setPadding(0, dp(10), 0, 0);
            info.addView(pr);
        }
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(26), 0, 0);
        TextView watch = Kit.pill(this, "▶  Смотреть в " + p.app, true, v -> open());
        watch.setTextSize(17);
        btns.addView(watch);
        favBtn = Kit.pill(this, "", false, v -> toggle());
        favBtn.setTextSize(17);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
        fl.setMarginStart(dp(12));
        btns.addView(favBtn, fl);
        info.addView(btns);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        updateFav();
        watch.post(watch::requestFocus);
    }

    private void open() {
        Intent i = null;
        try { if (!p.intent.isEmpty()) i = Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME); } catch (Throwable ignored) { }
        if (i == null) {
            i = getPackageManager().getLeanbackLaunchIntentForPackage(p.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(p.pkg);
        }
        if (i == null) { Ui.toast(this, "Приложение " + p.app + " не найдено"); return; }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Throwable t) { Ui.toast(this, "Не открывается: " + t.getMessage()); }
    }

    private boolean fav() {
        for (String j : Kit.list(this, "favProg")) try { if (Program.fromJson(new JSONObject(j)).key().equals(p.key())) return true; } catch (Throwable ignored) { }
        return false;
    }

    private void toggle() {
        List<String> l = Kit.list(this, "favProg");
        boolean was = fav();
        for (int i = l.size() - 1; i >= 0; i--)
            try { if (Program.fromJson(new JSONObject(l.get(i))).key().equals(p.key())) l.remove(i); } catch (Throwable ignored) { }
        if (!was) l.add(0, p.toJson().toString());
        Kit.saveList(this, "favProg", l);
        updateFav();
    }

    private void updateFav() { favBtn.setText(fav() ? "✔  В избранном" : "🔖  В избранное"); }

    private int dp(float v) { return Kit.dp(this, v); }
}
