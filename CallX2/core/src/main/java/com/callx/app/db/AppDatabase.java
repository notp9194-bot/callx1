package com.callx.app.db;

import android.content.Context;
import android.util.Log;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.callx.app.cache.EncryptedDbKeyStore;
import com.callx.app.db.dao.CallLogDao;
import com.callx.app.db.dao.ChatDao;
import com.callx.app.db.dao.CommunityDao;
import com.callx.app.db.dao.GroupDao;
import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.dao.ScheduledMessageDao;
import com.callx.app.db.dao.StatusDao;
import com.callx.app.db.dao.UserDao;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityGroupLinkEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.ScheduledMessageEntity;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.db.entity.UserEntity;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SupportFactory;

/**
 * Room Database — main local cache for CallX.
 * Version 1: messages, users, chats tables.
 * Encrypted with SQLCipher AES-256.
 *
 * FIX #1 (HIGH — SECURITY): Removed silent fallback to unencrypted DB.
 *
 *   Old behaviour:
 *     SQLCipher init failure → quietly opens callx_cache.db_plain (plain SQLite)
 *     → all user messages + contacts stored UNENCRYPTED on disk
 *     → user has zero visibility that security was silently downgraded
 *
 *   New behaviour:
 *     SQLCipher init failure → throws RuntimeException with clear message
 *     → app crashes with an obvious stack trace
 *     → developer MUST fix the root cause; no silent data exposure
 *
 *   Why crash instead of fallback?
 *     A messaging app that loses encryption silently is MORE dangerous than
 *     one that crashes loudly. The crash forces an immediate fix before ship.
 *     This mirrors WhatsApp / Signal behaviour.
 *
 * HOW TO ADD A NEW COLUMN:
 *   1. Bump version = 2.
 *   2. Add field to Entity.
 *   3. Write MIGRATION_1_2 (see template below).
 *   4. Add .addMigrations(MIGRATION_1_2) to buildDatabase().
 *   5. Commit the auto-generated app/schemas/.../<version>.json.
 */
