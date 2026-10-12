package com.callx.app.compose;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Transparent finger-drawing layer used by the status story editor. */
final class StatusDrawOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Path> paths = new ArrayList<>();
    private Path activePath;
    private boolean drawEnabled;
    private float lastX, lastY;

    StatusDrawOverlayView(Context context) {
        super(context);
        init(context);
    }

    StatusDrawOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f * context.getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setDrawEnabled(boolean enabled) {
        drawEnabled = enabled;
        setClickable(enabled);
    }

    void clearDrawing() {
        paths.clear();
        activePath = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Path path : paths) canvas.drawPath(path, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!drawEnabled) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePath = new Path();
                lastX = event.getX();
                lastY = event.getY();
                activePath.moveTo(lastX, lastY);
                paths.add(activePath);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activePath != null) {
                    float x = event.getX();
                    float y = event.getY();
                    activePath.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f);
                    lastX = x;
                    lastY = y;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (activePath != null) activePath.lineTo(event.getX(), event.getY());
                activePath = null;
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                activePath = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
