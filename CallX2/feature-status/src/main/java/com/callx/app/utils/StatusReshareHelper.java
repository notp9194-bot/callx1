package com.callx.app.utils;

import android.content.Context;
import android.content.Intent;

import com.callx.app.models.ReelModel;
import com.callx.app.social.StoryReshareActivity;

/**
 * Static helper for launching the Story Reshare flow from anywhere in the app.
 *
 * Usage:
 *   if (StatusReshareHelper.canReshareReel(reel)) {
 *       startActivity(StatusReshareHelper.buildReshareReelIntent(context, reel));
 *   }
 */
public final class StatusReshareHelper {

    private StatusReshareHelper() { /* no instances */ }

    // ── Intent Extra keys (mirrors StoryReshareActivity constants) ─────────

    public static final String EXTRA_CONTENT_TYPE   = StoryReshareActivity.EXTRA_CONTENT_TYPE;
    public static final String EXTRA_CONTENT_ID     = StoryReshareActivity.EXTRA_CONTENT_ID;
    public static final String EXTRA_OWNER_UID      = StoryReshareActivity.EXTRA_OWNER_UID;
    public static final String EXTRA_OWNER_NAME     = StoryReshareActivity.EXTRA_OWNER_NAME;
    public static final String EXTRA_OWNER_AVATAR   = StoryReshareActivity.EXTRA_OWNER_AVATAR;
    public static final String EXTRA_MEDIA_URL      = StoryReshareActivity.EXTRA_MEDIA_URL;
    public static final String EXTRA_THUMB_URL      = StoryReshareActivity.EXTRA_THUMB_URL;
    public static final String EXTRA_CAPTION        = StoryReshareActivity.EXTRA_CAPTION;
    public static final String EXTRA_MEDIA_TYPE     = StoryReshareActivity.EXTRA_MEDIA_TYPE;
    public static final String EXTRA_ALLOW_RESHARE  = StoryReshareActivity.EXTRA_ALLOW_RESHARE;
    public static final String EXTRA_STICKERS_JSON  = StoryReshareActivity.EXTRA_STICKERS_JSON;
    // ── Original text-status content (WhatsApp preserves this on repost even
    //    when there's no mediaUrl/thumbnailUrl to show, e.g. plain text statuses) ──
    public static final String EXTRA_VIA_UID  = "EXTRA_VIA_UID";   // chain repost: the reposter we took it from (B)
    public static final String EXTRA_VIA_NAME = "EXTRA_VIA_NAME";
    public static final String EXTRA_ORIGINAL_TEXT       = "EXTRA_ORIGINAL_TEXT";
    public static final String EXTRA_ORIGINAL_BG_COLOR   = "EXTRA_ORIGINAL_BG_COLOR";
    public static final String EXTRA_ORIGINAL_TEXT_COLOR = "EXTRA_ORIGINAL_TEXT_COLOR";

    // ── canReshare checks ──────────────────────────────────────────────────

    /**
     * Returns true if the given reel can be reshared to a story.
     * Respects the creator's allowReposts flag.
     */
    public static boolean canReshareReel(ReelModel reel) {
        return reel != null
            && reel.allowReposts
            && reel.uid != null
            && !reel.uid.isEmpty();
    }

    /**
     * Returns true if the given StatusItem can be reposted to the viewer's own story.
     * Respects the owner's allowSharing flag. Chain reposts are allowed; for those use
     * {@link #checkCanRepost} which verifies the ORIGINAL creator's setting too.
     */
    public static boolean canReshareStatus(com.callx.app.models.StatusItem status) {
        return status != null
            && status.allowSharing
            && status.ownerUid != null
            && !status.ownerUid.isEmpty(); // chain reposts (A->B->C) are allowed: the card keeps crediting the ORIGINAL creator
    }

    // ── Intent builders ────────────────────────────────────────────────────