@Database(
    entities = {
        MessageEntity.class,
        UserEntity.class,
        ChatEntity.class,
        CallLogEntity.class,
        GroupEntity.class,
        StatusEntity.class,    // v17: status cache
        ScheduledMessageEntity.class,  // v28: scheduled chat messages
        CommunityEntity.class,          // v30: community system
        CommunityMemberEntity.class,    // v30
        CommunityGroupLinkEntity.class, // v30
        CommunityPostEntity.class       // v30
    },
    version = 30,
    exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG     = "AppDatabase";
    private static final String DB_NAME = "callx_cache.db";
    private static volatile AppDatabase sInstance;

    public abstract MessageDao           messageDao();
    public abstract UserDao              userDao();
    public abstract ChatDao              chatDao();
    public abstract CallLogDao           callLogDao();
    public abstract GroupDao             groupDao();
    public abstract StatusDao            statusDao();    // v17
    public abstract ScheduledMessageDao  scheduledMessageDao();  // v28
    public abstract CommunityDao         communityDao();  // v30

    // ──────────────────────────────────────────────────────────────
    // MIGRATIONS
    // ──────────────────────────────────────────────────────────────

    /** v7 → v8: reelId + reelThumbUrl — reel_seen bubble in chat. */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reelId TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN reelThumbUrl TEXT DEFAULT NULL");
        }
    };

    /** v14 → v15: reactionsJson — per-message emoji reactions cache (see
     *  ReactionJsonUtil / ChatReactionController). Mirrors the
     *  editHistoryJson migration shape (v12→v13) above. */
    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT DEFAULT NULL");
        }
    };

    /** v13 → v14: scheduled_messages table — local cache of pending
     *  scheduled chat messages (see ChatScheduledSendController /
     *  ChatScheduledMessageWorker). Mirrors the statuses-table migration
     *  shape (v2→v3) above. */
    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scheduled_messages` (" +
                "`id` TEXT NOT NULL, " +
                "`chatId` TEXT, " +
                "`senderId` TEXT, " +
                "`senderName` TEXT, " +
                "`partnerUid` TEXT, " +
                "`text` TEXT, " +
                "`type` TEXT, " +
                "`fontStyle` INTEGER NOT NULL DEFAULT 0, " +
                "`sendAt` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_scheduled_messages_chatId_sendAt` " +
                "ON `scheduled_messages` (`chatId`, `sendAt`)"
            );
        }
    };

    /** v12 → v13: editHistoryJson — prior text versions for edited messages
     *  (see MessageEditHistoryController / EditHistoryJsonUtil). */
    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN editHistoryJson TEXT DEFAULT NULL");
        }
    };

    /** v11 → v12: advanced polls — pollMultiChoice flag (tick multiple options). */
    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN pollMultiChoice INTEGER DEFAULT NULL");
        }
    };

    /** v10 → v11: poll fields — question, options/votes (JSON), anonymous, closed. */
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN pollQuestion TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN pollOptionsJson TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN pollVotesJson TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN pollAnonymous INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN pollClosed INTEGER DEFAULT NULL");
        }
    };

    /** v9 → v10: expiresAt — disappearing messages support. */
    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER DEFAULT NULL");
        }
    };

    /** v8 → v9: fontStyle — typing style ID (TypingStyleManager.STYLE_*) per message. */
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN fontStyle INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v6 → v7: senderPhoto — avatar URL for status_seen bubble in chat. */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN senderPhoto TEXT DEFAULT NULL");
        }
    };

    /** v5 → v6: thumbUrl — 100×100 WebP avatar thumbnail for fast chat list loading. */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN thumbUrl TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE chats ADD COLUMN partnerThumb TEXT DEFAULT NULL");
        }
    };

    /** v4 → v5: SwipeReplySystem — replyToType + replyToMediaUrl columns in messages. */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToType TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToMediaUrl TEXT DEFAULT NULL");
        }
    };

        /** v3 → v4: v18 offline improvements — draft, pendingMarkRead, mediaLocalPath, mediaResourceType. */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // ChatEntity: draft column (IMPROVEMENT 2)
            db.execSQL("ALTER TABLE chats ADD COLUMN draft TEXT DEFAULT NULL");
            // ChatEntity: pendingMarkRead column (IMPROVEMENT 4)
            db.execSQL("ALTER TABLE chats ADD COLUMN pendingMarkRead INTEGER DEFAULT 0");
            // MessageEntity: mediaLocalPath column (IMPROVEMENT 5)
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaLocalPath TEXT DEFAULT NULL");
            // MessageEntity: mediaResourceType column (IMPROVEMENT 5)
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaResourceType TEXT DEFAULT NULL");
        }
    };

    /** v2 → v3: statuses table add kiya (offline status cache). */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `statuses` (" +
                "`id` TEXT NOT NULL, " +
                "`ownerUid` TEXT, " +
                "`ownerName` TEXT, " +
                "`ownerPhoto` TEXT, " +
                "`type` TEXT, " +
                "`text` TEXT, " +
                "`mediaUrl` TEXT, " +
                "`thumbnailUrl` TEXT, " +
                "`bgColor` TEXT, " +
                "`fontStyle` TEXT, " +
                "`textColor` TEXT, " +
                "`timestamp` INTEGER, " +
                "`expiresAt` INTEGER, " +
                "`deleted` INTEGER, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_statuses_ownerUid` " +
                "ON `statuses` (`ownerUid`)"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_statuses_timestamp` " +
                "ON `statuses` (`timestamp`)"
            );
        }
    };

    /** v1 → v2: call_logs + groups tables add kiye (offline cache). */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `call_logs` (" +
                "`id` TEXT NOT NULL, " +
                "`partnerUid` TEXT, " +
                "`partnerName` TEXT, " +
                "`partnerPhoto` TEXT, " +
                "`direction` TEXT, " +
                "`mediaType` TEXT, " +
                "`timestamp` INTEGER, " +
                "`duration` INTEGER, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_call_logs_timestamp` " +
                "ON `call_logs` (`timestamp`)"
            );
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `groups` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT, " +
                "`description` TEXT, " +
                "`iconUrl` TEXT, " +
                "`createdBy` TEXT, " +
                "`lastMessage` TEXT, " +
                "`lastSenderName` TEXT, " +
                "`lastMessageAt` INTEGER, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_groups_lastMessageAt` " +
                "ON `groups` (`lastMessageAt`)"
            );
        }
    };

    // ──────────────────────────────────────────────────────────────
    // MIGRATIONS — add one per version bump
    //
    // Example (v1 → v2, add 'reactions' column to messages):
    //
    //   static final Migration MIGRATION_1_2 = new Migration(1, 2) {
    //       @Override
    //       public void migrate(@NonNull SupportSQLiteDatabase db) {
    //           db.execSQL(
    //               "ALTER TABLE messages ADD COLUMN reactions TEXT DEFAULT NULL"
    //           );
    //       }
    //   };
    //
    //   Then: .addMigrations(MIGRATION_1_2) in buildDatabase()
    // ──────────────────────────────────────────────────────────────


    /** v15 -> v16: performance indexes on messages(chatId,status) and messages(status).
     *  Speeds up getPendingMessages() / getAllPendingMessages() queries which
     *  previously did a full table scan on every message in the DB. */
    static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId_status` ON `messages` (`chatId`, `status`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_status` ON `messages` (`status`)");
        }
    };

    /** v16 -> v17: reelOwnerUid — lets the chat UI tell apart the reel
     *  *owner* (who should see the "watched your reel" bubble) from the
     *  *viewer* (who should not see their own watch event echoed back).
     *  See MessageAdapter / MessagePagingAdapter getItemViewType(). */
    static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reelOwnerUid TEXT DEFAULT NULL");
        }
    };

    /**
     * v17 → v18: Composite index on messages(chatId, timestamp ASC).
     *
     * WHY THIS MIGRATION EXISTS:
     *   MessageEntity has had @Index(value={"chatId","timestamp"}) since v1,
     *   so fresh installs already carry this index.  However, no migration
     *   ever created it explicitly, meaning every user who upgraded from v1
     *   instead of installing fresh is missing the index entirely.
     *
     * IMPACT OF MISSING INDEX:
     *   MessageDao.getMessagesPagingSource() runs:
     *     SELECT * FROM messages WHERE chatId = ? ORDER BY timestamp ASC
     *   Without the composite index, SQLite falls back to a full table scan
     *   of ALL messages across ALL chats, then sorts — O(n) per page load.
     *   With the index, it does an index range scan for just that chatId,
     *   already in timestamp order — O(log n + page_size), effectively free.
     *   On a device with 50 k+ cached messages the difference is visible as
     *   a blank RecyclerView for 200-400 ms on first open.
     *
     * MIGRATION SAFETY:
     *   IF NOT EXISTS ensures this is idempotent on fresh installs (index
     *   already exists) and on any device that somehow already has it.
     *   No data is altered; this is a pure metadata operation.
     */
    static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Primary Paging query index — chatId equality + timestamp order.
            // Named to match Room's auto-generated convention so Room's schema
            // validator stays happy and does not try to recreate it.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_chatId_timestamp` " +
                "ON `messages` (`chatId`, `timestamp`)"
            );
        }
    };


    static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Feature 2: expiry timer for view-once messages
            db.execSQL("ALTER TABLE messages ADD COLUMN viewOnceExpiresAt INTEGER DEFAULT NULL");
        }
    };

    /** v20 → v21: reel_share card fields — reelShareUrl, reelShareThumb,
     *  reelShareCaption, reelShareUsername, reelShareOwnerPhoto.
     *  Required so forwarded reel cards render correctly from Room cache. */
    static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reelShareUrl TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN reelShareThumb TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN reelShareCaption TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN reelShareUsername TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN reelShareOwnerPhoto TEXT DEFAULT NULL");
        }
    };

    /** v23 → v24: broadcast flag — marks messages delivered via a broadcast
     *  list so ChatActivity can render the 📢 indicator without a live
     *  Firebase read. Nullable INTEGER (SQLite boolean). */
    /** v24 → v25: deliveredAt/readAt columns — Message Info dialog needs these
     *  persisted locally instead of always reading null from Room. */
    static final Migration MIGRATION_24_25 = new Migration(24, 25) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN deliveredAt INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN readAt INTEGER DEFAULT NULL");
        }
    };

    /** v25 → v26: group tick system — per-member delivered/read receipt maps
     *  (JSON, uid → epoch ms), persisted so the group Message Info dialog
     *  survives the Room round-trip / app restart the same way 1:1's
     *  deliveredAt/readAt do (see MIGRATION_24_25). */
    static final Migration MIGRATION_25_26 = new Migration(25, 26) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN groupDeliveredByJson TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN groupReadByJson TEXT DEFAULT NULL");
        }
    };

    /** v28 → v29: group-list read receipts (ticks) + media label cache —
     *  lastMessageType/lastMessageStatus/lastMessageSenderUid/lastMessageId
     *  on the `groups` table (see GroupEntity), group analogue of
     *  MIGRATION_27_28's chats-table columns. Lets the GROUP list render
     *  ✓/✓✓/blue✓✓ ticks and media-type labels straight from Room on cold
     *  start, same as the 1:1 chat list already does. */
    static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE groups ADD COLUMN lastMessageType TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE groups ADD COLUMN lastMessageStatus TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE groups ADD COLUMN lastMessageSenderUid TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE groups ADD COLUMN lastMessageId TEXT DEFAULT NULL");
        }
    };

    /** v29 → v30: Community system tables — communities, community_members,
     *  community_group_links, community_posts (see the CommunityEntity
     *  family + CommunityDao). Community is opt-in: rows only exist for
     *  users who enabled one, mirrors how `groups` rows only exist for
     *  chats the user actually joined. */
    static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `communities` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT, " +
                "`description` TEXT, " +
                "`iconUrl` TEXT, " +
                "`ownerUid` TEXT, " +
                "`memberCount` INTEGER NOT NULL DEFAULT 0, " +
                "`groupCount` INTEGER NOT NULL DEFAULT 0, " +
                "`postCount` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_communities_ownerUid` " +
                "ON `communities` (`ownerUid`)"
            );
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `community_members` (" +
                "`communityId` TEXT NOT NULL, " +
                "`uid` TEXT NOT NULL, " +
                "`name` TEXT, " +
                "`photoUrl` TEXT, " +
                "`role` TEXT, " +
                "`joinedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`communityId`, `uid`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_community_members_communityId` " +
                "ON `community_members` (`communityId`)"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_community_members_uid` " +
                "ON `community_members` (`uid`)"
            );
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `community_group_links` (" +
                "`communityId` TEXT NOT NULL, " +
                "`groupId` TEXT NOT NULL, " +
                "`addedByUid` TEXT, " +
                "`addedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`communityId`, `groupId`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_community_group_links_communityId` " +
                "ON `community_group_links` (`communityId`)"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_community_group_links_groupId` " +
                "ON `community_group_links` (`groupId`)"
            );
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `community_posts` (" +
                "`id` TEXT NOT NULL, " +
                "`communityId` TEXT, " +
                "`authorUid` TEXT, " +
                "`authorName` TEXT, " +
                "`authorPhoto` TEXT, " +
                "`text` TEXT, " +
                "`mediaUrl` TEXT, " +
                "`mediaType` TEXT, " +
                "`isAnnouncement` INTEGER NOT NULL DEFAULT 0, " +
                "`pinned` INTEGER NOT NULL DEFAULT 0, " +
                "`likeCount` INTEGER NOT NULL DEFAULT 0, " +
                "`commentCount` INTEGER NOT NULL DEFAULT 0, " +
                "`pollJson` TEXT, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_community_posts_communityId_isAnnouncement_createdAt` " +
                "ON `community_posts` (`communityId`, `isAnnouncement`, `createdAt`)"
            );
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_community_posts_communityId_mediaUrl` " +
                "ON `community_posts` (`communityId`, `mediaUrl`)"
            );
        }
    };

    /** v27 → v28: chat-list read receipts (ticks) + media label cache —
     *  lastMessageType/lastMessageStatus/lastMessageSenderUid/lastMessageId
     *  on the `chats` table (see ChatEntity). Lets the chat list render
     *  ✓/✓✓/blue✓✓ ticks and "📷 Photo"/"🎤 Voice message" style labels
     *  straight from Room on cold start, before Firebase re-syncs. */
    static final Migration MIGRATION_27_28 = new Migration(27, 28) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE chats ADD COLUMN lastMessageType TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE chats ADD COLUMN lastMessageStatus TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE chats ADD COLUMN lastMessageSenderUid TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE chats ADD COLUMN lastMessageId TEXT DEFAULT NULL");
        }
    };

    /** v26 → v27: BUG FIX — "Seen your status" bubble owner-only fix.
     *  statusOwnerUid/statusOwnerName/statusThumbUrl were never persisted
     *  in Room (unlike the equivalent reelOwnerUid/reelThumbUrl for
     *  reel_seen), so on every cold read from cache the owner-check in
     *  MessagePagingAdapter fell back to senderId (the viewer), flipping
     *  the bubble to show on the viewer's side instead of the owner's. */
    static final Migration MIGRATION_26_27 = new Migration(26, 27) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN statusOwnerUid TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN statusOwnerName TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN statusThumbUrl TEXT DEFAULT NULL");
        }
    };

    static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN broadcast INTEGER DEFAULT NULL");
        }
    };

    /** v22 → v23: contact card share (contactName/Phone/Phone2/PhotoUrl) and
     *  location share (locationLat/Lng/Address) columns — type="contact" /
     *  type="location" messages. */
    static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN contactName TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN contactPhone TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN contactPhone2 TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN contactPhotoUrl TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN locationLat REAL DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN locationLng REAL DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN locationAddress TEXT DEFAULT NULL");
        }
    };

    /** v21 → v22: mediaItemsJson, caption — multi-image/video grouped messages
     *  (type = "multi_media"). Required so the grid layout (1/2/3/4/5+ image
     *  grouping) survives the Room cache round-trip instead of vanishing
     *  after the realtime Firebase listener writes through to Room. */
    static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaItemsJson TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN caption TEXT DEFAULT NULL");
        }
    };

    static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Feature 13: View Once / Secret Message columns
            db.execSQL("ALTER TABLE messages ADD COLUMN viewOnce INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN viewOnceState TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN openedAt INTEGER DEFAULT NULL");
            // NOTE: no manual CREATE INDEX here — an index created in a migration
            // but not declared via @Index on the entity causes Room's startup
            // schema validation to fail (IllegalStateException: "Migration didn't
            // properly handle..."), crashing the entire app on first DB access.
            // The chatId+timestamp index already covers view-once lookups well
            // enough at this scale, so we keep this migration simple and safe.
        }
    };

    /** PERF FIX: lets callers check, without any I/O or synchronization cost
     *  beyond a volatile read, whether the singleton is already built. If
     *  true, getInstance() below is guaranteed non-blocking (just returns
     *  sInstance) and is safe to call directly on the main thread — no
     *  background-thread hop needed. */
    public static boolean isWarm() {
        return sInstance != null;
    }

    public static AppDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    // TraceSectionMetric("DB#getInstance") — measures cold Room+SQLCipher
                    // init time. If this is consistently > 200ms, move the call off the
                    // main thread and show a loading state instead.
                    android.os.Trace.beginSection("DB#getInstance");
                    try {
                        sInstance = buildDatabase(ctx.getApplicationContext());
                    } finally {
                        android.os.Trace.endSection();
                    }
                }
            }
        }
        return sInstance;
    }

    private static AppDatabase buildDatabase(Context ctx) {
        // FIX #1: No try/catch — let SQLCipher failures propagate as a crash.
        // A crash is intentional: losing encryption silently is unacceptable
        // in a production messaging app.

        // TraceSectionMetric("DB#sqlcipherLoad") — SQLiteDatabase.loadLibs() unpacks
        // the native .so on first install; should be < 50ms after first run.
        android.os.Trace.beginSection("DB#sqlcipherLoad");
        SQLiteDatabase.loadLibs(ctx);
        android.os.Trace.endSection();

        // TraceSectionMetric("DB#keyFetch") — EncryptedSharedPreferences AES256 key
        // derivation. First call triggers key generation (~80-150ms), subsequent
        // calls should be < 10ms (key already stored).
        android.os.Trace.beginSection("DB#keyFetch");
        byte[] passphrase = EncryptedDbKeyStore
                .getInstance(ctx)
                .getDbKeyBytes();
        android.os.Trace.endSection();

        if (passphrase == null || passphrase.length == 0) {
            // FIX #1: Key generation failed → explicit crash, not silent fallback
            throw new RuntimeException(
                "[SECURITY] DB encryption key could not be retrieved from " +
                "EncryptedSharedPreferences. Refusing to open unencrypted DB. " +
                "Check EncryptedDbKeyStore logs for root cause."
            );
        }

        SupportFactory factory = new SupportFactory(passphrase);

        // PERF FIX: Dedicated IO executor for Room queries.
        java.util.concurrent.ExecutorService queryExecutor =
                java.util.concurrent.Executors.newFixedThreadPool(4);

        AppDatabase db = Room.databaseBuilder(ctx, AppDatabase.class, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30)  // v30: community system tables
                .fallbackToDestructiveMigration()
                // NOTE: WAL mode removed — SQLCipher 4.5.4 + Room WAL combination
                // causes silent open failures on some devices. The write-batching
                // fix (applyBufferedChanges single transaction) achieves the same
                // goal: one DB write burst per Firebase replay instead of N writes.
                .setQueryExecutor(queryExecutor)
                .build();

        Log.d(TAG, "AppDatabase (SQLCipher encrypted, dedicated executor, exportSchema=true) ready");
        return db;
    }
}
