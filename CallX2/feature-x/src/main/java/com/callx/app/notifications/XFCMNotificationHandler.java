package com.callx.app.notifications;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import androidx.core.app.NotificationCompat;
import com.callx.app.activities.XActivity;
import com.callx.app.activities.XDMConversationActivity;
import com.callx.app.activities.XTweetDetailActivity;
import com.callx.app.x.R;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * XFCMNotificationHandler — X feature ke sabhi FCM push payloads handle karta hai.
 *
 * ── Background / Killed State Fix ─────────────────────────────────────────────
 *
 * Pehle ka problem (XFirebaseMessagingService):
 *   XFirebaseMessagingService alag service thi jo manifest me register nahi thi,
 *   isliye killed state me FCM delivery nahi ho rahi thi. Upar se XNotificationHelper
 *   ke methods directly FCM thread par call ho rahe the bina Executor ke — agar
 *   koi bhi network I/O hota (avatar download) to FCM wakelock expire ho jaata
 *   aur process kill ho jaata.
 *
 * Fix (reel pattern follow karta hai):
 *   1. XFirebaseMessagingService hata diya — ab CallxMessagingService hi
 *      single entry point hai (manifest me pehle se registered).
 *   2. Payload me "x_notif_type" key detect karke CallxMessagingService yahan
 *      delegate karta hai (bilkul "reel_notif_type" jaisa).
 *   3. HAR type ko Executors.newSingleThreadExecutor().execute() me wrap kiya —
 *      FCM wakelock onMessageReceived() ke pure duration tak active rehta hai,
 *      is executor submit ke baad bhi, so process kill nahi hota.
 *   4. Avatar download network-aware hai: 2G/no-network pe skip, 3G+ pe 100px circle.
 *
 * Supported types:
 *   like, retweet, reply, mention, quote, follow, dm,
 *   poll_ended, list_added, space_started, close_friend_post
 *
 * Server payload keys:
 *   x_notif_type   : type string (REQUIRED — routing key)
 *   fromName       : sender display name
 *   fromPhoto      : sender avatar URL
 *   fromUid        : sender UID
 *   tweetId        : target tweet ID (like/retweet/reply/mention/quote)
 *   conversationId : DM conversation ID
 *   otherUid       : DM other user UID
 *   otherHandle    : DM other user handle
 *   otherPhoto     : DM other user avatar URL
 *   preview        : DM message preview text
 *   pollQuestion   : poll_ended ke liye poll question
 *   listName       : list_added ke liye list name
 *   spaceId        : space_started ke liye space ID
 *   spaceTitle     : space_started ke liye space title
 */
public class XFCMNotificationHandler {

    // ── Notification type constants ────────────────────────────────────────────
    public static final String TYPE_LIKE              = "like";
    public static final String TYPE_RETWEET          = "retweet";
    public static final String TYPE_REPLY            = "reply";
    public static final String TYPE_MENTION          = "mention";
    public static final String TYPE_QUOTE            = "quote";
    public static final String TYPE_FOLLOW           = "follow";
    public static final String TYPE_DM               = "dm";
    public static final String TYPE_POLL_ENDED       = "poll_ended";
    public static final String TYPE_LIST_ADDED       = "list_added";
    public static final String TYPE_SPACE_STARTED    = "space_started";
    public static final String TYPE_CLOSE_FRIEND_POST = "close_friend_post";

    // Notification ID base (X ke liye alag range)
    private static final int NOTIF_ID_BASE = 55000;

