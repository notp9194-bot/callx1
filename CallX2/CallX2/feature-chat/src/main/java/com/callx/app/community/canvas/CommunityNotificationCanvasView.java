package com.callx.app.community.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityNotificationEntity;

/**
 * Canvas-based row for in-app community notifications — replaces
 * item_community_notification.xml inflate. Mirrors CommunityPostCanvasView
 * pattern: all geometry computed in onMeasure, all painting in onDraw.
 *
 * Layout (left→right):
 *   [unread dot] [type-icon emoji] [title / body / time]
 */
public class CommunityNotificationCanvasView extends View {

    public interface Listener {
        void onClick(CommunityNotificationEntity notif);
    }

    // Dimensions
    private float density;
    private float padH, padV;
    private float dotRadius;
    private float iconSize;
    private float iconTextGap;
    private float titleBodyGap, bodyTimeGap;
    private float rowMinHeight;

    // Paints
    private TextPaint titlePaint, bodyPaint, timePaint, iconPaint;
    private Paint dotPaint, bgPaint, unreadBgPaint;

        // Pre-computed FontMetrics — populated once in init(), zero alloc in onMeasure/onDraw
    private final Paint.FontMetrics titleFm = new Paint.FontMetrics();
    private final Paint.FontMetrics bodyFm  = new Paint.FontMetrics();
    private final Paint.FontMetrics timeFm  = new Paint.FontMetrics();
    private final Paint.FontMetrics iconFm  = new Paint.FontMetrics();
    // Bind state
    private String typeIcon = "🔔";
    private String titleText = "", bodyText = "", timeText = "";
    private boolean isRead = true;
    private boolean isUnread;

    // Geometry
    private float iconX, iconY;
    private float textLeft;
    private float titleBaselineY, bodyBaselineY, timeBaselineY;
    private float titleDrawWidth;

    // Touch
    private Listener listener;
    private CommunityNotificationEntity boundEntity;

    public CommunityNotificationCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        padH = 14 * density;
        padV = 12 * density;
        dotRadius = 4 * density;
        iconSize = 36 * density;
        iconTextGap = 12 * density;
        titleBodyGap = 3 * density;
        bodyTimeGap = 4 * density;
        rowMinHeight = 56 * density;

        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted   = ContextCompat.getColor(context, R.color.text_muted);
        int colorPrimary= ContextCompat.getColor(context, R.color.colorPrimary);
        int surfaceCard = ContextCompat.getColor(context, R.color.surface_card);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(surfaceCard);

        unreadBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        unreadBgPaint.setColor(ColorUtil.withAlpha(colorPrimary, 18));

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(colorPrimary);

        iconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        iconPaint.setTextSize(20 * density);
        iconPaint.setTextAlign(Paint.Align.CENTER);

        titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(14 * density);
        titlePaint.setColor(textPrimary);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);

        bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setTextSize(13 * density);
        bodyPaint.setColor(textMuted);

        timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        timePaint.setTextSize(11 * density);
        timePaint.setColor(textMuted);
    
        // Populate FM once — text size/typeface never changes after init
        titlePaint.getFontMetrics(titleFm);
        bodyPaint.getFontMetrics(bodyFm);
        timePaint.getFontMetrics(timeFm);
        iconPaint.getFontMetrics(iconFm);
    }

    public void bind(CommunityNotificationEntity n) {
        this.boundEntity = n;
        this.typeIcon  = getNotifIcon(n.type);
        this.titleText = n.title != null ? n.title : "";
        this.bodyText  = n.body  != null ? n.body  : "";
        this.isRead    = n.isRead;
        this.isUnread  = !n.isRead;
        if (n.createdAt > 0) {
            this.timeText = android.text.format.DateUtils.getRelativeTimeSpanString(
                    n.createdAt, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
        } else {
            this.timeText = "";
        }
        setAlpha(n.isRead ? 0.65f : 1.0f);
        requestLayout();
        invalidate();
    }

    private String getNotifIcon(String type) {
        if (type == null) return "🔔";
        switch (type) {
            case "mention":        return "@";
            case "reply":          return "↩";
            case "role_change":    return "🛡";
            case "join_approved":  return "✅";
            case "join_rejected":  return "❌";
            case "event_reminder": return "📅";
            case "reaction":       return "❤";
            case "new_post":
            default:               return "🔔";
        }
    }

    /**
     * Lightweight read-state update — repaints only the unread dot and background
     * without a full measure/layout pass. Called by the adapter's payload path.
     */
    public void setReadState(boolean read) {
        if (this.isRead == read) return; // already correct, skip draw
        this.isRead = read;
        this.isUnread = !read;
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);

        float dotSpace = dotRadius * 2 + padH * 0.5f;
        iconX = padH + dotSpace + iconSize / 2f;
        textLeft = padH + dotSpace + iconSize + iconTextGap;
        titleDrawWidth = Math.max(1f, w - textLeft - padH);

        float y = padV;
        titleBaselineY = y - titleFm.ascent;
        y += (titleFm.descent - titleFm.ascent);

        if (!bodyText.isEmpty()) {
            y += titleBodyGap;
            bodyBaselineY = y - bodyFm.ascent;
            y += (bodyFm.descent - bodyFm.ascent);
        }

        if (!timeText.isEmpty()) {
            y += bodyTimeGap;
            timeBaselineY = y - timeFm.ascent;
            y += (timeFm.descent - timeFm.ascent);
        }

        y += padV;
        iconY = y / 2f;

        int h = (int) Math.max(rowMinHeight, y);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();

        canvas.drawRect(0, 0, w, h, bgPaint);
        if (isUnread) {
            canvas.drawRect(0, 0, w, h, unreadBgPaint);
        }

        // Unread dot
        if (isUnread) {
            canvas.drawCircle(padH + dotRadius, h / 2f, dotRadius, dotPaint);
        }

        // Type icon
        canvas.drawText(typeIcon, iconX, h / 2f - (iconFm.ascent + iconFm.descent) / 2f, iconPaint);

        // Title
        String ellTitle = TextUtils.ellipsize(titleText, titlePaint, titleDrawWidth, TextUtils.TruncateAt.END).toString();
        canvas.drawText(ellTitle, textLeft, titleBaselineY, titlePaint);

        // Body
        if (!bodyText.isEmpty()) {
            String ellBody = TextUtils.ellipsize(bodyText, bodyPaint, titleDrawWidth, TextUtils.TruncateAt.END).toString();
            canvas.drawText(ellBody, textLeft, bodyBaselineY, bodyPaint);
        }

        // Time
        if (!timeText.isEmpty()) {
            canvas.drawText(timeText, textLeft, timeBaselineY, timePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            if (listener != null && boundEntity != null) listener.onClick(boundEntity);
            return true;
        }
        return e.getActionMasked() == MotionEvent.ACTION_DOWN;
    }
}
