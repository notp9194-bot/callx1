package com.callx.app.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import com.callx.app.R;
import com.callx.app.group.GroupChatActivity;
import com.callx.app.services.NotificationActionReceiver;
import java.util.Calendar;
import java.util.Map;

/**
 * GroupNotificationHelper — Production-level group notification routing engine.
 *
 * Decision tree (executed per incoming group_message FCM):
 *
 *  1.  Is the group currently open (foreground)?      → suppress notification
 *  2.  Is the user's DND schedule active right now?
 *        a. Is this an @mention AND mentionAlerts=on? → use CHANNEL_GROUP_MENTION (bypasses DND)
 *        b. Else                                      → suppress entirely
 *  3.  Is the group muted (muteUntil > now)?
 *        a. Is this an @mention AND mentionAlerts=on? → use CHANNEL_GROUP_MENTION (bypasses mute)
 *        b. Else                                      → use CHANNEL_GROUPS_MUTED (silent)
 *  4.  Is this flagged isPriority=true by server?     → use CHANNEL_GROUP_PRIORITY (max priority)
 *  5.  Normal message                                 → use CHANNEL_GROUPS
 *
 * Also: creates all five channels on Android O+ with correct settings.
 */
public class GroupNotificationHelper {

    private static final String PREF_PREFIX = Constants.DND_PREFS_PREFIX;

    // Stable notification IDs per group (hash-based)
    public static int notifIdForGroup(String groupId) {
        return ("group_" + groupId).hashCode();
    }

    // ── Channel registration ──────────────────────────────────────────────
    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // 1. Normal groups channel (high priority)
        NotificationChannel groups = new NotificationChannel(
                Constants.CHANNEL_GROUPS,
                "Group Messages",
                NotificationManager.IMPORTANCE_HIGH);
        groups.setDescription("Messages from group chats");
        groups.enableLights(true);
        groups.setLightColor(Color.parseColor("#5B5BF6"));
        groups.enableVibration(true);
        groups.setVibrationPattern(new long[]{0, 250, 100, 250});
        nm.createNotificationChannel(groups);

        // 2. Muted groups channel (min, no sound)
        NotificationChannel muted = new NotificationChannel(
                Constants.CHANNEL_GROUPS_MUTED,
                "Muted Group Messages",
                NotificationManager.IMPORTANCE_MIN);
        muted.setDescription("Silently delivered group messages");
        muted.setSound(null, null);
        muted.enableVibration(false);
        nm.createNotificationChannel(muted);

