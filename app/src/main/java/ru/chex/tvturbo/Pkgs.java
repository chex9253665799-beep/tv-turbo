package ru.chex.tvturbo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Список пакетов на ТВ, их состояние и журнал того, что изменило это приложение (для «Вернуть всё»). */
public final class Pkgs {

    public static final class P {
        public String pkg, label; public boolean system, enabled, installed;
        public String state() {
            if (!installed) return "удалён";
            return enabled ? "включён" : "отключён";
        }
    }

    @SuppressWarnings("deprecation")
    public static List<P> list(Context c) {
        PackageManager pm = c.getPackageManager();
        int flags = PackageManager.GET_META_DATA;
        if (android.os.Build.VERSION.SDK_INT >= 24)
            flags |= PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS;
        else
            flags |= PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS;
        List<ApplicationInfo> apps = pm.getInstalledApplications(flags);
        List<P> out = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(c.getPackageName())) continue;
            P p = new P();
            p.pkg = ai.packageName;
            CharSequence l = null;
            try { l = ai.loadLabel(pm); } catch (Throwable ignored) { }
            p.label = l == null ? ai.packageName : l.toString();
            p.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            p.installed = (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            p.enabled = ai.enabled;
            out.add(p);
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    public static Map<String, P> map(Context c) {
        Map<String, P> m = new HashMap<>();
        for (P p : list(c)) m.put(p.pkg, p);
        return m;
    }

    /** Пакет текущего главного экрана (лаунчера). */
    public static String currentHome(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return "";
        String pkg = ri.activityInfo.packageName;
        return "android".equals(pkg) ? "" : pkg; // "android" = окно выбора, лаунчер не задан
    }

    /** Количество приложений, которые могут быть главным экраном. */
    public static int homeCount(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        return c.getPackageManager().queryIntentActivities(i, 0).size();
    }

    // ---------- команды ----------

    public static String cmdDisable(String pkg) { return "pm disable-user --user 0 " + pkg; }
    public static String cmdEnable(String pkg) { return "pm enable " + pkg; }
    public static String cmdUninstall(String pkg) { return "pm uninstall -k --user 0 " + pkg; }
    public static String cmdReinstall(String pkg) { return "pm install-existing " + pkg; }
    public static String cmdStop(String pkg) { return "am force-stop " + pkg; }
    public static String cmdNoBackground(String pkg) {
        return "cmd appops set " + pkg + " RUN_IN_BACKGROUND ignore; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore; am force-stop " + pkg;
    }
    public static String cmdAllowBackground(String pkg) {
        return "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow";
    }

    // ---------- журнал изменений ----------

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("changes", Context.MODE_PRIVATE); }

    /** kind: d = отключён, u = удалён для пользователя, b = запрещён фон */
    public static void remember(Context c, char kind, String pkg) {
        Set<String> s = new HashSet<>(sp(c).getStringSet("list", new HashSet<>()));
        s.add(kind + ":" + pkg);
        sp(c).edit().putStringSet("list", s).apply();
    }

    public static void forget(Context c, String pkg) {
        Set<String> s = new HashSet<>(sp(c).getStringSet("list", new HashSet<>()));
        s.remove("d:" + pkg); s.remove("u:" + pkg); s.remove("b:" + pkg);
        sp(c).edit().putStringSet("list", s).apply();
    }

    public static Set<String> changes(Context c) { return new HashSet<>(sp(c).getStringSet("list", new HashSet<>())); }

    public static void clearChanges(Context c) { sp(c).edit().remove("list").apply(); }

    /**
     * Что можно вернуть — по реальному состоянию ТВ, а не только по журналу:
     * все пакеты со статусом «отключено пользователем» (так их отключает pm disable-user и это
     * переживает перезагрузку), плюс удалённые для пользователя из журнала и из базы мусора.
     * Пакеты, которые производитель выключил сам (статус «отключено»), не трогаем.
     */
    public static List<P> restorable(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> journal = new HashSet<>();
        for (String e : changes(c)) journal.add(e.substring(2));
        List<P> out = new ArrayList<>();
        for (P p : list(c)) {
            if (p.installed && !p.enabled) {
                int st;
                try { st = pm.getApplicationEnabledSetting(p.pkg); } catch (Throwable t) { st = -1; }
                if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || journal.contains(p.pkg)) out.add(p);
            } else if (!p.installed && (journal.contains(p.pkg) || Bloat.find(p.pkg) != null)) {
                out.add(p);
            }
        }
        return out;
    }

    public static List<String> restoreCommandsFor(List<P> ps) {
        List<String> cmds = new ArrayList<>();
        for (P p : ps) {
            if (!p.installed) cmds.add(cmdReinstall(p.pkg));
            cmds.add(cmdEnable(p.pkg));
        }
        return cmds;
    }

    /** Команды, которые возвращают всё изменённое этим приложением. */
    public static List<String> restoreCommands(Context c) {
        List<String> cmds = new ArrayList<>();
        for (String e : changes(c)) {
            String pkg = e.substring(2);
            switch (e.charAt(0)) {
                case 'u': cmds.add(cmdReinstall(pkg)); cmds.add(cmdEnable(pkg)); break;
                case 'd': cmds.add(cmdEnable(pkg)); break;
                case 'b': cmds.add(cmdAllowBackground(pkg)); break;
            }
        }
        return cmds;
    }

    private Pkgs() { }
}
