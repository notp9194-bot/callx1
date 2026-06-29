package com.callx.app.conversation;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
 * WhatsApp-style media group layout builder.
 * Populates a LinearLayout with a 2-column grid of image/video thumbnails.
 * Max 4 visible; remaining count shown as "+N" overlay on last cell.
 */
public class MediaGroupLayoutHelper {

    private static final int MAX_VISIBLE = 4;

    public static void populate(Context ctx, LinearLayout container,
                                List<Map<String, Object>> items, String caption) {
        container.removeAllViews();
        if (items == null || items.isEmpty()) return;

        float density = ctx.getResources().getDisplayMetrics().density;
        int thumbPx   = (int)(120 * density);
        int gapPx     = (int)(2  * density);

        int total   = items.size();
        int visible = Math.min(total, MAX_VISIBLE);

        int idx = 0;
        while (idx < visible) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (idx > 0) rowLp.topMargin = gapPx;
            row.setLayoutParams(rowLp);

            for (int col = 0; col < 2 && idx < visible; col++, idx++) {
                final Map<String, Object> item = items.get(idx);
                final boolean lastAndMore = (idx == visible - 1) && (total > visible);
                final int remaining = total - visible;

                FrameLayout cell = buildCell(ctx, item, lastAndMore, remaining, thumbPx, density);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(thumbPx, thumbPx);
                if (col > 0) cellLp.leftMargin = gapPx;
                cell.setLayoutParams(cellLp);

                final String url       = (String) item.get("url");
                final String mediaType = (String) item.get("mediaType");
                final String thumbUrl  = (String) item.get("thumbUrl");

                cell.setOnClickListener(v -> {
                    if (url == null || url.isEmpty()) return;
                    try {
                        Intent intent = new Intent();
                        intent.setClassName(ctx, "com.callx.app.activities.MediaViewerActivity");
                        intent.putExtra("url",      url);
                        intent.putExtra("type",     mediaType != null ? mediaType : "image");
                        intent.putExtra("thumbUrl", thumbUrl != null ? thumbUrl : url);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(intent);
                    } catch (Exception ignored) {}
                });

                row.addView(cell);
            }
            container.addView(row);
        }

        if (caption != null && !caption.isEmpty()) {
            TextView tvCap = new TextView(ctx);
            LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            capLp.topMargin = (int)(4 * density);
            tvCap.setLayoutParams(capLp);
            tvCap.setText(caption);
            tvCap.setTextSize(14f);
            tvCap.setTextColor(0xFFEEEEEE);
            container.addView(tvCap);
        }
    }

    private static FrameLayout buildCell(Context ctx, Map<String, Object> item,
                                         boolean showMoreOverlay, int remaining,
                                         int sizePx, float density) {
        FrameLayout cell = new FrameLayout(ctx);
        cell.setClipToOutline(true);
        cell.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

        String url      = (String) item.get("url");
        String thumbUrl = (String) item.get("thumbUrl");
        String loadUrl  = (thumbUrl != null && !thumbUrl.isEmpty()) ? thumbUrl : url;
        if (loadUrl != null && !loadUrl.isEmpty()) {
            Glide.with(ctx)
                    .load(loadUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(iv);
        }
        cell.addView(iv);

        boolean isVideo = "video".equals(item.get("mediaType"));
        if (isVideo && !showMoreOverlay) {
            int iconSz = (int)(32 * density);
            ImageView play = new ImageView(ctx);
            FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(iconSz, iconSz);
            playLp.gravity = Gravity.CENTER;
            play.setLayoutParams(playLp);
            play.setImageResource(android.R.drawable.ic_media_play);
            play.setColorFilter(Color.WHITE);
            cell.addView(play);
        }

        if (showMoreOverlay && remaining > 0) {
            View dimOverlay = new View(ctx);
            dimOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            dimOverlay.setBackgroundColor(0xBB000000);
            cell.addView(dimOverlay);

            TextView tvMore = new TextView(ctx);
            FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            tvLp.gravity = Gravity.CENTER;
            tvMore.setLayoutParams(tvLp);
            tvMore.setText("+" + remaining);
            tvMore.setTextColor(Color.WHITE);
            tvMore.setTextSize(20f);
            tvMore.setTypeface(null, Typeface.BOLD);
            cell.addView(tvMore);
        }

        return cell;
    }
}
