package com.callx.app.viewer;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * v509: LinearLayout whose children never overlap each other (status viewer chrome groups).
 * Returning false from hasOverlappingRendering() lets the renderer apply an alpha fade per draw
 * op instead of rendering the whole group into an offscreen layer on every frame.
 */
public class NoOverlapLinearLayout extends LinearLayout {
    public NoOverlapLinearLayout(Context c) { super(c); }
    public NoOverlapLinearLayout(Context c, AttributeSet a) { super(c, a); }
    public NoOverlapLinearLayout(Context c, AttributeSet a, int s) { super(c, a, s); }
    @Override public boolean hasOverlappingRendering() { return false; }
}
