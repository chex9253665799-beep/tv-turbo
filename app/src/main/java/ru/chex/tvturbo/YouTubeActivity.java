package ru.chex.tvturbo;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.chex.tvturbo.mv.PhoneInput;

/** Экран «YouTube в России»: настройка под ключ, смена стратегии, выключение. */
public class YouTubeActivity extends ListScreen {

    private static final String[] STEPS = {
            "Установить «ТВ Турбо Обход» — только для YouTube",
            "Убрать старый обход на весь ТВ (если был)",
            "Выдать разрешение VPN (без окон)",
            "Включить обход",
            "Включать обход сам после перезагрузки"
    };

    private boolean busy;
    private Button nextBtn, switchBtn;
    private final PhoneInput phone = new PhoneInput();
    private AlertDialog keyDlg;
    private String pendingLink;
    private static final int REQ_VPN = 77;

    @Override
    protected void setup() {
        title.setText("▶ YouTube в России");
        hint.setText("Обход работает ТОЛЬКО для YouTube — банки, Кинопоиск, Wink и другие российские приложения "
                + "идут напрямую и VPN не видят. Обход включается сам, в том числе после перезагрузки ТВ.");
        Ui.action(this, actions, "▶  Настроить всё", v -> runSetup());
        nextBtn = Ui.action(this, actions, "", v -> nextStrategy());
        Ui.action(this, actions, "🔑  Свой VPN-сервер (QR)", v -> keyDialog());
        switchBtn = Ui.action(this, actions, "", v -> switchMode());
        Ui.action(this, actions, "🧪  Проверить YouTube", v -> test());
        Ui.action(this, actions, "📺  Открыть YouTube", v -> adb.run(
                "monkey -p " + YouTube.YOUTUBE_PKG + " -c android.intent.category.LEANBACK_LAUNCHER 1", null));
        Ui.action(this, actions, "⏹  Выключить обход", v -> Ui.confirm(this, "Выключить обход?",
                "YouTube снова будет тормозить.", () -> adb.background(() -> {
                    YouTube.stop(this, adb);
                    adb.ui(() -> Ui.toast(this, "Обход выключен"));
                })));
        updateNext();
        for (String s : STEPS) {
            Ui.Row r = new Ui.Row();
            r.mark = "○";
            r.name = s;
            adapter.rows.add(r);
        }
        adapter.notifyDataSetChanged();
        actions.getChildAt(0).requestFocus();
        if (getIntent().getBooleanExtra("autostart", false)) runSetup();
    }

    private void updateNext() {
        boolean v = YouTube.hasVpn(this);
        switchBtn.setText(!v ? "🔀  Свой VPN: не задан" : "vpn".equals(YouTube.active(this))
                ? "🔀  Сейчас: свой VPN → включить обход" : "🔀  Сейчас: обход → включить свой VPN");
        nextBtn.setText("🔁  Тормозит? Другая стратегия (" + (YouTube.strategy(this) + 1) + "/" + YouTube.STRATEGIES.length + ")");
    }

    @Override
    protected void onRowClick(Ui.Row r, int pos) { }

    private void mark(int i, String state, String text) {
        adb.ui(() -> {
            Ui.Row r = adapter.rows.get(i);
            switch (state) {
                case "ok": r.mark = "✔"; r.badgeColor = 0xFF3DDC84; break;
                case "warn": r.mark = "⚠"; r.badgeColor = 0xFFFFB020; break;
                case "bad": r.mark = "✖"; r.badgeColor = 0xFFFF5A5F; break;
                default: r.mark = "⏳"; r.badgeColor = 0xFF8A93A6;
            }
            r.badge = text.length() > 45 ? text.substring(0, 45) + "…" : text;
            if (state.equals("bad") || state.equals("warn")) r.sub = text;
            adapter.notifyDataSetChanged();
        });
    }

    private void runSetup() {
        if (busy || !Ui.needAdb(this)) return;
        busy = true;
        hint.setText("Настраиваю… Установка может занять пару минут, не выключай ТВ.");
        adb.background(() -> {
            boolean ok = YouTube.setup(this, adb, this::mark);
            adb.ui(() -> {
                busy = false;
                hint.setText(ok ? "✔ Готово! Открывай YouTube. Если видео всё равно тормозит — нажми «Другая стратегия»."
                        : "Готово с замечаниями — смотри пункты с ⚠/✖.");
            });
        });
    }

    private void nextStrategy() {
        if (busy || !Ui.needAdb(this)) return;
        if (!YouTube.installed(this, YouTube.DPI_PKG)) { Ui.toast(this, "Сначала нажми «Настроить всё»"); return; }
        busy = true;
        int next = (YouTube.strategy(this) + 1) % YouTube.STRATEGIES.length;
        hint.setText("Переключаю на стратегию " + (next + 1) + "…");
        adb.background(() -> {
            boolean ok = YouTube.start(this, adb, next);
            adb.ui(() -> {
                busy = false;
                updateNext();
                hint.setText(ok ? "✔ Стратегия " + (next + 1) + " включена. Проверь видео в YouTube; если тормозит — нажми ещё раз."
                        : "⚠ Обход не запустился — нажми «Настроить всё».");
            });
        });
    }

    // ================================================= свой VPN-сервер