    /**
     * Main entry point — CallxMessagingService.onMessageReceived() se call hota hai
     * jab payload me "x_notif_type" key detect ho.
     *
     * Har type ek dedicated Executor thread me run hota hai — FCM wakelock ke andar.
     */
    public static void handle(Context ctx, Map<String, String> data) {
        XNotificationChannelManager.ensureChannels(ctx);

        final String type          = get(data, "x_notif_type");
        final String fromName      = nonEmpty(get(data, "fromName"), "Someone");
        final String fromPhoto     = get(data, "fromPhoto");
        final String fromUid       = get(data, "fromUid");
        final String tweetId       = get(data, "tweetId");
        final String conversationId = get(data, "conversationId");
        final String otherUid      = get(data, "otherUid");
        final String otherHandle   = get(data, "otherHandle");
        final String otherPhoto    = get(data, "otherPhoto");
        final String preview       = get(data, "preview");
        final String pollQuestion  = get(data, "pollQuestion");
        final String listName      = get(data, "listName");
        final String spaceId       = get(data, "spaceId");
        final String spaceTitle    = get(data, "spaceTitle");

        if (type == null || type.isEmpty()) return;

        switch (type) {

            // ── GROUP A: Sender-aware types (avatar download needed) ───────────────
            // Avatar 3G+ pe download, 2G/no-network pe skip.

            case TYPE_LIKE:
            case TYPE_RETWEET:
            case TYPE_REPLY:
            case TYPE_MENTION:
            case TYPE_QUOTE:
            case TYPE_FOLLOW:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap avatar = (net >= 2)
                            ? downloadCircle(fromPhoto, 100) : null;
                    showTweetInteractionNotif(ctx, type, fromName, fromUid,
                            avatar, tweetId);
                });
                break;

            // ── GROUP B: DM (avatar download + both-party photo) ──────────────────

