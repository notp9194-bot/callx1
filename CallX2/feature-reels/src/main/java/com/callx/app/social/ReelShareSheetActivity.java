package com.callx.app.social;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

/**
 * ReelShareSheetActivity — thin launchable host for {@link ReelShareSheetFragment}.
 *
 * FIX (build break): ReelContactShareAdapter was upgraded to the Instagram/WhatsApp-style
 * multi-select grid (checkmarks + "Send separately" / "Send to new group chat", search box,
 * message box — see its class doc and {@link ReelShareSheetFragment}), which dropped the old
 * single-tap {@code OnContactShareListener} callback and its 2-arg constructor entirely. Every
 * in-app entry point (HomeFragment, PostsFeedActivity, ReelShareController) had already been
 * migrated to show {@link ReelShareSheetFragment} directly as a bottom sheet — this Activity
 * was the one caller left implementing the old interface/constructor, which is why only this
 * file failed to compile.
 *
 * This Activity itself can't just be deleted: notification action PendingIntents
 * (ReelNotificationHelper) need an actual Activity class to target, and it can't render a
 * DialogFragment on its own — it needs a host. So rather than duplicate ~900 lines of
 * multi-select/search/group-chat logic a second time, this Activity now simply hosts the
 * same {@link ReelShareSheetFragment} used everywhere else and finishes once the sheet is
 * dismissed — one implementation of the share sheet, reused instead of re-forked.
 *
 * Kept for compatibility with existing callers that still start this Activity directly via
 * Intent + these EXTRA_* keys (e.g. SoundDetailFragment#shareOwnedReel(), and the notification
 * actions above): {@link #EXTRA_REEL_ID}, {@link #EXTRA_VIDEO_URL}, {@link #EXTRA_THUMB_URL},
 * {@link #EXTRA_CAPTION}, {@link #EXTRA_OWNER_UID}, {@link #EXTRA_ALLOW_REPOST}.
 */
public class ReelShareSheetActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID      = "share_reel_id";
    public static final String EXTRA_VIDEO_URL    = "share_video_url";
    public static final String EXTRA_THUMB_URL    = "share_thumb_url";
    public static final String EXTRA_CAPTION      = "share_caption";
    public static final String EXTRA_OWNER_UID    = "share_owner_uid";
    public static final String EXTRA_ALLOW_REPOST = "share_allow_repost";

    private static final String SHEET_TAG = "share_sheet_host";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String reelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        if (reelId == null) {
            // Matches old behavior: notification actions that launch this Activity with
            // no reel context (e.g. generic "Share Milestone" / "Share Now" taps) have
            // nothing to show, so just close instead of opening an empty sheet.
            finish();
            return;
        }

        // Nothing to render ourselves — window stays on Theme.CallX.Transparent (see
        // AndroidManifest) and the bottom sheet fragment below is the entire UI.
        if (savedInstanceState == null) {
            String videoUrl    = getIntent().getStringExtra(EXTRA_VIDEO_URL);
            String thumbUrl    = getIntent().getStringExtra(EXTRA_THUMB_URL);
            String caption     = getIntent().getStringExtra(EXTRA_CAPTION);
            String ownerUid    = getIntent().getStringExtra(EXTRA_OWNER_UID);
            boolean allowRepost = getIntent().getBooleanExtra(EXTRA_ALLOW_REPOST, true);

            ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                reelId, videoUrl, thumbUrl, caption, ownerUid,
                /* ownerUsername */ null, /* ownerPhoto */ null, allowRepost);

            FragmentManager fm = getSupportFragmentManager();
            // Finish this host once the sheet goes away (any exit path: close button,
            // swipe-down/backdrop tap, a share action completing) — otherwise a
            // transparent, empty Activity would linger on the back stack.
            fm.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentDetached(@NonNull FragmentManager fragmentManager, @NonNull Fragment f) {
                    if (f == sheet) finish();
                }
            }, false);
            sheet.show(fm, SHEET_TAG);
        }
    }
}
