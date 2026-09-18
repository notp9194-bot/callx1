package com.callx.app.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.callx.app.chat.R;

/**
 * Lightweight input-bar container.
 *
 * <p>The old input bar paid for this hierarchy on every keyboard, line-count,
 * and icon-state relayout:</p>
 *
 * <pre>
 * floating stack -> horizontal row -> MaterialCardView -> ConstraintLayout
 * </pre>
 *
 * <p>This view owns the capsule's small, fixed layout directly. It keeps the
 * input controls and the lazy recording bar as direct children, so one custom
 * measure/layout pass replaces three generic ViewGroup passes. The recording
 * bar remains lazy and can replace the normal controls without hiding the
 * capsule itself.</p>
 */
public final class ChatInputBarContainer extends ViewGroup {

    private static final int INPUT_ROW_HORIZONTAL_INSET_DP = 4;
    private static final int INPUT_ROW_VERTICAL_INSET_DP = 6;
    private static final int FIXED_ICON_SLOT_DP = 34;

    private final int horizontalInsetPx;
    private final int verticalInsetPx;
    private final int fixedIconSlotPx;

    private boolean inputContentVisible = true;

    public ChatInputBarContainer(Context context) {
        this(context, null);
    }

    public ChatInputBarContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        horizontalInsetPx = Math.round(INPUT_ROW_HORIZONTAL_INSET_DP * density);
        verticalInsetPx = Math.round(INPUT_ROW_VERTICAL_INSET_DP * density);
        fixedIconSlotPx = Math.round(FIXED_ICON_SLOT_DP * density);
        setClipChildren(false);
        setClipToPadding(false);
    }

    /**
     * Shows/hides only the normal input controls. The container itself stays
     * visible so the lazily inflated recording bar can occupy the same slot.
     */
    public void setInputContentVisible(boolean visible) {
        if (inputContentVisible == visible) return;
        inputContentVisible = visible;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (isRecordingChild(child)) continue;
            child.setVisibility(visible ? VISIBLE : GONE);
        }
        requestLayout();
        invalidate();
    }

    public boolean isInputContentVisible() {
        return inputContentVisible;
    }

    private boolean isRecordingChild(View child) {
        int id = child.getId();
        return id == R.id.stub_recording_bar || id == R.id.ll_recording_bar;
    }

    private View directChild(int id) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getId() == id) return child;
        }
        return null;
    }

    private View recordingChild() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getId() == R.id.ll_recording_bar) return child;
        }
        return null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = 0;
        }

        View recording = recordingChild();
        if (recording != null && recording.getVisibility() != GONE) {
            int childWidth = Math.max(0, width);
            recording.measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int desiredWidth = childWidth;
            int desiredHeight = recording.getMeasuredHeight();
            setMeasuredDimension(
                    resolveSize(desiredWidth, widthMeasureSpec),
                    resolveSize(desiredHeight, heightMeasureSpec));
            return;
        }

        View viewOnce = directChild(R.id.btn_view_once);
        View message = directChild(R.id.et_message);
        View iconBar = directChild(R.id.chat_icon_bar);

        int contentWidth = Math.max(0, width - horizontalInsetPx * 2);
        int onceWidth = fixedIconSlotPx;
        int iconWidth = 0;

        if (viewOnce != null && viewOnce.getVisibility() != GONE) {
            viewOnce.measure(
                    MeasureSpec.makeMeasureSpec(fixedIconSlotPx, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fixedIconSlotPx, MeasureSpec.EXACTLY));
        }

        if (iconBar != null && iconBar.getVisibility() != GONE) {
            iconBar.measure(
                    MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(fixedIconSlotPx, MeasureSpec.EXACTLY));
            iconWidth = iconBar.getMeasuredWidth();
        }

        int messageWidth = Math.max(0,
                contentWidth - onceWidth - iconWidth - horizontalInsetPx * 2);
        if (message != null && message.getVisibility() != GONE) {
            message.measure(
                    MeasureSpec.makeMeasureSpec(messageWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }

        int contentHeight = fixedIconSlotPx;
        if (message != null && message.getVisibility() != GONE) {
            contentHeight = Math.max(contentHeight, message.getMeasuredHeight());
        }
        if (viewOnce != null && viewOnce.getVisibility() != GONE) {
            contentHeight = Math.max(contentHeight, viewOnce.getMeasuredHeight());
        }
        if (iconBar != null && iconBar.getVisibility() != GONE) {
            contentHeight = Math.max(contentHeight, iconBar.getMeasuredHeight());
        }

        int desiredWidth = Math.max(0, width);
        int desiredHeight = verticalInsetPx * 2 + contentHeight;
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;

        View recording = recordingChild();
        if (recording != null && recording.getVisibility() != GONE) {
            recording.layout(0, 0, width, height);
            return;
        }

        View viewOnce = directChild(R.id.btn_view_once);
        View message = directChild(R.id.et_message);
        View iconBar = directChild(R.id.chat_icon_bar);

        int contentLeft = horizontalInsetPx;
        int contentRight = Math.max(contentLeft, width - horizontalInsetPx);
        int centerY = height / 2;

        if (viewOnce != null && viewOnce.getVisibility() != GONE) {
            int childWidth = viewOnce.getMeasuredWidth();
            int childHeight = viewOnce.getMeasuredHeight();
            int childTop = centerY - childHeight / 2;
            viewOnce.layout(contentLeft, childTop,
                    contentLeft + childWidth, childTop + childHeight);
            contentLeft += childWidth;
        }

        if (iconBar != null && iconBar.getVisibility() != GONE) {
            int childWidth = iconBar.getMeasuredWidth();
            int childHeight = iconBar.getMeasuredHeight();
            int childLeft = contentRight - childWidth;
            int childTop = centerY - childHeight / 2;
            iconBar.layout(childLeft, childTop,
                    childLeft + childWidth, childTop + childHeight);
            contentRight = Math.max(contentLeft, childLeft);
        }

        if (message != null && message.getVisibility() != GONE) {
            int childLeft = Math.min(contentRight, contentLeft + horizontalInsetPx);
            int childRight = Math.max(childLeft, contentRight - horizontalInsetPx);
            int childHeight = message.getMeasuredHeight();
            int childTop = centerY - childHeight / 2;
            message.layout(childLeft, childTop, childRight, childTop + childHeight);
        }
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new MarginLayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams params) {
        return params instanceof MarginLayoutParams;
    }
}