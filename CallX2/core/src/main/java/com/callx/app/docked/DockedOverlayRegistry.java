package com.callx.app.docked;

import androidx.annotation.Nullable;

/**
 * App-wide registry for the single active chat-docked mini reel player
 * session (see {@link DockedOverlayHandoff}).
 *
 * Populated by ReelChatDockedPlayer (feature-reels) whenever a dock session
 * starts ({@code show()}) or permanently ends ({@code dismiss()} /
 * {@code collapseBack()}). Read by any screen — including ones in modules
 * that don't depend on feature-reels — that wants to detach the overlay
 * before navigating to its own window, or reattach it on return.
 */
public final class DockedOverlayRegistry {

    @Nullable
    private static volatile DockedOverlayHandoff active;

    private DockedOverlayRegistry() {}

    public static void setActive(@Nullable DockedOverlayHandoff handoff) {
        active = handoff;
    }

    @Nullable
    public static DockedOverlayHandoff getActive() {
        return active;
    }

    /**
     * Convenience for call sites that only ever need to detach-if-showing
     * right before starting another Activity (e.g. ChatListAdapter before
     * launching ChatActivity) — no null/isShowing boilerplate at the call site.
     */
    public static void detachIfShowing() {
        DockedOverlayHandoff a = active;
        if (a != null && a.isShowing()) {
            a.detachKeepAlive();
        }
    }

    /**
     * Convenience for call sites that only ever need to reattach-if-active
     * right when their own Activity resumes (e.g. ChatActivity, MainActivity).
     */
    public static void attachIfActiveAndHidden(android.app.Activity activity) {
        DockedOverlayHandoff a = active;
        if (a != null && a.isActive() && !a.isShowing()) {
            a.attachToActivity(activity);
        }
    }
}