            case TYPE_DM:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap avatar = (net >= 2)
                            ? downloadCircle(fromPhoto, 100) : null;
                    showDMNotif(ctx, fromName, fromUid, avatar,
                            conversationId, otherUid, otherHandle, otherPhoto, preview);
                });
                break;

            // ── GROUP C: No avatar needed ─────────────────────────────────────────
            // Executor wrap still required — ensures we're inside FCM wakelock.

            case TYPE_POLL_ENDED:
                Executors.newSingleThreadExecutor().execute(() ->
                        showPollEndedNotif(ctx, tweetId, pollQuestion));
                break;

            case TYPE_LIST_ADDED:
                Executors.newSingleThreadExecutor().execute(() ->
                        showListAddedNotif(ctx, fromName, listName));
                break;

            case TYPE_SPACE_STARTED:
                Executors.newSingleThreadExecutor().execute(() ->
                        showSpaceStartedNotif(ctx, fromName, spaceId, spaceTitle));
                break;

            case TYPE_CLOSE_FRIEND_POST:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = getNetworkLevel(ctx);
                    Bitmap avatar = (net >= 2)
                            ? downloadCircle(fromPhoto, 100) : null;
                    showCloseFriendPostNotif(ctx, fromName, fromUid, avatar, tweetId);
                });
                break;
        }
    }

    // ── showTweetInteractionNotif ──────────────────────────────────────────────

    private static void showTweetInteractionNotif(Context ctx, String type,
            String fromName, String fromUid, Bitmap avatar, String tweetId) {

        String title = buildTitle(type, fromName);

        Intent tapIntent = tweetId != null && !tweetId.isEmpty()
                ? new Intent(ctx, XTweetDetailActivity.class)
                        .putExtra("tweet_id", tweetId)
                : new Intent(ctx, XActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(type, fromUid, tweetId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String channel = dmType(type)
                ? XNotificationChannelManager.CHANNEL_X_DM
                : XNotificationChannelManager.CHANNEL_X_MENTIONS;

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_x_logo)
                .setContentTitle(title)
                .setContentText("")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        if (avatar != null) b.setLargeIcon(avatar);

        post(ctx, reqCode, b);
    }

    // ── showDMNotif ───────────────────────────────────────────────────────────

    private static void showDMNotif(Context ctx, String fromName, String fromUid,
            Bitmap avatar, String conversationId, String otherUid,
            String otherHandle, String otherPhoto, String preview) {

        Intent intent = new Intent(ctx, XDMConversationActivity.class)
                .putExtra("conversation_id", conversationId)
                .putExtra("other_uid", otherUid)
                .putExtra("other_name", fromName)
                .putExtra("other_handle", otherHandle)
                .putExtra("other_photo", otherPhoto)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_DM, fromUid, conversationId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                XNotificationChannelManager.CHANNEL_X_DM)
                .setSmallIcon(R.drawable.ic_x_dm)
                .setContentTitle(fromName)
                .setContentText(preview != null && !preview.isEmpty() ? preview : "New message")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        if (avatar != null) b.setLargeIcon(avatar);

        post(ctx, reqCode, b);
    }

    // ── showPollEndedNotif ────────────────────────────────────────────────────

    private static void showPollEndedNotif(Context ctx, String tweetId, String pollQuestion) {
        Intent intent = new Intent(ctx, XTweetDetailActivity.class)
                .putExtra("tweet_id", tweetId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_POLL_ENDED, "", tweetId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = (pollQuestion != null && !pollQuestion.isEmpty())
                ? "\"" + pollQuestion + "\" — Results are in"
                : "Your poll has ended";

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                XNotificationChannelManager.CHANNEL_X_GENERAL)
                .setSmallIcon(R.drawable.ic_x_logo)
                .setContentTitle("Poll ended")
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        post(ctx, reqCode, b);
    }

    // ── showListAddedNotif ────────────────────────────────────────────────────

    private static void showListAddedNotif(Context ctx, String fromName, String listName) {
        Intent intent = new Intent(ctx, XActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_LIST_ADDED, fromName, listName);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = (listName != null && !listName.isEmpty())
                ? fromName + " added you to \"" + listName + "\""
                : fromName + " added you to a list";

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                XNotificationChannelManager.CHANNEL_X_GENERAL)
                .setSmallIcon(R.drawable.ic_x_logo)
                .setContentTitle("Added to a list")
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        post(ctx, reqCode, b);
    }

    // ── showSpaceStartedNotif ─────────────────────────────────────────────────

    private static void showSpaceStartedNotif(Context ctx, String fromName,
            String spaceId, String spaceTitle) {
        Intent intent = new Intent(ctx, XActivity.class)
                .putExtra("space_id", spaceId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_SPACE_STARTED, fromName, spaceId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = (spaceTitle != null && !spaceTitle.isEmpty())
                ? "\"" + spaceTitle + "\""
                : fromName + " started a Space";

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                XNotificationChannelManager.CHANNEL_X_MENTIONS)
                .setSmallIcon(R.drawable.ic_x_logo)
                .setContentTitle(fromName + " is live on Spaces")
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        post(ctx, reqCode, b);
    }

    // ── showCloseFriendPostNotif ──────────────────────────────────────────────

    private static void showCloseFriendPostNotif(Context ctx, String fromName,
            String fromUid, Bitmap avatar, String tweetId) {

        Intent intent = new Intent(ctx, XTweetDetailActivity.class)
                .putExtra("tweet_id", tweetId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int reqCode = notifId(TYPE_CLOSE_FRIEND_POST, fromUid, tweetId);
        PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                XNotificationChannelManager.CHANNEL_X_MENTIONS)
                .setSmallIcon(R.drawable.ic_x_logo)
                .setContentTitle(fromName + " posted")
                .setContentText("Someone you follow closely posted something new")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        if (avatar != null) b.setLargeIcon(avatar);

        post(ctx, reqCode, b);
    }

    // ── Network helpers (same pattern as ReelNotificationHelper) ──────────────

    /**
     * Network level:
     *   0 = no network / unknown
     *   1 = 2G (GPRS, EDGE)
     *   2 = 3G
     *   3 = 4G / 5G / WiFi
     */
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
                    return 1; // 2G
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                    return 2; // 3G
                case TelephonyManager.NETWORK_TYPE_LTE:
                case TelephonyManager.NETWORK_TYPE_NR:
                    return 3; // 4G / 5G
                default:
                    return 2;
            }
        }
        return 2;
    }

    /**
     * Download avatar as a circle-cropped Bitmap.
     * Size: 100px (same as reel avatar policy).
     * Returns null on failure — caller handles gracefully.
     */
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
            // Scale to sizePx × sizePx
            Bitmap scaled = Bitmap.createScaledBitmap(raw, sizePx, sizePx, true);
            // Crop to circle
            android.graphics.Bitmap circle =
                    android.graphics.Bitmap.createBitmap(sizePx, sizePx,
                            android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(circle);
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint);
            paint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(scaled, 0, 0, paint);
            return circle;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private static String buildTitle(String type, String name) {
        switch (type) {
            case TYPE_LIKE:     return name + " liked your post";
            case TYPE_RETWEET:  return name + " reposted your post";
            case TYPE_REPLY:    return name + " replied to you";
            case TYPE_MENTION:  return name + " mentioned you";
            case TYPE_QUOTE:    return name + " quoted your post";
            case TYPE_FOLLOW:   return name + " followed you";
            default:            return name + " interacted with you";
        }
    }

    private static boolean dmType(String type) {
        return TYPE_DM.equals(type);
    }

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
