package com.callx.app.notifications;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import androidx.core.app.NotificationCompat;
import com.callx.app.reels.R;
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.notifications.ReelRepostNotificationHelper;
import com.callx.app.player.SingleReelPlayerActivity;
import java.util.Map;
import java.util.concurrent.Executors;
import android.net.ConnectivityManager;
import android.telephony.TelephonyManager;

/**
 * ReelFCMNotificationHandler — Dispatches ALL 38 reel FCM push payloads.
 *
 * FIX v16 — All 38 types background/killed safe:
 *
 *   Root cause (v15 → v16):
 *   ReelNotificationHelper's show* methods already use their own internal
 *   Executors, BUT they were called directly on the FCM delivery thread.
 *   The FCM wakelock covers only onMessageReceived() — not executor tasks
 *   spawned *after* it returns. So if Helper spawns its own executor thread
 *   after FCM has already returned from onMessageReceived(), the process
 *   can be killed before that inner executor task completes.
 *
 *   Fix: Wrap EVERY type (including the 28 "foreground-only" ones) in an
 *   outer Executors.newSingleThreadExecutor().execute() call INSIDE
 *   onMessageReceived() scope. This keeps the entire work — including
 *   Helper's inner executor spawning — inside the FCM wakelock window.
 *
 *   The 10 core types (Group A) continue to build the notification directly
 *   in the handler (no Helper call), since they need custom BigPicture UI.
 *
 *   The remaining 28 types (Groups B/C/D) call Helper's existing show*()
 *   methods from within the outer executor — guaranteeing they are
 *   dispatched before FCM's wakelock expires.
 *
 *   Server-side (index.js): No changes needed. All 38 types already
 *   present in VALID_REEL_TYPES.
 */
public class ReelFCMNotificationHandler {

    // ── Notification type constants ────────────────────────────────────────
    public static final String TYPE_LIKE               = "like";
    public static final String TYPE_COMMENT            = "comment";
    public static final String TYPE_COMMENT_LIKE       = "comment_like";
    public static final String TYPE_COMMENT_REPLY      = "comment_reply";
    public static final String TYPE_MENTION_CAPTION    = "mention_caption";
    public static final String TYPE_MENTION_COMMENT    = "mention_comment";
    public static final String TYPE_NEW_FOLLOWER       = "new_follower";
    public static final String TYPE_FOLLOWING_POSTED   = "following_posted";
    public static final String TYPE_DUET               = "duet";
    public static final String TYPE_STITCH             = "stitch";
    public static final String TYPE_VIDEO_REPLY        = "video_reply";
    public static final String TYPE_COLLAB_REQUEST     = "collab_request";
    public static final String TYPE_COLLAB_ACCEPTED    = "collab_accepted";
    public static final String TYPE_GIFT               = "gift";
    public static final String TYPE_LIVE_STARTED       = "live_started";
    public static final String TYPE_LIVE_MILESTONE     = "live_milestone";
    public static final String TYPE_CLOSE_FRIEND_LIVE  = "close_friend_live";
    public static final String TYPE_TRENDING           = "trending";
    public static final String TYPE_VIRAL              = "viral";
    public static final String TYPE_VIEW_MILESTONE     = "view_milestone";
    public static final String TYPE_FOLLOWER_MILESTONE = "follower_milestone";
    public static final String TYPE_UPLOAD_COMPLETE    = "upload_complete";
    public static final String TYPE_UPLOAD_FAILED      = "upload_failed";
    public static final String TYPE_SCHEDULED_POST     = "scheduled_post";
    public static final String TYPE_SCHEDULED_REMINDER = "scheduled_reminder";
    public static final String TYPE_PRODUCT_TAG_CLICK  = "product_tag_click";
    public static final String TYPE_CREATOR_FUND       = "creator_fund_payout";
    public static final String TYPE_CONTENT_REMOVED    = "content_removed";
    public static final String TYPE_REPORT_RESOLVED    = "report_resolved";
    public static final String TYPE_SOUND_TRENDING     = "sound_trending";
    public static final String TYPE_PINNED_COMMENT     = "pinned_comment";
    public static final String TYPE_CLOSE_FRIEND_POST  = "close_friend_post";
    public static final String TYPE_CHALLENGE          = "challenge";
    public static final String TYPE_REEL_SHARED        = "reel_shared";
    public static final String TYPE_REEL_SAVED         = "reel_saved";
    public static final String TYPE_REEL_DOWNLOADED    = "reel_downloaded";
    public static final String TYPE_WEEKLY_DIGEST      = "weekly_digest";
    public static final String TYPE_COLLAB_LIVE        = "collab_live";
      public static final String TYPE_PRODUCT_TAG_SALE   = "product_tag_sale";
      public static final String TYPE_CHALLENGE_UPDATE   = "challenge_update";
      public static final String TYPE_REEL_RECOMMENDED   = "reel_recommended";
    // v4.2.9 — repost system
    public static final String TYPE_REPOST              = "repost";
    public static final String TYPE_QUOTE_REPOST        = "quote_repost";

