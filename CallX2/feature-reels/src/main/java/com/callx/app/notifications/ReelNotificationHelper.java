package com.callx.app.notifications;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.upload.ReelSchedulerActivity;
import com.callx.app.music.ReelTrendingAudioActivity;
import com.callx.app.social.ReelShareSheetActivity;
import com.callx.app.explore.ReelChallengeActivity;
import com.callx.app.analytics.ReelAnalyticsActivity;
import com.callx.app.creator.ReelCreatorFundActivity;
import com.callx.app.creator.ReelGiftingActivity;
import com.callx.app.library.ReelDraftsActivity;
import com.callx.app.live.ReelLiveActivity;
import com.callx.app.followers.ReelCollabInboxActivity;
import com.callx.app.services.ReelNotificationService;

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
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import com.callx.app.reels.R;
import com.callx.app.activities.*;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;

/**
 * ReelNotificationHelper — Central builder for ALL 40+ reel notification types.
 *
 * Usage:
 *   ReelNotificationHelper.showLikeNotification(ctx, likerName, likerPhoto, reelId, reelThumb);
 *   ReelNotificationHelper.showCommentNotification(ctx, ...);
 *   ... (one static method per notification type)
 *
 * Architecture:
 *  - Every method runs avatar/image download on bg thread, then posts notification on UI thread
 *  - Uses NotificationCompat for API 21+ compat
 *  - BigPictureStyle for thumbnail-rich notifications (upload complete, milestone)
 *  - MessagingStyle for comment threads
 *  - InboxStyle for grouped digests
 *  - Action buttons: Like Back, Reply, Follow, Open Reel, etc.
 *  - Deep links into the correct activity via Intent extras
 */
public class ReelNotificationHelper {

    public static final String KEY_REPLY_TEXT  = "reel_reply_text";
    private static final Random RNG = new Random();

