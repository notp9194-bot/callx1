package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Central security utility manager.
 * Covers: ghost mode, incognito mode, secure notifications, IP protection,
 * auto-delete messages, 2-step verification, login activity log.
 */
public class SecurityManager {

    // Privacy keys
    private static final String KEY_GHOST_MODE        = "ghost_mode";
    private static final String KEY_INCOGNITO_MODE    = "incognito_mode";
    private static final String KEY_STATUS_PRIVACY    = "status_privacy";
    private static final String KEY_ABOUT_PRIVACY     = "about_privacy";
    private static final String KEY_GROUP_ADD_PERM    = "group_add_permission";
    private static final String KEY_CALL_PERM         = "call_permission";
    private static final String KEY_LAST_SEEN         = "lastSeen";
    private static final String KEY_PROFILE_PHOTO     = "profilePhoto";
    private static final String KEY_READ_RECEIPTS     = "readReceipts";
    private static final String KEY_SCREENSHOT_LOCK   = "screenshotLock";

    // Security keys
    private static final String KEY_SECURE_NOTIFS     = "secure_notifications";
    private static final String KEY_IP_PROTECTION     = "ip_address_protection";
    private static final String KEY_AUTO_DELETE_MS    = "auto_delete_messages_ms";
    private static final String KEY_SECURITY_ALERTS   = "security_alert_notifications";
    private static final String KEY_TWO_STEP_ENABLED  = "two_step_enabled";
    private static final String KEY_TWO_STEP_HASH     = "two_step_hash";
    private static final String KEY_TWO_STEP_SALT     = "two_step_salt";
    private static final String KEY_TWO_STEP_HINT     = "two_step_hint";
    private static final String KEY_LOGIN_LOG         = "login_activity_log";

    // Auto-delete options (ms)
    public static final long AUTO_DELETE_OFF  = 0L;
    public static final long AUTO_DELETE_24H  = 24 * 3600_000L;
    public static final long AUTO_DELETE_7D   = 7 * 24 * 3600_000L;
    public static final long AUTO_DELETE_30D  = 30 * 24 * 3600_000L;
    public static final long AUTO_DELETE_90D  = 90 * 24 * 3600_000L;

    // Privacy visibility options
    public static final String VIS_EVERYONE   = "Everyone";
    public static final String VIS_CONTACTS   = "My Contacts";
    public static final String VIS_NOBODY     = "Nobody";

    private static final int MAX_LOG_ENTRIES = 50;

    private final SharedPreferences prefs;

    public SecurityManager(@NonNull Context ctx) {
        SharedPreferences sp;
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
            sp = EncryptedSharedPreferences.create(
                ctx, "callx_security_v2", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            sp = ctx.getSharedPreferences("callx_security_v2_plain", Context.MODE_PRIVATE);
        }
        this.prefs = sp;
    }

    // ── Ghost / Incognito ─────────────────────────────────────────────────

    public boolean isGhostMode()        { return prefs.getBoolean(KEY_GHOST_MODE, false); }
    public void setGhostMode(boolean v) { prefs.edit().putBoolean(KEY_GHOST_MODE, v).apply(); }

    public boolean isIncognitoMode()        { return prefs.getBoolean(KEY_INCOGNITO_MODE, false); }
    public void setIncognitoMode(boolean v) { prefs.edit().putBoolean(KEY_INCOGNITO_MODE, v).apply(); }

    // ── Privacy settings ──────────────────────────────────────────────────

    public boolean isReadReceiptsEnabled()        { return prefs.getBoolean(KEY_READ_RECEIPTS, true); }
    public void setReadReceipts(boolean v)        { prefs.edit().putBoolean(KEY_READ_RECEIPTS, v).apply(); }

    public boolean isScreenshotLockEnabled()      { return prefs.getBoolean(KEY_SCREENSHOT_LOCK, false); }
    public void setScreenshotLock(boolean v)      { prefs.edit().putBoolean(KEY_SCREENSHOT_LOCK, v).apply(); }

    public String getLastSeenVisibility()         { return prefs.getString(KEY_LAST_SEEN, VIS_EVERYONE); }
    public void setLastSeenVisibility(String v)   { prefs.edit().putString(KEY_LAST_SEEN, v).apply(); }

    public String getProfilePhotoVisibility()     { return prefs.getString(KEY_PROFILE_PHOTO, VIS_EVERYONE); }
    public void setProfilePhotoVisibility(String v){ prefs.edit().putString(KEY_PROFILE_PHOTO, v).apply(); }

    public String getStatusPrivacy()              { return prefs.getString(KEY_STATUS_PRIVACY, VIS_EVERYONE); }
    public void setStatusPrivacy(String v)        { prefs.edit().putString(KEY_STATUS_PRIVACY, v).apply(); }

    public String getAboutPrivacy()               { return prefs.getString(KEY_ABOUT_PRIVACY, VIS_EVERYONE); }
    public void setAboutPrivacy(String v)         { prefs.edit().putString(KEY_ABOUT_PRIVACY, v).apply(); }

    public String getGroupAddPermission()         { return prefs.getString(KEY_GROUP_ADD_PERM, VIS_EVERYONE); }
    public void setGroupAddPermission(String v)   { prefs.edit().putString(KEY_GROUP_ADD_PERM, v).apply(); }

