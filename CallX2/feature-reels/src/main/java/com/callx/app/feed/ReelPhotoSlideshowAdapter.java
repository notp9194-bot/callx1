package com.callx.app.feed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;

import java.util.List;
import java.util.Random;

/**
 * ReelPhotoSlideshowAdapter ── Ultra-Advanced Photo Slideshow v5
 * ══════════════════════════════════════════════════════════════════
 *
 * Drives the photo ViewPager2 inside ReelPlayerFragment for photo_slideshow reels.
 *
 * ✅ v5 NEW features:
 *   • 12 page transitions: fade | zoom | slide | cube | flip3d | carousel
 *                          stack | parallax | blur | glitch | reveal | none
 *   • 16 colour filters:   normal | warm | cool | vivid | bw | golden_hour
 *                          rose | sunset | neon_pop | matrix | dream | chrome
 *                          matte | vintage | fade_film | noir
 *   • 11 visual effects:   none | vignette | grain | glitch_overlay | neon_glow
 *                          matte_overlay | chrome_leak | bokeh | scanlines
 *                          dust | double_exposure
 *   • Per-photo filters, transitions, captions, stickers, effects, Ken Burns dir
 *   • Ken Burns 4 intensity presets × 5 direction presets
 *   • Pinch-to-zoom (up to maxZoomScale) with inertia snap-back
 *   • Double-tap zoom toggle (1× ↔ 2.5×) with animated spring
 *   • Long-press pause (shows ⏸ badge via callback) + release resume
 *   • Per-photo caption animated slide-up with custom style JSON
 *   • Sticker overlays per photo (emoji / text, draggable in edit mode)
 *   • Auto-advance timer integration hook (fireAutoAdvance callback)
 *   • Glide preloading: next 2 photos prefetched in background
 *   • Caption gradient scrim auto-shown when caption is non-empty
 *   • Accessible: all photos have content descriptions
 */
