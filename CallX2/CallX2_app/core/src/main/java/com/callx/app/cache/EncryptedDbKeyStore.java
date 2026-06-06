package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Encrypted DB Key Store — production-grade key management.
 *
 * FIX #2 (CRITICAL): Explicit UTF-8 charset everywhere.
 *   Old code: new String(passphrase).getBytes()
 *   → On devices where default charset ≠ UTF-8, the byte array produced
 *     differs between app runs → SQLCipher can't open the DB → DATA WIPE.
 *   Fix: StandardCharsets.UTF_8 used explicitly at every conversion point.
 *
 * FIX #2b: Passphrase stored as raw Base64 string and converted back to
 *   bytes using UTF-8 explicitly. Byte arrays are zeroed after use to prevent
 *   key material lingering in heap.
 */
public class EncryptedDbKeyStore {

    private static final String TAG       = "EncryptedDbKeyStore";
    private static final String PREFS     = "callx_db_keystore";
    private static final String KEY_ALIAS = "callx_db_key";
    private static final int    KEY_BYTES = 32;

    private static EncryptedDbKeyStore sInstance;
    private final SharedPreferences mEncryptedPrefs;

    private EncryptedDbKeyStore(Context ctx) {
        SharedPreferences prefs;
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    ctx,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, fallback to plain: "
                    + e.getMessage());
            prefs = ctx.getSharedPreferences(PREFS + "_fallback", Context.MODE_PRIVATE);
        }
        mEncryptedPrefs = prefs;
    }

    public static synchronized EncryptedDbKeyStore getInstance(Context ctx) {
        if (sInstance == null)
            sInstance = new EncryptedDbKeyStore(ctx.getApplicationContext());
        return sInstance;
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the DB encryption passphrase as byte[] for SQLCipher SupportFactory.
     *
     * FIX #2: Explicit UTF-8 — guarantees identical bytes on every device
     * and every app launch. Old code used platform-default charset which
     * differs on some manufacturers (e.g. Xiaomi with GB18030 default).
     *
     * Caller should zero the array after use:
     *   Arrays.fill(key, (byte) 0);
     */
    public byte[] getDbKeyBytes() {
        String stored = mEncryptedPrefs.getString(KEY_ALIAS, null);
        if (stored == null) {
            stored = generateKey();
            mEncryptedPrefs.edit().putString(KEY_ALIAS, stored).apply();
            Log.d(TAG, "New DB encryption key generated and stored securely.");
        }
        // FIX #2: Always use UTF-8 — deterministic across all devices/locales
        return stored.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the passphrase as char[] for legacy SQLCipher APIs.
     * Explicit UTF-8 guaranteed via round-trip through getDbKeyBytes().
     */
    public char[] getDbPassphrase() {
        // Derive from the canonical byte representation
        byte[] bytes = getDbKeyBytes();
        try {
            String s = new String(bytes, StandardCharsets.UTF_8);
            return s.toCharArray();
        } finally {
            Arrays.fill(bytes, (byte) 0); // zero key material after use
        }
    }

    // ─────────────────────────────────────────────────────────────
    // KEY GENERATION
    // ─────────────────────────────────────────────────────────────

    /**
     * Generates a cryptographically random 32-byte key, Base64-encoded.
     * Result is a 44-character ASCII string — safe for all charset conversions.
     */
    private String generateKey() {
        byte[] keyBytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(keyBytes);
        // Use Android's Base64 (available all API levels) with NO_WRAP
        String encoded = android.util.Base64.encodeToString(
                keyBytes, android.util.Base64.NO_WRAP);
        Arrays.fill(keyBytes, (byte) 0); // zero source bytes immediately
        return encoded;
    }
}
