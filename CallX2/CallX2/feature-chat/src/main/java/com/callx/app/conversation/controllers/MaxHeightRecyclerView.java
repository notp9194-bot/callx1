package com.callx.app.conversation.controllers;

import android.content.Context;
import android.util.AttributeSet;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Plain RecyclerView plus a settable pixel ceiling on its measured height.
 * RecyclerView (unlike TextView/ImageView) doesn't support android:maxHeight
 * out of the box, and the "Recents ▾" folder-picker popup
 * (popup_attach_folder_list.xml / AttachSheetFolderPicker) needs one so a
 * long folder list scrolls internally instead of measuring to full content
 * height and running off the bottom of the screen inside a wrap_content
 * PopupWindow.
 */
public final class MaxHeightRecyclerView extends RecyclerView {

    private int maxHeightPx = -1;

    public MaxHeightRecyclerView(Context context) {
        super(context);
    }

    public MaxHeightRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setMaxHeightPx(int maxHeightPx) {
        this.maxHeightPx = maxHeightPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int effectiveHeightSpec = heightSpec;
        if (maxHeightPx >= 0) {
            int mode = MeasureSpec.getMode(heightSpec);
            int size = MeasureSpec.getSize(heightSpec);
            if (mode == MeasureSpec.UNSPECIFIED || size > maxHeightPx) {
                effectiveHeightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
            }
        }
        super.onMeasure(widthSpec, effectiveHeightSpec);
    }
}
