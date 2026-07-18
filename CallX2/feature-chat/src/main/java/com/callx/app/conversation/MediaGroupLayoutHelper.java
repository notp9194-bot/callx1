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
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;
import java.util.Map;

/**
 * Full WhatsApp-style media group layout.
 *
 * Layout rules:
 *  1 item   → single full-width image (240×200dp)
 *  2 items  → two equal side-by-side squares
 *  3 items  → 1 tall image on top + 2 side-by-side below
 *  4 items  → 2×2 equal grid
 *  5-9 items→ 3×3 grid (denser preview instead of hiding everything behind
 *             a 2×2 + "+N"); last visible cell shows "+N" overlay if total > 9
 *  10+items → 3×3 grid, last cell "+N" overlay
 *
 * Rounded corners: 12dp on outer group container.
 * Video cells: play-button circle overlay + duration badge (bottom-start).
 * Tap  → opens MediaViewerActivity with url + type.
 * Long-tap → propagated to parent (action sheet).
 */
public class MediaGroupLayoutHelper {

    // Max thumbnails shown before "+N" overflow, for the 2×2 layout (<=4 items)
    private static final int MAX_VISIBLE_2x2 = 4;
    // Max thumbnails shown before "+N" overflow, for the 3×3 layout (5+ items)
    private static final int MAX_VISIBLE_3x3 = 9;

    // Cell size constants (dp)
    private static final int SINGLE_W   = 240;
    private static final int SINGLE_H   = 200;
    private static final int PAIR_CELL  = 118;
    private static final int THREE_TOP_W= 240;
    private static final int THREE_TOP_H= 140;
    private static final int THREE_BOT  = 116;
    private static final int GRID_CELL  = 118;   // used for 2×2
    private static final int GRID3_CELL = 78;    // used for 3×3 (denser grid)
    private static final int GAP        = 2;
    private static final int CORNER_R   = 12;

    // ─── Public entry point ────────────────────────────────────────────────
    public static void populate(Context ctx, LinearLayout container,
                                List<Map<String, Object>> items, String caption) {
        populate(ctx, container, items, caption, null, null);
    }

