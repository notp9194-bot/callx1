package com.callx.app.utils;

/**
 * Bridges the RLottie-backed reaction picker to the existing unicode-emoji
 * data contract (Message#reactions is Map<uid, unicode-emoji-string>, same
 * shape in Firebase + Room — see ReactionJsonUtil). Nothing about *storage*
 * changes: tapping a reaction still calls onReact(message, unicodeEmoji).
 *
 * This catalog only maps each quick-reaction slot to the LottieAssetCache
 * key (EmojiPackDownloadWorker.CACHE_KEY_PREFIX + id) the picker should try
 * to animate instead of the plain unicode glyph. If that file isn't cached
 * yet, callers fall back to the unicode glyph — never a blank slot.
 */
public final class ReactionEmojiCatalog {

    private ReactionEmojiCatalog() {}

    public static final class Entry {
        public final String id;      // manifest id / cache key suffix, e.g. "heart"
        public final String unicode; // what's actually stored/sent, e.g. "❤️"

        public Entry(String id, String unicode) {
            this.id = id;
            this.unicode = unicode;
        }
    }

    public static final Entry[] QUICK_REACTIONS = {
        new Entry("heart", "\u2764\uFE0F"),
        new Entry("thumb", "\uD83D\uDC4D"),
        new Entry("laugh", "\uD83D\uDE02"),
        new Entry("wow",   "\uD83D\uDE2E"),
        new Entry("sad",   "\uD83D\uDE22"),
        new Entry("angry", "\uD83D\uDE21"),
    };
}
