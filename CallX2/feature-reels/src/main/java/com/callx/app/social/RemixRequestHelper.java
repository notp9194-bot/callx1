package com.callx.app.social;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v789 — Remix / Collab permission requests (design screens 4 + 5).
 *
 * remixRequests/{ownerUid}/{requestId} = { reelId, requesterUid, requesterName, requesterPhoto, thumbUrl, mode("remix"|"collab"),
 *     message, status(pending|approved|rejected|cancelled|expired), ts, expiresAt, decidedAt }
 * remixRequestsSent/{myUid}/{requestId} = { ownerUid, reelId, ts }   (so the requester can list / watch his own requests)
 *
 * The requester sends / cancels; the owner approves / rejects; the "expired" state is set by the Cloud Function
 * expireRemixRequests (48 h). Status changes are validated by the database rules.
 */
public final class RemixRequestHelper {
    private RemixRequestHelper() {}

    public static final long EXPIRY_MS = 48L * 60 * 60 * 1000;
    public static final String PENDING = "pending", APPROVED = "approved", REJECTED = "rejected",
            CANCELLED = "cancelled", EXPIRED = "expired";

    public interface SendCb { void onSent(@NonNull String requestId); void onError(@NonNull String reason); }
    public interface StatusCb { void onStatus(@NonNull String status, long expiresAt); }
    public interface DoneCb { void onDone(boolean ok); }

    private static DatabaseReference root() { return FirebaseUtils.db().getReference(); }

