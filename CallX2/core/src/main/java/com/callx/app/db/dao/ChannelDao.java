package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.db.entity.ChannelPostEntity;
import java.util.List;

/**
 * ChannelDao — Room queries for channels + channel posts.
 *
 * All list queries return LiveData so the UI auto-updates when the DB changes
 * (repository writes from Firebase → Room → LiveData → ViewModel → UI).
 */
@Dao
public interface ChannelDao {

    // ── Channels ──────────────────────────────────────────────────────────

    /** All channels the current user follows, ordered by most recent post. */
    @Query("SELECT * FROM channels WHERE isFollowed = 1 ORDER BY lastPostAt DESC")
    LiveData<List<ChannelEntity>> getFollowedChannels();

    /** Sync version — for background thread use. */
    @Query("SELECT * FROM channels WHERE isFollowed = 1 ORDER BY lastPostAt DESC")
    List<ChannelEntity> getFollowedChannelsSync();

    /** Top channels by followers (for "Suggested" section). */
    @Query("SELECT * FROM channels WHERE isFollowed = 0 ORDER BY followers DESC LIMIT :limit")
    LiveData<List<ChannelEntity>> getSuggestedChannels(int limit);

    /** All channels ordered by followers (for Explore). */
    @Query("SELECT * FROM channels ORDER BY followers DESC LIMIT :limit")
    LiveData<List<ChannelEntity>> getAllChannels(int limit);

    /** Search channels by name. */
    @Query("SELECT * FROM channels WHERE name LIKE :pattern ORDER BY followers DESC LIMIT :limit")
    List<ChannelEntity> searchChannels(String pattern, int limit);

    /** Single channel by id. */
    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    LiveData<ChannelEntity> getChannel(String channelId);

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    ChannelEntity getChannelSync(String channelId);

    /** Check if user follows a specific channel. */
    @Query("SELECT isFollowed FROM channels WHERE id = :channelId LIMIT 1")
    boolean isFollowed(String channelId);

    // ── Writes ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChannel(ChannelEntity channel);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChannels(List<ChannelEntity> channels);

    @Update
    void updateChannel(ChannelEntity channel);

    @Query("UPDATE channels SET isFollowed = :followed WHERE id = :channelId")
    void setFollowed(String channelId, boolean followed);

    @Query("UPDATE channels SET followers = followers + 1 WHERE id = :channelId")
    void incrementFollowers(String channelId);

    @Query("UPDATE channels SET followers = MAX(0, followers - 1) WHERE id = :channelId")
    void decrementFollowers(String channelId);

    @Query("DELETE FROM channels WHERE id = :channelId")
    void deleteChannel(String channelId);

    // ── Channel Posts ─────────────────────────────────────────────────────

    /** Live posts for a channel, newest first. */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId ORDER BY timestamp DESC LIMIT :limit")
    LiveData<List<ChannelPostEntity>> getChannelPosts(String channelId, int limit);

    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId ORDER BY timestamp DESC LIMIT :limit")
    List<ChannelPostEntity> getChannelPostsSync(String channelId, int limit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPost(ChannelPostEntity post);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPosts(List<ChannelPostEntity> posts);

    @Query("DELETE FROM channel_posts WHERE channelId = :channelId")
    void deletePostsByChannel(String channelId);

    @Query("UPDATE channel_posts SET viewCount = viewCount + 1 WHERE id = :postId")
    void incrementViewCount(String postId);

    @Query("UPDATE channel_posts SET forwardCount = forwardCount + 1 WHERE id = :postId")
    void incrementForwardCount(String postId);

    // ── Maintenance ────────────────────────────────────────────────────────

    /** Prune posts older than given timestamp to save storage. */
    @Query("DELETE FROM channel_posts WHERE timestamp < :beforeTimestamp")
    void pruneOldPosts(long beforeTimestamp);

    /** Total post count for a channel. */
    @Query("SELECT COUNT(*) FROM channel_posts WHERE channelId = :channelId")
    int getPostCount(String channelId);
}
