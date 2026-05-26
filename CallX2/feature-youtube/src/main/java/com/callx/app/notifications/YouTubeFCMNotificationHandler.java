package com.callx.app.notifications;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import androidx.core.app.NotificationCompat;
import com.callx.app.activities.YouTubeActivity;
import com.callx.app.activities.YouTubeChannelActivity;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.youtube.R;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * YouTubeFCMNotificationHandler — v2 (Heads-Up Notification fix)
 *
 * ── Problem jo fix kiya ──────────────────────────────────────────────────────
 *   Background/killed state mein notification aata tha lekin upar se neeche
 *   slide karke (YouTube jaisa heads-up) nahi dikhta tha — sirf notification
 *   tray mein jaata tha.
 *
 *   Root cause:
 *     1. CHANNEL_YT_COMMENTS + CHANNEL_YT_GENERAL = IMPORTANCE_DEFAULT  → no HUN
 *     2. Builder pe setDefaults() missing tha
 *     3. Channel IDs same the isliye Android cached importance use karta tha
 *
 *   Fix:
 *     1. Sabhi channels ab IMPORTANCE_HIGH (v2 IDs ke saath)
 *     2. Har builder pe PRIORITY_HIGH + setDefaults(DEFAULT_ALL) lagaya
 *     3. Likes channel sirf PRIORITY_DEFAULT (spam nahi hona chahiye)
 *
 * ── Background / Killed State ─────────────────────────────────────────────────
 *   X system jaisa exact pattern:
 *   1. CallxMessagingService single FCM entry point (manifest registered).
 *   2. Payload me "yt_notif_type" detect → yahan delegate.
 *   3. Executors.newSingleThreadExecutor() mein wrap — FCM wakelock safe.
 *   4. Avatar/thumbnail network-aware download (2G skip, 3G+ download).
 *
 * Supported types: new_video, comment, reply, subscribe, live, like_milestone
 */
public class YouTubeFCMNotificationHandler {

    public static final String TYPE_NEW_VIDEO      = "new_video";
    public static final String TYPE_COMMENT        = "comment";
    public static final String TYPE_REPLY          = "reply";
    public static final String TYPE_SUBSCRIBE      = "subscribe";
    public static final String TYPE_LIVE           = "live";
    public static final String TYPE_LIKE_MILESTONE = "like_milestone";

    private static final int NOTIF_ID_BASE = 66000;

    /**
     * Main entry point — CallxMessagingService.onMessageReceived() se call hota hai
     * jab payload me "yt_notif_type" key detect ho.
     */
    public static void handle(Context ctx, Map<String, String> data) {
        YouTubeNotificationChannelManager.ensureChannels(ctx);

        final String type         = get(data, "yt_notif_type");
        final String fromName     = nonEmpty(get(data, "fromName"), "Someone");
        final String fromPhoto    = get(data, "fromPhoto");
        final String fromUid      = get(data, "fromUid");
        final String videoId      = get(data, "videoId");
        final String videoTitle   = get(data, "videoTitle");
        final String thumbnailUrl = get(data, "thumbnailUrl");
        final String commentText  = get(data, "commentText");
        final String likeCount    = get(data, "likeCount");

        if (type == null || type.isEmpty()) return;

        switch (type) {

            // ── New video — channel avatar + video thumbnail ───────────────────
            case TYPE_NEW_VIDEO:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap thumb = (net >= 2) ? downloadBitmap(thumbnailUrl) : null;
                    showNewVideoNotif(ctx, fromName, fromUid, videoTitle, thumb, videoId);
                });
                break;

