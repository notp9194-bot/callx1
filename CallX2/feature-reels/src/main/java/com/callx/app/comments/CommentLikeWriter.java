package com.callx.app.comments;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.Constants;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * One atomic multi-path write for a comment/reply like (replaces the old
 * likedBy.setValue() + likesCount.runTransaction() pair).
 *
 * Paths touched in a single updateChildren():
 *   {itemPath}/likesCount                       ServerValue.increment(+1/-1)
 *   userCommentLikes/{me}/{reelId}/{itemId}     true / null   ("did I like it" marker)
 *   {itemPath}/creatorLiked                     true / null   (only when the reel owner likes)
 *   {itemPath}/likedBy/{me}                     null on unlike (clears LEGACY entries; no-op otherwise)
 *
 * itemId is a push() key, so comment ids and reply ids never collide and one
 * userCommentLikes/{me}/{reelId} node holds both.
 */
final class CommentLikeWriter {

    static final String USER_LIKES = "userCommentLikes";

    private CommentLikeWriter() {}

    static DatabaseReference myLikesRef(@NonNull String myUid, @NonNull String reelId) {
        return FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference(USER_LIKES).child(myUid).child(reelId);
    }

    static String commentPath(String reelId, String commentId) {
        return "reelComments/" + reelId + "/" + commentId;
    }

    static String replyPath(String reelId, String parentId, String replyId) {
        return "reelCommentReplies/" + reelId + "/" + parentId + "/" + replyId;
    }

    static void write(@NonNull String itemPath, @NonNull String reelId, @NonNull String itemId,
                      @NonNull String myUid, boolean newLiked, boolean isReelOwner,
                      @Nullable Runnable onFail) {
        Map<String, Object> up = new HashMap<>(4);
        up.put(itemPath + "/likesCount", ServerValue.increment(newLiked ? 1 : -1));
        up.put(USER_LIKES + "/" + myUid + "/" + reelId + "/" + itemId, newLiked ? Boolean.TRUE : null);
        if (isReelOwner) up.put(itemPath + "/creatorLiked", newLiked ? Boolean.TRUE : null);
        if (!newLiked) up.put(itemPath + "/likedBy/" + myUid, null);
        FirebaseDatabase.getInstance(Constants.DB_URL).getReference()
            .updateChildren(up, (err, ref) -> { if (err != null && onFail != null) onFail.run(); });
    }
}
