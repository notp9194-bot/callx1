package com.callx.app.models;

import java.util.List;
import java.util.Map;

public class XTweet {
    public String id;
    public String authorUid;
    public String authorName;
    public String authorHandle;
    public String authorPhotoUrl;
    public String authorThumbUrl;
    public boolean authorVerified;
    public String text;
    public long   timestamp;
    public long   scheduledAt;            // 0 = not scheduled
    public long   editedAt;               // 0 = never edited
    public String editedText;             // original text before last edit
    public String audience;               // "public" | "followers" | "circle"

    // Media — single primary + up to 4-image gallery
    public String mediaUrl;
    public String thumbnailUrl;
    public String mediaType;              // "image" | "video" | "gif"
    public List<String> mediaUrls;        // multi-image (up to 4)
    public List<String> mediaTypes;       // per-image type
    public List<String> mediaAltTexts;    // accessibility alt text

    // Relations
    public String replyToTweetId;
    public String replyToHandle;
    public String quotedTweetId;          // quote tweet
    public String pollId;                 // poll (stored separately)
    public String threadId;               // thread root id (if part of thread)
    public int    threadIndex;            // position in thread (0-based)

    // Link preview
    public String linkPreviewUrl;
    public String linkPreviewTitle;
    public String linkPreviewDesc;
    public String linkPreviewImageUrl;
    public String linkPreviewDomain;

    // Tags
    public List<String> hashtags;
    public List<String> mentions;

    // Counts
    public long  likeCount;
    public long  retweetCount;
    public long  replyCount;
    public long  viewCount;
    public long  bookmarkCount;

    // Flags
    public boolean isDeleted;
    public boolean isPinned;
    public boolean isThread;              // true if this tweet is part of a thread
    public boolean isThreadEnd;           // true if last in thread
    public boolean isSensitive;           // NSFW flag

    // Engagement maps {uid: true}
    public Map<String, Boolean> likes;
    public Map<String, Boolean> retweets;
    public Map<String, Boolean> bookmarks;

    public boolean isLikedBy(String uid)      { return uid != null && likes != null && Boolean.TRUE.equals(likes.get(uid)); }
    public boolean isRetweetedBy(String uid)  { return uid != null && retweets != null && Boolean.TRUE.equals(retweets.get(uid)); }
    public boolean isBookmarkedBy(String uid) { return uid != null && bookmarks != null && Boolean.TRUE.equals(bookmarks.get(uid)); }
    public boolean isMedia()                  { return mediaUrl != null && !mediaUrl.isEmpty(); }
    public boolean isMultiImage()             { return mediaUrls != null && mediaUrls.size() > 1; }
}