    /**
     * Same as {@link #populate(Context, LinearLayout, List, String)} but
     * also stamps chatId + messageId onto the gallery intent so
     * MediaViewerActivity can hand a swipe-up "reply" request back to the
     * chat screen via {@link GalleryReplyBridge}. Used by
     * MessagePagingAdapter; pass null/null to keep the old behavior.
     *
     * NOTE: chatId/messageId are threaded through as plain method
     * parameters (not static fields) — RecyclerView binds happen for many
     * different rows in quick succession (fast scroll, multiple adapters),
     * so a static "current chat" handoff was a race condition: row A's
     * click listener could end up carrying row B's chatId/messageId if a
     * second bind happened before row A's listener fired. Each cell's
     * click listener now closes over its own local chatId/messageId.
     */
    public static void populate(Context ctx, LinearLayout container,
                                List<Map<String, Object>> items, String caption,
                                String chatId, String messageId) {
        container.removeAllViews();
        if (items == null || items.isEmpty()) return;

        float d   = ctx.getResources().getDisplayMetrics().density;
        int gapPx = dp(GAP, d);
        int total = items.size();

        // Clip the container with rounded corners
        applyRoundedBg(container, CORNER_R, d);
        container.setClipToOutline(true);
        container.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        // The actual grid/rows are built into an inner content holder so the
        // group caption (if any) can be layered ON TOP of it (WhatsApp-style
        // gradient overlay at the bottom edge) instead of being stacked as a
        // separate row underneath — that's why it previously never appeared
        // to "overlap" the media.
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        // Collects a start-download Runnable for every image cell that isn't
        // cached yet — used to wire the master "Download N photos" pill so
        // one tap kicks off every uncached item's own per-cell download.
        List<Runnable> pendingDownloads = new java.util.ArrayList<>();

        if (total == 1) {
            buildSingle(ctx, content, items, d, chatId, messageId, pendingDownloads);

        } else if (total == 2) {
            buildTwoSideBySide(ctx, content, items, d, gapPx, chatId, messageId, pendingDownloads);

        } else if (total == 3) {
            buildThree(ctx, content, items, d, gapPx, chatId, messageId, pendingDownloads);

        } else if (total == 4) {
            // 2×2 grid, fully visible, no overlay needed
            buildGrid(ctx, content, items, total, d, gapPx, 2, GRID_CELL, chatId, messageId, pendingDownloads);

        } else {
            // 5+ → denser 3×3 grid; last visible cell gets "+N" overlay
            // once there are more than MAX_VISIBLE_3x3 items.
            buildGrid(ctx, content, items, total, d, gapPx, 3, GRID3_CELL, chatId, messageId, pendingDownloads);
        }

        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(content);

        // Group caption: gradient scrim + text, pinned to the bottom edge
        // and OVERLAPPING the media grid (FrameLayout-on-top, not a sibling
        // row below it).
        if (caption != null && !caption.isEmpty()) {
            View scrim = new View(ctx);
            FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(40, d));
            scrimLp.gravity = Gravity.BOTTOM;
            GradientDrawable grad = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0xAA000000});
            scrim.setBackground(grad);
            scrim.setLayoutParams(scrimLp);
            wrapper.addView(scrim);

            TextView tvCap = new TextView(ctx);
            FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            capLp.gravity = Gravity.BOTTOM;
            capLp.leftMargin   = dp(8, d);
            capLp.rightMargin  = dp(8, d);
            capLp.bottomMargin = dp(6, d);
            tvCap.setLayoutParams(capLp);
            tvCap.setText(caption);
            tvCap.setTextSize(14f);
            tvCap.setTextColor(Color.WHITE);
            tvCap.setMaxLines(3);
            tvCap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            wrapper.addView(tvCap);
        }

        // ── MASTER "Download N photos" PILL (WhatsApp-style) ────────────
        // Shown only while at least one image in the group hasn't been
        // downloaded yet. One tap fires every individual cell's own
        // download (each shows its own icon→spinner overlay), matching
        // the per-cell system used by the single-image bubble.
        if (!pendingDownloads.isEmpty()) {
            addMasterDownloadOverlay(ctx, wrapper, pendingDownloads.size(), d, pendingDownloads);
        }

        container.addView(wrapper);
    }

    /** Builds + attaches the grid-wide "Download N photos" pill described above. */
    private static void addMasterDownloadOverlay(Context ctx, FrameLayout wrapper, int count,
                                                  float d, List<Runnable> pendingDownloads) {
        FrameLayout masterOverlay = new FrameLayout(ctx);
        masterOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        masterOverlay.setBackgroundColor(0x2E000000);

        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.CENTER;
        pill.setLayoutParams(pillLp);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x8A000000);
        pillBg.setCornerRadius(dp(24, d));
        pill.setBackground(pillBg);
        int padH = dp(14, d), padV = dp(8, d);
        pill.setPadding(padH, padV, padH, padV);
        pill.setClickable(true);
        pill.setFocusable(true);

        ImageView icon = new ImageView(ctx);
        int iconSz = dp(18, d);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSz, iconSz));
        icon.setImageResource(com.callx.app.core.R.drawable.ic_download_reel);
        pill.addView(icon);

        TextView label = new TextView(ctx);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lblLp.leftMargin = dp(8, d);
        label.setLayoutParams(lblLp);
        label.setText("Download " + count + (count == 1 ? " photo" : " photos"));
        label.setTextColor(Color.WHITE);
        label.setTextSize(13f);
        label.setTypeface(null, Typeface.BOLD);
        pill.addView(label);

        masterOverlay.addView(pill);

        masterOverlay.setOnClickListener(v -> {
            // Snapshot + run — starting a download removes nothing from
            // pendingDownloads itself, just kicks off each cell's own flow.
            for (Runnable r : pendingDownloads) r.run();
            wrapper.removeView(masterOverlay);
        });

        wrapper.addView(masterOverlay);
    }

    // ─── Layout builders ──────────────────────────────────────────────────

    /** 1 image: full-width tall card */
    private static void buildSingle(Context ctx, LinearLayout parent,
                                    List<Map<String, Object>> items, float d,
                                    String chatId, String messageId, List<Runnable> pendingDownloads) {
        FrameLayout cell = buildCell(ctx, items, 0, false, 0, d, chatId, messageId, pendingDownloads);
        int w = dp(SINGLE_W, d);
        int h = dp(SINGLE_H, d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        cell.setLayoutParams(lp);
        parent.addView(cell);
    }

    /** 2 images: side by side */
    private static void buildTwoSideBySide(Context ctx, LinearLayout parent,
                                           List<Map<String, Object>> items,
                                           float d, int gapPx,
                                           String chatId, String messageId, List<Runnable> pendingDownloads) {
        LinearLayout row = makeHRow(ctx);
        int cell = dp(PAIR_CELL, d);
        for (int i = 0; i < 2; i++) {
            FrameLayout c = buildCell(ctx, items, i, false, 0, d, chatId, messageId, pendingDownloads);
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
                                   float d, int gapPx,
                                   String chatId, String messageId, List<Runnable> pendingDownloads) {
        // Top: single wide image
        FrameLayout top = buildCell(ctx, items, 0, false, 0, d, chatId, messageId, pendingDownloads);
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
            FrameLayout c = buildCell(ctx, items, i, false, 0, d, chatId, messageId, pendingDownloads);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(bc, bc);
            if (i > 1) lp.leftMargin = gapPx;
            c.setLayoutParams(lp);
            row.addView(c);
        }
        parent.addView(row);
    }

    /**
     * N×N grid (N = 2 for exactly-4-item messages, N = 3 for 5+ item
     * messages). Last visible cell shows a "+remaining" overlay once total
     * exceeds the grid's capacity (4 for 2×2, 9 for 3×3).
     */
    private static void buildGrid(Context ctx, LinearLayout parent,
                                  List<Map<String, Object>> items,
                                  int total, float d, int gapPx,
                                  int columns, int cellDp,
                                  String chatId, String messageId, List<Runnable> pendingDownloads) {
        int maxVisible = columns * columns;
        int visible    = Math.min(total, maxVisible);
        int remaining  = total - maxVisible; // negative is fine, only used when > 0
        int gc = dp(cellDp, d);

        int idx = 0;
        while (idx < visible) {
            LinearLayout row = makeHRow(ctx);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (idx > 0) rowLp.topMargin = gapPx;
            row.setLayoutParams(rowLp);

            for (int col = 0; col < columns && idx < visible; col++, idx++) {
                boolean isLast   = (idx == visible - 1);
                boolean showMore = isLast && remaining > 0;
                FrameLayout c    = buildCell(ctx, items, idx, showMore, remaining, d, chatId, messageId, pendingDownloads);
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
                                         float d, String chatId, String messageId,
                                         List<Runnable> pendingDownloads) {
        Map<String, Object> item = allItems.get(index);
        FrameLayout cell = new FrameLayout(ctx);
        cell.setClipToOutline(true);
        // Rounded corners on each cell (inner clipping)
        GradientDrawable cellBg = new GradientDrawable();
        cellBg.setColor(Color.DKGRAY);
        cellBg.setCornerRadius(dp(4, d));
        cell.setBackground(cellBg);
        cell.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        String url      = safeStr(item.get("url"));
        String thumbUrl = safeStr(item.get("thumbUrl"));
        // WhatsApp-style: grid cells are lightweight thumbnails ONLY — never
        // fall back to the raw full-res url. If a real thumbUrl is missing
        // (thumb upload failed / legacy item without one), show a plain
        // placeholder icon instead of pulling the full file through Glide;
        // the full-res file is only ever fetched when the user opens the
        // item (gallery viewer / manual download), never for the grid.
        String mediaType = safeStr(item.get("mediaType"));
        boolean isVideo   = "video".equals(mediaType);
        boolean isAudio   = "audio".equals(mediaType);
        boolean isFile    = "file".equals(mediaType);

        String descKind = isVideo ? "Video" : isAudio ? "Audio" : isFile ? "File" : "Photo";
        cell.setContentDescription(descKind + " " + (index + 1) + " of " + allItems.size());

        if (isAudio || isFile) {
            // No thumbnail to render — show an icon + filename instead of
            // running a raw (non-image) url through Glide, which previously
            // rendered as a broken/placeholder image.
            cell.setBackgroundColor(0xFF2C2C2C);
            ImageView icon = new ImageView(ctx);
            int iconSz = dp(28, d);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSz, iconSz);
            iconLp.gravity = Gravity.CENTER;
            iconLp.bottomMargin = dp(14, d);
            icon.setLayoutParams(iconLp);
            icon.setImageResource(isAudio
                    ? android.R.drawable.ic_btn_speak_now
                    : android.R.drawable.ic_menu_save);
            icon.setColorFilter(Color.WHITE);
            cell.addView(icon);

            String label = isAudio
                    ? (safeStr(item.get("duration")).isEmpty() ? "Audio" : safeStr(item.get("duration")))
                    : safeStr(item.get("fileName"));
            if (label.isEmpty()) label = isAudio ? "Audio" : "File";
            TextView tvLabel = new TextView(ctx);
            FrameLayout.LayoutParams lblLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lblLp.gravity = Gravity.BOTTOM;
            lblLp.bottomMargin = dp(4, d);
            tvLabel.setLayoutParams(lblLp);
            tvLabel.setText(label);
            tvLabel.setTextSize(9f);
            tvLabel.setTextColor(Color.WHITE);
            tvLabel.setGravity(Gravity.CENTER);
            tvLabel.setMaxLines(1);
            tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tvLabel.setPadding(dp(2, d), 0, dp(2, d), 0);
            cell.addView(tvLabel);

        } else if (isVideo) {
            // Video cells stream from `url` directly (existing player), so
            // no manual-download gate here — just the thumbnail/placeholder.
            if (!thumbUrl.isEmpty()) {
                ImageView iv = new ImageView(ctx);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setContentDescription(null);
                Glide.with(ctx).load(thumbUrl).diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(720, 720)
                        .centerCrop().placeholder(android.R.color.darker_gray).into(iv);
                cell.addView(iv);
            } else {
                cell.setBackgroundColor(0xFF2C2C2C);
                ImageView icon = new ImageView(ctx);
                int iconSz = dp(28, d);
                FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSz, iconSz);
                iconLp.gravity = Gravity.CENTER;
                icon.setLayoutParams(iconLp);
                icon.setImageResource(android.R.drawable.ic_media_play);
                icon.setColorFilter(Color.WHITE);
                cell.addView(icon);
            }
        } else {
            // IMAGE cell — WhatsApp-style manual download, same idea as the
            // single-image bubble: grid always shows a lightweight blurred
            // thumbnail (real thumbUrl, or a derived low-res Cloudinary
            // transform if thumbUrl is missing — never the raw full url).
            // The full-res file is only fetched when the user taps this
            // cell (or the master "Download N photos" pill), and once
            // MediaCache has it locally, later rebinds load it straight
            // from disk instead of showing the gate again.
            String loadThumb = !thumbUrl.isEmpty()
                    ? thumbUrl
                    : (!url.isEmpty() ? com.callx.app.utils.CloudinaryUploader.deriveThumbUrl(url, 200) : "");

            ImageView iv = new ImageView(ctx);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setContentDescription(null);
            cell.addView(iv);

            java.io.File cachedFile = !url.isEmpty()
                    ? com.callx.app.utils.MediaCache.getCached(ctx, url) : null;

            if (cachedFile != null) {
                // Already downloaded — show the sharp local copy directly.
                Glide.with(ctx).load(cachedFile).centerCrop().override(720, 720).into(iv);
            } else {
                if (!loadThumb.isEmpty()) {
                    Glide.with(ctx).load(loadThumb).diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(720, 720)
                            .centerCrop().placeholder(android.R.color.darker_gray).into(iv);
                } else {
                    cell.setBackgroundColor(0xFF2C2C2C);
                }
                if (!url.isEmpty()) {
                    FrameLayout overlay = buildCellDownloadOverlay(ctx, d);
                    cell.addView(overlay);
                    final String cellUrl = url;
                    Runnable starter = () -> startCellDownload(ctx, cellUrl, iv, overlay);
                    overlay.setOnClickListener(v -> starter.run());
                    if (pendingDownloads != null) pendingDownloads.add(starter);
                }
            }
        }

        String itemCaption = safeStr(item.get("caption"));
        boolean hasItemCaption = !itemCaption.isEmpty() && !showMoreOverlay;

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
            play.setContentDescription("Video, tap to play");
            cell.addView(play);

            // Duration badge — bottom-start normally, but moved to top-end
            // when a per-item caption is also showing so the two don't
            // overlap each other at the bottom of the cell.
            String dur = safeStr(item.get("duration"));
            if (!dur.isEmpty()) {
                TextView tvDur = new TextView(ctx);
                FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                if (hasItemCaption) {
                    durLp.gravity    = Gravity.TOP | Gravity.END;
                    durLp.rightMargin = dp(4, d);
                    durLp.topMargin   = dp(4, d);
                } else {
                    durLp.gravity     = Gravity.BOTTOM | Gravity.START;
                    durLp.leftMargin  = dp(4, d);
                    durLp.bottomMargin = dp(4, d);
                }
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

        // Per-item caption: small gradient strip + text pinned to the
        // bottom of this individual thumbnail cell (only when not covered
        // by the "+N" overflow overlay).
        if (hasItemCaption) {
            View capScrim = new View(ctx);
            FrameLayout.LayoutParams capScrimLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(22, d));
            capScrimLp.gravity = Gravity.BOTTOM;
            GradientDrawable capGrad = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x99000000});
            capScrim.setBackground(capGrad);
            capScrim.setLayoutParams(capScrimLp);
            cell.addView(capScrim);

            TextView tvItemCap = new TextView(ctx);
            FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            capLp.gravity = Gravity.BOTTOM;
            capLp.leftMargin   = dp(4, d);
            capLp.rightMargin  = dp(4, d);
            capLp.bottomMargin = dp(2, d);
            tvItemCap.setLayoutParams(capLp);
            tvItemCap.setText(itemCaption);
            tvItemCap.setTextSize(10f);
            tvItemCap.setTextColor(Color.WHITE);
            tvItemCap.setMaxLines(1);
            tvItemCap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(tvItemCap);
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
            tvMore.setContentDescription("Plus " + remaining + " more photos and videos");
            cell.addView(tvMore);
        }

        // Click → open MediaViewerActivity in swipeable gallery mode,
        // starting on the tapped image/video, with left/right swipe
        // across every item in this group (WhatsApp/Instagram-style).
        // chatId/messageId are captured locally (method params, not a
        // static field) — see the populate() javadoc for why this matters.
        final int tapIndex = index;
        final String cbChatId    = chatId;
        final String cbMessageId = messageId;
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
                intent.putExtra("type",     isVideo ? "video" : isAudio ? "audio" : isFile ? "file" : "image");
                intent.putExtra("thumbUrl", !thumbUrl.isEmpty() ? thumbUrl : url);
                // Lets MediaViewerActivity hand a swipe-up "reply" request
                // back to this chat screen via GalleryReplyBridge. Both
                // are null when called from the legacy 4-arg populate().
                if (cbChatId != null && cbMessageId != null) {
                    intent.putExtra("chatId",    cbChatId);
                    intent.putExtra("messageId", cbMessageId);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Exception ignored) {}
        });

        return cell;
    }

    // ─── Manual download (WhatsApp-style, images in a grid) ─────────────────

    // Dedupe across rebinds — populate() rebuilds the whole grid from
    // scratch on every RecyclerView bind, so without this a quick
    // scroll-away-and-back while a download is still running would kick
    // off a second parallel download for the same url.
    private static final java.util.Set<String> sDownloadingUrls =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Small centered icon/spinner badge — the grid-cell equivalent of the
     *  single-image bubble's download pill, sized down to fit small cells. */
    private static FrameLayout buildCellDownloadOverlay(Context ctx, float d) {
        FrameLayout overlay = new FrameLayout(ctx);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(0x40000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        int badgeSz = dp(26, d);
        FrameLayout badge = new FrameLayout(ctx);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSz, badgeSz);
        badgeLp.gravity = Gravity.CENTER;
        badge.setLayoutParams(badgeLp);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(0x99000000);
        badge.setBackground(badgeBg);

        ImageView icon = new ImageView(ctx);
        int iconSz = dp(14, d);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSz, iconSz);
        iconLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconLp);
        icon.setId(android.view.View.generateViewId());
        icon.setTag("dl_icon");
        icon.setImageResource(com.callx.app.core.R.drawable.ic_download_reel);
        badge.addView(icon);

        ProgressBar spinner = new ProgressBar(ctx);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(iconSz, iconSz);
        spinLp.gravity = Gravity.CENTER;
        spinner.setLayoutParams(spinLp);
        spinner.setTag("dl_spinner");
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        spinner.setVisibility(View.GONE);
        badge.addView(spinner);

        overlay.addView(badge);
        return overlay;
    }

    /** Kicks off (or no-ops if already running) the manual download for one
     *  grid cell. Swaps the icon for a spinner, then on success hides the
     *  overlay and loads the full-res local file into `iv`. */
    private static void startCellDownload(Context ctx, String url, ImageView iv, FrameLayout overlay) {
        if (url == null || url.isEmpty() || sDownloadingUrls.contains(url)) return;
        sDownloadingUrls.add(url);

        ImageView icon = overlay.findViewWithTag("dl_icon");
        ProgressBar spinner = overlay.findViewWithTag("dl_spinner");
        if (icon != null) icon.setVisibility(View.GONE);
        if (spinner != null) spinner.setVisibility(View.VISIBLE);
        overlay.setOnClickListener(null); // ignore taps while downloading

        com.callx.app.utils.MediaCache.getWithProgress(ctx, url,
                new com.callx.app.utils.MediaCache.ProgressCallback() {
            @Override public void onProgress(int percent) { /* grid cells are too small for a % label */ }
            @Override public void onReady(java.io.File file) {
                sDownloadingUrls.remove(url);
                if (overlay.getParent() == null) return; // cell recycled/removed
                ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
                Glide.with(ctx).load(file).centerCrop().override(720, 720).into(iv);
            }
            @Override public void onError(String reason) {
                sDownloadingUrls.remove(url);
                if (icon != null) icon.setVisibility(View.VISIBLE);
                if (spinner != null) spinner.setVisibility(View.GONE);
                overlay.setOnClickListener(v -> startCellDownload(ctx, url, iv, overlay));
            }
        });
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