        // 3. @Mention channel — HIGH importance, always makes a sound
        NotificationChannel mention = new NotificationChannel(
                Constants.CHANNEL_GROUP_MENTION,
                "Group @Mentions",
                NotificationManager.IMPORTANCE_HIGH);
        mention.setDescription("You were mentioned in a group. Always alerts.");
        mention.enableLights(true);
        mention.setLightColor(Color.parseColor("#22D3A6"));
        mention.enableVibration(true);
        mention.setVibrationPattern(new long[]{0, 150, 80, 150, 80, 300});
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        mention.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), aa);
        nm.createNotificationChannel(mention);

        // 4. Priority channel — MAX (reserved for admin-broadcast or urgent messages)
        NotificationChannel priority = new NotificationChannel(
                Constants.CHANNEL_GROUP_PRIORITY,
                "Priority Group Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        priority.setDescription("High-priority group messages or announcements");
        priority.enableLights(true);
        priority.setLightColor(Color.parseColor("#EF4444"));
        priority.enableVibration(true);
        priority.setVibrationPattern(new long[]{0, 300, 100, 300, 100, 500});
        nm.createNotificationChannel(priority);
    }

    // ── Main entry point ──────────────────────────────────────────────────
    /**
     * Show (or suppress) a group message notification based on user's per-group settings.
     *
     * @param ctx       application context
     * @param data      FCM payload data map
     * @param groupIcon downloaded group icon bitmap (may be null)
     * @param myUid     current user's Firebase UID
     */
    public static void handleGroupNotification(Context ctx,
                                               Map<String, String> data,
                                               Bitmap groupIcon,
                                               String myUid) {
        ensureChannels(ctx);

        final String groupId    = data.getOrDefault("groupId", "");
        final String groupName  = data.getOrDefault("groupName", "Group");
        final String senderName = data.getOrDefault(Constants.GROUP_NOTIF_KEY_SENDER,
                data.getOrDefault("fromName", "Member"));
        final String text       = data.getOrDefault("text", "New message");
        final String groupIcon2 = data.getOrDefault("groupIcon", "");
        final boolean isMention = "true".equals(data.get(Constants.GROUP_NOTIF_KEY_MENTION));
        final boolean isPriority = "true".equals(data.get(Constants.GROUP_NOTIF_KEY_PRIORITY));

        if (groupId.isEmpty()) return;

        SharedPreferences prefs = ctx.getSharedPreferences(PREF_PREFIX + groupId,
                Context.MODE_PRIVATE);

        // ── 1. DND check ──────────────────────────────────────────────────
        if (isDNDActive(prefs)) {
            boolean mentionAlert = prefs.getBoolean("mention_alert", true);
            if (isMention && mentionAlert) {
                // Mention bypass: always fire even during DND
                fire(ctx, prefs, groupId, groupName, senderName, text,
                        groupIcon, isMention, isPriority,
                        Constants.CHANNEL_GROUP_MENTION, data);
            }
            // Otherwise suppress during DND
            return;
        }

        // ── 2. Mute check ─────────────────────────────────────────────────
        long muteUntil = prefs.getLong("mute_until", 0);
        boolean isMuted = muteUntil == Long.MAX_VALUE
                || (muteUntil > 0 && muteUntil > System.currentTimeMillis());
        if (isMuted) {
            boolean mentionAlert = prefs.getBoolean("mention_alert", true);
            if (isMention && mentionAlert) {
                fire(ctx, prefs, groupId, groupName, senderName, text,
                        groupIcon, true, isPriority,
                        Constants.CHANNEL_GROUP_MENTION, data);
            } else {
                fire(ctx, prefs, groupId, groupName, senderName, text,
                        groupIcon, false, false,
                        Constants.CHANNEL_GROUPS_MUTED, data);
            }
            return;
        }

        // ── 3. Priority / normal ──────────────────────────────────────────
        String channel = isPriority
                ? Constants.CHANNEL_GROUP_PRIORITY
                : (isMention ? Constants.CHANNEL_GROUP_MENTION : Constants.CHANNEL_GROUPS);

        fire(ctx, prefs, groupId, groupName, senderName, text,
                groupIcon, isMention, isPriority, channel, data);
    }

    // ── Build & fire the notification ─────────────────────────────────────
    private static void fire(Context ctx,
                             SharedPreferences prefs,
                             String groupId,
                             String groupName,
                             String senderName,
                             String text,
                             Bitmap groupIcon,
                             boolean isMention,
                             boolean isPriority,
                             String channel,
                             Map<String, String> data) {

        boolean showPreview = prefs.getBoolean("notif_preview", true);
        String  displayText = showPreview
                ? senderName + ": " + text
                : "New group message";

        if (isMention && showPreview) {
            displayText = "\uD83D\uDCE2 " + senderName + " mentioned you";
        }

        // ── Open group chat tap intent ────────────────────────────────────
        Intent openIntent = new Intent(ctx, GroupChatActivity.class);
        openIntent.putExtra(Constants.EXTRA_GROUP_ID,   groupId);
        openIntent.putExtra(Constants.EXTRA_GROUP_NAME, groupName);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx,
                notifIdForGroup(groupId), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Inline reply action ───────────────────────────────────────────
        RemoteInput remoteInput = new RemoteInput.Builder(Constants.KEY_GROUP_TEXT_REPLY)
                .setLabel("Reply to " + groupName)
                .build();
        Intent replyIntent = new Intent(ctx, NotificationActionReceiver.class);
        replyIntent.setAction(Constants.ACTION_GROUP_REPLY);
        replyIntent.putExtra(Constants.EXTRA_GROUP_ID,   groupId);
        replyIntent.putExtra(Constants.EXTRA_GROUP_NAME, groupName);
        replyIntent.putExtra(Constants.EXTRA_NOTIF_ID,   notifIdForGroup(groupId));
        PendingIntent replyPi = PendingIntent.getBroadcast(ctx,
                notifIdForGroup(groupId) + 100, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_send, "Reply", replyPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build();

        // ── Mark read action ──────────────────────────────────────────────
        Intent markReadIntent = new Intent(ctx, NotificationActionReceiver.class);
        markReadIntent.setAction(Constants.ACTION_GROUP_MARK_READ);
        markReadIntent.putExtra(Constants.EXTRA_GROUP_ID,  groupId);
        markReadIntent.putExtra(Constants.EXTRA_NOTIF_ID,  notifIdForGroup(groupId));
        PendingIntent markReadPi = PendingIntent.getBroadcast(ctx,
                notifIdForGroup(groupId) + 200, markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Mute this group action ────────────────────────────────────────
        Intent muteIntent = new Intent(ctx, NotificationActionReceiver.class);
        muteIntent.setAction(Constants.ACTION_GROUP_MUTE);
        muteIntent.putExtra(Constants.EXTRA_GROUP_ID,  groupId);
        muteIntent.putExtra(Constants.EXTRA_NOTIF_ID,  notifIdForGroup(groupId));
        PendingIntent mutePi = PendingIntent.getBroadcast(ctx,
                notifIdForGroup(groupId) + 300, muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Custom notification sound (if set) ────────────────────────────
        String toneUriStr = prefs.getString("notif_tone", null);
        Uri toneUri = null;
        if (toneUriStr != null && !"none".equals(toneUriStr)) {
            toneUri = Uri.parse(toneUriStr);
        }

        // ── Build notification ────────────────────────────────────────────
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(isMention ? R.drawable.ic_person : R.drawable.ic_message_notification)
                .setContentTitle(isMention ? "\uD83D\uDCE2 " + groupName : groupName)
                .setContentText(displayText)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(displayText)
                        .setSummaryText(groupName))
                .setPriority(channel.equals(Constants.CHANNEL_GROUP_PRIORITY)
                        ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setGroup(Constants.GROUP_KEY_GROUPS)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .addAction(replyAction)
                .addAction(0, "Mark Read", markReadPi)
                .addAction(0, "Mute", mutePi);

        // Large icon (group icon bitmap)
        if (groupIcon != null) {
            b.setLargeIcon(groupIcon);
        }

        // Custom sound
        if (toneUri != null && !Constants.CHANNEL_GROUPS_MUTED.equals(channel)) {
            b.setSound(toneUri);
        }

        // @mention: add distinct colour flash
        if (isMention) {
            b.setLights(Color.parseColor("#22D3A6"), 300, 1000);
            b.setVibrate(new long[]{0, 150, 80, 150, 80, 300});
        }

        // Priority: vibrate harder
        if (isPriority) {
            b.setLights(Color.parseColor("#EF4444"), 400, 800);
            b.setVibrate(new long[]{0, 400, 150, 400, 150, 600});
        }

        // Muted channel: no sound, no vibrate
        if (Constants.CHANNEL_GROUPS_MUTED.equals(channel)) {
            b.setSound(null);
            b.setVibrate(new long[]{0});
            b.setPriority(NotificationCompat.PRIORITY_LOW);
        }

        // Summary notification (group stack)
        buildGroupSummary(ctx);

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(notifIdForGroup(groupId), b.build());
        }
    }

    // ── Summary / stack notification ──────────────────────────────────────
    private static void buildGroupSummary(Context ctx) {
        Intent i = new Intent(ctx, com.callx.app.activities.MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification summary = new NotificationCompat.Builder(ctx, Constants.CHANNEL_GROUPS)
                .setSmallIcon(R.drawable.ic_message_notification)
                .setGroup(Constants.GROUP_KEY_GROUPS)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(-("groups_summary".hashCode()), summary);
    }

    // ── DND helpers ───────────────────────────────────────────────────────
    /**
     * Returns true if the current time falls within the user's per-group DND window.
     */
    public static boolean isDNDActive(SharedPreferences prefs) {
        String dndFrom = prefs.getString("dnd_from", null);
        String dndTo   = prefs.getString("dnd_to",   null);
        if (dndFrom == null || dndFrom.isEmpty()) return false;
        if (dndTo   == null || dndTo.isEmpty())   return false;

        try {
            int fromH = Integer.parseInt(dndFrom.split(":")[0]);
            int fromM = Integer.parseInt(dndFrom.split(":")[1]);
            int toH   = Integer.parseInt(dndTo.split(":")[0]);
            int toM   = Integer.parseInt(dndTo.split(":")[1]);

            Calendar now = Calendar.getInstance();
            int nowH = now.get(Calendar.HOUR_OF_DAY);
            int nowM = now.get(Calendar.MINUTE);

            int nowTotal  = nowH  * 60 + nowM;
            int fromTotal = fromH * 60 + fromM;
            int toTotal   = toH   * 60 + toM;

            if (fromTotal < toTotal) {
                // e.g. 10:00 – 18:00 (same day)
                return nowTotal >= fromTotal && nowTotal < toTotal;
            } else {
                // e.g. 22:00 – 07:00 (overnight)
                return nowTotal >= fromTotal || nowTotal < toTotal;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the current group is muted right now.
     */
    public static boolean isGroupMuted(Context ctx, String groupId) {
        SharedPreferences prefs = ctx.getSharedPreferences(
                PREF_PREFIX + groupId, Context.MODE_PRIVATE);
        long muteUntil = prefs.getLong("mute_until", 0);
        if (muteUntil == 0) return false;
        if (muteUntil == Long.MAX_VALUE) return true;
        return muteUntil > System.currentTimeMillis();
    }

    /**
     * Detect if the message text contains "@displayName" for the given user.
     * Checks both exact display name and "all" wildcard (@everyone, @all).
     */
    public static boolean detectMention(String messageText, String myDisplayName) {
        if (messageText == null || messageText.isEmpty()) return false;
        String lower = messageText.toLowerCase();
        if (lower.contains("@everyone") || lower.contains("@all")) return true;
        if (myDisplayName != null && !myDisplayName.isEmpty()) {
            String atName = "@" + myDisplayName.toLowerCase().replace(" ", "");
            String atNameSpace = "@" + myDisplayName.toLowerCase();
            return lower.contains(atName) || lower.contains(atNameSpace);
        }
        return false;
    }

      // ─────────────────────────────────────────────────────────────────────
      // @MENTION PRIORITY NOTIFICATION (high-priority, separate channel)
      // ─────────────────────────────────────────────────────────────────────
      public static void showMentionNotification(Context ctx, String groupId, String groupName,
                                                 String groupIcon, String senderUid,
                                                 String senderName, String senderPhoto,
                                                 String messageText, String myUid) {
          if (!detectMention(messageText, FirebaseUtils.getCurrentName())) return;
          QuietHoursManager qhm = new QuietHoursManager(ctx);
          if (qhm.shouldSuppress("group")) return;

          String channelId = Constants.CHANNEL_GROUP_MENTION;
          String title     = groupName + " 🔔 @mention";
          String body      = senderName + ": " + messageText;
          int    notifId   = ("mention_" + groupId + senderUid).hashCode() & 0x7FFFFFFF;

          Intent openIntent = new Intent();
          openIntent.setClassName(ctx, "com.callx.app.group.GroupChatActivity");
          openIntent.putExtra(Constants.EXTRA_GROUP_ID,   groupId);
          openIntent.putExtra(Constants.EXTRA_GROUP_NAME, groupName);
          openIntent.putExtra(Constants.EXTRA_IS_MENTION, true);
          openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
          PendingIntent contentPi = PendingIntent.getActivity(ctx, notifId, openIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          // Per-group mute from notification
          Intent muteIntent = new Intent(ctx, com.callx.app.services.NotificationActionReceiver.class)
              .setAction(Constants.ACTION_GROUP_MUTE)
              .putExtra(Constants.EXTRA_GROUP_ID,   groupId)
              .putExtra(Constants.EXTRA_NOTIF_ID,   notifId)
              .putExtra(Constants.EXTRA_GROUP_NAME, groupName);
          PendingIntent mutePi = PendingIntent.getBroadcast(ctx, ("mute_" + groupId).hashCode(),
              muteIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          new Thread(() -> {
              Bitmap avatar = downloadAvatar(senderPhoto);
              NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channelId)
                  .setSmallIcon(R.drawable.ic_message_notification)
                  .setContentTitle(title)
                  .setContentText(body)
                  .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                  .setPriority(NotificationCompat.PRIORITY_HIGH)
                  .setAutoCancel(true)
                  .setContentIntent(contentPi)
                  .addAction(R.drawable.ic_volume_off, "🔇 Mute Group", mutePi);
              if (avatar != null) b.setLargeIcon(avatar);

              NotificationManager nm = ctx.getSystemService(NotificationManager.class);
              if (nm != null) nm.notify(notifId, b.build());
          }).start();
      }

      // ─────────────────────────────────────────────────────────────────────
      // GROUP MEMBER JOIN NOTIFICATION
      // ─────────────────────────────────────────────────────────────────────
      public static void showMemberJoinedNotification(Context ctx, String groupId,
                                                      String groupName, String newMemberName,
                                                      String newMemberPhoto) {
          QuietHoursManager qhm = new QuietHoursManager(ctx);
          if (qhm.shouldSuppress("group")) return;

          String title   = newMemberName + " joined " + groupName;
          String body    = "Say hi to your new group member!";
          int    notifId = ("join_" + groupId + newMemberName).hashCode() & 0x7FFFFFFF;

          Intent openIntent = new Intent();
          openIntent.setClassName(ctx, "com.callx.app.group.GroupChatActivity");
          openIntent.putExtra(Constants.EXTRA_GROUP_ID,   groupId);
          openIntent.putExtra(Constants.EXTRA_GROUP_NAME, groupName);
          openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          PendingIntent pi = PendingIntent.getActivity(ctx, notifId, openIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          new Thread(() -> {
              Bitmap avatar = downloadAvatar(newMemberPhoto);
              NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Constants.CHANNEL_GROUPS)
                  .setSmallIcon(R.drawable.ic_message_notification)
                  .setContentTitle(title)
                  .setContentText(body)
                  .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                  .setAutoCancel(true)
                  .setContentIntent(pi);
              if (avatar != null) b.setLargeIcon(avatar);
              NotificationManager nm = ctx.getSystemService(NotificationManager.class);
              if (nm != null) nm.notify(notifId, b.build());
          }).start();
      }

      // Bitmap download helper
      private static Bitmap downloadAvatar(String url) {
          if (url == null || url.isEmpty()) return null;
          try {
              java.net.HttpURLConnection c =
                  (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
              c.setDoInput(true); c.connect();
              Bitmap raw = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
              if (raw == null) return null;
              // crop to circle
              int size = Math.min(raw.getWidth(), raw.getHeight());
              Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
              android.graphics.Canvas cv = new android.graphics.Canvas(out);
              android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
              cv.drawCircle(size/2f, size/2f, size/2f, p);
              p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
              cv.drawBitmap(raw, 0, 0, p);
              return out;
          } catch (Exception e) { return null; }
      }
  
}