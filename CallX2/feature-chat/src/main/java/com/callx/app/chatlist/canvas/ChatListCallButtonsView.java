package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.callx.app.chat.R;

/**
 * ChatListCallButtonsView — v84 modern circular call buttons.
 *
 * v84 CHANGE: the chat list row's call buttons were still the old v82/v83
 * hand-drawn line-icon Paths (a thin green camera glyph + phone-arc glyph),
 * left over from before the app-wide call UI redesign. Every other call
 * entry point — the chat screen's toolbar, the Calls tab, and the contact
 * bottom sheet (bottom_sheet_contact_call.xml) — already uses the modern
 * filled-circle icon buttons (purple circle + video icon, green circle +
 * phone icon). This brings the chat list row in line with that: same two
 * colours (#5B5BF6 video / #22C55E voice), same ic_video_call/ic_phone
 * vector icons, just drawn on a lightweight canvas view instead of inflating
 * two more ImageViews per row (this view exists specifically to avoid that
 * inflate + layout cost in a scrolling RecyclerView).
 *
 * PERF: bounds/positions for both circles + icons are computed once in
 * onSizeChanged() (v83's approach kept) — onDraw() only draws two ovals and
 * two already-bounded Drawables, no per-frame allocation.
 */
public class ChatListCallButtonsView extends View {

    private static final int COLOR_VIDEO_CIRCLE = 0xFF5B5BF6;
    private static final int COLOR_VOICE_CIRCLE = 0xFF22C55E;

    private final Drawable videoIcon;
    private final Drawable phoneIcon;

    private final float density;
    private boolean boundsBaked = false;

    // v83: layout values cached in onSizeChanged
    private float midX = 0f;

    private Runnable onVoiceCall;
    private Runnable onVideoCall;

    private boolean touchDown    = false;

    public ChatListCallButtonsView(Context ctx) {
        this(ctx, null);
    }

    public ChatListCallButtonsView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;

        videoIcon = loadColoredIcon(ctx, R.drawable.ic_video_call, COLOR_VIDEO_CIRCLE);
        phoneIcon = loadColoredIcon(ctx, R.drawable.ic_phone, COLOR_VOICE_CIRCLE);
    }

    /** Loads a vector icon and tints it to the specified color, mutating its own state
     *  (not the shared cached constant state) so this doesn't bleed into
     *  any other place in the app using the same drawable resource. */
    private static Drawable loadColoredIcon(Context ctx, int drawableRes, int color) {
        Drawable d = ContextCompat.getDrawable(ctx, drawableRes);
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, color);
        return d;
    }

    /** Sets the click callbacks for voice and video call. */
    public void setListeners(Runnable voiceCall, Runnable videoCall) {
        this.onVoiceCall = voiceCall;
        this.onVideoCall = videoCall;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float w = (34 * 2 + 6) * density;
        float h = 34 * density;
        setMeasuredDimension(
            resolveSize((int) Math.ceil(w), widthMeasureSpec),
            resolveSize((int) Math.ceil(h), heightMeasureSpec)
        );
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        bakeBounds(w, h);
    }

    /**
     * Pre-calculate icon bounds and touch zone boundary. Called once in onSizeChanged().
     */
    private void bakeBounds(int tw, int th) {
        float btnW  = 34 * density;
        float gap   = 6 * density;
        float iconD = 16 * density;
        float cy    = th / 2f;

        // Video icon (left)
        float vx = btnW / 2f;
        if (videoIcon != null) {
            int l = Math.round(vx - iconD / 2f), t = Math.round(cy - iconD / 2f);
            videoIcon.setBounds(l, t, Math.round(l + iconD), Math.round(t + iconD));
        }

        // Voice icon (right)
        float px = btnW + gap + btnW / 2f;
        if (phoneIcon != null) {
            int l = Math.round(px - iconD / 2f), t = Math.round(cy - iconD / 2f);
            phoneIcon.setBounds(l, t, Math.round(l + iconD), Math.round(t + iconD));
        }

        midX = btnW + gap / 2f;
        boundsBaked = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int tw = getWidth();
        int th = getHeight();
        if (tw <= 0 || th <= 0) return;
        if (!boundsBaked) bakeBounds(tw, th);

        // Icons only, no circles or strokes
        if (videoIcon != null) videoIcon.draw(canvas);
        if (phoneIcon != null) phoneIcon.draw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDown = true;
                setPressed(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchDown = false;
                setPressed(false);
                return true;
            case MotionEvent.ACTION_UP:
                if (touchDown) {
                    touchDown = false;
                    setPressed(false);
                    performClick();
                    boolean video = e.getX() < midX;
                    if (video  && onVideoCall != null) onVideoCall.run();
                    else if (!video && onVoiceCall != null) onVoiceCall.run();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
