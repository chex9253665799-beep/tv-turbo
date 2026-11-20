package ru.chex.turbohome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Stores a downsampled wallpaper in private app storage (no large original held in RAM). */
public final class PhotoWallpaper {
    public static final String FILE_NAME = "turbo_wallpaper.jpg";
    private PhotoWallpaper() {}
    private static Bitmap cached;
    private static long cachedModified;

    public static boolean save(Context context, Uri uri) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            BitmapFactory.Options header = new BitmapFactory.Options();
            header.inJustDecodeBounds = true;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return false;
                BitmapFactory.decodeStream(input, null, header);
            }
            if (header.outWidth <= 0 || header.outHeight <= 0) return false;
            int sample = 1;
            while (header.outWidth / sample > 1440 || header.outHeight / sample > 900) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap image;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return false;
                image = BitmapFactory.decodeStream(input, null, opts);
            }
            if (image == null) return false;
            File file = new File(context.getFilesDir(), FILE_NAME);
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 84, out)) return false;
            } finally {
                image.recycle();
            }
            synchronized(PhotoWallpaper.class) {
                if(cached!=null){cached.recycle();cached=null;}
                cachedModified=0;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized Drawable load(Context context, boolean dark, int opacity) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return null;
        if(cached==null || cached.isRecycled() || cachedModified!=file.lastModified()) {
            if(cached!=null && !cached.isRecycled())cached.recycle();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            cached = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            cachedModified=file.lastModified();
        }
        if (cached == null) return null;
        return new Picture(cached, dark, opacity);
    }

    private static final class Picture extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dest = new RectF();
        private final boolean dark;
        private final int opacity;
        Picture(Bitmap bitmap, boolean dark, int opacity) {
            this.bitmap = bitmap;
            this.dark = dark;
            this.opacity = opacity;
        }
        @Override public void draw(Canvas canvas) {
            int w = getBounds().width(), h = getBounds().height();
            if (w == 0 || h == 0 || bitmap.isRecycled()) return;
            float scale = Math.max((float)w / bitmap.getWidth(), (float)h / bitmap.getHeight());
            int cropW = Math.min(bitmap.getWidth(), (int)(w / scale));
            int cropH = Math.min(bitmap.getHeight(), (int)(h / scale));
            int x = (bitmap.getWidth() - cropW) / 2, y = (bitmap.getHeight() - cropH) / 2;
            src.set(x,y,x+cropW,y+cropH);
            dest.set(getBounds().left,getBounds().top,getBounds().right,getBounds().bottom);
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap,src,dest,paint);
            int overlay = Math.max(30,Math.min(200,opacity));
            paint.setColor(dark ? Color.BLACK : Color.WHITE);
            paint.setAlpha(overlay);
            canvas.drawRect(dest,paint);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter c) {}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
}
