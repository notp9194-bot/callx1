package com.callx.app.db;

/**
 * Cheap freshness probe for {@link com.callx.app.utils.ChatMediaGalleryBuilder}'s
 * in-memory gallery cache (item #1: LruCache by chatId).
 *
 * Backs {@link com.callx.app.db.dao.MessageDao#getChatMediaFreshness(String)} —
 * a COUNT+MAX aggregate over the same indexed (chatId,timestamp) range the
 * full {@code getChatMediaRows()} query scans, so checking "did anything
 * change since I cached this chat's gallery?" is a single cheap indexed
 * aggregate instead of re-reading and re-flattening every row.
 */
public class ChatMediaFreshness {
    public int cnt;
    public Long maxTs;

    public boolean matches(int cachedCnt, long cachedMaxTs) {
        long ts = maxTs == null ? 0L : maxTs;
        return cnt == cachedCnt && ts == cachedMaxTs;
    }
}
