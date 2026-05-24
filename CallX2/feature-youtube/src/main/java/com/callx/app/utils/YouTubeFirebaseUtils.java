package com.callx.app.utils;

import com.google.firebase.database.DatabaseReference;
import com.callx.app.utils.Constants;
import com.google.firebase.database.FirebaseDatabase;

public class YouTubeFirebaseUtils {

    private static final String YT_ROOT = "youtube";

    private static DatabaseReference root() {
        return FirebaseDatabase.getInstance(Constants.DB_URL).getReference(YT_ROOT);
    }

    // ── Videos ──────────────────────────────────────────────────────────────
    public static DatabaseReference videosRef()                             { return root().child("videos"); }
    public static DatabaseReference videoRef(String videoId)                { return videosRef().child(videoId); }
    public static DatabaseReference videoLikesRef(String videoId)           { return root().child("video_likes").child(videoId); }
    public static DatabaseReference videoDislikesRef(String videoId)        { return root().child("video_dislikes").child(videoId); }
    public static DatabaseReference videoViewsRef(String videoId)           { return root().child("video_views").child(videoId); }
    public static DatabaseReference videoWatchTimeRef(String videoId)       { return root().child("video_watch_time").child(videoId); }
    public static DatabaseReference videoDraftsRef(String uid)              { return root().child("video_drafts").child(uid); }
    public static DatabaseReference videoScheduledRef(String uid)           { return root().child("video_scheduled").child(uid); }

    // ── Shorts ───────────────────────────────────────────────────────────────
    public static DatabaseReference shortsRef()                             { return root().child("shorts"); }
    public static DatabaseReference shortRef(String shortId)                { return shortsRef().child(shortId); }

    // ── Feeds ────────────────────────────────────────────────────────────────
    public static DatabaseReference globalFeedRef()                         { return root().child("global_feed"); }
    public static DatabaseReference trendingRef()                           { return root().child("trending"); }
    public static DatabaseReference categoryFeedRef(String category)        { return root().child("category_feeds").child(category); }
    public static DatabaseReference userFeedRef(String uid)                 { return root().child("user_feeds").child(uid); }
    public static DatabaseReference continueWatchingRef(String uid)         { return root().child("continue_watching").child(uid); }

    // ── Channels ─────────────────────────────────────────────────────────────
    public static DatabaseReference channelsRef()                           { return root().child("channels"); }
    public static DatabaseReference channelRef(String uid)                  { return channelsRef().child(uid); }
    public static DatabaseReference userVideosRef(String uid)               { return root().child("user_videos").child(uid); }
    public static DatabaseReference userShortsRef(String uid)               { return root().child("user_shorts").child(uid); }
    public static DatabaseReference communityPostsRef(String uid)           { return root().child("community_posts").child(uid); }
    public static DatabaseReference globalCommunityFeedRef()                { return root().child("community_feed"); }

    // ── Subscriptions ─────────────────────────────────────────────────────────
    public static DatabaseReference subscriptionsRef(String uid)            { return root().child("subscriptions").child(uid); }
    public static DatabaseReference subscribersRef(String channelUid)       { return root().child("subscribers").child(channelUid); }
    public static DatabaseReference notifTierRef(String uid, String channelUid) {
        return root().child("notif_tiers").child(uid).child(channelUid);
    }

    // ── Comments ──────────────────────────────────────────────────────────────
    public static DatabaseReference commentsRef(String videoId)             { return root().child("comments").child(videoId); }
    public static DatabaseReference commentRef(String videoId, String commentId) {
        return commentsRef(videoId).child(commentId);
    }
    public static DatabaseReference commentRepliesRef(String videoId, String commentId) {
        return root().child("comment_replies").child(videoId).child(commentId);
    }
    public static DatabaseReference commentLikesRef(String videoId, String commentId) {
        return root().child("comment_likes").child(videoId).child(commentId);
    }

    // ── Playlists ─────────────────────────────────────────────────────────────
    public static DatabaseReference playlistsRef(String uid)                { return root().child("playlists").child(uid); }
    public static DatabaseReference playlistRef(String uid, String playlistId) {
        return playlistsRef(uid).child(playlistId);
    }
    public static DatabaseReference playlistVideosRef(String uid, String playlistId) {
        return root().child("playlist_videos").child(uid).child(playlistId);
    }

    // ── User data ─────────────────────────────────────────────────────────────
    public static DatabaseReference watchHistoryRef(String uid)             { return root().child("watch_history").child(uid); }
    public static DatabaseReference watchLaterRef(String uid)               { return root().child("watch_later").child(uid); }
    public static DatabaseReference likedVideosRef(String uid)              { return root().child("liked_videos").child(uid); }
    public static DatabaseReference savedPlaylistsRef(String uid)           { return root().child("saved_playlists").child(uid); }
    public static DatabaseReference notInterestedRef(String uid)            { return root().child("not_interested").child(uid); }
    public static DatabaseReference blockedChannelsRef(String uid)          { return root().child("blocked_channels").child(uid); }
    public static DatabaseReference userWatchTimeRef(String uid)            { return root().child("user_watch_time").child(uid); }
    public static DatabaseReference downloadsRef(String uid)                { return root().child("downloads").child(uid); }

    // ── Notifications ─────────────────────────────────────────────────────────
    public static DatabaseReference notificationsRef(String uid)            { return root().child("notifications").child(uid); }

    // ── Search ────────────────────────────────────────────────────────────────
    public static DatabaseReference searchHistoryRef(String uid)            { return root().child("search_history").child(uid); }

    // ── Live ─────────────────────────────────────────────────────────────────
    public static DatabaseReference liveStreamsRef()                        { return root().child("live_streams"); }
    public static DatabaseReference liveStreamRef(String streamId)          { return liveStreamsRef().child(streamId); }
    public static DatabaseReference liveViewersRef(String streamId)         { return root().child("live_viewers").child(streamId); }
    public static DatabaseReference liveChatRef(String streamId)            { return root().child("live_chat").child(streamId); }

    // ── Reports ───────────────────────────────────────────────────────────────
    public static DatabaseReference reportsRef()                            { return root().child("reports"); }

    // ── Creator Studio ────────────────────────────────────────────────────────
    public static DatabaseReference videoAnalyticsRef(String videoId)       { return root().child("video_analytics").child(videoId); }
    public static DatabaseReference channelAnalyticsRef(String uid)         { return root().child("channel_analytics").child(uid); }
    public static DatabaseReference dailyViewsRef(String uid, String date)  {
        return root().child("daily_views").child(uid).child(date);
    }
}
