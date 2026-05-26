package com.callx.app.utils;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class YouTubeFirebaseUtils {

    private static final String YT_ROOT = "youtube";

    private static DatabaseReference root() {
        return FirebaseDatabase.getInstance(Constants.DB_URL).getReference(YT_ROOT);
    }

    // ── Videos ──────────────────────────────────────────────────────────────
    public static DatabaseReference videosRef()                          { return root().child("videos"); }
    public static DatabaseReference videoRef(String videoId)             { return videosRef().child(videoId); }
    public static DatabaseReference videoLikesRef(String videoId)        { return root().child("video_likes").child(videoId); }
    public static DatabaseReference videoDislikesRef(String videoId)     { return root().child("video_dislikes").child(videoId); }
    public static DatabaseReference videoViewsRef(String videoId)        { return root().child("video_views").child(videoId); }
    public static DatabaseReference videoSharesRef(String videoId)       { return root().child("video_shares").child(videoId); }

    // ── Shorts ───────────────────────────────────────────────────────────────
    public static DatabaseReference shortsRef()                          { return root().child("shorts"); }
    public static DatabaseReference shortRef(String shortId)             { return shortsRef().child(shortId); }

    // ── Feeds ────────────────────────────────────────────────────────────────
    public static DatabaseReference globalFeedRef()                      { return root().child("global_feed"); }
    public static DatabaseReference trendingRef()                        { return root().child("trending"); }
    public static DatabaseReference categoryFeedRef(String category)     { return root().child("category_feeds").child(category.toLowerCase()); }
    public static DatabaseReference userFeedRef(String uid)              { return root().child("user_feeds").child(uid); }

    // ── Channels ─────────────────────────────────────────────────────────────
    public static DatabaseReference channelsRef()                        { return root().child("channels"); }
    public static DatabaseReference channelRef(String uid)               { return channelsRef().child(uid); }
    public static DatabaseReference userVideosRef(String uid)            { return root().child("user_videos").child(uid); }
    public static DatabaseReference userShortsRef(String uid)            { return root().child("user_shorts").child(uid); }

    // ── Subscriptions ─────────────────────────────────────────────────────────
    public static DatabaseReference subscriptionsRef(String uid)         { return root().child("subscriptions").child(uid); }
    public static DatabaseReference subscribersRef(String channelUid)    { return root().child("subscribers").child(channelUid); }

    // ── Comments ──────────────────────────────────────────────────────────────
    public static DatabaseReference commentsRef(String videoId)          { return root().child("comments").child(videoId); }
    public static DatabaseReference commentRef(String videoId, String id) { return commentsRef(videoId).child(id); }
    public static DatabaseReference commentRepliesRef(String videoId, String commentId) {
        return root().child("comment_replies").child(videoId).child(commentId);
    }
    public static DatabaseReference commentLikesRef(String videoId, String commentId) {
        return root().child("comment_likes").child(videoId).child(commentId);
    }
    public static DatabaseReference commentDislikesRef(String videoId, String commentId) {
        return root().child("comment_dislikes").child(videoId).child(commentId);
    }

    // ── Playlists ─────────────────────────────────────────────────────────────
    public static DatabaseReference playlistsRef(String uid)             { return root().child("playlists").child(uid); }
    public static DatabaseReference playlistRef(String uid, String pid)  { return playlistsRef(uid).child(pid); }
    public static DatabaseReference playlistVideosRef(String uid, String pid) {
        return root().child("playlist_videos").child(uid).child(pid);
    }
    public static DatabaseReference savedPlaylistsRef(String uid)        { return root().child("saved_playlists").child(uid); }

    // ── User Data ─────────────────────────────────────────────────────────────
    public static DatabaseReference watchHistoryRef(String uid)          { return root().child("watch_history").child(uid); }
    public static DatabaseReference watchLaterRef(String uid)            { return root().child("watch_later").child(uid); }
    public static DatabaseReference likedVideosRef(String uid)           { return root().child("liked_videos").child(uid); }
    public static DatabaseReference dislikedVideosRef(String uid)        { return root().child("disliked_videos").child(uid); }
    public static DatabaseReference sharedVideosRef(String uid)          { return root().child("shared_videos").child(uid); }

    // ── Notifications ─────────────────────────────────────────────────────────
    public static DatabaseReference notificationsRef(String uid)         { return root().child("notifications").child(uid); }

    // ── Search ────────────────────────────────────────────────────────────────
    public static DatabaseReference searchHistoryRef(String uid)         { return root().child("search_history").child(uid); }

    // ── Not Interested / Reports ───────────────────────────────────────────────
    public static DatabaseReference notInterestedRef(String uid, String videoId) {
        return root().child("not_interested").child(uid).child(videoId);
    }
    public static DatabaseReference downloadsRef(String uid)             { return root().child("downloads").child(uid); }
    public static DatabaseReference reportsRef(String videoId, String uid) {
        return root().child("reports").child(videoId).child(uid);
    }

    // ── Creator Analytics ─────────────────────────────────────────────────────
    public static DatabaseReference creatorAnalyticsRef(String uid)      { return root().child("creator_analytics").child(uid); }
    public static DatabaseReference videoAnalyticsRef(String videoId)    { return root().child("video_analytics").child(videoId); }
    /** Daily view snapshot: analytics/{uid}/daily/{yyyyMMdd} = viewCount */
    public static DatabaseReference dailyAnalyticsRef(String uid, String dateKey) {
        return creatorAnalyticsRef(uid).child("daily").child(dateKey);
    }

    // ── Trending Score ────────────────────────────────────────────────────────
    public static DatabaseReference trendingScoreRef(String videoId)     { return root().child("trending_scores").child(videoId); }

    // ── Live ─────────────────────────────────────────────────────────────────
    public static DatabaseReference liveStreamsRef()                      { return root().child("live_streams"); }
    public static DatabaseReference liveStreamRef(String streamId)        { return liveStreamsRef().child(streamId); }
    public static DatabaseReference liveChatRef(String streamId)         { return root().child("live_chat").child(streamId); }
}
