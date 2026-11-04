package ru.chex.tvturbo;

import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

/**
 * Показать текст на телефоне: ТВ поднимает страницу в домашней сети, телефон открывает её по QR.
 * Работает, пока открыто окно на ТВ.
 */
public final class TextShare {

    public static final int PORT = 8096;
    private ServerSocket server;
    private volatile boolean running;

    /** Возвращает адрес страницы или null, если ТВ не в сети. */
    public String start(String title, String text) {
        stop();
        String ip = ip();
        if (ip == null) return null;
        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(title) + "</title><body style='font-family:sans-serif;background:#0A1640;color:#fff;padding:16px'>"
                + "<h2>" + esc(title) + "</h2>"
                + "<button onclick=\"navigator.clipboard&&navigator.clipboard.writeText(document.getElementById('t').innerText).then(()=>this.textContent='✔ Скопировано')\" "
                + "style='font-size:17px;padding:12px 20px;border-radius:10px;border:0;background:#2E6BFF;color:#fff'>Скопировать всё</button>"
                + "<pre id=t style='white-space:pre-wrap;font-size:14px;line-height:1.45'>" + esc(text) + "</pre>";
        byte[] body;
        try { body = html.getBytes("UTF-8"); } catch (Exception e) { return null; }
        running = true;
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                server = ss;
                while (running) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(4000);
                        byte[] buf = new byte[2048];
                        s.getInputStream().read(buf);      // запрос не нужен — страница одна
                        OutputStream o = s.getOutputStream();
                        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        o.write(body);
                        o.flush();
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

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String ip() {
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
