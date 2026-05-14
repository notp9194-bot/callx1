package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

/**
 * Feature 13: ReelReply — a reply to a parent comment.
 * Firebase path: reelCommentReplies/{reelId}/{commentId}/{replyId}/
 */
@IgnoreExtraProperties
public class ReelReply {
    public String replyId;
    public String parentCommentId;
    public String uid;
    public String ownerName;
    public String ownerPhoto;
    public String text;
    public long   timestamp;
    public int    likesCount;

    public ReelReply() {}

    public ReelReply(String replyId, String parentCommentId, String uid,
                     String ownerName, String ownerPhoto, String text, long timestamp) {
        this.replyId         = replyId;
        this.parentCommentId = parentCommentId;
        this.uid             = uid;
        this.ownerName       = ownerName;
        this.ownerPhoto      = ownerPhoto;
        this.text            = text;
        this.timestamp       = timestamp;
        this.likesCount      = 0;
    }
}