    /**
     * Main entry point — called from CallxMessagingService.onMessageReceived().
     *
     * v16 contract: every type is submitted to a new single-thread executor
     * SYNCHRONOUSLY within onMessageReceived(). FCM holds the wakelock for
     * the entire duration of onMessageReceived(), so our executor task is
     * guaranteed to be dispatched and started inside that window.
     */
    public static void handle(Context ctx, Map<String, String> data) {
        ReelNotificationChannelManager.ensureChannels(ctx);

        final String type            = get(data, "reel_notif_type");
        final String senderName      = get(data, "sender_name");
        final String senderPhoto     = get(data, "sender_photo");
        final String senderUid       = get(data, "sender_uid");
        final String reelId          = get(data, "reel_id");
        final String reelThumb       = get(data, "reel_thumb");
        final String commentText     = get(data, "comment_text");
        final String commentId       = get(data, "comment_id");
        final int    likeCount       = (int) parseLong(data, "like_count", 1);
        final long   milestone       = parseLong(data, "milestone", 0);
        final long   coinValue       = parseLong(data, "coin_value", 0);
        final long   clickCount      = parseLong(data, "click_count", 1);
        final long   useCount        = parseLong(data, "use_count", 0);
        final long   coins           = parseLong(data, "coins", 0);
        final long   totalViews      = parseLong(data, "total_views", 0);
        final long   newFollowers    = parseLong(data, "new_followers", 0);
        final long   totalLikes      = parseLong(data, "total_likes", 0);
        final long   totalComments   = parseLong(data, "total_comments", 0);
        final double usd             = parseDouble(data, "usd", 0);
        final String collabId        = get(data, "collab_id");
        final String liveId          = get(data, "live_id");
        final String replyReelId     = get(data, "reply_reel_id");
        final String giftName        = get(data, "gift_name");
        final String draftId         = get(data, "draft_id");
        final String uploadReason    = get(data, "reason");
        final String scheduledAt     = get(data, "scheduled_at");
        final String productName     = get(data, "product_name");
        final String reportId        = get(data, "report_id");
        final String decision        = get(data, "decision");
        final String soundTitle      = get(data, "sound_title");
        final String soundId         = get(data, "sound_id");
        final String challengeName   = get(data, "challenge_name");
        final String challengeTag    = get(data, "challenge_hashtag");
        final String challengeId     = get(data, "challenge_id");
        final boolean isUpdate       = "true".equals(get(data, "is_update"));

        if (type == null || type.isEmpty()) return;

        switch (type) {

            // ── GROUP A: Core social (10 types) ──────────────────────────────────
            // Outer executor: downloads avatar + thumb, builds notification directly.
            // No ReelNotificationHelper call — handler owns the full notification.
            case TYPE_LIKE:
            case TYPE_COMMENT:
            case TYPE_COMMENT_LIKE:
            case TYPE_COMMENT_REPLY:
            case TYPE_MENTION_CAPTION:
            case TYPE_MENTION_COMMENT:
            case TYPE_NEW_FOLLOWER:
            case TYPE_FOLLOWING_POSTED:
            case TYPE_DUET:
            case TYPE_STITCH:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = ReelNotificationHelper.getNetworkLevel(ctx);
                    Bitmap avatar = (net >= 2)
                        ? ReelNotificationHelper.downloadCirclePublic(senderPhoto, 100) : null;
                    Bitmap thumb  = null;
                    if (!reelThumb.isEmpty() && net == 3) { // thumb only on WiFi/4G/5G
                        thumb = ReelNotificationHelper.downloadBitmapPublic(reelThumb, 400, 300);
                    }
                    showNotifDirect(ctx, type, senderName, senderUid,
                            avatar, thumb, reelId, reelThumb,
                            commentText, commentId, likeCount);
                });
                break;

