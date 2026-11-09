package com.callx.app.social;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * v790 — Can I Remix / Collab with this reel right now?
 *
 * The creator's level lives at reels/{reelId}/remix_settings/remix = everyone | followers | approval | off
 * (missing = everyone, so every old reel keeps working exactly as before).
 *
 *   Remix  : everyone → ALLOWED · followers → ALLOWED if I follow the owner · approval → needs an approved
 *            request (remixApprovals/{reelId}/{myUid}) · off → OFF
 *   Collab : always needs the original creator's approval (he becomes a co-creator), unless he is "off".
 *
 * The database rules enforce "off" and "approval" again when the remix is published (reelRemixes), so this
 * check is only for a clean UI — it is not the security boundary.
 */
public final class RemixAccessHelper {
    private RemixAccessHelper() {}

    public enum Kind { ALLOWED, NEEDS_REQUEST, PENDING, OFF, FOLLOWERS_ONLY, OWN_POST_COLLAB, ERROR }

    public static final class Result {
        public final Kind kind;
        @Nullable public final String requestId;
        Result(Kind k, @Nullable String id) { kind = k; requestId = id; }
    }

    public interface Callback { void onResult(@NonNull Result r); }

    public static void check(@NonNull final RemixCollabTarget t, @NonNull final String mode, @NonNull final Callback cb) {
        final String me = FirebaseUtils.getCurrentUid();
        if (me == null || me.isEmpty() || !t.isValid()) { cb.onResult(new Result(Kind.ERROR, null)); return; }
        final boolean collab = RemixCollabTarget.MODE_COLLAB.equals(mode);
        final boolean mine = me.equals(t.ownerUid);
        if (mine) { cb.onResult(new Result(collab ? Kind.OWN_POST_COLLAB : Kind.ALLOWED, null)); return; }

        FirebaseUtils.db().getReference("reels").child(t.reelId).child("remix_settings").child("remix")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        String lv = s.getValue(String.class);
                        final String level = (lv == null || lv.isEmpty()) ? "everyone" : lv;
                        if ("off".equals(level)) { cb.onResult(new Result(Kind.OFF, null)); return; }
                        if ("followers".equals(level)) {
                            FirebaseUtils.getReelFollowsRef(me).child(t.ownerUid)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot f) {
                                            if (!Boolean.TRUE.equals(f.getValue(Boolean.class))) {
                                                cb.onResult(new Result(Kind.FOLLOWERS_ONLY, null));
                                            } else {
                                                afterLevel(t, me, collab, level, cb);
                                            }
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {
                                            cb.onResult(new Result(Kind.ERROR, null));
                                        }
                                    });
                            return;
                        }
                        afterLevel(t, me, collab, level, cb);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(new Result(Kind.ERROR, null)); }
                });
    }

    private static void afterLevel(final RemixCollabTarget t, final String me, final boolean collab,
                                   final String level, final Callback cb) {
        final boolean needsApproval = collab || "approval".equals(level);
        if (!needsApproval) { cb.onResult(new Result(Kind.ALLOWED, null)); return; }

        FirebaseUtils.db().getReference("remixApprovals").child(t.reelId).child(me)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot a) {
                        String approvedMode = a.child("mode").getValue(String.class);
                        // a collab approval also covers a plain remix; a remix approval does not cover a collab
                        boolean ok = a.exists() && (!collab || RemixCollabTarget.MODE_COLLAB.equals(approvedMode));
                        if (ok) { cb.onResult(new Result(Kind.ALLOWED, null)); return; }
                        RemixRequestHelper.findExisting(t.ownerUid, t.reelId, (id, status) -> {
                            if (id != null && RemixRequestHelper.PENDING.equals(status)) {
                                cb.onResult(new Result(Kind.PENDING, id));
                            } else {
                                cb.onResult(new Result(Kind.NEEDS_REQUEST, null));
                            }
                        });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(new Result(Kind.ERROR, null)); }
                });
    }
}
