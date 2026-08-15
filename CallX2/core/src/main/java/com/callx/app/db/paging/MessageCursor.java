package com.callx.app.db.paging;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Compound keyset cursor for {@link MessageKeysetPagingSource}: (timestamp, id).
 *
 * ADVANCED FIX — this replaces the previous single-{@code Long timestamp}
 * cursor. A bare-timestamp cursor is unsound the moment two messages in the
 * same chat share an exact millisecond timestamp, which is not an edge case
 * here: it happens whenever several messages land in the same Firebase sync
 * burst (applyBufferedChanges' whole point is applying a batch in one shot),
 * an offline send-queue flush pushes a run of messages at once, or the
 * sender and receiver's clocks just happen to produce the same ms. With a
 * single-timestamp key:
 *
 *   - PREPEND's {@code WHERE timestamp < :key} silently SKIPS every other
 *     message that shares :key's timestamp with the boundary row, since
 *     none of them satisfy a strict {@code <}. Those messages are never
 *     loaded — they simply vanish from scroll-up history.
 *   - The anchor-REFRESH branch (see MessageKeysetPagingSource's class doc)
 *     re-queries {@code timestamp >= :key} to rebuild the page around the
 *     viewport; when several rows share that timestamp, this can't tell
 *     "the one already on screen" from "the one that isn't" — duplicate
 *     bubbles for one and a dropped bubble for the other on the same
 *     refresh.
 *
 * Pairing {@code (timestamp, id)} — id being the Room primary key, so no two
 * rows ever share a full pair — gives every query in {@code MessageDao}'s
 * keyset section (the {@code *ByCursor} methods) a genuine total order that
 * can never tie. This is the standard fix for keyset pagination over a
 * non-unique sort column (same idea as a "(ts, id)" or "(ts, seq)" cursor in
 * any cursor-paginated API/backend).
 */
public final class MessageCursor {

    public final long timestamp;
    @NonNull public final String id;

    public MessageCursor(long timestamp, @NonNull String id) {
        this.timestamp = timestamp;
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageCursor)) return false;
        MessageCursor that = (MessageCursor) o;
        return timestamp == that.timestamp && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, id);
    }

    @NonNull
    @Override
    public String toString() {
        return "MessageCursor{timestamp=" + timestamp + ", id='" + id + "'}";
    }
}
