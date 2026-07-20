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
 * v38: AppDatabase — Room database (WhatsApp/Telegram-level offline-first architecture).
 *
 * Schema bump: 37 → 38
 * Migration adds:
 *   chats table:
 *     - folderId INTEGER (nullable) — which Chat Folder this chat belongs to
 *     - labels TEXT (nullable) — comma-separated label tags
 *   NEW TABLE chat_folders — Telegram-style folder metadata
 *   NEW TABLE saved_messages — global cross-chat saved messages bookmark store
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
        CommunityModerationLogEntity.class,
        // Reels grid offline cache (v33)
        ReelThumbCacheEntity.class,
        // Channels offline cache (v34) ────────────────────────────
        ChannelEntity.class,
        ChannelPostEntity.class,
        // Chat Folders + Saved Messages (v38) ──────────────────────
        ChatFolderEntity.class,
        SavedMessageEntity.class
    },
    version = 38,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase sInstance;

    // ─── DAOs ────────────────────────────────────────────────────────────────

    public abstract UserDao             userDao();
    public abstract ChatDao             chatDao();
    public abstract GroupDao            groupDao();
    public abstract GroupMemberDao      groupMemberDao();
    public abstract MessageDao          messageDao();
    public abstract CallLogDao          callLogDao();
    public abstract ScheduledMessageDao scheduledMessageDao();
    public abstract StatusDao           statusDao();

    // Community original
    public abstract CommunityDao               communityDao();

    // Community v31
    public abstract CommunityJoinRequestDao    communityJoinRequestDao();
    public abstract CommunityEventDao          communityEventDao();
    public abstract CommunityNotificationDao   communityNotificationDao();
    public abstract CommunityScheduledPostDao  communityScheduledPostDao();
    public abstract CommunityModerationLogDao  communityModerationLogDao();

    // Reels grid offline cache (v33)
    public abstract ReelThumbCacheDao          reelThumbCacheDao();

    // Channels offline cache (v34)
    public abstract ChannelDao                 channelDao();

    // Chat Folders + Saved Messages (v38)
    public abstract ChatFolderDao              chatFolderDao();
    public abstract SavedMessageDao            savedMessageDao();

    // ─── Migrations ───────────────────────────────────────────────────────────

    /**
     * Migration 30 → 31
     */
    static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE communities ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE communities ADD COLUMN inviteToken TEXT");
            db.execSQL("ALTER TABLE communities ADD COLUMN inviteEnabled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_members ADD COLUMN badge TEXT");
            db.execSQL("ALTER TABLE community_members ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_members ADD COLUMN isBanned INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN reactionCountsJson TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN myReactionType TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN mentionedUids TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN scheduledAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS group_members ("
                    + "groupId TEXT NOT NULL, uid TEXT NOT NULL, name TEXT, role TEXT, "
                    + "photoUrl TEXT, thumbUrl TEXT, online INTEGER NOT NULL DEFAULT 0, "
                    + "lastSeen INTEGER, joinedAt INTEGER NOT NULL DEFAULT 0, "
                    + "syncedAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(groupId, uid))");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_uid ON group_members (uid)");
            db.execSQL("CREATE TABLE IF NOT EXISTS community_join_requests ("
                    + "id TEXT NOT NULL PRIMARY KEY, communityId TEXT NOT NULL, "
                    + "requesterUid TEXT NOT NULL, requesterName TEXT, requesterPhoto TEXT, "
                    + "status TEXT NOT NULL DEFAULT 'pending', message TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0, processedAt INTEGER NOT NULL DEFAULT 0, "
                    + "processedByUid TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_join_requests_communityId_status "
                    + "ON community_join_requests (communityId, status)");
            db.execSQL("CREATE TABLE IF NOT EXISTS community_events ("
                    + "id TEXT NOT NULL PRIMARY KEY, communityId TEXT NOT NULL, title TEXT, "
                    + "description TEXT, location TEXT, createdByUid TEXT, createdByName TEXT, "
                    + "startTimeMs INTEGER NOT NULL DEFAULT 0, endTimeMs INTEGER NOT NULL DEFAULT 0, "
                    + "rsvpCount INTEGER NOT NULL DEFAULT 0, rsvpJson TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0, syncedAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_events_communityId_startTimeMs "
                    + "ON community_events (communityId, startTimeMs)");
            db.execSQL("CREATE TABLE IF NOT EXISTS community_notifications ("
                    + "id TEXT NOT NULL PRIMARY KEY, targetUid TEXT NOT NULL, communityId TEXT NOT NULL, "
                    + "type TEXT NOT NULL, title TEXT, body TEXT, postId TEXT, fromUid TEXT, "
                    + "fromName TEXT, fromPhoto TEXT, isRead INTEGER NOT NULL DEFAULT 0, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_notifications_targetUid_communityId "
                    + "ON community_notifications (targetUid, communityId)");
            db.execSQL("CREATE TABLE IF NOT EXISTS community_scheduled_posts ("
                    + "id TEXT NOT NULL PRIMARY KEY, communityId TEXT NOT NULL, authorUid TEXT NOT NULL, "
                    + "authorName TEXT, authorPhoto TEXT, text TEXT, mediaUrl TEXT, mediaType TEXT, "
                    + "isAnnouncement INTEGER NOT NULL DEFAULT 0, scheduledAt INTEGER NOT NULL DEFAULT 0, "
                    + "status TEXT NOT NULL DEFAULT 'pending', pollJson TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_scheduled_posts_communityId_status "
                    + "ON community_scheduled_posts (communityId, status)");
            db.execSQL("CREATE TABLE IF NOT EXISTS community_moderation_logs ("
                    + "id TEXT NOT NULL PRIMARY KEY, communityId TEXT NOT NULL, actionByUid TEXT, "
                    + "actionByName TEXT, targetUid TEXT, targetName TEXT, action TEXT NOT NULL, "
                    + "reason TEXT, targetPostId TEXT, createdAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_community_moderation_logs_communityId "
                    + "ON community_moderation_logs (communityId)");
        }
    };

    /** Migration 31 → 32 */
    static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE community_group_links ADD COLUMN accessType TEXT NOT NULL DEFAULT 'OPEN'");
            db.execSQL("ALTER TABLE community_join_requests ADD COLUMN groupId TEXT");
        }
    };

    /** Migration 32 → 33 — reel_thumb_cache table */
    static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS reel_thumb_cache ("
                    + "reelId TEXT NOT NULL PRIMARY KEY, ownerUid TEXT, tab INTEGER NOT NULL DEFAULT 0, "
                    + "thumbUrl TEXT, blurHash TEXT, caption TEXT, duration INTEGER NOT NULL DEFAULT 0, "
                    + "viewsCount INTEGER NOT NULL DEFAULT 0, likesCount INTEGER NOT NULL DEFAULT 0, "
                    + "commentsCount INTEGER NOT NULL DEFAULT 0, timestamp INTEGER NOT NULL DEFAULT 0, "
                    + "sortOrder INTEGER NOT NULL DEFAULT 0, cachedAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reel_thumb_cache_ownerUid_tab_timestamp "
                    + "ON reel_thumb_cache (ownerUid, tab, timestamp)");
        }
    };

    /**
     * Migration 33 → 34
     * Adds channels + channel_posts tables for the offline-first Channels feature.
     * (WhatsApp-level: DB is source of truth, Firebase syncs to it via ChannelRepository)
     */
    static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {

            // ── channels table ────────────────────────────────────────────────
            db.execSQL("CREATE TABLE IF NOT EXISTS channels ("
                    + "id TEXT NOT NULL PRIMARY KEY, "
                    + "name TEXT, "
                    + "description TEXT, "
                    + "iconUrl TEXT, "
                    + "followers INTEGER NOT NULL DEFAULT 0, "
                    + "verified INTEGER NOT NULL DEFAULT 0, "
                    + "category TEXT, "
                    + "ownerUid TEXT, "
                    + "createdAt INTEGER NOT NULL DEFAULT 0, "
                    + "lastPostAt INTEGER NOT NULL DEFAULT 0, "
                    + "lastPostText TEXT, "
                    + "lastPostMediaUrl TEXT, "
                    + "lastPostType TEXT, "
                    + "isFollowed INTEGER NOT NULL DEFAULT 0, "
                    + "syncedAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_ownerUid ON channels (ownerUid)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_followers ON channels (followers)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_lastPostAt ON channels (lastPostAt)");

            // ── channel_posts table ───────────────────────────────────────────
            db.execSQL("CREATE TABLE IF NOT EXISTS channel_posts ("
                    + "id TEXT NOT NULL PRIMARY KEY, "
                    + "channelId TEXT, "
                    + "text TEXT, "
                    + "type TEXT, "
                    + "mediaUrl TEXT, "
                    + "thumbnailUrl TEXT, "
                    + "linkUrl TEXT, "
                    + "linkTitle TEXT, "
                    + "linkDescription TEXT, "
                    + "timestamp INTEGER NOT NULL DEFAULT 0, "
                    + "viewCount INTEGER NOT NULL DEFAULT 0, "
                    + "forwardCount INTEGER NOT NULL DEFAULT 0, "
                    + "reactionsJson TEXT, "
                    + "syncedAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_posts_channelId_timestamp "
                    + "ON channel_posts (channelId, timestamp)");
        }
    };

    /**
     * Migration 34 → 35
     * Adds "archived" column to chats table (Archived Chats feature).
     */
    static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER");
        }
    };

    /**
     * Migration 35 → 36
     * Repairs the "chats" table for devices that already ran a bad
     * 34→35 migration (which added "archived" as NOT NULL DEFAULT 0
     * instead of nullable, causing a Room schema-validation crash).
     * Rebuilds the table with the correct nullable "archived" column,
     * preserving existing data.
     */
    static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS chats_new ("
                    + "chatId TEXT NOT NULL PRIMARY KEY, "
                    + "type TEXT, "
                    + "partnerUid TEXT, "
                    + "partnerName TEXT, "
                    + "partnerPhoto TEXT, "
                    + "partnerThumb TEXT, "
                    + "lastMessage TEXT, "
                    + "lastMessageAt INTEGER, "
                    + "unread INTEGER, "
                    + "muted INTEGER, "
                    + "pinned INTEGER, "
                    + "lastMessageType TEXT, "
                    + "lastMessageStatus TEXT, "
                    + "lastMessageSenderUid TEXT, "
                    + "lastMessageId TEXT, "
                    + "syncedAt INTEGER NOT NULL, "
                    + "draft TEXT, "
                    + "pendingMarkRead INTEGER, "
                    + "archived INTEGER)");
            db.execSQL("INSERT INTO chats_new ("
                    + "chatId, type, partnerUid, partnerName, partnerPhoto, partnerThumb, "
                    + "lastMessage, lastMessageAt, unread, muted, pinned, lastMessageType, "
                    + "lastMessageStatus, lastMessageSenderUid, lastMessageId, syncedAt, "
                    + "draft, pendingMarkRead, archived) "
                    + "SELECT chatId, type, partnerUid, partnerName, partnerPhoto, partnerThumb, "
                    + "lastMessage, lastMessageAt, unread, muted, pinned, lastMessageType, "
                    + "lastMessageStatus, lastMessageSenderUid, lastMessageId, syncedAt, "
                    + "draft, pendingMarkRead, archived FROM chats");
            db.execSQL("DROP TABLE chats");
            db.execSQL("ALTER TABLE chats_new RENAME TO chats");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_lastMessageAt ON chats (lastMessageAt)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_type ON chats (type)");
        }
    };

    /**
     * Migration 36 → 37
     * The Channels v2 feature added many new fields to ChannelEntity and
     * ChannelPostEntity (owner cache, invite info, polls, audio, documents,
     * scheduling, pin/reply/reaction counters, etc.) but the DB version was
     * never bumped, so Room's schema hash no longer matched the actual
     * entity classes. This migration brings the "channels" and
     * "channel_posts" tables up to date with those entities.
     */
    static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // ── channels: new columns ─────────────────────────────────────────
            db.execSQL("ALTER TABLE channels ADD COLUMN ownerName TEXT");
            db.execSQL("ALTER TABLE channels ADD COLUMN ownerIconUrl TEXT");
            db.execSQL("ALTER TABLE channels ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN inviteLink TEXT");
            db.execSQL("ALTER TABLE channels ADD COLUMN inviteCode TEXT");
            db.execSQL("ALTER TABLE channels ADD COLUMN totalPosts INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN totalViews INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN weeklyGrowth INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN pinnedPostId TEXT");
            db.execSQL("ALTER TABLE channels ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN isAdmin INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN unreadCount INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN lastSeenPostTimestamp INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN followersSyncedAt INTEGER NOT NULL DEFAULT 0");

            db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_category ON channels (category)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_isFollowed ON channels (isFollowed)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_weeklyGrowth ON channels (weeklyGrowth)");

            // ── channel_posts: new columns ────────────────────────────────────
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN authorUid TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN authorName TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN authorIconUrl TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN mediaWidth INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN mediaHeight INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN linkImageUrl TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN linkDomain TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN pollQuestion TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN pollOptionsJson TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN pollVotesJson TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN pollTotalVotes INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN pollMultiSelect INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN pollExpiresAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN audioUrl TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN audioDurationMs INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN audioWaveformJson TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN documentUrl TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN documentName TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN documentSizeBytes INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN documentMimeType TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN scheduledAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN isDraft INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN editedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN replyCount INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN allowReactions INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN allowForward INTEGER NOT NULL DEFAULT 1");

            db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_posts_channelId_isDeleted ON channel_posts (channelId, isDeleted)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_posts_channelId_isPinned ON channel_posts (channelId, isPinned)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_posts_channelId_scheduledAt ON channel_posts (channelId, scheduledAt)");
        }
    };

    /**
     * Migration 37 → 38
     * Adds:
     *  - chats.folderId (nullable INTEGER) — Chat Folder assignment
     *  - chats.labels (nullable TEXT) — comma-separated label tags
     *  - NEW TABLE chat_folders — Telegram-style folders
     *  - NEW TABLE saved_messages — global cross-chat bookmarks
     */
    static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // chats: new columns
            db.execSQL("ALTER TABLE chats ADD COLUMN folderId INTEGER");
            db.execSQL("ALTER TABLE chats ADD COLUMN labels TEXT");

            // chat_folders: new table
            db.execSQL("CREATE TABLE IF NOT EXISTS `chat_folders` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + "`name` TEXT,"
                + "`emoji` TEXT,"
                + "`sortOrder` INTEGER NOT NULL DEFAULT 0,"
                + "`chatIdsJson` TEXT,"
                + "`includeContacts` INTEGER NOT NULL DEFAULT 0,"
                + "`includeGroups` INTEGER NOT NULL DEFAULT 0,"
                + "`includeNonContacts` INTEGER NOT NULL DEFAULT 0,"
                + "`includeMuted` INTEGER NOT NULL DEFAULT 0,"
                + "`includeUnreadOnly` INTEGER NOT NULL DEFAULT 0,"
                + "`createdAt` INTEGER NOT NULL DEFAULT 0"
                + ")");

            // saved_messages: new table
            db.execSQL("CREATE TABLE IF NOT EXISTS `saved_messages` ("
                + "`id` TEXT NOT NULL,"
                + "`origChatId` TEXT,"
                + "`chatName` TEXT,"
                + "`isGroup` INTEGER NOT NULL DEFAULT 0,"
                + "`senderUid` TEXT,"
                + "`senderName` TEXT,"
                + "`senderPhoto` TEXT,"
                + "`text` TEXT,"
                + "`type` TEXT,"
                + "`mediaUrl` TEXT,"
                + "`thumbnailUrl` TEXT,"
                + "`fileName` TEXT,"
                + "`duration` INTEGER,"
                + "`origTimestamp` INTEGER,"
                + "`savedAt` INTEGER,"
                + "`note` TEXT,"
                + "`reactionsJson` TEXT,"
                + "`replyToText` TEXT,"
                + "`replyToSenderName` TEXT,"
                + "PRIMARY KEY(`id`)"
                + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_messages_savedAt` ON `saved_messages` (`savedAt`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_messages_origChatId` ON `saved_messages` (`origChatId`)");
        }
    };

    // ─── Singleton ────────────────────────────────────────────────────────────

    public static boolean isWarm() { return sInstance != null; }

    public static AppDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    sInstance = Room.databaseBuilder(
                                    ctx.getApplicationContext(),
                                    AppDatabase.class,
                                    "callx_database")
                            .addMigrations(
                                    MIGRATION_30_31, MIGRATION_31_32,
                                    MIGRATION_32_33, MIGRATION_33_34,
                                    MIGRATION_34_35, MIGRATION_35_36,
                                    MIGRATION_36_37, MIGRATION_37_38)
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
