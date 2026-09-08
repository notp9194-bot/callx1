package com.callx.app.utils;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;

import com.callx.app.cache.StatusCacheManager;

/**
 * StoryRingApplier — the SAME 3-state avatar ring (gradient = unseen story,
 * flat gray = all-seen, hidden = no active story) that HomeFragment's feed
 * post owner-avatar (addFeedPostCard#ivPostStoryRing), the Stories tray
 * (bindStoryTileViews), ReelUiController, PostsFeedActivity,
 * UserReelsActivity, and ReelCommentsAdapter already use — pulled out into
 * one reusable call so every OTHER avatar-list screen in feature-reels
 * (Followers/Following/Mutual, Discover People, Suggested accounts, Close
 * Friends, Collab Inbox, Notifications) can show the exact same ring
 * instead of a plain flat avatar, without re-deriving the
 * StatusCacheManager/StoryRingGradientDrawable/circle_status_seen wiring
 * per screen.
 *
 * Usage: give each row layout one extra ImageView sized a few dp bigger
 * than the avatar (same "ring sits behind/around the avatar" shape as
 * ivPostStoryRing), then call {@link #apply} from the adapter's bind step
 * with that uid's row.
 */
public final class StoryRingApplier {

    private StoryRingApplier() {}

    /**
     * @param ringView the overlay ring ImageView for this row (sized bigger
     *                  than the avatar it surrounds). No-op if null.
     * @param uid       the row's user id. Ring is hidden if null/empty.
     */
    public static void apply(Context ctx, ImageView ringView, String uid) {
        if (ringView == null || ctx == null) return;
        if (uid == null || uid.isEmpty()) {
            ringView.setVisibility(View.GONE);
            return;
        }
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasUnseen = scm.hasUnseen(uid);
        boolean hasAny    = scm.hasStatus(uid);
        if (hasUnseen) {
            ringView.setImageDrawable(null);
            ringView.setBackground(StoryRingGradientDrawable.withStrokeDp(
                    2f, ctx.getResources().getDisplayMetrics().density));
            ringView.setVisibility(View.VISIBLE);
        } else if (hasAny) {
            ringView.setBackground(null);
            ringView.setImageResource(com.callx.app.core.R.drawable.circle_status_seen);
            ringView.setVisibility(View.VISIBLE);
        } else {
            ringView.setBackground(null);
            ringView.setImageDrawable(null);
            ringView.setVisibility(View.GONE);
        }
    }

    /**
     * Same visual apply() as above, PLUS wires the ring's tap to open
     * StatusViewerActivity (with ownerUid/ownerName extras, exactly like
     * ReelCommentsAdapter's ring already did) so every screen that reuses
     * this applier gets the same "tap ring → story viewer opens → view
     * gets counted" behavior instead of just the visual ring with no
     * click. Safe even when there's no active story: apply() already sets
     * ringView to GONE in that case, so the listener never actually fires.
     *
     * @param uid  the row's user id — same value passed to apply().
     * @param name display name for the viewer header (nullable — StatusViewerActivity
     *             treats a missing name as "").
     */
    public static void applyWithClick(Context ctx, ImageView ringView, String uid, String name) {
        apply(ctx, ringView, uid);
        if (ringView == null) return;
        ringView.setOnClickListener(v -> openStatusViewer(v.getContext(), uid, name));
    }

    /** Overload for call sites that don't have a display name handy. */
    public static void applyWithClick(Context ctx, ImageView ringView, String uid) {
        applyWithClick(ctx, ringView, uid, null);
    }

    /**
     * Opens StatusViewerActivity via Class.forName so feature-reels doesn't
     * need a compile dependency on feature-status — same reflection pattern
     * ReelCommentsAdapter/HomeFragment/UserReelsActivity already use. A
     * no-op (well, no navigation) if the status module isn't present or uid
     * is empty.
     */
    public static void openStatusViewer(Context ctx, String uid, String name) {
        if (ctx == null || uid == null || uid.isEmpty()) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
            Intent i = new Intent(ctx, cls);
            i.putExtra("ownerUid",  uid);
            i.putExtra("ownerName", name != null ? name : "");
            ctx.startActivity(i);
        } catch (ClassNotFoundException ignored) {
            // Status module not present in this build — nothing sensible to fall
            // back to generically here (unlike per-screen callers that know their
            // own profile-activity fallback), so just no-op rather than crash.
        }
    }
}
