package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Production-grade AppLockManager.
 * - Credentials stored in EncryptedSharedPreferences (AES-256-GCM)
 * - PBKDF2WithHmacSHA256 hashing with per-credential salt (310,000 iterations)
 * - Brute-force protection: 5 wrong attempts → progressive lockout
 *   (30 s → 60 s → 2 min → 5 min → 30 min) capped at 30 min
 * - Auto-lock delay (immediately / 1 min / 5 min / 15 min / 1 hr)
 */
public class AppLockManager {

    public static final String NONE        = "none";
    public static final String PIN         = "pin";
    public static final String PATTERN     = "pattern";
    public static final String FINGERPRINT = "fingerprint";

    public static final int PIN_LENGTH    = 6;
    public static final int MAX_ATTEMPTS  = 5;

    // Auto-lock delay options (millis); 0 = immediately
    public static final long DELAY_IMMEDIATELY = 0L;
    public static final long DELAY_1MIN        = 60_000L;
    public static final long DELAY_5MIN        = 5 * 60_000L;
    public static final long DELAY_15MIN       = 15 * 60_000L;
    public static final long DELAY_1HR         = 60 * 60_000L;

    private static final String PREFS_NAME    = "callx_app_lock_v2";
    private static final String KEY_TYPE      = "lock_type";
    private static final String KEY_HASH      = "lock_hash";
    private static final String KEY_SALT      = "lock_salt";
    private static final String KEY_ENABLED   = "lock_enabled";
    private static final String KEY_ATTEMPTS  = "failed_attempts";
    private static final String KEY_LOCKOUT   = "lockout_until_ms";
    private static final String KEY_BIO_FALL  = "biometric_fallback";
    private static final String KEY_DELAY     = "auto_lock_delay_ms";

    // PBKDF2 params
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int PBKDF2_KEY_LEN    = 256; // bits
    private static final int SALT_BYTES        = 16;

    // Lockout schedule (seconds per tier)
    private static final int[] LOCKOUT_SECS = {30, 60, 120, 300, 1800};

    private final SharedPreferences prefs;

