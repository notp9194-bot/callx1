package com.callx.app.channel.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Draws a channel event post card:
 *   • Optional banner image (full-width, rounded top corners, centerCrop)
 *   • Event title (large, bold)
 *   • Date/time row
 *   • Location row (if set)
 *   • RSVP button row (Going / Maybe / Not Going) — only if rsvpEnabled
 *
 * Touch hit-testing via hitTestRsvp(x, y) returns "going" / "maybe" / "not_going" / null.
 * All geometry allocated in layout(), zero allocation in draw().
 */
final class ChannelPostEventRenderer {

    private final ChannelPostCanvasView host;

    ChannelPostEventRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Geometry
    private final RectF bannerRect = new RectF();
    private float titleBaselineY;
    private float dateBaselineY  = -1f;
    private float locBaselineY   = -1f;
    private final RectF rsvpGoingRect = new RectF();
    private final RectF rsvpMaybeRect = new RectF();
    private final RectF rsvpNoRect    = new RectF();
    private boolean hasRsvp;
    private float eventCardBottom;

    // Banner shader cache
    private BitmapShader bannerShaderCache;
    private Bitmap       bannerShaderBitmap;
    private float        bannerShaderW = -1f, bannerShaderH = -1f;
    private final Path   bannerClipPath = new Path();
    private float        bannerClipW = -1f, bannerClipH = -1f;

    float layout(float left, float top, float right) {
        float r = host.eventCardCornerRadius;
        float y = top;

        if (host.eventBannerBitmap != null
                || (host.eventImageUrl != null && !host.eventImageUrl.isEmpty())) {
            bannerRect.set(left, y, right, y + host.eventBannerHeight);
            y = bannerRect.bottom;
        } else {
            bannerRect.setEmpty();
        }

        y += host.eventContentPad;

        // Title
        Paint.FontMetrics tfm = host.eventTitlePaint.getFontMetrics();
        titleBaselineY = y - tfm.ascent;
        y += tfm.descent - tfm.ascent + host.eventLineGap;

        // Date
        if (host.eventDateText != null && !host.eventDateText.isEmpty()) {
            Paint.FontMetrics dfm = host.eventMetaPaint.getFontMetrics();
            dateBaselineY = y - dfm.ascent;
            y += dfm.descent - dfm.ascent + host.eventLineGap;
        } else { dateBaselineY = -1f; }

        // Location
        if (host.eventLocation != null && !host.eventLocation.isEmpty()) {
            Paint.FontMetrics lfm = host.eventMetaPaint.getFontMetrics();
            locBaselineY = y - lfm.ascent;
            y += lfm.descent - lfm.ascent + host.eventLineGap;
        } else { locBaselineY = -1f; }

        // RSVP buttons
        hasRsvp = host.eventRsvpEnabled;
        if (hasRsvp) {
            y += host.eventLineGap;
            float btnH  = host.eventRsvpBtnHeight;
            float btnW3 = (right - left - host.eventRsvpBtnGap * 2f) / 3f;
            rsvpGoingRect.set(left,                      y, left + btnW3,                      y + btnH);
            rsvpMaybeRect.set(left + btnW3 + host.eventRsvpBtnGap,      y,
                              left + btnW3 * 2f + host.eventRsvpBtnGap, y + btnH);
            rsvpNoRect   .set(left + btnW3 * 2f + host.eventRsvpBtnGap * 2f, y,
                              right,                                           y + btnH);
            y += btnH + host.eventLineGap;
        }

        eventCardBottom = y + host.eventContentPad;
        return eventCardBottom;
    }

    void draw(Canvas canvas) {
        float r = host.eventCardCornerRadius;

        // Banner
        if (!bannerRect.isEmpty()) {
            float w = bannerRect.width(), h = bannerRect.height();
            if (bannerClipW != w || bannerClipH != h) {
                bannerClipPath.reset();
                float[] radii = {r, r, r, r, 0f, 0f, 0f, 0f};
                bannerClipPath.addRoundRect(bannerRect, radii, Path.Direction.CW);
                bannerClipW = w; bannerClipH = h;
            }
            canvas.save();
            canvas.clipPath(bannerClipPath);
            if (host.eventBannerBitmap != null) {
                Bitmap bmp = host.eventBannerBitmap;
                if (bannerShaderCache == null || bannerShaderBitmap != bmp
                        || bannerShaderW != w || bannerShaderH != h) {
                    float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                    float dx = bannerRect.left - (bmp.getWidth()  * scale - w) / 2f;
                    float dy = bannerRect.top  - (bmp.getHeight() * scale - h) / 2f;
                    android.graphics.Matrix mx = new android.graphics.Matrix();
                    mx.setScale(scale, scale);
                    mx.postTranslate(dx, dy);
                    BitmapShader shader = new BitmapShader(
                            bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader.setLocalMatrix(mx);
                    bannerShaderCache  = shader;
                    bannerShaderBitmap = bmp;
                    bannerShaderW = w; bannerShaderH = h;
                }
                host.eventBannerPaint.setShader(bannerShaderCache);
                canvas.drawRect(bannerRect, host.eventBannerPaint);
            } else {
                host.eventBannerPaint.setShader(null);
                canvas.drawRect(bannerRect, host.eventBannerPlaceholderPaint);
            }
            canvas.restore();
        }

        float textX = bannerRect.isEmpty()
                ? host.cardPadding
                : bannerRect.left;

        // Title
        if (host.eventTitle != null) {
            canvas.drawText(host.eventTitle, textX, titleBaselineY, host.eventTitlePaint);
        }
        // Date
        if (dateBaselineY >= 0 && host.eventDateText != null) {
            canvas.drawText("🗓 " + host.eventDateText, textX, dateBaselineY, host.eventMetaPaint);
        }
        // Location
        if (locBaselineY >= 0 && host.eventLocation != null) {
            canvas.drawText("📍 " + host.eventLocation, textX, locBaselineY, host.eventMetaPaint);
        }

        // RSVP buttons
        if (hasRsvp) {
            drawRsvpBtn(canvas, rsvpGoingRect,  "Going");
            drawRsvpBtn(canvas, rsvpMaybeRect,  "Maybe");
            drawRsvpBtn(canvas, rsvpNoRect,     "Can't go");
        }
    }

    private void drawRsvpBtn(Canvas canvas, RectF r, String label) {
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, host.eventRsvpBtnBgPaint);
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, host.eventRsvpBtnStrokePaint);
        float tx = r.centerX() - host.eventRsvpBtnTextPaint.measureText(label) / 2f;
        float ty = r.centerY() - (host.eventRsvpBtnTextPaint.ascent()
                + host.eventRsvpBtnTextPaint.descent()) / 2f;
        canvas.drawText(label, tx, ty, host.eventRsvpBtnTextPaint);
    }

    /** Returns "going", "maybe", "not_going", or null. */
    String hitTestRsvp(float x, float y) {
        if (!hasRsvp) return null;
        if (rsvpGoingRect.contains(x, y)) return "going";
        if (rsvpMaybeRect.contains(x, y)) return "maybe";
        if (rsvpNoRect   .contains(x, y)) return "not_going";
        return null;
    }
}
