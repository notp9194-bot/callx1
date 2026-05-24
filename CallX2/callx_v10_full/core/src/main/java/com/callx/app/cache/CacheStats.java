package com.callx.app.cache;

/**
 * Analytics model — tracks per-chat cache usage.
 * Used by CacheAnalytics to compute dynamic cache priority.
 *
 * FIX #7 (LOW): openCount changed from int → long.
 *
 *   Old: public int openCount;
 *   → Integer max = ~2.1 billion. An extremely active user (or a bug that
 *     calls recordChatOpen() in a tight loop) overflows to a negative value.
 *   → computePriority() thresholds are 10 (HIGH) and 3 (MEDIUM).
 *     A negative openCount always falls below both → priority drops to LOW.
 *   → An actively used chat gets evicted from RAM → unnecessary DB reads
 *     every time the chat is opened → performance regression for power users.
 *
 *   Fix: long openCount — max ~9.2 × 10^18. Effectively never overflows.
 *     computePriority() thresholds unchanged (10 / 3) — still correct.
 */
public class CacheStats {

    public final String chatId;

    // FIX #7: long instead of int — no overflow for power users
    public long openCount;

    public long lastUsed;
    public long totalBytesLoaded;

    public CacheStats(String chatId) {
        this.chatId           = chatId;
        this.openCount        = 1L;
        this.lastUsed         = System.currentTimeMillis();
        this.totalBytesLoaded = 0L;
    }

    /**
     * Determine cache priority based on usage analytics.
     *
     * HIGH   → opened ≥10 times, OR used within last 30 minutes
     * MEDIUM → opened ≥3 times,  OR used within last 24 hours
     * LOW    → idle for 7+ days
     */
    public CachePriority computePriority() {
        long ageMs    = System.currentTimeMillis() - lastUsed;
        long sevenDays = 7L * 24 * 60 * 60 * 1000;

        if (openCount >= 10L || ageMs < 30L * 60 * 1000) {
            return CachePriority.HIGH;
        } else if (openCount >= 3L || ageMs < 24L * 60 * 60 * 1000) {
            return CachePriority.MEDIUM;
        } else if (ageMs > sevenDays) {
            return CachePriority.LOW;
        }
        return CachePriority.MEDIUM;
    }
}
