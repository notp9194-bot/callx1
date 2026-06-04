package com.callx.app.managers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;

/**
 * ChatNotificationManager
 *
 * Per-chat notification settings stored in SharedPreferences.
 * All settings are keyed by chatId so each chat is independent.
 *
 * Settings supported:
 *   ✅ Mute (off / 1hr / 8hr / 1wk / always)
 *   ✅ Custom ringtone (Uri string or DEFAULT or SILENT)
 *   ✅ Vibration pattern (off / default / gentle / strong)
 *   ✅ Notification LED color (default / brand / red / green / blue / none)
 *   ✅ Show preview (message text in notification vs "New message")
 *   ✅ Per-chat notification channel (Android 8+) for OS-level control
 *
 * Usage:
 *   ChatNotificationManager mgr = new ChatNotificationManager(context, chatId);
 *   mgr.setMute(ChatNotificationManager.MUTE_8HR);
 *   boolean muted = mgr.isMuted();
 *   mgr.applyToNotification(builder, chatId);
 */
public class ChatNotificationManager {

    // ── Mute durations (ms) ────────────────────────────────────────────────
    public static final long MUTE_OFF    = 0L;
    public static final long MUTE_1HR    = 3_600_000L;
    public static final long MUTE_8HR    = 28_800_000L;
    public static final long MUTE_1WK    = 604_800_000L;
    public static final long MUTE_ALWAYS = Long.MAX_VALUE;

    // ── Ringtone values ────────────────────────────────────────────────────
    public static final String RINGTONE_DEFAULT = "DEFAULT";
    public static final String RINGTONE_SILENT  = "SILENT";

    // ── Vibration patterns ─────────────────────────────────────────────────
    public static final int VIBRATE_OFF     = 0;
    public static final int VIBRATE_DEFAULT = 1;
    public static final int VIBRATE_GENTLE  = 2;
    public static final int VIBRATE_STRONG  = 3;

    // ── LED colors ─────────────────────────────────────────────────────────
    public static final int LED_DEFAULT = 0;   // system default
    public static final int LED_BRAND   = 1;   // brand_primary (#6C63FF)
    public static final int LED_RED     = 2;
    public static final int LED_GREEN   = 3;
    public static final int LED_BLUE    = 4;
    public static final int LED_NONE    = 5;

    // ── Prefs keys ─────────────────────────────────────────────────────────
    private static final String PREFS_NAME        = "chat_notif_prefs";
    private static final String KEY_MUTE_UNTIL    = "_muteUntil";
    private static final String KEY_RINGTONE      = "_ringtone";
    private static final String KEY_VIBRATE       = "_vibrate";
    private static final String KEY_LED           = "_led";
    private static final String KEY_SHOW_PREVIEW  = "_showPreview";

    // ── State ──────────────────────────────────────────────────────────────
    private final Context          context;
    private final String           chatId;
    private final SharedPreferences prefs;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────

