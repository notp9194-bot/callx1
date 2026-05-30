package com.callx.app.utils;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class XFirebaseUtils {

    private static final String X_ROOT = "x";

    private static DatabaseReference root() {
        return FirebaseDatabase.getInstance(Constants.DB_URL).getReference(X_ROOT);
    }

    public static DatabaseReference root_x_users() { return root().child("users"); }

    // ── Tweets ──────────────────────────────────────────────────────────────
    public static DatabaseReference tweetsRef()                      { return root().child("tweets"); }
    public static DatabaseReference tweetRef(String id)              { return tweetsRef().child(id); }
    public static DatabaseReference tweetLikesRef(String id)         { return root().child("tweet_likes").child(id); }
    public static DatabaseReference tweetRetweetsRef(String id)      { return root().child("tweet_retweets").child(id); }
    public static DatabaseReference tweetRepliesRef(String id)       { return root().child("tweet_replies").child(id); }
    public static DatabaseReference tweetPollRef(String pollId)      { return root().child("polls").child(pollId); }
    public static DatabaseReference tweetReportsRef(String id)       { return root().child("reports").child(id); }
    public static DatabaseReference tweetEditHistoryRef(String id)   { return root().child("tweet_edits").child(id); }

    // ── Feeds ────────────────────────────────────────────────────────────────
    public static DatabaseReference globalFeedRef()                  { return root().child("global_feed"); }
    public static DatabaseReference userFeedRef(String uid)          { return root().child("user_feeds").child(uid); }
    public static DatabaseReference hashtagFeedRef(String tag)       { return root().child("hashtag_feeds").child(tag); }
    public static DatabaseReference scheduledPostsRef()              { return root().child("scheduled_posts"); }

    // ── Trending ─────────────────────────────────────────────────────────────
    public static DatabaseReference trendingRef()                    { return root().child("trending"); }
    public static DatabaseReference trendingTagRef(String cleanTag)  { return root().child("trending").child(cleanTag); }

    // ── User data ────────────────────────────────────────────────────────────
    public static DatabaseReference xUserRef(String uid)             { return root().child("users").child(uid); }
    public static DatabaseReference userTweetsRef(String uid)        { return root().child("user_tweets").child(uid); }
    public static DatabaseReference userRepliesRef(String uid)       { return root().child("user_replies").child(uid); }
    public static DatabaseReference userMediaTweetsRef(String uid)   { return root().child("user_media_tweets").child(uid); }
    public static DatabaseReference userLikedTweetsRef(String uid)   { return root().child("user_likes").child(uid); }
    public static DatabaseReference userRetweetsRef(String uid)      { return root().child("user_retweets").child(uid); }
    public static DatabaseReference userBookmarksRef(String uid)     { return root().child("user_bookmarks").child(uid); }
    public static DatabaseReference userFollowersRef(String uid)     { return root().child("user_followers").child(uid); }
    public static DatabaseReference userFollowingRef(String uid)     { return root().child("user_following").child(uid); }
    public static DatabaseReference userMutedRef(String uid)         { return root().child("user_muted").child(uid); }
    public static DatabaseReference userBlockedRef(String uid)       { return root().child("user_blocked").child(uid); }
    public static DatabaseReference userProfileViewsRef(String uid)  { return xUserRef(uid).child("profileViews"); }

    // ── Notifications ────────────────────────────────────────────────────────
    public static DatabaseReference xNotificationsRef(String uid)    { return root().child("notifications").child(uid); }
    public static DatabaseReference xUnreadNotifCountRef(String uid)  { return root().child("unread_notif_count").child(uid); }

    // ── DMs ──────────────────────────────────────────────────────────────────
    public static DatabaseReference xDmConversationsRef(String uid)  { return root().child("dm_conversations").child(uid); }
    public static DatabaseReference xDmMessagesRef(String convId)    { return root().child("dm_messages").child(convId); }
    public static DatabaseReference xDmGroupsRef()                   { return root().child("dm_groups"); }
    public static DatabaseReference xDmGroupRef(String groupId)      { return xDmGroupsRef().child(groupId); }
    public static DatabaseReference xDmGroupMessagesRef(String gid)  { return root().child("dm_group_messages").child(gid); }
    public static DatabaseReference xTypingRef(String convId)        { return root().child("dm_typing").child(convId); }
    public static DatabaseReference xDmReactionsRef(String convId, String msgId) {
        return root().child("dm_reactions").child(convId).child(msgId);
    }

    // ── Handle index ─────────────────────────────────────────────────────────
    public static DatabaseReference xHandlesRef()                    { return root().child("x_handles"); }
    public static DatabaseReference xHandleRef(String handle)        { return xHandlesRef().child(handle); }

    // ── Link previews cache ───────────────────────────────────────────────────
    public static DatabaseReference xLinkPreviewRef(String urlKey)   { return root().child("link_previews").child(urlKey); }

    // ── Helpers ───────────────────────────────────────────────────────────────
    public static String dmConversationId(String uid1, String uid2) {
        if (uid1.compareTo(uid2) < 0) return uid1 + "_" + uid2;
        return uid2 + "_" + uid1;
    }

    /** Safe key from URL (replace forbidden chars for Firebase path) */
    public static String urlToKey(String url) {
        return url.replaceAll("[.#$\\[\\]/]", "_");
    }

    // ── Root reference (for batch writes) ────────────────────────────────────
    public static DatabaseReference root_x()                         { return root(); }

    // ── FCM push token ───────────────────────────────────────────────────────
    public static DatabaseReference fcmTokenRef(String uid)          { return root().child("fcm_tokens").child(uid); }

}