    private void keyDialog() {
        if (busy || !Ui.needAdb(this)) return;
        String url = phone.start(this, "ТВ Турбо — свой VPN для YouTube",
                "Вставь ключ своего сервера (vless://, vmess://, trojan://, ss://, hy2:// или ссылку подписки) "
                        + "или считай его QR-код с фото/скриншота. Через сервер пойдёт ТОЛЬКО YouTube.", true, link -> {
                    if (!YouTube.looksLikeKey(link)) { Ui.toast(this, "Это не похоже на ключ VPN — проверь и отправь ещё раз"); return; }
                    if (keyDlg != null) keyDlg.dismiss();
                    applyKey(link);
                });
        if (url == null) { Ui.toast(this, "ТВ не в домашней сети — подключи Wi-Fi или кабель"); return; }
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
        t.setText("1. Наведи камеру телефона на QR-код (телефон — в той же Wi-Fi)\n   или открой в браузере: " + url
                + "\n2. Вставь ключ сервера или нажми «Сфотографировать QR-код ключа»\n3. «Отправить на ТВ» — дальше всё сам."
                + "\n\nСейчас: " + YouTube.vpnLabel(this));
        box.addView(t);
        keyDlg = new AlertDialog.Builder(this).setTitle("🔑 Свой VPN-сервер для YouTube").setView(box)
                .setNegativeButton("Закрыть", null)
                .setOnDismissListener(d -> phone.stop())
                .create();
        keyDlg.show();
    }

    /** Поставить клиент, выдать разрешения и передать ему ключ (ключ — только внутри ТВ, без командной строки). */
    private void applyKey(String link) {
        busy = true;
        hint.setText("Ставлю «ТВ Турбо VPN»… (пара минут при первой установке)");
        adb.background(() -> {
            String err = YouTube.installVpn(this, adb, t -> adb.ui(() -> hint.setText("ТВ Турбо VPN: " + t)));
            if (err != null) { adb.ui(() -> { busy = false; hint.setText("✖ ТВ Турбо VPN " + err); }); return; }
            YouTube.vpnGrant(adb);
            adb.ui(() -> {
                hint.setText("Передаю ключ…");
                pendingLink = link;
                try { startActivityForResult(YouTube.vpnConfigIntent(this, link), REQ_VPN); }
                catch (Throwable t) { busy = false; hint.setText("✖ Не удалось передать ключ: " + t.getMessage()); }
            });
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VPN) return;
        String link = pendingLink;
        pendingLink = null;
        if (res != RESULT_OK || link == null) {
            busy = false;
            String e = data != null ? data.getStringExtra("error") : null;
            hint.setText("✖ Ключ не подошёл" + (e != null ? ": " + e : "") + ". Проверь ссылку и отправь ещё раз.");
            return;
        }
        int n = data != null ? data.getIntExtra("count", 0) : 0;
        YouTube.saveVpnLink(this, link);
        YouTube.setActive(this, "vpn");
        hint.setText("Ключ принят (серверов: " + n + "). Включаю свой VPN для YouTube…");
        adb.background(() -> {
            boolean ok = YouTube.startVpn(this, adb);
            boolean works = ok && YouTube.youtubeWorks("vpn");
            adb.ui(() -> {
                busy = false;
                updateNext();
                hint.setText(!ok ? "⚠ Свой VPN не запустился — нажми «Проверить YouTube» или отправь ключ ещё раз."
                        : works ? "✔ Готово! YouTube идёт через твой сервер, всё остальное — напрямую. Страж сам переключит на обход, если сервер перестанет отвечать."
                        : "⚠ VPN включён, но YouTube через сервер не открылся. Проверь, что сервер работает, или включи обход кнопкой «Сейчас: …».");
            });
        });
    }

    private void switchMode() {
        if (busy || !Ui.needAdb(this)) return;
        if (!YouTube.hasVpn(this)) { keyDialog(); return; }
        boolean toVpn = !"vpn".equals(YouTube.active(this));
        busy = true;
        hint.setText(toVpn ? "Включаю свой VPN…" : "Включаю обход…");
        adb.background(() -> {
            boolean ok = toVpn ? YouTube.startVpn(this, adb) : YouTube.startDpi(this, adb);
            boolean works = ok && YouTube.youtubeWorks(toVpn ? "vpn" : "dpi");
            adb.ui(() -> {
                busy = false;
                updateNext();
                hint.setText((ok ? "✔ " : "⚠ ") + (toVpn ? "Свой VPN" : "Обход") + (ok ? " включён" : " не запустился")
                        + (ok ? (works ? ", YouTube открывается." : ", но YouTube пока не открывается.") : "."));
            });
        });
    }

    private void test() {
        if (busy || !Ui.needAdb(this)) return;
        busy = true;
        hint.setText("Проверяю, открывается ли YouTube…");
        adb.background(() -> {
            String act = YouTube.active(this);
            boolean vpnUp = YouTube.isVpnRunning(adb), dpiUp = YouTube.isRunning(adb);
            boolean net = YouTube.internet();
            boolean works = (vpnUp || dpiUp) && YouTube.youtubeWorks(vpnUp ? "vpn" : "dpi");
            adb.ui(() -> {
                busy = false;
                String now = vpnUp ? "свой VPN (" + YouTube.vpnLabel(this) + ")" : dpiUp ? "обход, стратегия " + (YouTube.strategy(this) + 1) : "ничего не включено";
                hint.setText("Интернет: " + (net ? "✔" : "✖ нет") + "   ·   Сейчас: " + now + "   ·   YouTube: "
                        + (works ? "✔ открывается" : "✖ не открывается — попробуй «Сейчас: …» или «Другая стратегия»"));
            });
        });
    }

    @Override
    protected void onDestroy() {
        phone.stop();
        super.onDestroy();
    }
}
