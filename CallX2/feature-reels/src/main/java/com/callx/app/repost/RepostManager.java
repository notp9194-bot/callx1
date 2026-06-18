package com.callx.app.repost;

import com.callx.app.models.RepostModel;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

/**
 * RepostManager — Core logic for all repost operations.
 * Handles: simple repost, quote repost, repost to story, remove repost,
 *          repost count, has-reposted check, repost chain tracking.
 */
public class RepostManager {

    public interface RepostCallback  { void onSuccess(boolean isNowReposted); void onError(String msg); }
    public interface RepostCountCallback { void onCount(long count); }
    public interface HasRepostedCallback { void onResult(boolean hasReposted, RepostModel existing); }

    private static final String REEL_REPOSTS    = "reelReposts";
    private static final String USER_REPOSTS    = "userReposts";
    private static final String REPOST_CAPTIONS = "repostCaptions";
    private static final String REPOST_CHAIN    = "repostChain";
    private static final String REELS           = "reels";
    private static final String STORIES         = "stories";

    private final DatabaseReference db;
    private final String mUid, mName, mPhoto;

    public RepostManager(String uid, String name, String photo) {
        this.db     = FirebaseDatabase.getInstance().getReference();
        this.mUid   = uid;
        this.mName  = name;
        this.mPhoto = photo;
    }

    public String myUid()   { return mUid;   }
    public String myName()  { return mName;  }
    public String myPhoto() { return mPhoto; }

    // ── Toggle (repost ↔ undo) ────────────────────────────────────────────────
    public void toggleRepost(String reelId, String ownerUid, String caption,
                             String type, RepostCallback cb) {
        checkHasReposted(reelId, (has, existing) -> {
            if (has) removeRepost(reelId, cb);
            else     doRepost(reelId, ownerUid, caption, type, cb);
        });
    }

    // ── Simple / Quote / Story repost ─────────────────────────────────────────
    public void doRepost(String reelId, String ownerUid, String caption,
                          String type, RepostCallback cb) {
        String key = db.child(REEL_REPOSTS).child(reelId).push().getKey();
        if (key == null) { cb.onError("DB error"); return; }

        long now = System.currentTimeMillis();
        Map<String, Object> repostMap = new HashMap<>();
        repostMap.put("repostId",      key);
        repostMap.put("reelId",        reelId);
        repostMap.put("reposterId",    mUid);
        repostMap.put("reposterName",  mName);
        repostMap.put("reposterPhoto", mPhoto);
        repostMap.put("caption",       caption != null ? caption : "");
        repostMap.put("repostType",    type    != null ? type    : "simple");
        repostMap.put("timestamp",     now);

        Map<String, Object> updates = new HashMap<>();
        updates.put(REEL_REPOSTS    + "/" + reelId + "/" + key, repostMap);
        updates.put(USER_REPOSTS    + "/" + mUid   + "/" + reelId, key);
        updates.put(REELS           + "/" + reelId + "/repostCount", ServerValue.increment(1));

        if (caption != null && !caption.trim().isEmpty()) {
            Map<String, Object> cap = new HashMap<>();
            cap.put("caption",    caption.trim());
            cap.put("uid",        mUid);
            cap.put("name",       mName);
            cap.put("timestamp",  now);
            cap.put("repostType", type != null ? type : "simple");
            updates.put(REPOST_CAPTIONS + "/" + reelId + "/" + mUid, cap);
        }

        db.updateChildren(updates)
          .addOnSuccessListener(a -> {
              writeChainEntry(reelId, key, ownerUid, now);
              cb.onSuccess(true);
          })
          .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    // ── Remove / Undo repost ─────────────────────────────────────────────────
    public void removeRepost(String reelId, RepostCallback cb) {
        db.child(USER_REPOSTS).child(mUid).child(reelId).get()
          .addOnSuccessListener(snap -> {
            if (!snap.exists()) { cb.onSuccess(false); return; }
            String repostKey = snap.getValue(String.class);
            if (repostKey == null) { cb.onSuccess(false); return; }

            Map<String, Object> del = new HashMap<>();
            del.put(REEL_REPOSTS    + "/" + reelId + "/" + repostKey, null);
            del.put(USER_REPOSTS    + "/" + mUid   + "/" + reelId, null);
            del.put(REPOST_CAPTIONS + "/" + reelId + "/" + mUid, null);
            del.put(REELS           + "/" + reelId + "/repostCount", ServerValue.increment(-1));

            db.updateChildren(del)
              .addOnSuccessListener(a -> cb.onSuccess(false))
              .addOnFailureListener(e -> cb.onError(e.getMessage()));
        });
    }

    // ── Check if already reposted ─────────────────────────────────────────────
    public void checkHasReposted(String reelId, HasRepostedCallback cb) {
        db.child(USER_REPOSTS).child(mUid).child(reelId).get()
          .addOnSuccessListener(snap -> {
            if (!snap.exists()) { cb.onResult(false, null); return; }
            String key = snap.getValue(String.class);
            if (key == null) { cb.onResult(false, null); return; }
            db.child(REEL_REPOSTS).child(reelId).child(key).get()
              .addOnSuccessListener(s2 -> cb.onResult(true, s2.getValue(RepostModel.class)));
        });
    }

    // ── Get live repost count ─────────────────────────────────────────────────
    public void getRepostCount(String reelId, RepostCountCallback cb) {
        db.child(REELS).child(reelId).child("repostCount").get()
          .addOnSuccessListener(snap -> {
            Long c = snap.exists() ? snap.getValue(Long.class) : 0L;
            cb.onCount(Math.max(0L, c != null ? c : 0L));
        });
    }

    // ── Repost to Story (24h) ─────────────────────────────────────────────────
    public void repostToStory(String reelId, String reelVideo, String reelThumb,
                               String ownerName, RepostCallback cb) {
        String key = db.child(STORIES).child(mUid).push().getKey();
        if (key == null) { cb.onError("DB error"); return; }

        Map<String, Object> story = new HashMap<>();
        story.put("storyId",        key);
        story.put("uid",            mUid);
        story.put("name",           mName);
        story.put("photo",          mPhoto);
        story.put("type",           "reel_repost");
        story.put("mediaUrl",       reelVideo  != null ? reelVideo  : "");
        story.put("thumbnailUrl",   reelThumb  != null ? reelThumb  : "");
        story.put("repostedReelId", reelId);
        story.put("repostedFrom",   ownerName  != null ? ownerName  : "");
        story.put("timestamp",      System.currentTimeMillis());
        story.put("expiresAt",      System.currentTimeMillis() + 86_400_000L);

        db.child(STORIES).child(mUid).child(key).updateChildren(story)
          .addOnSuccessListener(a -> cb.onSuccess(true))
          .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    // ── Repost chain entry ────────────────────────────────────────────────────
    private void writeChainEntry(String reelId, String repostKey, String ownerUid, long ts) {
        Map<String, Object> e = new HashMap<>();
        e.put("reposterId",    mUid);
        e.put("reposterName",  mName);
        e.put("reposterPhoto", mPhoto);
        e.put("ownerUid",      ownerUid != null ? ownerUid : "");
        e.put("timestamp",     ts);
        db.child(REPOST_CHAIN).child(reelId).child(repostKey).updateChildren(e);
    }
}
