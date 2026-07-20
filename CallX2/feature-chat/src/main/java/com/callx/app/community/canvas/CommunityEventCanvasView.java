package com.callx.app.community.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEventEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Canvas-based event card — replaces item_community_event_v2.xml inflate.
 *
 * Layout (top→bottom):
 *   [Cover image — optional, full-width]
 *   [DateBox(mon/day)] [Title / Time / Location / EventType / OnlineLink]
 *   [Going btn] [Interested btn] [Not Going btn]
 *
 * RSVP buttons and cover image are the only interactive regions.
 */
public class CommunityEventCanvasView extends View {

    public interface Listener {
        void onEventClicked(CommunityEventEntity event);
        void onRsvp(CommunityEventEntity event, String status); // "going"|"interested"|"not_going"
    }

    // ── Dimensions ──
    private float density;
    private float padH, padV, lineGap;
    private float coverH;
    private float dateBoxW, dateBoxH, dateBoxCorner;
    private float badgeH, badgePadH, badgeCorner;
    private float btnH, btnGap, btnCorner;
    private float cardCorner;

    // ── Paints ──
    private Paint cardBgPaint, coverBgPaint;
    private Paint coverBitmapPaint;
    private TextPaint monthPaint, dayPaint;
    private Paint dateBoxBgPaint;
    private TextPaint titlePaint, timePaint, locationPaint, linkPaint;
    private TextPaint onlineBadgePaint, hybridBadgePaint;
    private Paint onlineBadgeBgPaint, hybridBadgeBgPaint;
    private Paint goingBtnBgPaint, interestedBtnBgPaint, notGoingBtnBgPaint;
    private Paint goingBtnActiveBgPaint, interestedBtnActiveBgPaint, notGoingBtnActiveBgPaint;
    private TextPaint goingBtnPaint, interestedBtnPaint, notGoingBtnPaint;

        // Pre-computed FontMetrics
    private final Paint.FontMetrics evTitleFm     = new Paint.FontMetrics();
    private final Paint.FontMetrics evTimeFm      = new Paint.FontMetrics();
    private final Paint.FontMetrics evLocFm       = new Paint.FontMetrics();
    private final Paint.FontMetrics evLinkFm      = new Paint.FontMetrics();
    private final Paint.FontMetrics evMonthFm     = new Paint.FontMetrics();
    private final Paint.FontMetrics evDayFm       = new Paint.FontMetrics();
    private final Paint.FontMetrics evOnlineBadFm = new Paint.FontMetrics();
    private final Paint.FontMetrics evHybridBadFm = new Paint.FontMetrics();
    private final Paint.FontMetrics evGoingFm     = new Paint.FontMetrics();
    private final Paint.FontMetrics evIntrstFm    = new Paint.FontMetrics();
    private final Paint.FontMetrics evNotGoFm     = new Paint.FontMetrics();
    // Pre-allocated cover placeholder emoji paint
    private final android.text.TextPaint coverEmoji = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics coverEmojiFm = new Paint.FontMetrics();
    // ── Bind state ──
    private String monthText = "", dayText = "", titleText = "", timeText = "";
    private String locationText = "", eventType = "OFFLINE", onlineLinkText = "";
    private boolean hasCover, hasLocation, hasLink, hasEventType;
    private String rsvpGoing = "", rsvpInterested = "", rsvpNotGoing = "";
    private String myRsvpStatus = null; // null | "going" | "interested" | "not_going"

    private Bitmap coverBitmap;
    private BitmapShader coverShader;
    private Bitmap coverShaderBmp;
    private float coverShaderW = -1, coverShaderH = -1;
    private final Matrix coverMatrix = new Matrix();

    // ── Geometry ──
    private final RectF coverRect = new RectF();
    private final RectF dateBoxRect = new RectF();
    private final RectF goingBtnRect = new RectF();
    private final RectF interestedBtnRect = new RectF();
    private final RectF notGoingBtnRect = new RectF();
    private final RectF badgeRect = new RectF();

    private float monthBaselineY, dayBaselineY;
    private float infoLeft;
    private float titleBaselineY, timeBaselineY, locationBaselineY, linkBaselineY;
    private float infoMaxW;
    private float infoTop;

    private Listener listener;
    private CommunityEventEntity boundEvent;

    public CommunityEventCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        padH = 14 * density;
        padV = 12 * density;
        lineGap = 4 * density;
        coverH = 160 * density;
        dateBoxW = 52 * density;
        dateBoxH = 56 * density;
        dateBoxCorner = 8 * density;
        badgeH = 20 * density;
        badgePadH = 8 * density;
        badgeCorner = 10 * density;
        btnH = 34 * density;
        btnGap = 8 * density;
        btnCorner = 8 * density;
        cardCorner = 12 * density;

        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted   = ContextCompat.getColor(context, R.color.text_muted);
        int colorPrimary= ContextCompat.getColor(context, R.color.colorPrimary);
        int surfaceCard = ContextCompat.getColor(context, R.color.surface_card);
        int surfaceInput= ContextCompat.getColor(context, R.color.surface_input);

        cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBgPaint.setColor(surfaceCard);

        coverBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        coverBgPaint.setColor(ColorUtil.lighten(colorPrimary));

        coverBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        dateBoxBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dateBoxBgPaint.setColor(colorPrimary);

        monthPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        monthPaint.setTextSize(11 * density);
        monthPaint.setColor(Color.WHITE);
        monthPaint.setTextAlign(Paint.Align.CENTER);

        dayPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        dayPaint.setTextSize(22 * density);
        dayPaint.setColor(Color.WHITE);
        dayPaint.setTextAlign(Paint.Align.CENTER);
        dayPaint.setTypeface(Typeface.DEFAULT_BOLD);

        titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(15 * density);
        titlePaint.setColor(textPrimary);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);

        timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        timePaint.setTextSize(12 * density);
        timePaint.setColor(textMuted);

        locationPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        locationPaint.setTextSize(12 * density);
        locationPaint.setColor(textMuted);

        linkPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        linkPaint.setTextSize(12 * density);
        linkPaint.setColor(colorPrimary);

        onlineBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        onlineBadgeBgPaint.setColor(ColorUtil.withAlpha(0xFF2196F3, 40));
        onlineBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        onlineBadgePaint.setTextSize(11 * density);
        onlineBadgePaint.setColor(0xFF1565C0);
        onlineBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);

        hybridBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hybridBadgeBgPaint.setColor(ColorUtil.withAlpha(0xFF9C27B0, 40));
        hybridBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        hybridBadgePaint.setTextSize(11 * density);
        hybridBadgePaint.setColor(0xFF6A1B9A);
        hybridBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);

        // RSVP buttons
        goingBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        goingBtnBgPaint.setColor(surfaceInput);
        goingBtnActiveBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        goingBtnActiveBgPaint.setColor(ColorUtil.withAlpha(0xFF4CAF50, 50));
        goingBtnPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        goingBtnPaint.setTextSize(11 * density);
        goingBtnPaint.setTextAlign(Paint.Align.CENTER);

        interestedBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        interestedBtnBgPaint.setColor(surfaceInput);
        interestedBtnActiveBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        interestedBtnActiveBgPaint.setColor(ColorUtil.withAlpha(0xFFFF9800, 50));
        interestedBtnPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        interestedBtnPaint.setTextSize(11 * density);
        interestedBtnPaint.setTextAlign(Paint.Align.CENTER);

        notGoingBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        notGoingBtnBgPaint.setColor(surfaceInput);
        notGoingBtnActiveBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        notGoingBtnActiveBgPaint.setColor(ColorUtil.withAlpha(0xFFF44336, 40));
        notGoingBtnPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        notGoingBtnPaint.setTextSize(11 * density);
        notGoingBtnPaint.setTextAlign(Paint.Align.CENTER);
    
        titlePaint.getFontMetrics(evTitleFm);
        timePaint.getFontMetrics(evTimeFm);
        locationPaint.getFontMetrics(evLocFm);
        linkPaint.getFontMetrics(evLinkFm);
        monthPaint.getFontMetrics(evMonthFm);
        dayPaint.getFontMetrics(evDayFm);
        onlineBadgePaint.getFontMetrics(evOnlineBadFm);
        hybridBadgePaint.getFontMetrics(evHybridBadFm);
        goingBtnPaint.getFontMetrics(evGoingFm);
        interestedBtnPaint.getFontMetrics(evIntrstFm);
        notGoingBtnPaint.getFontMetrics(evNotGoFm);
        coverEmoji.setTextSize(32 * density);
        coverEmoji.setTextAlign(android.graphics.Paint.Align.CENTER);
        coverEmoji.setColor(android.graphics.Color.WHITE);
        coverEmoji.getFontMetrics(coverEmojiFm);
    }

    public void bind(CommunityEventEntity ev, String currentUid) {
        this.boundEvent = ev;
        eventType = ev.eventType != null ? ev.eventType : "OFFLINE";
        hasEventType = !"OFFLINE".equals(eventType);

        if (ev.startTimeMs > 0) {
            monthText = new SimpleDateFormat("MMM", Locale.getDefault()).format(new Date(ev.startTimeMs)).toUpperCase(Locale.ROOT);
            dayText   = new SimpleDateFormat("d",   Locale.getDefault()).format(new Date(ev.startTimeMs));
            timeText  = new SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(new Date(ev.startTimeMs));
        } else {
            monthText = ""; dayText = ""; timeText = "";
        }

        titleText = ev.title != null ? ev.title : "";
        hasLocation = ev.location != null && !ev.location.isEmpty();
        locationText = hasLocation ? "📍 " + ev.location : "";

        hasLink = ev.onlineLink != null && !ev.onlineLink.isEmpty()
                && ("ONLINE".equals(eventType) || "HYBRID".equals(eventType));
        onlineLinkText = hasLink ? "🔗 " + ev.onlineLink : "";

        hasCover = ev.coverImageUrl != null && !ev.coverImageUrl.isEmpty();

        rsvpGoing      = "✅ Going ("       + ev.rsvpCount       + ")";
        rsvpInterested = "⭐ Interested ("   + ev.interestedCount + ")";
        rsvpNotGoing   = "❌ ("             + ev.notGoingCount   + ")";

        myRsvpStatus = getRsvpStatus(ev.rsvpJson, currentUid);

        coverBitmap = null;
        coverShaderBmp = null;
        coverShader = null;
        requestLayout();
        invalidate();
    }

    public void setCoverBitmap(String forEventId, @Nullable Bitmap bmp) {
        if (boundEvent == null || !boundEvent.id.equals(forEventId)) return;
        coverBitmap = bmp;
        coverShaderBmp = null;
        coverShader = null;
        invalidate();
    }

    private String getRsvpStatus(String rsvpJson, String uid) {
        if (rsvpJson == null || uid == null || uid.isEmpty()) return null;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(rsvpJson);
            return obj.optString(uid, null);
        } catch (Exception e) { return null; }
    }

    public void setListener(Listener l) { this.listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);

        float y = 0;

        // Cover
        if (hasCover) {
            coverRect.set(0, 0, w, coverH);
            y = coverH;
        } else {
            coverRect.setEmpty();
        }

        y += padV;
        infoTop = y;

        // Date box
        dateBoxRect.set(padH, y, padH + dateBoxW, y + dateBoxH);

        // Info area
        infoLeft = padH + dateBoxW + padH;
        infoMaxW = Math.max(1f, w - infoLeft - padH);

        // Event type badge (inline above title)
        float iy = y;
        if (hasEventType) {
            iy += badgeH + lineGap;
        }

                titleBaselineY = iy - evTitleFm.ascent;
        iy += (evTitleFm.descent - evTitleFm.ascent) + lineGap;

                timeBaselineY = iy - evTimeFm.ascent;
        iy += (evTimeFm.descent - evTimeFm.ascent) + lineGap;

        if (hasLocation) {
                        locationBaselineY = iy - evLocFm.ascent;
            iy += (evLocFm.descent - evLocFm.ascent) + lineGap;
        }

        if (hasLink) {
                        linkBaselineY = iy - evLinkFm.ascent;
            iy += (evLinkFm.descent - evLinkFm.ascent) + lineGap;
        }

        y = Math.max(y + dateBoxH, iy) + padV;

        // Badge layout
        if (hasEventType) {
            String badgeText = "ONLINE".equals(eventType) ? "🌐 Online" : "🔀 Hybrid";
            float bw = onlineBadgePaint.measureText(badgeText) + badgePadH * 2;
            badgeRect.set(infoLeft, infoTop, infoLeft + bw, infoTop + badgeH);
        } else {
            badgeRect.setEmpty();
        }

        // RSVP buttons row
        float bLeft = padH;
        float btnTotalW = w - padH * 2;
        float goingW = btnTotalW * 0.40f;
        float intW   = btnTotalW * 0.40f;
        float notW   = btnTotalW - goingW - intW - btnGap * 2;
        goingBtnRect.set(bLeft, y, bLeft + goingW, y + btnH);
        bLeft += goingW + btnGap;
        interestedBtnRect.set(bLeft, y, bLeft + intW, y + btnH);
        bLeft += intW + btnGap;
        notGoingBtnRect.set(bLeft, y, bLeft + notW, y + btnH);
        y += btnH + padV;

        setMeasuredDimension(w, (int) y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), cardCorner, cardCorner, cardBgPaint);

        // Cover
        if (hasCover && !coverRect.isEmpty()) {
            if (coverBitmap != null) {
                float cw = coverRect.width(), ch = coverRect.height();
                if (coverShader == null || coverShaderBmp != coverBitmap
                        || coverShaderW != cw || coverShaderH != ch) {
                    float scale = Math.max(cw / coverBitmap.getWidth(), ch / coverBitmap.getHeight());
                    float dx = coverRect.left - (coverBitmap.getWidth() * scale - cw) / 2f;
                    float dy = coverRect.top  - (coverBitmap.getHeight() * scale - ch) / 2f;
                    coverMatrix.reset();
                    coverMatrix.setScale(scale, scale);
                    coverMatrix.postTranslate(dx, dy);
                    coverShader = new BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    coverShader.setLocalMatrix(coverMatrix);
                    coverShaderBmp = coverBitmap;
                    coverShaderW = cw; coverShaderH = ch;
                }
                coverBitmapPaint.setShader(coverShader);
                canvas.drawRect(coverRect, coverBitmapPaint);
            } else {
                canvas.drawRect(coverRect, coverBgPaint);
                canvas.drawText("📅", coverRect.centerX(),
                        coverRect.centerY() - (coverEmojiFm.ascent + coverEmojiFm.descent) / 2f, coverEmoji);
            }
        }

        // Date box
        canvas.drawRoundRect(dateBoxRect, dateBoxCorner, dateBoxCorner, dateBoxBgPaint);
        if (!monthText.isEmpty()) {
                        float mBy = dateBoxRect.top + dateBoxH * 0.3f - (evMonthFm.ascent + evMonthFm.descent) / 2f;
            canvas.drawText(monthText, dateBoxRect.centerX(), mBy, monthPaint);
        }
        if (!dayText.isEmpty()) {
                        float dBy = dateBoxRect.top + dateBoxH * 0.68f - (evDayFm.ascent + evDayFm.descent) / 2f;
            canvas.drawText(dayText, dateBoxRect.centerX(), dBy, dayPaint);
        }

        // Event type badge
        if (hasEventType && !badgeRect.isEmpty()) {
            boolean online = "ONLINE".equals(eventType);
            canvas.drawRoundRect(badgeRect, badgeCorner, badgeCorner,
                    online ? onlineBadgeBgPaint : hybridBadgeBgPaint);
            TextPaint bp = online ? onlineBadgePaint : hybridBadgePaint;
            Paint.FontMetrics bfm = online ? evOnlineBadFm : evHybridBadFm;
            String bt = online ? "🌐 Online" : "🔀 Hybrid";
            canvas.drawText(bt, badgeRect.left + badgePadH,
                    badgeRect.centerY() - (bfm.ascent + bfm.descent) / 2f, bp);
        }

        // Title
        canvas.drawText(TextUtils.ellipsize(titleText, titlePaint, infoMaxW, TextUtils.TruncateAt.END).toString(),
                infoLeft, titleBaselineY, titlePaint);

        // Time
        if (!timeText.isEmpty())
            canvas.drawText(timeText, infoLeft, timeBaselineY, timePaint);

        // Location
        if (hasLocation)
            canvas.drawText(TextUtils.ellipsize(locationText, locationPaint, infoMaxW, TextUtils.TruncateAt.END).toString(),
                    infoLeft, locationBaselineY, locationPaint);

        // Online link
        if (hasLink)
            canvas.drawText(TextUtils.ellipsize(onlineLinkText, linkPaint, infoMaxW, TextUtils.TruncateAt.END).toString(),
                    infoLeft, linkBaselineY, linkPaint);

        // RSVP buttons
        drawRsvpButton(canvas, goingBtnRect, rsvpGoing, goingBtnPaint, goingBtnBgPaint, goingBtnActiveBgPaint, 0xFF4CAF50, "going");
        drawRsvpButton(canvas, interestedBtnRect, rsvpInterested, interestedBtnPaint, interestedBtnBgPaint, interestedBtnActiveBgPaint, 0xFFFF9800, "interested");
        drawRsvpButton(canvas, notGoingBtnRect, rsvpNotGoing, notGoingBtnPaint, notGoingBtnBgPaint, notGoingBtnActiveBgPaint, 0xFFF44336, "not_going");
    }

    private void drawRsvpButton(Canvas canvas, RectF rect, String text, TextPaint tp,
                                 Paint bgNormal, Paint bgActive, int activeColor, String status) {
        boolean active = status.equals(myRsvpStatus);
        canvas.drawRoundRect(rect, btnCorner, btnCorner, active ? bgActive : bgNormal);
        tp.setColor(active ? activeColor : 0xFF757575);
        Paint.FontMetrics fm = (tp == goingBtnPaint) ? evGoingFm
                : (tp == interestedBtnPaint) ? evIntrstFm : evNotGoFm;
        canvas.drawText(TextUtils.ellipsize(text, tp, rect.width() - 8 * density, TextUtils.TruncateAt.END).toString(),
                rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, tp);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (goingBtnRect.contains(x, y))      { fire("going");      return true; }
            if (interestedBtnRect.contains(x, y)) { fire("interested"); return true; }
            if (notGoingBtnRect.contains(x, y))   { fire("not_going");  return true; }
            if (listener != null && boundEvent != null) listener.onEventClicked(boundEvent);
            return true;
        }
        return e.getActionMasked() == MotionEvent.ACTION_DOWN;
    }

    private void fire(String status) {
        if (listener != null && boundEvent != null) listener.onRsvp(boundEvent, status);
    }
}