            // ── GROUP B: Sender-aware (10 types) ─────────────────────────────────
            // Outer executor ensures Helper's show* call (and its inner executor)
            // is dispatched within the FCM wakelock window.
            // Helper receives String photoUrl — it handles its own download internally.

            case TYPE_VIDEO_REPLY:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showVideoReplyNotification(
                            ctx, senderName, senderPhoto, reelId, replyReelId));
                break;

            case TYPE_COLLAB_REQUEST:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showCollabRequestNotification(
                            ctx, senderName, senderPhoto, reelId, collabId));
                break;

            case TYPE_COLLAB_ACCEPTED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showCollabAcceptedNotification(
                            ctx, senderName, senderPhoto, collabId));
                break;

            case TYPE_GIFT:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showGiftNotification(
                            ctx, senderName, senderPhoto, giftName, coinValue, reelId));
                break;

            case TYPE_LIVE_STARTED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showLiveStartedNotification(
                            ctx, senderName, senderPhoto, liveId));
                break;

            case TYPE_CLOSE_FRIEND_LIVE:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showCloseFriendLiveNotification(
                            ctx, senderName, senderPhoto, liveId));
                break;

            case TYPE_CLOSE_FRIEND_POST:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showCloseFriendPostedNotification(
                            ctx, senderName, senderPhoto, reelId, reelThumb));
                break;

            case TYPE_COLLAB_LIVE:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showCollabLiveNotification(
                            ctx, senderName, senderPhoto, liveId));
                break;

              // ── MISSING TYPE 1: Product Tag Sale ──────────────────────────────────
              case TYPE_PRODUCT_TAG_SALE:
                  Executors.newSingleThreadExecutor().execute(() ->
                      ReelNotificationHelper.showProductTagSaleNotification(
                              ctx, get(data, "product_name"),
                              parseLong(data, "sale_amount", 0), reelId));
                  break;

              // ── MISSING TYPE 2: Challenge Update ──────────────────────────────────
              case TYPE_CHALLENGE_UPDATE:
                  Executors.newSingleThreadExecutor().execute(() ->
                      ReelNotificationHelper.showChallengeUpdateNotification(
                              ctx, get(data, "challenge_name"),
                              get(data, "challenge_hashtag"),
                              get(data, "challenge_id"),
                              get(data, "update_text")));
                  break;

              // ── MISSING TYPE 3: Reel Recommendation ───────────────────────────────
              case TYPE_REEL_RECOMMENDED:
                  Executors.newSingleThreadExecutor().execute(() ->
                      ReelNotificationHelper.showReelRecommendationNotification(
                              ctx, reelId, reelThumb, get(data, "reason")));
                  break;
  

            case TYPE_PINNED_COMMENT:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showPinnedCommentNotification(
                            ctx, senderName, reelId, commentText));
                break;

            case TYPE_REEL_SHARED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showReelSharedNotification(
                            ctx, senderName, reelId));
                break;

            case TYPE_REEL_SAVED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showReelSavedNotification(
                            ctx, senderName, reelId));
                break;

            case TYPE_REEL_DOWNLOADED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showReelDownloadedNotification(
                            ctx, senderName, reelId));
                break;

            // ── v4.2.9: TYPE_REPOST + TYPE_QUOTE_REPOST ──────────────────────────
            // Full production: avatar download + thumb + grouped batch notification
            case TYPE_REPOST:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = ReelNotificationHelper.getNetworkLevel(ctx);
                    android.graphics.Bitmap avatar = (net >= 2)
                        ? ReelNotificationHelper.downloadCirclePublic(senderPhoto, 100) : null;
                    android.graphics.Bitmap thumb  = (net == 3 && !reelThumb.isEmpty())
                        ? ReelNotificationHelper.downloadBitmapPublic(reelThumb, 400, 300) : null;
                    final String repostCaption = get(data, "caption");
                    ReelRepostNotificationHelper.showRepostNotification(
                        ctx, senderName, senderUid, senderPhoto,
                        reelId, reelThumb, repostCaption, avatar, thumb);
                });
                break;

            case TYPE_QUOTE_REPOST:
                Executors.newSingleThreadExecutor().execute(() -> {
                    int net = ReelNotificationHelper.getNetworkLevel(ctx);
                    android.graphics.Bitmap avatar = (net >= 2)
                        ? ReelNotificationHelper.downloadCirclePublic(senderPhoto, 100) : null;
                    final String quoteText = get(data, "caption");
                    ReelRepostNotificationHelper.showQuoteRepostNotification(
                        ctx, senderName, senderUid, reelId, quoteText, avatar);
                });
                break;

            // ── GROUP C: Reel-thumb types (3 types) ──────────────────────────────

            case TYPE_TRENDING:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showTrendingNotification(
                            ctx, reelId, reelThumb, milestone));
                break;

            case TYPE_VIRAL:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showViralNotification(
                            ctx, reelId, reelThumb, milestone));
                break;

            case TYPE_UPLOAD_COMPLETE:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showUploadCompleteNotification(
                            ctx, reelId, reelThumb));
                break;

            // ── GROUP D: No network fetch (15 types) ─────────────────────────────
            // No download needed, but outer executor still wraps to guarantee
            // we're dispatched inside FCM wakelock before onMessageReceived() returns.

            case TYPE_LIVE_MILESTONE:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showLiveViewerMilestone(ctx, milestone));
                break;

            case TYPE_VIEW_MILESTONE:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showViewMilestoneNotification(ctx, reelId, milestone));
                break;

            case TYPE_FOLLOWER_MILESTONE:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showFollowerMilestoneNotification(ctx, milestone));
                break;

            case TYPE_UPLOAD_FAILED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showUploadFailedNotification(ctx, draftId, uploadReason));
                break;

            case TYPE_SCHEDULED_POST:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showScheduledPostNotification(ctx, reelId, scheduledAt));
                break;

            case TYPE_SCHEDULED_REMINDER:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showScheduledReminderNotification(ctx, scheduledAt));
                break;

            case TYPE_PRODUCT_TAG_CLICK:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showProductTagClickNotification(
                            ctx, productName, reelId, clickCount));
                break;

            case TYPE_CREATOR_FUND:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showCreatorFundPayoutNotification(ctx, coins, usd));
                break;

            case TYPE_CONTENT_REMOVED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showContentRemovedNotification(ctx, reelId, uploadReason));
                break;

            case TYPE_REPORT_RESOLVED:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showReportResolvedNotification(ctx, reportId, decision));
                break;

            case TYPE_SOUND_TRENDING:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showSoundTrendingNotification(
                            ctx, soundTitle, soundId, useCount));
                break;

            case TYPE_CHALLENGE:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showChallengeNotification(
                            ctx, challengeName, challengeTag, challengeId, isUpdate));
                break;

            case TYPE_WEEKLY_DIGEST:
                Executors.newSingleThreadExecutor().execute(() ->
                    ReelNotificationHelper.showWeeklyDigestNotification(
                            ctx, totalViews, newFollowers, totalLikes, totalComments));
                break;
        }
    }

    // ── showNotifDirect — only for Group A (10 core social types) ────────────
    private static void showNotifDirect(Context ctx, String type,
            String senderName, String senderUid,
            Bitmap avatar, Bitmap thumb,
            String reelId, String reelThumb,
            String commentText, String commentId, int likeCount) {

        String channel = channelFor(type);
        String title   = buildTitle(type, senderName);
        String body    = buildBody(type, commentText);

        Intent tapIntent = new Intent(ctx, SingleReelPlayerActivity.class);
        tapIntent.putExtra("reel_id", reelId);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int reqCode = (type + reelId + senderUid).hashCode();
        PendingIntent tapPi = PendingIntent.getActivity(ctx, reqCode, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent bellIntent = new Intent(ctx, ReelNotificationsActivity.class);
        bellIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent bellPi = PendingIntent.getActivity(ctx, reqCode + 1, bellIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(iconFor(type))
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(tapPi)
                .setColor(colorFor(type));

        if (avatar != null) b.setLargeIcon(avatar);

        if (thumb != null) {
            b.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(thumb)
                    .setSummaryText(body));
        }

        switch (type) {
            case TYPE_LIKE:
                b.addAction(R.drawable.ic_heart_filled, "❤️ Like Back", tapPi);
                break;
            case TYPE_COMMENT:
            case TYPE_COMMENT_REPLY:
                b.addAction(R.drawable.ic_comment_reel, "💬 View", tapPi);
                break;
            case TYPE_NEW_FOLLOWER:
                b.addAction(R.drawable.ic_person_add, "View Profile", bellPi);
                break;
        }

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((type + reelId + senderUid).hashCode(), b.build());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static String buildTitle(String type, String name) {
        switch (type) {
            case TYPE_LIKE:             return name + " liked your reel";
            case TYPE_COMMENT:          return name + " commented on your reel";
            case TYPE_COMMENT_LIKE:     return name + " liked your comment";
            case TYPE_COMMENT_REPLY:    return name + " replied to your comment";
            case TYPE_MENTION_CAPTION:
            case TYPE_MENTION_COMMENT:  return name + " mentioned you";
            case TYPE_NEW_FOLLOWER:     return name + " started following you";
            case TYPE_FOLLOWING_POSTED: return name + " posted a new reel";
            case TYPE_DUET:             return name + " made a duet with your reel";
            case TYPE_STITCH:           return name + " stitched your reel";
            default:                    return name + " interacted with your reel";
        }
    }

    private static String buildBody(String type, String commentText) {
        switch (type) {
            case TYPE_COMMENT:
            case TYPE_COMMENT_REPLY:
            case TYPE_MENTION_COMMENT:
            case TYPE_MENTION_CAPTION:
                return (commentText != null && !commentText.isEmpty()) ? commentText : "Tap to view";
            case TYPE_COMMENT_LIKE:
                return (commentText != null && !commentText.isEmpty())
                        ? "\"" + commentText + "\"" : "Tap to view";
            case TYPE_FOLLOWING_POSTED:
                return (commentText != null && !commentText.isEmpty()) ? commentText : "Watch now";
            default:
                return "Tap to view";
        }
    }

    private static String channelFor(String type) {
        switch (type) {
            case TYPE_LIKE:             return ReelNotificationChannelManager.CHANNEL_REEL_LIKES;
            case TYPE_COMMENT:          return ReelNotificationChannelManager.CHANNEL_REEL_COMMENTS;
            case TYPE_COMMENT_LIKE:     return ReelNotificationChannelManager.CHANNEL_REEL_COMMENT_LIKES;
            case TYPE_COMMENT_REPLY:    return ReelNotificationChannelManager.CHANNEL_REEL_COMMENT_REPLIES;
            case TYPE_MENTION_CAPTION:
            case TYPE_MENTION_COMMENT:  return ReelNotificationChannelManager.CHANNEL_REEL_MENTIONS;
            case TYPE_NEW_FOLLOWER:     return ReelNotificationChannelManager.CHANNEL_REEL_NEW_FOLLOWER;
            case TYPE_FOLLOWING_POSTED: return ReelNotificationChannelManager.CHANNEL_REEL_FOLLOWING_POSTED;
            case TYPE_DUET:             return ReelNotificationChannelManager.CHANNEL_REEL_DUET;
            case TYPE_STITCH:           return ReelNotificationChannelManager.CHANNEL_REEL_STITCH;
            default:                    return ReelNotificationChannelManager.CHANNEL_REEL_LIKES;
        }
    }

    private static int iconFor(String type) {
        switch (type) {
            case TYPE_LIKE:
            case TYPE_COMMENT_LIKE:     return R.drawable.ic_heart_filled;
            case TYPE_COMMENT:
            case TYPE_COMMENT_REPLY:    return R.drawable.ic_comment_reel;
            case TYPE_NEW_FOLLOWER:     return R.drawable.ic_person;
            default:                    return R.drawable.ic_reels;
        }
    }

    private static int colorFor(String type) {
        switch (type) {
            case TYPE_LIKE:
            case TYPE_COMMENT:          return 0xFFFF3B5C;
            case TYPE_COMMENT_LIKE:     return 0xFFFF6B6B;
            case TYPE_NEW_FOLLOWER:     return 0xFF34C759;
            case TYPE_DUET:
            case TYPE_STITCH:           return 0xFFFF9500;
            default:                    return 0xFFFF3B5C;
        }
    }

    private static String get(Map<String, String> d, String k) {
        String v = d.get(k);
        return v != null ? v : "";
    }
    private static long parseLong(Map<String, String> d, String k, long def) {
        try { return Long.parseLong(d.getOrDefault(k, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }
    private static double parseDouble(Map<String, String> d, String k, double def) {
        try { return Double.parseDouble(d.getOrDefault(k, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }
}
