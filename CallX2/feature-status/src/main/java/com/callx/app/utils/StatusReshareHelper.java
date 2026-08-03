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
     * Respects the owner's allowSharing flag and skips already-reshared content
     * (no chain-reposting of status_reshare items).
     */
    public static boolean canReshareStatus(com.callx.app.models.StatusItem status) {
        return status != null
            && status.allowSharing
            && status.ownerUid != null
            && !status.ownerUid.isEmpty()
            && !"status_reshare".equals(status.type);
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
        i.putExtra(EXTRA_CONTENT_TYPE,  "status");
        i.putExtra(EXTRA_CONTENT_ID,    safeStr(status.id));
        i.putExtra(EXTRA_OWNER_UID,     safeStr(ownerUid));
        i.putExtra(EXTRA_OWNER_NAME,    safeStr(ownerName));
        i.putExtra(EXTRA_OWNER_AVATAR,  safeStr(status.ownerPhoto));
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
        i.putExtra(EXTRA_MEDIA_TYPE,    mType);
        i.putExtra(EXTRA_ALLOW_RESHARE, status.allowSharing);
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

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String safeStr(String s) { return s != null ? s : ""; }
}
