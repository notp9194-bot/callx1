package com.callx.app.conversation.canvas;

import androidx.annotation.Nullable;

/**
 * Immutable per-cell descriptor for {@code MessageBubbleCanvasView.bindMediaGroup()};
 * bitmaps are supplied later, one at a time, via setMediaGroupBitmap() as Glide decodes them.
 *
 * Extracted verbatim from MessageBubbleCanvasView (feature-based file split,
 * no behavior change).
 */
public final class GridItem {
    public final boolean isVideo;
    public final String duration; // e.g. "0:32"; null/empty if none or not a video
    public final String caption;  // per-item caption; null/empty for none — see setGroupCaptions doc
    public GridItem(boolean isVideo, @Nullable String duration) {
        this(isVideo, duration, null);
    }
    public GridItem(boolean isVideo, @Nullable String duration, @Nullable String caption) {
        this.isVideo = isVideo;
        this.duration = duration;
        this.caption = caption;
    }
}
