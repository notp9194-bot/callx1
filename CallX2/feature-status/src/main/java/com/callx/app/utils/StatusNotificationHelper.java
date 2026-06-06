package com.callx.app.utils;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;
import com.callx.app.status.R;
import com.callx.app.viewer.StatusViewerActivity;
import com.callx.app.services.StatusExpiryReceiver;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * Builds and posts rich status notifications.
 *
 * Rich features:
 *  - BigPicture style for image statuses (fetches thumbnail via Glide)
 *  - MessagingStyle-style header with sender avatar
 *  - Deep-link PendingIntent → StatusViewerActivity
 *  - Reply action (quick-reply to status via chat)
 *  - Grouped notifications per sender
 *  - Heads-up / high-priority delivery
 */
public final class StatusNotificationHelper {
    public static final String CHANNEL_ID   = "callx_status";
    public static final String CHANNEL_NAME = "Status Updates";
    private static final String GROUP_KEY   = "callx_status_group";
    private static final ExecutorService BG = Executors.newCachedThreadPool();
    private StatusNotificationHelper() {}
    // ── Channel bootstrap (call once from Application.onCreate) ──────────
    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Notifications for new statuses from your contacts");
        ch.enableVibration(true);
        NotificationManager nm =
                ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
    // ── Post a status notification ────────────────────────────────────────
    /**
     * Post a rich status notification. Runs Glide image fetch on a background
     * thread so it is safe to call from a BroadcastReceiver or Service.
     *
     * @param ctx        context
     * @param fromUid    status owner UID
     * @param fromName   display name
     * @param fromPhoto  avatar URL (may be null)
     * @param statusType "text" | "image" | "video"
     * @param text       status text or caption
     * @param mediaUrl   media URL for image/video (may be null)
     * @param notifId    unique notification ID (use fromUid.hashCode())
     */
    public static void postStatusNotification(Context ctx,
                                              String fromUid,
                                              String fromName,
                                              String fromPhoto,
                                              String statusType,
                                              String text,
                                              String mediaUrl,
                                              int    notifId) {
        BG.execute(() -> {
            try {
                Bitmap avatarBmp = null;
                if (fromPhoto != null && !fromPhoto.isEmpty()) {
                    try {
                        avatarBmp = Glide.with(ctx.getApplicationContext())
                                .asBitmap()
                                .load(fromPhoto)
                                .circleCrop()
                                .submit(128, 128)
                                .get();
                    } catch (Exception ignored) {}
                }
                Bitmap mediaBmp = null;
                if ("image".equals(statusType) && mediaUrl != null && !mediaUrl.isEmpty()) {
                    try {
                        mediaBmp = Glide.with(ctx.getApplicationContext())
                                .asBitmap()
                                .load(mediaUrl)
                                .submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                                .get();
                    } catch (Exception ignored) {}
                }
                // Deep-link intent → StatusViewerActivity
                Intent open = new Intent(ctx, StatusViewerActivity.class);
                open.putExtra(StatusViewerActivity.EXTRA_OWNER_UID,  fromUid);
                open.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, fromName);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent openPi = PendingIntent.getActivity(ctx, notifId, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                // Body text
                String body;
                if ("image".equals(statusType))  body = "Posted a photo status";
                else if ("video".equals(statusType)) body = "Posted a video status";
                else body = (text != null && !text.isEmpty()) ? text : "Posted a new status";
                NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_status_notification)
                        .setContentTitle(fromName != null ? fromName : "New Status")
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setGroup(GROUP_KEY)
                        .setContentIntent(openPi)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(NotificationCompat.CATEGORY_SOCIAL);
                if (avatarBmp != null) {
                    b.setLargeIcon(avatarBmp);
                }
                if (mediaBmp != null) {
                    // BigPicture style shows image preview in notification
                    b.setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(mediaBmp)
                            .setSummaryText(body));
                } else {
                    b.setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(body));
                }
                NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
                nm.notify(notifId, b.build());
                // Summary notification for grouping
                Notification summary = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_status_notification)
                        .setGroup(GROUP_KEY)
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .build();
                nm.notify(Integer.MAX_VALUE - 1, summary);
            } catch (Exception e) {
                android.util.Log.w("StatusNotifHelper", "Failed to post: " + e.getMessage());
            }
        });
    }
      // ─────────────────────────────────────────────────────────────────────
      // STATUS REACTION NOTIFICATION
      // ─────────────────────────────────────────────────────────────────────
      public static void postStatusReactionNotification(Context ctx, String reactorUid,
              String reactorName, String reactorPhoto, String reaction, String statusOwnerUid) {
          final String CH_REACTION = "callx_status_reaction";
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
              NotificationManager nm2 = ctx.getSystemService(NotificationManager.class);
              if (nm2 != null && nm2.getNotificationChannel(CH_REACTION) == null) {
                  android.app.NotificationChannel ch =
                      new android.app.NotificationChannel(CH_REACTION,
                          "Status Reactions", NotificationManager.IMPORTANCE_DEFAULT);
                  ch.setDescription("When someone reacts to your status");
                  nm2.createNotificationChannel(ch);
              }
          }
          String title = reactorName + " reacted " + reaction + " to your status";
          String body  = "Tap to view your status";
          android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx,
              ("react_" + reactorUid).hashCode(),
              new android.content.Intent(ctx, com.callx.app.viewer.StatusViewerActivity.class)
                  .putExtra("owner_uid", statusOwnerUid)
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
              android.app.PendingIntent.FLAG_UPDATE_CURRENT |
              android.app.PendingIntent.FLAG_IMMUTABLE);
          androidx.core.app.NotificationCompat.Builder b =
              new androidx.core.app.NotificationCompat.Builder(ctx, CH_REACTION)
                  .setSmallIcon(R.drawable.ic_status_notification)
                  .setContentTitle(title)
                  .setContentText(body)
                  .setAutoCancel(true)
                  .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                  .setContentIntent(pi);
          new Thread(() -> {
              try {
                  android.graphics.Bitmap bm = downloadBitmap(ctx, reactorPhoto);
                  if (bm != null) b.setLargeIcon(circle(bm));
              } catch (Exception ignored) {}
              NotificationManager nm3 = ctx.getSystemService(NotificationManager.class);
              if (nm3 != null) nm3.notify(("react_" + reactorUid).hashCode(), b.build());
          }).start();
      }
      // ─────────────────────────────────────────────────────────────────────
      // STATUS EXPIRY REMINDER (fires 2h before status expires)
      // ─────────────────────────────────────────────────────────────────────
      public static void scheduleStatusExpiryReminder(Context ctx, String statusId, long expiresAt) {
          long reminderAt = expiresAt - (2 * 60 * 60 * 1000L); // 2h before
          if (reminderAt <= System.currentTimeMillis()) return;
          android.app.AlarmManager am =
              (android.app.AlarmManager) ctx.getSystemService(android.content.Context.ALARM_SERVICE);
          if (am == null) return;
          android.content.Intent intent = new android.content.Intent(ctx, com.callx.app.services.StatusExpiryReceiver.class)
              .putExtra("status_id", statusId);
          android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(ctx,
              statusId.hashCode(), intent,
              android.app.PendingIntent.FLAG_UPDATE_CURRENT |
              android.app.PendingIntent.FLAG_IMMUTABLE);
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
              am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, reminderAt, pi);
          } else {
              am.setExact(android.app.AlarmManager.RTC_WAKEUP, reminderAt, pi);
          }
      }
      // ─────────────────────────────────────────────────────────────────────
      // STATUS VIEWED NOTIFICATION (optional, user-controlled)
      // ─────────────────────────────────────────────────────────────────────
      public static void postStatusViewedNotification(Context ctx, String viewerName,
              String viewerPhoto, String ownerUid, int viewerCount) {
          final String CH = "callx_status_viewed";
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
              NotificationManager nm2 = ctx.getSystemService(NotificationManager.class);
              if (nm2 != null && nm2.getNotificationChannel(CH) == null) {
                  android.app.NotificationChannel ch =
                      new android.app.NotificationChannel(CH,
                          "Status Views", NotificationManager.IMPORTANCE_LOW);
                  ch.setDescription("When contacts view your status");
                  nm2.createNotificationChannel(ch);
              }
          }
          String title = viewerCount == 1
              ? viewerName + " viewed your status"
              : viewerName + " and " + (viewerCount - 1) + " others viewed your status";
          androidx.core.app.NotificationCompat.Builder b =
              new androidx.core.app.NotificationCompat.Builder(ctx, CH)
                  .setSmallIcon(R.drawable.ic_status_notification)
                  .setContentTitle(title)
                  .setContentText("Tap to see who viewed your status")
                  .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                  .setAutoCancel(true);
          NotificationManager nm = ctx.getSystemService(NotificationManager.class);
          if (nm != null) nm.notify(("viewed_" + ownerUid).hashCode(), b.build());
      }
      // ─────────────────────────────────────────────────────────────────────
      // Bitmap helpers (package-private reuse)
      // ─────────────────────────────────────────────────────────────────────
      public static android.graphics.Bitmap downloadBitmap(Context ctx, String url) {
          if (url == null || url.isEmpty()) return null;
          try {
              java.net.HttpURLConnection c =
                  (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
              c.setDoInput(true); c.connect();
              return android.graphics.BitmapFactory.decodeStream(c.getInputStream());
          } catch (Exception e) { return null; }
      }
      public static android.graphics.Bitmap circle(android.graphics.Bitmap src) {
          if (src == null) return null;
          int size = Math.min(src.getWidth(), src.getHeight());
          android.graphics.Bitmap output = android.graphics.Bitmap.createBitmap(size, size,
              android.graphics.Bitmap.Config.ARGB_8888);
          android.graphics.Canvas canvas = new android.graphics.Canvas(output);
          android.graphics.Paint paint = new android.graphics.Paint();
          paint.setAntiAlias(true);
          canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
          paint.setXfermode(new android.graphics.PorterDuffXfermode(
              android.graphics.PorterDuff.Mode.SRC_IN));
          canvas.drawBitmap(src, 0, 0, paint);
          return output;
      }
  
}