    public AppLockManager(@NonNull Context ctx) {
        SharedPreferences sp;
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
            sp = EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Fallback for devices that don't support Keystore (emulators in CI)
            sp = ctx.getSharedPreferences(PREFS_NAME + "_plain", Context.MODE_PRIVATE);
        }
        this.prefs = sp;
    }

    // ── State ─────────────────────────────────────────────────────────────

    public boolean isLockEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public @NonNull String getLockType() {
        return prefs.getString(KEY_TYPE, NONE);
    }

    public @NonNull String getLockTypeLabel() {
        switch (getLockType()) {
            case PIN:         return "PIN (" + PIN_LENGTH + "-digit)";
            case PATTERN:     return "Pattern";
            case FINGERPRINT: return "Fingerprint / Face";
            default:          return "None";
        }
    }

    public boolean isBiometricFallbackEnabled() {
        return prefs.getBoolean(KEY_BIO_FALL, true);
    }

    public void setBiometricFallback(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIO_FALL, enabled).apply();
    }

    public long getAutoLockDelayMs() {
        return prefs.getLong(KEY_DELAY, DELAY_IMMEDIATELY);
    }

    public void setAutoLockDelayMs(long ms) {
        prefs.edit().putLong(KEY_DELAY, ms).apply();
    }

    public @NonNull String getAutoLockDelayLabel() {
        long d = getAutoLockDelayMs();
        if (d == DELAY_IMMEDIATELY) return "Immediately";
        if (d == DELAY_1MIN)        return "After 1 minute";
        if (d == DELAY_5MIN)        return "After 5 minutes";
        if (d == DELAY_15MIN)       return "After 15 minutes";
        if (d == DELAY_1HR)         return "After 1 hour";
        return "Immediately";
    }

    // ── Brute-force protection ────────────────────────────────────────────

    public int getFailedAttempts() {
        return prefs.getInt(KEY_ATTEMPTS, 0);
    }

    /** Returns remaining lockout millis, or 0 if not locked out. */
    public long getRemainingLockoutMs() {
        long until = prefs.getLong(KEY_LOCKOUT, 0);
        long remaining = until - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public boolean isLockedOut() {
        return getRemainingLockoutMs() > 0;
    }

    /** Returns number of remaining attempts before next lockout. */
    public int getRemainingAttempts() {
        return Math.max(0, MAX_ATTEMPTS - getFailedAttempts());
    }

    private void recordFailedAttempt() {
        int attempts = getFailedAttempts() + 1;
        prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply();
        if (attempts >= MAX_ATTEMPTS) {
            int tier = Math.min((attempts / MAX_ATTEMPTS) - 1, LOCKOUT_SECS.length - 1);
            long lockoutMs = LOCKOUT_SECS[tier] * 1000L;
            long until = System.currentTimeMillis() + lockoutMs;
            prefs.edit().putLong(KEY_LOCKOUT, until).apply();
        }
    }

    private void clearFailedAttempts() {
        prefs.edit().putInt(KEY_ATTEMPTS, 0).putLong(KEY_LOCKOUT, 0).apply();
    }

    // ── PIN ───────────────────────────────────────────────────────────────

    public void setPin(@NonNull String pin) {
        byte[] salt = generateSalt();
        String hash = pbkdf2Hash(pin, salt);
        prefs.edit()
            .putString(KEY_TYPE, PIN)
            .putString(KEY_HASH, hash)
            .putString(KEY_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putBoolean(KEY_ENABLED, true)
            .apply();
        clearFailedAttempts();
    }

    /** Returns true if PIN is correct; increments failed counter otherwise. */
    public boolean checkPin(@NonNull String pin) {
        if (isLockedOut()) return false;
        String stored = prefs.getString(KEY_HASH, "");
        String saltB64 = prefs.getString(KEY_SALT, "");
        byte[] salt = saltB64.isEmpty() ? new byte[0] :
            android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP);
        String hash = salt.length == 0 ? sha256Legacy(pin) : pbkdf2Hash(pin, salt);
        boolean ok = hash.equals(stored);
        if (ok) clearFailedAttempts(); else recordFailedAttempt();
        return ok;
    }

    // ── Pattern ───────────────────────────────────────────────────────────

    public void setPattern(@NonNull String patternKey) {
        byte[] salt = generateSalt();
        String hash = pbkdf2Hash(patternKey, salt);
        prefs.edit()
            .putString(KEY_TYPE, PATTERN)
            .putString(KEY_HASH, hash)
            .putString(KEY_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putBoolean(KEY_ENABLED, true)
            .apply();
        clearFailedAttempts();
    }

    public boolean checkPattern(@NonNull String patternKey) {
        if (isLockedOut()) return false;
        String stored = prefs.getString(KEY_HASH, "");
        String saltB64 = prefs.getString(KEY_SALT, "");
        byte[] salt = saltB64.isEmpty() ? new byte[0] :
            android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP);
        String hash = salt.length == 0 ? sha256Legacy(patternKey) : pbkdf2Hash(patternKey, salt);
        boolean ok = hash.equals(stored);
        if (ok) clearFailedAttempts(); else recordFailedAttempt();
        return ok;
    }

    // ── Fingerprint ───────────────────────────────────────────────────────

    public void setFingerprint() {
        prefs.edit()
            .putString(KEY_TYPE, FINGERPRINT)
            .putString(KEY_HASH, "")
            .putString(KEY_SALT, "")
            .putBoolean(KEY_ENABLED, true)
            .apply();
        clearFailedAttempts();
    }

    // ── Clear ─────────────────────────────────────────────────────────────

    public void clearLock() {
        prefs.edit()
            .putString(KEY_TYPE, NONE)
            .putString(KEY_HASH, "")
            .putString(KEY_SALT, "")
            .putBoolean(KEY_ENABLED, false)
            .apply();
        clearFailedAttempts();
    }

    // ── Crypto helpers ────────────────────────────────────────────────────

    private static byte[] generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static @NonNull String pbkdf2Hash(@NonNull String input, @NonNull byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                input.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LEN);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            spec.clearPassword();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return sha256Legacy(input); // safe fallback
        }
    }

    /** Legacy SHA-256 fallback for migrating existing hashes. */
    private static @NonNull String sha256Legacy(@NonNull String input) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] hash = d.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }
}
