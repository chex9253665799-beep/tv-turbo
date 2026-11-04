package ru.chex.tvturbo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Общие кусочки интерфейса: строка списка, адаптер, кнопки действий, диалоги. */
public final class Ui {

    public static final class Row {
        public String mark = "", name = "", sub = "", badge = "";
        public int badgeColor = 0xFF8A93A6;
        public boolean checked;
        public boolean checkable;
        public Object tag;
    }

    public static final class RowAdapter extends BaseAdapter {
        private final LayoutInflater inf;
        public final List<Row> rows = new ArrayList<>();
        public RowAdapter(Context c) { inf = LayoutInflater.from(c); }
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = inf.inflate(R.layout.row_item, parent, false);
            Row r = rows.get(i);
            ((TextView) v.findViewById(R.id.mark)).setText(r.checkable ? (r.checked ? "☑" : "☐") : r.mark);
            ((TextView) v.findViewById(R.id.name)).setText(r.name);
            TextView sub = v.findViewById(R.id.sub);
            sub.setText(r.sub);
            sub.setVisibility(r.sub.isEmpty() ? View.GONE : View.VISIBLE);
            TextView b = v.findViewById(R.id.badge);
            b.setText(r.badge);
            b.setTextColor(r.badgeColor);
            if (r.badge.isEmpty()) b.setBackground(null);
            else {
                android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                pill.setCornerRadius(dp(b.getContext(), 100));
                pill.setColor((r.badgeColor & 0x00FFFFFF) | 0x26000000);
                b.setBackground(pill);
                int h = dp(b.getContext(), 12), vv = dp(b.getContext(), 5);
                b.setPadding(h, vv, h, vv);
            }
            return v;
        }
    }

    public static Button action(Activity a, LinearLayout bar, String text, View.OnClickListener l) {
        Button b = new Button(a, null, 0, R.style.BigButton);
        b.setText(text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(a, 10));
        b.setLayoutParams(lp);
        bar.addView(b);
        return b;
    }

    public static void confirm(Activity a, String title, String msg, Runnable yes) {
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Да", (d, w) -> yes.run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    public static void info(Activity a, String title, String msg) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    public static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }

    public static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Если не подключено — показать подсказку и вернуть false. */
    public static boolean needAdb(Activity a) {
        if (Adb.get(a).isConnected()) return true;
        info(a, "Нет подключения", "Сначала нажми «Подключиться» на главном экране.");
        return false;
    }

    private Ui() { }
}
