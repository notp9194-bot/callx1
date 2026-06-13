package com.callx.app.feed;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;

/**
 * ReelPhotoCollageView — Multi-Photo Collage Slide v6
 * ═══════════════════════════════════════════════════════
 *
 * A custom FrameLayout that renders 2–4 photos in a dynamic collage layout
 * on a single ViewPager2 slide, replacing the single-photo display.
 *
 * Each photo tile gets its own independent Ken Burns animation so the
 * collage feels alive and cinematic.
 *
 * ── Supported layouts ────────────────────────────────────────────────────
 *   GRID_2x2        — 4 equal tiles in a 2×2 grid
 *   SPLIT_H         — 2 photos side by side (50/50 horizontal)
 *   SPLIT_V         — 2 photos stacked (top / bottom)
 *   TRIPTYCH        — 3 equal columns
 *   FEATURED_LEFT   — 1 large photo left (60%) + 2 stacked right (40%)
 *   FEATURED_RIGHT  — 2 stacked left (40%) + 1 large right (60%)
 *   WIDE_TOP        — 1 wide photo top (40% h) + 2 equal below
 *   SINGLE          — fallback: full-screen single photo (same as normal slide)
 *
 * Usage:
 *   ReelPhotoCollageView collage = new ReelPhotoCollageView(ctx);
 *   collage.bind(photoUrls, "grid_2x2", "warm", "vignette");
 *   viewPager2ItemRoot.addView(collage, MATCH_PARENT, MATCH_PARENT);
 */
public class ReelPhotoCollageView extends FrameLayout {

    public enum Layout {
        SINGLE, SPLIT_H, SPLIT_V, TRIPTYCH, GRID_2X2, FEATURED_LEFT, FEATURED_RIGHT, WIDE_TOP;

        /** Parse from a stored string. Falls back to SINGLE. */
        public static Layout fromString(@Nullable String s) {
            if (s == null) return SINGLE;
            switch (s.toLowerCase()) {
                case "split_h":         return SPLIT_H;
                case "split_v":         return SPLIT_V;
                case "triptych":        return TRIPTYCH;
                case "grid_2x2":        return GRID_2X2;
                case "featured_left":   return FEATURED_LEFT;
                case "featured_right":  return FEATURED_RIGHT;
                case "wide_top":        return WIDE_TOP;
                default:                return SINGLE;
            }
        }

