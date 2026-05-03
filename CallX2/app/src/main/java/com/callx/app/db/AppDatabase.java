package com.callx.app.db;

import android.content.Context;
import android.util.Log;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.callx.app.cache.EncryptedDbKeyStore;
import com.callx.app.db.dao.ChatDao;
import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.dao.UserDao;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.UserEntity;

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
        ChatEntity.class
    },
    version = 1,
    exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG     = "AppDatabase";
    private static final String DB_NAME = "callx_cache.db";
    private static volatile AppDatabase sInstance;

    public abstract MessageDao messageDao();
    public abstract UserDao    userDao();
    public abstract ChatDao    chatDao();

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

        AppDatabase db = Room.databaseBuilder(ctx, AppDatabase.class, DB_NAME)
                .openHelperFactory(factory)
                // Safety net: if a matching Migration is missing, wipe & rebuild
                // rather than crash on a SCHEMA error. Always add explicit
                // migrations before bumping version in production.
                .fallbackToDestructiveMigration()
                .build();

        Log.d(TAG, "AppDatabase (SQLCipher encrypted, exportSchema=true) ready");
        return db;
    }
}
