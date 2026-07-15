package com.callx.app.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.callx.app.db.dao.*;
import com.callx.app.db.entity.*;

/**
 * v31: AppDatabase — Room database.
 *
 * Schema version bump: 30 → 31
 * Migration adds columns required by the v31 community upgrade:
 *   communities:        isPrivate, inviteToken, inviteEnabled
 *   community_members:  badge, isMuted, isBanned
 *   community_posts:    reactionCountsJson, myReactionType, mentionedUids, scheduledAt
 *
 * New tables (v31):
 *   community_join_requests
 *   community_events
 *   community_notifications
 *   community_scheduled_posts
 *   community_moderation_logs
 */
@Database(
    entities = {
        // User
        UserEntity.class,
        // Chat / Groups
        ChatEntity.class,
        GroupEntity.class,
        GroupMemberEntity.class,
        MessageEntity.class,
        CallLogEntity.class,
        ScheduledMessageEntity.class,
        StatusEntity.class,
        // Community (original)
        CommunityEntity.class,
        CommunityMemberEntity.class,
        CommunityPostEntity.class,
        CommunityGroupLinkEntity.class,
        // Community v31 — new tables
        CommunityJoinRequestEntity.class,
        CommunityEventEntity.class,
        CommunityNotificationEntity.class,
        CommunityScheduledPostEntity.class,
        CommunityModerationLogEntity.class
    },
    version = 31,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase sInstance;

    // ─── DAOs ────────────────────────────────────────────────────────────────

    public abstract UserDao userDao();
    public abstract ChatDao chatDao();
    public abstract GroupDao groupDao();
    public abstract GroupMemberDao groupMemberDao();
    public abstract MessageDao messageDao();
    public abstract CallLogDao callLogDao();
    public abstract ScheduledMessageDao scheduledMessageDao();
    public abstract StatusDao statusDao();

    // Community original
    public abstract CommunityDao communityDao();

    // Community v31
    public abstract CommunityJoinRequestDao communityJoinRequestDao();
    public abstract CommunityEventDao communityEventDao();
    public abstract CommunityNotificationDao communityNotificationDao();
    public abstract CommunityScheduledPostDao communityScheduledPostDao();
    public abstract CommunityModerationLogDao communityModerationLogDao();

    // ─── Migrations ───────────────────────────────────────────────────────────

    /**
     * Migration 30 → 31
     * Adds new columns to existing community tables and creates the 5 new tables.
     */
    static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {

            // ── communities table: privacy + invite link ──────────────────────
            db.execSQL("ALTER TABLE communities ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE communities ADD COLUMN inviteToken TEXT");
            db.execSQL("ALTER TABLE communities ADD COLUMN inviteEnabled INTEGER NOT NULL DEFAULT 0");

            // ── community_members: badge + moderation ─────────────────────────
            db.execSQL("ALTER TABLE community_members ADD COLUMN badge TEXT");
            db.execSQL("ALTER TABLE community_members ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_members ADD COLUMN isBanned INTEGER NOT NULL DEFAULT 0");

            // ── community_posts: reactions + mentions + scheduledAt ───────────
            db.execSQL("ALTER TABLE community_posts ADD COLUMN reactionCountsJson TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN myReactionType TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN mentionedUids TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN scheduledAt INTEGER NOT NULL DEFAULT 0");

            // ── New table: group_members (v31 — offline cache, was missing) ───
            db.execSQL("CREATE TABLE IF NOT EXISTS group_members ("
                    + "groupId TEXT NOT NULL, "
                    + "uid TEXT NOT NULL, "
                    + "name TEXT, "
                    + "role TEXT, "
                    + "photoUrl TEXT, "
                    + "thumbUrl TEXT, "
                    + "online INTEGER NOT NULL DEFAULT 0, "
                    + "lastSeen INTEGER, "
                    + "joinedAt INTEGER NOT NULL DEFAULT 0, "
                    + "syncedAt INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY(groupId, uid))");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId "
                    + "ON group_members (groupId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_uid "
                    + "ON group_members (uid)");

            // ── New table: community_join_requests ────────────────────────────
            db.execSQL("CREATE TABLE IF NOT EXISTS community_join_requests ("
                    + "id TEXT NOT NULL PRIMARY KEY, "
                    + "communityId TEXT NOT NULL, "
                    + "requesterUid TEXT NOT NULL, "
                    + "requesterName TEXT, "
                    + "requesterPhoto TEXT, "
                    + "status TEXT NOT NULL DEFAULT 'pending', "
                    + "message TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0, "
                    + "processedAt INTEGER NOT NULL DEFAULT 0, "
                    + "processedByUid TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_join_requests_communityId_status "
                    + "ON community_join_requests (communityId, status)");

            // ── New table: community_events ───────────────────────────────────
            db.execSQL("CREATE TABLE IF NOT EXISTS community_events ("
                    + "id TEXT NOT NULL PRIMARY KEY, "
                    + "communityId TEXT NOT NULL, "
                    + "title TEXT, "
                    + "description TEXT, "
                    + "location TEXT, "
                    + "createdByUid TEXT, "
                    + "createdByName TEXT, "
                    + "startTimeMs INTEGER NOT NULL DEFAULT 0, "
                    + "endTimeMs INTEGER NOT NULL DEFAULT 0, "
                    + "rsvpCount INTEGER NOT NULL DEFAULT 0, "
                    + "rsvpJson TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0, "
                    + "syncedAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_events_communityId_startTimeMs "
                    + "ON community_events (communityId, startTimeMs)");

            // ── New table: community_notifications ────────────────────────────
            db.execSQL("CREATE TABLE IF NOT EXISTS community_notifications ("
                    + "id TEXT NOT NULL PRIMARY KEY, "
                    + "targetUid TEXT NOT NULL, "
                    + "communityId TEXT NOT NULL, "
                    + "type TEXT NOT NULL, "
                    + "title TEXT, "
                    + "body TEXT, "
                    + "postId TEXT, "
                    + "fromUid TEXT, "
                    + "fromName TEXT, "
                    + "fromPhoto TEXT, "
                    + "isRead INTEGER NOT NULL DEFAULT 0, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_notifications_targetUid_communityId "
                    + "ON community_notifications (targetUid, communityId)");

            // ── New table: community_scheduled_posts ──────────────────────────
            db.execSQL("CREATE TABLE IF NOT EXISTS community_scheduled_posts ("
                    + "id TEXT NOT NULL PRIMARY KEY, "
                    + "communityId TEXT NOT NULL, "
                    + "authorUid TEXT NOT NULL, "
                    + "authorName TEXT, "
                    + "authorPhoto TEXT, "
                    + "text TEXT, "
                    + "mediaUrl TEXT, "
                    + "mediaType TEXT, "
                    + "isAnnouncement INTEGER NOT NULL DEFAULT 0, "
                    + "scheduledAt INTEGER NOT NULL DEFAULT 0, "
                    + "status TEXT NOT NULL DEFAULT 'pending', "
                    + "pollJson TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_scheduled_posts_communityId_status "
                    + "ON community_scheduled_posts (communityId, status)");

            // ── New table: community_moderation_logs ──────────────────────────
            db.execSQL("CREATE TABLE IF NOT EXISTS community_moderation_logs ("
                    + "id TEXT NOT NULL PRIMARY KEY, "
                    + "communityId TEXT NOT NULL, "
                    + "actionByUid TEXT, "
                    + "actionByName TEXT, "
                    + "targetUid TEXT, "
                    + "targetName TEXT, "
                    + "action TEXT NOT NULL, "
                    + "reason TEXT, "
                    + "targetPostId TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_moderation_logs_communityId "
                    + "ON community_moderation_logs (communityId)");
        }
    };

    // ─── Singleton ────────────────────────────────────────────────────────────

    public static AppDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    sInstance = Room.databaseBuilder(
                                    ctx.getApplicationContext(),
                                    AppDatabase.class,
                                    "callx_database")
                            .addMigrations(MIGRATION_30_31)
                            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8,
                                    9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                                    21, 22, 23, 24, 25, 26, 27, 28, 29)
                            .build();
                }
            }
        }
        return sInstance;
    }
}
