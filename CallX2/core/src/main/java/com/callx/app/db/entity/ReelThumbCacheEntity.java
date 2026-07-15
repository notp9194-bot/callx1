package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room DB entity — advance #6 (offline-first profile reels grid).
 *
 * Mirrors the same pattern as the chat profile cache (UserEntity's
 * thumbUrl/photoUrl): caches just enough of each reel to redraw the
 * profile grid instantly on next open (thumbUrl + blurHash + the handful
 * of counters the grid overlays show), keyed by (ownerUid, tab) so the
 * "My Reels" / "Liked" / "Saved" tabs don't collide with each other.
 *
 * NOT a full reel cache — videoUrl, captions, music, etc. are deliberately
 * left out; those are re-fetched from Firebase once the grid item is
 * actually opened. This table exists purely so the grid never flashes an
 * empty state while Firebase responds.
 */
@Entity(
    tableName = "reel_thumb_cache",
    indices = { @Index(value = {"ownerUid", "tab", "timestamp"}) }
)
public class ReelThumbCacheEntity {

    @PrimaryKey
    @NonNull
    public String reelId = "";

    public String ownerUid;
    /** 0 = own reels, 1 = liked, 2 = saved — matches the tab constants in UserReelsActivity. */
    public int    tab;

    public String thumbUrl;
    public String blurHash;
    public String caption;
    public int    duration;
    public int    viewsCount;
    public int    likesCount;
    public int    commentsCount;
    public long   timestamp;

    /** Position within the cached page — lets us restore original ordering. */
    public int    sortOrder;

    public long   cachedAt;

    public ReelThumbCacheEntity() {
        this.cachedAt = System.currentTimeMillis();
    }
}
