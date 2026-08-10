package com.callx.app.feed;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.ImageView;

import androidx.core.widget.NestedScrollView;

import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.List;

/**
 * HomeFeedWindowManager — manual view-virtualization layer for the Home tab's
 * NestedScrollView + LinearLayout feed (see HomeFragment).
 *
 * A NestedScrollView keeps every child view permanently inflated and
 * attached — unlike RecyclerView, nothing is ever recycled. Home's feed
 * grows without a hard end (infinite-scroll pagination keeps appending
 * pages), so every avatar/thumbnail bitmap ever decoded stays resident for
 * the life of the fragment. That steadily climbing bitmap footprint — and
 * the GC pauses it causes — is the dominant source of scroll stutter in a
 * long Home session, and it gets worse the longer the user scrolls.
 *
 * A full RecyclerView migration is the textbook fix, but rewiring every
 * post card's several click listeners (like / comment / repost / save /
 * mute / follow / collab / slideshow / report / …) around ViewHolder
 * recycling is a large, high-risk rewrite of a screen that was built as
 * one-permanent-view-per-post. This class gets the two dominant wins of
 * RecyclerView-style recycling — bounded bitmap memory, and no image-decode
 * work competing with the scroll animation for frame time — without
 * touching any of that per-card logic:
 *
 *  1. Off-screen bitmap unloading. Cards more than {@link #WINDOW_SCREENS}
 *     screens away from the viewport have their avatar/thumbnail Glide
 *     loads cleared and swapped for a flat placeholder color, releasing the
 *     decoded bitmap. Cards scrolling back into range are reloaded from the
 *     same URL — Glide's own memory/disk cache makes that reload cheap in
 *     the common case, not a fresh network fetch.
 *  2. Fling-aware request pausing. All in-flight/queued Glide loads for the
 *     fragment are paused the instant the user starts scrolling and resumed
 *     once the scroll settles, so decode work never competes with the
 *     scroll animation for frame time — the same class of fix as
 *     CommunityScrollOptimizer's hardware-layer toggle, applied to the
 *     image pipeline instead of the draw pipeline.
 *
 * Wiring (see HomeFragment):
 *  - construct once, after bindViews();
 *  - registerCard() at the end of addFeedPostCard(), right before the card
 *    is added to containerFeed;
 *  - onScrollStarted() on every scroll delta, onScrollSettled() from the
 *    existing scroll-settle timer;
 *  - reset() anywhere containerFeed.removeAllViews() is called.
 */
public final class HomeFeedWindowManager {

    /** How many screen-heights above/below the viewport a card's media stays loaded. */
    private static final int WINDOW_SCREENS = 2;

    /** Cheap decode options reused for reloaded thumbnails — mirrors
     *  HomeFragment.FEED_IMAGE_OPTS (RGB_565, no cross-fade). */
    private static final RequestOptions THUMB_RELOAD_OPTS = new RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .dontAnimate();

    private static final int PLACEHOLDER_COLOR = 0xFF1A1A1A;

    /** One entry per rendered feed card. */
    private static final class Entry {
        View      rootView;
        ImageView avatarView;
        String    avatarUrl;
        ImageView thumbView;
        String    thumbUrl;
        /** Cards start loaded — registerCard() is called right after the
         *  card's own Glide loads were already dispatched. */
        boolean   mediaLoaded = true;
    }

    private final NestedScrollView scrollView;
    private final RequestManager   glide;
    private final List<Entry>      entries = new ArrayList<>();
    private boolean requestsPaused = false;

    public HomeFeedWindowManager(NestedScrollView scrollView, RequestManager glide) {
        this.scrollView = scrollView;
        this.glide = glide;
    }

