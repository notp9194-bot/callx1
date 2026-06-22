package com.callx.app.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory "last messages" cache — one small list (≤20) of the most recent
 * messages per chat, kept purely so a chat screen can render INSTANTLY on
 * reopen, before Room/Paging/Firebase have done anything.
 *
 * This is NOT a source of truth. Room DB + Firebase remain the only sources
 * of truth — this cache is read once on ChatActivity.onCreate() for the
 * very first frame, then the real Paging3 + Room + Firebase pipeline takes
 * over and this cache is only kept warm in the background for next time.
 *
 * Thread-safety: every method is synchronized. Lists handed out by get()
 * are defensive copies — callers can't mutate cache state by accident.
 *
 * Two-level LRU:
 *   - Outer: which CHATS stay in memory at all (MAX_CHATS, evicts oldest
 *     touched chat once exceeded — access-order LinkedHashMap).
 *   - Inner: how many MESSAGES per chat (MAX_PER_CHAT, oldest trimmed off
 *     the front since the list is kept oldest→newest, matching Room's
 *     ASC order / Paging3's order — see MessageDao).
 */
public final class LastMessagesCache {

    private static final int MAX_CHATS     = 25; // LRU ceiling across chats
    private static final int MAX_PER_CHAT  = 20; // messages kept per chat

    private static volatile LastMessagesCache sInstance;

    public static LastMessagesCache getInstance() {
        if (sInstance == null) {
            synchronized (LastMessagesCache.class) {
                if (sInstance == null) sInstance = new LastMessagesCache();
            }
        }
        return sInstance;
    }

    // accessOrder=true → LRU; removeEldestEntry evicts the least-recently-touched chat
    private final LinkedHashMap<String, List<Message>> cache =
            new LinkedHashMap<String, List<Message>>(MAX_CHATS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Message>> eldest) {
                    return size() > MAX_CHATS;
                }
            };

    private LastMessagesCache() {}

    /** True if we have any cached messages for this chat (used for the
     *  warm-render fast path in ChatActivity.onCreate). */
    public synchronized boolean has(@Nullable String chatId) {
        if (chatId == null) return false;
        List<Message> list = cache.get(chatId);
        return list != null && !list.isEmpty();
    }

    /** Defensive-copy snapshot, oldest→newest (same order as Room/Paging). */
    @NonNull
    public synchronized List<Message> get(@Nullable String chatId) {
        if (chatId == null) return new ArrayList<>();
        List<Message> list = cache.get(chatId);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /**
     * Replaces the whole cached list for a chat — used right after a real
     * Room load (initial page, or post-flush refresh) so the cache always
     * reflects what Room actually has, never a stale/partial picture.
     * Input is trimmed to the last MAX_PER_CHAT entries, kept oldest→newest.
     */
    public synchronized void seed(@Nullable String chatId, @Nullable List<Message> latestAsc) {
        if (chatId == null || latestAsc == null) return;
        List<Message> trimmed = new ArrayList<>(latestAsc);
        if (trimmed.size() > MAX_PER_CHAT) {
            trimmed = new ArrayList<>(trimmed.subList(trimmed.size() - MAX_PER_CHAT, trimmed.size()));
        }
        cache.put(chatId, trimmed);
    }

    /**
     * Upserts a single message (new Firebase message, or an edit/status
     * change to an existing one) while keeping order + the size cap intact.
     * No duplicates: matched by Message.id.
     */
    public synchronized void upsert(@Nullable String chatId, @Nullable Message m) {
        if (chatId == null || m == null || m.id == null) return;
        List<Message> list = cache.get(chatId);
        if (list == null) {
            list = new ArrayList<>();
            cache.put(chatId, list);
        }
        long ts = m.timestamp != null ? m.timestamp : 0L;
        int existingIdx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (m.id.equals(list.get(i).id)) { existingIdx = i; break; }
        }
        if (existingIdx >= 0) {
            // Edit/status update to a message already in cache — replace in place,
            // order doesn't change (its timestamp shouldn't either).
            list.set(existingIdx, m);
        } else {
            // New message — insert in timestamp order (almost always just append,
            // since new messages arrive newest-last; insertion-sort handles the
            // rare out-of-order delivery case too).
            int insertAt = list.size();
            for (int i = list.size() - 1; i >= 0; i--) {
                long otherTs = list.get(i).timestamp != null ? list.get(i).timestamp : 0L;
                if (otherTs <= ts) break;
                insertAt = i;
            }
            list.add(insertAt, m);
            while (list.size() > MAX_PER_CHAT) {
                list.remove(0); // drop oldest — only the last MAX_PER_CHAT are kept
            }
        }
    }

    /** Removes a single message (Firebase onChildRemoved / delete-for-everyone). */
    public synchronized void removeMessage(@Nullable String chatId, @Nullable String messageId) {
        if (chatId == null || messageId == null) return;
        List<Message> list = cache.get(chatId);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (messageId.equals(list.get(i).id)) { list.remove(i); break; }
        }
    }

    /** Drops one chat's cached messages entirely (rarely needed — chats are
     *  naturally scoped by key, so normal chat-switching never bleeds data
     *  between chats; this exists for explicit cases like "chat deleted"). */
    public synchronized void evictChat(@Nullable String chatId) {
        if (chatId == null) return;
        cache.remove(chatId);
    }

    /** Full clear — used on logout and on critical low-memory signals. */
    public synchronized void clear() {
        cache.clear();
    }

    /**
     * Respond to Android's onTrimMemory signals. Moderate: just shrink each
     * chat's list down to a smaller hot-set. Critical: drop everything —
     * Room remains the source of truth so nothing is lost, only the instant-
     * render fast path is temporarily disabled until chats are reopened.
     */
    public synchronized void trimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            cache.clear();
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            for (List<Message> list : cache.values()) {
                while (list.size() > 5) list.remove(0); // keep just a tiny hot-set per chat
            }
        }
    }
}