    public ChatNotificationManager(Context context, String chatId) {
        this.context = context.getApplicationContext();
        this.chatId  = chatId;
        this.prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MUTE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Mute this chat for a given duration.
     * @param durationMs use MUTE_OFF to unmute, MUTE_ALWAYS for indefinite
     */
    public void setMute(long durationMs) {
        long until = durationMs == MUTE_OFF    ? 0L
                   : durationMs == MUTE_ALWAYS ? Long.MAX_VALUE
                   : System.currentTimeMillis() + durationMs;
        prefs.edit().putLong(chatId + KEY_MUTE_UNTIL, until).apply();
    }

    /** Returns true if this chat is currently muted. */
    public boolean isMuted() {
        long until = prefs.getLong(chatId + KEY_MUTE_UNTIL, 0L);
        if (until == 0L) return false;
        if (until == Long.MAX_VALUE) return true;
        if (System.currentTimeMillis() < until) return true;
        // Mute expired — auto-clear
        prefs.edit().remove(chatId + KEY_MUTE_UNTIL).apply();
        return false;
    }

    /** Returns remaining mute time in ms, or 0 if not muted. */
    public long getMuteRemainingMs() {
        long until = prefs.getLong(chatId + KEY_MUTE_UNTIL, 0L);
        if (until == Long.MAX_VALUE) return Long.MAX_VALUE;
        long remaining = until - System.currentTimeMillis();
        return remaining > 0 ? remaining : 0L;
    }

    public String getMuteLabel() {
        long remaining = getMuteRemainingMs();
        if (remaining == 0) return "Notifications on";
        if (remaining == Long.MAX_VALUE) return "Muted";
        if (remaining < 3_600_000)  return "Muted " + (remaining / 60_000) + "m";
        if (remaining < 86_400_000) return "Muted " + (remaining / 3_600_000) + "h";
        return "Muted " + (remaining / 86_400_000) + "d";
    }

    public void clearMute() { setMute(MUTE_OFF); }

    // ─────────────────────────────────────────────────────────────────────
    // RINGTONE
    // ─────────────────────────────────────────────────────────────────────

    /** @param uriString use RINGTONE_DEFAULT, RINGTONE_SILENT, or a content:// URI */
    public void setRingtone(String uriString) {
        prefs.edit().putString(chatId + KEY_RINGTONE, uriString).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updateChannel();
    }

    public String getRingtoneUriString() {
        return prefs.getString(chatId + KEY_RINGTONE, RINGTONE_DEFAULT);
    }

    public Uri getRingtoneUri() {
        String s = getRingtoneUriString();
        if (RINGTONE_SILENT.equals(s))  return null;
        if (RINGTONE_DEFAULT.equals(s)) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return Uri.parse(s);
    }

    public String getRingtoneName() {
        String s = getRingtoneUriString();
        if (RINGTONE_SILENT.equals(s))  return "Silent";
        if (RINGTONE_DEFAULT.equals(s)) return "Default";
        try {
            android.media.Ringtone r = RingtoneManager.getRingtone(context, Uri.parse(s));
            if (r != null) return r.getTitle(context);
        } catch (Exception ignored) {}
        return "Custom";
    }

    // ─────────────────────────────────────────────────────────────────────
    // VIBRATION
    // ─────────────────────────────────────────────────────────────────────

    public void setVibrationPattern(int pattern) {
        prefs.edit().putInt(chatId + KEY_VIBRATE, pattern).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updateChannel();
    }

    public int getVibrationPattern() {
        return prefs.getInt(chatId + KEY_VIBRATE, VIBRATE_DEFAULT);
    }

    public long[] getVibrationArray() {
        switch (getVibrationPattern()) {
            case VIBRATE_OFF:    return null;
            case VIBRATE_GENTLE: return new long[]{0, 100, 100, 100};
            case VIBRATE_STRONG: return new long[]{0, 400, 200, 400, 200, 400};
            default:             return new long[]{0, 250, 250, 250}; // DEFAULT
        }
    }

    public String getVibrationLabel() {
        switch (getVibrationPattern()) {
            case VIBRATE_OFF:    return "Off";
            case VIBRATE_GENTLE: return "Gentle";
            case VIBRATE_STRONG: return "Strong";
            default:             return "Default";
        }
    }

    // Play vibration immediately (for preview in settings screen)
    public void previewVibration() {
        long[] pattern = getVibrationArray();
        if (pattern == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) vm.getDefaultVibrator().vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // LED COLOR
    // ─────────────────────────────────────────────────────────────────────

    public void setLedColor(int ledOption) {
        prefs.edit().putInt(chatId + KEY_LED, ledOption).apply();
    }

    public int getLedOption() {
        return prefs.getInt(chatId + KEY_LED, LED_DEFAULT);
    }

    public int getLedArgb() {
        switch (getLedOption()) {
            case LED_BRAND:  return 0xFF6C63FF;
            case LED_RED:    return 0xFFFF0000;
            case LED_GREEN:  return 0xFF00FF00;
            case LED_BLUE:   return 0xFF0000FF;
            case LED_NONE:   return 0;
            default:         return 0xFF6C63FF; // default = brand
        }
    }

    public String getLedLabel() {
        switch (getLedOption()) {
            case LED_BRAND:  return "Purple (Brand)";
            case LED_RED:    return "Red";
            case LED_GREEN:  return "Green";
            case LED_BLUE:   return "Blue";
            case LED_NONE:   return "None";
            default:         return "Default";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PREVIEW
    // ─────────────────────────────────────────────────────────────────────

    public void setShowPreview(boolean show) {
        prefs.edit().putBoolean(chatId + KEY_SHOW_PREVIEW, show).apply();
    }

    public boolean isShowPreview() {
        return prefs.getBoolean(chatId + KEY_SHOW_PREVIEW, true);
    }

    // ─────────────────────────────────────────────────────────────────────
    // APPLY TO NOTIFICATION BUILDER
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Apply all per-chat settings to a NotificationCompat.Builder.
     * Call this just before notification.notify().
     *
     * @param builder   The notification builder to configure
     * @param senderName Sender's display name
     * @param messageText Message body
     */
    public void applyToNotification(NotificationCompat.Builder builder,
                                    String senderName, String messageText) {
        // Muted → don't show notification at all (caller should check isMuted() first)
        // This method assumes mute check is done by caller.

        // Ringtone
        Uri ringtone = getRingtoneUri();
        if (ringtone != null) builder.setSound(ringtone);
        else builder.setSound(null); // silent

        // Vibration
        long[] pattern = getVibrationArray();
        if (pattern != null) builder.setVibrate(pattern);
        else builder.setVibrate(new long[]{0}); // no vibration

        // LED
        int ledColor = getLedArgb();
        if (ledColor != 0) {
            builder.setLights(ledColor, 1000, 2000);
        }

        // Preview
        if (isShowPreview()) {
            builder.setContentText(messageText);
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(messageText));
        } else {
            builder.setContentText("New message from " + senderName);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NOTIFICATION CHANNEL (Android 8+)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Create/update per-chat notification channel (Android 8+).
     * Each chat gets its own channel ID so OS-level controls work.
     * Call on first notification send for this chat.
     */
    public String getOrCreateChannel(String partnerName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "messages";

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return "messages";

        String channelId = "chat_" + chatId;
        String channelName = partnerName + " messages";

        // Check if channel already exists
        if (nm.getNotificationChannel(channelId) != null) return channelId;

        // Create new channel
        NotificationChannel channel = new NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Messages from " + partnerName);

        // Apply ringtone
        Uri ringtone = getRingtoneUri();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if (ringtone != null) channel.setSound(ringtone, attrs);

        // Apply vibration
        long[] pattern = getVibrationArray();
        if (pattern != null) {
            channel.enableVibration(true);
            channel.setVibrationPattern(pattern);
        } else {
            channel.enableVibration(false);
        }

        // Apply LED
        int ledColor = getLedArgb();
        if (ledColor != 0) {
            channel.enableLights(true);
            channel.setLightColor(ledColor);
        }

        nm.createNotificationChannel(channel);
        return channelId;
    }

    /**
     * Update existing channel (called when settings change).
     * On Android 8+, users can override channel settings from OS.
     * Deleting + recreating channel resets to our preferences.
     */
    private void updateChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String channelId = "chat_" + chatId;
        nm.deleteNotificationChannel(channelId);
        // Will be recreated on next notification
    }

    // ─────────────────────────────────────────────────────────────────────
    // RESET ALL
    // ─────────────────────────────────────────────────────────────────────

    public void resetToDefaults() {
        prefs.edit()
                .remove(chatId + KEY_MUTE_UNTIL)
                .remove(chatId + KEY_RINGTONE)
                .remove(chatId + KEY_VIBRATE)
                .remove(chatId + KEY_LED)
                .remove(chatId + KEY_SHOW_PREVIEW)
                .apply();
        updateChannel();
    }

    // ─────────────────────────────────────────────────────────────────────
    // STATIC HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Check mute from outside — e.g. in FCM handler before showing notification. */
    public static boolean isChatMuted(Context ctx, String chatId) {
        return new ChatNotificationManager(ctx, chatId).isMuted();
    }

    public static String formatMuteDuration(long durationMs) {
        if (durationMs == MUTE_OFF)    return "Unmute";
        if (durationMs == MUTE_ALWAYS) return "Always";
        if (durationMs < 3_600_000)   return (durationMs / 60_000) + " minutes";
        if (durationMs < 86_400_000)  return (durationMs / 3_600_000) + " hours";
        return (durationMs / 86_400_000) + " days";
    }
}
