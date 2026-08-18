package com.callx.app.library;

import com.google.firebase.database.IgnoreExtraProperties;

import java.util.List;

/**
 * WatchHistoryItem — One entry in the user's reel watch history
 *
 * Firebase path: watchHistory/{uid}/{reelId}
 *
 * Design decisions:
 *  - Keyed by reelId so re-watching a reel updates the same record (no duplicates)
 *  - watchedAtMs updated on every re-watch (latest watch time)
 *  - watchCount increments each time the reel is opened
 *  - percentWatched: 0–100 (how much of the reel the user actually watched)
 */
@IgnoreExtraProperties
public class WatchHistoryItem {

    public String reelId;
    public String ownerUid;
    public String ownerName;
    public String ownerPhoto;
    public String thumbUrl;
    public String caption;
    public String mediaType;     // "video" | "photo_slideshow"
    public int    duration;      // seconds

    /**
     * Hashtags copied from the reel at watch time — lets ranking build an
     * *implicit* topic-affinity signal from what the user actually watches
     * (and how much of it), separate from the explicit Preferred/Not
     * Interested topics set in ReelFeedSettingsActivity. Null/empty for
     * entries recorded before this field existed — ranking treats that the
     * same as "no signal", not a penalty.
     */
    public List<String> hashtags;

    public long   watchedAtMs;   // timestamp of last watch
    public int    watchCount;    // total times watched
    public int    percentWatched; // 0-100 (last session completion)

    public WatchHistoryItem() {}

    public WatchHistoryItem(String reelId, String ownerUid, String ownerName,
                            String ownerPhoto, String thumbUrl, String caption,
                            String mediaType, int duration) {
        this(reelId, ownerUid, ownerName, ownerPhoto, thumbUrl, caption, mediaType, duration, null);
    }

    public WatchHistoryItem(String reelId, String ownerUid, String ownerName,
                            String ownerPhoto, String thumbUrl, String caption,
                            String mediaType, int duration, java.util.List<String> hashtags) {
        this.reelId       = reelId;
        this.ownerUid     = ownerUid;
        this.ownerName    = ownerName;
        this.ownerPhoto   = ownerPhoto;
        this.thumbUrl     = thumbUrl;
        this.caption      = caption;
        this.mediaType    = mediaType;
        this.duration     = duration;
        this.hashtags     = hashtags;
        this.watchedAtMs  = System.currentTimeMillis();
        this.watchCount   = 1;
        this.percentWatched = 0;
    }
}
