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

    public String start(Context c, String title, Got got) {
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
                        if (first.startsWith("POST") && len > 0 && len < 4096) {
                            char[] buf = new char[len];
                            int r = 0;
                            while (r < len) { int n = in.read(buf, r, len - r); if (n < 0) break; r += n; }
                            String body = new String(buf, 0, r);
                            for (String kv : body.split("&"))
                                if (kv.startsWith("v=")) value = URLDecoder.decode(kv.substring(2), "UTF-8").trim();
                        }
                        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                                + "<body style='font-family:sans-serif;background:#0A1640;color:#fff;padding:24px'>"
                                + "<h2>MiVision TV</h2><p>" + title + "</p>"
                                + (value != null && !value.isEmpty() ? "<p style='color:#3DDC84'>✔ Отправлено на телевизор</p>" : "")
                                + "<form method=post><input name=v autofocus style='width:100%;font-size:18px;padding:12px;border-radius:10px;border:0'>"
                                + "<button style='margin-top:14px;font-size:18px;padding:12px 24px;border-radius:10px;border:0;background:#2E6BFF;color:#fff'>Отправить на ТВ</button></form>";
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
