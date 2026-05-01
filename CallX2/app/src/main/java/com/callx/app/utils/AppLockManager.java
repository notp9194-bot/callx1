package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.annotation.NonNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Manages app lock state: type (none/pin/pattern/fingerprint) and hash storage.
 * Credentials are stored as SHA-256 hashes — never plain text.
 */
public class AppLockManager {

    public static final String NONE        = "none";
    public static final String PIN         = "pin";
    public static final String PATTERN     = "pattern";
    public static final String FINGERPRINT = "fingerprint";

    public static final int PIN_LENGTH = 6;

    private static final String PREFS_NAME  = "callx_app_lock";
    private static final String KEY_TYPE    = "lock_type";
    private static final String KEY_HASH    = "lock_hash";
    private static final String KEY_ENABLED = "lock_enabled";

    private final SharedPreferences prefs;

    public AppLockManager(@NonNull Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Whether any lock is active. */
    public boolean isLockEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    /** Current lock type: none / pin / pattern / fingerprint. */
    public @NonNull String getLockType() {
        return prefs.getString(KEY_TYPE, NONE);
    }

    /** Human-readable label for current lock type. */
    public @NonNull String getLockTypeLabel() {
        switch (getLockType()) {
            case PIN:         return "PIN (" + PIN_LENGTH + "-digit)";
            case PATTERN:     return "Pattern";
            case FINGERPRINT: return "Fingerprint / Face";
            default:          return "None";
        }
    }

    /** Save PIN (stored as SHA-256 hash). */
    public void setPin(@NonNull String pin) {
        prefs.edit()
            .putString(KEY_TYPE, PIN)
            .putString(KEY_HASH, sha256(pin))
            .putBoolean(KEY_ENABLED, true)
            .apply();
    }

    /** Verify PIN. */
    public boolean checkPin(@NonNull String pin) {
        String stored = prefs.getString(KEY_HASH, "");
        return stored.equals(sha256(pin));
    }

    /** Save Pattern (list of cell indices as string, stored as hash). */
    public void setPattern(@NonNull String patternKey) {
        prefs.edit()
            .putString(KEY_TYPE, PATTERN)
            .putString(KEY_HASH, sha256(patternKey))
            .putBoolean(KEY_ENABLED, true)
            .apply();
    }

    /** Verify Pattern. */
    public boolean checkPattern(@NonNull String patternKey) {
        String stored = prefs.getString(KEY_HASH, "");
        return stored.equals(sha256(patternKey));
    }

    /** Enable fingerprint lock (no secret needed — biometric API handles it). */
    public void setFingerprint() {
        prefs.edit()
            .putString(KEY_TYPE, FINGERPRINT)
            .putString(KEY_HASH, "")
            .putBoolean(KEY_ENABLED, true)
            .apply();
    }

    /** Remove any lock. */
    public void clearLock() {
        prefs.edit()
            .putString(KEY_TYPE, NONE)
            .putString(KEY_HASH, "")
            .putBoolean(KEY_ENABLED, false)
            .apply();
    }

    private static @NonNull String sha256(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input; // fallback (should never happen)
        }
    }
}
