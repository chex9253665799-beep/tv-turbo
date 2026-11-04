package ru.chex.tvturbo.mv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ТВ-каналы из ПОДКЛЮЧЁННОГО пользователем плейлиста M3U (от его провайдера или IPTV-сервиса).
 * Своих каналов и потоков не создаём. Просмотр — через установленный плеер.
 */
public final class Tv {

    public static final class Channel { public String name = "", logo = "", group = "", url = ""; }

    public static String url(Context c) { return Kit.sp(c).getString("m3u", ""); }
    public static void setUrl(Context c, String u) { Kit.sp(c).edit().putString("m3u", u.trim()).apply(); file(c).delete(); }

    private static File file(Context c) { return new File(c.getFilesDir(), "mv_playlist.m3u"); }

    /** Каналы: из кэша, а раз в 6 часов — заново с адреса. В фоне. */
    public static List<Channel> load(Context c, boolean force) throws Exception {
        String u = url(c);
        if (u.isEmpty()) return new ArrayList<>();
        File f = file(c);
        String text = null;
        if (!force && f.isFile() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) {
            try (FileInputStream in = new FileInputStream(f)) { text = new String(Img.readAll(in), "UTF-8"); }
        }
        if (text == null) {
            HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
            con.setConnectTimeout(10000); con.setReadTimeout(30000);
            if (con.getResponseCode() != 200) throw new IllegalStateException("плейлист не открылся: " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) { text = new String(Img.readAll(in), "UTF-8"); }
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes("UTF-8")); }
        }
        return parse(text);
    }

    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

    static List<Channel> parse(String m3u) {
        List<Channel> out = new ArrayList<>();
        Channel cur = null;
        for (String raw : m3u.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) {
                cur = new Channel();
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    if (m.group(1).equals("tvg-logo")) cur.logo = m.group(2);
                    if (m.group(1).equals("group-title")) cur.group = m.group(2);
                }
                int comma = line.lastIndexOf(',');
                cur.name = comma >= 0 ? line.substring(comma + 1).trim() : "Канал";
            } else if (line.startsWith("#EXTGRP:") && cur != null) {
                cur.group = line.substring(8).trim();
            } else if (!line.isEmpty() && !line.startsWith("#") && cur != null) {
                cur.url = line;
                if (cur.group.isEmpty()) cur.group = "Без группы";
                out.add(cur);
                cur = null;
            }
        }
        return out;
    }

    public static Map<String, List<Channel>> groups(List<Channel> all) {
        Map<String, List<Channel>> m = new LinkedHashMap<>();
        for (Channel ch : all) {
            List<Channel> l = m.get(ch.group);
            if (l == null) m.put(ch.group, l = new ArrayList<>());
            l.add(ch);
        }
        return m;
    }

    private Tv() { }
}
