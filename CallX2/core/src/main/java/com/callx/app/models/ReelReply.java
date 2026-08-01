package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Feature 13: ReelReply — a reply to a parent comment.
 * Firebase path: reelCommentReplies/{reelId}/{commentId}/{replyId}/
 *
 * Instagram-level parity: replies are fully interactive — like, reply
 * (tags the user being replied to), edit, delete, report — same as
 * top-level comments, just flattened one level under the parent.
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
    /** uid → true for every user who liked this reply. */
    public Map<String, Boolean> likedBy;

    /** Set when this reply is itself tagging another reply's author
     *  (i.e. user tapped "Reply" on a reply, not the top-level comment).
     *  Instagram flattens all replies to one level and shows "@name" inline. */
    public String mentionUid;
    public String mentionName;

    /** True when the reply owner has edited the text after posting. */
    public boolean isEdited;
    /** Timestamp of the last edit (ms). */
    public long editedAt;

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
        this.likedBy         = new HashMap<>();
        this.isEdited        = false;
    }

    /** Returns true if the given uid has liked this reply. */
    public boolean isLikedBy(String uid) {
        if (uid == null || likedBy == null) return false;
        Boolean v = likedBy.get(uid);
        return v != null && v;
    }
}
