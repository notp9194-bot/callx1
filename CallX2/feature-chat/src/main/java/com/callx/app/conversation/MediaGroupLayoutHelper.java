package com.callx.app.conversation;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;
import java.util.Map;

/**
 * Full WhatsApp-style media group layout.
 *
 * Layout rules (mirrors WhatsApp exactly):
 *  1 item  → single full-width image (240×200dp)
 *  2 items → two equal side-by-side squares
 *  3 items → 1 tall image on top + 2 side-by-side below
 *  4 items → 2×2 equal grid
 *  5+items → 2×2 grid; last (4th) cell shows "+N" overlay on top of thumbnail
 *
 * Rounded corners: 12dp on outer group container.
 * Video cells: play-button circle overlay + duration badge (bottom-start).
 * Tap  → opens MediaViewerActivity with url + type.
 * Long-tap → propagated to parent (action sheet).
 */
public class MediaGroupLayoutHelper {

    // Max thumbnails shown before "+N" overflow
    private static final int MAX_VISIBLE = 4;

    // Cell size constants (dp)
    private static final int SINGLE_W   = 240;
    private static final int SINGLE_H   = 200;
    private static final int PAIR_CELL  = 118;
    private static final int THREE_TOP_W= 240;
    private static final int THREE_TOP_H= 140;
    private static final int THREE_BOT  = 116;
    private static final int GRID_CELL  = 118;
    private static final int GAP        = 2;
    private static final int CORNER_R   = 12;

