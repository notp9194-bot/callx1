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
import com.callx.app.db.entity.CommunityJoinRequestEntity;

/**
 * Canvas-based row for pending join requests — replaces item_community_join_request.xml.
 * Layout:
 *   [avatar] [name / timestamp / message]
 *   [        ] [Approve btn] [Reject btn]
 */
public class CommunityJoinRequestCanvasView extends View {

    public interface Listener {
        void onApprove(CommunityJoinRequestEntity req);
        void onReject(CommunityJoinRequestEntity req);
    }

    private float density;
    private float padH, padV, itemGap;
    private float avatarSize, avatarTextGap;
    private float btnH, btnW, btnCorner, btnGap;

    // Paints
    private TextPaint namePaint, tsPaint, msgPaint;
    private Paint avatarPaint, avatarPlaceholderBgPaint;
    private Paint approveBtnBgPaint, rejectBtnBgPaint;
    private TextPaint approveBtnTextPaint, rejectBtnTextPaint;
    private Paint bgPaint;

        // Pre-computed FontMetrics
    private final Paint.FontMetrics jrNameFm  = new Paint.FontMetrics();
    private final Paint.FontMetrics jrTsFm    = new Paint.FontMetrics();
    private final Paint.FontMetrics jrMsgFm   = new Paint.FontMetrics();
    private final Paint.FontMetrics jrApprFm  = new Paint.FontMetrics();
    private final Paint.FontMetrics jrRejFm   = new Paint.FontMetrics();
    private final android.text.TextPaint glyphPaint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics glyphFm = new Paint.FontMetrics();
    // Bind state
    private String nameText = "", tsText = "", msgText = "";
    private boolean hasMsg;
    private Bitmap avatarBitmap;
    private BitmapShader avatarShader;
    private Bitmap avatarShaderBmp;
    private float avatarShaderW = -1, avatarShaderH = -1;
    private final Matrix avatarMatrix = new Matrix();

    // Geometry
    private final RectF avatarRect = new RectF();
    private final RectF approveBtnRect = new RectF();
    private final RectF rejectBtnRect  = new RectF();
    private float nameBaselineY, tsBaselineY, msgBaselineY;
    private float textLeft, textMaxW;

    private Listener listener;
    private CommunityJoinRequestEntity boundReq;

    public CommunityJoinRequestCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        padH = 14 * density;
        padV = 12 * density;
        itemGap = 4 * density;
        avatarSize = 44 * density;
        avatarTextGap = 12 * density;
        btnH = 34 * density;
        btnW = 100 * density;
        btnCorner = 8 * density;
        btnGap = 10 * density;

        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted   = ContextCompat.getColor(context, R.color.text_muted);
        int colorPrimary= ContextCompat.getColor(context, R.color.colorPrimary);
        int surfaceCard = ContextCompat.getColor(context, R.color.surface_card);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(surfaceCard);

        avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatarPlaceholderBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatarPlaceholderBgPaint.setColor(ColorUtil.lighten(colorPrimary));

        namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        namePaint.setTextSize(14 * density);
        namePaint.setColor(textPrimary);
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);

        tsPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tsPaint.setTextSize(11 * density);
        tsPaint.setColor(textMuted);

        msgPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        msgPaint.setTextSize(13 * density);
        msgPaint.setColor(textMuted);

        approveBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        approveBtnBgPaint.setColor(0xFF4CAF50);
        approveBtnTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        approveBtnTextPaint.setTextSize(13 * density);
        approveBtnTextPaint.setColor(Color.WHITE);
        approveBtnTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        approveBtnTextPaint.setTextAlign(Paint.Align.CENTER);

        rejectBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rejectBtnBgPaint.setColor(ColorUtil.withAlpha(0xFFF44336, 220));
        rejectBtnTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        rejectBtnTextPaint.setTextSize(13 * density);
        rejectBtnTextPaint.setColor(Color.WHITE);
        rejectBtnTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        rejectBtnTextPaint.setTextAlign(Paint.Align.CENTER);
    
        namePaint.getFontMetrics(jrNameFm);
        tsPaint.getFontMetrics(jrTsFm);
        msgPaint.getFontMetrics(jrMsgFm);
        approveBtnTextPaint.getFontMetrics(jrApprFm);
        rejectBtnTextPaint.getFontMetrics(jrRejFm);
        glyphPaint.setTextSize(18 * density);
        glyphPaint.setColor(android.graphics.Color.WHITE);
        glyphPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        glyphPaint.getFontMetrics(glyphFm);
    }

    public void bind(CommunityJoinRequestEntity req) {
        this.boundReq = req;
        this.nameText = req.requesterName != null ? req.requesterName : "Unknown";
        this.tsText   = req.createdAt > 0
                ? android.text.format.DateUtils.getRelativeTimeSpanString(
                        req.createdAt, System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                : "";
        if (req.message != null && !req.message.isEmpty()) {
            hasMsg = true;
            msgText = "\"" + req.message + "\"";
        } else if (req.groupId != null) {
            hasMsg = true;
            msgText = "Wants to join a group in this community";
        } else {
            hasMsg = false;
            msgText = "";
        }
        avatarBitmap = null;
        avatarShaderBmp = null;
        avatarShader = null;
        requestLayout();
        invalidate();
    }

    public void setAvatarBitmap(String forId, @Nullable Bitmap bmp) {
        if (boundReq == null || !boundReq.id.equals(forId)) return;
        avatarBitmap = bmp;
        avatarShaderBmp = null;
        avatarShader = null;
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);

        textLeft = padH + avatarSize + avatarTextGap;
        textMaxW = Math.max(1f, w - textLeft - padH);

        float avatarTop = padV;
        avatarRect.set(padH, avatarTop, padH + avatarSize, avatarTop + avatarSize);

        float y = padV;
                nameBaselineY = y - jrNameFm.ascent;
        y += (jrNameFm.descent - jrNameFm.ascent) + itemGap;

                tsBaselineY = y - jrTsFm.ascent;
        y += (jrTsFm.descent - jrTsFm.ascent);

        if (hasMsg) {
            y += itemGap;
                        msgBaselineY = y - jrMsgFm.ascent;
            y += (jrMsgFm.descent - jrMsgFm.ascent);
        }

        y = Math.max(y, avatarTop + avatarSize);
        y += padV;

        // Buttons row
        float btnY = y;
        float btnRight = w - padH;
        rejectBtnRect.set(btnRight - btnW, btnY, btnRight, btnY + btnH);
        approveBtnRect.set(btnRight - btnW - btnGap - btnW, btnY, btnRight - btnW - btnGap, btnY + btnH);
        y += btnH + padV;

        setMeasuredDimension(w, (int) y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        // Avatar
        if (avatarBitmap != null) {
            float aw = avatarRect.width(), ah = avatarRect.height();
            if (avatarShader == null || avatarShaderBmp != avatarBitmap
                    || avatarShaderW != aw || avatarShaderH != ah) {
                float scale = Math.max(aw / avatarBitmap.getWidth(), ah / avatarBitmap.getHeight());
                float dx = avatarRect.left - (avatarBitmap.getWidth() * scale - aw) / 2f;
                float dy = avatarRect.top  - (avatarBitmap.getHeight() * scale - ah) / 2f;
                avatarMatrix.reset();
                avatarMatrix.setScale(scale, scale);
                avatarMatrix.postTranslate(dx, dy);
                avatarShader = new BitmapShader(avatarBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                avatarShader.setLocalMatrix(avatarMatrix);
                avatarShaderBmp = avatarBitmap;
                avatarShaderW = aw; avatarShaderH = ah;
            }
            avatarPaint.setShader(avatarShader);
            canvas.drawOval(avatarRect, avatarPaint);
        } else {
            canvas.drawOval(avatarRect, avatarPlaceholderBgPaint);
            canvas.drawText("👤", avatarRect.centerX(),
                    avatarRect.centerY() - (glyphFm.ascent + glyphFm.descent) / 2f, glyphPaint);
        }

        // Name
        canvas.drawText(TextUtils.ellipsize(nameText, namePaint, textMaxW, TextUtils.TruncateAt.END).toString(),
                textLeft, nameBaselineY, namePaint);
        // Timestamp
        if (!tsText.isEmpty()) canvas.drawText(tsText, textLeft, tsBaselineY, tsPaint);
        // Message
        if (hasMsg && !msgText.isEmpty()) {
            canvas.drawText(TextUtils.ellipsize(msgText, msgPaint, textMaxW, TextUtils.TruncateAt.END).toString(),
                    textLeft, msgBaselineY, msgPaint);
        }

        // Approve button
        canvas.drawRoundRect(approveBtnRect, btnCorner, btnCorner, approveBtnBgPaint);
        drawBtnText(canvas, "✅ Approve", approveBtnRect, approveBtnTextPaint);

        // Reject button
        canvas.drawRoundRect(rejectBtnRect, btnCorner, btnCorner, rejectBtnBgPaint);
        drawBtnText(canvas, "✖ Reject", rejectBtnRect, rejectBtnTextPaint);
    }

    private void drawBtnText(Canvas c, String text, RectF r, TextPaint p) {
        // Use pre-computed FM — p is always approve or reject paint (both pre-cached)
        Paint.FontMetrics fm = (p == approveBtnTextPaint) ? jrApprFm : jrRejFm;
        c.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (approveBtnRect.contains(x, y)) {
                if (listener != null && boundReq != null) listener.onApprove(boundReq);
                return true;
            }
            if (rejectBtnRect.contains(x, y)) {
                if (listener != null && boundReq != null) listener.onReject(boundReq);
                return true;
            }
        }
        return e.getActionMasked() == MotionEvent.ACTION_DOWN;
    }
}
