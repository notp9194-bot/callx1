package com.callx.app.utils;

import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * PERF (item 3 — "Paging + Canvas/media rebind"): ChatActivity#entityToModel()
 * and GroupChatActivity#entityToModel() are called from
 * PagingDataTransforms.map() on EVERY page that (re)loads from Room. Per
 * MessageKeysetPagingSource's anchor-REFRESH (see its
 * MAX_PRESERVED_BEFORE_CONTEXT / MAX_BOTTOM_CATCHUP_AFTER), that refresh
 * window can be several hundred rows on every single send/receive while a
 * chat stays open — even though normally only 1-2 of those rows actually
 * changed since the last time they were mapped. Each remap used to be a
 * full ~90-field copy PLUS up to 6 fresh JSON re-parses (reactions, two
 * group receipt maps, poll options/votes, media items, edit history) and a
 * new Message allocation — real, repeated CPU + GC cost paid for rows whose
 * content hasn't moved at all.
 *
 * This cache keeps the last-built Message per message id, alongside a cheap
 * "fingerprint" made only of the fields that can actually change AFTER a
 * message is first inserted. On every entityToModel() call:
 *   - if the entity's current fingerprint still matches what's cached for
 *     that id  -> the previously-built Message is returned as-is; the real
 *     mapper (field copy + JSON parsing + Canvas precompute) never runs.
 *   - otherwise -> the real mapper runs once, and the result is cached
 *     under the new fingerprint.
 *
 * Fingerprint design: fields that NEVER change post-insert (timestamp,
 * senderId, type, mediaUrl's identity as "this message's media", etc.) are
 * deliberately left OUT, so they can never cause a false "changed" verdict.
 * Anything that legitimately mutates over a message's lifetime (tick
 * status, edits, reactions, receipts, poll votes, view-once state, local
 * media path while an upload is in flight, ...) is deliberately IN it, so a
 * genuine change always still remaps correctly. The fingerprint compares
 * RAW stored values (e.g. the raw reactionsJson string, not its parsed
 * form), so computing it is always far cheaper than a real remap.
 *
 * Shared (static) across ChatActivity and GroupChatActivity: message ids
 * are globally unique, so this is safe to key by id alone with no chatId
 * component, and there's no need to clear it on chat switch — entries for
 * a chat that's no longer open simply stop being looked up and age out via
 * the LRU cap below.
 */
public final class MessageModelCache {

    private MessageModelCache() {}

    // Comfortably covers the largest anchor-REFRESH window
    // (MAX_PRESERVED_BEFORE_CONTEXT + MAX_BOTTOM_CATCHUP_AFTER = 800) with
    // room to spare for a second recently-open chat, without holding onto
    // unbounded history for a session that visits many chats.
    private static final int MAX_ENTRIES = 1500;

    private static final class CacheEntry {
        final String fingerprint;
        final Message message;
        CacheEntry(String fingerprint, Message message) {
            this.fingerprint = fingerprint;
            this.message = message;
        }
    }

    private static final LinkedHashMap<String, CacheEntry> CACHE =
            new LinkedHashMap<String, CacheEntry>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private static String fingerprintOf(MessageEntity e) {
        // '\u0001' separators so e.g. text="a" + deleted=null can't collide
        // with text="a\u0001" + deleted="null"-ish values across fields.
        StringBuilder sb = new StringBuilder(160);
        sb.append(e.status).append('\u0001')
          .append(e.deliveredAt).append('\u0001')
          .append(e.readAt).append('\u0001')
          .append(e.groupDeliveredByJson).append('\u0001')
          .append(e.groupReadByJson).append('\u0001')
          .append(e.edited).append('\u0001')
          .append(e.editedAt).append('\u0001')
          .append(e.text).append('\u0001')
          .append(e.deleted).append('\u0001')
          .append(e.starred).append('\u0001')
          .append(e.pinned).append('\u0001')
          .append(e.reactionsJson).append('\u0001')
          .append(e.pollVotesJson).append('\u0001')
          .append(e.pollClosed).append('\u0001')
          .append(e.viewOnceState).append('\u0001')
          .append(e.openedAt).append('\u0001')
          .append(e.mediaLocalPath).append('\u0001')
          .append(e.mediaUrl).append('\u0001')
          .append(e.thumbnailUrl).append('\u0001')
          .append(e.caption).append('\u0001')
          .append(e.editHistoryJson).append('\u0001')
          .append(e.forwardedFrom).append('\u0001')
          .append(e.topicName);
        return sb.toString();
    }

    /**
     * Returns the cached Message for e.id if unchanged since the last call
     * for that id; otherwise runs {@code mapper} (the real
     * MessageEntityMapper.toModel()+precompute path) once, caches, and
     * returns the fresh result. `mapper` is never invoked on a cache hit.
     */
    public static Message getOrMap(MessageEntity e, Function<MessageEntity, Message> mapper) {
        if (e == null || e.id == null) return mapper.apply(e);
        String fp = fingerprintOf(e);
        synchronized (CACHE) {
            CacheEntry cached = CACHE.get(e.id);
            if (cached != null && cached.fingerprint.equals(fp)) {
                return cached.message;
            }
        }
        Message m = mapper.apply(e);
        synchronized (CACHE) {
            CACHE.put(e.id, new CacheEntry(fp, m));
        }
        return m;
    }
}