            // ── Comment on user's video ────────────────────────────────────────
            case TYPE_COMMENT:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap avatar = (net >= 2) ? downloadCircle(fromPhoto, 100) : null;
                    showCommentNotif(ctx, fromName, fromUid, avatar, videoId, videoTitle, commentText);
                });
                break;

            // ── Reply to user's comment ────────────────────────────────────────
            case TYPE_REPLY:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap avatar = (net >= 2) ? downloadCircle(fromPhoto, 100) : null;
                    showReplyNotif(ctx, fromName, fromUid, avatar, videoId, commentText);
                });
                break;

            // ── New subscriber ─────────────────────────────────────────────────
            case TYPE_SUBSCRIBE:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap avatar = (net >= 2) ? downloadCircle(fromPhoto, 100) : null;
                    showSubscribeNotif(ctx, fromName, fromUid, avatar);
                });
                break;

            // ── Live stream started ────────────────────────────────────────────
            case TYPE_LIVE:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap thumb = (net >= 2) ? downloadBitmap(thumbnailUrl) : null;
                    showLiveNotif(ctx, fromName, fromUid, thumb, videoId, videoTitle);
                });
                break;

            // ── Like milestone ─────────────────────────────────────────────────
            case TYPE_LIKE_MILESTONE:
                Executors.newSingleThreadExecutor().execute(() ->
                        showLikeMilestoneNotif(ctx, videoTitle, videoId, likeCount));
                break;
        }
    }

    // ── showNewVideoNotif ─────────────────────────────────────────────────────

    private static void showNewVideoNotif(Context ctx, String channelName, String fromUid,
            String videoTitle, Bitmap thumb, String videoId) {

        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
                .putExtra("video_id", videoId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_NEW_VIDEO, fromUid, videoId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                YouTubeNotificationChannelManager.CHANNEL_YT_SUBSCRIPTIONS)
                .setSmallIcon(R.drawable.ic_youtube_logo)
                .setContentTitle(channelName + " uploaded a new video")
                .setContentText(videoTitle != null && !videoTitle.isEmpty() ? videoTitle : "")
                .setContentIntent(pi)
                .setAutoCancel(true)
                // ── Heads-up ke liye zaroori ──
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        if (thumb != null) {
            b.setLargeIcon(thumb);
            b.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(thumb)
                    .bigLargeIcon((Bitmap) null));
        }

        post(ctx, reqCode, b);
    }

    // ── showCommentNotif ──────────────────────────────────────────────────────

    private static void showCommentNotif(Context ctx, String fromName, String fromUid,
            Bitmap avatar, String videoId, String videoTitle, String commentText) {

        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
                .putExtra("video_id", videoId)
                .putExtra("open_comments", true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_COMMENT, fromUid, videoId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String preview = nonEmpty(commentText, "New comment");
        String bigText = "\"" + preview + "\""
                + (videoTitle != null && !videoTitle.isEmpty() ? "\n" + videoTitle : "");

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                YouTubeNotificationChannelManager.CHANNEL_YT_COMMENTS)
                .setSmallIcon(R.drawable.ic_youtube_logo)
                .setContentTitle(fromName + " commented on your video")
                .setContentText(preview)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setContentIntent(pi)
                .setAutoCancel(true)
                // ── Heads-up ke liye zaroori ──
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        if (avatar != null) b.setLargeIcon(avatar);

        post(ctx, reqCode, b);
    }

    // ── showReplyNotif ────────────────────────────────────────────────────────

    private static void showReplyNotif(Context ctx, String fromName, String fromUid,
            Bitmap avatar, String videoId, String replyText) {

        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
                .putExtra("video_id", videoId)
                .putExtra("open_comments", true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_REPLY, fromUid, videoId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                YouTubeNotificationChannelManager.CHANNEL_YT_COMMENTS)
                .setSmallIcon(R.drawable.ic_youtube_logo)
                .setContentTitle(fromName + " replied to your comment")
                .setContentText(nonEmpty(replyText, "New reply"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                // ── Heads-up ke liye zaroori ──
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        if (avatar != null) b.setLargeIcon(avatar);

        post(ctx, reqCode, b);
    }

    // ── showSubscribeNotif ────────────────────────────────────────────────────

    private static void showSubscribeNotif(Context ctx, String fromName,
            String fromUid, Bitmap avatar) {

        Intent intent = new Intent(ctx, YouTubeChannelActivity.class)
                .putExtra("channel_uid", fromUid)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_SUBSCRIBE, fromUid, "");
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                YouTubeNotificationChannelManager.CHANNEL_YT_GENERAL)
                .setSmallIcon(R.drawable.ic_youtube_logo)
                .setContentTitle(fromName + " subscribed to your channel")
                .setContentText("You have a new subscriber!")
                .setContentIntent(pi)
                .setAutoCancel(true)
                // ── Heads-up ke liye zaroori ──
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        if (avatar != null) b.setLargeIcon(avatar);

        post(ctx, reqCode, b);
    }

    // ── showLiveNotif ─────────────────────────────────────────────────────────

    private static void showLiveNotif(Context ctx, String channelName, String fromUid,
            Bitmap thumb, String videoId, String streamTitle) {

        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
                .putExtra("video_id", videoId)
                .putExtra("is_live", true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_LIVE, fromUid, videoId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                YouTubeNotificationChannelManager.CHANNEL_YT_LIVE)
                .setSmallIcon(R.drawable.ic_youtube_logo)
                .setContentTitle(channelName + " is LIVE now!")
                .setContentText(nonEmpty(streamTitle, "Live stream started"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                // ── Heads-up ke liye zaroori ──
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_EVENT);

        if (thumb != null) b.setLargeIcon(thumb);

        post(ctx, reqCode, b);
    }

    // ── showLikeMilestoneNotif ────────────────────────────────────────────────

    private static void showLikeMilestoneNotif(Context ctx, String videoTitle,
            String videoId, String likeCount) {

        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
                .putExtra("video_id", videoId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_LIKE_MILESTONE, "", videoId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String count = (likeCount != null && !likeCount.isEmpty()) ? likeCount : "";
        String title = count.isEmpty() ? "Like milestone reached!" : "🎉 " + count + " likes on your video!";

        // Likes = PRIORITY_DEFAULT (spam avoid karne ke liye HUN nahi chahiye)
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                YouTubeNotificationChannelManager.CHANNEL_YT_LIKES)
                .setSmallIcon(R.drawable.ic_youtube_logo)
                .setContentTitle(title)
                .setContentText(nonEmpty(videoTitle, ""))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        post(ctx, reqCode, b);
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    public static int getNetworkLevel(Context ctx) {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return 0;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) return 0;
        if (ni.getType() == ConnectivityManager.TYPE_WIFI) return 3;
        if (ni.getType() == ConnectivityManager.TYPE_MOBILE) {
            switch (ni.getSubtype()) {
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    return 1;
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                    return 2;
                case TelephonyManager.NETWORK_TYPE_LTE:
                case TelephonyManager.NETWORK_TYPE_NR:
                    return 3;
                default:
                    return 2;
            }
        }
        return 2;
    }

    /** Circle-crop avatar download */
    public static Bitmap downloadCircle(String photoUrl, int sizePx) {
        if (photoUrl == null || photoUrl.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(photoUrl).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            Bitmap raw = BitmapFactory.decodeStream(is);
            is.close();
            if (raw == null) return null;
            Bitmap scaled = Bitmap.createScaledBitmap(raw, sizePx, sizePx, true);
            Bitmap circle = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(circle);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(scaled, 0, 0, paint);
            return circle;
        } catch (Exception e) {
            return null;
        }
    }

    /** Plain bitmap download — thumbnail ke liye */
    public static Bitmap downloadBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(8000);
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static int notifId(String type, String uid, String extra) {
        return NOTIF_ID_BASE + (type + uid + (extra != null ? extra : "")).hashCode();
    }

    private static void post(Context ctx, int id, NotificationCompat.Builder b) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, b.build());
    }

    private static String get(Map<String, String> d, String k) {
        String v = d.get(k);
        return v != null ? v : "";
    }

    private static String nonEmpty(String val, String fallback) {
        return (val != null && !val.isEmpty()) ? val : fallback;
    }
}
