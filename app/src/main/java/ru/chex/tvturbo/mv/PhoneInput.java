package ru.chex.tvturbo.mv;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collections;

/**
 * Ввод длинного текста (ключа) с телефона: на ТВ поднимается маленькая страница в домашней сети,
 * открываешь её на телефоне, вставляешь ключ — он приходит на ТВ. Работает, пока открыт экран настроек.
 */
public final class PhoneInput {

    public interface Got { void on(String value); }

    private ServerSocket server;
    private volatile boolean running;

    public static final int PORT = 8095;

    public String start(Context c, String title, Got got) { return start(c, "MiVision TV", title, false, got); }

    /**
     * header — заголовок страницы, title — что ввести. scanQr — большое поле + кнопки «считать QR с фото/камеры»
     * (для ключей VPN: QR распознаётся прямо в браузере телефона, на ТВ уходит только текст).
     */
    public String start(Context c, String header, String title, boolean scanQr, Got got) {
        stop();
        String ip = ip(c);
        if (ip == null) return null;
        Handler main = new Handler(Looper.getMainLooper());
        running = true;
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                server = ss;
                while (running) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(5000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                        String first = in.readLine();
                        if (first == null) continue;
                        int len = 0;
                        String line;
                        while ((line = in.readLine()) != null && !line.isEmpty())
                            if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
                        String value = null;
                        if (first.startsWith("POST") && len > 0 && len < 32768) {
                            char[] buf = new char[len];
                            int r = 0;
                            while (r < len) { int n = in.read(buf, r, len - r); if (n < 0) break; r += n; }
                            String body = new String(buf, 0, r);
                            for (String kv : body.split("&"))
                                if (kv.startsWith("v=")) value = URLDecoder.decode(kv.substring(2), "UTF-8").trim();
                        }
                        String html = page(header, title, scanQr, value != null && !value.isEmpty());
                        byte[] b = html.getBytes("UTF-8");
                        OutputStream o = s.getOutputStream();
                        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        o.write(b);
                        o.flush();
                        if (value != null && !value.isEmpty()) { String v = value; main.post(() -> got.on(v)); }
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }).start();
        return "http://" + ip + ":" + PORT;
    }

    private static final String BTN = "display:block;width:100%;margin-top:12px;font-size:18px;padding:14px;border-radius:12px;border:0;";

    private static String page(String header, String title, boolean scan, boolean sent) {
        StringBuilder h = new StringBuilder("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + header + "</title><body style='font-family:sans-serif;background:#0A1640;color:#fff;padding:20px;max-width:560px;margin:auto'>"
                + "<h2 style='margin:0 0 6px'>" + header + "</h2><p style='color:#B8C2E0'>" + title + "</p>");
        if (sent) h.append("<p style='color:#3DDC84;font-size:18px'>✔ Отправлено на телевизор — смотри на экран ТВ</p>");
        if (!scan) {
            h.append("<form method=post><input name=v autofocus style='width:100%;box-sizing:border-box;font-size:18px;padding:12px;border-radius:10px;border:0'>"
                    + "<button style='" + BTN + "background:#2E6BFF;color:#fff'>Отправить на ТВ</button></form>");
            return h.toString();
        }
        h.append("<label style='" + BTN + "background:#1E2F66;color:#fff;text-align:center'>📷 Сфотографировать QR-код ключа"
                + "<input type=file accept='image/*' capture=environment id=cam hidden></label>"
                + "<label style='" + BTN + "background:#1E2F66;color:#fff;text-align:center'>🖼 Выбрать скриншот с QR-кодом"
                + "<input type=file accept='image/*' id=gal hidden></label>"
                + "<p id=msg style='color:#FFB020;min-height:1em'></p>"
                + "<form method=post><textarea name=v id=v rows=6 placeholder='или вставь ссылку: vless://…  vmess://…  trojan://…  ss://…  hy2://…  или https://… (подписка)' "
                + "style='width:100%;box-sizing:border-box;font-size:15px;padding:12px;border-radius:10px;border:0'></textarea>"
                + "<button style='" + BTN + "background:#2E6BFF;color:#fff'>Отправить на ТВ</button></form>"
                + "<p style='color:#8A93A6;font-size:13px'>Ключ уходит только на твой телевизор по домашней сети и хранится только на нём.</p>"
                + "<script src='https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.min.js'></script><script>"
                + "async function rd(f){const m=document.getElementById('msg');m.textContent='Читаю QR…';let t=null;"
                + "try{const img=await createImageBitmap(f);"
                + "if('BarcodeDetector' in window){try{const r=await new BarcodeDetector({formats:['qr_code']}).detect(img);if(r[0])t=r[0].rawValue;}catch(e){}}"
                + "if(!t&&window.jsQR){const k=Math.min(1,1400/Math.max(img.width,img.height));const c=document.createElement('canvas');"
                + "c.width=Math.round(img.width*k);c.height=Math.round(img.height*k);const x=c.getContext('2d');x.drawImage(img,0,0,c.width,c.height);"
                + "const d=x.getImageData(0,0,c.width,c.height);const r=jsQR(d.data,c.width,c.height);if(r)t=r.data;}}catch(e){}"
                + "if(t){document.getElementById('v').value=t.trim();m.style.color='#3DDC84';m.textContent='✔ QR прочитан — нажми «Отправить на ТВ»';}"
                + "else{m.style.color='#FF5A5F';m.textContent='Не удалось прочитать QR. Сфотографируй ближе и ровнее или выбери скриншот.';}}"
                + "for(const id of ['cam','gal'])document.getElementById(id).onchange=e=>{if(e.target.files[0])rd(e.target.files[0]);};"
                + "</script>");
        return h.toString();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
    }

    @SuppressWarnings("deprecation")
    private static String ip(Context c) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
