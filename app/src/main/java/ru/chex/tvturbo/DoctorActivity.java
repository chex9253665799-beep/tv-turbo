package ru.chex.tvturbo;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** «Доктор ТВ»: неисправности с объяснением и исправлением, паспорт железа, справочник кодов ошибок. */
public class DoctorActivity extends ListScreen {

    private static final int PROBLEMS = 0, PASSPORT = 1, CODES = 2;
    private int mode = PROBLEMS;
    private Doctor.Result last;
    private boolean busy;
    private final TextShare share = new TextShare();

    @Override
    protected void setup() {
        title.setText("💊 Доктор ТВ");
        Ui.action(this, actions, "🔄  Проверить", v -> scan());
        Ui.action(this, actions, "📋  Паспорт ТВ", v -> showPassport());
        Ui.action(this, actions, "📱  Паспорт на телефон", v -> passportToPhone());
        Ui.action(this, actions, "🔧  Сервисное меню", v -> serviceMenu());
        Ui.action(this, actions, "📖  Коды ошибок", v -> showCodes());
        scan();
    }

    // ------------------------------------------------------------ проверка

    private void scan() {
        if (busy) return;
        if (!Adb.get(this).isConnected()) { Ui.needAdb(this); showCodes(); return; }
        busy = true;
        mode = PROBLEMS;
        hint.setText("Проверяю: ошибки приложений за неделю, перезагрузки, температуру, память, флеш-память, сеть, время… (до минуты)");
        adapter.rows.clear();
        adapter.notifyDataSetChanged();
        adb.background(() -> {
            Doctor.Result r;
            try { r = Doctor.run(this); }
            catch (Throwable t) { adb.ui(() -> { busy = false; hint.setText("Ошибка проверки: " + t); }); return; }
            Doctor.Result res = r;
            adb.ui(() -> { busy = false; last = res; showProblems(); });
        });
    }