    /**
     * Build Intent to launch StoryReshareActivity to repost a status to the viewer's own story.
     *
     * @param ctx        Context
     * @param status     The StatusItem being reposted
     * @param ownerName  Display name of the original status owner
     * @param ownerUid   UID of the original status owner
     */
    public static Intent buildReshareStatusIntent(Context ctx,
            com.callx.app.models.StatusItem status,
            String ownerName, String ownerUid) {
        Intent i = new Intent(ctx, StoryReshareActivity.class);
        // FIX (re-repost shows wrong owner): when the status being reposted is ALREADY a repost
        // (A -> B -> C), the card must credit the ORIGINAL creator (A), not the reposter (B).
        // Follow the repost's resharedFrom* fields back to the source.
        String cType = "status", cId = status.id, oUid = ownerUid, oName = ownerName, oAvatar = status.ownerPhoto;
        boolean isChain = status.type != null && status.type.endsWith("_reshare")
                && status.resharedFromId != null && !status.resharedFromId.isEmpty()
                && ((status.resharedFromOwnerUid != null && !status.resharedFromOwnerUid.isEmpty())
                    || (status.resharedFromOwnerName != null && !status.resharedFromOwnerName.isEmpty()));
        if (isChain) {
            if (status.resharedFromType != null && !status.resharedFromType.isEmpty()) cType = status.resharedFromType;
            cId = status.resharedFromId;
            oUid = status.resharedFromOwnerUid;
            oName = status.resharedFromOwnerName; // display name; username is re-resolved from oUid at share time
            oAvatar = status.resharedFromOwnerAvatar;
            i.putExtra(EXTRA_VIA_UID,  safeStr(ownerUid));   // the reposter we are taking it from (B)
            i.putExtra(EXTRA_VIA_NAME, safeStr(ownerName));
        }
        i.putExtra(EXTRA_CONTENT_TYPE,  cType);
        i.putExtra(EXTRA_CONTENT_ID,    safeStr(cId));
        i.putExtra(EXTRA_OWNER_UID,     safeStr(oUid));
        i.putExtra(EXTRA_OWNER_NAME,    safeStr(oName));
        i.putExtra(EXTRA_OWNER_AVATAR,  safeStr(oAvatar));
        // Prefer mediaUrl for image/video; fall back to thumbnailUrl for the card preview
        String media = (status.mediaUrl != null && !status.mediaUrl.isEmpty())
                ? status.mediaUrl : safeStr(status.thumbnailUrl);
        i.putExtra(EXTRA_MEDIA_URL,     media);
        String thumb = (status.thumbnailUrl != null && !status.thumbnailUrl.isEmpty())
                ? status.thumbnailUrl : media;
        i.putExtra(EXTRA_THUMB_URL,     thumb);
        i.putExtra(EXTRA_CAPTION,       safeStr(status.caption != null ? status.caption : status.text));
        // media type: video types → "video", everything else → "image"
        String mType = ("video".equals(status.type) || "reel_story".equals(status.type)
                || "reel_clip".equals(status.type)) ? "video" : "image";
        if (isChain && status.resharedMediaType != null && !status.resharedMediaType.isEmpty())
            mType = status.resharedMediaType; // a repost's own type is never "video"/"image"

        i.putExtra(EXTRA_MEDIA_TYPE,    mType);
        i.putExtra(EXTRA_ALLOW_RESHARE, status.allowSharing);
        if (status.mediaWidth > 0 && status.mediaHeight > 0) { // so the repost card can match the original's aspect ratio
            i.putExtra("EXTRA_MEDIA_W", status.mediaWidth);
            i.putExtra("EXTRA_MEDIA_H", status.mediaHeight);
        }
        // Carry the original text-status content through so the reshare still
        // has something to show when the source status has no media at all
        // (plain text statuses) — without this, the resulting reshare item
        // ends up with empty mediaUrl/thumbnailUrl and the viewer has nothing
        // to render.
        i.putExtra(EXTRA_ORIGINAL_TEXT,       safeStr(status.text));
        i.putExtra(EXTRA_ORIGINAL_BG_COLOR,   safeStr(status.bgColor));
        i.putExtra(EXTRA_ORIGINAL_TEXT_COLOR, safeStr(status.textColor));
        // Music + interactive stickers (StatusItem.stickersJson). Guarded: a Binder transaction must stay small.
        if (status.stickersJson != null && !status.stickersJson.isEmpty()
                && status.stickersJson.length() < 100_000) {
            i.putExtra(EXTRA_STICKERS_JSON, status.stickersJson);
        }
        return i;
    }

