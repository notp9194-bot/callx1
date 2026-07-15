package com.callx.app.community.canvas;

import android.view.View;

/**
 * Click/interaction callback used by {@link CommunityPostCanvasView} — every
 * tappable region on a community post card reports through this single
 * listener, set via {@code CommunityPostCanvasView.setOnPostClickListener()}.
 *
 * Mirrors OnBubbleClickListener from the chat canvas package — same
 * one-listener-per-host pattern, default no-op methods so callers only
 * override what they actually use.
 */
public interface OnPostClickListener {

    /** Tapped anywhere on the card that isn't a more specific region below. */
    default void onPostClick() {}
    /** Long-pressed the card body. */
    default void onPostLongClick() {}
    /** Tapped the author avatar or name. */
    default void onAuthorClick() {}
    /** Tapped the 3-dot options button (admin/author only). */
    default void onOptionsClick() {}
    /** Tapped an @mention span inside the post text at the given uid, if resolvable. */
    default void onMentionClick(String rawMention) {}
    /** Tapped the single media card (image/video). */
    default void onMediaClick() {}
    /** Tapped cell `index` inside a multi-image media group grid. */
    default void onMediaCellClick(int index) {}
    /** Tapped the "+N" overflow cell — caller should open the gallery at the end. */
    default void onMediaGroupOverflowClick() {}
    /** Tapped poll option at `optionIndex` to cast/change a vote. */
    default void onPollOptionClick(int optionIndex) {}
    /** Tapped the reaction chip row — caller should open the reaction-details sheet. */
    default void onReactionsClick() {}
    /** Tapped the like button (single tap = toggle default reaction). */
    default void onLikeClick() {}
    /** Long-pressed the like button — caller should open the reaction picker anchored at this view. */
    default void onLikeLongClick(View anchorView) {}
    /** Tapped the comment icon/count — caller should open the comments screen. */
    default void onCommentClick() {}
    /** Tapped the share icon — caller should open the share sheet for this post. */
    default void onShareClick() {}
}
