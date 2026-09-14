package com.callx.app.utils;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * #5 mediaItemsJson serialization skip: the chat-wide gallery window
 * (see ChatMediaGalleryBuilder/MessagePagingAdapter#openChatMediaViewer)
 * is already built in-memory on a background thread — handing it to
 * MediaViewerActivity used to mean serializing it to a JSON string,
 * shipping that string through an Intent extra (a real Binder
 * transaction, capped ~1MB), and having the Activity immediately
 * deserialize it straight back into the same List/Map shape. Since both
 * sides live in the same process, that whole round-trip is pure
 * overhead: this holder just hands the actual List reference across via
 * a static slot instead.
 *
 * A monotonically-increasing token (not the list itself) goes into the
 * Intent extra — cheap, and lets {@link #take} tell an actually-empty
 * gallery apart from "this token is stale" (e.g. the app process was
 * killed and recreated between put() and the Activity's onCreate() —
 * rare, but the whole point of a token instead of a plain boolean flag).
 * Callers should always also pass a plain-string single-item fallback
 * (url/thumbUrl/type extras) alongside the token so a stale/missed
 * take() degrades to the old single-image view instead of a blank
 * screen — see openChatMediaViewer's use of this class.
 *
 * take() clears the slot after a successful read so the (potentially
 * large) list reference doesn't outlive its one intended use.
 */
public final class GalleryIntentHolder {

    private GalleryIntentHolder() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static volatile int token = -1;
    @Nullable private static volatile List<Map<String, Object>> items;

    /** Stashes {@code list} and returns a token to pass through an Intent extra. */
    public static int put(List<Map<String, Object>> list) {
        int t = COUNTER.incrementAndGet();
        items = list;
        token = t;
        return t;
    }

    /**
     * Returns the list stashed under {@code expectedToken}, or null if it
     * doesn't match what's currently held (never put, already consumed,
     * or superseded by a newer put() — e.g. process death/recreation, or
     * the user backgrounding and re-opening a different photo before
     * this one's Activity finished starting).
     */
    @Nullable
    public static List<Map<String, Object>> take(int expectedToken) {
        if (expectedToken < 0 || token != expectedToken) return null;
        List<Map<String, Object>> result = items;
        items = null; // don't keep the reference alive past its one use
        return result;
    }
}
