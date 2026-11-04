package ru.chex.tvturbo;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** Общий экран «заголовок + подсказка + кнопки + список». */
public abstract class ListScreen extends BaseActivity {

    protected Adb adb;
    protected TextView title, hint;
    protected LinearLayout actions;
    protected ListView list;
    protected Ui.RowAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_list);
        adb = Adb.get(this);
        title = findViewById(R.id.title);
        hint = findViewById(R.id.hint);
        actions = findViewById(R.id.actions);
        list = findViewById(R.id.list);
        adapter = new Ui.RowAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onRowClick(adapter.getItem(pos), pos));
        setup();
    }

    protected abstract void setup();
    protected abstract void onRowClick(Ui.Row r, int pos);

    /** Переключить галочку в строке. */
    protected void toggle(Ui.Row r) {
        r.checked = !r.checked;
        adapter.notifyDataSetChanged();
    }
}
