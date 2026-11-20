package ru.chex.turbohome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A local, lightweight TV launcher. No ADB, WebView, player or background service. */
public final class HomeActivity extends Activity {
    private static final int VIOLET = Color.rgb(136, 78, 240);
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (clock != null) {
                clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                date.setText(new SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(new Date()));
            }
            clockHandler.postDelayed(this, 30_000);
        }
    };
    private SharedPreferences prefs;
    private LinearLayout root, appBar;
    private TextView clock, date;
    private HorizontalScrollView scroller;
    private final List<AppItem> apps = new ArrayList<>();
    private final Map<String, View> cards = new LinkedHashMap<>();
    private final Set<String> favorites = new HashSet<>();
    private int activeColor = VIOLET;

    static final class AppItem {
        String pkg, title;
        Drawable icon;
        Intent launch;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("appearance", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        loadApps();
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        clockHandler.removeCallbacks(tick);
        clockHandler.post(tick);
    }
    @Override protected void onPause() {
        clockHandler.removeCallbacks(tick);
        super.onPause();
    }

    private int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v + .5f); }
    private int screenWidth() { return getResources().getDisplayMetrics().widthPixels; }
    private boolean low() { return prefs.getBoolean("lite", false); }
    private boolean dark() {
        String mode = prefs.getString("theme", "dark");
        if ("light".equals(mode)) return false;
        if ("auto".equals(mode)) {
            int hour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(new Date()));
            return hour < 7 || hour >= 20;
        }
        return true;
    }
    private int foreground() { return dark() ? Color.WHITE : Color.rgb(25, 27, 47); }
    private int secondary() { return dark() ? 0xFFB9C2D9 : 0xFF4E536E; }

    private static GradientDrawable shape(int a, int b, int radius, int line) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setCornerRadius(radius);
        if (line != 0) g.setStroke(2, line);
        return g;
    }
    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.rgb((int)(Color.red(a)*(1-t)+Color.red(b)*t),
                (int)(Color.green(a)*(1-t)+Color.green(b)*t),
                (int)(Color.blue(a)*(1-t)+Color.blue(b)*t));
    }
    private void background(int accent) {
        activeColor = accent;
        int strength = low() ? 0 : prefs.getInt("glow", 60);
        int base = dark() ? 0xFF090E20 : 0xFFF2F3FA;
        int other = dark() ? 0xFF16112D : 0xFFDADDED;
        int tinted = mix(other, accent, strength / 300f);
        root.setBackground(shape(base, tinted, 0, 0));
    }
    private int appColor(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("youtube")) return 0xFFE53945;
        if (p.contains("wink") || p.contains("rt.video")) return 0xFFFF8548;
        if (p.contains("kion") || p.contains("mts")) return 0xFFD730B3;
        if (p.contains("ivi")) return 0xFFDB3776;
        if (p.contains("kinopoisk")) return 0xFFF38C35;
        if (p.contains("rutube")) return 0xFF366BE3;
        if (p.contains("zona")) return 0xFF159FCA;
        return VIOLET;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, AppItem> all = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || all.containsKey(pkg)) continue;
                AppItem app = new AppItem();
                app.pkg = pkg;
                app.title = String.valueOf(info.loadLabel(pm));
                app.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(pkg, info.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                all.put(pkg, app);
            }
        }
        apps.addAll(all.values());
        Collections.sort(apps, (a,b)->a.title.compareToIgnoreCase(b.title));
        String saved = prefs.getString("favorites", "");
        favorites.clear();
        if (!saved.isEmpty()) for (String pkg : saved.split(",")) favorites.add(pkg);
        if (saved.isEmpty()) {
            int count=0;
            for (AppItem app: apps) {
                if (count++ >= 6) break;
                favorites.add(app.pkg);
            }
        }
    }
    private void saveFavorites() {
        StringBuilder b = new StringBuilder();
        for (AppItem a: apps) if (favorites.contains(a.pkg)) {
            if (b.length()>0) b.append(',');
            b.append(a.pkg);
        }
        prefs.edit().putString("favorites",b.toString()).apply();
    }
    private void build() {
        root = vertical();
        root.setPadding(dp(32), dp(22), dp(32), dp(20));
        background(VIOLET);
        setContentView(root);
        LinearLayout top = horizontal();
        TextView brand = text("◆  Turbo Home", 22, foreground(), true);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView gear = button("⚙  Настройки", this::settings);
        top.addView(gear);
        root.addView(top);
        LinearLayout timeRow = horizontal();
        timeRow.setPadding(0, dp(26), 0, dp(30));
        clock=text("", 58, foreground(), true);
        date=text("", 19, secondary(), false);
        date.setPadding(dp(22), 0, 0, 0);
        timeRow.addView(clock);
        timeRow.addView(date);
        root.addView(timeRow);

        TextView name = text("Избранное", 19, foreground(), true);
        name.setPadding(0, 0, 0, dp(14));
        root.addView(name);

        scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        appBar = horizontal();
        appBar.setPadding(dp(3), dp(10), dp(3), dp(18));
        scroller.addView(appBar);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, dp(prefs.getInt("large", 0)==1 ? 172:144)));
        addFavoriteCards();
        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(12), 0, 0);
        addAction(actions,"▦  Все приложения",this::allApps);
        addAction(actions,"☆  Избранное",this::editFavorites);
        addAction(actions,"✦  Фон и тема",this::settings);
        addAction(actions,"⚡  ТВ Турбо",this::openTurbo);
        addAction(actions,"⚙  Android",()->startActivity(new Intent(Settings.ACTION_SETTINGS)));
        root.addView(actions);

        if (!cards.isEmpty()) cards.values().iterator().next().requestFocus();
    }
    private TextView button(String label, Runnable action) {
        TextView b = text(label, 16, foreground(), false);
        b.setFocusable(true);
        b.setClickable(true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14),dp(11),dp(14),dp(11));
        b.setBackground(shape(dark()?0xFF232746:0xFFE0E3F4,dark()?0xFF29234C:0xFFD2D8EC,dp(16),0));
        b.setOnFocusChangeListener((v,focus)->{
            b.setBackground(shape(focus?0xFF5750AB:(dark()?0xFF232746:0xFFE0E3F4),focus?0xFF7963CE:(dark()?0xFF29234C:0xFFD2D8EC),dp(16),focus?0xFF9EEAFF:0));
            b.setTextColor(focus?Color.WHITE:foreground());
        });
        b.setOnClickListener(v->action.run());
        return b;
    }
    private void addAction(LinearLayout row,String label,Runnable action) {
        TextView b=button(label,action);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(58),1);
        p.setMargins(dp(4),0,dp(4),0);
        row.addView(b,p);
    }
    private void addFavoriteCards() {
        cards.clear();
        appBar.removeAllViews();
        for (AppItem a: apps) if (favorites.contains(a.pkg)) addAppCard(appBar,a);
        if (cards.isEmpty()) addAppCard(appBar, apps.isEmpty()?null:apps.get(0));
    }
    private void addAppCard(LinearLayout bar, AppItem app) {
        if (app==null) {
            TextView empty=button("Добавить приложения",this::editFavorites);
            bar.addView(empty);
            return;
        }
        LinearLayout card = vertical();
        card.setFocusable(true);
        card.setClickable(true);
        card.setGravity(Gravity.CENTER);
        int large=prefs.getInt("large",0);
        int width=large==1?dp(178):large==2?dp(124):dp(152);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(width,dp(large==1?150:122));
        lp.setMargins(dp(6),0,dp(6),0);
        card.setLayoutParams(lp);
        int c=appColor(app.pkg);
        card.setBackground(shape(mix(c,0xFF11162A,.55f),mix(c,0xFF12172A,.75f),dp(20),0));
        ImageView image=new ImageView(this);
        try { image.setImageDrawable(getPackageManager().getApplicationIcon(app.pkg)); }
        catch (PackageManager.NameNotFoundException ignored) { }
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(image,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text(app.title,16,Color.WHITE,true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(5),dp(8),dp(5),0);
        card.addView(title,new LinearLayout.LayoutParams(-1,dp(34)));
        card.setOnFocusChangeListener((v,focused)->{
            card.setBackground(shape(focused?mix(c,0xFF463265,.2f):mix(c,0xFF11162A,.55f),
                    focused?mix(c,0xFF212351,.28f):mix(c,0xFF12172A,.75f),dp(20),focused?0xFFB5A4FF:0));
            if (focused) {
                background(prefs.getBoolean("dynamic",true)?c:VIOLET);
                if(!low() && prefs.getBoolean("motion",true)) card.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start();
            } else {
                card.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
        });
        card.setOnClickListener(v->launch(app));
        card.setOnLongClickListener(v->{
            favorites.remove(app.pkg);
            saveFavorites();
            addFavoriteCards();
            Toast.makeText(this,"Убрано из избранного",Toast.LENGTH_SHORT).show();
            return true;
        });
        bar.addView(card);
        cards.put(app.pkg,card);
    }
    private void launch(AppItem app) {
        try {startActivity(app.launch);} catch(Exception e) {
            Toast.makeText(this,"Не удалось открыть "+app.title,Toast.LENGTH_LONG).show();
        }
    }
    private void allApps() { showPicker(false); }
    private void editFavorites() { showPicker(true); }
    private void showPicker(boolean edit) {
        if(apps.isEmpty()) {Toast.makeText(this,"Приложений не найдено",Toast.LENGTH_LONG).show();return;}
        String[] titles=new String[apps.size()];
        boolean[] checked=new boolean[apps.size()];
        for(int i=0;i<apps.size();i++){
            titles[i]=apps.get(i).title;
            checked[i]=favorites.contains(apps.get(i).pkg);
        }
        AlertDialog.Builder dlg=new AlertDialog.Builder(this).setTitle(edit?"Избранные приложения":"Все приложения");
        if(edit){
            dlg.setMultiChoiceItems(titles,checked,(d,idx,value)->checked[idx]=value);
            dlg.setPositiveButton("Сохранить",(d,w)->{
                favorites.clear();
                for(int i=0;i<apps.size();i++)if(checked[i])favorites.add(apps.get(i).pkg);
                saveFavorites();
                build();
            });
        } else dlg.setItems(titles,(d,idx)->launch(apps.get(idx)));
        dlg.setNegativeButton("Закрыть",null).show();
    }
    private void openTurbo() {
        Intent i=getPackageManager().getLaunchIntentForPackage("ru.chex.tvturbo");
        if(i==null){Toast.makeText(this,"Установи ТВ Турбо",Toast.LENGTH_LONG).show();return;}
        startActivity(i);
    }
    private void settings() {
        final String[] options={"Тема: "+prefs.getString("theme","dark"),
          "Динамический фон: "+onOff("dynamic",true),
          "Яркость фонового свечения: "+prefs.getInt("glow",60)+"%",
          "Подсветка и анимация: "+onOff("motion",true),
          "Размер карточек: "+(prefs.getInt("large",0)+1),
          "Режим слабого ТВ: "+onOff("lite",false),
          "Настроить избранное"};
        new AlertDialog.Builder(this).setTitle("Turbo Home · Настройки")
          .setItems(options,(d,n)->{
             if(n==0) chooseTheme();
             if(n==1) flip("dynamic",true);
             if(n==2) chooseGlow();
             if(n==3) flip("motion",true);
             if(n==4){prefs.edit().putInt("large",(prefs.getInt("large",0)+1)%3).apply();build();settings();}
             if(n==5) flip("lite",false);
             if(n==6) editFavorites();
          }).setNegativeButton("Готово",null).show();
    }
    private String onOff(String key,boolean def){return prefs.getBoolean(key,def)?"вкл":"выкл";}
    private void flip(String key,boolean def){
        prefs.edit().putBoolean(key,!prefs.getBoolean(key,def)).apply();
        build();settings();
    }
    private void chooseTheme(){
        String[] modes={"Тёмная","Светлая","Автоматически"};
        String[] keys={"dark","light","auto"};
        new AlertDialog.Builder(this).setTitle("Тема").setItems(modes,(d,n)->{
            prefs.edit().putString("theme",keys[n]).apply();build();settings();
        }).show();
    }
    private void chooseGlow(){
        String[] labels={"Без свечения","Слабое · 25%","Среднее · 50%","Яркое · 75%","Максимум · 100%"};
        int[] values={0,25,50,75,100};
        new AlertDialog.Builder(this).setTitle("Яркость фонового эффекта")
          .setItems(labels,(d,n)->{prefs.edit().putInt("glow",values[n]).apply();build();settings();}).show();
    }
}
