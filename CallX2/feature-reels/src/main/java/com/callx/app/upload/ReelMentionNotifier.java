package com.callx.app.upload;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelMentionNotifier — fire-and-forget mention notifications for reels.
 *
 * After a reel is successfully uploaded, call {@link #notifyAll} once.
 * For every uid in {@code mentionedUids} (excluding the poster themselves):
 *
 * 1. Writes a notification to  {@code notifications/{mentionedUid}/push()}:
 *    <pre>
 *    type:        "reel_mention"
 *    fromUid:     posterUid
 *    fromName:    posterName
 *    reelId:      reelId
 *    thumbUrl:    reelThumbUrl   (may be empty string)
 *    caption:     caption excerpt (first 100 chars)
 *    timestamp:   ServerValue.TIMESTAMP
 *    read:        false
 *    </pre>
 *
 * 2. Writes an entry to {@code reelMentions/{mentionedUid}/{reelId}}:
 *    Same payload — used by {@link ReelMentionsActivity} to list all
 *    reels where this user has been tagged.
 *
 * This runs entirely on the calling thread (it only issues Firebase
 * setValue calls, which are non-blocking). Safe to call from the main
 * thread inside a post-upload success handler.
 */
public final class ReelMentionNotifier {

    private ReelMentionNotifier() {}

    /**
     * Notify all mentioned users.
     *
     * @param posterUid     UID of the reel creator
     * @param posterName    Display name of the reel creator
     * @param reelId        Firebase key of the newly uploaded reel
     * @param thumbUrl      Thumbnail URL for the reel (may be null / empty)
     * @param caption       Full caption text typed by the creator
     * @param mentionedUids UIDs extracted by {@link ReelCaptionMentionController#getMentionedUids}
     */
    public static void notifyAll(@NonNull  String       posterUid,
                                 @NonNull  String       posterName,
                                 @NonNull  String       reelId,
                                 @Nullable String       thumbUrl,
                                 @Nullable String       caption,
                                 @Nullable List<String> mentionedUids) {
        if (mentionedUids == null || mentionedUids.isEmpty()) return;

        String safeThumb   = thumbUrl   != null ? thumbUrl   : "";
        String safeCaption = caption    != null ? caption    : "";
        // Truncate caption excerpt to 100 chars for notification preview
        String excerpt = safeCaption.length() > 100
                ? safeCaption.substring(0, 100) + "…"
                : safeCaption;

        for (String uid : mentionedUids) {
            if (uid == null || uid.isEmpty() || uid.equals(posterUid)) continue;

            // ── 1. Push notification ─────────────────────────────────────
            Map<String, Object> notif = new HashMap<>();
            notif.put("type",        "reel_mention");
            notif.put("fromUid",     posterUid);
            notif.put("fromName",    posterName);
            notif.put("reelId",      reelId);
            notif.put("thumbUrl",    safeThumb);
            notif.put("caption",     excerpt);
            notif.put("timestamp",   ServerValue.TIMESTAMP);
            notif.put("read",        false);

            FirebaseUtils.db()
                    .getReference("notifications")
                    .child(uid)
                    .push()
                    .setValue(notif);

            // ── 2. Reel mention index (used by ReelMentionsActivity) ─────
            Map<String, Object> mentionEntry = new HashMap<>();
            mentionEntry.put("reelId",        reelId);
            mentionEntry.put("mentionerUid",  posterUid);
            mentionEntry.put("mentionerName", posterName);
            mentionEntry.put("caption",       excerpt);
            mentionEntry.put("thumbUrl",      safeThumb);
            mentionEntry.put("timestamp",     ServerValue.TIMESTAMP);
            mentionEntry.put("read",          false);

            FirebaseUtils.db()
                    .getReference("reelMentions")
                    .child(uid)
                    .child(reelId)
                    .setValue(mentionEntry);
        }
    }
}
