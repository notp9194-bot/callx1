package com.callx.app.social;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * v790 — Remove-collaboration controls.
 *  • owner removes a co-creator from his post, or
 *  • a co-creator leaves a post.
 * Marks collabMap/{uid}/status = "removed" (so every reader that already filters on "accepted" drops him),
 * deletes the profile Collabs index + the feed indexes of that co-creator, then recomputes the reel's
 * isCollabPost flag.
 */
public final class CollabControl {
    private CollabControl() {}

    public interface Done { void onDone(boolean ok); }

    public static void remove(@NonNull final String reelId, @NonNull final String collabUid, @Nullable final Done cb) {
        final long now = System.currentTimeMillis();
        final Map<String, Object> up = new HashMap<>();
        up.put("reels/" + reelId + "/collabMap/" + collabUid + "/status", "removed");
        up.put("reels/" + reelId + "/collabMap/" + collabUid + "/removedAt", now);
        up.put("userCollabPosts/" + collabUid + "/" + reelId, null);
        up.put("reelsByUser/" + collabUid + "/" + reelId, null);
        up.put("userReels/" + collabUid + "/" + reelId, null);
        FirebaseUtils.db().getReference().updateChildren(up).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) { if (cb != null) cb.onDone(false); return; }
            recomputeFlag(reelId);
            if (cb != null) cb.onDone(true);
        });
    }

    /** A co-creator leaves: same as remove, for myself. */
    public static void leave(@NonNull String reelId, @Nullable Done cb) {
        final String me = FirebaseUtils.getCurrentUid();
        if (me == null) { if (cb != null) cb.onDone(false); return; }
        remove(reelId, me, cb);
    }

    private static void recomputeFlag(@NonNull final String reelId) {
        FirebaseUtils.db().getReference("reels").child(reelId).child("collabMap")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        boolean anyAccepted = false;
                        for (DataSnapshot c : s.getChildren())
                            if ("accepted".equals(c.child("status").getValue(String.class))) { anyAccepted = true; break; }
                        Map<String, Object> f = new HashMap<>();
                        f.put("isCollabPost", anyAccepted);
                        if (!anyAccepted) {
                            f.put("collabUid", "");
                            f.put("collabDisplayName", "");
                            f.put("collabAvatarUrl", "");
                        }
                        FirebaseUtils.db().getReference("reels").child(reelId).updateChildren(f);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }
}
