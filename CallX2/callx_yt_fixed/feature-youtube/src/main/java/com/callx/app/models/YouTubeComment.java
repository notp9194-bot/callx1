package com.callx.app.models;

public class YouTubeComment {
    public String commentId;
    public String videoId;
    public String authorUid;
    public String authorName;
    public String authorPhotoUrl;
    public String text;
    public long   likeCount;
    public long   replyCount;
    public long   timestamp;
    public String parentCommentId; // null if top-level
    public boolean isPinned;
    public boolean isHearted;      // hearted by video owner

    public YouTubeComment() {}

    public YouTubeComment(String commentId, String videoId, String authorUid,
                          String authorName, String authorPhotoUrl, String text) {
        this.commentId       = commentId;
        this.videoId         = videoId;
        this.authorUid       = authorUid;
        this.authorName      = authorName;
        this.authorPhotoUrl  = authorPhotoUrl;
        this.text            = text;
        this.likeCount       = 0;
        this.replyCount      = 0;
        this.timestamp       = System.currentTimeMillis();
        this.isPinned        = false;
        this.isHearted       = false;
    }
}