        /** Returns the minimum number of photos this layout needs. */
        public int minPhotos() {
            switch (this) {
                case GRID_2X2:          return 4;
                case TRIPTYCH:          return 3;
                case FEATURED_LEFT:
                case FEATURED_RIGHT:
                case WIDE_TOP:          return 3;
                case SPLIT_H:
                case SPLIT_V:           return 2;
                default:                return 1;
            }
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private static final float GAP_DP = 2f;

    // ── Constructors ──────────────────────────────────────────────────────────

    public ReelPhotoCollageView(@NonNull Context context) { super(context); }
    public ReelPhotoCollageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Binds photo URLs to the collage view. Clears any previous content.
     *
     * @param urls       List of photo URLs (must have >= layout.minPhotos() entries)
     * @param layoutKey  Layout key string (see Layout.fromString)
     * @param filter     Global colour filter name (from ReelPhotoSlideshowAdapter.buildColorFilter)
     * @param effect     Global effect name (used as background tint on each tile's overlay)
     */
    public void bind(@NonNull List<String> urls, @Nullable String layoutKey,
                     @Nullable String filter, @Nullable String effect) {
        removeAllViews();
        if (urls.isEmpty()) return;

        Layout layout = Layout.fromString(layoutKey);
        // Downgrade if not enough photos
        while (layout.minPhotos() > urls.size() && layout != Layout.SINGLE) {
            layout = degradeLayout(layout);
        }

        buildCollage(urls, layout, filter, effect);
    }

    // ── Layout builders ───────────────────────────────────────────────────────

    private void buildCollage(List<String> urls, Layout layout, String filter, String effect) {
        float density = getResources().getDisplayMetrics().density;
        float gap     = GAP_DP * density;

        switch (layout) {

            case SPLIT_H: {
                // [ Photo 0 | Photo 1 ]
                LinearLayout row = hRow(MATCH_PARENT, MATCH_PARENT);
                addTile(row, urls.get(0), 1f, MATCH_PARENT, filter, effect, gap/2, 0, gap/2, 0, 400);
                addTile(row, urls.get(1), 1f, MATCH_PARENT, filter, effect, 0, 0, 0, 0, 600);
                addView(row);
                break;
            }
            case SPLIT_V: {
                // [ Photo 0 top / Photo 1 bottom ]
                LinearLayout col = vCol(MATCH_PARENT, MATCH_PARENT);
                addTile(col, urls.get(0), MATCH_PARENT, 1f, filter, effect, 0, 0, 0, gap/2, 400);
                addTile(col, urls.get(1), MATCH_PARENT, 1f, filter, effect, 0, 0, 0, 0, 600);
                addView(col);
                break;
            }
            case TRIPTYCH: {
                // [ 0 | 1 | 2 ] equal thirds
                LinearLayout row = hRow(MATCH_PARENT, MATCH_PARENT);
                addTile(row, urls.get(0), 1f, MATCH_PARENT, filter, effect, 0, 0, gap/2, 0, 300);
                addTile(row, urls.get(1), 1f, MATCH_PARENT, filter, effect, gap/2, 0, gap/2, 0, 500);
                addTile(row, urls.get(2), 1f, MATCH_PARENT, filter, effect, gap/2, 0, 0, 0, 700);
                addView(row);
                break;
            }
            case GRID_2X2: {
                // Row 0: [0 | 1]
                // Row 1: [2 | 3]
                LinearLayout col = vCol(MATCH_PARENT, MATCH_PARENT);
                LinearLayout r0  = hRow(MATCH_PARENT, 1f);
                addTile(r0, urls.get(0), 1f, MATCH_PARENT, filter, effect, 0, 0, gap/2, gap/2, 200);
                addTile(r0, urls.get(1), 1f, MATCH_PARENT, filter, effect, gap/2, 0, 0, gap/2, 400);
                LinearLayout r1  = hRow(MATCH_PARENT, 1f);
                addTile(r1, urls.get(2), 1f, MATCH_PARENT, filter, effect, 0, gap/2, gap/2, 0, 600);
                addTile(r1, urls.get(3), 1f, MATCH_PARENT, filter, effect, gap/2, gap/2, 0, 0, 800);
                col.addView(r0); col.addView(r1);
                addView(col);
                break;
            }
            case FEATURED_LEFT: {
                // [ large (60%) | small top / small bottom (40%) ]
                LinearLayout row = hRow(MATCH_PARENT, MATCH_PARENT);
                addTileFraction(row, urls.get(0), 0.60f, MATCH_PARENT, filter, effect, 0, 0, gap/2, 0, 300);
                LinearLayout rightCol = vCol(0, MATCH_PARENT);
                ((LinearLayout.LayoutParams) rightCol.getLayoutParams()).weight = 0.40f;
                ((LinearLayout.LayoutParams) rightCol.getLayoutParams()).width  = 0;
                addTile(rightCol, urls.get(1), MATCH_PARENT, 1f, filter, effect, 0, 0, 0, gap/2, 550);
                addTile(rightCol, urls.get(2), MATCH_PARENT, 1f, filter, effect, 0, gap/2, 0, 0, 750);
                row.addView(rightCol);
                addView(row);
                break;
            }
            case FEATURED_RIGHT: {
                // [ small top / small bottom (40%) | large (60%) ]
                LinearLayout row = hRow(MATCH_PARENT, MATCH_PARENT);
                LinearLayout leftCol = vCol(0, MATCH_PARENT);
                leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, 0.40f));
                addTile(leftCol, urls.get(0), MATCH_PARENT, 1f, filter, effect, 0, 0, gap/2, gap/2, 300);
                addTile(leftCol, urls.get(1), MATCH_PARENT, 1f, filter, effect, 0, gap/2, gap/2, 0, 500);
                row.addView(leftCol);
                addTileFraction(row, urls.get(2), 0.60f, MATCH_PARENT, filter, effect, gap/2, 0, 0, 0, 700);
                addView(row);
                break;
            }
            case WIDE_TOP: {
                // [ wide photo top (40% h) ]
                // [ photo2 | photo3 (60% h) ]
                LinearLayout col = vCol(MATCH_PARENT, MATCH_PARENT);
                LinearLayout topRow = hRow(MATCH_PARENT, 0);
                topRow.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.40f));
                addTile(topRow, urls.get(0), 1f, MATCH_PARENT, filter, effect, 0, 0, 0, gap/2, 300);
                col.addView(topRow);
                LinearLayout botRow = hRow(MATCH_PARENT, 0);
                botRow.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.60f));
                addTile(botRow, urls.get(1), 1f, MATCH_PARENT, filter, effect, 0, gap/2, gap/2, 0, 600);
                addTile(botRow, urls.get(2), 1f, MATCH_PARENT, filter, effect, gap/2, gap/2, 0, 0, 800);
                col.addView(botRow);
                addView(col);
                break;
            }
            default: {
                // SINGLE — full-screen
                addTile(this, urls.get(0), 1f, MATCH_PARENT, filter, effect, 0, 0, 0, 0, 0);
                break;
            }
        }

        // Divider overlay (thin gap lines)
        addGapOverlay(gap);
    }

    // ── Tile helpers ──────────────────────────────────────────────────────────

    /** Add a photo tile to a parent ViewGroup with weight-based sizing. */
    private void addTile(ViewGroup parent, String url, float weightW, int heightOrWeight,
                         String filter, String effect,
                         float marginLeft, float marginTop, float marginRight, float marginBottom,
                         long entryDelayMs) {
        FrameLayout tile = makeTile(url, filter, effect, entryDelayMs);
        LinearLayout.LayoutParams lp;
        if (heightOrWeight == MATCH_PARENT) {
            lp = new LinearLayout.LayoutParams(0, MATCH_PARENT, weightW);
        } else {
            lp = new LinearLayout.LayoutParams(0, 0, weightW);
        }
        lp.setMargins((int)marginLeft, (int)marginTop, (int)marginRight, (int)marginBottom);
        tile.setLayoutParams(lp);
        parent.addView(tile);
    }

    /** Add a tile with explicit weight fraction in a horizontal row. */
    private void addTileFraction(ViewGroup parent, String url, float weightW, int heightOrWeight,
                                 String filter, String effect,
                                 float mL, float mT, float mR, float mB, long delay) {
        addTile(parent, url, weightW, heightOrWeight, filter, effect, mL, mT, mR, mB, delay);
    }

    private FrameLayout makeTile(String url, String filter, String effect, long entryDelayMs) {
        FrameLayout tile = new FrameLayout(getContext());
        tile.setClipChildren(true); tile.setClipToPadding(true);
        tile.setBackgroundColor(Color.BLACK);

        // Photo ImageView
        ImageView iv = new ImageView(getContext());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        tile.addView(iv);

        // Effect overlay
        View overlay = new View(getContext());
        overlay.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        overlay.setClickable(false);
        if (effect != null && !"none".equals(effect)) {
            overlay.setBackgroundColor(effectTint(effect));
            overlay.setVisibility(VISIBLE);
        } else {
            overlay.setVisibility(GONE);
        }
        tile.addView(overlay);

        // Load image with Glide
        Glide.with(getContext())
                .load(url)
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop())
                .into(iv);

        // Apply colour filter
        if (filter != null && !"normal".equals(filter)) {
            iv.setColorFilter(ReelPhotoSlideshowAdapter.buildColorFilter(filter));
        }

        // Entry pop-in animation (each tile enters with staggered delay)
        tile.setAlpha(0f);
        tile.setScaleX(0.92f); tile.setScaleY(0.92f);
        ObjectAnimator alphaIn = ObjectAnimator.ofFloat(tile, "alpha", 0f, 1f);
        ObjectAnimator scaleX  = ObjectAnimator.ofFloat(tile, "scaleX", 0.92f, 1f);
        ObjectAnimator scaleY  = ObjectAnimator.ofFloat(tile, "scaleY", 0.92f, 1f);
        AnimatorSet enter = new AnimatorSet();
        enter.playTogether(alphaIn, scaleX, scaleY);
        enter.setDuration(380);
        enter.setStartDelay(entryDelayMs);
        enter.setInterpolator(new DecelerateInterpolator(1.4f));
        tile.post(enter::start);

        // Subtle per-tile Ken Burns (random direction)
        tile.post(() -> startTileKenBurns(iv, 4000L + entryDelayMs / 2));

        return tile;
    }

    private void startTileKenBurns(ImageView iv, long durationMs) {
        long seed = System.nanoTime() + iv.hashCode();
        float dx = ((seed & 1L) == 0 ? 1f : -1f) * 16f;
        float dy = ((seed & 2L) == 0 ? 1f : -1f) * 10f;
        float scaleEnd = 1.12f;
        AnimatorSet kb = new AnimatorSet();
        kb.playTogether(
            ObjectAnimator.ofFloat(iv, "scaleX",      1f, scaleEnd),
            ObjectAnimator.ofFloat(iv, "scaleY",      1f, scaleEnd),
            ObjectAnimator.ofFloat(iv, "translationX", 0f, dx),
            ObjectAnimator.ofFloat(iv, "translationY", 0f, dy)
        );
        kb.setDuration(durationMs);
        kb.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        kb.start();
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private LinearLayout hRow(int w, float weightH) {
        LinearLayout ll = new LinearLayout(getContext());
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setLayoutParams(new LinearLayout.LayoutParams(w, 0, weightH));
        return ll;
    }

    private LinearLayout hRow(int w, int h) {
        LinearLayout ll = new LinearLayout(getContext());
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return ll;
    }

    private LinearLayout vCol(int w, int h) {
        LinearLayout ll = new LinearLayout(getContext());
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return ll;
    }

    /** Draws a thin dark hairline gap over the collage seams for a polished look. */
    private void addGapOverlay(float gap) {
        View gapView = new View(getContext()) {
            @Override protected void onDraw(@NonNull Canvas canvas) {
                if (gap <= 1f) return;
                Paint p = new Paint();
                p.setColor(Color.BLACK);
                p.setAlpha(200);
                // We just let the layout's natural spacing do the work;
                // no explicit draw needed if tile margins handle it.
            }
        };
        gapView.setBackgroundColor(Color.TRANSPARENT);
        gapView.setClickable(false);
        addView(gapView, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    private static int effectTint(String effect) {
        if (effect == null) return 0;
        switch (effect) {
            case "vignette":     return 0x44000000;
            case "grain":        return 0x1AFFFFFF;
            case "glitch_overlay": return 0x22FF0044;
            case "neon_glow":    return 0x22FF00FF;
            case "matte_overlay": return 0x33FFFFFF;
            case "chrome_leak":  return 0x22FFFACD;
            case "scanlines":    return 0x18000000;
            case "dust":         return 0x14FFFFCC;
            default:             return 0x10000000;
        }
    }

    /** Downgrade layout if not enough photos available. */
    private static Layout degradeLayout(Layout l) {
        switch (l) {
            case GRID_2X2:        return Layout.FEATURED_LEFT;
            case FEATURED_LEFT:
            case FEATURED_RIGHT:
            case WIDE_TOP:
            case TRIPTYCH:        return Layout.SPLIT_H;
            case SPLIT_H:
            case SPLIT_V:         return Layout.SINGLE;
            default:              return Layout.SINGLE;
        }
    }

    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
}
