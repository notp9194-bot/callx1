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
 *
 * WhatsApp-level v2 — adds pinned post, scheduled posts, trending sort,
 * category filtering, reaction stats, and draft support.
 */
@Dao
public interface ChannelDao {

    // ── Channels ──────────────────────────────────────────────────────────

    /** All channels the current user follows, ordered by most recent post. */
    @Query("SELECT * FROM channels WHERE isFollowed = 1 ORDER BY lastPostAt DESC")
    LiveData<List<ChannelEntity>> getFollowedChannels();

    @Query("SELECT * FROM channels WHERE isFollowed = 1 ORDER BY lastPostAt DESC")
    List<ChannelEntity> getFollowedChannelsSync();

    /** Followed channels with unread posts, for badge display. */
    @Query("SELECT * FROM channels WHERE isFollowed = 1 AND unreadCount > 0 ORDER BY lastPostAt DESC")
    LiveData<List<ChannelEntity>> getFollowedChannelsWithUnread();

    /** Top channels by followers for "Suggested" section (not already followed). */
    @Query("SELECT * FROM channels WHERE isFollowed = 0 ORDER BY followers DESC LIMIT :limit")
    LiveData<List<ChannelEntity>> getSuggestedChannels(int limit);

    /** All channels ordered by followers (for Explore). */
    @Query("SELECT * FROM channels ORDER BY followers DESC LIMIT :limit")
    LiveData<List<ChannelEntity>> getAllChannels(int limit);

    /** Channels ordered by weekly growth (for Trending). */
    @Query("SELECT * FROM channels ORDER BY weeklyGrowth DESC LIMIT :limit")
    LiveData<List<ChannelEntity>> getTrendingChannels(int limit);

    /** Channels by category. */
    @Query("SELECT * FROM channels WHERE category = :category ORDER BY followers DESC LIMIT :limit")
    LiveData<List<ChannelEntity>> getChannelsByCategory(String category, int limit);

    /** Search channels by name. */
    @Query("SELECT * FROM channels WHERE name LIKE :pattern ORDER BY followers DESC LIMIT :limit")
    List<ChannelEntity> searchChannels(String pattern, int limit);

    /** Muted channels. */
    @Query("SELECT * FROM channels WHERE isFollowed = 1 AND isMuted = 1 ORDER BY lastPostAt DESC")
    LiveData<List<ChannelEntity>> getMutedChannels();

    /** Channels owned by current user. */
    @Query("SELECT * FROM channels WHERE ownerUid = :uid ORDER BY createdAt DESC")
    LiveData<List<ChannelEntity>> getMyOwnedChannels(String uid);

    /** Single channel by id. */
    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    LiveData<ChannelEntity> getChannel(String channelId);

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    ChannelEntity getChannelSync(String channelId);

    /** Check if user follows a specific channel. */
    @Query("SELECT isFollowed FROM channels WHERE id = :channelId LIMIT 1")
    boolean isFollowed(String channelId);

    /** Check if channel is muted. */
    @Query("SELECT isMuted FROM channels WHERE id = :channelId LIMIT 1")
    boolean isMuted(String channelId);

    /** Get total unread count across all followed channels. */
    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM channels WHERE isFollowed = 1")
    LiveData<Long> getTotalUnreadCount();

    // ── Channel Writes ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChannel(ChannelEntity channel);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChannels(List<ChannelEntity> channels);

    @Update
    void updateChannel(ChannelEntity channel);

    @Query("UPDATE channels SET isFollowed = :followed WHERE id = :channelId")
    void setFollowed(String channelId, boolean followed);

    @Query("UPDATE channels SET isMuted = :muted WHERE id = :channelId")
    void setMuted(String channelId, boolean muted);

    @Query("UPDATE channels SET isAdmin = :admin WHERE id = :channelId")
    void setAdmin(String channelId, boolean admin);

    @Query("UPDATE channels SET followers = followers + 1 WHERE id = :channelId")
    void incrementFollowers(String channelId);

