package com.sharath.tvscreensaver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ClockView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.US);
    private long animStart = System.currentTimeMillis();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, 500);
        }
    };

    public ClockView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animStart = System.currentTimeMillis();
        handler.postDelayed(tick, 500);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        long t = System.currentTimeMillis() - animStart;

        canvas.drawColor(Color.BLACK);

        float cx = (float) (w / 2.0 + Math.sin(t / 17000.0) * w * 0.30);
        float cy = (float) (h / 2.0 + Math.cos(t / 21000.0) * h * 0.28);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0E1622);
        canvas.drawCircle(cx, cy, Math.min(w, h) * 0.34f, paint);

        float cx2 = (float) (w / 2.0 + Math.cos(t / 23000.0) * w * 0.26);
        float cy2 = (float) (h / 2.0 + Math.sin(t / 19000.0) * h * 0.24);
        paint.setColor(0xFF131A12);
        canvas.drawCircle(cx2, cy2, Math.min(w, h) * 0.26f, paint);

        Date now = new Date();

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        paint.setColor(0xFFF2F2F2);
        paint.setTextSize(Math.min(w, h) * 0.24f);
        canvas.drawText(timeFmt.format(now), w / 2f, h / 2f + h * 0.06f, paint);

        paint.setColor(0xFF9AA5B1);
        paint.setTextSize(Math.min(w, h) * 0.055f);
        canvas.drawText(dateFmt.format(now), w / 2f, h / 2f + h * 0.17f, paint);

        float dotX = (float) (w * 0.5 + Math.sin(t / 13000.0) * w * 0.35);
        float dotY = (float) (h * 0.5 + Math.cos(t / 15000.0) * h * 0.30);
        paint.setColor(0x663D8BFF);
        canvas.drawCircle(dotX, dotY, Math.min(w, h) * 0.012f, paint);

        paint.setColor(0xFF6B7280);
        paint.setTextSize(Math.min(w, h) * 0.032f);
        canvas.drawText("Press any button to resume", w / 2f, h * 0.90f, paint);
    }
}
