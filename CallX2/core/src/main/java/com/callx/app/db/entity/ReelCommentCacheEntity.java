package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * Room DB entity — offline cache for the Reel comments sheet
 * (ReelCommentFragment / ReelCommentSheetFragment).
 *
 * Same shape/intent as TrendingAudioCacheEntity: caches the last-loaded
 * live window (PAGE_SIZE comments, see ReelCommentFragment) so reopening
 * the comments sheet paints instantly from disk instead of always sitting
 * on the shimmer until Firebase's first read lands. Firebase is still
 * always re-queried on open (ChildEventListener in loadComments()) — this
 * table only fills the gap before that response arrives, and covers a
 * fully-offline cold open. likedBy/reactions/mentions are small per-comment
 * maps, so they're stored pre-serialized as JSON rather than needing their
 * own tables.
 */
@Entity(
    tableName = "reel_comment_cache",
    primaryKeys = { "reelId", "commentId" },
    indices = { @Index(value = {"reelId", "sortOrder"}) }
)
public class ReelCommentCacheEntity {

    @NonNull
    public String reelId = "";

    @NonNull
    public String commentId = "";

    public String uid;
    public String ownerName;
    public String ownerPhoto;
    public String text;
    public String imageUrl;
    public long   timestamp;
    public int    likesCount;
    public int    replyCount;
    public long   avatarVersion;
    public boolean isPinned;
    public boolean isEdited;
    public long   editedAt;

    /** Map<String,Boolean> (uid → liked) serialized via Gson. */
    public String likedByJson;
    /** Map<String,String> (uid → emoji) serialized via Gson. */
    public String reactionsJson;
    /** Map<String,String> (uid → display name) serialized via Gson. */
    public String mentionsJson;

    /** Position within the cached window — lets us restore original order. */
    public int    sortOrder;

    public long   cachedAt;

    public ReelCommentCacheEntity() {
        this.cachedAt = System.currentTimeMillis();
    }
}
