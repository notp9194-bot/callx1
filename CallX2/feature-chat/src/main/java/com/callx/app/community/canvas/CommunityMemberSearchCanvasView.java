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
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.entity.CommunityMemberEntity;

/**
 * Canvas-based row for member search results — replaces
 * item_community_search_result_member.xml. Simpler than CommunityMemberCanvasView:
 * avatar + name + role label (no badges, no options button).
 */
public class CommunityMemberSearchCanvasView extends View {

    private float density;
    private float padH, padV;
    private float avatarSize, avatarTextGap;
    private float nameRoleGap;
    private float rowMinH;

    // Paints
    private TextPaint namePaint, rolePaint;
    private Paint avatarPaint, avatarPlaceholderBgPaint, bgPaint;

    // Shader
    private BitmapShader avatarShader;
    private Bitmap avatarShaderBmp;
    private float avatarShaderW = -1, avatarShaderH = -1;
    private final Matrix avatarMatrix = new Matrix();

        // Pre-computed FontMetrics
    private final Paint.FontMetrics nameFm = new Paint.FontMetrics();
    private final Paint.FontMetrics roleFm = new Paint.FontMetrics();
    // Pre-allocated avatar placeholder glyph paint + FM (avoids per-draw allocation)
    private final android.text.TextPaint glyphPaint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics glyphFm = new Paint.FontMetrics();
    // Bind state
    private String nameText = "", roleText = "";
    private Bitmap avatarBitmap;

    // Geometry
    private final RectF avatarRect = new RectF();
    private float nameBaselineY, roleBaselineY, nameX, textMaxW;

    public CommunityMemberSearchCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        padH = 14 * density;
        padV = 10 * density;
        avatarSize = 40 * density;
        avatarTextGap = 12 * density;
        nameRoleGap = 3 * density;
        rowMinH = 56 * density;

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

        rolePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        rolePaint.setTextSize(12 * density);
        rolePaint.setColor(textMuted);
    
        namePaint.getFontMetrics(nameFm);
        rolePaint.getFontMetrics(roleFm);
        glyphPaint.setTextSize(16 * density);
        glyphPaint.setColor(android.graphics.Color.WHITE);
        glyphPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        glyphPaint.getFontMetrics(glyphFm);
    }

    public void bind(CommunityMemberEntity m) {
        nameText = m.name != null ? m.name : "Member";
        roleText = CommunityRole.OWNER.equals(m.role) ? "Owner"
                : CommunityRole.ADMIN.equals(m.role) ? "Admin" : "Member";
        avatarBitmap = null;
        avatarShaderBmp = null;
        avatarShader = null;
        requestLayout();
        invalidate();
    }

    public void setAvatarBitmap(@Nullable Bitmap bmp) {
        avatarBitmap = bmp;
        avatarShaderBmp = null;
        avatarShader = null;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);

        float avatarTop = 0;
        float totalH = Math.max(rowMinH, avatarSize + padV * 2);
        avatarTop = (totalH - avatarSize) / 2f;
        avatarRect.set(padH, avatarTop, padH + avatarSize, avatarTop + avatarSize);

        nameX = padH + avatarSize + avatarTextGap;
        textMaxW = Math.max(1f, w - nameX - padH);

                        float nameH = nameFm.descent - nameFm.ascent;
        float roleH = roleFm.descent - roleFm.ascent;
        float blockH = nameH + nameRoleGap + roleH;
        float blockTop = (totalH - blockH) / 2f;

        nameBaselineY = blockTop - nameFm.ascent;
        roleBaselineY = blockTop + nameH + nameRoleGap - roleFm.ascent;

        setMeasuredDimension(w, (int) totalH);
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

        canvas.drawText(TextUtils.ellipsize(nameText, namePaint, textMaxW, TextUtils.TruncateAt.END).toString(),
                nameX, nameBaselineY, namePaint);
        canvas.drawText(roleText, nameX, roleBaselineY, rolePaint);
    }
}
