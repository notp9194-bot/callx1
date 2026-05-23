package com.callx.app.utils;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class XFirebaseUtils {

    private static final String X_ROOT = "x";

    private static DatabaseReference root() {
        return FirebaseDatabase.getInstance(Constants.DB_URL).getReference(X_ROOT);
    }

    public static DatabaseReference root_x_users() { return root().child("users"); }

    // Tweets
    public static DatabaseReference tweetsRef()                      { return root().child("tweets"); }
    public static DatabaseReference tweetRef(String id)              { return tweetsRef().child(id); }
    public static DatabaseReference tweetLikesRef(String id)         { return root().child("tweet_likes").child(id); }
    public static DatabaseReference tweetRetweetsRef(String id)      { return root().child("tweet_retweets").child(id); }
    public static DatabaseReference tweetRepliesRef(String id)       { return root().child("tweet_replies").child(id); }
    public static DatabaseReference tweetPollRef(String pollId)      { return root().child("polls").child(pollId); }
    public static DatabaseReference tweetReportsRef(String id)       { return root().child("reports").child(id); }

    // Feeds
    public static DatabaseReference globalFeedRef()                  { return root().child("global_feed"); }
    public static DatabaseReference userFeedRef(String uid)          { return root().child("user_feeds").child(uid); }
    public static DatabaseReference hashtagFeedRef(String tag)       { return root().child("hashtag_feeds").child(tag); }

    // Trending — /x/trending/{cleanTag}/count|lastPostAt|displayTag
    public static DatabaseReference trendingRef()                    { return root().child("trending"); }
    public static DatabaseReference trendingTagRef(String cleanTag)  { return root().child("trending").child(cleanTag); }

    // User data
    public static DatabaseReference xUserRef(String uid)             { return root().child("users").child(uid); }
    public static DatabaseReference userTweetsRef(String uid)        { return root().child("user_tweets").child(uid); }
    public static DatabaseReference userLikedTweetsRef(String uid)   { return root().child("user_likes").child(uid); }
    public static DatabaseReference userRetweetsRef(String uid)      { return root().child("user_retweets").child(uid); }
    public static DatabaseReference userBookmarksRef(String uid)     { return root().child("user_bookmarks").child(uid); }
    public static DatabaseReference userFollowersRef(String uid)     { return root().child("user_followers").child(uid); }
    public static DatabaseReference userFollowingRef(String uid)     { return root().child("user_following").child(uid); }
    public static DatabaseReference userMutedRef(String uid)         { return xUserRef(uid).child("muted"); }
    public static DatabaseReference userBlockedRef(String uid)       { return xUserRef(uid).child("blocked"); }

    // Notifications
    public static DatabaseReference xNotificationsRef(String uid)    { return root().child("notifications").child(uid); }
    public static DatabaseReference xUnreadNotifCountRef(String uid)  { return root().child("unread_notif_count").child(uid); }

    // DMs
    public static DatabaseReference xDmConversationsRef(String uid)  { return root().child("dm_conversations").child(uid); }
    public static DatabaseReference xDmMessagesRef(String convId)    { return root().child("dm_messages").child(convId); }

    // handle → uid index for uniqueness check
    public static DatabaseReference xHandlesRef()                    { return root().child("x_handles"); }

    public static String dmConversationId(String uid1, String uid2) {
        if (uid1.compareTo(uid2) < 0) return uid1 + "_" + uid2;
        return uid2 + "_" + uid1;
    }
}
