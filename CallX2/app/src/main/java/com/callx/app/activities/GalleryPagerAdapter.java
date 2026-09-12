package com.callx.app.activities;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.Downsampler;
import com.bumptech.glide.request.RequestOptions;
import com.github.chrisbanes.photoview.PhotoView;
import com.callx.app.utils.HighResImageDecoder;
import com.callx.app.utils.MediaCache;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs the ViewPager2 in MediaViewerActivity for grouped/multi-media
 * messages — one page per image/video, swipe left/right to move between
 * them (mirrors WhatsApp / Instagram's grouped-media viewer).
 *
 * Each page lazily builds its own PhotoView (image) or ExoPlayer+PlayerView
 * (video). Video players are created on bind and released on recycle /
 * page-away so only the currently-visible video keeps decoding.
 */
public class GalleryPagerAdapter extends RecyclerView.Adapter<GalleryPagerAdapter.PageVH> {

    // PERF (memory/decode speed): full-screen viewer photos don't need
    // alpha, and RGB_565 halves per-pixel memory (2 bytes vs 4) versus the
    // ARGB_8888 default, with a faster decode since there's a quarter as
    // much data to write. Any very-low-color-depth banding this can cause
    // is imperceptible on real photos at full-screen viewing distance, and
    // this is exactly the format WhatsApp/Instagram viewers use for the
    // same reason. Shared instance — RequestOptions is immutable-safe to
    // reuse across every Glide call below.
    //
    // PERF (hardware bitmaps, one step past RGB_565): ALLOW_HARDWARE_CONFIG
    // lets Glide hand back an ARGB_8888/HARDWARE Bitmap backed directly by
    // GPU memory (API 26+) instead of a Java-heap Bitmap — the decoded
    // pixels never cross into normal heap at all, and drawing is a
    // zero-copy GPU blit instead of a CPU upload-then-draw each frame.
    // Glide already restricts this to safe cases on its own (falls back to
    // RGB_565 automatically below API 26, or wherever a transformation
    // needs pixel-level access), so it's safe to request unconditionally
    // here — nothing in this adapter's Glide calls needs software pixel
    // access to the final Bitmap.
    private static final RequestOptions RGB_565 =
            RequestOptions.formatOf(DecodeFormat.PREFER_RGB_565)
                    .set(Downsampler.ALLOW_HARDWARE_CONFIG, true);

    // PERF (region decoding): single background thread for HighResImageDecoder
    // work. Oversized images are rare, so one thread is plenty and keeps this
    // off Glide's own executors/MediaCache's download pool entirely.
    private static final ExecutorService REGION_DECODE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final android.os.Handler MAIN_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());

    public interface TapListener { void onTap(); }
    /** #1 fix — long-press a page to enter multi-select mode (forward/delete/star). */
    public interface LongPressListener { void onLongPress(int position); }
    /** Fired when the user taps a page's selection checkbox while already in select mode. */
    public interface SelectionToggleListener { void onToggle(int position); }

    private final List<Map<String, Object>> items;
    private final TapListener tapListener;
    private LongPressListener longPressListener;
    private SelectionToggleListener selectionToggleListener;

    private boolean selectMode = false;
    private final java.util.Set<Integer> selectedPositions = new java.util.HashSet<>();

    // PERF (priority tuning): which page is currently on-screen, set by
    // MediaViewerActivity. Drives Glide request priority in bindImage() —
    // previously every page (current AND the neighbor kept alive by
    // offscreenPageLimit=1 / prefetch) queued its image load at the same
    // default priority, so a neighbor's request could contend with and
    // delay the actually-visible page's own load on Glide's shared
    // network/decode executors.
    private int activePosition = RecyclerView.NO_POSITION;

    /** Called by MediaViewerActivity whenever the visible page changes. */
    public void setActivePosition(int position) {
        activePosition = position;
    }

    public GalleryPagerAdapter(List<Map<String, Object>> items, TapListener tapListener) {
        this.items = items;
        this.tapListener = tapListener;
    }

    public void setLongPressListener(LongPressListener l) { this.longPressListener = l; }
    public void setSelectionToggleListener(SelectionToggleListener l) { this.selectionToggleListener = l; }

    /** #1 — enables/disables the checkbox overlay + tap-to-toggle behavior. */
    public void setSelectMode(boolean enabled) {
        if (selectMode == enabled) return;
        selectMode = enabled;
        if (!enabled) selectedPositions.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectMode() { return selectMode; }

    public void toggleSelected(int position) {
        if (selectedPositions.contains(position)) selectedPositions.remove(position);
        else selectedPositions.add(position);
        notifyItemChanged(position);
    }

    public java.util.List<Integer> getSelectedPositions() {
        return new java.util.ArrayList<>(selectedPositions);
    }

    public int getSelectedCount() { return selectedPositions.size(); }

    @NonNull @Override
    public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();

        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PhotoView photoView = new PhotoView(ctx);
        photoView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        photoView.setScaleType(PhotoView.ScaleType.FIT_CENTER);
        root.addView(photoView);

        PlayerView playerView = new PlayerView(ctx);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        playerView.setVisibility(View.GONE);
        root.addView(playerView);

        ProgressBar spinner = new ProgressBar(ctx);
        FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spinnerLp.gravity = android.view.Gravity.CENTER;
        spinner.setLayoutParams(spinnerLp);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        spinner.setVisibility(View.GONE);
        root.addView(spinner);

        // #1 — selection checkbox (top-right), shown only in select mode
        android.widget.CheckBox checkbox = new android.widget.CheckBox(ctx);
        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        int cbMargin = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        cbLp.setMargins(0, cbMargin, cbMargin, 0);
        checkbox.setLayoutParams(cbLp);
        checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        checkbox.setVisibility(View.GONE);
        checkbox.setClickable(false); // tap handled by root so the whole page toggles, not just the tiny box
        root.addView(checkbox);

        // #2 — per-item caption overlay (bottom), shown only when this item
        // has its own caption distinct from the group-level caption.
        android.widget.TextView tvCaption = new android.widget.TextView(ctx);
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.gravity = android.view.Gravity.BOTTOM;
        capLp.setMargins(0, 0, 0, (int) (64 * ctx.getResources().getDisplayMetrics().density));
        tvCaption.setLayoutParams(capLp);
        tvCaption.setTextColor(0xFFFFFFFF);
        tvCaption.setTextSize(15f);
        tvCaption.setPadding(
                (int) (16 * ctx.getResources().getDisplayMetrics().density), (int) (8 * ctx.getResources().getDisplayMetrics().density),
                (int) (16 * ctx.getResources().getDisplayMetrics().density), (int) (8 * ctx.getResources().getDisplayMetrics().density));
        tvCaption.setBackgroundColor(0x66000000);
        tvCaption.setVisibility(View.GONE);
        root.addView(tvCaption);

        return new PageVH(root, photoView, playerView, spinner, checkbox, tvCaption);
    }

    @Override
    public void onBindViewHolder(@NonNull PageVH h, int position) {
        Map<String, Object> item = items.get(position);
        String url      = safeStr(item.get("url"));
        String thumbUrl = safeStr(item.get("thumbUrl"));
        boolean isVideo = "video".equals(item.get("mediaType"));

        // #8 — accessibility content description per page
        h.root.setContentDescription((isVideo ? "Video" : "Photo") + " " + (position + 1) + " of " + items.size());

        // #1 — selection mode: tap toggles checkbox instead of the normal
        // tap-to-toggle-toolbar behavior; long-press always enters select mode.
        h.checkbox.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        h.checkbox.setChecked(selectedPositions.contains(position));

        h.root.setOnClickListener(v -> {
            if (selectMode) {
                toggleSelected(position);
                if (selectionToggleListener != null) selectionToggleListener.onToggle(position);
            } else if (tapListener != null) {
                tapListener.onTap();
            }
        });
        h.root.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(position);
            return true;
        });

        // #2 — per-item caption, falls back to hidden if this item has none
        Object captionObj = item.get("caption");
        if (captionObj instanceof String && !((String) captionObj).isEmpty()) {
            h.tvCaption.setText((String) captionObj);
            h.tvCaption.setVisibility(View.VISIBLE);
        } else {
            h.tvCaption.setVisibility(View.GONE);
        }

        if (isVideo) {
            h.photoView.setVisibility(View.GONE);
            h.playerView.setVisibility(View.VISIBLE);
            h.playerView.setOnClickListener(v -> h.root.callOnClick());
            h.playerView.setOnLongClickListener(v -> { h.root.performLongClick(); return true; });
            bindVideo(h, url);
        } else {
            h.playerView.setVisibility(View.GONE);
            h.photoView.setVisibility(View.VISIBLE);
            // PERF (priority tuning): current page loads IMMEDIATE, every
            // other bound page (neighbor kept alive by offscreenPageLimit=1)
            // loads LOW.
            Priority priority = (position == activePosition) ? Priority.IMMEDIATE : Priority.LOW;
            bindImage(h, url, thumbUrl, priority);
        }
    }

    private void bindImage(PageVH h, String fullUrl, String thumbUrl, Priority priority) {
        Context ctx = h.photoView.getContext();
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            Glide.with(ctx).load(thumbUrl)
                    .apply(RGB_565)
                    .priority(priority)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(400, 400)
                    .into(h.photoView);
            Glide.with(ctx).load(fullUrl)
                    .apply(RGB_565)
                    .priority(priority)
                    .thumbnail(Glide.with(ctx).load(thumbUrl).apply(RGB_565).priority(priority).diskCacheStrategy(DiskCacheStrategy.ALL))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(com.bumptech.glide.load.resource.drawable
                            .DrawableTransitionOptions.withCrossFade(400))
                    .into(h.photoView);
        } else {
            File cached = MediaCache.getCached(ctx, fullUrl);
            if (cached != null) {
                // PERF (region decoding): outlier-sized images (panorama/
                // high-res scan) skip Glide's normal decode entirely and go
                // through BitmapRegionDecoder instead — see
                // HighResImageDecoder's class doc for why/scope.
                if (HighResImageDecoder.isOversized(cached)) {
                    decodeHighResOnBackground(h, cached, priority);
                } else {
                    Glide.with(ctx).load(cached).apply(RGB_565).priority(priority).diskCacheStrategy(DiskCacheStrategy.ALL).into(h.photoView);
                }
            } else {
                Glide.with(ctx).load(fullUrl).apply(RGB_565).priority(priority).diskCacheStrategy(DiskCacheStrategy.ALL).into(h.photoView);
                MediaCache.get(ctx, fullUrl, new MediaCache.Callback() {
                    @Override public void onReady(File file) {}
                    @Override public void onError(String reason) {}
                });
            }
        }
    }

    /**
     * Decodes an oversized local file via HighResImageDecoder off the main
     * thread, then sets the result directly on the PhotoView. Falls back to
     * the normal (RGB_565 + hardware) Glide path if region decoding fails
     * for any reason, so a weird/unsupported file never leaves the page blank.
     */
    private void decodeHighResOnBackground(PageVH h, File file, Priority priority) {
        Context ctx = h.photoView.getContext();
        int reqW = ctx.getResources().getDisplayMetrics().widthPixels;
        int reqH = ctx.getResources().getDisplayMetrics().heightPixels;
        h.spinner.setVisibility(View.VISIBLE);
        REGION_DECODE_EXECUTOR.execute(() -> {
            android.graphics.Bitmap bmp = HighResImageDecoder.decodeSafely(file, reqW, reqH);
            MAIN_HANDLER.post(() -> {
                if (h.getAdapterPosition() == RecyclerView.NO_POSITION) return; // recycled meanwhile
                h.spinner.setVisibility(View.GONE);
                if (bmp != null) {
                    h.photoView.setImageBitmap(bmp);
                } else {
                    // Region decode couldn't handle this file — let Glide try normally.
                    Glide.with(ctx).load(file).apply(RGB_565).priority(priority)
                            .diskCacheStrategy(DiskCacheStrategy.ALL).into(h.photoView);
                }
            });
        });
    }

    private void bindVideo(PageVH h, String url) {
        Context ctx = h.playerView.getContext();
        releasePlayer(h); // safety — in case of view-holder reuse without unbind

        Uri playUri;
        File cached = MediaCache.getCached(ctx, url);
        if (cached != null) {
            playUri = Uri.fromFile(cached);
        } else {
            playUri = Uri.parse(url);
            h.spinner.setVisibility(View.VISIBLE);
            MediaCache.get(ctx, url, new MediaCache.Callback() {
                @Override public void onReady(File file) { h.spinner.setVisibility(View.GONE); }
                @Override public void onError(String reason) { h.spinner.setVisibility(View.GONE); }
            });
        }

        // PERF: acquire from the shared ExoPlayerPool instead of building a
        // fresh ExoPlayer on every bind — see ExoPlayerPool class doc.
        h.player = com.callx.app.utils.ExoPlayerPool.acquire(ctx);
        h.playerView.setPlayer(h.player);
        h.player.setMediaItem(MediaItem.fromUri(playUri));
        h.player.prepare();
        // Auto-play only the currently active page — MediaViewerActivity
        // calls play()/pause() via onPageSelected so other pages stay paused.
        h.player.setPlayWhenReady(false);
        // Keep a reference so releasePlayer() can remove exactly this
        // listener before the player goes back to the pool — a reused
        // pooled player must not carry a previous page's listener forward
        // (would fire spinner/state callbacks against a recycled holder).
        h.playerListener = new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) h.spinner.setVisibility(View.GONE);
            }
        };
        h.player.addListener(h.playerListener);
    }

    /** Called by the activity when a video page becomes the active/visible one. */
    public void setActive(PageVH h, boolean active) {
        if (h.player == null) return;
        h.player.setPlayWhenReady(active);
    }

    public void releasePlayer(PageVH h) {
        if (h.player != null) {
            if (h.playerListener != null) {
                h.player.removeListener(h.playerListener);
                h.playerListener = null;
            }
            h.playerView.setPlayer(null);
            // PERF: return to the shared pool instead of releasing native
            // resources outright — see ExoPlayerPool class doc.
            com.callx.app.utils.ExoPlayerPool.release(h.player);
            h.player = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull PageVH h) {
        super.onViewRecycled(h);
        releasePlayer(h);
        // PERF (verify release timing): once a page is recycled (i.e. it's
        // gone past offscreenPageLimit=1's window), cancel any Glide
        // request still in flight for it — otherwise a background
        // thumbnail/full-image download for a page the user can no longer
        // reach keeps running and competing for the same network/decode
        // executors as the page actually on screen.
        try {
            Glide.with(h.photoView.getContext()).clear(h.photoView);
        } catch (Exception ignored) {
            // Context may already be torn down (activity finishing) — safe to no-op.
        }
    }

    @Override public int getItemCount() { return items == null ? 0 : items.size(); }

    private static String safeStr(Object o) { return (o instanceof String) ? (String) o : ""; }

    static class PageVH extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final PhotoView photoView;
        final PlayerView playerView;
        final ProgressBar spinner;
        final android.widget.CheckBox checkbox;
        final android.widget.TextView tvCaption;
        ExoPlayer player;
        Player.Listener playerListener;

        PageVH(FrameLayout root, PhotoView photoView, PlayerView playerView, ProgressBar spinner,
               android.widget.CheckBox checkbox, android.widget.TextView tvCaption) {
            super(root);
            this.root = root;
            this.photoView = photoView;
            this.playerView = playerView;
            this.spinner = spinner;
            this.checkbox = checkbox;
            this.tvCaption = tvCaption;
        }
    }
}
