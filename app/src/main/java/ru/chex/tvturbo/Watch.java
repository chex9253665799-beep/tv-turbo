package ru.chex.tvturbo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

import ru.chex.tvturbo.mv.Kit;
import ru.chex.tvturbo.mv.Program;

/** «Где смотреть»: фильм из каталога открывается в одном из установленных онлайн-кинотеатров. */
public final class Watch {

    public static void choose(Activity a, Program p) {
        PackageManager pm = a.getPackageManager();
        List<String> pkgs = new ArrayList<>(), names = new ArrayList<>();
        List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        String last = Kit.sp(a).getString("watchApp", "");
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            String label = String.valueOf(ri.loadLabel(pm));
            String cat = HomeCats.of(pkg, label, false);
            if (!HomeCats.MOVIES.equals(cat) || pkgs.contains(pkg)) continue;
            if (pkg.equals(last)) { pkgs.add(0, pkg); names.add(0, label + "  (в прошлый раз)"); }
            else { pkgs.add(pkg); names.add(label); }
        }
        if (pkgs.isEmpty()) { Ui.toast(a, "Не найдено приложений с фильмами (КИОН, Иви, Кинопоиск…)"); return; }
        new AlertDialog.Builder(a)
                .setTitle("Где смотреть «" + p.title + "»?")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    Kit.sp(a).edit().putString("watchApp", pkgs.get(w)).apply();
                    openIn(a, pkgs.get(w), p);
                })
                .setNegativeButton("Отмена", null).show();
    }

    static void openIn(Activity a, String pkg, Program p) {
        PackageManager pm = a.getPackageManager();
        // Кинопоиск понимает ссылку на фильм
        if (pkg.contains("kinopoisk") && p.kpId != 0) {
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.kinopoisk.ru/film/" + p.kpId + "/")).setPackage(pkg);
            if (start(a, v)) return;
        }
        // поиск внутри приложения (стандарт Android)
        Intent s = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, p.title);
        if (!pm.queryIntentActivities(s, 0).isEmpty() && start(a, s)) return;
        // иначе просто открыть приложение
        Intent l = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (l == null) l = pm.getLaunchIntentForPackage(pkg);
        if (l != null && start(a, l)) Ui.toast(a, "Найди в поиске: «" + p.title + "»");
        else Ui.toast(a, "Не открывается");
    }

    private static boolean start(Activity a, Intent i) {
        try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (Throwable t) { return false; }
    }

    private Watch() { }
}
