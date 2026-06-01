package com.callx.app.views;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.*;
import java.util.*;

/** StatusDrawingOverlayView v26 — Finger drawing on status image. */
public class StatusDrawingOverlayView extends View {
    private final List<Path>  paths  = new ArrayList<>();
    private final List<Paint> paints = new ArrayList<>();
    private final Deque<Path>  undoPaths  = new ArrayDeque<>();
    private final Deque<Paint> undoPaints = new ArrayDeque<>();
    private Path currentPath;
    private int   currentColor = Color.WHITE;
    private float strokeWidth  = 8f;
    private boolean isEraser   = false;

    public StatusDrawingOverlayView(Context ctx) { super(ctx); init(); }
    public StatusDrawingOverlayView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() { setLayerType(LAYER_TYPE_SOFTWARE, null); }

    public void setColor(int color) { currentColor = color; isEraser = false; }
    public void setBrushSize(float size) { strokeWidth = size; }
    public void setEraser(boolean e) { isEraser = e; }

    public void undo() {
        if (!paths.isEmpty()) {
            undoPaths.push(paths.remove(paths.size()-1));
            undoPaints.push(paints.remove(paints.size()-1));
            invalidate();
        }
    }
    public void redo() {
        if (!undoPaths.isEmpty()) { paths.add(undoPaths.pop()); paints.add(undoPaints.pop()); invalidate(); }
    }
    public void clear() { paths.clear(); paints.clear(); undoPaths.clear(); undoPaints.clear(); invalidate(); }
    public boolean hasDrawing() { return !paths.isEmpty(); }

    public Bitmap exportBitmap() {
        Bitmap bmp = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        draw(new Canvas(bmp)); return bmp;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < paths.size(); i++) canvas.drawPath(paths.get(i), paints.get(i));
        if (currentPath != null) canvas.drawPath(currentPath, makePaint());
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: currentPath = new Path(); currentPath.moveTo(x,y); undoPaths.clear(); undoPaints.clear(); break;
            case MotionEvent.ACTION_MOVE: if (currentPath!=null){currentPath.lineTo(x,y); invalidate();} break;
            case MotionEvent.ACTION_UP:   if (currentPath!=null){paths.add(currentPath); paints.add(makePaint()); currentPath=null; invalidate();} break;
        }
        return true;
    }

    private Paint makePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(strokeWidth);
        if (isEraser) p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        else p.setColor(currentColor);
        return p;
    }
}
