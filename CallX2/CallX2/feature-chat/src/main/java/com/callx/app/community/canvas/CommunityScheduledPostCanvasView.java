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
import com.callx.app.db.entity.CommunityScheduledPostEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Canvas-based row for scheduled posts — replaces item_community_scheduled_post.xml.
 * Layout:
 *   ⏰ Scheduled: [datetime]
 *   [post text preview]     [📷] [📢]
 *   [Cancel button]
 */
public class CommunityScheduledPostCanvasView extends View {

    public interface Listener {
        void onCancelClicked(CommunityScheduledPostEntity post);
    }

    private float density;
    private float padH, padV, lineGap;
    private float badgeH, badgePadH, badgeCorner, badgeGap;
    private float btnH, btnW, btnCorner;

    // Paints
    private TextPaint scheduledTimePaint, previewPaint;
    private TextPaint mediaBadgePaint, announcementBadgePaint;
    private Paint mediaBadgeBgPaint, announcementBadgeBgPaint;
    private Paint cancelBtnBgPaint;
    private TextPaint cancelBtnTextPaint;
    private Paint bgPaint, cardBgPaint;

        // Pre-computed FontMetrics
    private final Paint.FontMetrics schedTimeFm    = new Paint.FontMetrics();
    private final Paint.FontMetrics schedPreviewFm = new Paint.FontMetrics();
    private final Paint.FontMetrics schedCancelFm  = new Paint.FontMetrics();
    private final Paint.FontMetrics schedBadge1Fm  = new Paint.FontMetrics();
    private final Paint.FontMetrics schedBadge2Fm  = new Paint.FontMetrics();
    // Bind state
    private String scheduledTimeText = "";
    private String previewText = "";
    private boolean hasMedia;
    private boolean isAnnouncement;
    private CommunityScheduledPostEntity boundPost;

    // Geometry
    private float timeLine1BaselineY, previewBaselineY;
    private float badgesRowY;
    private final RectF cancelBtnRect = new RectF();
    private final RectF mediaBadgeRect = new RectF();
    private final RectF announcementBadgeRect = new RectF();
    private float previewMaxW;

    private Listener listener;

    public CommunityScheduledPostCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        padH = 14 * density;
        padV = 12 * density;
        lineGap = 6 * density;
        badgeH = 20 * density;
        badgePadH = 8 * density;
        badgeCorner = 10 * density;
        badgeGap = 6 * density;
        btnH = 34 * density;
        btnW = 100 * density;
        btnCorner = 8 * density;

        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted   = ContextCompat.getColor(context, R.color.text_muted);
        int colorPrimary= ContextCompat.getColor(context, R.color.colorPrimary);
        int surfaceCard = ContextCompat.getColor(context, R.color.surface_card);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(surfaceCard);

        scheduledTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        scheduledTimePaint.setTextSize(12 * density);
        scheduledTimePaint.setColor(colorPrimary);
        scheduledTimePaint.setTypeface(Typeface.DEFAULT_BOLD);

        previewPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        previewPaint.setTextSize(14 * density);
        previewPaint.setColor(textPrimary);

        mediaBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mediaBadgeBgPaint.setColor(ColorUtil.withAlpha(0xFF2196F3, 40));
        mediaBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mediaBadgePaint.setTextSize(11 * density);
        mediaBadgePaint.setColor(0xFF1565C0);
        mediaBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);

        announcementBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        announcementBadgeBgPaint.setColor(ColorUtil.withAlpha(colorPrimary, 40));
        announcementBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        announcementBadgePaint.setTextSize(11 * density);
        announcementBadgePaint.setColor(colorPrimary);
        announcementBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);

        cancelBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cancelBtnBgPaint.setColor(ColorUtil.withAlpha(0xFFF44336, 200));
        cancelBtnTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        cancelBtnTextPaint.setTextSize(13 * density);
        cancelBtnTextPaint.setColor(Color.WHITE);
        cancelBtnTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        cancelBtnTextPaint.setTextAlign(Paint.Align.CENTER);
    
        scheduledTimePaint.getFontMetrics(schedTimeFm);
        previewPaint.getFontMetrics(schedPreviewFm);
        cancelBtnTextPaint.getFontMetrics(schedCancelFm);
        mediaBadgePaint.getFontMetrics(schedBadge1Fm);
        announcementBadgePaint.getFontMetrics(schedBadge2Fm);
    }

    public void bind(CommunityScheduledPostEntity p) {
        this.boundPost = p;
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault());
        scheduledTimeText = "⏰ Scheduled: " + sdf.format(new Date(p.scheduledAt));
        previewText = p.text != null && !p.text.isEmpty() ? p.text
                : (p.mediaUrl != null ? "📷 Media post" : "(no text)");
        hasMedia = p.mediaUrl != null && !p.mediaUrl.isEmpty();
        isAnnouncement = p.isAnnouncement;
        requestLayout();
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        previewMaxW = Math.max(1f, w - padH * 2);

        float y = padV;

                timeLine1BaselineY = y - schedTimeFm.ascent;
        y += (schedTimeFm.descent - schedTimeFm.ascent) + lineGap;

                previewBaselineY = y - schedPreviewFm.ascent;
        y += (schedPreviewFm.descent - schedPreviewFm.ascent);

        if (hasMedia || isAnnouncement) {
            y += lineGap;
            badgesRowY = y;
            y += badgeH;

            // Layout badge rects
            float bx = padH;
            if (hasMedia) {
                String label = "📷 Media";
                float bw = mediaBadgePaint.measureText(label) + badgePadH * 2;
                mediaBadgeRect.set(bx, badgesRowY, bx + bw, badgesRowY + badgeH);
                bx += bw + badgeGap;
            } else mediaBadgeRect.setEmpty();
            if (isAnnouncement) {
                String label = "📢 Announcement";
                float bw = announcementBadgePaint.measureText(label) + badgePadH * 2;
                announcementBadgeRect.set(bx, badgesRowY, bx + bw, badgesRowY + badgeH);
            } else announcementBadgeRect.setEmpty();
        }

        y += padV;

        // Cancel button
        cancelBtnRect.set(padH, y, padH + btnW, y + btnH);
        y += btnH + padV;

        setMeasuredDimension(w, (int) y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        canvas.drawText(TextUtils.ellipsize(scheduledTimeText, scheduledTimePaint, previewMaxW,
                TextUtils.TruncateAt.END).toString(), padH, timeLine1BaselineY, scheduledTimePaint);

        canvas.drawText(TextUtils.ellipsize(previewText, previewPaint, previewMaxW,
                TextUtils.TruncateAt.END).toString(), padH, previewBaselineY, previewPaint);

        if (hasMedia && !mediaBadgeRect.isEmpty()) {
            canvas.drawRoundRect(mediaBadgeRect, badgeCorner, badgeCorner, mediaBadgeBgPaint);
            drawBadgeText(canvas, "📷 Media", mediaBadgeRect, mediaBadgePaint);
        }
        if (isAnnouncement && !announcementBadgeRect.isEmpty()) {
            canvas.drawRoundRect(announcementBadgeRect, badgeCorner, badgeCorner, announcementBadgeBgPaint);
            drawBadgeText(canvas, "📢 Announcement", announcementBadgeRect, announcementBadgePaint);
        }

        canvas.drawRoundRect(cancelBtnRect, btnCorner, btnCorner, cancelBtnBgPaint);
                canvas.drawText("Cancel", cancelBtnRect.centerX(),
                cancelBtnRect.centerY() - (schedCancelFm.ascent + schedCancelFm.descent) / 2f, cancelBtnTextPaint);
    }

    private void drawBadgeText(Canvas c, String text, RectF r, TextPaint p) {
        Paint.FontMetrics fm = (p == mediaBadgePaint) ? schedBadge1Fm : schedBadge2Fm;
        c.drawText(text, r.left + badgePadH, r.centerY() - (fm.ascent + fm.descent) / 2f, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            if (cancelBtnRect.contains(e.getX(), e.getY())) {
                if (listener != null && boundPost != null) listener.onCancelClicked(boundPost);
                return true;
            }
        }
        return e.getActionMasked() == MotionEvent.ACTION_DOWN;
    }
}
