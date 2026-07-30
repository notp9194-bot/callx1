package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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

    private static final int COLOR_VIDEO_CIRCLE = 0xFF5B5BF6; // matches bg_action_btn_circle_purple
    private static final int COLOR_VOICE_CIRCLE = 0xFF22C55E; // matches bg_action_btn_circle_green
    private static final int COLOR_STROKE = 0xFFFFFFFF; // white stroke for definition
    private static final float STROKE_WIDTH_DP = 1.5f;

    private final Paint circleVideoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circleVoicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // v83/v84: pre-allocated RectF for the two circle backgrounds — no new RectF in draw
    private final RectF circleVideoRect  = new RectF();
    private final RectF circleVoiceRect  = new RectF();

    // Shared white-tinted vector icons — same assets the toolbar/calls-tab/contact
    // sheet use, so the glyphs are pixel-identical across the app.
    private final Drawable videoIcon;
    private final Drawable phoneIcon;

    private final float density;
    private boolean boundsBaked = false;

    // v83: layout values cached in onSizeChanged
    private float midX = 0f;  // x boundary between video and phone touch zones
    private float strokeWidth = 0f;

    private Runnable onVoiceCall;
    private Runnable onVideoCall;

    private boolean touchDown    = false;

    public ChatListCallButtonsView(Context ctx) {
        this(ctx, null);
    }

    public ChatListCallButtonsView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        strokeWidth = STROKE_WIDTH_DP * density;

        circleVideoPaint.setColor(COLOR_VIDEO_CIRCLE);
        circleVideoPaint.setStyle(Paint.Style.FILL);

        circleVoicePaint.setColor(COLOR_VOICE_CIRCLE);
        circleVoicePaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(COLOR_STROKE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);

        videoIcon = loadWhiteIcon(ctx, R.drawable.ic_video_call);
        phoneIcon = loadWhiteIcon(ctx, R.drawable.ic_phone);
    }

    /** Loads a vector icon and force-tints it white, mutating its own state
     *  (not the shared cached constant state) so this doesn't bleed into
     *  any other place in the app using the same drawable resource. */
    private static Drawable loadWhiteIcon(Context ctx, int drawableRes) {
        Drawable d = ContextCompat.getDrawable(ctx, drawableRes);
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, 0xFFFFFFFF);
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
     * v83/v84: pre-bake both circle rects + icon drawable bounds based on the
     * view's current dimensions. Called once in onSizeChanged(); onDraw()
     * just plays them back.
     */
    private void bakeBounds(int tw, int th) {
        float btnW    = 34 * density;
        float gap     = 6  * density;
        float circleD = 30 * density; // circle diameter, centred in each 34dp slot
        float iconD   = 16 * density; // icon glyph size inside the circle
        float cy      = th / 2f;

        // ── Video button (left) — purple circle ──────────────────────────
        float vx = btnW / 2f;
        circleVideoRect.set(vx - circleD / 2f, cy - circleD / 2f, vx + circleD / 2f, cy + circleD / 2f);
        if (videoIcon != null) {
            int l = Math.round(vx - iconD / 2f), t = Math.round(cy - iconD / 2f);
            videoIcon.setBounds(l, t, Math.round(l + iconD), Math.round(t + iconD));
        }

        // ── Voice button (right) — green circle ───────────────────────────
        float px = btnW + gap + btnW / 2f;
        circleVoiceRect.set(px - circleD / 2f, cy - circleD / 2f, px + circleD / 2f, cy + circleD / 2f);
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

        // v84 hot path: two filled circles + stroke outlines + two already-bounded Drawable
        // draws, zero allocations.
        canvas.drawOval(circleVideoRect, circleVideoPaint);
        canvas.drawOval(circleVideoRect, strokePaint);  // white stroke for video button
        canvas.drawOval(circleVoiceRect, circleVoicePaint);
        canvas.drawOval(circleVoiceRect, strokePaint);  // white stroke for voice button
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