    @Query("UPDATE channels SET followers = MAX(0, followers - 1) WHERE id = :channelId")
    void decrementFollowers(String channelId);

    @Query("UPDATE channels SET unreadCount = unreadCount + 1 WHERE id = :channelId")
    void incrementUnread(String channelId);

    @Query("UPDATE channels SET unreadCount = 0, lastSeenPostTimestamp = :timestamp WHERE id = :channelId")
    void markAllRead(String channelId, long timestamp);

    @Query("UPDATE channels SET totalPosts = totalPosts + 1 WHERE id = :channelId")
    void incrementPostCount(String channelId);

    @Query("UPDATE channels SET pinnedPostId = :postId WHERE id = :channelId")
    void setPinnedPost(String channelId, String postId);

    @Query("UPDATE channels SET inviteCode = :code, inviteLink = :link WHERE id = :channelId")
    void setInviteLink(String channelId, String code, String link);

    @Query("UPDATE channels SET name = :name, description = :desc, iconUrl = :iconUrl, category = :category, isPrivate = :isPrivate WHERE id = :channelId")
    void updateChannelMeta(String channelId, String name, String desc, String iconUrl, String category, boolean isPrivate);

    @Query("UPDATE channels SET weeklyGrowth = :growth WHERE id = :channelId")
    void setWeeklyGrowth(String channelId, long growth);

    @Query("UPDATE channels SET totalViews = totalViews + :delta WHERE id = :channelId")
    void addViews(String channelId, long delta);

    @Query("UPDATE channels SET ownerName = :name, ownerIconUrl = :iconUrl WHERE id = :channelId")
    void setOwnerProfile(String channelId, String name, String iconUrl);

    @Query("DELETE FROM channels WHERE id = :channelId")
    void deleteChannel(String channelId);

    // ── Channel Posts ─────────────────────────────────────────────────────

    /** Live posts for a channel, newest first (excludes scheduled/draft). */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND scheduledAt = 0 AND isDraft = 0 ORDER BY timestamp DESC LIMIT :limit")
    LiveData<List<ChannelPostEntity>> getChannelPosts(String channelId, int limit);

    /** Pinned post for a channel (if any). */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND isPinned = 1 AND isDeleted = 0 LIMIT 1")
    LiveData<ChannelPostEntity> getPinnedPost(String channelId);

    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND isPinned = 1 AND isDeleted = 0 LIMIT 1")
    ChannelPostEntity getPinnedPostSync(String channelId);

    /** Scheduled posts for a channel (admin view). */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND scheduledAt > 0 AND isDeleted = 0 ORDER BY scheduledAt ASC")
    LiveData<List<ChannelPostEntity>> getScheduledPosts(String channelId);

    /** Draft posts for a channel. */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND isDraft = 1 AND isDeleted = 0 ORDER BY timestamp DESC")
    LiveData<List<ChannelPostEntity>> getDraftPosts(String channelId);

    /** Posts by type. */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND type = :type AND isDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    LiveData<List<ChannelPostEntity>> getPostsByType(String channelId, String type, int limit);

    /** Paginated: posts older than cursor timestamp (excludes scheduled/drafts). */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND timestamp < :beforeTimestamp AND scheduledAt = 0 AND isDraft = 0 ORDER BY timestamp DESC LIMIT :limit")
    LiveData<List<ChannelPostEntity>> getChannelPostsBefore(String channelId, long beforeTimestamp, int limit);

    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND scheduledAt = 0 AND isDraft = 0 ORDER BY timestamp DESC LIMIT :limit")
    List<ChannelPostEntity> getChannelPostsSync(String channelId, int limit);

    /** Posts containing a search term. */
    @Query("SELECT * FROM channel_posts WHERE channelId = :channelId AND text LIKE :pattern AND isDeleted = 0 ORDER BY timestamp DESC LIMIT 50")
    List<ChannelPostEntity> searchPosts(String channelId, String pattern);

