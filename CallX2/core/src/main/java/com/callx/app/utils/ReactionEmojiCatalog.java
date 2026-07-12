package com.callx.app.utils;

/**
 * Quick-reaction slot catalog for the chat long-press reaction picker.
 * Maps each slot's id to the unicode emoji that's actually stored/sent
 * (Message#reactions is Map<uid, unicode-emoji-string>, same shape in
 * Firebase + Room — see ReactionJsonUtil). The picker renders these as
 * plain unicode glyphs (see MessagePagingAdapter#buildUnicodeReactionGlyph)
 * — no RLottie/animated-sticker rendering here anymore.
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
