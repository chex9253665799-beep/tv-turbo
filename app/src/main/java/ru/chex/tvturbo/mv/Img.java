package ru.chex.tvturbo.mv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.chex.tvturbo.Adb;

/** Загрузка постеров: http(s) напрямую, content:// других приложений — через ADB. Кэш в памяти и на диске. */
public final class Img {

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** maxW — ширина, до которой уменьшать картинку (экономия памяти на слабых ТВ). */
    public static void load(Context c, String uri, ImageView into, int maxW) {
        if (uri == null || uri.isEmpty()) return;
        into.setTag(uri);
        Bitmap hit = MEM.get(uri + maxW);
        if (hit != null) { into.setImageBitmap(hit); return; }
        Context app = c.getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = null;
            try {
                byte[] data = readDisk(app, uri);
                if (data == null) {
                    data = fetch(app, uri);
                    if (data != null) writeDisk(app, uri, data);
                }
                if (data != null) b = decode(data, maxW);
            } catch (Throwable ignored) { }
            Bitmap res = b;
            if (res != null) MEM.put(uri + maxW, res);
            MAIN.post(() -> { if (res != null && uri.equals(into.getTag())) into.setImageBitmap(res); });
        });
    }

    private static byte[] fetch(Context c, String uri) throws Exception {
        if (uri.startsWith("http")) {
            String u = uri;
            for (int i = 0; i < 5; i++) {
                HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
                con.setConnectTimeout(8000); con.setReadTimeout(15000);
                con.setInstanceFollowRedirects(false);
                int code = con.getResponseCode();
                if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                    u = new URL(new URL(u), con.getHeaderField("Location")).toString(); continue;
                }
                if (code != 200) return null;
                try (InputStream in = con.getInputStream()) { return readAll(in); }
            }
            return null;
        }
        if (uri.startsWith("content://") || uri.startsWith("android.resource://")) {
            // чужой content:// нашему приложению не открыть — читаем от имени shell
            return Adb.get(c).readBytes("content read --uri '" + uri.replace("'", "") + "'");
        }
        return null;
    }

    private static Bitmap decode(byte[] d, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(d, 0, d.length, o);
    }

    private static File diskFile(Context c, String uri) {
        File dir = new File(c.getCacheDir(), "posters");
        dir.mkdirs();
        return new File(dir, Integer.toHexString(uri.hashCode()) + "_" + uri.length());
    }

    private static byte[] readDisk(Context c, String uri) {
        File f = diskFile(c, uri);
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > 3L * 24 * 3600_000) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { return readAll(in); } catch (Throwable t) { return null; }
    }

    private static void writeDisk(Context c, String uri, byte[] d) {
        try (FileOutputStream o = new FileOutputStream(diskFile(c, uri))) { o.write(d); } catch (Throwable ignored) { }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[32768]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    private Img() { }
}
