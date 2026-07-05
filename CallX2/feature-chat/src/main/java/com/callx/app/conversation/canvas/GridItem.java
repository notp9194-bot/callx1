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
    public final boolean isAudio; // mixed-group audio cell — no thumbnail, icon+label only
    public final boolean isFile;  // mixed-group file cell — no thumbnail, icon+label only
    public final String duration; // e.g. "0:32"; null/empty if none or not a video
    public final String caption;  // per-item caption; null/empty for none — see setGroupCaptions doc
    // Label drawn under the glyph for audio/file cells: the audio duration
    // (falls back to "Audio") or the file name (falls back to "File") —
    // mirrors MediaGroupLayoutHelper.buildCell()'s isAudio||isFile branch.
    public final String label;

    public GridItem(boolean isVideo, @Nullable String duration) {
        this(isVideo, duration, null);
    }

    public GridItem(boolean isVideo, @Nullable String duration, @Nullable String caption) {
        this(isVideo, false, false, duration, caption, null);
    }

    /** Full constructor — used for mixed-type groups whose cells can be
     *  image/video/audio/file (see MessagePagingAdapter.isCanvasEligible). */
    public GridItem(boolean isVideo, boolean isAudio, boolean isFile,
                     @Nullable String duration, @Nullable String caption, @Nullable String label) {
        this.isVideo = isVideo;
        this.isAudio = isAudio;
        this.isFile = isFile;
        this.duration = duration;
        this.caption = caption;
        this.label = label;
    }
}
