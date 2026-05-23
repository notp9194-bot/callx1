package com.callx.app.models;

import java.util.List;
import java.util.Map;

public class XTweet {
    public String id;
    public String authorUid;
    public String authorName;
    public String authorHandle;
    public String authorPhotoUrl;
    public boolean authorVerified;
    public String text;
    public long   timestamp;
    public long   scheduledAt;       // 0 = not scheduled
    public String mediaUrl;
    public String thumbnailUrl;
    public String mediaType;          // "image" | "video"
    public String replyToTweetId;
    public String quotedTweetId;      // quote tweet
    public String pollId;             // poll (stored separately)
    public List<String> hashtags;
    public List<String> mentions;
    public long  likeCount;
    public long  retweetCount;
    public long  replyCount;
    public long  viewCount;
    public boolean isDeleted;
    public boolean isPinned;

    // Like / RT / bookmark maps {uid: true}
    public Map<String, Boolean> likes;
    public Map<String, Boolean> retweets;
    public Map<String, Boolean> bookmarks;

    public boolean isLikedBy(String uid)      { return uid != null && likes != null && Boolean.TRUE.equals(likes.get(uid)); }
    public boolean isRetweetedBy(String uid)  { return uid != null && retweets != null && Boolean.TRUE.equals(retweets.get(uid)); }
    public boolean isBookmarkedBy(String uid) { return uid != null && bookmarks != null && Boolean.TRUE.equals(bookmarks.get(uid)); }
}