    @Query("SELECT * FROM channel_posts WHERE id = :postId LIMIT 1")
    ChannelPostEntity getPostSync(String postId);

    @Query("SELECT * FROM channel_posts WHERE id = :postId LIMIT 1")
    LiveData<ChannelPostEntity> getPost(String postId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPost(ChannelPostEntity post);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPosts(List<ChannelPostEntity> posts);

    @Query("UPDATE channel_posts SET isDeleted = 1, text = '' WHERE id = :postId")
    void softDeletePost(String postId);

    @Query("UPDATE channel_posts SET text = :newText, editedAt = :editedAt WHERE id = :postId")
    void updatePostText(String postId, String newText, long editedAt);

    @Query("UPDATE channel_posts SET isPinned = :pinned WHERE id = :postId")
    void setPinned(String postId, boolean pinned);

    @Query("UPDATE channel_posts SET isPinned = 0 WHERE channelId = :channelId")
    void clearAllPinned(String channelId);

    @Query("UPDATE channel_posts SET scheduledAt = 0, timestamp = :publishedAt WHERE id = :postId")
    void publishScheduledPost(String postId, long publishedAt);

    @Query("DELETE FROM channel_posts WHERE channelId = :channelId")
    void deletePostsByChannel(String channelId);

    @Query("UPDATE channel_posts SET viewCount = viewCount + 1 WHERE id = :postId")
    void incrementViewCount(String postId);

    @Query("UPDATE channel_posts SET forwardCount = forwardCount + 1 WHERE id = :postId")
    void incrementForwardCount(String postId);

    @Query("UPDATE channel_posts SET replyCount = replyCount + 1 WHERE id = :postId")
    void incrementReplyCount(String postId);

    @Query("UPDATE channel_posts SET pollVotesJson = :votesJson, pollTotalVotes = :total WHERE id = :postId")
    void updatePollVotes(String postId, String votesJson, int total);

    @Query("UPDATE channel_posts SET reactionsJson = :reactionsJson WHERE id = :postId")
    void updateReactions(String postId, String reactionsJson);

    @Query("UPDATE channel_posts SET allowReactions = :allow WHERE id = :postId")
    void setAllowReactions(String postId, boolean allow);

    @Query("UPDATE channel_posts SET allowForward = :allow WHERE id = :postId")
    void setAllowForward(String postId, boolean allow);

    // ── Maintenance ────────────────────────────────────────────────────────

    /** Prune posts older than given timestamp to save storage. */
    @Query("DELETE FROM channel_posts WHERE timestamp < :beforeTimestamp AND isPinned = 0 AND scheduledAt = 0")
    void pruneOldPosts(long beforeTimestamp);

    /** Total post count for a channel. */
    @Query("SELECT COUNT(*) FROM channel_posts WHERE channelId = :channelId AND isDeleted = 0 AND scheduledAt = 0")
    int getPostCount(String channelId);

    /** Unread post count since lastSeen. */
    @Query("SELECT COUNT(*) FROM channel_posts WHERE channelId = :channelId AND timestamp > :afterTimestamp AND isDeleted = 0 AND scheduledAt = 0")
    int countPostsAfter(String channelId, long afterTimestamp);

    /** Total views across all posts of a channel. */
    @Query("SELECT COALESCE(SUM(viewCount), 0) FROM channel_posts WHERE channelId = :channelId AND isDeleted = 0")
    long getTotalViews(String channelId);

    /** Total forwards across all posts. */
    @Query("SELECT COALESCE(SUM(forwardCount), 0) FROM channel_posts WHERE channelId = :channelId AND isDeleted = 0")
    long getTotalForwards(String channelId);

    /** Scheduled posts ready to publish (scheduledAt <= now). */
    @Query("SELECT * FROM channel_posts WHERE scheduledAt > 0 AND scheduledAt <= :now AND isDeleted = 0")
    List<ChannelPostEntity> getPostsDueForPublishing(long now);
}
