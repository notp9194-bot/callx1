package com.callx.app.community.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityModerationLogEntity;

/**
 * Canvas-based row for moderation log entries — replaces item_community_moderation_log.xml.
 * Layout:
 *   [colored action dot] [admin → Action: target]   [time]
 *                        [reason text if present]
 */
public class CommunityModerationLogCanvasView extends View {

    private float density;
    private float padH, padV, lineGap;
    private float dotRadius;
    private float rowMinH;

    // Paints
    private TextPaint actionPaint, reasonPaint, timePaint;
    private Paint dotPaint, bgPaint;

        // Pre-computed FontMetrics
    private final Paint.FontMetrics actionFm = new Paint.FontMetrics();
    private final Paint.FontMetrics reasonFm = new Paint.FontMetrics();
    private final Paint.FontMetrics modTimeFm = new Paint.FontMetrics();
    // Bind state
    private String actionText = "";
    private String reasonText = "";
    private boolean hasReason;
    private String timeText = "";
    private int actionColor = Color.DKGRAY;

    // Geometry
    private float textLeft;
    private float actionBaselineY, reasonBaselineY;
    private float timePaintX;
    private float textMaxW;

    public CommunityModerationLogCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        padH = 14 * density;
        padV = 12 * density;
        lineGap = 4 * density;
        dotRadius = 5 * density;
        rowMinH = 48 * density;

        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted   = ContextCompat.getColor(context, R.color.text_muted);
        int surfaceCard = ContextCompat.getColor(context, R.color.surface_card);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(surfaceCard);

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.DKGRAY);

        actionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        actionPaint.setTextSize(13 * density);
        actionPaint.setColor(textPrimary);
        actionPaint.setTypeface(Typeface.DEFAULT_BOLD);

        reasonPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        reasonPaint.setTextSize(12 * density);
        reasonPaint.setColor(textMuted);

        timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        timePaint.setTextSize(11 * density);
        timePaint.setColor(textMuted);
        timePaint.setTextAlign(Paint.Align.RIGHT);
    
        actionPaint.getFontMetrics(actionFm);
        reasonPaint.getFontMetrics(reasonFm);
        timePaint.getFontMetrics(modTimeFm);
    }

    public void bind(CommunityModerationLogEntity log) {
        String admin  = log.actionByName != null ? log.actionByName : "Admin";
        String target = log.targetName   != null ? log.targetName   : "Member";
        String label  = getActionLabel(log.action);
        actionText = admin + " → " + label + ": " + target;
        actionColor = getActionColor(log.action);
        actionPaint.setColor(actionColor);
        dotPaint.setColor(actionColor);

        hasReason = log.reason != null && !log.reason.isEmpty();
        reasonText = hasReason ? "Reason: " + log.reason : "";
        timeText = log.createdAt > 0
                ? android.text.format.DateUtils.getRelativeTimeSpanString(log.createdAt,
                        System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                : "";
        requestLayout();
        invalidate();
    }

    private String getActionLabel(String action) {
        if (action == null) return "Action";
        switch (action) {
            case "mute":         return "Muted";
            case "unmute":       return "Unmuted";
            case "ban":          return "Banned";
            case "unban":        return "Unbanned";
            case "delete_post":  return "Deleted Post";
            case "make_admin":   return "Made Admin";
            case "remove_admin": return "Removed Admin";
            case "approve_join": return "Approved Join";
            case "reject_join":  return "Rejected Join";
            case "report_post":  return "Reported Post";
            default:             return action;
        }
    }

    private int getActionColor(String action) {
        if (action == null) return Color.DKGRAY;
        switch (action) {
            case "ban":          return Color.parseColor("#F44336");
            case "mute":
            case "delete_post":
            case "report_post":  return Color.parseColor("#FF9800");
            case "make_admin":
            case "approve_join": return Color.parseColor("#2196F3");
            case "unban":
            case "unmute":       return Color.parseColor("#4CAF50");
            default:             return Color.DKGRAY;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);

        float dotX = padH + dotRadius;
        textLeft = dotX + dotRadius + 10 * density;
        float timeW = timePaint.measureText(timeText) + 4 * density;
        timePaintX = w - padH;
        textMaxW = Math.max(1f, w - textLeft - padH - timeW);

        float y = padV;
        //[FM pre-computed]
        actionBaselineY = y - actionFm.ascent;
        y += (actionFm.descent - actionFm.ascent);

        if (hasReason) {
            y += lineGap;
            //[FM pre-computed]
            reasonBaselineY = y - reasonFm.ascent;
            y += (reasonFm.descent - reasonFm.ascent);
        }

        y += padV;
        setMeasuredDimension(w, (int) Math.max(rowMinH, y));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Colored dot
        canvas.drawCircle(padH + dotRadius, h / 2f, dotRadius, dotPaint);

        // Action text
        canvas.drawText(TextUtils.ellipsize(actionText, actionPaint, textMaxW, TextUtils.TruncateAt.END).toString(),
                textLeft, actionBaselineY, actionPaint);

        // Reason
        if (hasReason) {
            canvas.drawText(TextUtils.ellipsize(reasonText, reasonPaint, textMaxW + timePaint.measureText(timeText),
                    TextUtils.TruncateAt.END).toString(), textLeft, reasonBaselineY, reasonPaint);
        }

        // Time (right-aligned)
        if (!timeText.isEmpty()) {
            //[FM pre-computed]
            canvas.drawText(timeText, timePaintX, actionBaselineY, timePaint);
        }
    }
}
