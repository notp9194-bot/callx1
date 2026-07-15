package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityGroupLinkEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.db.entity.GroupEntity;

import java.util.List;

/**
 * v31: CommunityDao — updated with reaction counts, member moderation fields,
 * and media-post query.
 */
@Dao
public interface CommunityDao {

    // ─── Communities ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCommunity(CommunityEntity community);

    @Query("SELECT * FROM communities WHERE id = :communityId LIMIT 1")
    LiveData<CommunityEntity> observeCommunity(String communityId);

    @Query("SELECT * FROM communities WHERE id = :communityId LIMIT 1")
    CommunityEntity getCommunitySync(String communityId);

    @Query("SELECT * FROM communities WHERE ownerUid = :ownerUid LIMIT 1")
    CommunityEntity getCommunityByOwnerSync(String ownerUid);

    @Query("DELETE FROM communities WHERE id = :communityId")
    void deleteCommunity(String communityId);

    // ─── Members ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMember(CommunityMemberEntity member);

    @Query("SELECT * FROM community_members WHERE communityId = :communityId ORDER BY role ASC, name ASC")
    LiveData<List<CommunityMemberEntity>> observeMembers(String communityId);

    @Query("SELECT * FROM community_members WHERE communityId = :communityId ORDER BY role ASC, name ASC")
    List<CommunityMemberEntity> getMembersSync(String communityId);

    /** v32: Community Access System — is this uid a member (and what role)? */
    @Query("SELECT * FROM community_members WHERE communityId = :communityId AND uid = :uid LIMIT 1")
    CommunityMemberEntity getMemberSync(String communityId, String uid);

    @Query("UPDATE community_members SET role = :newRole WHERE communityId = :communityId AND uid = :uid")
    void updateMemberRole(String communityId, String uid, String newRole);

    /** v31: mute / unmute */
    @Query("UPDATE community_members SET isMuted = :muted WHERE communityId = :communityId AND uid = :uid")
    void updateMemberMuted(String communityId, String uid, boolean muted);

    /** v31: assign badge */
    @Query("UPDATE community_members SET badge = :badge WHERE communityId = :communityId AND uid = :uid")
    void updateMemberBadge(String communityId, String uid, String badge);

    @Query("DELETE FROM community_members WHERE communityId = :communityId AND uid = :uid")
    void deleteMember(String communityId, String uid);

    // ─── Posts ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPost(CommunityPostEntity post);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPosts(List<CommunityPostEntity> posts);

    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = 0 ORDER BY createdAt DESC")
    LiveData<List<CommunityPostEntity>> observeFeed(String communityId);

    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = 1 ORDER BY createdAt DESC")
    LiveData<List<CommunityPostEntity>> observeAnnouncements(String communityId);

    // PERF: bounded/windowed variants of the two queries above.
    //
    // Room LiveData is invalidation-based: ANY write to community_posts
    // (a single member liking one post, voting on one poll, etc.) re-runs
    // EVERY currently-observed query against that table and re-emits its
    // full result, regardless of how many rows actually changed. An
    // unbounded `ORDER BY createdAt DESC` feed query means that cost grows
    // with the community's entire lifetime post count — a community that's
    // been active for months can have thousands of locally-synced rows even
    // though the UI only ever shows the most recent ones, so a single like
    // from any member re-queries and re-diffs the whole history on every
    // tap. Capping with LIMIT keeps that cost constant no matter how big
    // the community's history has grown.
    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = 0 ORDER BY createdAt DESC LIMIT :limit")
    LiveData<List<CommunityPostEntity>> observeFeedWindowed(String communityId, int limit);

    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = 1 ORDER BY createdAt DESC LIMIT :limit")
    LiveData<List<CommunityPostEntity>> observeAnnouncementsWindowed(String communityId, int limit);

    /** PERF: keyset ("load older") pagination for posts already cached
     *  locally — same cursor-based technique as MessageKeysetPagingSource
     *  (WHERE createdAt < :beforeCreatedAt instead of an OFFSET), answered
     *  directly by the existing (communityId, isAnnouncement, createdAt)
     *  index. Lets the feed fragment page through everything Room already
     *  has on-device, purely locally, as the user scrolls past the initial
     *  windowed page — no network round-trip needed for history that's
     *  already synced. */
    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = :isAnnouncement AND createdAt < :beforeCreatedAt ORDER BY createdAt DESC LIMIT :limit")
    List<CommunityPostEntity> getOlderPostsSync(String communityId, boolean isAnnouncement, long beforeCreatedAt, int limit);

    /** v31: media gallery — posts that have a mediaUrl */
    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND mediaUrl IS NOT NULL AND mediaUrl != '' ORDER BY createdAt DESC")
    LiveData<List<CommunityPostEntity>> observeMediaPosts(String communityId);

    @Query("SELECT * FROM community_posts WHERE id = :postId LIMIT 1")
    CommunityPostEntity getPostSync(String postId);

    @Query("UPDATE community_posts SET pollJson = :pollJson WHERE id = :postId")
    void updatePollJson(String postId, String pollJson);

    /** v31: update cached reaction counts */
    @Query("UPDATE community_posts SET reactionCountsJson = :json WHERE id = :postId")
    void updateReactionCounts(String postId, String json);

    /** v31: cache the current user's own reaction on a post */
    @Query("UPDATE community_posts SET myReactionType = :reactionType WHERE id = :postId")
    void updateMyReaction(String postId, String reactionType);

    @Query("DELETE FROM community_posts WHERE id = :postId")
    void deletePost(String postId);

    // ─── Group links ─────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertGroupLink(CommunityGroupLinkEntity link);

    @Query("SELECT * FROM community_group_links WHERE communityId = :communityId")
    LiveData<List<CommunityGroupLinkEntity>> observeLinkedGroups(String communityId);

    /** v31: joined view — the actual GroupEntity rows linked to a community (for CommunityGroupsFragment / CommunityAddGroupActivity). */
    @Query("SELECT g.* FROM groups g "
            + "INNER JOIN community_group_links l ON l.groupId = g.id "
            + "WHERE l.communityId = :communityId ORDER BY g.name ASC")
    LiveData<List<GroupEntity>> observeCommunityGroups(String communityId);

    @Query("DELETE FROM community_group_links WHERE communityId = :communityId AND groupId = :groupId")
    void deleteGroupLink(String communityId, String groupId);

    /** v32: Community Access System — accessType lookup before opening a linked group. */
    @Query("SELECT * FROM community_group_links WHERE communityId = :communityId AND groupId = :groupId LIMIT 1")
    CommunityGroupLinkEntity getGroupLinkSync(String communityId, String groupId);

    @Query("UPDATE community_group_links SET accessType = :accessType WHERE communityId = :communityId AND groupId = :groupId")
    void updateGroupAccessType(String communityId, String groupId, String accessType);

    // ─── Helper: groups for a user (for linking) ─────────────────────────────

    @Query("SELECT g.* FROM groups g "
            + "INNER JOIN group_members gm ON gm.groupId = g.id "
            + "WHERE gm.uid = :uid ORDER BY g.name ASC")
    List<GroupEntity> getGroupsForUserSync(String uid);
}
