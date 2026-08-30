package com.callx.app.followers;

import android.content.Context;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * AvatarScrollPrefetchHelper — wires {@link FollowAvatarBinder}'s
 * velocity-based prefetch (fast fling → skip, slow/deliberate scroll →
 * warm several rows ahead — see FollowAvatarBinder#prefetch and
 * AvatarPrefetcher for the canonical version of this logic) into any
 * plain vertical avatar RecyclerView.
 *
 * Added so every screen that was previously flagged as "sirf plain
 * Glide.with().load(), no binder, no pipeline" — ReelTagPeopleActivity,
 * ReelNotificationsActivity, ReelCloseFriendsActivity,
 * MutualFollowersActivity, CollaboratorsBottomSheet — gets the exact same
 * fast/slow scroll behavior the reels owner-avatar strip already has,
 * without re-deriving the velocity math (px/ms since the last onScrolled)
 * separately in each activity.
 */
public final class AvatarScrollPrefetchHelper {

    private AvatarScrollPrefetchHelper() {}

    public static void attach(RecyclerView rv, LinearLayoutManager lm,
                               FollowAvatarBinder.AvatarSource source) {
        if (rv == null || lm == null || source == null) return;
        Context appCtx = rv.getContext().getApplicationContext();

        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            long lastTimeMs = 0L;

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible < 0) return;

                long now = System.currentTimeMillis();
                float velocity = 0f; // treated as DEPTH_DEFAULT (see FollowAvatarBinder) on the very first callback
                if (lastTimeMs != 0L) {
                    long dt = Math.max(1L, now - lastTimeMs);
                    velocity = Math.abs(dy) / (float) dt; // px/ms
                }
                lastTimeMs = now;

                FollowAvatarBinder.prefetch(appCtx, source, lastVisible + 1, velocity);
            }
        });
    }
}