    // ─────────────────────────────────────────────────────────────────────
    // 1. LIKE
    // ─────────────────────────────────────────────────────────────────────
    public static void showLikeNotification(Context ctx, String likerName, String likerPhoto,
                                            String reelId, String reelThumb, int likeCount) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(likerPhoto, 100) : null;
            Bitmap thumb  = downloadBitmap(reelThumb, 400, 300);
            String title  = likerName + " liked your reel";
            String body   = likeCount > 1
                ? likerName + " and " + (likeCount - 1) + " others liked your reel"
                : title;
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_LIKES, R.drawable.ic_heart_filled, title, body, 0xFFFF3B5C)
                .setContentIntent(reelPi(ctx, reelId, 0))
                .addAction(R.drawable.ic_heart_filled, "❤️ Like Back", reelPi(ctx, reelId, 1));
            if (avatar != null) b.setLargeIcon(avatar);
            if (thumb  != null) b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(body));
            show(ctx, b, notifId("like", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. COMMENT
    // ─────────────────────────────────────────────────────────────────────
    public static void showCommentNotification(Context ctx, String commenterName, String commenterPhoto,
                                               String reelId, String commentText, String commentId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(commenterPhoto, 100) : null;
            String title  = commenterName + " commented on your reel";
            RemoteInput ri = new RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply…").build();
            PendingIntent replyPi = actionPi(ctx, ReelNotificationActionReceiver.ACTION_REEL_COMMENT_REPLY, reelId, commentId, notifId("comment", reelId));
            NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(R.drawable.ic_comment_reel, "Reply", replyPi).addRemoteInput(ri).build();
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_COMMENTS, R.drawable.ic_comment_reel, title, commentText, 0xFFFF3B5C)
                .setContentIntent(reelPi(ctx, reelId, 0))
                .addAction(replyAction)
                .addAction(R.drawable.ic_heart, "❤️ Like", actionPi(ctx, ReelNotificationActionReceiver.ACTION_LIKE_COMMENT, reelId, commentId, notifId("comment", reelId)));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("comment", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. COMMENT LIKE
    // ─────────────────────────────────────────────────────────────────────
    public static void showCommentLikeNotification(Context ctx, String likerName, String likerPhoto,
                                                   String reelId, String commentText) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(likerPhoto, 100) : null;
            String title  = likerName + " liked your comment";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_COMMENT_LIKES, R.drawable.ic_heart_filled, title, "\"" + commentText + "\"", 0xFFFF6B6B)
                .setContentIntent(reelPi(ctx, reelId, 0));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("clike", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. COMMENT REPLY
    // ─────────────────────────────────────────────────────────────────────
    public static void showCommentReplyNotification(Context ctx, String replierName, String replierPhoto,
                                                    String reelId, String replyText, String commentId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(replierPhoto, 100) : null;
            String title  = replierName + " replied to your comment";
            RemoteInput ri = new RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply…").build();
            PendingIntent replyPi = actionPi(ctx, ReelNotificationActionReceiver.ACTION_REEL_COMMENT_REPLY, reelId, commentId, notifId("creply", reelId));
            NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(R.drawable.ic_comment_reel, "Reply", replyPi).addRemoteInput(ri).build();
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_COMMENT_REPLIES, R.drawable.ic_comment_reel, title, replyText, 0xFFFF3B5C)
                .setContentIntent(reelPi(ctx, reelId, 0))
                .addAction(replyAction);
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("creply", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. MENTION
    // ─────────────────────────────────────────────────────────────────────
    public static void showMentionNotification(Context ctx, String mentionerName, String mentionerPhoto,
                                               String reelId, String context2, String type) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(mentionerPhoto, 100) : null;
            boolean isCaption = "caption".equals(type);
            String title = mentionerName + " mentioned you in " + (isCaption ? "a reel caption" : "a comment");
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_MENTIONS, R.drawable.ic_reels, title, "\"" + context2 + "\"", 0xFFFF3B5C)
                .setContentIntent(reelPi(ctx, reelId, 0))
                .addAction(R.drawable.ic_reels, "View Reel", reelPi(ctx, reelId, 0));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("mention", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. NEW FOLLOWER
    // ─────────────────────────────────────────────────────────────────────
    public static void showNewFollowerNotification(Context ctx, String followerName, String followerPhoto,
                                                   String followerUid) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(followerPhoto, 100) : null;
            String title  = followerName + " started following you";
            String body   = "Tap to view their profile and follow back";
            Intent profileIntent = new Intent().setClassName(ctx, "com.callx.app.activities.ProfileActivity");
            profileIntent.putExtra("uid", followerUid);
            profileIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent profilePi = PendingIntent.getActivity(ctx, notifId("follow", followerUid), profileIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent followBackPi = actionPi(ctx, ReelNotificationActionReceiver.ACTION_FOLLOW_BACK, followerUid, "", notifId("follow", followerUid));
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_NEW_FOLLOWER, R.drawable.ic_person_add, title, body, 0xFF34C759)
                .setContentIntent(profilePi)
                .addAction(R.drawable.ic_person_add, "Follow Back", followBackPi);
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("follow", followerUid));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7. FOLLOWING USER POSTED
    // ─────────────────────────────────────────────────────────────────────
    public static void showFollowingPostedNotification(Context ctx, String posterName, String posterPhoto,
                                                       String reelId, String reelThumb, String caption) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(posterPhoto, 100) : null;
            Bitmap thumb  = downloadBitmap(reelThumb, 400, 300);
            String title  = posterName + " posted a new reel";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_FOLLOWING_POSTED, R.drawable.ic_reels, title, caption != null && !caption.isEmpty() ? caption : "Watch now", 0xFF5AC8FA)
                .setContentIntent(reelPi(ctx, reelId, 0));
            if (avatar != null) b.setLargeIcon(avatar);
            if (thumb  != null) b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(caption));
            show(ctx, b, notifId("posted", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 8. DUET
    // ─────────────────────────────────────────────────────────────────────
    public static void showDuetNotification(Context ctx, String dueterName, String dueterPhoto,
                                            String originalReelId, String duetReelId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(dueterPhoto, 100) : null;
            String title  = dueterName + " dueted your reel!";
            String body   = "Tap to watch their duet";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_DUET, R.drawable.ic_reels, title, body, 0xFFFF9500)
                .setContentIntent(reelPi(ctx, duetReelId, 0))
                .addAction(R.drawable.ic_reels, "Watch Duet", reelPi(ctx, duetReelId, 0));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("duet", originalReelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 9. STITCH
    // ─────────────────────────────────────────────────────────────────────
    public static void showStitchNotification(Context ctx, String stitcherName, String stitcherPhoto,
                                              String originalReelId, String stitchReelId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(stitcherPhoto, 100) : null;
            String title  = stitcherName + " stitched your reel!";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_STITCH, R.drawable.ic_reels, title, "See what they created", 0xFFFF9500)
                .setContentIntent(reelPi(ctx, stitchReelId, 0));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("stitch", originalReelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 10. VIDEO REPLY
    // ─────────────────────────────────────────────────────────────────────
    public static void showVideoReplyNotification(Context ctx, String replierName, String replierPhoto,
                                                  String reelId, String replyReelId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(replierPhoto, 100) : null;
            String title  = replierName + " replied to your reel with a video";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_VIDEO_REPLY, R.drawable.ic_reels, title, "Tap to watch their reply", 0xFFFF9500)
                .setContentIntent(reelPi(ctx, replyReelId, 0));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("videoreply", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 11. COLLAB REQUEST
    // ─────────────────────────────────────────────────────────────────────
    public static void showCollabRequestNotification(Context ctx, String requesterName, String requesterPhoto,
                                                     String reelId, String collabId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(requesterPhoto, 100) : null;
            String title  = requesterName + " invited you to collab!";
            PendingIntent acceptPi = actionPi(ctx, ReelNotificationActionReceiver.ACTION_COLLAB_ACCEPT, reelId, collabId, notifId("collab", collabId));
            PendingIntent declinePi= actionPi(ctx, ReelNotificationActionReceiver.ACTION_COLLAB_DECLINE, reelId, collabId, notifId("collab", collabId));
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_COLLAB_REQUEST, R.drawable.ic_reels, title, "Tap to view the collab invitation", 0xFF5856D6)
                .setContentIntent(reelPi(ctx, reelId, 0))
                .addAction(R.drawable.ic_person_add, "✓ Accept", acceptPi)
                .addAction(R.drawable.ic_close,      "✗ Decline", declinePi);
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("collab", collabId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 12. COLLAB ACCEPTED
    // ─────────────────────────────────────────────────────────────────────
    public static void showCollabAcceptedNotification(Context ctx, String partnerName, String partnerPhoto, String collabId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(partnerPhoto, 100) : null;
            String title  = partnerName + " accepted your collab invite!";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_COLLAB_ACCEPTED, R.drawable.ic_reels, title, "Start recording your collab now", 0xFF34C759)
                .setContentIntent(genericPi(ctx, com.callx.app.followers.ReelCollabInboxActivity.class));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("collabac", collabId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 13. GIFT RECEIVED
    // ─────────────────────────────────────────────────────────────────────
    public static void showGiftNotification(Context ctx, String gifterName, String gifterPhoto,
                                            String giftName, long coinValue, String reelId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(gifterPhoto, 100) : null;
            String title  = "🎁 " + gifterName + " sent you " + giftName + "!";
            String body   = "+" + coinValue + " coins added to your balance";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_GIFTING, R.drawable.ic_music_note, title, body, 0xFFFF2D55)
                .setContentIntent(genericPi(ctx, ReelGiftingActivity.class))
                .addAction(R.drawable.ic_heart_filled, "💰 View Balance", genericPi(ctx, ReelCreatorFundActivity.class));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("gift", gifterName));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 14. LIVE STARTED
    // ─────────────────────────────────────────────────────────────────────
    public static void showLiveStartedNotification(Context ctx, String creatorName, String creatorPhoto,
                                                   String liveId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(creatorPhoto, 100) : null;
            String title  = creatorName + " is live now! 📡";
            String body   = "Join their live stream";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_LIVE_STARTED, R.drawable.ic_video, title, body, 0xFFFF3B5C)
                .setContentIntent(genericPi(ctx, ReelLiveActivity.class))
                .addAction(R.drawable.ic_video, "📡 Join Live", genericPi(ctx, ReelLiveActivity.class));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("live", liveId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 15. LIVE VIEWER MILESTONE
    // ─────────────────────────────────────────────────────────────────────
    public static void showLiveViewerMilestone(Context ctx, long viewerCount) {
        String title = "🔥 " + fmt(viewerCount) + " viewers on your live!";
        String body  = "Keep it up! Your audience is growing.";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_LIVE_MILESTONE, R.drawable.ic_eye_off, title, body, 0xFFFFCC00);
        show(ctx, b, notifId("livemile", String.valueOf(viewerCount)));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 16. CLOSE FRIEND LIVE
    // ─────────────────────────────────────────────────────────────────────
    public static void showCloseFriendLiveNotification(Context ctx, String friendName, String friendPhoto, String liveId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(friendPhoto, 100) : null;
            String title  = "🌟 " + friendName + " (Close Friend) is live!";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_CLOSE_FRIEND_LIVE, R.drawable.ic_video, title, "Join their private live now", 0xFF34C759)
                .setContentIntent(genericPi(ctx, ReelLiveActivity.class));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("cflive", liveId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 17. TRENDING ALERT
    // ─────────────────────────────────────────────────────────────────────
    public static void showTrendingNotification(Context ctx, String reelId, String reelThumb, long viewCount) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap thumb = downloadBitmap(reelThumb, 400, 300);
            String title = "📈 Your reel is trending!";
            String body  = fmt(viewCount) + " views in the last hour";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_TRENDING, R.drawable.ic_reels, title, body, 0xFFFF3B5C)
                .setContentIntent(reelPi(ctx, reelId, 0));
            if (thumb != null) b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(body));
            show(ctx, b, notifId("trend", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 18. VIRAL ALERT
    // ─────────────────────────────────────────────────────────────────────
    public static void showViralNotification(Context ctx, String reelId, String reelThumb, long viewCount) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap thumb = downloadBitmap(reelThumb, 400, 300);
            String title = "🚀 Your reel went VIRAL!";
            String body  = fmt(viewCount) + " total views!";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_VIRAL, R.drawable.ic_reels, title, body, 0xFFFF3B5C)
                .setContentIntent(reelPi(ctx, reelId, 0));
            if (thumb != null) b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(body));
            show(ctx, b, notifId("viral", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 19. VIEW MILESTONE
    // ─────────────────────────────────────────────────────────────────────
    public static void showViewMilestoneNotification(Context ctx, String reelId, long milestone) {
        String title = "🏆 " + fmt(milestone) + " views on your reel!";
        String body  = "Congratulations! Keep creating great content.";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_VIEW_MILESTONE, R.drawable.ic_eye_off, title, body, 0xFFFFD700)
            .setContentIntent(reelPi(ctx, reelId, 0))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(title + "\n\n" + body + "\n\n🎉 Share this milestone!"));
        show(ctx, b, notifId("viewmile", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 20. FOLLOWER MILESTONE
    // ─────────────────────────────────────────────────────────────────────
    public static void showFollowerMilestoneNotification(Context ctx, long milestone) {
        String title = "🎉 " + fmt(milestone) + " followers!";
        String body  = "You've reached " + fmt(milestone) + " followers. Thank your community!";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_FOLLOWER_MILESTONE, R.drawable.ic_person, title, body, 0xFFFFD700)
            .addAction(R.drawable.ic_share_reel, "Share Milestone", genericPi(ctx, ReelShareSheetActivity.class));
        show(ctx, b, notifId("folmile", String.valueOf(milestone)));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 21. UPLOAD COMPLETE
    // ─────────────────────────────────────────────────────────────────────
    public static void showUploadCompleteNotification(Context ctx, String reelId, String reelThumb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap thumb = downloadBitmap(reelThumb, 400, 300);
            String title = "✅ Reel uploaded successfully!";
            String body  = "Your reel is now live. Share it with your followers!";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_UPLOAD_COMPLETE, R.drawable.ic_reels, title, body, 0xFF34C759)
                .setContentIntent(reelPi(ctx, reelId, 0))
                .addAction(R.drawable.ic_share_reel, "Share Now", genericPi(ctx, ReelShareSheetActivity.class));
            if (thumb != null) b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(body));
            show(ctx, b, notifId("upload", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 22. UPLOAD FAILED
    // ─────────────────────────────────────────────────────────────────────
    public static void showUploadFailedNotification(Context ctx, String draftId, String reason) {
        String title = "❌ Reel upload failed";
        String body  = "Reason: " + reason + "\nTap to retry from Drafts.";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_UPLOAD_FAILED, R.drawable.ic_reels, title, body, 0xFFFF3B5C)
            .setContentIntent(genericPi(ctx, ReelDraftsActivity.class))
            .addAction(R.drawable.ic_reels, "Retry Upload", genericPi(ctx, ReelDraftsActivity.class))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        show(ctx, b, notifId("uploadfail", draftId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 23. SCHEDULED POST (auto-posted)
    // ─────────────────────────────────────────────────────────────────────
    public static void showScheduledPostNotification(Context ctx, String reelId, String scheduledTime) {
        String title = "🕐 Scheduled reel is now live!";
        String body  = "Your reel scheduled for " + scheduledTime + " was posted successfully.";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_SCHEDULED_POST, R.drawable.ic_timer, title, body, 0xFF5AC8FA)
            .setContentIntent(reelPi(ctx, reelId, 0));
        show(ctx, b, notifId("sched", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 24. SCHEDULED REMINDER (30 min before)
    // ─────────────────────────────────────────────────────────────────────
    public static void showScheduledReminderNotification(Context ctx, String scheduledAt) {
        String title = "⏰ Scheduled reel in 30 minutes";
        String body  = "Your reel is scheduled for " + scheduledAt + ". Make sure everything is ready!";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_SCHEDULED_REMINDER, R.drawable.ic_timer, title, body, 0xFFFFCC00)
            .setContentIntent(genericPi(ctx, ReelSchedulerActivity.class))
            .addAction(R.drawable.ic_timer, "View Schedule", genericPi(ctx, ReelSchedulerActivity.class));
        show(ctx, b, notifId("schedrem", scheduledAt));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 25. PRODUCT TAG CLICK
    // ─────────────────────────────────────────────────────────────────────
    public static void showProductTagClickNotification(Context ctx, String productName, String reelId, long clickCount) {
        String title = "🛍️ " + clickCount + " people tapped your product tag!";
        String body  = "Product: " + productName;
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_PRODUCT_TAG, R.drawable.ic_reels, title, body, 0xFF34C759)
            .setContentIntent(reelPi(ctx, reelId, 0));
        show(ctx, b, notifId("prodtag", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 26. CREATOR FUND PAYOUT
    // ─────────────────────────────────────────────────────────────────────
    public static void showCreatorFundPayoutNotification(Context ctx, long coins, double usd) {
        String title = "💰 Creator Fund payout ready!";
        String body  = String.format(Locale.US, "%s coins ($%.2f) are ready for withdrawal.", fmt(coins), usd);
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_CREATOR_FUND, R.drawable.ic_music_note, title, body, 0xFFFFD700)
            .setContentIntent(genericPi(ctx, ReelCreatorFundActivity.class))
            .addAction(R.drawable.ic_music_note, "💰 Withdraw Now", genericPi(ctx, ReelCreatorFundActivity.class))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        show(ctx, b, notifId("fund", "payout"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 27. MODERATION - CONTENT REMOVED
    // ─────────────────────────────────────────────────────────────────────
    public static void showContentRemovedNotification(Context ctx, String reelId, String reason) {
        String title = "⚠️ Your reel was removed";
        String body  = "Reason: " + reason + "\nYou can appeal this decision in the Help Center.";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_MODERATION, R.drawable.ic_shield, title, body, 0xFFFF3B5C)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        show(ctx, b, notifId("mod", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 28. REPORT RESOLVED
    // ─────────────────────────────────────────────────────────────────────
    public static void showReportResolvedNotification(Context ctx, String reportId, String decision) {
        String title = "🛡️ Report reviewed";
        String body  = "Decision: " + decision;
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_REPORT_RESOLVED, R.drawable.ic_shield, title, body, 0xFFAAAAAA);
        show(ctx, b, notifId("report", reportId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 29. SOUND TRENDING
    // ─────────────────────────────────────────────────────────────────────
    public static void showSoundTrendingNotification(Context ctx, String soundTitle, String soundId, long useCount) {
        String title = "🎵 \"" + soundTitle + "\" is trending!";
        String body  = fmt(useCount) + " reels are using this sound now.";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_SOUND_TRENDING, R.drawable.ic_music_note, title, body, 0xFF5AC8FA)
            .setContentIntent(genericPi(ctx, ReelTrendingAudioActivity.class))
            .addAction(R.drawable.ic_add_reels, "Make a Reel", genericPi(ctx, ReelCameraActivity.class));
        show(ctx, b, notifId("sound", soundId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 30. PINNED COMMENT
    // ─────────────────────────────────────────────────────────────────────
    public static void showPinnedCommentNotification(Context ctx, String creatorName, String reelId, String commentText) {
        String title = "📌 " + creatorName + " pinned your comment!";
        String body  = "\"" + commentText + "\"";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_PINNED_COMMENT, R.drawable.ic_comment_reel, title, body, 0xFF5856D6)
            .setContentIntent(reelPi(ctx, reelId, 0));
        show(ctx, b, notifId("pin", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 31. CLOSE FRIEND POSTED
    // ─────────────────────────────────────────────────────────────────────
    public static void showCloseFriendPostedNotification(Context ctx, String friendName, String friendPhoto,
                                                         String reelId, String reelThumb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(friendPhoto, 100) : null;
            Bitmap thumb  = downloadBitmap(reelThumb, 400, 300);
            String title  = "🌟 " + friendName + " (Close Friend) posted a reel";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_CLOSE_FRIEND_POST, R.drawable.ic_reels, title, "Tap to watch", 0xFF34C759)
                .setContentIntent(reelPi(ctx, reelId, 0));
            if (avatar != null) b.setLargeIcon(avatar);
            if (thumb  != null) b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb));
            show(ctx, b, notifId("cfpost", reelId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 32. CHALLENGE INVITE
    // ─────────────────────────────────────────────────────────────────────
    public static void showChallengeNotification(Context ctx, String challengeName, String hashtag,
                                                 String challengeId, boolean isUpdate) {
        String title = isUpdate ? "🏆 Challenge update: " + hashtag : "🏆 New challenge: " + hashtag;
        String body  = isUpdate ? challengeName + " challenge results are in!" : "Join the " + challengeName + " and show your talent!";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_CHALLENGE, R.drawable.ic_reels, title, body, 0xFFFF9500)
            .setContentIntent(genericPi(ctx, ReelChallengeActivity.class))
            .addAction(R.drawable.ic_reel_camera, "🎬 Participate", genericPi(ctx, ReelCameraActivity.class));
        show(ctx, b, notifId("challenge", challengeId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 33. REEL SHARED
    // ─────────────────────────────────────────────────────────────────────
    public static void showReelSharedNotification(Context ctx, String sharerName, String reelId) {
        String title = sharerName + " shared your reel";
        String body  = "Your reel is being spread to more people!";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_SHARES, R.drawable.ic_share_reel, title, body, 0xFFAAAAAA)
            .setContentIntent(reelPi(ctx, reelId, 0));
        show(ctx, b, notifId("share", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 34. REEL SAVED
    // ─────────────────────────────────────────────────────────────────────
    public static void showReelSavedNotification(Context ctx, String saverName, String reelId) {
        String title = saverName + " saved your reel";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_SAVED, R.drawable.ic_bookmark_filled, title, "They bookmarked your content", 0xFFAAAAAA)
            .setContentIntent(reelPi(ctx, reelId, 0));
        show(ctx, b, notifId("saved", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 35. WEEKLY DIGEST
    // ─────────────────────────────────────────────────────────────────────
    public static void showWeeklyDigestNotification(Context ctx, long totalViews, long newFollowers,
                                                    long totalLikes, long totalComments) {
        String title = "📊 Your weekly creator summary";
        String body  = String.format(Locale.US,
            "%s views · %s new followers · %s likes · %s comments",
            fmt(totalViews), fmt(newFollowers), fmt(totalLikes), fmt(totalComments));
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_WEEKLY_DIGEST, R.drawable.ic_reels, title, body, 0xFF5AC8FA)
            .setContentIntent(genericPi(ctx, ReelAnalyticsActivity.class))
            .setStyle(new NotificationCompat.BigTextStyle()
                .setBigContentTitle(title)
                .bigText("Views: " + fmt(totalViews) + "\nNew Followers: " + fmt(newFollowers)
                    + "\nLikes: " + fmt(totalLikes) + "\nComments: " + fmt(totalComments)));
        show(ctx, b, notifId("digest", "weekly"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 36. REEL DOWNLOADED
    // ─────────────────────────────────────────────────────────────────────
    public static void showReelDownloadedNotification(Context ctx, String downloaderName, String reelId) {
        String title = downloaderName + " downloaded your reel";
        NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_DOWNLOADED, R.drawable.ic_download_reel, title, "Your content is being saved by others", 0xFFAAAAAA)
            .setContentIntent(reelPi(ctx, reelId, 0));
        show(ctx, b, notifId("dl", reelId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 37. COLLAB LIVE INVITE
    // ─────────────────────────────────────────────────────────────────────
    public static void showCollabLiveNotification(Context ctx, String hostName, String hostPhoto, String liveId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int net = getNetworkLevel(ctx);
            Bitmap avatar = (net >= 2) ? downloadCircle(hostPhoto, 100) : null;
            String title  = hostName + " invited you to join their live collab!";
            NotificationCompat.Builder b = base(ctx, ReelNotificationChannelManager.CHANNEL_REEL_COLLAB_LIVE, R.drawable.ic_video, title, "Join now as a co-host", 0xFF5856D6)
                .setContentIntent(genericPi(ctx, ReelLiveActivity.class))
                .addAction(R.drawable.ic_video, "Join Live", genericPi(ctx, ReelLiveActivity.class));
            if (avatar != null) b.setLargeIcon(avatar);
            show(ctx, b, notifId("collablive", liveId));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // UTILITY METHODS
    // ─────────────────────────────────────────────────────────────────────

    private static NotificationCompat.Builder base(Context ctx, String channel, int icon,
                                                   String title, String body, int color) {
        return new NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setColor(color)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL);
    }

    private static void show(Context ctx, NotificationCompat.Builder b, int id) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, b.build());
    }

    private static PendingIntent reelPi(Context ctx, String reelId, int extra) {
        Intent i = new Intent(ctx, SingleReelPlayerActivity.class);
        i.putExtra("reel_id", reelId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, notifId("reel_open", reelId) + extra, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent genericPi(Context ctx, Class<?> target) {
        Intent i = new Intent(ctx, target);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, RNG.nextInt(100000), i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent actionPi(Context ctx, String action, String reelId, String extra, int reqCode) {
        Intent i = new Intent(ctx, ReelNotificationActionReceiver.class);
        i.setAction(action);
        i.putExtra("reel_id", reelId);
        i.putExtra("extra",   extra);
        i.putExtra("notif_id", reqCode);
        return PendingIntent.getBroadcast(ctx, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int notifId(String type, String key) {
        return (type + "_" + key).hashCode();
    }

    // ── Network level helper ─────────────────────────────────────────────────
    // Returns: 0 = no network, 1 = 2G/EDGE, 2 = 3G, 3 = 4G/5G/WiFi
    static int getNetworkLevel(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return 0;
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return 0;
            if (info.getType() == ConnectivityManager.TYPE_WIFI) return 3;
            int sub = info.getSubtype();
            switch (sub) {
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    return 1; // 2G
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                    return 2; // 3G
                default:
                    return 3; // 4G/5G
            }
        } catch (Exception e) { return 2; } // safe fallback
    }

    private static Bitmap downloadBitmap(String url, int maxW, int maxH) {
        if (url == null || url.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000); conn.setReadTimeout(10000);
            conn.connect();
            InputStream is = conn.getInputStream();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            return BitmapFactory.decodeStream(is, null, opts);
        } catch (Exception e) { return null; }
    }

    private static Bitmap downloadCircle(String url, int size) {
        Bitmap bm = downloadBitmap(url, size, size);
        if (bm == null) return null;
        bm = Bitmap.createScaledBitmap(bm, size, size, true);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(bm, 0, 0, p);
        return out;
    }

    private static String fmt(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
    // ── Public accessors for ReelNotificationService (foreground service) ─
    public static Bitmap downloadCirclePublic(String url, int size) {
        return downloadCircle(url, size);
    }
    public static Bitmap downloadBitmapPublic(String url, int maxW, int maxH) {
        return downloadBitmap(url, maxW, maxH);
    }


      // ─────────────────────────────────────────────────────────────────────
      // PRODUCT TAG SALE  (MISSING TYPE — was not implemented)
      // ─────────────────────────────────────────────────────────────────────
      public static void showProductTagSaleNotification(Context ctx, String productName,
                                                        long saleAmount, String reelId) {
          Executors.newSingleThreadExecutor().execute(() -> {
              String title = "\uD83D\uDED2 Product sold via your reel!";
              String body  = (productName != null && !productName.isEmpty() ? productName : "A product") +
                             (saleAmount > 0 ? " — \u20B9" + saleAmount : "");
              NotificationCompat.Builder b = base(ctx,
                      ReelNotificationChannelManager.CHANNEL_REEL_PRODUCT_TAG,
                      R.drawable.ic_reels, title, body, 0xFF34C759)
                  .setContentIntent(reelPi(ctx, reelId, 0))
                  .addAction(R.drawable.ic_reels, "View Reel", reelPi(ctx, reelId, 0));
              show(ctx, b, notifId("product_sale", reelId != null ? reelId : "none"));
          });
      }

      // ─────────────────────────────────────────────────────────────────────
      // CHALLENGE UPDATE  (MISSING TYPE — only generic 'challenge' existed)
      // ─────────────────────────────────────────────────────────────────────
      public static void showChallengeUpdateNotification(Context ctx, String challengeName,
                                                         String hashtag, String challengeId,
                                                         String updateText) {
          Executors.newSingleThreadExecutor().execute(() -> {
              String ht    = (hashtag != null && !hashtag.isEmpty()) ? hashtag : challengeName;
              String title = "\uD83C\uDFC6 Challenge update: #" + (ht != null ? ht : "challenge");
              String body  = (updateText != null && !updateText.isEmpty()) ? updateText
                           : "New updates for " + challengeName;
              Intent intent = new Intent().setClassName(ctx, "com.callx.app.activities.MainActivity");
              intent.putExtra("challenge_id", challengeId);
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              PendingIntent pi = PendingIntent.getActivity(ctx,
                  notifId("chupd", challengeId != null ? challengeId : "chdef"), intent,
                  PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
              NotificationCompat.Builder b = base(ctx,
                      ReelNotificationChannelManager.CHANNEL_REEL_CHALLENGE,
                      R.drawable.ic_reels, title, body, 0xFFFF9500)
                  .setContentIntent(pi)
                  .addAction(R.drawable.ic_reels, "View Challenge", pi);
              show(ctx, b, notifId("chupd", challengeId != null ? challengeId : "chdef"));
          });
      }

      // ─────────────────────────────────────────────────────────────────────
      // REEL RECOMMENDATION  (MISSING TYPE — completely new)
      // ─────────────────────────────────────────────────────────────────────
      public static void showReelRecommendationNotification(Context ctx, String reelId,
                                                            String reelThumb, String reason) {
          Executors.newSingleThreadExecutor().execute(() -> {
              String title = "\u2728 Recommended for you";
              String body  = (reason != null && !reason.isEmpty()) ? reason
                           : "A reel we think you'll love";
              Bitmap thumb = (reelThumb != null && !reelThumb.isEmpty())
                           ? downloadBitmap(reelThumb, 400, 300) : null;
              NotificationCompat.Builder b = base(ctx,
                      ReelNotificationChannelManager.CHANNEL_REEL_RECOMMENDATION,
                      R.drawable.ic_reels, title, body, 0xFFAAAAAA)
                  .setContentIntent(reelPi(ctx, reelId, 0));
              if (thumb != null)
                  b.setStyle(new NotificationCompat.BigPictureStyle()
                      .bigPicture(thumb).setSummaryText(body));
              show(ctx, b, notifId("recommend", reelId != null ? reelId : "recodef"));
          });
      }
  
}