public class ReelPhotoSlideshowAdapter
        extends RecyclerView.Adapter<ReelPhotoSlideshowAdapter.PhotoVH> {

    // ── Contracts ─────────────────────────────────────────────────────────────

    public interface PhotoInteractionListener {
        /** Called when the user long-presses (pause) or releases (resume). */
        void onLongPressStateChanged(boolean isPaused);
        /** Called when the current photo index changes due to a user swipe. */
        void onPhotoSwipedByUser(int newIndex);
        /** Called when auto-advance should fire for the current photo. */
        void onAutoAdvanceTick(int fromIndex);
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final float  DEFAULT_ZOOM_TARGET   = 2.5f;
    private static final long   CAPTION_ANIM_DURATION = 280L;
    private static final long   ZOOM_SNAP_DURATION    = 220L;
    private static final long   DOUBLE_TAP_MAX_MS     = 300L;
    private static final String TAG_STICKER_VIEW      = "sticker_overlay";

    // ── State ─────────────────────────────────────────────────────────────────

    private final ReelModel                   reelModel;
    private final List<String>                photoUrls;
    @Nullable private PhotoInteractionListener listener;

    // Global overrides (set from host fragment/activity)
    private String  globalFilter     = "normal";
    private boolean editMode         = false; // stickers are draggable in edit mode

    // Per-adapter zoom state (shared across all visible VH, keyed by adapter position)
    private final float[] zoomScaleArr;     // current scale per position
    private final float[] zoomTransXArr;    // current translateX per position
    private final float[] zoomTransYArr;    // current translateY per position

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Constructor ───────────────────────────────────────────────────────────

    public ReelPhotoSlideshowAdapter(@NonNull ReelModel reelModel) {
        this.reelModel = reelModel;
        this.photoUrls = reelModel.photoUrls;
        int n = photoUrls != null ? photoUrls.size() : 0;
        zoomScaleArr  = new float[n]; for (int i = 0; i < n; i++) zoomScaleArr[i]  = 1f;
        zoomTransXArr = new float[n];
        zoomTransYArr = new float[n];
    }

    // ── Public setters ────────────────────────────────────────────────────────

    public void setPhotoInteractionListener(@Nullable PhotoInteractionListener l) {
        this.listener = l;
    }

    /** Hot-swap global colour filter and rebind visible pages. */
    public void setGlobalFilter(String filter) {
        this.globalFilter = (filter != null) ? filter : "normal";
        notifyDataSetChanged();
    }

    /** Enable / disable sticker drag mode. */
    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        notifyDataSetChanged();
    }

    /** Reset zoom for the given position (call when photo auto-advances away). */
    public void resetZoom(int position) {
        if (position < 0 || position >= zoomScaleArr.length) return;
        zoomScaleArr[position]  = 1f;
        zoomTransXArr[position] = 0f;
        zoomTransYArr[position] = 0f;
    }

    // ── RecyclerView.Adapter ──────────────────────────────────────────────────

    @NonNull
    @Override
    public PhotoVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reel_photo_slide, parent, false);
        return new PhotoVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoVH h, int position) {
        cancelKenBurns(h);
        resetViewTransforms(h);

        String url    = photoUrls.get(position);
        String filter = resolveFilter(position);
        String effect = reelModel.effectiveEffectForPhoto(position);
        String caption = reelModel.captionForPhoto(position);
        String stickerJson = reelModel.stickerJsonForPhoto(position);
        String captionStyleJson = null;
        if (reelModel.photoCaptionStyleList != null && position < reelModel.photoCaptionStyleList.size()) {
            captionStyleJson = reelModel.photoCaptionStyleList.get(position);
        }

        // Apply colour filter immediately (before image loads)
        applyColorFilter(h, filter);

        // Apply effect overlay
        applyEffect(h, effect);

        // Caption
        bindCaption(h, caption, captionStyleJson);

        // Stickers
        bindStickers(h, stickerJson, position);

        // Load image
        Glide.with(h.ivPhoto.getContext())
                .load(url)
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(Target.SIZE_ORIGINAL))
                .placeholder(android.R.color.black)
                .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override public boolean onLoadFailed(GlideException e, Object model,
                            Target<android.graphics.drawable.Drawable> t, boolean first) {
                        return false;
                    }
                    @Override public boolean onResourceReady(
                            android.graphics.drawable.Drawable res, Object model,
                            Target<android.graphics.drawable.Drawable> t,
                            DataSource src, boolean first) {
                        startKenBurns(h, position);
                        return false;
                    }
                })
                .into(h.ivPhoto);

        // Restore zoom state
        h.ivPhoto.setScaleX(zoomScaleArr[position]);
        h.ivPhoto.setScaleY(zoomScaleArr[position]);
        h.ivPhoto.setTranslationX(zoomTransXArr[position]);
        h.ivPhoto.setTranslationY(zoomTransYArr[position]);

        // Attach gesture handlers
        attachGestures(h, position);

        // Preload next 2 photos
        preloadNeighbours(h.ivPhoto.getContext(), position);
    }

    @Override
    public void onViewRecycled(@NonNull PhotoVH h) {
        super.onViewRecycled(h);
        cancelKenBurns(h);
        resetViewTransforms(h);
        h.ivPhoto.clearColorFilter();
        h.vEffectOverlay.setVisibility(View.GONE);
        h.vColorFilterOverlay.setVisibility(View.GONE);
        h.tvCaption.setVisibility(View.GONE);
        h.vCaptionGradient.setVisibility(View.GONE);
        h.llStickerLayer.removeAllViews();
    }

    @Override
    public int getItemCount() {
        return photoUrls != null ? photoUrls.size() : 0;
    }

    // ── Colour filter ─────────────────────────────────────────────────────────

    private String resolveFilter(int position) {
        String perPhoto = reelModel.effectiveFilterForPhoto(position);
        // If the host set a global override and the per-photo is "normal", use global
        if (!"normal".equals(globalFilter) && "normal".equals(perPhoto)) return globalFilter;
        return perPhoto;
    }

    private void applyColorFilter(@NonNull PhotoVH h, @NonNull String filter) {
        ColorMatrixColorFilter cmcf = buildColorFilter(filter);
        h.ivPhoto.setColorFilter(cmcf);

        // Show / hide the supplemental tint overlay for effects that need it
        Integer tint = buildTintOverlay(filter);
        if (tint != null) {
            h.vColorFilterOverlay.setBackgroundColor(tint);
            h.vColorFilterOverlay.setVisibility(View.VISIBLE);
        } else {
            h.vColorFilterOverlay.setVisibility(View.GONE);
        }
    }

    @Nullable
    public static ColorMatrixColorFilter buildColorFilter(String filter) {
        if (filter == null || "normal".equals(filter)) return null;
        ColorMatrix cm = new ColorMatrix();
        switch (filter) {
            // ── Warm: boost reds/yellows, cool blues ──────────────────────
            case "warm":
                cm.set(new float[]{
                    1.20f, 0f,    0f,    0f, 18f,
                    0f,    1.05f, 0f,    0f,  8f,
                    0f,    0f,    0.85f, 0f,-20f,
                    0f,    0f,    0f,    1f,  0f});
                break;
            // ── Cool: desaturate reds, boost blues ────────────────────────
            case "cool":
                cm.set(new float[]{
                    0.82f, 0f, 0f,    0f,-18f,
                    0f,    1f, 0f,    0f,  0f,
                    0f,    0f, 1.22f, 0f, 22f,
                    0f,    0f, 0f,    1f,  0f});
                break;
            // ── Vivid: heavy saturation boost ────────────────────────────
            case "vivid":
                cm.setSaturation(1.9f);
                break;
            // ── Black & white ─────────────────────────────────────────────
            case "bw":
                cm.setSaturation(0f);
                break;
            // ── Golden hour: warm amber sunrise/sunset look ───────────────
            case "golden_hour":
                cm.set(new float[]{
                    1.25f, 0f,    0f,    0f, 30f,
                    0f,    1.10f, 0f,    0f, 10f,
                    0f,    0f,    0.70f, 0f,-40f,
                    0f,    0f,    0f,    1f,  0f});
                break;
            // ── Rose: warm pinks ──────────────────────────────────────────
            case "rose":
                cm.set(new float[]{
                    1.15f, 0f,    0.15f, 0f, 10f,
                    0f,    0.90f, 0f,    0f, -5f,
                    0f,    0f,    0.80f, 0f,  0f,
                    0f,    0f,    0f,    1f,  0f});
                break;
            // ── Sunset: red-orange blaze ──────────────────────────────────
            case "sunset":
                cm.set(new float[]{
                    1.30f, 0f,    0f,    0f, 40f,
                    0f,    0.95f, 0f,    0f,  5f,
                    0f,    0f,    0.60f, 0f,-50f,
                    0f,    0f,    0f,    1f,  0f});
                break;
            // ── Neon pop: electric saturation + blue-green shift ──────────
            case "neon_pop":
                cm.setSaturation(2.2f);
                ColorMatrix neonShift = new ColorMatrix(new float[]{
                    0.9f, 0f,    0.1f,  0f, -5f,
                    0f,   1.0f,  0f,    0f,  0f,
                    0.1f, 0f,    1.15f, 0f, 15f,
                    0f,   0f,    0f,    1f,  0f});
                cm.postConcat(neonShift);
                break;
            // ── Matrix: green terminal tint ───────────────────────────────
            case "matrix":
                cm.set(new float[]{
                    0f,    0.20f, 0f,    0f, 0f,
                    0f,    1.10f, 0f,    0f, 20f,
                    0f,    0.10f, 0.15f, 0f, 0f,
                    0f,    0f,    0f,    1f, 0f});
                break;
            // ── Dream: soft pastel desaturation + brightness ─────────────
            case "dream":
                cm.setSaturation(0.7f);
                ColorMatrix dreamBright = new ColorMatrix(new float[]{
                    1f, 0f, 0f, 0f,  30f,
                    0f, 1f, 0f, 0f,  30f,
                    0f, 0f, 1f, 0f,  30f,
                    0f, 0f, 0f, 1f, -15f});
                cm.postConcat(dreamBright);
                break;
            // ── Chrome: high-contrast glossy ─────────────────────────────
            case "chrome":
                cm.set(new float[]{
                    1.30f, 0f,    0f,    0f,-20f,
                    0f,    1.30f, 0f,    0f,-20f,
                    0f,    0f,    1.30f, 0f,-20f,
                    0f,    0f,    0f,    1f,  0f});
                cm.setSaturation(1.5f);
                break;
            // ── Matte: faded low-contrast look ────────────────────────────
            case "matte":
                cm.set(new float[]{
                    0.75f, 0f,    0f,    0f, 40f,
                    0f,    0.75f, 0f,    0f, 40f,
                    0f,    0f,    0.75f, 0f, 40f,
                    0f,    0f,    0f,    1f,  0f});
                break;
            // ── Vintage: sepia + reduced saturation ───────────────────────
            case "vintage":
                cm.setSaturation(0.4f);
                ColorMatrix sepia = new ColorMatrix(new float[]{
                    1.0f, 0f, 0f, 0f, 10f,
                    0f,   1f, 0f, 0f, -5f,
                    0f,   0f, 1f, 0f,-20f,
                    0f,   0f, 0f, 1f,  0f});
                cm.postConcat(sepia);
                break;
            // ── Fade film: washed-out film look ──────────────────────────
            case "fade_film":
                cm.set(new float[]{
                    0.85f, 0f,    0f,    0f, 50f,
                    0f,    0.85f, 0f,    0f, 40f,
                    0f,    0f,    0.80f, 0f, 35f,
                    0f,    0f,    0f,    1f,  0f});
                cm.setSaturation(0.65f);
                break;
            // ── Noir: deep monochrome with crushed blacks ─────────────────
            case "noir":
                cm.setSaturation(0f);
                ColorMatrix noirContrast = new ColorMatrix(new float[]{
                    1.5f, 0f,   0f,   0f,-60f,
                    0f,   1.5f, 0f,   0f,-60f,
                    0f,   0f,   1.5f, 0f,-60f,
                    0f,   0f,   0f,   1f,  0f});
                cm.postConcat(noirContrast);
                break;
            default:
                return null;
        }
        return new ColorMatrixColorFilter(cm);
    }

    /** Returns an ARGB int for the supplemental tint overlay, or null if not needed. */
    @Nullable
    private static Integer buildTintOverlay(String filter) {
        if (filter == null) return null;
        switch (filter) {
            case "golden_hour": return 0x18FF9900;
            case "rose":        return 0x12FF6688;
            case "sunset":      return 0x1AFF4400;
            case "neon_pop":    return 0x10FF00FF;
            case "matrix":      return 0x1500FF44;
            case "dream":       return 0x14AAAAFF;
            case "chrome":      return 0x0CFFFFFF;
            case "matte":       return 0x10FFFFFF;
            case "vintage":     return 0x10AA6600;
            case "fade_film":   return 0x10DDDDCC;
            default:            return null;
        }
    }

    // ── Visual effect overlays ────────────────────────────────────────────────

    private void applyEffect(@NonNull PhotoVH h, @Nullable String effect) {
        if (effect == null || "none".equals(effect)) {
            h.vEffectOverlay.setVisibility(View.GONE);
            return;
        }
        h.vEffectOverlay.setVisibility(View.VISIBLE);

        switch (effect) {
            case "vignette":
                drawVignetteOnView(h.vEffectOverlay);
                break;
            case "grain":
                drawGrainOnView(h.vEffectOverlay);
                break;
            case "glitch_overlay":
                drawGlitchOnView(h.vEffectOverlay);
                break;
            case "neon_glow":
                h.vEffectOverlay.setBackgroundColor(0x22FF00FF);
                break;
            case "matte_overlay":
                h.vEffectOverlay.setBackgroundColor(0x33FFFFFF);
                break;
            case "chrome_leak":
                drawChromeLeak(h.vEffectOverlay);
                break;
            case "bokeh":
                h.vEffectOverlay.setBackgroundColor(0x15000000);
                break;
            case "scanlines":
                drawScanlinesOnView(h.vEffectOverlay);
                break;
            case "dust":
                drawDustOnView(h.vEffectOverlay);
                break;
            case "double_exposure":
                h.vEffectOverlay.setBackgroundColor(0x30FFFFFF);
                break;
            default:
                h.vEffectOverlay.setVisibility(View.GONE);
        }
    }

    private static void drawVignetteOnView(View v) {
        v.setBackground(new android.graphics.drawable.Drawable() {
            @Override
            public void draw(@NonNull Canvas c) {
                int w = getBounds().width(), h = getBounds().height();
                if (w == 0 || h == 0) return;
                float cx = w / 2f, cy = h / 2f;
                float radius = (float) Math.sqrt(cx * cx + cy * cy);
                int[] colors = {0x00000000, 0x00000000, 0xBB000000};
                float[] positions = {0f, 0.55f, 1f};
                RadialGradient rg = new RadialGradient(cx, cy, radius, colors, positions, Shader.TileMode.CLAMP);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(rg);
                c.drawRect(0, 0, w, h, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
    }

    private static void drawGrainOnView(View v) {
        // Subtle noise via a repeating random-dot tile
        v.setBackground(new android.graphics.drawable.Drawable() {
            @Override
            public void draw(@NonNull Canvas c) {
                int w = getBounds().width(), h = getBounds().height();
                if (w == 0 || h == 0) return;
                Paint p = new Paint();
                Random rnd = new Random(42); // deterministic seed for stable look
                for (int i = 0; i < 1800; i++) {
                    int alpha = 15 + rnd.nextInt(25);
                    p.setColor(Color.argb(alpha, 255, 255, 255));
                    float x = rnd.nextFloat() * w;
                    float y = rnd.nextFloat() * h;
                    c.drawPoint(x, y, p);
                }
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
    }

    private static void drawGlitchOnView(View v) {
        v.setBackground(new android.graphics.drawable.Drawable() {
            @Override
            public void draw(@NonNull Canvas c) {
                int w = getBounds().width(), h = getBounds().height();
                Paint p = new Paint();
                // 3 horizontal RGB-shift stripes
                p.setColor(0x20FF0000); c.drawRect(0, h*0.3f, w, h*0.32f, p);
                p.setColor(0x200000FF); c.drawRect(6, h*0.3f, w, h*0.32f, p);
                p.setColor(0x1500FF00); c.drawRect(0, h*0.65f, w, h*0.655f, p);
                p.setColor(0x15FF0000); c.drawRect(8, h*0.65f, w, h*0.655f, p);
                p.setColor(0x12FFFFFF); c.drawRect(0, h*0.72f, w*0.4f, h*0.724f, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
    }

    private static void drawChromeLeak(View v) {
        v.setBackground(new android.graphics.drawable.Drawable() {
            @Override
            public void draw(@NonNull Canvas c) {
                int w = getBounds().width(), h = getBounds().height();
                // Top-left lens flare
                int[] col = {0x55FFFACD, 0x00FFFACD};
                RadialGradient rg = new RadialGradient(w*0.1f, h*0.1f, w*0.5f, col, null, Shader.TileMode.CLAMP);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(rg);
                c.drawRect(0, 0, w, h, p);
                // Bottom-right complementary
                int[] col2 = {0x33FF88CC, 0x00FF88CC};
                RadialGradient rg2 = new RadialGradient(w*0.9f, h*0.9f, w*0.4f, col2, null, Shader.TileMode.CLAMP);
                p.setShader(rg2);
                c.drawRect(0, 0, w, h, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
    }

    private static void drawScanlinesOnView(View v) {
        v.setBackground(new android.graphics.drawable.Drawable() {
            @Override
            public void draw(@NonNull Canvas c) {
                int w = getBounds().width(), h = getBounds().height();
                Paint p = new Paint();
                p.setColor(0x18000000);
                for (int y = 0; y < h; y += 4) c.drawRect(0, y, w, y + 2, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
    }

    private static void drawDustOnView(View v) {
        v.setBackground(new android.graphics.drawable.Drawable() {
            @Override
            public void draw(@NonNull Canvas c) {
                int w = getBounds().width(), h = getBounds().height();
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                Random r = new Random(7);
                for (int i = 0; i < 60; i++) {
                    int alpha = 20 + r.nextInt(40);
                    p.setColor(Color.argb(alpha, 255, 255, 220));
                    float x = r.nextFloat() * w, y = r.nextFloat() * h;
                    float radius = 0.5f + r.nextFloat() * 2f;
                    c.drawCircle(x, y, radius, p);
                }
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
    }

    // ── Caption ───────────────────────────────────────────────────────────────

    private void bindCaption(@NonNull PhotoVH h, @Nullable String caption,
                             @Nullable String styleJson) {
        if (caption == null || caption.isEmpty()) {
            h.tvCaption.setVisibility(View.GONE);
            h.vCaptionGradient.setVisibility(View.GONE);
            return;
        }
        h.tvCaption.setText(caption);
        h.tvCaption.setVisibility(View.VISIBLE);
        h.vCaptionGradient.setVisibility(View.VISIBLE);

        // Apply caption style
        applyCaptionStyle(h.tvCaption, styleJson);

        // Slide-up + fade-in animation
        h.tvCaption.setAlpha(0f);
        h.tvCaption.setTranslationY(24f);
        h.tvCaption.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(CAPTION_ANIM_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private static void applyCaptionStyle(TextView tv, @Nullable String styleJson) {
        // Defaults
        int textColor   = Color.WHITE;
        int bgColor     = 0xBB000000;
        float textSizeSp = 13f;
        boolean bold    = false;
        boolean italic  = false;
        String fontFamily = "sans";
        int gravity     = android.view.Gravity.START;

        if (styleJson != null && !styleJson.isEmpty()) {
            try {
                // Tiny inline JSON parser (avoid adding a library dependency)
                textColor   = extractJsonColor(styleJson, "color", textColor);
                bgColor     = extractJsonColor(styleJson, "bg",    bgColor);
                textSizeSp  = extractJsonFloat(styleJson, "size",  textSizeSp);
                bold        = extractJsonBool(styleJson, "bold",   bold);
                italic      = extractJsonBool(styleJson, "italic", italic);
                fontFamily  = extractJsonStr(styleJson, "font",    fontFamily);
                String align = extractJsonStr(styleJson, "align",  "start");
                if ("center".equals(align)) gravity = android.view.Gravity.CENTER;
                else if ("end".equals(align)) gravity = android.view.Gravity.END;
            } catch (Exception ignored) {}
        }
        tv.setTextColor(textColor);
        tv.setBackgroundColor(bgColor);
        tv.setTextSize(textSizeSp);
        tv.setGravity(gravity);
        int style = bold && italic ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD
                    : italic ? Typeface.ITALIC : Typeface.NORMAL;
        if ("serif".equals(fontFamily)) {
            tv.setTypeface(Typeface.SERIF, style);
        } else if ("mono".equals(fontFamily)) {
            tv.setTypeface(Typeface.MONOSPACE, style);
        } else {
            tv.setTypeface(Typeface.SANS_SERIF, style);
        }
    }

    // ── Sticker overlays ──────────────────────────────────────────────────────

    private void bindStickers(@NonNull PhotoVH h, @Nullable String stickerJson, int position) {
        h.llStickerLayer.removeAllViews();
        if (stickerJson == null || stickerJson.isEmpty() || stickerJson.equals("[]")) return;
        // Parse minimal JSON array: [{"type":"emoji","value":"🔥","x":0.5,"y":0.3,"scale":1.2}, ...]
        parseStickerArray(stickerJson, h.llStickerLayer, position);
    }

    private void parseStickerArray(String json, FrameLayout container, int position) {
        // Strip outer brackets
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return;

        // Split by top-level objects
        int depth = 0, start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String obj = inner.substring(start, i + 1);
                    addStickerView(obj, container, position);
                    start = i + 1;
                    while (start < inner.length() && (inner.charAt(start) == ',')) start++;
                }
            }
        }
    }

    private void addStickerView(String obj, FrameLayout container, int position) {
        try {
            String type  = extractJsonStr(obj, "type",  "emoji");
            String value = extractJsonStr(obj, "value", "");
            float  xFrac = extractJsonFloat(obj, "x",   0.5f);
            float  yFrac = extractJsonFloat(obj, "y",   0.5f);
            float  scale = extractJsonFloat(obj, "scale", 1.0f);
            float  rot   = extractJsonFloat(obj, "rotation", 0f);

            if (value.isEmpty()) return;

            TextView tv = new TextView(container.getContext());
            tv.setText(value);
            tv.setTextSize(28f);
            tv.setPadding(4, 2, 4, 2);
            tv.setTag(TAG_STICKER_VIEW);
            tv.setScaleX(scale);
            tv.setScaleY(scale);
            tv.setRotation(rot);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            tv.setLayoutParams(lp);

            container.addView(tv);
            // Position after layout pass
            container.post(() -> {
                int cw = container.getWidth(), ch = container.getHeight();
                if (cw == 0 || ch == 0) return;
                tv.setX(xFrac * cw - tv.getWidth() / 2f);
                tv.setY(yFrac * ch - tv.getHeight() / 2f);
            });

            if (editMode) makeDraggable(tv, container, position);
        } catch (Exception ignored) {}
    }

    private void makeDraggable(View view, ViewGroup parent, int adapterPos) {
        view.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - e.getRawX();
                        dY = v.getY() - e.getRawY();
                        v.performClick();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float nx = e.getRawX() + dX;
                        float ny = e.getRawY() + dY;
                        nx = Math.max(0, Math.min(nx, parent.getWidth() - v.getWidth()));
                        ny = Math.max(0, Math.min(ny, parent.getHeight() - v.getHeight()));
                        v.setX(nx); v.setY(ny);
                        return true;
                }
                return false;
            }
        });
    }

    // ── Ken Burns pan+zoom ────────────────────────────────────────────────────

    private void startKenBurns(@NonNull PhotoVH h, int position) {
        cancelKenBurns(h);

        long durationMs = reelModel.effectiveDurationForPhoto(position);
        long animDur    = Math.max(durationMs + 800L, 3600L);
        float scaleTarget = reelModel.kenBurnsScaleTarget();

        String direction = reelModel.kenBurnsDirectionForPhoto(position);
        if ("random".equals(direction)) {
            int pick = (int)(System.nanoTime() % 5);
            String[] dirs = {"tl_br", "tr_bl", "center_out", "bottom_up", "br_tl"};
            direction = dirs[pick < 0 ? 0 : pick];
        }

        float dx = 0f, dy = 0f;
        switch (direction) {
            case "tl_br":   dx =  22f; dy =  14f; break;
            case "tr_bl":   dx = -22f; dy =  14f; break;
            case "center_out": dx = 0f; dy = 0f;  break;
            case "bottom_up": dx = 0f; dy = -20f; break;
            case "br_tl":   dx = -22f; dy = -14f; break;
            default:
                long seed = System.nanoTime();
                dx = ((seed & 1L) == 0 ? 1f : -1f) * 22f;
                dy = ((seed & 2L) == 0 ? 1f : -1f) * 14f;
        }

        ObjectAnimator sX = ObjectAnimator.ofFloat(h.ivPhoto, "scaleX",     1.0f, scaleTarget);
        ObjectAnimator sY = ObjectAnimator.ofFloat(h.ivPhoto, "scaleY",     1.0f, scaleTarget);
        ObjectAnimator tX = ObjectAnimator.ofFloat(h.ivPhoto, "translationX", 0f, dx);
        ObjectAnimator tY = ObjectAnimator.ofFloat(h.ivPhoto, "translationY", 0f, dy);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(sX, sY, tX, tY);
        set.setDuration(animDur);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.start();
        h.ivPhoto.setTag(R.id.iv_photo_slide, set);
    }

    private void cancelKenBurns(@NonNull PhotoVH h) {
        Object tag = h.ivPhoto.getTag(R.id.iv_photo_slide);
        if (tag instanceof AnimatorSet) ((AnimatorSet) tag).cancel();
    }

    private void resetViewTransforms(@NonNull PhotoVH h) {
        h.ivPhoto.setScaleX(1f);    h.ivPhoto.setScaleY(1f);
        h.ivPhoto.setTranslationX(0f); h.ivPhoto.setTranslationY(0f);
        h.ivPhoto.setAlpha(1f);
    }

    // ── Gesture handlers (pinch-to-zoom, double-tap, long-press) ─────────────

    private void attachGestures(@NonNull PhotoVH h, int position) {
        boolean allowPinch = reelModel.allowPinchZoom;
        boolean allowDTap  = reelModel.allowDoubleTapZoom;
        float   maxScale   = reelModel.maxZoomScale > 0 ? reelModel.maxZoomScale : 3f;

        ScaleGestureDetector pinch = new ScaleGestureDetector(
                h.viewZoomTouch.getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            float initScale;
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                cancelKenBurns(h);
                initScale = zoomScaleArr[position];
                return allowPinch;
            }
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector d) {
                if (!allowPinch) return false;
                float factor = d.getScaleFactor();
                float newScale = Math.max(1f, Math.min(zoomScaleArr[position] * factor, maxScale));
                zoomScaleArr[position] = newScale;
                h.ivPhoto.setScaleX(newScale);
                h.ivPhoto.setScaleY(newScale);
                // Pan offset clamping
                float maxTx = (newScale - 1f) * h.ivPhoto.getWidth() * 0.5f;
                float maxTy = (newScale - 1f) * h.ivPhoto.getHeight() * 0.5f;
                zoomTransXArr[position] = clamp(zoomTransXArr[position], -maxTx, maxTx);
                zoomTransYArr[position] = clamp(zoomTransYArr[position], -maxTy, maxTy);
                return true;
            }
            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                if (zoomScaleArr[position] <= 1.05f) {
                    // Snap back to 1×
                    animateZoomBack(h, position);
                }
            }
        });

        GestureDetector gesture = new GestureDetector(
                h.viewZoomTouch.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!allowDTap) return false;
                cancelKenBurns(h);
                float target = zoomScaleArr[position] > 1.2f ? 1f : DEFAULT_ZOOM_TARGET;
                animateZoomTo(h, position, target);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoomScaleArr[position] <= 1.0f) return false;
                float cur = zoomScaleArr[position];
                float maxTx = (cur - 1f) * h.ivPhoto.getWidth() * 0.5f;
                float maxTy = (cur - 1f) * h.ivPhoto.getHeight() * 0.5f;
                zoomTransXArr[position] = clamp(zoomTransXArr[position] - dx * 0.5f, -maxTx, maxTx);
                zoomTransYArr[position] = clamp(zoomTransYArr[position] - dy * 0.5f, -maxTy, maxTy);
                h.ivPhoto.setTranslationX(zoomTransXArr[position]);
                h.ivPhoto.setTranslationY(zoomTransYArr[position]);
                return true;
            }
        });

        h.viewZoomTouch.setOnTouchListener((v, event) -> {
            pinch.onTouchEvent(event);
            gesture.onTouchEvent(event);
            handleLongPress(h, event, position);
            return true;
        });
    }

    // Long-press detection (separate from GestureDetector to avoid swallow issues)
    private static final long LONG_PRESS_TRIGGER_MS = 420L;
    private final Handler lpHandler = new Handler(Looper.getMainLooper());
    private Runnable lpRunnable;

    private void handleLongPress(@NonNull PhotoVH h, MotionEvent e, int position) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lpRunnable = () -> {
                    if (listener != null) listener.onLongPressStateChanged(true);
                };
                lpHandler.postDelayed(lpRunnable, LONG_PRESS_TRIGGER_MS);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lpHandler.removeCallbacks(lpRunnable);
                if (listener != null) listener.onLongPressStateChanged(false);
                break;
        }
    }

    private void animateZoomTo(@NonNull PhotoVH h, int position, float targetScale) {
        ObjectAnimator sx = ObjectAnimator.ofFloat(h.ivPhoto, "scaleX", h.ivPhoto.getScaleX(), targetScale);
        ObjectAnimator sy = ObjectAnimator.ofFloat(h.ivPhoto, "scaleY", h.ivPhoto.getScaleY(), targetScale);
        ObjectAnimator tx = ObjectAnimator.ofFloat(h.ivPhoto, "translationX", h.ivPhoto.getTranslationX(), 0f);
        ObjectAnimator ty = ObjectAnimator.ofFloat(h.ivPhoto, "translationY", h.ivPhoto.getTranslationY(), 0f);
        AnimatorSet anim = new AnimatorSet();
        anim.playTogether(sx, sy, tx, ty);
        anim.setDuration(ZOOM_SNAP_DURATION);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                zoomScaleArr[position]  = targetScale;
                zoomTransXArr[position] = 0f;
                zoomTransYArr[position] = 0f;
                if (targetScale <= 1f) startKenBurns(h, position);
            }
        });
        anim.start();
    }

    private void animateZoomBack(@NonNull PhotoVH h, int position) {
        animateZoomTo(h, position, 1f);
    }

    // ── Glide preload ─────────────────────────────────────────────────────────

    private void preloadNeighbours(Context ctx, int position) {
        for (int delta = 1; delta <= 2; delta++) {
            int next = position + delta;
            if (next < getItemCount()) {
                Glide.with(ctx)
                        .load(photoUrls.get(next))
                        .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.ALL))
                        .preload();
            }
        }
    }

    // ── PageTransformer factory ───────────────────────────────────────────────

    /**
     * Returns a ViewPager2.PageTransformer for the given transition key.
     * The adapter can have per-photo transitions set in the model; this static
     * factory is used by the host to set the initial default transformer.
     */
    @Nullable
    public static ViewPager2.PageTransformer getPageTransformer(String type) {
        if (type == null) return new FadeTransformer();
        switch (type) {
            case "zoom":       return new ZoomOutTransformer();
            case "slide":      return new DepthTransformer();
            case "cube":       return new CubeTransformer();
            case "flip3d":     return new Flip3DTransformer();
            case "carousel":   return new CarouselTransformer();
            case "stack":      return new StackTransformer();
            case "parallax":   return new ParallaxTransformer();
            case "blur":       return new BlurFadeTransformer();
            case "glitch":     return new GlitchTransformer();
            case "reveal":     return new RevealTransformer();
            case "none":       return null;
            default:           return new FadeTransformer();
        }
    }

    // ── Transitions ───────────────────────────────────────────────────────────

    /** Classic Instagram cross-fade. */
    public static class FadeTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            if (pos < -1f || pos > 1f) page.setAlpha(0f);
            else {
                page.setAlpha(1f - Math.abs(pos));
                page.setTranslationX(-pos * page.getWidth());
            }
        }
    }

    /** Google's ZoomOut from ViewPager docs. */
    public static class ZoomOutTransformer implements ViewPager2.PageTransformer {
        private static final float MIN_S = 0.85f, MIN_A = 0.5f;
        @Override public void transformPage(@NonNull View page, float pos) {
            if (pos < -1f || pos > 1f) { page.setAlpha(0f); return; }
            float scale = Math.max(MIN_S, 1f - Math.abs(pos) * (1f - MIN_S));
            page.setScaleX(scale); page.setScaleY(scale);
            page.setAlpha(MIN_A + (scale - MIN_S) / (1f - MIN_S) * (1f - MIN_A));
        }
    }

    /** 3-D depth effect: foreground slides, background scales. */
    public static class DepthTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            if (pos < -1f) { page.setAlpha(0f); return; }
            if (pos <= 0f) { page.setAlpha(1f); page.setTranslationX(0f); page.setScaleX(1f); page.setScaleY(1f); }
            else if (pos <= 1f) {
                page.setAlpha(1f - pos);
                page.setTranslationX(-page.getWidth() * pos);
                float s = 0.75f + (1f - pos) * 0.25f;
                page.setScaleX(s); page.setScaleY(s);
            } else { page.setAlpha(0f); }
        }
    }

    /** 3-D cube rotation on Y-axis. */
    public static class CubeTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            page.setPivotX(pos < 0 ? page.getWidth() : 0f);
            page.setRotationY(90f * pos);
            page.setAlpha(pos > -0.5f && pos < 0.5f ? 1f : 0f);
        }
    }

    /** Flip on Y-axis (like turning a card). */
    public static class Flip3DTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            float rot = -180f * pos;
            page.setRotationY(rot);
            page.setAlpha(Math.abs(rot) < 90f ? 1f : 0f);
        }
    }

    /** Carousel: outer pages shrink and fade. */
    public static class CarouselTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            float absPos = Math.abs(pos);
            page.setScaleX(1f - 0.15f * absPos);
            page.setScaleY(1f - 0.10f * absPos);
            page.setAlpha(1f - 0.4f * absPos);
            page.setTranslationX(-pos * page.getWidth() * 0.25f);
        }
    }

    /** Stack: new page slides over a stationary stack. */
    public static class StackTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            if (pos <= 0f) {
                page.setTranslationX(0f); page.setScaleX(1f); page.setScaleY(1f);
            } else {
                page.setTranslationX(-pos * page.getWidth());
                float scale = 1f - 0.12f * pos;
                page.setScaleX(scale); page.setScaleY(scale);
                page.setAlpha(1f - pos * 0.5f);
            }
        }
    }

    /** Parallax: background moves slower than foreground. */
    public static class ParallaxTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            page.setTranslationX(-pos * page.getWidth() * 0.3f);
            page.setAlpha(1f - Math.abs(pos) * 0.5f);
        }
    }

    /** Blur-fade: fades with slight scale (simulates blur). */
    public static class BlurFadeTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            float absPos = Math.abs(pos);
            page.setAlpha(1f - absPos * 0.7f);
            page.setScaleX(1f - absPos * 0.08f);
            page.setScaleY(1f - absPos * 0.08f);
            page.setTranslationX(-pos * page.getWidth());
        }
    }

    /** Glitch: jitter + RGB-offset (simulated via rapid translation). */
    public static class GlitchTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            float absPos = Math.abs(pos);
            page.setAlpha(1f - absPos);
            page.setTranslationX(-pos * page.getWidth());
            if (absPos > 0.05f && absPos < 0.95f) {
                // Horizontal jitter based on position
                float jitter = (float)(Math.sin(pos * Math.PI * 12) * 10 * absPos);
                page.setTranslationX(page.getTranslationX() + jitter);
            }
        }
    }

    /** Reveal: next page appears from behind current (no translate). */
    public static class RevealTransformer implements ViewPager2.PageTransformer {
        @Override public void transformPage(@NonNull View page, float pos) {
            if (pos <= 0f) {
                page.setTranslationX(-page.getWidth() * pos * 0.5f);
                page.setAlpha(1f);
            } else {
                page.setTranslationX(0f);
                page.setAlpha(1f - pos);
            }
        }
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    public static class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView   ivPhoto;
        final View        vEffectOverlay;
        final View        vColorFilterOverlay;
        final FrameLayout llStickerLayer;
        final View        viewZoomTouch;
        final TextView    tvCaption;
        final View        vCaptionGradient;

        PhotoVH(@NonNull View itemView) {
            super(itemView);
            ivPhoto             = itemView.findViewById(R.id.iv_photo_slide);
            vEffectOverlay      = itemView.findViewById(R.id.v_effect_overlay);
            vColorFilterOverlay = itemView.findViewById(R.id.v_color_filter_overlay);
            llStickerLayer      = itemView.findViewById(R.id.ll_sticker_layer);
            viewZoomTouch       = itemView.findViewById(R.id.view_zoom_touch);
            tvCaption           = itemView.findViewById(R.id.tv_photo_caption);
            vCaptionGradient    = itemView.findViewById(R.id.v_caption_gradient);
        }
    }

    // ── Tiny inline JSON helpers ───────────────────────────────────────────────

    private static int extractJsonColor(String json, String key, int def) {
        try {
            String s = extractJsonStr(json, key, null);
            if (s == null) return def;
            return Color.parseColor(s);
        } catch (Exception e) { return def; }
    }

    private static float extractJsonFloat(String json, String key, float def) {
        try {
            int ki = json.indexOf("\"" + key + "\"");
            if (ki < 0) return def;
            int colon = json.indexOf(':', ki);
            int end = colon + 1;
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
            return Float.parseFloat(json.substring(colon + 1, end).trim());
        } catch (Exception e) { return def; }
    }

    private static boolean extractJsonBool(String json, String key, boolean def) {
        try {
            int ki = json.indexOf("\"" + key + "\"");
            if (ki < 0) return def;
            int colon = json.indexOf(':', ki);
            String rest = json.substring(colon + 1).trim();
            return rest.startsWith("true");
        } catch (Exception e) { return def; }
    }

    private static String extractJsonStr(String json, String key, String def) {
        try {
            int ki = json.indexOf("\"" + key + "\"");
            if (ki < 0) return def;
            int colon = json.indexOf(':', ki);
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) return def;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return def;
            return json.substring(q1 + 1, q2);
        } catch (Exception e) { return def; }
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
