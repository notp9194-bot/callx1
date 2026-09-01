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
        SavedMessageEntity.class,
        PaymentTransactionEntity.class,
        PaymentAccountEntity.class,
        PaymentPinEntity.class,
        // Local cache of the viewer's own reel-watch history (v49) —
        // powers the "Just watched" profile-grid overlay.
        ReelWatchHistoryCacheEntity.class,
        // Offline cache for the Trending Audio browser (v50) — instant
        // reopen instead of a fresh Firebase read every time.
        TrendingAudioCacheEntity.class,
        // v54: durable per-chat compound message sync cursors.
        MessageSyncStateEntity.class
    },
    version = 56,
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
    public abstract MessageSyncStateDao messageSyncStateDao();
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
    public abstract PaymentTransactionDao      paymentTransactionDao();
    public abstract PaymentAccountDao          paymentAccountDao();
    public abstract PaymentPinDao              paymentPinDao();
    public abstract ReelWatchHistoryCacheDao   reelWatchHistoryCacheDao();

    // Trending Audio browser offline cache (v50)
    public abstract TrendingAudioCacheDao      trendingAudioCacheDao();

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


    // ─── Migration 38 → 39  (community v34 feature upgrade) ─────────────────
    //
    // community_posts  : mediaUrlsJson, mediaTypesJson, viewCount, bookmarkCount, shareCount
    // community_events : coverImageUrl, interestedCount, notGoingCount, eventType, onlineLink, reminderSet
    // communities      : bannerUrl, rules, category
    //
    static final Migration MIGRATION_38_39 = new Migration(38, 39) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {

            // ── community_posts ──────────────────────────────────────────────
            db.execSQL("ALTER TABLE community_posts ADD COLUMN mediaUrlsJson  TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN mediaTypesJson TEXT");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN viewCount      INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN bookmarkCount  INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_posts ADD COLUMN shareCount     INTEGER NOT NULL DEFAULT 0");

            // ── community_events ─────────────────────────────────────────────
            db.execSQL("ALTER TABLE community_events ADD COLUMN coverImageUrl   TEXT");
            db.execSQL("ALTER TABLE community_events ADD COLUMN interestedCount INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_events ADD COLUMN notGoingCount   INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE community_events ADD COLUMN eventType       TEXT    NOT NULL DEFAULT 'OFFLINE'");
            db.execSQL("ALTER TABLE community_events ADD COLUMN onlineLink      TEXT");
            db.execSQL("ALTER TABLE community_events ADD COLUMN reminderSet     INTEGER NOT NULL DEFAULT 0");

            // ── communities ──────────────────────────────────────────────────
            db.execSQL("ALTER TABLE communities ADD COLUMN bannerUrl TEXT");
            db.execSQL("ALTER TABLE communities ADD COLUMN rules     TEXT");
            db.execSQL("ALTER TABLE communities ADD COLUMN category  TEXT");
        }
    };

    // ─── Migration 39 → 40 ────────────────────────────────────────────────────
    //
    // FIX: MessageEntity.isAnonymous existed in code for a while (anonymous
    // poll voting) but no migration ever ran "ALTER TABLE messages ADD COLUMN
    // isAnonymous" — the DB version was never bumped for it. Room's schema
    // validation therefore always found the "messages" table missing this
    // column vs. the entity, crashing with IllegalStateException on every
    // app start ("Migration didn't properly handle: messages"). This
    // migration finally adds the missing column.
    //
    static final Migration MIGRATION_39_40 = new Migration(39, 40) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN isAnonymous INTEGER");
        }
    };

    // ─── Migration 40 → 41 ────────────────────────────────────────────────────
    //
    // FIX: CommunityEntity.isVerified (owner-verified badge) existed in code
    // but no migration ever added it to the "communities" table — same class
    // of bug as MIGRATION_39_40. Caused IllegalStateException("Migration
    // didn't properly handle: communities") crashing any LiveData query that
    // touches the communities table (e.g. ChatFolderDao live queries that
    // join/observe it).
    //
    static final Migration MIGRATION_40_41 = new Migration(40, 41) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE communities ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 0");
        }
    };

    // ─── Migration 41 → 42 ────────────────────────────────────────────────────
    //
    // FIX: Same class of bug as MIGRATION_39_40 / MIGRATION_40_41. The
    // ChannelEntity and ChannelPostEntity "NEW in v5" fields (isVerified,
    // topicTagsJson, isFollowing on channels; broadcastPriority, event*,
    // pollAnonymous, topicTagsJson, mentionedUidsJson on channel_posts) were
    // added to the entity classes but never had a matching ALTER TABLE,
    // so Room's schema check would eventually crash on these tables too.
    //
    static final Migration MIGRATION_41_42 = new Migration(41, 42) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // ── channels ──────────────────────────────────────────────────────
            db.execSQL("ALTER TABLE channels ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channels ADD COLUMN topicTagsJson TEXT");
            db.execSQL("ALTER TABLE channels ADD COLUMN isFollowing INTEGER NOT NULL DEFAULT 0");

            // ── channel_posts ─────────────────────────────────────────────────
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN broadcastPriority TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN eventTitle TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN eventLocation TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN eventStartAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN eventEndAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN eventImageUrl TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN eventRsvpEnabled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN pollAnonymous INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN topicTagsJson TEXT");
            db.execSQL("ALTER TABLE channel_posts ADD COLUMN mentionedUidsJson TEXT");
        }
    };

    // ─── Migration 42 → 43 ────────────────────────────────────────────────────
    //
    // BUG FIX: MessageEntity.mediaWidth/mediaHeight (the pixel dimensions
    // captured once at send time — see Message#mediaWidth/mediaHeight) were
    // added to the Message model and to ChatMediaController's send path
    // ages ago, but never had a matching Room column. Net effect: the value
    // survived exactly one Firebase round-trip (the freshly-sent bubble
    // still had it in memory) and was then silently dropped the moment the
    // message got cached to Room — any paged/reloaded history fell back to
    // the old decode-then-relayout square-placeholder flash for every image
    // and video bubble. This migration adds the two missing columns so the
    // dimensions now persist across the Room round-trip like every other
    // field.
    //
    static final Migration MIGRATION_42_43 = new Migration(42, 43) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaWidth INTEGER");
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaHeight INTEGER");
        }
    };

    // ─── Migration 43 → 44 ────────────────────────────────────────────────────
    //
    // BUG FIX: MessageEntity.blurHash was present on the Message model and
    // populated at send time by ChatMediaController (right after the thumbnail
    // upload succeeds), but had no matching Room column, so it was silently
    // dropped on every Firebase → Room round-trip.  Receivers never saw the
    // BlurHash placeholder for either images or videos — they got a grey
    // skeleton instead of the instant blurred-color preview.
    //
    // Also adds the @PropertyName("thumbUrl") annotation to
    // MessageEntity.thumbnailUrl so Firebase RTDB deserialises the "thumbUrl"
    // key (written by the sender) into the correct Java field instead of
    // dropping it silently (no DB column change needed for thumbnailUrl itself).
    //
    static final Migration MIGRATION_43_44 = new Migration(43, 44) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN blurHash TEXT");
        }
    };

    /** v45: Scheduled status posting — statuses table gets a scheduledAt column
     *  (> 0 = future auto-publish time, 0 = already live), mirroring channel_posts. */
    static final Migration MIGRATION_44_45 = new Migration(44, 45) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE statuses ADD COLUMN scheduledAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v46: Media E2E (image) — messages table gets a mediaKeyEnc column
     *  (see MessageEntity#mediaKeyEnc / Message#mediaKeyEnc / MediaE2ECrypto). */
    static final Migration MIGRATION_45_46 = new Migration(45, 46) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaKeyEnc TEXT");
        }
    };

    /** v47: Payments foundation — local-first transaction and account cache. */
    static final Migration MIGRATION_46_47 = new Migration(46, 47) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS payment_transactions (" +
                    "id TEXT NOT NULL PRIMARY KEY, ownerUid TEXT NOT NULL, " +
                    "counterpartyUid TEXT, counterpartyName TEXT, counterpartyUpi TEXT, " +
                    "amountPaise INTEGER NOT NULL, currency TEXT NOT NULL DEFAULT 'INR', " +
                    "type TEXT NOT NULL, note TEXT, status TEXT NOT NULL, " +
                    "referenceId TEXT, chatId TEXT, direction TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_payment_transactions_ownerUid_createdAt " +
                    "ON payment_transactions (ownerUid, createdAt)");
            db.execSQL("CREATE TABLE IF NOT EXISTS payment_accounts (" +
                    "id TEXT NOT NULL PRIMARY KEY, ownerUid TEXT NOT NULL, " +
                    "bankName TEXT, maskedAccount TEXT, upiId TEXT, " +
                    "isDefault INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_payment_accounts_ownerUid " +
                    "ON payment_accounts (ownerUid)");
            db.execSQL("CREATE TABLE IF NOT EXISTS payment_pins (" +
                    "ownerUid TEXT NOT NULL PRIMARY KEY, configured INTEGER NOT NULL DEFAULT 0, " +
                    "updatedAt INTEGER NOT NULL)");
        }
    };

    /** v48: Voice Caption on Photo — messages table gets voiceUrl/voiceDuration
     *  columns (see MessageEntity#voiceUrl / Message#voiceUrl). Set only on
     *  1:1 image messages recorded with the mic-hold gesture on
     *  MediaEditActivity's preview screen; null on every other message. */
    static final Migration MIGRATION_47_48 = new Migration(47, 48) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN voiceUrl TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN voiceDuration INTEGER");
        }
    };

    /** v49: "Just watched" reels-grid overlay — local cache table mirroring
     *  each entry the viewer already has in Firebase's reelWatchHistory/{myUid}
     *  (written by ReelSocialController#recordView()), so any profile's Reels
     *  grid can answer "did I already watch this one?" from disk instantly
     *  instead of a Firebase round-trip on every grid open. */
    static final Migration MIGRATION_48_49 = new Migration(48, 49) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS reel_watch_history_cache (" +
                    "reelId TEXT NOT NULL PRIMARY KEY, watchedAt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reel_watch_history_cache_watchedAt " +
                    "ON reel_watch_history_cache (watchedAt)");
        }
    };

    /** v50: offline cache table for ReelTrendingAudioActivity — mirrors the
     *  last-loaded "musicLibrary" and "sounds" pages so the Trending Audio
     *  screen paints instantly on reopen instead of always waiting on a
     *  fresh Firebase read. */
    static final Migration MIGRATION_49_50 = new Migration(49, 50) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS trending_audio_cache (" +
                    "audioId TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "title TEXT, " +
                    "artist TEXT, " +
                    "audioUrl TEXT, " +
                    "previewAudioUrl TEXT, " +
                    "coverUrl TEXT, " +
                    "genre TEXT, " +
                    "mood TEXT, " +
                    "usageCount INTEGER NOT NULL DEFAULT 0, " +
                    "durationMs INTEGER NOT NULL DEFAULT 0, " +
                    "trendingRank INTEGER NOT NULL DEFAULT 0, " +
                    "bpm INTEGER NOT NULL DEFAULT 0, " +
                    "addedAt INTEGER NOT NULL DEFAULT 0, " +
                    "sortOrder INTEGER NOT NULL DEFAULT 0, " +
                    "cachedAt INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(audioId, source))");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trending_audio_cache_source_sortOrder " +
                    "ON trending_audio_cache (source, sortOrder)");
        }
    };

    /** v51: users table gets avatarVersion — bumped on every avatar upload so
     *  AvatarUrlBuilder can append a cache-busting ?v= param even when a
     *  locally cached photoUrl/thumbUrl string is momentarily stale (see
     *  UserEntity#avatarVersion / ProfileActivity#uploadAvatar). */
    static final Migration MIGRATION_50_51 = new Migration(50, 51) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN avatarVersion INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v52: chats table gets partnerAvatarVersion — chat list's version of
     *  the same ?v= cache-busting fix MIGRATION_50_51 gave the users table
     *  (see ChatEntity#partnerAvatarVersion / ChatAvatarBinder). */
    static final Migration MIGRATION_51_52 = new Migration(51, 52) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE chats ADD COLUMN partnerAvatarVersion INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v53: preserve group topic/thread metadata in the offline message cache. */
    static final Migration MIGRATION_52_53 = new Migration(52, 53) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN topicId TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN topicName TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_topicId_timestamp " +
                    "ON messages (chatId, topicId, timestamp)");
        }
    };

    /** v54: durable per-chat (timestamp, messageId) Firebase sync cursor. */
    static final Migration MIGRATION_53_54 = new Migration(53, 54) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS message_sync_state (" +
                    "chatId TEXT NOT NULL, " +
                    "cursorTimestamp INTEGER NOT NULL DEFAULT 0, " +
                    "cursorMessageId TEXT, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(chatId))");
        }
    };

    /**
     * v55: GAP FIX (#1 — true server cursor). Adds the server-assigned
     * per-chat `seq` (see Message#seq / functions/index.js#assignMessageSeq)
     * to both the messages table and the sync-cursor table, so a seq-anchored
     * cursor persists across app restarts instead of needing to be
     * re-derived. Both columns are nullable with no default beyond SQLite's
     * implicit NULL — existing rows simply have no seq until their chat's
     * next delta sync picks one up, exactly the same "not backfilled,
     * upgrades opportunistically" behavior as the server side (see the
     * Cloud Function's BACKWARD COMPAT note).
     */
    static final Migration MIGRATION_54_55 = new Migration(54, 55) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN seq INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_seq " +
                    "ON messages (chatId, seq)");
            db.execSQL("ALTER TABLE message_sync_state ADD COLUMN cursorSeq INTEGER");
        }
    };

    /**
     * v56: BUG FIX — MessageEntity.groupDeliveredByJson / groupReadByJson
     * (Group tick system, comments reference "v26") were added to the
     * entity class but never had a matching Room migration anywhere in
     * this file. Any device that reached the "messages" table via the
     * migration path (i.e. every upgrading install, as opposed to a fresh
     * install that uses the generated CREATE TABLE) therefore ended up
     * with a "messages" table permanently missing these two columns,
     * no matter how many later migrations ran — causing
     * IllegalStateException("Migration didn't properly handle: messages")
     * on every app start. This migration finally adds them.
     */
    static final Migration MIGRATION_55_56 = new Migration(55, 56) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN groupDeliveredByJson TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN groupReadByJson TEXT");
        }
    };

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static final String DB_NAME = "callx_database";

    public static boolean isWarm() { return sInstance != null; }

    // v240 — PERF FIX: real "DB fully open" signal for the splash-screen
    // gate. isWarm() above only tells you Room.databaseBuilder().build()
    // ran — that call is cheap object construction and returns almost
    // instantly, well BEFORE the actual slow part (file open + schema
    // validation + migrations) has happened. A splash gate built on
    // isWarm() would let the splash dismiss immediately and the user
    // would still stare at an empty/loading Chat List for the real
    // 500ms-3sec cost, same as before this fix. This flag is only set
    // TRUE after a real DAO query (see CallxApp's db-warmup thread)
    // actually completes, so it reflects the DB being genuinely ready.
    private static volatile boolean sDbWarmupComplete = false;

    public static boolean isDbWarmupComplete() { return sDbWarmupComplete; }

    public static void markDbWarmupComplete() { sDbWarmupComplete = true; }

    public static AppDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    sInstance = Room.databaseBuilder(
                                    ctx.getApplicationContext(),
                                    AppDatabase.class,
                                    DB_NAME)
                            // v207 — PERF FIX: WAL explicitly forced instead of
                            // relying on Room's default JournalMode.AUTOMATIC.
                            // AUTOMATIC silently falls back to TRUNCATE mode on
                            // low-RAM devices — and TRUNCATE mode makes readers
                            // BLOCK behind an in-progress writer transaction.
                            // That's a direct hit on this app's exact hot path:
                            // ChatsFragment's Room reads (first paint, load-more,
                            // UiCriticalReadExecutor) can land at the same moment
                            // as a Firebase-delta insertChats() write on
                            // AppBgExecutor. Under TRUNCATE that read would stall
                            // until the write's transaction commits; under WAL,
                            // readers and a writer run concurrently — the read
                            // sees the last-committed snapshot and returns
                            // immediately. This is exactly why WhatsApp's own
                            // SQLite layer always runs in WAL mode. Forcing it
                            // here removes the low-RAM-device fallback as a
                            // source of intermittent chat-list jank.
                            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                            .addMigrations(
                                    MIGRATION_30_31, MIGRATION_31_32,
                                    MIGRATION_32_33, MIGRATION_33_34,
                                    MIGRATION_34_35, MIGRATION_35_36,
                                    MIGRATION_36_37, MIGRATION_37_38,
                                    MIGRATION_38_39, MIGRATION_39_40,
                                    MIGRATION_40_41, MIGRATION_41_42,
                                    MIGRATION_42_43, MIGRATION_43_44,
                                    MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47,
                                    MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50,
                                    MIGRATION_50_51, MIGRATION_51_52,
                                    MIGRATION_52_53, MIGRATION_53_54,
                                    MIGRATION_54_55, MIGRATION_55_56)
                            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8,
                                    9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                                    21, 22, 23, 24, 25, 26, 27, 28, 29)
                            .build();
                }
            }
        }
        return sInstance;
    }

    /**
     * FIX-ACCT-SWITCH: closes and deletes the entire local Room DB so the
     * next signed-in user starts from a clean slate.
     *
     * ROOT CAUSE this fixes: chats/messages/groups etc. are cached in one
     * single, device-wide "callx_database" file. None of those entities
     * (ChatEntity, MessageEntity, GroupEntity, ...) carry an ownerUid column
     * — unlike e.g. PaymentAccountEntity, which does scope rows per user.
     * So without wiping, ChatsFragment.loadFromRoom() would read straight
     * from Room after a switch and show the PREVIOUS account's cached
     * chats/messages to whoever just logged in — rows Firebase never
     * happens to overwrite for the new account (e.g. the old user's private
     * chats) would stay visible forever, not just until the next sync.
     *
     * Retrofitting per-row ownerUid scoping across every DAO/repository in
     * the app would be the "proper" long-term fix but touches a huge,
     * high-risk surface. Wiping the whole DB at the exact moment of switch
     * (same place logout already clears presence, biometric state, and
     * ChatSnapshotCache) is the safe, minimal-risk fix and is exactly what
     * WhatsApp/Telegram-style clients do on "log into a different account".
     * The new account simply re-syncs everything fresh from Firebase, same
     * as a normal first login.
     *
     * MUST be called synchronously, BEFORE the new user's login flow can
     * touch getInstance() again (see AuthActivity's EXTRA_FORCE_LOGIN
     * branch) — never fire-and-forget this, or a race lets the new user's
     * first Room read land on the old file mid-delete.
     */
    public static synchronized void wipeForAccountSwitch(Context ctx) {
        if (sInstance != null) {
            try {
                sInstance.close();
            } catch (Exception ignored) {
                // Best-effort — proceed to delete the file regardless.
            }
            sInstance = null;
        }
        // deleteDatabase() also removes the -wal/-shm/-journal companion
        // files Room's WAL journal mode creates, not just the main file.
        ctx.getApplicationContext().deleteDatabase(DB_NAME);
    }
}
