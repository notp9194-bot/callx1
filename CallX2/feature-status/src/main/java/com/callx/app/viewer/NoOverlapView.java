package com.callx.app.viewer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/** v509: plain View (gradient scrims) — alpha fades without an offscreen layer. */
public class NoOverlapView extends View {
    public NoOverlapView(Context c) { super(c); }
    public NoOverlapView(Context c, AttributeSet a) { super(c, a); }
    public NoOverlapView(Context c, AttributeSet a, int s) { super(c, a, s); }
    @Override public boolean hasOverlappingRendering() { return false; }
}
