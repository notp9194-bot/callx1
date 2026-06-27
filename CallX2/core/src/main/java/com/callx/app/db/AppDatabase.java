package com.callx.app.db;

import android.content.Context;
import android.util.Log;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.callx.app.cache.EncryptedDbKeyStore;
import com.callx.app.db.dao.CallLogDao;
import com.callx.app.db.dao.ChatDao;
import com.callx.app.db.dao.GroupDao;
import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.dao.ScheduledMessageDao;
import com.callx.app.db.dao.StatusDao;
import com.callx.app.db.dao.UserDao;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.db.entity.ChatEntity;
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
        ScheduledMessageEntity.class  // v28: scheduled chat messages
    },
    version = 19,
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


    static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Feature 13: View Once / Secret Message columns
            db.execSQL("ALTER TABLE messages ADD COLUMN viewOnce INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN viewOnceState TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN openedAt INTEGER DEFAULT NULL");
            // Index: fast lookup for view-once state changes (adapter + SyncWorker)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_viewOnce` " +
                "ON `messages` (`viewOnce`, `viewOnceState`)"
            );
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
                    sInstance = buildDatabase(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private static AppDatabase buildDatabase(Context ctx) {
        // FIX #1: No try/catch — let SQLCipher failures propagate as a crash.
        // A crash is intentional: losing encryption silently is unacceptable
        // in a production messaging app.

        SQLiteDatabase.loadLibs(ctx);

        byte[] passphrase = EncryptedDbKeyStore
                .getInstance(ctx)
                .getDbKeyBytes();

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)  // v19: view-once columns; v18: chatId+timestamp index backfill for upgrade users
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
