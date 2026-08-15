package com.callx.app.db.paging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagingState;
import androidx.paging.rxjava3.RxPagingSource;

import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.entity.MessageEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * PERF FIX — keyset (a.k.a. cursor) pagination for the chat screen.
 *
 * Room's auto-generated {@code PagingSource<Integer, MessageEntity>} from a
 * plain {@code ORDER BY timestamp ASC} query is OFFSET-based under the hood:
 * to show a page anchored near the END of the table (which is exactly what
 * opening a chat needs — land on the newest messages), SQLite has to walk
 * past `OFFSET` rows before it can return `LIMIT` rows. That walk costs is
 * proportional to how many messages that chat has — a 20-message chat opens
 * instantly, a 5,000-message chat visibly stalls, even with the right index,
 * purely from the OFFSET skip.
 *
 * This class keys pages by a compound (timestamp, id) cursor instead of row
 * position. Every page is fetched with a `(chatId, timestamp[, id])`
 * comparison, which the existing (chatId, timestamp) index answers directly
 * — no OFFSET, so cost no longer grows with total chat history. Same
 * technique WhatsApp/Telegram/Signal-style chat apps use for this exact
 * reason.
 *
 * ADVANCED FIX — the cursor used to be a bare {@code Long timestamp}. That
 * breaks the moment two messages in the same chat share an exact
 * millisecond timestamp (a realistic case here — see {@link MessageCursor}'s
 * class doc): the old `WHERE timestamp < :key` boundary could silently skip
 * a sibling row on that same timestamp during PREPEND, and the old
 * anchor-REFRESH `timestamp >= :key` could re-show or drop one of them on
 * refresh. The cursor is now {@link MessageCursor} — (timestamp, id) — and
 * every query in {@code MessageDao}'s keyset section breaks the tie on
 * `id`, the Room primary key, so the pair is a true total order: every
 * message is reachable exactly once, full stop, regardless of timestamp
 * collisions.
 *
 * Key = the (timestamp, id) of the item just past whichever end of the
 * loaded window that page's request extends from. A null-key REFRESH
 * always loads the most recent PAGE_SIZE messages — chats normally open
 * bottom-anchored. A non-null-key REFRESH is a jump-to-message anchor: the
 * page is built CENTERED on that message instead, so
 * ChatActivity#navigateToOriginalMsg() can land on any message — however
 * old, in however large a chat, and however many other messages share its
 * timestamp — without an OFFSET scan. See the REFRESH branch below for
 * both cases.
 */
public class MessageKeysetPagingSource extends RxPagingSource<MessageCursor, MessageEntity> {

    private final MessageDao dao;
    private final String chatId;
    private final int pageSize;

    // A bottom-anchored write should refresh the latest window. A reader who
    // is in history should instead refresh around the viewport anchor.
    //
    // ADVANCED FIX: this flag used to be dead weight — a prior fix removed
    // the `if (refreshAtLatest) return null;` bypass in getRefreshKey()
    // (which was the actual flicker bug) but left the flag itself unused,
    // "harmless... in case a future caller needs it". It's now put to real
    // use: it controls the before/after split of the anchor-REFRESH branch
    // in loadSingle() (see MIN_BOTTOM_CONTEXT below) so a bottom-anchored
    // refresh spends nearly the whole page on genuinely new tail messages
    // instead of splitting 50/50 with "before" context the reader, who is
    // already at the bottom, doesn't need to re-see.
    private volatile boolean refreshAtLatest;

    // When refreshAtLatest is true, this is the most "before" context an
    // anchor-REFRESH keeps around the anchor. A plain send only ever adds
    // one new tail message, so this mostly just prevents a full-window
    // replace (the original flicker cause) for the boundary row. But it
    // also matters for bursts: e.g. reopening the app after being offline
    // while several messages arrived, or a chat catching up after a
    // buffered Firebase flush (see MessageDao#applyBufferedChanges) — a
    // 50/50 split would cap the "after" fetch at pageSize/2 and silently
    // leave newer tail messages for a follow-up APPEND load a moment
    // later; reserving only a small fixed "before" budget lets the anchor
    // page absorb up to (pageSize - MIN_BOTTOM_CONTEXT) new messages in
    // the very first refreshed page instead.
    private static final int MIN_BOTTOM_CONTEXT = 6;

    public MessageKeysetPagingSource(MessageDao dao, String chatId, int pageSize) {
        this.dao = dao;
        this.chatId = chatId;
        this.pageSize = pageSize;
    }

    public void setRefreshAtLatest(boolean refreshAtLatest) {
        this.refreshAtLatest = refreshAtLatest;
    }

