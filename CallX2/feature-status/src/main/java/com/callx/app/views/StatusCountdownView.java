package com.callx.app.views;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.AttributeSet;
import android.view.View;
import java.util.Locale;

/** StatusCountdownView v26 — Animated HH:MM:SS countdown overlay. */
public class StatusCountdownView extends View {
    private long targetTs = 0;
    private String label  = "Happening in";
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler  = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    public StatusCountdownView(Context ctx) { this(ctx,null); }
    public StatusCountdownView(Context ctx, AttributeSet a) {
        super(ctx,a);
        textPaint.setColor(Color.WHITE); textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        bgPaint.setColor(Color.parseColor("#99000000"));
    }

    public void setCountdown(long targetTimestampMs, String lbl) {
        targetTs = targetTimestampMs; if (lbl!=null) label = lbl; startTick();
    }
    public void stop() { if (ticker!=null){handler.removeCallbacks(ticker); ticker=null;} }

    private void startTick() {
        if (ticker!=null) handler.removeCallbacks(ticker);
        ticker = new Runnable(){ @Override public void run(){
            invalidate(); if(System.currentTimeMillis()<targetTs) handler.postDelayed(this,1000);
        }};
        handler.post(ticker);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w=getWidth(), h=getHeight(), pad=24;
        canvas.drawRoundRect(pad, h/2f-60, w-pad, h/2f+60, 16,16, bgPaint);
        long diff = Math.max(0, targetTs - System.currentTimeMillis());
        long hrs=(diff/3_600_000L), mins=(diff%3_600_000L)/60_000L, secs=(diff%60_000L)/1000L;
        textPaint.setTextSize(18); canvas.drawText(label, w/2f, h/2f-24, textPaint);
        textPaint.setTextSize(44); canvas.drawText(String.format(Locale.US,"%02d:%02d:%02d",hrs,mins,secs), w/2f, h/2f+36, textPaint);
    }
}
