package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityGroupLinkEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;

import java.util.List;

/**
 * v30: DAO for the Community offline cache (see CommunityEntity family).
 * Mirrors the GroupDao/ChatDao style already used in this file for the
 * rest of the app — sync WorkerThread methods for the offline-first
 * repository layer, LiveData queries for the UI to observe directly.
 */
@Dao
public interface CommunityDao {

    // ── Community ────────────────────────────────────────────────────────
    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCommunity(CommunityEntity community);

    @WorkerThread
    @Query("SELECT * FROM communities WHERE id = :id LIMIT 1")
    CommunityEntity getCommunitySync(String id);

    @Query("SELECT * FROM communities WHERE id = :id LIMIT 1")
    LiveData<CommunityEntity> observeCommunity(String id);

    @WorkerThread
    @Query("SELECT id FROM communities WHERE ownerUid = :ownerUid LIMIT 1")
    String getCommunityIdByOwnerSync(String ownerUid);

    @WorkerThread
    @Query("DELETE FROM communities WHERE id = :id")
    void deleteCommunity(String id);

    @WorkerThread
    @Query("UPDATE communities SET memberCount = :count WHERE id = :id")
    void updateMemberCount(String id, long count);

    // ── Members ──────────────────────────────────────────────────────────
    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMembers(List<CommunityMemberEntity> members);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMember(CommunityMemberEntity member);

    @Query("SELECT * FROM community_members WHERE communityId = :communityId ORDER BY " +
           "CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, name ASC")
    LiveData<List<CommunityMemberEntity>> observeMembers(String communityId);

    @WorkerThread
    @Query("SELECT * FROM community_members WHERE communityId = :communityId AND uid = :uid LIMIT 1")
    CommunityMemberEntity getMemberSync(String communityId, String uid);

    @WorkerThread
    @Query("DELETE FROM community_members WHERE communityId = :communityId AND uid = :uid")
    void deleteMember(String communityId, String uid);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM community_members WHERE communityId = :communityId")
    int getMemberCountSync(String communityId);

    // ── Group links ──────────────────────────────────────────────────────
    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertGroupLink(CommunityGroupLinkEntity link);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertGroupLinks(List<CommunityGroupLinkEntity> links);

    @Query("SELECT groups.* FROM groups INNER JOIN community_group_links " +
           "ON groups.id = community_group_links.groupId " +
           "WHERE community_group_links.communityId = :communityId " +
           "ORDER BY groups.lastMessageAt DESC")
    LiveData<List<com.callx.app.db.entity.GroupEntity>> observeCommunityGroups(String communityId);

    @WorkerThread
    @Query("DELETE FROM community_group_links WHERE communityId = :communityId AND groupId = :groupId")
    void deleteGroupLink(String communityId, String groupId);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM community_group_links WHERE communityId = :communityId")
    int getGroupCountSync(String communityId);

    // ── Posts / feed / announcements ────────────────────────────────────
    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPost(CommunityPostEntity post);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPosts(List<CommunityPostEntity> posts);

    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = 0 " +
           "ORDER BY createdAt DESC")
    LiveData<List<CommunityPostEntity>> observeFeed(String communityId);

    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = 1 " +
           "ORDER BY pinned DESC, createdAt DESC")
    LiveData<List<CommunityPostEntity>> observeAnnouncements(String communityId);

    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND mediaUrl IS NOT NULL " +
           "ORDER BY createdAt DESC")
    LiveData<List<CommunityPostEntity>> observeMediaPosts(String communityId);

    @WorkerThread
    @Query("SELECT * FROM community_posts WHERE communityId = :communityId AND isAnnouncement = :announcementsOnly " +
           "AND createdAt < :beforeTs ORDER BY createdAt DESC LIMIT :limit")
    List<CommunityPostEntity> getPostsPageSync(String communityId, boolean announcementsOnly, long beforeTs, int limit);

    @WorkerThread
    @Query("SELECT * FROM community_posts WHERE id = :id LIMIT 1")
    CommunityPostEntity getPostSync(String id);

    @WorkerThread
    @Query("UPDATE community_posts SET likeCount = :count WHERE id = :id")
    void updateLikeCount(String id, long count);

    @WorkerThread
    @Query("UPDATE community_posts SET commentCount = :count WHERE id = :id")
    void updateCommentCount(String id, long count);

    @WorkerThread
    @Query("UPDATE community_posts SET pollJson = :pollJson WHERE id = :id")
    void updatePollJson(String id, String pollJson);

    @WorkerThread
    @Query("DELETE FROM community_posts WHERE id = :id")
    void deletePost(String id);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM community_posts WHERE communityId = :communityId AND isAnnouncement = 0")
    int getPostCountSync(String communityId);
}