    private void showProblems() {
        mode = PROBLEMS;
        hint.setText(last.summary);
        adapter.rows.clear();
        for (Doctor.Item i : last.items) {
            Ui.Row row = new Ui.Row();
            row.mark = i.level == Doctor.BAD ? "🔴" : i.level == Doctor.WARN ? "🔶" : i.level == Doctor.INFO ? "💡" : "✅";
            row.name = i.title;
            String first = i.detail == null ? "" : i.detail.split("\n")[0];
            row.sub = first;
            boolean canFix = i.fixLabel != null || !i.enablePkgs.isEmpty() || i.pkg != null;
            row.badge = i.level >= Doctor.WARN || i.level == Doctor.INFO ? (canFix ? "Что делать  ›" : "Подробнее  ›") : "";
            row.badgeColor = i.level == Doctor.BAD ? 0xFFFF5A5F : 0xFFFFB020;
            row.tag = i;
            adapter.rows.add(row);
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
    }

    // ------------------------------------------------------------ паспорт

    private void showPassport() {
        if (last == null) { if (!busy) scan(); Ui.toast(this, "Сначала дождись проверки — паспорт собирается вместе с ней"); return; }
        mode = PASSPORT;
        hint.setText("Паспорт ТВ: железо и коды прошивки. Пригодится для ремонта и поиска прошивки (4PDA и т. п.). «Паспорт на телефон» — сохранить себе.");
        adapter.rows.clear();
        String group = "";
        for (String[] p : last.passport) {
            if (!p[0].equals(group)) {
                group = p[0];
                Ui.Row h = new Ui.Row();
                h.mark = "▸";
                h.name = group;
                h.badgeColor = 0xFF5AA9FF;
                adapter.rows.add(h);
            }
            Ui.Row r = new Ui.Row();
            r.mark = " ";
            r.name = p[1];
            r.sub = p[2];
            r.tag = p;
            adapter.rows.add(r);
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
    }

    private void passportToPhone() {
        if (last == null) { showPassport(); return; }
        String url = share.start("Паспорт ТВ", last.passportText() + "\n\n== Найденные проблемы ==\n" + problemsText());
        if (url == null) { Ui.toast(this, "ТВ не в домашней сети"); return; }
        int px = Ui.dp(this, 220);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 8));
        ImageView qr = new ImageView(this);
        try { qr.setImageBitmap(Qr.bitmap(url, px)); } catch (Throwable ignored) { }
        box.addView(qr, new LinearLayout.LayoutParams(px, px));
        TextView t = new TextView(this);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 12), 0, 0);
        t.setText("Наведи камеру телефона на QR-код (телефон — в той же Wi-Fi)\nили открой в браузере: " + url
                + "\n\nНа телефоне будет кнопка «Скопировать всё» — вставь в заметки или отправь в мессенджер.");
        box.addView(t);
        new AlertDialog.Builder(this).setTitle("📱 Паспорт ТВ на телефон").setView(box)
                .setNegativeButton("Закрыть", null)
                .setOnDismissListener(d -> share.stop())
                .show();
    }

    private String problemsText() {
        StringBuilder sb = new StringBuilder();
        for (Doctor.Item i : last.items)
            if (i.level >= Doctor.WARN) sb.append("• ").append(i.title).append('\n');
        return sb.length() == 0 ? "нет" : sb.toString();
    }

    // ------------------------------------------------------------ сервисное меню

    private void serviceMenu() {
        if (busy || !Ui.needAdb(this)) return;
        busy = true;
        hint.setText("Ищу сервисное (заводское) меню среди системных приложений…");
        adb.background(() -> {
            List<ServiceMenu.Found> menus = ServiceMenu.find(this);
            adb.ui(() -> {
                busy = false;
                if (menus.isEmpty()) {
                    hint.setText("Сервисное меню не найдено.");
                    Ui.info(this, "🔧 Сервисное меню не найдено",
                            "На этом ТВ заводское меню не вынесено в отдельное приложение (или спрятано в прошивке). "
                                    + "Его можно открыть только секретной комбинацией на пульте — она своя для каждой модели. "
                                    + "Ищи на 4PDA по кодовому имени из «Паспорта ТВ».");
                    return;
                }
                ServiceMenu.Found main = ServiceMenu.main(menus);
                if (main != null) { confirmGrab(main); return; }   // FactoryMenuActivity — сразу его
                String[] names = new String[menus.size()];
                for (int i = 0; i < names.length; i++) names[i] = menus.get(i).label;
                new AlertDialog.Builder(this).setTitle("🔧 Найдено меню: " + menus.size() + " (сверху — самое вероятное)")
                        .setItems(names, (d, w) -> confirmGrab(menus.get(w)))
                        .setNegativeButton("Отмена", null).show();
            });
        });
    }

    private void confirmGrab(ServiceMenu.Found m) {
        Ui.confirm(this, "Считать сервисное меню?",
                "Меню откроется на 3–5 секунд, программа прочитает с него данные (матрица, плата, версии), сделает снимок "
                        + "и закроет кнопкой «Назад».\n\n⚠ Пока меню открыто — НЕ нажимай кнопки пульта: в заводском меню можно сбить настройки изображения.",
                () -> grab(m));
    }

    private void grab(ServiceMenu.Found m) {
        busy = true;
        hint.setText("Открываю «" + m.label + "» и считываю… Не нажимай пульт.");
        adb.background(() -> {
            ServiceMenu.Grab g = ServiceMenu.grab(this, adb, m);
            adb.ui(() -> { busy = false; showGrab(g); });
        });
    }

    private void showGrab(ServiceMenu.Grab g) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        TextView t = new TextView(this);
        t.setTextSize(15);
        StringBuilder sb = new StringBuilder();
        if (g.error != null) sb.append("⚠ ").append(g.error).append("\n\n");
        if (!g.facts.isEmpty()) {
            sb.append("Найдено важное:\n");
            for (String[] f : g.facts) sb.append("• ").append(f[0].isEmpty() ? "" : f[0] + ": ").append(f[1]).append('\n');
            sb.append("\nДобавлено в «Паспорт ТВ».\n");
        } else if (!g.lines.isEmpty()) {
            sb.append("Текст с экрана меню:\n").append(android.text.TextUtils.join("\n", g.lines.subList(0, Math.min(40, g.lines.size()))));
        }
        if (g.shot != null) sb.append("\nНиже — снимок меню (если данные на другой странице — открой её комбинацией на пульте сам).");
        t.setText(sb.toString().trim());
        box.addView(t);
        if (g.shot != null) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(g.shot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 10);
            box.addView(iv, lp);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("🔧 " + g.menu.label).setView(sv)
                .setPositiveButton("OK", (d, w) -> { if (!g.facts.isEmpty()) scan(); })
                .show();
        hint.setText(g.facts.isEmpty() ? "Сервисное меню: важных строк не найдено — смотри снимок." : "Сервисное меню считано, данные добавлены в «Паспорт ТВ».");
    }

    // ------------------------------------------------------------ коды

    private void showCodes() {
        mode = CODES;
        hint.setText("Справочник кодов ошибок: найди свой код или текст ошибки и нажми — расскажу, что делать.");
        adapter.rows.clear();
        for (String[] c : ErrorCodes.ALL) {
            Ui.Row r = new Ui.Row();
            r.mark = "⌗";
            r.name = c[0];
            r.sub = c[1];
            r.badge = "Что делать  ›";
            r.badgeColor = 0xFF5AA9FF;
            r.tag = c;
            adapter.rows.add(r);
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.requestFocus();
    }

    // ------------------------------------------------------------ нажатия

    @Override
    protected void onRowClick(Ui.Row r, int pos) {
        if (r.tag == null) return;
        if (mode == CODES) {
            String[] c = (String[]) r.tag;
            Ui.info(this, c[0], "Что значит: " + c[1] + "\n\nЧто делать: " + c[2]);
            return;
        }
        if (mode == PASSPORT) {
            String[] p = (String[]) r.tag;
            Ui.info(this, p[1], p[2]);
            return;
        }
        Doctor.Item i = (Doctor.Item) r.tag;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(i.title).setMessage(i.detail == null || i.detail.isEmpty() ? "Всё в порядке." : i.detail)
                .setNegativeButton("Закрыть", null);
        if (i.pkg != null) b.setPositiveButton("Что сделать…", (d, w) -> appActions(i));
        else if (i.fixLabel != null || !i.enablePkgs.isEmpty())
            b.setPositiveButton(i.fixLabel != null ? i.fixLabel : "Исправить", (d, w) -> fix(i));
        b.show();
    }

    private void appActions(Doctor.Item i) {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (!i.enablePkgs.isEmpty()) { labels.add("✔ Вернуть нужный пакет (" + i.enablePkgs.size() + ")"); acts.add(() -> fix(i)); }
        labels.add("🔁 Перезапустить приложение");
        acts.add(() -> run(listOf("am force-stop " + i.pkg), "Приложение перезапущено — открой его снова"));
        labels.add("🧹 Сбросить данные приложения");
        acts.add(() -> Ui.confirm(this, "Сбросить данные?", "Приложение станет как после установки: настройки и вход в аккаунт сотрутся. "
                + "Часто это лечит постоянные вылеты.", () -> run(listOf("pm clear " + i.pkg), "Данные сброшены")));
        labels.add("⚙ Открыть настройки приложения");
        acts.add(() -> run(listOf("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:" + i.pkg), "Открываю…"));
        new AlertDialog.Builder(this).setTitle(i.pkg)
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Отмена", null).show();
    }

    private void fix(Doctor.Item i) {
        if (!Ui.needAdb(this)) return;
        List<String> cmds = new ArrayList<>(i.fixCmds);
        if (!i.enablePkgs.isEmpty()) {
            Profile.allow(this, i.enablePkgs.toArray(new String[0]));   // чтобы Страж не отключил их снова
            for (String p : i.enablePkgs) cmds.add(Pkgs.cmdEnable(p));
        }
        if (cmds.contains("reboot")) {
            Ui.confirm(this, "Перезагрузить ТВ?", "ТВ выключится и включится через минуту.", () -> run(cmds, "Перезагружаю…"));
            return;
        }
        run(cmds, "✔ " + (i.fixLabel != null ? i.fixLabel : "Исправлено"));
    }

    private void run(List<String> cmds, String okText) {
        if (!Ui.needAdb(this)) return;
        adb.runAll(cmds, (ok, out) -> {
            Ui.toast(this, ok ? okText : "⚠ " + out);
            if (mode == PROBLEMS && !cmds.contains("reboot") && !cmds.get(0).startsWith("am start")) scan();
        });
    }

    private static List<String> listOf(String s) { List<String> l = new ArrayList<>(); l.add(s); return l; }

    @Override
    protected void onDestroy() {
        share.stop();
        super.onDestroy();
    }
}