    /**
     * Build Intent to launch StoryReshareActivity for a reel.
     */
    public static Intent buildReshareReelIntent(Context ctx, ReelModel reel) {
        Intent i = new Intent(ctx, StoryReshareActivity.class);
        i.putExtra(EXTRA_CONTENT_TYPE,  "reel");
        i.putExtra(EXTRA_CONTENT_ID,    safeStr(reel.reelId));
        i.putExtra(EXTRA_OWNER_UID,     safeStr(reel.uid));
        i.putExtra(EXTRA_OWNER_NAME,    safeStr(reel.ownerName));
        i.putExtra(EXTRA_OWNER_AVATAR,  safeStr(reel.ownerPhoto));
        i.putExtra(EXTRA_MEDIA_URL,     safeStr(reel.videoUrl));
        i.putExtra(EXTRA_THUMB_URL,     safeStr(reel.thumbUrl));
        i.putExtra(EXTRA_CAPTION,       safeStr(reel.caption));
        i.putExtra(EXTRA_MEDIA_TYPE,    "video");
        i.putExtra(EXTRA_ALLOW_RESHARE, reel.allowReposts);
        return i;
    }

    /**
     * Build Intent for resharing a regular feed post to story.
     */
    public static Intent buildResharePostIntent(Context ctx,
            String postId, String ownerUid, String ownerName,
            String ownerAvatar, String mediaUrl, String thumbUrl,
            String mediaType, String caption) {
        Intent i = new Intent(ctx, StoryReshareActivity.class);
        i.putExtra(EXTRA_CONTENT_TYPE,  "post");
        i.putExtra(EXTRA_CONTENT_ID,    safeStr(postId));
        i.putExtra(EXTRA_OWNER_UID,     safeStr(ownerUid));
        i.putExtra(EXTRA_OWNER_NAME,    safeStr(ownerName));
        i.putExtra(EXTRA_OWNER_AVATAR,  safeStr(ownerAvatar));
        i.putExtra(EXTRA_MEDIA_URL,     safeStr(mediaUrl));
        i.putExtra(EXTRA_THUMB_URL,     safeStr(thumbUrl));
        i.putExtra(EXTRA_CAPTION,       safeStr(caption));
        i.putExtra(EXTRA_MEDIA_TYPE,    safeStr(mediaType));
        i.putExtra(EXTRA_ALLOW_RESHARE, true);
        return i;
    }

    /**
     * Build Intent for resharing a channel post to story.
     */
    public static Intent buildReshareChannelPostIntent(Context ctx,
            String postId, String channelName, String channelAvatar,
            String mediaUrl, String thumbUrl) {
        Intent i = new Intent(ctx, StoryReshareActivity.class);
        i.putExtra(EXTRA_CONTENT_TYPE,  "channel_post");
        i.putExtra(EXTRA_CONTENT_ID,    safeStr(postId));
        i.putExtra(EXTRA_OWNER_UID,     "");
        i.putExtra(EXTRA_OWNER_NAME,    safeStr(channelName));
        i.putExtra(EXTRA_OWNER_AVATAR,  safeStr(channelAvatar));
        i.putExtra(EXTRA_MEDIA_URL,     safeStr(mediaUrl));
        i.putExtra(EXTRA_THUMB_URL,     safeStr(thumbUrl));
        i.putExtra(EXTRA_CAPTION,       "");
        i.putExtra(EXTRA_MEDIA_TYPE,    "image");
        i.putExtra(EXTRA_ALLOW_RESHARE, true);
        return i;
    }


    // ── Repost guards / notifications ──────────────────────────────────────

