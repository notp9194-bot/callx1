package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room DB entity — offline/instant-paint cache for the Home tab's reels
 * feed (HomeFragment).
 *
 * PROBLEM this fixes: every cold app open, HomeFragment showed a loading
 * spinner until Firebase responded (ranking pass + reelsRef read), even
 * though the user almost always sees roughly the same top-of-feed as their
 * last session. Instagram/TikTok-style apps paint the LAST-SEEN feed from
 * disk instantly, then silently reconcile with a fresh network fetch —
 * the loading state is only ever seen on a genuine first-ever install.
 *
 * This table caches just enough of each ReelModel to redraw the first
 * screenful of feed cards immediately (thumbnail, blurhash, playable video
 * urls, counts, owner info) — NOT a full offline feed store. It is fully
 * replaced (not merged) on every successful For-You/Following feed load;
 * see HomeFragment#renderFeedPostsWithState. Capped to
 * HOME_FEED_CACHE_LIMIT rows (see HomeFragment) so this never grows
 * unbounded.
 */
@Entity(
    tableName = "home_feed_cache",
    indices = { @Index(value = {"sortOrder"}) }
)
public class HomeFeedCacheEntity {

    @PrimaryKey
    @NonNull
    public String reelId = "";

    public String uid;
    public String ownerName;
    public String ownerPhoto;
    public String ownerAvatarBlurHash;
    public long   avatarVersion;

    public String videoUrl;
    public String video480;
    public String video720;
    public String video1080;
    public String hlsManifestUrl;

    public String thumbUrl;
    public String thumbnailUrl;
    public String blurHash;

    public String caption;
    public String musicName;
    public String musicId;
    public String musicUrl;
    public String musicCoverUrl;
    public String musicArtist;
    public int    musicStartSec;

    public long   timestamp;
    public int    duration;
    public int    width;
    public int    height;

    public int    likesCount;
    public int    commentsCount;
    public int    sharesCount;
    public int    viewsCount;
    public int    repostCount;

    public boolean isVerified;

    /** Position within the cached page — lets us restore original ordering. */
    public int    sortOrder;

    public long   cachedAt;

    public HomeFeedCacheEntity() {
        this.cachedAt = System.currentTimeMillis();
    }
}