    public String getCallPermission()             { return prefs.getString(KEY_CALL_PERM, VIS_EVERYONE); }
    public void setCallPermission(String v)       { prefs.edit().putString(KEY_CALL_PERM, v).apply(); }

    // ── Security settings ─────────────────────────────────────────────────

    public boolean isSecureNotificationsEnabled()     { return prefs.getBoolean(KEY_SECURE_NOTIFS, false); }
    public void setSecureNotifications(boolean v)     { prefs.edit().putBoolean(KEY_SECURE_NOTIFS, v).apply(); }

    public boolean isIpProtectionEnabled()            { return prefs.getBoolean(KEY_IP_PROTECTION, false); }
    public void setIpProtection(boolean v)            { prefs.edit().putBoolean(KEY_IP_PROTECTION, v).apply(); }

    public boolean isSecurityAlertsEnabled()          { return prefs.getBoolean(KEY_SECURITY_ALERTS, true); }
    public void setSecurityAlerts(boolean v)          { prefs.edit().putBoolean(KEY_SECURITY_ALERTS, v).apply(); }

    public long getAutoDeleteMessagesMs()             { return prefs.getLong(KEY_AUTO_DELETE_MS, AUTO_DELETE_OFF); }
    public void setAutoDeleteMessagesMs(long ms)      { prefs.edit().putLong(KEY_AUTO_DELETE_MS, ms).apply(); }

    public @NonNull String getAutoDeleteLabel() {
        long ms = getAutoDeleteMessagesMs();
        if (ms == AUTO_DELETE_OFF)  return "Off";
        if (ms == AUTO_DELETE_24H)  return "24 hours";
        if (ms == AUTO_DELETE_7D)   return "7 days";
        if (ms == AUTO_DELETE_30D)  return "30 days";
        if (ms == AUTO_DELETE_90D)  return "90 days";
        return "Off";
    }

    // ── Two-Step Verification ─────────────────────────────────────────────

    public boolean isTwoStepEnabled()  { return prefs.getBoolean(KEY_TWO_STEP_ENABLED, false); }

    public void setTwoStep(@NonNull String pin, @NonNull String hint) {
        byte[] salt = generateSalt();
        String hash = pbkdf2Hash(pin, salt);
        prefs.edit()
            .putBoolean(KEY_TWO_STEP_ENABLED, true)
            .putString(KEY_TWO_STEP_HASH, hash)
            .putString(KEY_TWO_STEP_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString(KEY_TWO_STEP_HINT, hint)
            .apply();
    }

    public boolean checkTwoStep(@NonNull String pin) {
        String stored = prefs.getString(KEY_TWO_STEP_HASH, "");
        String saltB64 = prefs.getString(KEY_TWO_STEP_SALT, "");
        if (stored.isEmpty()) return false;
        byte[] salt = android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP);
        return pbkdf2Hash(pin, salt).equals(stored);
    }

    public @NonNull String getTwoStepHint() {
        return prefs.getString(KEY_TWO_STEP_HINT, "");
    }

    public void disableTwoStep() {
        prefs.edit()
            .putBoolean(KEY_TWO_STEP_ENABLED, false)
            .putString(KEY_TWO_STEP_HASH, "")
            .putString(KEY_TWO_STEP_SALT, "")
            .putString(KEY_TWO_STEP_HINT, "")
            .apply();
    }

    // ── Login Activity Log ────────────────────────────────────────────────

    public void recordLoginEvent() {
        String device = Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
        String time   = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(new Date());
        try {
            JSONArray log = getLoginLogJson();
            JSONObject entry = new JSONObject();
            entry.put("device", device);
            entry.put("time", time);
            entry.put("ts", System.currentTimeMillis());
            // Insert at beginning
            JSONArray newLog = new JSONArray();
            newLog.put(entry);
            for (int i = 0; i < Math.min(log.length(), MAX_LOG_ENTRIES - 1); i++) {
                newLog.put(log.get(i));
            }
            prefs.edit().putString(KEY_LOGIN_LOG, newLog.toString()).apply();
        } catch (Exception ignored) {}
    }

    public @NonNull List<LoginEvent> getLoginHistory() {
        List<LoginEvent> list = new ArrayList<>();
        try {
            JSONArray arr = getLoginLogJson();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new LoginEvent(
                    o.optString("device", "Unknown device"),
                    o.optString("time", ""),
                    o.optLong("ts", 0),
                    i == 0
                ));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private JSONArray getLoginLogJson() {
        String raw = prefs.getString(KEY_LOGIN_LOG, "[]");
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    public void clearLoginHistory() {
        prefs.edit().putString(KEY_LOGIN_LOG, "[]").apply();
    }

    // ── Crypto helpers ────────────────────────────────────────────────────

    private static byte[] generateSalt() {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        return salt;
    }

    private static @NonNull String pbkdf2Hash(@NonNull String input, @NonNull byte[] salt) {
        try {
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                input.toCharArray(), salt, 310_000, 256);
            javax.crypto.SecretKeyFactory skf =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            spec.clearPassword();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    // ── Data models ───────────────────────────────────────────────────────

    public static class LoginEvent {
        public final String device;
        public final String time;
        public final long   timestamp;
        public final boolean isCurrent;
        public LoginEvent(String device, String time, long ts, boolean isCurrent) {
            this.device = device; this.time = time;
            this.timestamp = ts; this.isCurrent = isCurrent;
        }
    }
}
