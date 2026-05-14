package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.HashMap;
import java.util.Map;

@IgnoreExtraProperties
public class ReelComment {
    public String commentId;
    public String uid;
    public String ownerName;
    public String ownerPhoto;
    public String text;
    public long   timestamp;
    public int    likesCount;
    public int    replyCount;
    /** uid → true for every user who liked this comment. */
    public Map<String, Boolean> likedBy;

    // ── Advanced fields ──────────────────────────────────────────────────
    /** True when the reel owner has pinned this comment to the top. */
    public boolean isPinned;
    /** uid → emoji string — emoji reactions from viewers. */
    public Map<String, String> reactions;
    /** True when the comment owner has edited the text after posting. */
    public boolean isEdited;
    /** Timestamp of the last edit (ms). */
    public long editedAt;

    public ReelComment() {}

    public ReelComment(String commentId, String uid, String ownerName,
                       String ownerPhoto, String text, long timestamp) {
        this.commentId  = commentId;
        this.uid        = uid;
        this.ownerName  = ownerName;
        this.ownerPhoto = ownerPhoto;
        this.text       = text;
        this.timestamp  = timestamp;
        this.likesCount = 0;
        this.replyCount = 0;
        this.likedBy    = new HashMap<>();
        this.reactions  = new HashMap<>();
        this.isPinned   = false;
        this.isEdited   = false;
    }

    /** Returns true if the given uid has liked this comment. */
    public boolean isLikedBy(String uid) {
        if (uid == null || likedBy == null) return false;
        Boolean v = likedBy.get(uid);
        return v != null && v;
    }

    /** Returns the emoji this uid reacted with, or null if no reaction. */
    public String getMyReaction(String uid) {
        if (uid == null || reactions == null) return null;
        return reactions.get(uid);
    }
}