    /** Call once per card, right after its avatar/thumbnail Glide loads are
     *  dispatched (any of the image params may be null — skipped safely). */
    public void registerCard(View rootView, ImageView avatarView, String avatarUrl,
                              ImageView thumbView, String thumbUrl) {
        if (rootView == null) return;
        Entry e = new Entry();
        e.rootView   = rootView;
        e.avatarView = avatarView;
        e.avatarUrl  = avatarUrl;
        e.thumbView  = thumbView;
        e.thumbUrl   = thumbUrl;
        entries.add(e);
    }

    /** Drop all tracked cards. Call right after containerFeed.removeAllViews()
     *  (pull-to-refresh, Following↔For You switch). Safe to call even if
     *  requests are mid-pause. */
    public void reset() {
        entries.clear();
        resumeRequests();
    }

    // ── Fling-aware request pausing ─────────────────────────────────────────

    /** Call from the scroll listener on every scroll delta. */
    public void onScrollStarted() {
        if (!requestsPaused) {
            requestsPaused = true;
            glide.pauseRequests();
        }
    }

    /** Call once the scroll-settle timer fires (scrolling has stopped). */
    public void onScrollSettled() {
        resumeRequests();
        updateWindow();
    }

    private void resumeRequests() {
        if (requestsPaused) {
            requestsPaused = false;
            glide.resumeRequests();
        }
    }

    // ── Off-screen media windowing ──────────────────────────────────────────

    /** Re-scans tracked cards and loads/unloads media based on distance from
     *  the viewport. Only touches views whose loaded state actually flips —
     *  a no-op scan over N cards is just N getLocationOnScreen() calls. */
    private void updateWindow() {
        if (entries.isEmpty()) return;
        View anyRoot = null;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).rootView != null) { anyRoot = entries.get(i).rootView; break; }
        }
        if (anyRoot == null || anyRoot.getResources() == null) return;

        // Absolute screen coordinates (via getLocationOnScreen) match the
        // exact technique HomeFragment.playMostVisibleCard() already uses
        // for this same NestedScrollView-nested layout, so it stays correct
        // regardless of how many LinearLayouts sit between a card and the
        // scroll container.
        int screenH   = anyRoot.getResources().getDisplayMetrics().heightPixels;
        int windowPx  = screenH * WINDOW_SCREENS;
        int[] loc     = new int[2];

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.rootView == null || e.rootView.getHeight() == 0 || !e.rootView.isAttachedToWindow()) continue;
            e.rootView.getLocationOnScreen(loc);
            int top    = loc[1];
            int bottom = top + e.rootView.getHeight();
            boolean inWindow = bottom >= -windowPx && top <= screenH + windowPx;
            if (inWindow && !e.mediaLoaded) {
                loadEntryMedia(e);
            } else if (!inWindow && e.mediaLoaded) {
                unloadEntryMedia(e);
            }
        }
    }

    private void unloadEntryMedia(Entry e) {
        e.mediaLoaded = false;
        if (e.avatarView != null) {
            glide.clear(e.avatarView);
            e.avatarView.setImageDrawable(new ColorDrawable(PLACEHOLDER_COLOR));
        }
        // Never unload a thumbnail that's currently hidden for a reason
        // other than windowing (e.g. faded out post-autoplay, or GONE for a
        // photo-slideshow card) — only touch it while it's actually shown.
        if (e.thumbView != null && e.thumbView.getVisibility() == View.VISIBLE) {
            glide.clear(e.thumbView);
            e.thumbView.setImageDrawable(new ColorDrawable(PLACEHOLDER_COLOR));
        }
    }

    private void loadEntryMedia(Entry e) {
        e.mediaLoaded = true;
        if (e.avatarView != null && e.avatarUrl != null && !e.avatarUrl.isEmpty()) {
            glide.load(e.avatarUrl)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(e.avatarView);
        }
        if (e.thumbView != null && e.thumbUrl != null && !e.thumbUrl.isEmpty()
                && e.thumbView.getVisibility() == View.VISIBLE) {
            glide.load(e.thumbUrl)
                    .apply(THUMB_RELOAD_OPTS)
                    .override(720, 1280)
                    .into(e.thumbView);
        }
    }
}
