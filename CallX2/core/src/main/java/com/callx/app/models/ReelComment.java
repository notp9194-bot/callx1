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
    /** Optional Cloudinary secure_url of a photo attached to this comment
     *  (Instagram-style comment photo). Null/empty when the comment is
     *  text-only. */
    public String imageUrl;
    public long   timestamp;
    public int    likesCount;
    public int    replyCount;
    /** uid → true for every user who liked this comment. */
    public Map<String, Boolean> likedBy;
    /** Denormalized copy of users/{uid}/avatarVersion at the moment this
     *  comment was posted — lets ReelCommentAvatarBinder append the same
     *  &v=&lt;avatarVersion&gt; cache-bust param AvatarUrlBuilder gives every
     *  other avatar in the app (see AvatarUrlBuilder class doc). Firebase
     *  simply leaves this 0 for older comments written before this field
     *  existed; 0 is already AvatarUrlBuilder's documented "omit the param"
     *  value, so old comments keep working exactly as before. */
    public long avatarVersion;

    // ── Advanced fields ──────────────────────────────────────────────────
    /** True when the reel owner has pinned this comment to the top. */
    public boolean isPinned;
    /** uid → emoji string — emoji reactions from viewers. */
    public Map<String, String> reactions;
    /** True when the comment owner has edited the text after posting. */
    public boolean isEdited;
    /** Timestamp of the last edit (ms). */
    public long editedAt;
    /** uid → display name for every user @mentioned in this comment's text
     *  (captured from the mention-autocomplete strip when composing). */
    public Map<String, String> mentions;

    // ── Local-first send state (NOT written to Firebase) ────────────────
    // Mirrors the chat module's "pending"/"failed" pattern (see
    // ChatMessageSender): set client-side only when a comment is composed
    // locally, so the bubble can show instantly instead of waiting on the
    // Firebase round trip, and can flip to a tap-to-retry state on
    // failure/offline instead of silently vanishing. Firebase never has
    // this field (it's excluded from the data map built in postComment()),
    // and @IgnoreExtraProperties means it's simply left null/absent when
    // ReelComment.class is deserialized from a real Firebase snapshot.
    public static final String SEND_STATE_SENDING = "sending";
    public static final String SEND_STATE_FAILED  = "failed";
    /** null = normal/confirmed-sent comment (the default for anything that
     *  came from Firebase). Only set locally while a comment is in flight
     *  or has failed to send. */
    public transient String sendState;

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