    public static void send(@NonNull String ownerUid, @NonNull String reelId, @Nullable String thumbUrl,
                            @NonNull String mode, @Nullable String message,
                            @Nullable String myName, @Nullable String myPhoto, @NonNull final SendCb cb) {
        final String me = FirebaseUtils.getCurrentUid();
        if (me == null || me.isEmpty()) { cb.onError("not_signed_in"); return; }
        if (me.equals(ownerUid)) { cb.onError("own_post"); return; }
        final String id = root().child("remixRequests").child(ownerUid).push().getKey();
        if (id == null) { cb.onError("no_id"); return; }
        final long now = System.currentTimeMillis();
        final Map<String, Object> row = new HashMap<>();
        row.put("reelId", reelId);
        row.put("requesterUid", me);
        row.put("requesterName", myName == null ? "" : cut(myName, 120));
        row.put("requesterPhoto", myPhoto == null ? "" : cut(myPhoto, 500));
        row.put("thumbUrl", thumbUrl == null ? "" : cut(thumbUrl, 500));
        row.put("mode", "collab".equals(mode) ? "collab" : "remix");
        row.put("message", message == null ? "" : cut(message, 200));
        row.put("status", PENDING);
        row.put("ts", now);
        row.put("expiresAt", now + EXPIRY_MS);
        root().child("remixRequests").child(ownerUid).child(id).setValue(row).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) { cb.onError("write_failed"); return; }
            final Map<String, Object> idx = new HashMap<>();
            idx.put("ownerUid", ownerUid);
            idx.put("reelId", reelId);
            idx.put("mode", "collab".equals(mode) ? "collab" : "remix");
            idx.put("ts", now);
            root().child("remixRequestsSent").child(me).child(id).setValue(idx);   // best effort
            cb.onSent(id);
        });
    }

    /** Requester: withdraw a pending request. */
    public static void cancel(@NonNull String ownerUid, @NonNull String requestId, @Nullable DoneCb cb) {
        setStatus(ownerUid, requestId, CANCELLED, cb);
    }

    /** One row of remixRequests/{owner}/{id} (inbox + status screens). */
    public static final class Request {
        public String id, ownerUid, reelId, requesterUid, requesterName, requesterPhoto, thumbUrl, mode, message, status;
        public long ts, expiresAt, decidedAt;

        /** pending rows past their time read as expired even before the hourly function marks them. */
        public String effectiveStatus() {
            if (PENDING.equals(status) && expiresAt > 0 && expiresAt < System.currentTimeMillis()) return EXPIRED;
            return status == null ? PENDING : status;
        }

        @Nullable
        public static Request from(@NonNull String ownerUid, @NonNull DataSnapshot s) {
            if (s.getKey() == null || !s.exists()) return null;
            Request r = new Request();
            r.id = s.getKey();
            r.ownerUid = ownerUid;
            r.reelId = str(s, "reelId");
            r.requesterUid = str(s, "requesterUid");
            r.requesterName = str(s, "requesterName");
            r.requesterPhoto = str(s, "requesterPhoto");
            r.thumbUrl = str(s, "thumbUrl");
            r.mode = str(s, "mode");
            r.message = str(s, "message");
            r.status = str(s, "status");
            r.ts = lng(s, "ts");
            r.expiresAt = lng(s, "expiresAt");
            r.decidedAt = lng(s, "decidedAt");
            return r;
        }
    }

    private static String str(DataSnapshot s, String k) {
        String v = s.child(k).getValue(String.class);
        return v == null ? "" : v;
    }

    private static long lng(DataSnapshot s, String k) {
        Long v = s.child(k).getValue(Long.class);
        return v == null ? 0L : v;
    }

    /**
     * Owner: approve / reject a pending request. Approve also writes remixApprovals/{reelId}/{requesterUid}
     * (owner-only path) — the database rules read that node to let the requester publish a remix of an
     * "approval required" reel.
     */
    public static void decide(@NonNull Request req, boolean approve, @Nullable final DoneCb cb) {
        final String me = FirebaseUtils.getCurrentUid();
        if (me == null || req.id == null || req.reelId == null || req.requesterUid == null) {
            if (cb != null) cb.onDone(false);
            return;
        }
        final long now = System.currentTimeMillis();
        final Map<String, Object> up = new HashMap<>();
        up.put("remixRequests/" + me + "/" + req.id + "/status", approve ? APPROVED : REJECTED);
        up.put("remixRequests/" + me + "/" + req.id + "/decidedAt", now);
        if (approve) {
            final Map<String, Object> ap = new HashMap<>();
            ap.put("requestId", req.id);
            ap.put("mode", "collab".equals(req.mode) ? "collab" : "remix");
            ap.put("approvedAt", now);
            up.put("remixApprovals/" + req.reelId + "/" + req.requesterUid, ap);
        }
        root().updateChildren(up).addOnCompleteListener(t -> { if (cb != null) cb.onDone(t.isSuccessful()); });
    }

    /** Owner: take back a permission he approved earlier (the requester can no longer publish a remix). */
    public static void revoke(@NonNull String reelId, @NonNull String requesterUid, @Nullable final DoneCb cb) {
        root().child("remixApprovals").child(reelId).child(requesterUid).removeValue()
                .addOnCompleteListener(t -> { if (cb != null) cb.onDone(t.isSuccessful()); });
    }

    /** Owner: hide one remix from "Remixes of @me" and show "original removed" on the remix's own credit strip. */
    public static void blockRemix(@NonNull String originalReelId, @NonNull String remixReelId, boolean block,
                                  @Nullable final DoneCb cb) {
        final DatabaseReference r = root().child("remixBlocks").child(originalReelId).child(remixReelId);
        if (block) {
            r.setValue(System.currentTimeMillis()).addOnCompleteListener(t -> { if (cb != null) cb.onDone(t.isSuccessful()); });
        } else {
            r.removeValue().addOnCompleteListener(t -> { if (cb != null) cb.onDone(t.isSuccessful()); });
        }
    }

    public interface ExistingCb { void onResult(@Nullable String requestId, @Nullable String status); }

    /**
     * Requester: is there already a pending / approved request of mine for this reel? Avoids sending the
     * same request twice. status is the effective one (an overdue pending row reads "expired").
     */
    public static void findExisting(@NonNull final String ownerUid, @NonNull final String reelId,
                                    @NonNull final ExistingCb cb) {
        final String me = FirebaseUtils.getCurrentUid();
        if (me == null) { cb.onResult(null, null); return; }
        root().child("remixRequestsSent").child(me).orderByChild("reelId").equalTo(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        final List<String> ids = new ArrayList<>();
                        for (DataSnapshot c : s.getChildren()) if (c.getKey() != null) ids.add(c.getKey());
                        if (ids.isEmpty()) { cb.onResult(null, null); return; }
                        final int[] left = { ids.size() };
                        final String[] bestId = { null };
                        final String[] bestStatus = { null };
                        final long[] bestTs = { -1L };
                        for (final String id : ids) {
                            root().child("remixRequests").child(ownerUid).child(id)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot rs) {
                                            Request r = Request.from(ownerUid, rs);
                                            if (r != null) {
                                                String st = r.effectiveStatus();
                                                boolean live = PENDING.equals(st) || APPROVED.equals(st);
                                                if (live && r.ts > bestTs[0]) { bestTs[0] = r.ts; bestId[0] = id; bestStatus[0] = st; }
                                            }
                                            if (--left[0] == 0) cb.onResult(bestId[0], bestStatus[0]);
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {
                                            if (--left[0] == 0) cb.onResult(bestId[0], bestStatus[0]);
                                        }
                                    });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(null, null); }
                });
    }

    private static void setStatus(String ownerUid, String requestId, String status, @Nullable final DoneCb cb) {
        final DatabaseReference r = root().child("remixRequests").child(ownerUid).child(requestId);
        final Map<String, Object> up = new HashMap<>();
        up.put("status", status);
        up.put("decidedAt", System.currentTimeMillis());
        r.updateChildren(up).addOnCompleteListener(t -> { if (cb != null) cb.onDone(t.isSuccessful()); });
    }

    /** Requester or owner: live status of one request (screen 5). Returns the listener; remove it with {@link #unwatch}. */
    @NonNull
    public static ValueEventListener watch(@NonNull String ownerUid, @NonNull String requestId, @NonNull final StatusCb cb) {
        final ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String st = s.child("status").getValue(String.class);
                Long exp = s.child("expiresAt").getValue(Long.class);
                long e = exp == null ? 0L : exp;
                if (st == null) return;
                // a pending row past its time is "expired" even before the hourly function has marked it
                if (PENDING.equals(st) && e > 0 && e < System.currentTimeMillis()) st = EXPIRED;
                cb.onStatus(st, e);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        root().child("remixRequests").child(ownerUid).child(requestId).addValueEventListener(l);
        return l;
    }

    public static void unwatch(@NonNull String ownerUid, @NonNull String requestId, @Nullable ValueEventListener l) {
        if (l != null) root().child("remixRequests").child(ownerUid).child(requestId).removeEventListener(l);
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
}