    @NonNull
    @Override
    public Single<LoadResult<MessageCursor, MessageEntity>> loadSingle(@NonNull LoadParams<MessageCursor> params) {
        return Single.fromCallable(() -> {
            List<MessageEntity> page;
            MessageCursor prevKey; // PREPEND key — load OLDER than this page
            MessageCursor nextKey; // APPEND key  — load NEWER than this page

            if (params instanceof LoadParams.Prepend) {
                // Loading older messages, above what's currently shown.
                MessageCursor before = params.getKey();
                List<MessageEntity> desc = dao.getMessagesBeforeDesc(chatId, before.timestamp, before.id, pageSize);
                page = new ArrayList<>(desc);
                Collections.reverse(page); // DESC → ASC for display order
                prevKey = page.isEmpty() ? null : cursorOf(page.get(0));
                if (desc.size() < pageSize) prevKey = null; // reached true start of history
                nextKey = before; // resume forward pagination right where this page ends

            } else if (params instanceof LoadParams.Append) {
                // Loading newer messages, below what's currently shown.
                MessageCursor after = params.getKey();
                List<MessageEntity> asc = dao.getMessagesAfterAsc(chatId, after.timestamp, after.id, pageSize);
                page = asc;
                nextKey = page.isEmpty() ? null : cursorOf(page.get(page.size() - 1));
                if (asc.size() < pageSize) nextKey = null;
                prevKey = after;

            } else if (params.getKey() == null) {
                // REFRESH, no anchor — the normal "open a chat" case, always
                // the most recent page; this app opens a chat scrolled to
                // the bottom unless a jump-to-message anchor says otherwise.
                List<MessageEntity> desc = dao.getMessagesLatestDesc(chatId, pageSize);
                page = new ArrayList<>(desc);
                Collections.reverse(page); // DESC → ASC for display order
                prevKey = page.isEmpty() ? null : cursorOf(page.get(0));
                if (desc.size() < pageSize) prevKey = null;
                nextKey = null; // already at the newest message that existed at load time
            } else {
                // JUMP-TO-MESSAGE FIX (Telegram-style anchor REFRESH): a non-null
                // key on REFRESH means "land on this exact message", used by
                // ChatActivity#navigateToOriginalMsg() when the target reply
                // isn't in the currently loaded window, and also by every
                // send/receive-triggered refresh (see getRefreshKey below).
                // Build a fresh page CENTERED on the anchor's own (timestamp,
                // id). Both halves are plain indexed (chatId, timestamp)
                // range queries — same O(pageSize) cost regardless of total
                // chat history, no OFFSET scan — so this lands correctly on
                // message #1 or message #100,000 alike.
                //
                // ADVANCED FIX: the before/after split is no longer a fixed
                // 50/50 — see MIN_BOTTOM_CONTEXT's doc for why a
                // bottom-anchored refresh (refreshAtLatest) uses a small
                // fixed "before" budget instead, so nearly the whole page
                // goes to fresh tail content. A mid-history jump (not at the
                // bottom) keeps the original 50/50 centering, since there's
                // no "latest" bias to apply there.
                MessageCursor anchor = params.getKey();
                int beforeLimit = refreshAtLatest
                        ? Math.min(pageSize / 2, MIN_BOTTOM_CONTEXT)
                        : pageSize / 2;
                List<MessageEntity> beforeDesc = dao.getMessagesBeforeDesc(chatId, anchor.timestamp, anchor.id, beforeLimit);
                List<MessageEntity> before = new ArrayList<>(beforeDesc);
                Collections.reverse(before); // DESC → ASC
                int afterLimit = pageSize - before.size();
                // Inclusive of the anchor itself, so the target message
                // (whose (timestamp, id) == anchor) is guaranteed to be part
                // of this page even if other messages share its timestamp.
                List<MessageEntity> fromAnchor = dao.getMessagesFromAsc(chatId, anchor.timestamp, anchor.id, afterLimit);
                page = new ArrayList<>(before.size() + fromAnchor.size());
                page.addAll(before);
                page.addAll(fromAnchor);
                prevKey = page.isEmpty() ? null : cursorOf(page.get(0));
                if (before.size() < beforeLimit) prevKey = null; // reached true start of history
                nextKey = page.isEmpty() ? null : cursorOf(page.get(page.size() - 1));
                if (fromAnchor.size() < afterLimit) nextKey = null; // reached true end of history
            }

            return (LoadResult<MessageCursor, MessageEntity>) new LoadResult.Page<>(
                    page, prevKey, nextKey,
                    LoadResult.Page.COUNT_UNDEFINED, LoadResult.Page.COUNT_UNDEFINED);
        }).subscribeOn(Schedulers.io());
    }

    private static MessageCursor cursorOf(MessageEntity e) {
        // timestamp/id are non-null for any row that actually made it into
        // the DB (id is @NonNull on MessageEntity; timestamp is set at
        // insert time by every write path), so this is just defensive.
        long ts = e.timestamp != null ? e.timestamp : 0L;
        String id = e.id != null ? e.id : "";
        return new MessageCursor(ts, id);
    }

    @Nullable
    @Override
    public MessageCursor getRefreshKey(@NonNull PagingState<MessageCursor, MessageEntity> state) {
        // Writes invalidate this hand-written source explicitly. Preserve the
        // message nearest the viewport so a user reading older history stays
        // anchored instead of being rebuilt at the tail. loadSingle() treats
        // a non-null refresh key as a centered jump and includes both sides of
        // that message, which also lets a bottom-anchored refresh pick up new
        // tail messages without flashing the older rows.
        //
        // BUG FIX (flicker/junk on every send): a bypass used to sit here —
        // `if (refreshAtLatest) return null;` — which short-circuited BEFORE
        // the anchor logic below ever ran, for every local send while the
        // user was at the bottom. A null refresh key makes loadSingle() take
        // the "no anchor" REFRESH branch, which fetches ONLY the newest
        // pageSize messages and discards every other page Paging had
        // previously loaded — AsyncPagingDataDiffer then had to diff a
        // brand-new, much-smaller generation against whatever was on
        // screen, which is what actually caused the flicker/junk. Anchoring
        // unconditionally on the closest loaded item fixed that.
        //
        // ADVANCED FIX: refreshAtLatest is no longer just tolerated — it now
        // shapes loadSingle()'s anchor-REFRESH split (see MIN_BOTTOM_CONTEXT),
        // and the cursor itself is (timestamp, id) rather than a bare
        // timestamp, so this anchor can't collide with a same-timestamp
        // sibling of the closest item either.
        Integer anchorPosition = state.getAnchorPosition();
        if (anchorPosition == null) return null;
        MessageEntity anchor = state.closestItemToPosition(anchorPosition);
        if (anchor == null || anchor.timestamp == null || anchor.id == null) return null;
        return new MessageCursor(anchor.timestamp, anchor.id);
    }
}
