package com.callx.app.utils;

  import com.callx.app.utils.Constants;
  import com.google.firebase.database.DatabaseReference;
  import com.google.firebase.database.FirebaseDatabase;

  /**
   * Central Firebase reference factory for the X module.
   * All paths live under the "x" node to avoid collision with existing app data.
   *
   * Structure:
   *   x/tweets/{tweetId}
   *   x/global_feed/{tweetId}       — chronological global timeline
   *   x/user_feeds/{uid}/{tweetId}  — per-user home feed (follower fan-out)
   *   x/tweet_likes/{tweetId}/{uid}
   *   x/tweet_retweets/{tweetId}/{uid}
   *   x/tweet_replies/{tweetId}/{replyTweetId}
   *   x/hashtag_feeds/{tag}/{tweetId}
   *   x/user_tweets/{uid}/{tweetId}
   *   x/user_likes/{uid}/{tweetId}
   *   x/user_retweets/{uid}/{tweetId}
   *   x/user_bookmarks/{uid}/{tweetId}
   *   x/user_followers/{uid}/{followerUid}
   *   x/user_following/{uid}/{followeeUid}
   *   x/user_following/{uid}/{followeeUid}
   *   x/users/{uid}                 — X profile data (name, handle, bio, verified, etc.)
   *   x/notifications/{uid}/{notifId}
   *   x/unread_notif_count/{uid}
   *   x/dm_conversations/{uid}/{conversationId}
   *   x/dm_messages/{conversationId}/{messageId}
   */
  public class XFirebaseUtils {

      private static final String X_ROOT = "x";

      private static DatabaseReference root() {
          return FirebaseDatabase.getInstance(Constants.DB_URL).getReference(X_ROOT);
      }

      // ── Tweets ───────────────────────────────────────────────────────────────
      public static DatabaseReference tweetsRef()                      { return root().child("tweets"); }
      public static DatabaseReference tweetRef(String tweetId)         { return tweetsRef().child(tweetId); }
      public static DatabaseReference tweetLikesRef(String tweetId)    { return root().child("tweet_likes").child(tweetId); }
      public static DatabaseReference tweetRetweetsRef(String tweetId) { return root().child("tweet_retweets").child(tweetId); }
      public static DatabaseReference tweetRepliesRef(String tweetId)  { return root().child("tweet_replies").child(tweetId); }

      // ── Feeds ────────────────────────────────────────────────────────────────
      public static DatabaseReference globalFeedRef()                  { return root().child("global_feed"); }
      public static DatabaseReference userFeedRef(String uid)          { return root().child("user_feeds").child(uid); }
      public static DatabaseReference hashtagFeedRef(String tag)       { return root().child("hashtag_feeds").child(tag); }

      // ── User data ────────────────────────────────────────────────────────────
      public static DatabaseReference xUserRef(String uid)             { return root().child("users").child(uid); }
      public static DatabaseReference userTweetsRef(String uid)        { return root().child("user_tweets").child(uid); }
      public static DatabaseReference userLikedTweetsRef(String uid)   { return root().child("user_likes").child(uid); }
      public static DatabaseReference userRetweetsRef(String uid)      { return root().child("user_retweets").child(uid); }
      public static DatabaseReference userBookmarksRef(String uid)     { return root().child("user_bookmarks").child(uid); }
      public static DatabaseReference userFollowersRef(String uid)     { return root().child("user_followers").child(uid); }
      public static DatabaseReference userFollowingRef(String uid)     { return root().child("user_following").child(uid); }

      // ── Notifications ────────────────────────────────────────────────────────
      public static DatabaseReference xNotificationsRef(String uid)   { return root().child("notifications").child(uid); }
      public static DatabaseReference xUnreadNotifCountRef(String uid){ return root().child("unread_notif_count").child(uid); }

      // ── DMs ──────────────────────────────────────────────────────────────────
      public static DatabaseReference xDmConversationsRef(String uid)  { return root().child("dm_conversations").child(uid); }
      public static DatabaseReference xDmMessagesRef(String convId)    { return root().child("dm_messages").child(convId); }

      /**
       * Deterministic conversation ID — same for both participants regardless of
       * who initiated. Formed by sorting UIDs alphabetically and joining with "_".
       */
      public static String dmConversationId(String uid1, String uid2) {
          if (uid1.compareTo(uid2) < 0) return uid1 + "_" + uid2;
          return uid2 + "_" + uid1;
      }
  }