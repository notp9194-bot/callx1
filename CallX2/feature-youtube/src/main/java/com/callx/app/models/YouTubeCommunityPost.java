package com.callx.app.models;

public class YouTubeCommunityPost {
    public String postId;
    public String authorUid;
    public String authorName;
    public String authorPhotoUrl;
    public boolean authorVerified;
    public String  text;
    public String  imageUrl;       // optional attached image
    public String  videoId;        // optional linked video
    public long    likeCount;
    public long    commentCount;
    public long    timestamp;
    public String  pollJson;       // JSON-serialized poll options (optional)

    public YouTubeCommunityPost() {}

    public YouTubeCommunityPost(String postId, String authorUid, String authorName,
                                 String authorPhotoUrl, String text) {
        this.postId          = postId;
        this.authorUid       = authorUid;
        this.authorName      = authorName;
        this.authorPhotoUrl  = authorPhotoUrl;
        this.text            = text;
        this.likeCount       = 0;
        this.commentCount    = 0;
        this.timestamp       = System.currentTimeMillis();
    }
}
