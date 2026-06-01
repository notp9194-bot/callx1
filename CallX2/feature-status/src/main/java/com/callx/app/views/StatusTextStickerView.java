package com.callx.app.views;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.*;

/** StatusTextStickerView v26 — Draggable, scalable, rotatable text sticker. */
public class StatusTextStickerView extends androidx.appcompat.widget.AppCompatTextView {
    private float scaleFactor = 1f;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector moveDetector;

    public StatusTextStickerView(Context ctx) { this(ctx,null); }
    public StatusTextStickerView(Context ctx, AttributeSet a) {
        super(ctx,a);
        setTextColor(Color.WHITE); setTextSize(22);
        setTypeface(null, Typeface.BOLD); setShadowLayer(4,1,1,Color.BLACK);
        setPadding(16,8,16,8);
        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d) {
                scaleFactor = Math.max(0.5f, Math.min(4f, scaleFactor * d.getScaleFactor()));
                setScaleX(scaleFactor); setScaleY(scaleFactor); return true;
            }
        });
        moveDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy){
                setX(getX()-dx); setY(getY()-dy); return true;
            }
        });
        setOnTouchListener((v,e) -> { scaleDetector.onTouchEvent(e); moveDetector.onTouchEvent(e); return true; });
    }
}
