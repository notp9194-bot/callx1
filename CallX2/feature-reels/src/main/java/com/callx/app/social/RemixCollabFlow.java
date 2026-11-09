package com.callx.app.social;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;

/**
 * v790 — Router of the Remix &amp; Collab flow. One place decides which screen comes next, so every entry point
 * (3-dot menu, Select Post, approval notification, request status) behaves the same.
 */
public final class RemixCollabFlow {
    private RemixCollabFlow() {}

    /** Step 1 → 2: entry from a post's 3-dot menu. {@code mode} pre-selects the card (null = Remix). */
    public static void start(@NonNull Context ctx, @NonNull ReelModel reel, @Nullable String mode) {
        RemixCollabTarget t = new RemixCollabTarget(reel.reelId, reel.uid, reel.ownerName, reel.ownerPhoto,
                reel.videoUrl, reel.thumbUrl != null && !reel.thumbUrl.isEmpty() ? reel.thumbUrl : reel.thumbnailUrl);
        Intent i = new Intent(ctx, RemixCollabModeActivity.class);
        t.put(i);
        i.putExtra(RemixCollabTarget.X_MODE, mode == null ? RemixCollabTarget.MODE_REMIX : mode);
        ctx.startActivity(i);
    }

    /** Decide the next screen for (target, mode): record, ask for permission, show the pending status, or explain why not. */
    public static void next(@NonNull final Context ctx, @NonNull final RemixCollabTarget t, @NonNull final String mode) {
        RemixAccessHelper.check(t, mode, r -> {
            switch (r.kind) {
                case ALLOWED:
                    openRecorder(ctx, t, mode);
                    break;
                case NEEDS_REQUEST: {
                    Intent i = new Intent(ctx, RemixRequestActivity.class);
                    t.put(i);
                    i.putExtra(RemixCollabTarget.X_MODE, mode);
                    ctx.startActivity(i);
                    break;
                }
                case PENDING: {
                    Intent i = new Intent(ctx, RemixRequestStatusActivity.class);
                    t.put(i);
                    i.putExtra(RemixCollabTarget.X_MODE, mode);
                    i.putExtra(RemixCollabTarget.X_REQUEST_ID, r.requestId);
                    ctx.startActivity(i);
                    break;
                }
                case OFF:
                    toast(ctx, "The creator has turned off Remix & Collab for this post");
                    break;
                case FOLLOWERS_ONLY:
                    toast(ctx, "Only followers of this creator can remix this post");
                    break;
                case OWN_POST_COLLAB:
                    toast(ctx, "Pick a friend's post to collab with");
                    break;
                default:
                    toast(ctx, "Couldn't check permission. Please try again");
            }
        });
    }

    /** Last step of the flow: the split-screen recorder (ReelRemixActivity) → editor → upload. */
    public static void openRecorder(@NonNull Context ctx, @NonNull RemixCollabTarget t, @NonNull String mode) {
        Intent i = new Intent(ctx, ReelRemixActivity.class);
        i.putExtra(ReelRemixActivity.EXTRA_REEL_ID, t.reelId);
        i.putExtra(ReelRemixActivity.EXTRA_OWNER_UID, t.ownerUid);
        i.putExtra(ReelRemixActivity.EXTRA_OWNER_NAME, t.ownerName);
        i.putExtra(ReelRemixActivity.EXTRA_VIDEO_URL, t.videoUrl);
        i.putExtra(ReelRemixActivity.EXTRA_THUMB_URL, t.thumbUrl);
        i.putExtra(ReelRemixActivity.EXTRA_LAYOUT, ReelRemixActivity.LAYOUT_SIDE_BY_SIDE);
        i.putExtra(ReelRemixActivity.EXTRA_MODE, mode);
        ctx.startActivity(i);
    }

    private static void toast(Context c, String m) { Toast.makeText(c, m, Toast.LENGTH_SHORT).show(); }
}
