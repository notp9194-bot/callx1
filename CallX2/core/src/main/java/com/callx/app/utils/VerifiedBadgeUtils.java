package com.callx.app.utils;

import android.view.View;
import android.widget.ImageView;

import com.callx.app.cache.VerifiedStatusCache;

/**
 * Central place to show/hide the verified badge (@drawable/ic_verified_pink)
 * next to a username anywhere in the app. Any module can call this instead of
 * each screen wiring its own visibility check — new list/screen only needs
 * one call here.
 *
 * Usage in a layout, next to the name TextView:
 *   <ImageView android:id="@+id/iv_verified"
 *       android:layout_width="14dp" android:layout_height="14dp"
 *       android:src="@drawable/ic_verified_pink"
 *       android:visibility="gone"
 *       android:contentDescription="Verified"/>
 *
 * Usage when you already have the boolean (e.g. a snapshot/model that
 * already carries isVerified):
 *   VerifiedBadgeUtils.bind(holder.ivVerified, user.isVerified());
 *
 * Usage when you only have a uid (most list adapters — chat list, comments,
 * search results, calls tab, etc.) — resolves via the cached lookup so
 * scrolling doesn't repeatedly hit Firebase:
 *   VerifiedBadgeUtils.bindForUid(holder.ivVerified, uid);
 */
public class VerifiedBadgeUtils {

    private VerifiedBadgeUtils() {}

    /** Shows the badge if isVerified is true, hides it otherwise (null-safe on both args). */
    public static void bind(ImageView badgeView, Boolean isVerified) {
        if (badgeView == null) return;
        badgeView.setVisibility(Boolean.TRUE.equals(isVerified) ? View.VISIBLE : View.GONE);
    }

    /**
     * Resolves isVerified for uid (cached after first lookup) and binds the badge.
     * Safe to call on every RecyclerView bind — recycled rows are hidden immediately
     * and only flip to VISIBLE if the resolved value for THIS uid is true, so a
     * fast scroll never leaves a stale badge from a recycled row.
     */
    public static void bindForUid(ImageView badgeView, String uid) {
        if (badgeView == null || uid == null || uid.isEmpty()) {
            if (badgeView != null) badgeView.setVisibility(View.GONE);
            return;
        }
        Boolean cached = VerifiedStatusCache.getInstance().getCached(uid);
        if (cached != null) {
            bind(badgeView, cached);
            return;
        }
        badgeView.setVisibility(View.GONE);
        VerifiedStatusCache.getInstance().resolve(uid, isVerified -> {
            // Guard against the row having been recycled for a different uid
            // by the time the async lookup returns.
            if (uid.equals(badgeView.getTag(TAG_KEY))) {
                bind(badgeView, isVerified);
            }
        });
        badgeView.setTag(TAG_KEY, uid);
    }

    /** For custom canvas-drawn rows (e.g. ChatRowContentView.setVerified) that can't use a plain ImageView. */
    public interface BadgeSetter {
        void setVerified(boolean verified);
    }

    /** Same caching/recycle-safety as {@link #bindForUid(ImageView, String)}, for a custom-drawn badge setter. */
    public static void bindForUid(View recycledGuardView, BadgeSetter setter, String uid) {
        if (setter == null) return;
        if (uid == null || uid.isEmpty()) {
            setter.setVerified(false);
            return;
        }
        Boolean cached = VerifiedStatusCache.getInstance().getCached(uid);
        if (cached != null) {
            setter.setVerified(cached);
            return;
        }
        setter.setVerified(false);
        if (recycledGuardView != null) recycledGuardView.setTag(TAG_KEY, uid);
        VerifiedStatusCache.getInstance().resolve(uid, isVerified -> {
            if (recycledGuardView == null || uid.equals(recycledGuardView.getTag(TAG_KEY))) {
                setter.setVerified(isVerified);
            }
        });
    }

    /**
     * Warms the badge cache for a whole page/screen's uids in one pass —
     * call this right after setting a list's data (setResults()/submitList()),
     * before the RecyclerView binds rows. See VerifiedStatusCache#resolveBatch.
     */
    public static void prefetch(java.util.Collection<String> uids) {
        VerifiedStatusCache.getInstance().resolveBatch(uids);
    }

    private static final int TAG_KEY = com.callx.app.core.R.id.verified_badge_uid_tag;
}
