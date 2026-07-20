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
import com.callx.app.community.CommunityBadge;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.entity.CommunityMemberEntity;

/**
 * Canvas-based row for community member list — replaces item_community_member.xml.
 * Follows CommunityPostCanvasView pattern: geometry in onMeasure, painting in onDraw.
 *
 * Layout: [avatar] [name + badges] [muted indicator] [options ⋮]
 */
public class CommunityMemberCanvasView extends View {

    public interface Listener {
        void onOptionsClick(CommunityMemberEntity member);
        void onLongPress(CommunityMemberEntity member);
    }

    // Dimensions
    private float density;
    private float padH, padV;
    private float avatarSize, avatarTextGap;
    private float badgeH, badgePadH, badgeGap, badgeCorner;
    private float optionsBtnSize;
    private float rowMinHeight;

    // Paints
    private TextPaint namePaint, roleBadgePaint, memberBadgePaint, mutedBadgePaint;
    private Paint avatarPaint, avatarPlaceholderBgPaint, avatarGlyphPaint;
    private Paint roleBadgeBgPaint, memberBadgeBgPaint, mutedBadgeBgPaint;
    private Paint optionsDotPaint, bgPaint;

        // Pre-computed FontMetrics
    private final Paint.FontMetrics memNameFm  = new Paint.FontMetrics();
    private final Paint.FontMetrics memRoleFm  = new Paint.FontMetrics();
    private final Paint.FontMetrics memBadgeFm = new Paint.FontMetrics();
    private final android.text.TextPaint glyphPaint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics glyphFm = new Paint.FontMetrics();
    // Bind state
    private String displayName = "";
    private String roleLabel = "";
    private boolean showRole;
    private String memberBadgeLabel = "";
    private boolean showMemberBadge;
    private int memberBadgeColor = Color.DKGRAY;
    private boolean isMuted;
    private boolean showOptions;
    private Bitmap avatarBitmap;

    // Shader cache
    private BitmapShader avatarShader;
    private Bitmap avatarShaderBmp;
    private float avatarShaderW = -1f, avatarShaderH = -1f;
    private final Matrix avatarMatrix = new Matrix();

    // Geometry
    private final RectF avatarRect = new RectF();
    private final RectF optionsRect = new RectF();
    private final RectF roleBadgeRect = new RectF();
    private final RectF memberBadgeRect = new RectF();

    // Cached measurements
    private float nameBaselineY, nameX, nameMaxW;
    private float badgesRowY;
    private float optionsCx, optionsCy;
    private float computedH;

    // Touch / long press
    private Listener listener;
    private CommunityMemberEntity boundMember;
    private float downX, downY;
    private long downTime;
    private boolean longFired;
    private final Runnable longPressRunnable = () -> { longFired = true; fireLongPress(); };

    public CommunityMemberCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        padH = 14 * density;
        padV = 10 * density;
        avatarSize = 44 * density;
        avatarTextGap = 12 * density;
        badgeH = 18 * density;
        badgePadH = 8 * density;
        badgeGap = 6 * density;
        badgeCorner = 9 * density;
        optionsBtnSize = 24 * density;
        rowMinHeight = 60 * density;

        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted   = ContextCompat.getColor(context, R.color.text_muted);
        int colorPrimary= ContextCompat.getColor(context, R.color.colorPrimary);
        int surfaceCard = ContextCompat.getColor(context, R.color.surface_card);
        int surfaceInput= ContextCompat.getColor(context, R.color.surface_input);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(surfaceCard);

        avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatarPlaceholderBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatarPlaceholderBgPaint.setColor(ColorUtil.lighten(colorPrimary));

        avatarGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // drawn via TextPaint below as emoji text
        TextPaint tgp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tgp.setTextSize(16 * density);
        tgp.setColor(Color.WHITE);
        tgp.setTextAlign(Paint.Align.CENTER);

        namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        namePaint.setTextSize(14 * density);
        namePaint.setColor(textPrimary);
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);

        roleBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        roleBadgeBgPaint.setColor(ColorUtil.withAlpha(colorPrimary, 40));
        roleBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        roleBadgePaint.setTextSize(11 * density);
        roleBadgePaint.setColor(colorPrimary);
        roleBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);

        memberBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        memberBadgeBgPaint.setColor(surfaceInput);
        memberBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        memberBadgePaint.setTextSize(11 * density);
        memberBadgePaint.setColor(textMuted);

        mutedBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mutedBadgeBgPaint.setColor(ColorUtil.withAlpha(0xFFFF9800, 50));
        mutedBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mutedBadgePaint.setTextSize(11 * density);
        mutedBadgePaint.setColor(0xFFFF9800);

        optionsDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        optionsDotPaint.setColor(textMuted);
    
        namePaint.getFontMetrics(memNameFm);
        roleBadgePaint.getFontMetrics(memRoleFm);
        memberBadgePaint.getFontMetrics(memBadgeFm);
        glyphPaint.setTextSize(18 * density);
        glyphPaint.setColor(android.graphics.Color.WHITE);
        glyphPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        glyphPaint.getFontMetrics(glyphFm);
    }

    public void bind(CommunityMemberEntity m, String myRole, String currentUid) {
        this.boundMember = m;
        String badgeEmoji = CommunityBadge.getEmojiIcon(m.badge);
        this.displayName = (m.name != null ? m.name : "Member");
        if (!badgeEmoji.isEmpty()) displayName = badgeEmoji + " " + displayName;

        showRole = CommunityRole.OWNER.equals(m.role) || CommunityRole.ADMIN.equals(m.role);
        roleLabel = CommunityRole.OWNER.equals(m.role) ? "Owner" : "Admin";

        showMemberBadge = !CommunityBadge.isNone(m.badge);
        memberBadgeLabel = showMemberBadge ? CommunityBadge.getDisplayName(m.badge) : "";
        try {
            memberBadgeColor = showMemberBadge ? Color.parseColor(CommunityBadge.getBadgeColor(m.badge)) : Color.DKGRAY;
        } catch (Exception e) { memberBadgeColor = Color.DKGRAY; }
        memberBadgePaint.setColor(memberBadgeColor);

        isMuted = m.isMuted;
        boolean isAdminOrOwner = CommunityRole.isAdminOrOwner(myRole);
        boolean isSelf = currentUid != null && currentUid.equals(m.uid);
        showOptions = isAdminOrOwner && !isSelf;

        avatarBitmap = null;
        avatarShaderBmp = null;
        avatarShader = null;
        setAlpha(m.isMuted ? 0.6f : 1.0f);
        requestLayout();
        invalidate();
    }

    public void setAvatarBitmap(@Nullable Bitmap bmp) {
        avatarBitmap = bmp;
        avatarShaderBmp = null;
        avatarShader = null;
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);

        float avatarLeft = padH;
        float avatarCy = 0; // computed after total height
        avatarRect.set(avatarLeft, 0, avatarLeft + avatarSize, avatarSize);

        float textLeft = avatarLeft + avatarSize + avatarTextGap;
        float optW = showOptions ? optionsBtnSize : 0f;
        nameMaxW = Math.max(1f, w - textLeft - padH - optW - (showOptions ? padH * 0.5f : 0));

                float nameH = memNameFm.descent - memNameFm.ascent;

        boolean hasBadgeRow = showRole || showMemberBadge || isMuted;
        float badgeRowH = hasBadgeRow ? badgeH : 0f;
        float nameGapBadge = hasBadgeRow ? 4 * density : 0f;

        float blockH = nameH + (hasBadgeRow ? nameGapBadge + badgeRowH : 0f);
        float blockTop = (Math.max(avatarSize, blockH) - blockH) / 2f + padV;

        nameBaselineY = blockTop - memNameFm.ascent;
        nameX = textLeft;
        badgesRowY = blockTop + nameH + nameGapBadge;

        float totalH = Math.max(rowMinHeight, Math.max(avatarSize, blockH) + padV * 2);
        computedH = totalH;

        // Re-center avatar vertically
        float avatarTop = (totalH - avatarSize) / 2f;
        avatarRect.set(avatarLeft, avatarTop, avatarLeft + avatarSize, avatarTop + avatarSize);

        // Re-center text block
        blockTop = (totalH - blockH) / 2f;
        nameBaselineY = blockTop - memNameFm.ascent;
        badgesRowY = blockTop + nameH + nameGapBadge;

        // Layout badge rects
        float bx = textLeft;
        if (showRole) {
            float rw = roleBadgePaint.measureText(roleLabel) + badgePadH * 2;
            roleBadgeRect.set(bx, badgesRowY, bx + rw, badgesRowY + badgeH);
            bx += rw + badgeGap;
        } else {
            roleBadgeRect.setEmpty();
        }
        if (showMemberBadge && !memberBadgeLabel.isEmpty()) {
            float mw = memberBadgePaint.measureText(memberBadgeLabel) + badgePadH * 2;
            memberBadgeRect.set(bx, badgesRowY, bx + mw, badgesRowY + badgeH);
            bx += mw + badgeGap;
        } else {
            memberBadgeRect.setEmpty();
        }

        if (showOptions) {
            optionsCx = w - padH - optionsBtnSize / 2f;
            optionsCy = totalH / 2f;
            optionsRect.set(optionsCx - optionsBtnSize / 2f, optionsCy - optionsBtnSize / 2f,
                    optionsCx + optionsBtnSize / 2f, optionsCy + optionsBtnSize / 2f);
        } else {
            optionsRect.setEmpty();
        }

        setMeasuredDimension(w, (int) totalH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        // Avatar
        if (avatarBitmap != null) {
            float w = avatarRect.width(), h = avatarRect.height();
            if (avatarShader == null || avatarShaderBmp != avatarBitmap
                    || avatarShaderW != w || avatarShaderH != h) {
                float scale = Math.max(w / avatarBitmap.getWidth(), h / avatarBitmap.getHeight());
                float dx = avatarRect.left - (avatarBitmap.getWidth() * scale - w) / 2f;
                float dy = avatarRect.top  - (avatarBitmap.getHeight() * scale - h) / 2f;
                avatarMatrix.reset();
                avatarMatrix.setScale(scale, scale);
                avatarMatrix.postTranslate(dx, dy);
                avatarShader = new BitmapShader(avatarBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                avatarShader.setLocalMatrix(avatarMatrix);
                avatarShaderBmp = avatarBitmap;
                avatarShaderW = w;
                avatarShaderH = h;
            }
            avatarPaint.setShader(avatarShader);
            canvas.drawOval(avatarRect, avatarPaint);
        } else {
            canvas.drawOval(avatarRect, avatarPlaceholderBgPaint);
            canvas.drawText("👤", avatarRect.centerX(),
                    avatarRect.centerY() - (glyphFm.ascent + glyphFm.descent) / 2f, glyphPaint);
        }

        // Name
        String ellName = TextUtils.ellipsize(displayName, namePaint, nameMaxW, TextUtils.TruncateAt.END).toString();
        canvas.drawText(ellName, nameX, nameBaselineY, namePaint);

        // Muted text (inline after name if muted)
        if (isMuted) {
            float nameW = namePaint.measureText(ellName);
            canvas.drawText(" 🔇", nameX + nameW, nameBaselineY, namePaint);
        }

        // Role badge
        if (showRole && !roleBadgeRect.isEmpty()) {
            canvas.drawRoundRect(roleBadgeRect, badgeCorner, badgeCorner, roleBadgeBgPaint);
                        canvas.drawText(roleLabel, roleBadgeRect.left + badgePadH,
                    roleBadgeRect.centerY() - (memRoleFm.ascent + memRoleFm.descent) / 2f, roleBadgePaint);
        }

        // Member badge
        if (showMemberBadge && !memberBadgeRect.isEmpty()) {
            canvas.drawRoundRect(memberBadgeRect, badgeCorner, badgeCorner, memberBadgeBgPaint);
                        canvas.drawText(memberBadgeLabel, memberBadgeRect.left + badgePadH,
                    memberBadgeRect.centerY() - (memBadgeFm.ascent + memBadgeFm.descent) / 2f, memberBadgePaint);
        }

        // Options ⋮
        if (showOptions) {
            float dotR = optionsBtnSize * 0.07f;
            float spacing = optionsBtnSize * 0.28f;
            canvas.drawCircle(optionsCx, optionsCy - spacing, dotR, optionsDotPaint);
            canvas.drawCircle(optionsCx, optionsCy, dotR, optionsDotPaint);
            canvas.drawCircle(optionsCx, optionsCy + spacing, dotR, optionsDotPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                downTime = System.currentTimeMillis();
                longFired = false;
                postDelayed(longPressRunnable, 450);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float slop = 8 * density;
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)
                    removeCallbacks(longPressRunnable);
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (!longFired) {
                    float x = e.getX(), y = e.getY();
                    if (showOptions && optionsRect.contains(x, y)) {
                        if (listener != null && boundMember != null) listener.onOptionsClick(boundMember);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void fireLongPress() {
        if (listener != null && boundMember != null) listener.onLongPress(boundMember);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPressRunnable);
    }
}
