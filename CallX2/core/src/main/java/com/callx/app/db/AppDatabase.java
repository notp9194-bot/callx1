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
import com.callx.app.db.dao.StatusDao;
import com.callx.app.db.dao.UserDao;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.db.entity.UserEntity;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SupportFactory;

/**
 * Room Database — main local cache for CallX.
 * Encrypted with SQLCipher AES-256.
 *
 * Version history:
 *   v1  : initial (messages, users, chats)
 *   v2  : call_logs + groups tables
 *   v3  : statuses table
 *   v4  : draft, pendingMarkRead, mediaLocalPath, mediaResourceType
 *   v5  : replyToType + replyToMediaUrl
 *   v6  : thumbUrl + partnerThumb
 *   v7  : senderPhoto
 *   v8  : reelId + reelThumbUrl
 *   v9  : fontStyle
 *   v10 : v20 features — disappearAt, groupReadBy, locationLat/Lng,
 *          liveLocationExpiry, archived, disappearTimer
 */
@Database(
    entities = {
        MessageEntity.class,
        UserEntity.class,
        ChatEntity.class,
        CallLogEntity.class,
        GroupEntity.class,
        StatusEntity.class
    },
    version = 10,
    exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG     = "AppDatabase";
    private static final String DB_NAME = "callx_cache.db";
    private static volatile AppDatabase sInstance;

    public abstract MessageDao messageDao();
    public abstract UserDao    userDao();
    public abstract ChatDao    chatDao();
    public abstract CallLogDao callLogDao();
    public abstract GroupDao   groupDao();
    public abstract StatusDao  statusDao();

    // ──────────────────────────────────────────────────────────────
    // MIGRATIONS
    // ──────────────────────────────────────────────────────────────

    /**
     * v9 → v10 (v20 features):
     *   messages: disappearAt, groupReadBy, locationLat, locationLng, liveLocationExpiry
     *   chats:    archived, disappearTimer
     *   indexes:  disappearAt, text search
     */
    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // MessageEntity v20 columns
            db.execSQL("ALTER TABLE messages ADD COLUMN disappearAt INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN groupReadBy TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN locationLat REAL DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN locationLng REAL DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN liveLocationExpiry INTEGER DEFAULT NULL");

            // ChatEntity v20 columns
            db.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE chats ADD COLUMN disappearTimer INTEGER DEFAULT 0");

            // Performance indexes for new features
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_disappearAt` ON `messages` (`disappearAt`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chats_archived` ON `chats` (`archived`)");

            Log.d("Migration", "9→10 complete: disappearing msgs, group read receipts, location, archive");
        }
    };

    /** v8 → v9: fontStyle per message. */
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN fontStyle INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v7 → v8: reelId + reelThumbUrl for reel_seen bubble. */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reelId TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN reelThumbUrl TEXT DEFAULT NULL");
        }
    };

    /** v6 → v7: senderPhoto for status_seen bubble. */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN senderPhoto TEXT DEFAULT NULL");
        }
    };

    /** v5 → v6: thumbUrl + partnerThumb for fast avatar loading. */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN thumbUrl TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE chats ADD COLUMN partnerThumb TEXT DEFAULT NULL");
        }
    };

    /** v4 → v5: replyToType + replyToMediaUrl (SwipeReply). */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToType TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToMediaUrl TEXT DEFAULT NULL");
        }
    };

    /** v3 → v4: draft, pendingMarkRead, mediaLocalPath, mediaResourceType. */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE chats ADD COLUMN draft TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE chats ADD COLUMN pendingMarkRead INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaLocalPath TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaResourceType TEXT DEFAULT NULL");
        }
    };

    /** v2 → v3: statuses table. */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `statuses` (" +
                "`id` TEXT NOT NULL, `ownerUid` TEXT, `ownerName` TEXT, `ownerPhoto` TEXT, " +
                "`type` TEXT, `text` TEXT, `mediaUrl` TEXT, `thumbnailUrl` TEXT, " +
                "`bgColor` TEXT, `fontStyle` TEXT, `textColor` TEXT, `timestamp` INTEGER, " +
                "`expiresAt` INTEGER, `deleted` INTEGER, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
            );
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_statuses_ownerUid` ON `statuses` (`ownerUid`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_statuses_timestamp` ON `statuses` (`timestamp`)");
        }
    };

    /** v1 → v2: call_logs + groups tables. */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `call_logs` (" +
                "`id` TEXT NOT NULL, `partnerUid` TEXT, `partnerName` TEXT, " +
                "`partnerPhoto` TEXT, `direction` TEXT, `mediaType` TEXT, " +
                "`timestamp` INTEGER, `duration` INTEGER, " +
                "`syncedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
            );
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_logs_timestamp` ON `call_logs` (`timestamp`)");
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `groups` (" +
                "`id` TEXT NOT NULL, `name` TEXT, `description` TEXT, `iconUrl` TEXT, " +
                "`createdBy` TEXT, `lastMessage` TEXT, `lastSenderName` TEXT, " +
                "`lastMessageAt` INTEGER, `syncedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
            );
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_groups_lastMessageAt` ON `groups` (`lastMessageAt`)");
        }
    };

    // ──────────────────────────────────────────────────────────────
    // INSTANCE
    // ──────────────────────────────────────────────────────────────

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
        SQLiteDatabase.loadLibs(ctx);

        byte[] passphrase = EncryptedDbKeyStore.getInstance(ctx).getDbKeyBytes();

        if (passphrase == null || passphrase.length == 0) {
            throw new RuntimeException(
                "[SECURITY] DB encryption key could not be retrieved. " +
                "Refusing to open unencrypted DB. Check EncryptedDbKeyStore logs."
            );
        }

        SupportFactory factory = new SupportFactory(passphrase);

        AppDatabase db = Room.databaseBuilder(ctx, AppDatabase.class, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
                )
                .fallbackToDestructiveMigration()
                .build();

        Log.d(TAG, "AppDatabase v10 (SQLCipher encrypted) ready");
        return db;
    }
}
