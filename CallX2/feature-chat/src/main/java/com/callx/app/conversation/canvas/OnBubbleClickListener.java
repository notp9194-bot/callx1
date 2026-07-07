package com.callx.app.conversation.canvas;

/**
 * Click/interaction callback used by {@link MessageBubbleCanvasView} — every
 * tappable region on any bubble type reports through this single listener,
 * set via {@code MessageBubbleCanvasView.setOnBubbleClickListener()}.
 *
 * Extracted verbatim from MessageBubbleCanvasView (feature-based file split,
 * no behavior change).
 */
public interface OnBubbleClickListener {

    void onBubbleClick();
    void onBubbleLongClick();
    /** Returns true if a link at (x,y) was hit and handled — caller should not treat as a normal click. */
    default boolean onLinkClick(String url) { return false; }
    /** Tapped the reply-preview strip — caller should scroll/jump to the quoted message. */
    default void onReplyPreviewClick() {}
    /** Tapped the image itself (media bubbles only) — caller should open the full-screen media viewer. */
    default void onImageClick() {}
    /** Tapped the single-media download gate pill (setMediaDownloadGate) while idle (not yet downloading) —
     *  caller should start the manual download and drive setMediaDownloadProgress() as it reports progress. */
    default void onMediaDownloadClick() {}
    /** Tapped cell `index` inside a media-group grid (bindMediaGroup only) — caller should open the gallery viewer at that index. */
    default void onMediaCellClick(int index) {}
    /** Tapped the master "Download N photos" pill on a RECEIVED group — caller should start every pending cell's download (view has already dismissed the pill locally). */
    default void onGroupDownloadAllClick() {}
    /** Tapped an individual still-pending cell directly (gate already dismissed) — caller should start that one cell's download. */
    default void onGroupCellDownloadClick(int index) {}
    /** Tapped the reaction badge — caller should open the reaction-details/picker sheet (same as ll_reactions' click listener on the legacy path). */
    default void onReactionsClick() {}
    /** Tapped the play/pause button on an audio bubble (bindAudio only) — caller should toggle MediaPlayer playback for this message. */
    default void onAudioPlayPauseClick() {}
    /** Dragged/tapped the waveform on an audio bubble (bindAudio only) — fraction is 0..1 of the track; caller should seek MediaPlayer to it. The view already updated its own progress bar optimistically. */
    default void onAudioSeek(float fraction) {}
    /** Tapped the "View Contact" row on a contact card (bindContact only) — caller should open the system Contacts app / dialer for this contact's phone number, same as the legacy btnViewContact click listener. */
    default void onContactViewClick() {}
    /** Tapped the "Open in Maps" row on a location card (bindLocation only) — caller should launch a maps app (or geo: intent) for this location's coordinates, same as the legacy btnOpenMaps click listener. */
    default void onLocationOpenMapsClick() {}
    /** Tapped a GIF thumbnail — caller should open the full-screen GIF viewer / start GIF playback. */
    default void onGifClick() {}
    /** Tapped the ⬇ download button on an uncached file bubble — caller should start the file download. */
    default void onFileDownloadClick() {}
    /** Tapped the ⬗ open button on a cached file bubble — caller should open the file via FileProvider. */
    default void onFileOpenClick() {}
    default void onPollOptionClick(int optionIndex) {}
    /** Tapped the "✏️ edited" tag inside the footer timestamp — caller should show the edit-history sheet for this message. */
    default void onEditedTagClick() {}
    /** Tapped a view-once bubble (bindViewOnce only). Fires for every variant — the caller should
     *  ignore it for VIEW_ONCE_WAITING/VIEW_ONCE_EXPIRED (mirrors the legacy path's null click
     *  listener on those two states) and open the view-once viewer only for VIEW_ONCE_RECEIVED. */
    default void onViewOnceClick() {}
    /** Tapped a "watched your reel" / "seen your status" system bubble (bindSeenBubble only) —
     *  caller should open the reel or the status viewer, same as the legacy tap listeners on
     *  ll_bubble/fl_reel_seen_thumb / fl_status_seen_thumb (whole card and thumbnail both open it). */
    default void onSeenBubbleClick() {}
    /** Tapped the quick-forward icon button that sits just outside the bubble
     *  (setQuickForwardVisible only) — caller should forward this message,
     *  same as the legacy btn_quick_forward click listener. */
    default void onForwardClick() {}
    /** TICK ADVANCE (media bubbles): tapped the ⚠ failed-send tick — caller should retry sending this message, same as the legacy tv_status "⚠" click listener (only fires while the bubble's status is "failed"). */
    default void onRetryClick() {}
}
