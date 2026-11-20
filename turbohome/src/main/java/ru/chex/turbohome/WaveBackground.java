package ru.chex.turbohome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** CPU-cheap static gradients rendered by Android Canvas, with no video or blurred buffers. */
public final class WaveBackground extends Drawable {
    private final int base, secondary, accent;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private int alpha=255;

    public WaveBackground(int base,int secondary,int accent){
        this.base=base;
        this.secondary=secondary;
        this.accent=accent;
    }

    @Override public void draw(Canvas canvas){
        int width=getBounds().width(), height=getBounds().height();
        if(width<=0||height<=0)return;
        paint.setAlpha(alpha);
        paint.setShader(new LinearGradient(0,0,width,height,
                new int[]{base,secondary,base},new float[]{0f,.65f,1f},Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,width,height,paint);
        paint.setShader(new RadialGradient(width*.86f,height*.60f,width*.65f,
                new int[]{withAlpha(accent,105),withAlpha(accent,26),Color.TRANSPARENT},
                new float[]{0f,.48f,1f},Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,width,height,paint);
        wave.reset();
        wave.moveTo(0,height*.76f);
        wave.cubicTo(width*.22f,height*.49f,width*.43f,height*.98f,width*.73f,height*.66f);
        wave.cubicTo(width*.87f,height*.49f,width*.93f,height*.50f,width,height*.48f);
        wave.lineTo(width,height);
        wave.lineTo(0,height);
        wave.close();
        paint.setShader(new LinearGradient(0,height*.45f,width,height,
                new int[]{withAlpha(accent,70),withAlpha(0xFF6630BF,24),withAlpha(0xFF11205A,10)},
                null,Shader.TileMode.CLAMP));
        canvas.drawPath(wave,paint);
        wave.reset();
        wave.moveTo(width*.4f,0);
        wave.cubicTo(width*.63f,height*.21f,width*.7f,height*.13f,width,height*.22f);
        wave.lineTo(width,0);
        wave.close();
        paint.setShader(new LinearGradient(0,0,width,height*.6f,
                new int[]{withAlpha(accent,4),withAlpha(accent,70)},null,Shader.TileMode.CLAMP));
        canvas.drawPath(wave,paint);
        paint.setShader(null);
    }

    private int withAlpha(int color,int a){return (color&0x00FFFFFF)|((a*alpha/255)<<24);}
    @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