    public interface RepostCheckCb { void on(boolean ok, String message); }

    private static boolean isChain(com.callx.app.models.StatusItem st) {
        return st != null && st.type != null && st.type.endsWith("_reshare")
                && "status".equals(st.resharedFromType)
                && st.resharedFromId != null && !st.resharedFromId.isEmpty()
                && st.resharedFromOwnerUid != null && !st.resharedFromOwnerUid.isEmpty();
    }

    /**
     * Async pre-flight for "Repost to my status":
     *  1. the SOURCE's allowSharing (for a chain repost that is the ORIGINAL creator's flag, not the reposter's)
     *  2. the original still exists / is live
     *  3. the viewer hasn't already got a live repost of it
     * Network trouble never blocks the user (fail-open); the callback always runs on the main thread.
     */
    public static void checkCanRepost(final com.callx.app.models.StatusItem st, final String myUid,
                                      final RepostCheckCb cb) {
        if (st == null) { cb.on(false, null); return; }
        final boolean chain = isChain(st);
        final String srcUid = chain ? st.resharedFromOwnerUid : st.ownerUid;
        final String srcId  = chain ? st.resharedFromId : st.id;
        final String srcName = chain ? st.resharedFromOwnerName : st.ownerName;
        if (!chain && !st.allowSharing) { cb.on(false, (srcName != null ? srcName : "This user") + " has turned off sharing"); return; }
        final Runnable dupCheck = () -> {
            if (myUid == null || myUid.isEmpty() || srcId == null || srcId.isEmpty()) { cb.on(true, null); return; }
            try {
                FirebaseUtils.getStatusRef().child(myUid).get().addOnCompleteListener(t -> {
                    try {
                        if (t.isSuccessful() && t.getResult() != null) {
                            long now = System.currentTimeMillis();
                            for (com.google.firebase.database.DataSnapshot c : t.getResult().getChildren()) {
                                com.callx.app.models.StatusItem m = c.getValue(com.callx.app.models.StatusItem.class);
                                if (m != null && srcId.equals(m.resharedFromId) && m.isLive(now)) {
                                    cb.on(false, "You already reposted this story"); return;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    cb.on(true, null);
                });
            } catch (Exception e) { cb.on(true, null); }
        };
        if (!chain) { dupCheck.run(); return; }
        try {
            FirebaseUtils.getStatusRef().child(srcUid).child(srcId).get().addOnCompleteListener(t -> {
                if (!t.isSuccessful() || t.getResult() == null) { dupCheck.run(); return; } // can't tell -> don't block
                com.google.firebase.database.DataSnapshot d = t.getResult();
                com.callx.app.models.StatusItem orig = null;
                try { orig = d.getValue(com.callx.app.models.StatusItem.class); } catch (Exception ignored) {}
                if (!d.exists() || orig == null || !orig.isLive(System.currentTimeMillis())) {
                    cb.on(false, "Original story is no longer available"); return;
                }
                if (!orig.allowSharing) {
                    cb.on(false, (srcName != null && !srcName.isEmpty() ? srcName : "The original creator") + " has turned off sharing");
                    return;
                }
                dupCheck.run();
            });
        } catch (Exception e) { dupCheck.run(); }
    }

    /** Fire-and-forget "X reposted your story" notification. Skips self-notifications. */
    public static void notifyRepost(String toUid, String fromUid, String fromName, String statusId) {
        if (toUid == null || toUid.isEmpty() || fromUid == null || toUid.equals(fromUid)) return;
        try {
            java.util.Map<String, Object> n = new java.util.HashMap<>();
            n.put("type",      "status_repost");
            n.put("fromUid",   fromUid);
            n.put("fromName",  fromName != null ? fromName : "");
            n.put("statusId",  statusId != null ? statusId : "");
            n.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
            FirebaseUtils.db().getReference("notifications").child(toUid).push().setValue(n);
        } catch (Exception ignored) {}
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String safeStr(String s) { return s != null ? s : ""; }
}
