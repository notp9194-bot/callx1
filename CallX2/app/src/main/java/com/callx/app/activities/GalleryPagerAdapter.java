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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;
import com.callx.app.utils.MediaCache;

import java.io.File;
import java.util.List;
import java.util.Map;

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
            bindImage(h, url, thumbUrl);
        }
    }

    private void bindImage(PageVH h, String fullUrl, String thumbUrl) {
        Context ctx = h.photoView.getContext();
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            Glide.with(ctx).load(thumbUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(400, 400)
                    .into(h.photoView);
            Glide.with(ctx).load(fullUrl)
                    .thumbnail(Glide.with(ctx).load(thumbUrl).diskCacheStrategy(DiskCacheStrategy.ALL))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(com.bumptech.glide.load.resource.drawable
                            .DrawableTransitionOptions.withCrossFade(400))
                    .into(h.photoView);
        } else {
            File cached = MediaCache.getCached(ctx, fullUrl);
            if (cached != null) {
                Glide.with(ctx).load(cached).diskCacheStrategy(DiskCacheStrategy.ALL).into(h.photoView);
            } else {
                Glide.with(ctx).load(fullUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(h.photoView);
                MediaCache.get(ctx, fullUrl, new MediaCache.Callback() {
                    @Override public void onReady(File file) {}
                    @Override public void onError(String reason) {}
                });
            }
        }
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

        h.player = new ExoPlayer.Builder(ctx).build();
        h.playerView.setPlayer(h.player);
        h.player.setMediaItem(MediaItem.fromUri(playUri));
        h.player.prepare();
        // Auto-play only the currently active page — MediaViewerActivity
        // calls play()/pause() via onPageSelected so other pages stay paused.
        h.player.setPlayWhenReady(false);
        h.player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) h.spinner.setVisibility(View.GONE);
            }
        });
    }

    /** Called by the activity when a video page becomes the active/visible one. */
    public void setActive(PageVH h, boolean active) {
        if (h.player == null) return;
        h.player.setPlayWhenReady(active);
    }

    public void releasePlayer(PageVH h) {
        if (h.player != null) {
            h.player.release();
            h.player = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull PageVH h) {
        super.onViewRecycled(h);
        releasePlayer(h);
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