    // ─── Public entry point ────────────────────────────────────────────────
    public static void populate(Context ctx, LinearLayout container,
                                List<Map<String, Object>> items, String caption) {
        container.removeAllViews();
        if (items == null || items.isEmpty()) return;

        float d   = ctx.getResources().getDisplayMetrics().density;
        int gapPx = dp(GAP, d);
        int total = items.size();

        // Clip the container with rounded corners
        applyRoundedBg(container, CORNER_R, d);
        container.setClipToOutline(true);
        container.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        if (total == 1) {
            buildSingle(ctx, container, items, d);

        } else if (total == 2) {
            buildTwoSideBySide(ctx, container, items, d, gapPx);

        } else if (total == 3) {
            buildThree(ctx, container, items, d, gapPx);

        } else {
            // 4 or more → 2×2 grid, last cell may have +N overlay
            buildGrid(ctx, container, items, total, d, gapPx);
        }

        // Optional caption below grid
        if (caption != null && !caption.isEmpty()) {
            TextView tvCap = new TextView(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin    = dp(4, d);
            lp.leftMargin   = dp(6, d);
            lp.bottomMargin = dp(4, d);
            tvCap.setLayoutParams(lp);
            tvCap.setText(caption);
            tvCap.setTextSize(14f);
            tvCap.setTextColor(0xFFEEEEEE);
            container.addView(tvCap);
        }
    }

    // ─── Layout builders ──────────────────────────────────────────────────

    /** 1 image: full-width tall card */
    private static void buildSingle(Context ctx, LinearLayout parent,
                                    List<Map<String, Object>> items, float d) {
        FrameLayout cell = buildCell(ctx, items, 0, false, 0, d);
        int w = dp(SINGLE_W, d);
        int h = dp(SINGLE_H, d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        cell.setLayoutParams(lp);
        parent.addView(cell);
    }

    /** 2 images: side by side */
    private static void buildTwoSideBySide(Context ctx, LinearLayout parent,
                                           List<Map<String, Object>> items,
                                           float d, int gapPx) {
        LinearLayout row = makeHRow(ctx);
        int cell = dp(PAIR_CELL, d);
        for (int i = 0; i < 2; i++) {
            FrameLayout c = buildCell(ctx, items, i, false, 0, d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
            if (i > 0) lp.leftMargin = gapPx;
            c.setLayoutParams(lp);
            row.addView(c);
        }
        parent.addView(row);
    }

    /** 3 images: 1 wide on top, 2 side-by-side below */
    private static void buildThree(Context ctx, LinearLayout parent,
                                   List<Map<String, Object>> items,
                                   float d, int gapPx) {
        // Top: single wide image
        FrameLayout top = buildCell(ctx, items, 0, false, 0, d);
        int tw = dp(THREE_TOP_W, d);
        int th = dp(THREE_TOP_H, d);
        top.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
        parent.addView(top);

        // Bottom row: 2 images
        LinearLayout row = makeHRow(ctx);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = gapPx;
        row.setLayoutParams(rowLp);

        int bc = dp(THREE_BOT, d);
        for (int i = 1; i <= 2; i++) {
            FrameLayout c = buildCell(ctx, items, i, false, 0, d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(bc, bc);
            if (i > 1) lp.leftMargin = gapPx;
            c.setLayoutParams(lp);
            row.addView(c);
        }
        parent.addView(row);
    }

    /** 4+ images: 2×2 grid, last visible cell has +N overlay if total > 4 */
    private static void buildGrid(Context ctx, LinearLayout parent,
                                  List<Map<String, Object>> items,
                                  int total, float d, int gapPx) {
        int visible = Math.min(total, MAX_VISIBLE);
        int remaining = total - MAX_VISIBLE; // may be negative (safe—only used when > 0)
        int gc = dp(GRID_CELL, d);

        int idx = 0;
        while (idx < visible) {
            LinearLayout row = makeHRow(ctx);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (idx > 0) rowLp.topMargin = gapPx;
            row.setLayoutParams(rowLp);

            for (int col = 0; col < 2 && idx < visible; col++, idx++) {
                boolean isLast     = (idx == visible - 1);
                boolean showMore   = isLast && remaining > 0;
                FrameLayout c      = buildCell(ctx, items, idx, showMore, remaining, d);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(gc, gc);
                if (col > 0) lp.leftMargin = gapPx;
                c.setLayoutParams(lp);
                row.addView(c);
            }
            parent.addView(row);
        }
    }

    // ─── Cell builder ─────────────────────────────────────────────────────

    private static FrameLayout buildCell(Context ctx, List<Map<String, Object>> allItems, int index,
                                         boolean showMoreOverlay, int remaining,
                                         float d) {
        Map<String, Object> item = allItems.get(index);
        FrameLayout cell = new FrameLayout(ctx);
        cell.setClipToOutline(true);
        // Rounded corners on each cell (inner clipping)
        GradientDrawable cellBg = new GradientDrawable();
        cellBg.setColor(Color.DKGRAY);
        cellBg.setCornerRadius(dp(4, d));
        cell.setBackground(cellBg);
        cell.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        // Thumbnail ImageView
        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

        String url      = safeStr(item.get("url"));
        String thumbUrl = safeStr(item.get("thumbUrl"));
        String loadUrl  = (!thumbUrl.isEmpty()) ? thumbUrl : url;
        if (!loadUrl.isEmpty()) {
            Glide.with(ctx)
                    .load(loadUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .placeholder(android.R.color.darker_gray)
                    .into(iv);
        }
        cell.addView(iv);

        boolean isVideo = "video".equals(item.get("mediaType"));

        // Video: play-button circle overlay (only when NOT showing +N overlay)
        if (isVideo && !showMoreOverlay) {
            // Dark scrim circle
            View scrim = new View(ctx);
            int sz = dp(36, d);
            FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(sz, sz);
            scrimLp.gravity = Gravity.CENTER;
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0x99000000);
            scrim.setBackground(circle);
            scrim.setLayoutParams(scrimLp);
            cell.addView(scrim);

            // Play triangle
            ImageView play = new ImageView(ctx);
            int iconSz = dp(20, d);
            FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(iconSz, iconSz);
            playLp.gravity = Gravity.CENTER;
            play.setLayoutParams(playLp);
            play.setImageResource(android.R.drawable.ic_media_play);
            play.setColorFilter(Color.WHITE);
            cell.addView(play);

            // Duration badge (bottom-start)
            String dur = safeStr(item.get("duration"));
            if (!dur.isEmpty()) {
                TextView tvDur = new TextView(ctx);
                FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                durLp.gravity   = Gravity.BOTTOM | Gravity.START;
                durLp.leftMargin  = dp(4, d);
                durLp.bottomMargin = dp(4, d);
                tvDur.setLayoutParams(durLp);
                tvDur.setText(dur);
                tvDur.setTextSize(10f);
                tvDur.setTextColor(Color.WHITE);
                tvDur.setTypeface(null, Typeface.BOLD);
                tvDur.setPadding(dp(3, d), dp(1, d), dp(3, d), dp(1, d));
                GradientDrawable durBg = new GradientDrawable();
                durBg.setColor(0x99000000);
                durBg.setCornerRadius(dp(3, d));
                tvDur.setBackground(durBg);
                cell.addView(tvDur);
            }
        }

        // "+N" overlay (semi-transparent dark + text)
        if (showMoreOverlay && remaining > 0) {
            View dim = new View(ctx);
            dim.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            dim.setBackgroundColor(0xBB000000);
            cell.addView(dim);

            TextView tvMore = new TextView(ctx);
            FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            tvLp.gravity = Gravity.CENTER;
            tvMore.setLayoutParams(tvLp);
            tvMore.setText("+" + remaining);
            tvMore.setTextColor(Color.WHITE);
            tvMore.setTextSize(22f);
            tvMore.setTypeface(null, Typeface.BOLD);
            cell.addView(tvMore);
        }

        // Click → open MediaViewerActivity in swipeable gallery mode,
        // starting on the tapped image/video, with left/right swipe
        // across every item in this group (WhatsApp/Instagram-style).
        final int tapIndex = index;
        cell.setOnClickListener(v -> {
            if (url.isEmpty()) return;
            try {
                Intent intent = new Intent();
                intent.setClassName(ctx, "com.callx.app.activities.MediaViewerActivity");
                intent.putExtra("mediaItemsJson",
                        com.callx.app.utils.MediaItemsJsonUtil.mediaItemsToJson(allItems));
                intent.putExtra("startIndex", tapIndex);
                // Kept for backward compatibility with any code path that
                // still reads single-item extras before gallery mode kicks in.
                intent.putExtra("url",      url);
                intent.putExtra("type",     isVideo ? "video" : "image");
                intent.putExtra("thumbUrl", !thumbUrl.isEmpty() ? thumbUrl : url);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Exception ignored) {}
        });

        return cell;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static LinearLayout makeHRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void applyRoundedBg(View v, int radiusDp, float d) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(radiusDp * d);
        v.setBackground(bg);
    }

    private static int dp(int dp, float density) {
        return Math.round(dp * density);
    }

    private static String safeStr(Object o) {
        return (o instanceof String) ? (String) o : "";
    }
}
