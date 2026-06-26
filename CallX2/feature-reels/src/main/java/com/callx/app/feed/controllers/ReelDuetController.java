package com.callx.app.feed.controllers;

import android.content.Intent;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.callx.app.analytics.ReelAnalyticsActivity;
import com.callx.app.creator.ReelReportActivity;
import com.callx.app.editor.ReelEditActivity;
import com.callx.app.followers.ReelCollabRequestActivity;
import com.callx.app.library.ReelBookmarkCollectionsActivity;
import com.callx.app.models.ReelModel;
import com.callx.app.music.SoundDetailActivity;
import com.callx.app.comments.ReelPinnedCommentsActivity;
import com.callx.app.profile.ReelQRCodeActivity;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.social.CollabRepostActivity;
import com.callx.app.social.DuetApprovalQueueActivity;
import com.callx.app.social.DuetBattleCreateActivity;
import com.callx.app.social.DuetChallengeCreateActivity;
import com.callx.app.social.DuetInviteActivity;
import com.callx.app.social.DuetReelActivity;
import com.callx.app.social.DuetSeriesActivity;
import com.callx.app.social.DuetTreeActivity;
import com.callx.app.social.MultiDuetActivity;
import com.callx.app.social.ReelShareToStoryActivity;
import com.callx.app.comments.ReelVideoReplyActivity;
import com.callx.app.social.StitchReelActivity;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.HashSet;
import java.util.Set;

/**
 * Handles all duet/stitch/collab navigation, reel management actions (edit, delete,
 * analytics, report, block, QR, sound, bookmark), and user profile navigation.
 */
public class ReelDuetController {

    private final ReelPlayerDelegate delegate;
    private final Set<String> blockedUids = new HashSet<>();

    public ReelDuetController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Duet ─────────────────────────────────────────────────────────────

    public void openDuet() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        String myUidCheck = delegate.safeMyUid();
        if (myUidCheck != null && myUidCheck.equals(reel.uid)) {
            Toast.makeText(delegate.getContext(), "You can't duet your own reel", Toast.LENGTH_SHORT).show();
            return;
        }

        String duetLevel = reel.allowDuetLevel != null ? reel.allowDuetLevel : "everyone";
        if ("off".equals(duetLevel)) {
            Toast.makeText(delegate.getContext(), "This creator has disabled duets", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("followers".equals(duetLevel)) {
            if (!delegate.isFollowCheckLoaded()) {
                Toast.makeText(delegate.getContext(), "Checking permissions…", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!delegate.isFollowing()) {
                Toast.makeText(delegate.getContext(), "Only followers can duet this reel", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Intent i = new Intent(delegate.getActivity(), DuetReelActivity.class);
        i.putExtra(DuetReelActivity.EXTRA_REEL_ID,          reel.reelId);
        i.putExtra(DuetReelActivity.EXTRA_VIDEO_URL,        reel.videoUrl);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_NAME,       reel.ownerName);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_UID,        reel.uid);
        i.putExtra(DuetReelActivity.EXTRA_DURATION_SEC,     reel.duration / 1000);
        i.putExtra(DuetReelActivity.EXTRA_ALLOW_DUET_LEVEL, duetLevel);
        i.putExtra(DuetReelActivity.EXTRA_VIEWER_FOLLOWS,   delegate.isFollowing());
        if (reel.thumbUrl != null) i.putExtra("duet_reel_thumb", reel.thumbUrl);
        try {
            String cachedPath = com.callx.app.cache.ReelCacheManager.extractCachedVideoToFile(
                delegate.requireContext(), reel.videoUrl, reel.reelId);
            if (cachedPath != null) i.putExtra(DuetReelActivity.EXTRA_CACHED_VIDEO_PATH, cachedPath);
        } catch (Exception ignored) {}
        delegate.getFragment().startActivity(i);
    }

    // ── Stitch ────────────────────────────────────────────────────────────

    public void openStitch() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        String stitchLevel = reel.allowStitchLevel != null ? reel.allowStitchLevel : "everyone";
        if ("off".equals(stitchLevel)) {
            Toast.makeText(delegate.getContext(), "This creator has disabled stitches", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("followers".equals(stitchLevel)) {
            if (!delegate.isFollowCheckLoaded()) {
                Toast.makeText(delegate.getContext(), "Checking permissions…", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!delegate.isFollowing()) {
                Toast.makeText(delegate.getContext(), "Only followers can stitch this reel", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Intent i = new Intent(delegate.getActivity(), StitchReelActivity.class);
        i.putExtra(StitchReelActivity.EXTRA_ORIGINAL_REEL_ID,   reel.reelId);
        i.putExtra(StitchReelActivity.EXTRA_ORIGINAL_REEL_URL,  reel.videoUrl);
        i.putExtra(StitchReelActivity.EXTRA_ORIGINAL_OWNER_UID, reel.uid);
        delegate.getFragment().startActivity(i);
    }

    // ── Video Reply ───────────────────────────────────────────────────────

    public void openVideoReply() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelVideoReplyActivity.class);
        i.putExtra(ReelVideoReplyActivity.EXTRA_REEL_ID, reel.reelId);
        delegate.getFragment().startActivity(i);
    }

    // ── Share to Story ────────────────────────────────────────────────────

    public void openShareToStory() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelShareToStoryActivity.class);
        i.putExtra("reel_id",    reel.reelId);
        i.putExtra("reel_url",   reel.videoUrl);
        i.putExtra("reel_thumb", reel.thumbnailUrl);
        i.putExtra("owner_name", reel.ownerName);
        delegate.getFragment().startActivity(i);
    }

    // ── Duet Series ───────────────────────────────────────────────────────

    public void openDuetSeries() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.seriesId == null || reel.seriesId.isEmpty()) return;
        Intent i = new Intent(delegate.getActivity(), DuetSeriesActivity.class);
        i.putExtra(DuetSeriesActivity.EXTRA_SERIES_ID, reel.seriesId);
        delegate.getFragment().startActivity(i);
    }

    // ── Advanced Duet v10 entry points ────────────────────────────────────

    public void openDuetInvite() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), DuetInviteActivity.class);
        i.putExtra(DuetInviteActivity.EXTRA_REEL_ID,    reel.reelId);
        i.putExtra(DuetInviteActivity.EXTRA_OWNER_NAME, reel.ownerName);
        i.putExtra(DuetInviteActivity.EXTRA_REEL_THUMB, reel.thumbUrl != null ? reel.thumbUrl : "");
        i.putExtra(DuetInviteActivity.EXTRA_VIDEO_URL,  reel.videoUrl != null ? reel.videoUrl : "");
        i.putExtra(DuetInviteActivity.EXTRA_OWNER_UID,  reel.uid      != null ? reel.uid      : "");
        delegate.getFragment().startActivity(i);
    }

    public void openDuetBattle() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), DuetBattleCreateActivity.class);
        i.putExtra(DuetBattleCreateActivity.EXTRA_THEIR_REEL_ID,    reel.reelId);
        i.putExtra(DuetBattleCreateActivity.EXTRA_THEIR_VIDEO_URL,  reel.videoUrl  != null ? reel.videoUrl  : "");
        i.putExtra(DuetBattleCreateActivity.EXTRA_THEIR_REEL_THUMB, reel.thumbUrl  != null ? reel.thumbUrl  : "");
        i.putExtra(DuetBattleCreateActivity.EXTRA_THEIR_NAME,       reel.ownerName != null ? reel.ownerName : "");
        i.putExtra(DuetBattleCreateActivity.EXTRA_THEIR_UID,        reel.uid       != null ? reel.uid       : "");
        i.putExtra(DuetBattleCreateActivity.EXTRA_ORIGINAL_REEL_ID, reel.reelId);
        delegate.getFragment().startActivity(i);
    }

    public void openDuetTree() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), DuetTreeActivity.class);
        i.putExtra(DuetTreeActivity.EXTRA_ROOT_REEL_ID, reel.reelId);
        i.putExtra(DuetTreeActivity.EXTRA_OWNER_NAME,   reel.ownerName != null ? reel.ownerName : "");
        delegate.getFragment().startActivity(i);
    }

    public void openDuetChallenge() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), DuetChallengeCreateActivity.class);
        i.putExtra(DuetChallengeCreateActivity.EXTRA_REEL_ID,    reel.reelId);
        i.putExtra(DuetChallengeCreateActivity.EXTRA_VIDEO_URL,  reel.videoUrl  != null ? reel.videoUrl  : "");
        i.putExtra(DuetChallengeCreateActivity.EXTRA_THUMB_URL,  reel.thumbUrl  != null ? reel.thumbUrl  : "");
        i.putExtra(DuetChallengeCreateActivity.EXTRA_OWNER_NAME, reel.ownerName != null ? reel.ownerName : "");
        delegate.getFragment().startActivity(i);
    }

    public void openMultiDuet() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), MultiDuetActivity.class);
        i.putExtra(MultiDuetActivity.EXTRA_ORIGINAL_REEL_ID, reel.reelId);
        i.putExtra(MultiDuetActivity.EXTRA_VIDEO_URL,        reel.videoUrl  != null ? reel.videoUrl  : "");
        i.putExtra(MultiDuetActivity.EXTRA_OWNER_NAME,       reel.ownerName != null ? reel.ownerName : "");
        i.putExtra(MultiDuetActivity.EXTRA_OWNER_UID,        reel.uid       != null ? reel.uid       : "");
        delegate.getFragment().startActivity(i);
    }

    public void openDuetApproval() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), DuetApprovalQueueActivity.class);
        i.putExtra(DuetApprovalQueueActivity.EXTRA_REEL_ID,    reel.reelId);
        i.putExtra(DuetApprovalQueueActivity.EXTRA_REEL_TITLE, reel.ownerName != null ? "@" + reel.ownerName : "");
        delegate.getFragment().startActivity(i);
    }

    // ── Reel management ───────────────────────────────────────────────────

    public void openReelEdit() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelEditActivity.class);
        i.putExtra(ReelEditActivity.EXTRA_REEL_ID,       reel.reelId);
        i.putExtra(ReelEditActivity.EXTRA_CAPTION,       reel.caption);
        i.putExtra(ReelEditActivity.EXTRA_AUDIENCE_TYPE, reel.audienceType);
        delegate.getFragment().startActivity(i);
    }

    public void openReelAnalytics() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelAnalyticsActivity.class);
        i.putExtra(ReelAnalyticsActivity.EXTRA_REEL_ID,       reel.reelId);
        i.putExtra(ReelAnalyticsActivity.EXTRA_REEL_DURATION, reel.duration);
        delegate.getFragment().startActivity(i);
    }

    public void openReelReport() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelReportActivity.class);
        i.putExtra(ReelReportActivity.EXTRA_REEL_ID,         reel.reelId);
        i.putExtra(ReelReportActivity.EXTRA_REEL_UID,        reel.uid);
        i.putExtra(ReelReportActivity.EXTRA_REEL_OWNER_NAME, reel.ownerName);
        delegate.getFragment().startActivity(i);
    }

    public void openReelQRCode() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelQRCodeActivity.class);
        i.putExtra("reel_id",  reel.reelId);
        i.putExtra("reel_url", Constants.DEEP_LINK_BASE_URL + "/reel/" + reel.reelId);
        delegate.getFragment().startActivity(i);
    }

    public void openPinnedComments() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelPinnedCommentsActivity.class);
        i.putExtra("reel_id", reel.reelId);
        delegate.getFragment().startActivity(i);
    }

    public void confirmDeleteReel() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        new android.app.AlertDialog.Builder(delegate.getContext())
            .setTitle("Delete Reel?").setMessage("This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> deleteReel())
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteReel() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;
        String myUid = delegate.safeMyUid();
        if (myUid == null || !myUid.equals(reel.uid)) {
            Toast.makeText(delegate.requireContext(), "You can only delete your own reels", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseUtils.getReelsRef().child(reel.reelId).removeValue();
        FirebaseUtils.getReelsByUserRef(myUid).child(reel.reelId).removeValue();
        FirebaseUtils.db().getReference("userReels").child(myUid).child(reel.reelId).removeValue();
        Toast.makeText(delegate.requireContext(), "Reel deleted", Toast.LENGTH_SHORT).show();
        if (delegate.getActivity() != null) delegate.getActivity().onBackPressed();
    }

    // ── Collab ────────────────────────────────────────────────────────────

    public void openCollabRequest() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelCollabRequestActivity.class);
        i.putExtra("reel_id",    reel.reelId);
        i.putExtra("owner_uid",  reel.uid);
        i.putExtra("owner_name", reel.ownerName);
        delegate.getFragment().startActivity(i);
    }

    public void openCollabRepost() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        if (!reel.allowReposts) {
            Toast.makeText(delegate.getContext(),
                "This creator has disabled reposts", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(delegate.getActivity(), CollabRepostActivity.class);
        i.putExtra(CollabRepostActivity.EXTRA_REEL_ID,       reel.reelId);
        i.putExtra(CollabRepostActivity.EXTRA_OWNER_UID,     reel.uid);
        i.putExtra(CollabRepostActivity.EXTRA_OWNER_NAME,    reel.ownerName);
        i.putExtra(CollabRepostActivity.EXTRA_THUMB_URL,     reel.thumbUrl);
        i.putExtra(CollabRepostActivity.EXTRA_VIDEO_URL,     reel.videoUrl);
        i.putExtra(CollabRepostActivity.EXTRA_CAPTION,       reel.caption);
        i.putExtra(CollabRepostActivity.EXTRA_ALLOW_REPOSTS, reel.allowReposts);
        i.putExtra(CollabRepostActivity.EXTRA_MEDIA_TYPE,    reel.mediaType != null ? reel.mediaType : "video");
        delegate.getFragment().startActivity(i);
    }

    // ── Sound / Bookmark / Profile ────────────────────────────────────────

    public void openSoundDetail() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        // ⚠️ reel.musicId is written by a BACKGROUND job (audio extraction +
        // sound registration) that runs AFTER the reel is already live, so it
        // can still be empty in this in-memory/just-synced reel snapshot even
        // though the sound entity is about to exist (or already exists) on
        // Firebase. Don't rely on musicId alone — fall back to the same
        // deterministic "orig_{reelId}" ID that ReelUploadActivity registers
        // original audio under. This guarantees "Use this sound" always links
        // back to the ONE real sound entity for this reel (so reel_count and
        // the reels-grid update correctly), instead of a fresh duplicate
        // sound getting created every time someone reuses audio too soon.
        String soundId = reel.musicId;
        if ((soundId == null || soundId.isEmpty()) && reel.reelId != null && !reel.reelId.isEmpty()) {
            soundId = "orig_" + reel.reelId;
        }

        Intent i = new Intent(delegate.getActivity(), SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    soundId != null ? soundId : "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, reel.musicName  != null ? reel.musicName  : "Original Audio");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   reel.musicUrl   != null ? reel.musicUrl   : "");
        i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   reel.musicCoverUrl != null ? reel.musicCoverUrl : "");
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,
            reel.musicArtist != null && !reel.musicArtist.isEmpty()
                ? reel.musicArtist
                : (reel.ownerName != null ? reel.ownerName : ""));
        if (reel.originalAudioUrl != null && !reel.originalAudioUrl.isEmpty()) {
            i.putExtra(SoundDetailActivity.EXTRA_ORIGINAL_AUDIO_URL, reel.originalAudioUrl);
        } else {
            i.putExtra("reel_video_url", reel.videoUrl != null ? reel.videoUrl : "");
        }
        delegate.getFragment().startActivity(i);
    }

    public void openBookmarkCollections() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        Intent i = new Intent(delegate.getActivity(), ReelBookmarkCollectionsActivity.class);
        ReelModel reel = delegate.getReel();
        if (reel != null) i.putExtra("reel_id", reel.reelId);
        delegate.getFragment().startActivity(i);
    }

    public void openUserReels() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.uid == null) return;
        Intent i = new Intent(delegate.getActivity(), UserReelsActivity.class);
        i.putExtra(UserReelsActivity.EXTRA_UID,   reel.uid);
        i.putExtra(UserReelsActivity.EXTRA_NAME,  reel.ownerName);
        i.putExtra(UserReelsActivity.EXTRA_PHOTO, reel.ownerPhoto);
        delegate.getFragment().startActivity(i);
    }

    public void openOwnerStatus() {
        if (!delegate.isAdded() || delegate.getActivity() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.uid == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
            Intent si = new Intent(delegate.getActivity(), cls);
            si.putExtra("ownerUid",  reel.uid);
            si.putExtra("ownerName", reel.ownerName != null ? reel.ownerName : "");
            delegate.getFragment().startActivity(si);
        } catch (ClassNotFoundException e) {
            openUserReels();
        }
    }

    // ── Block ─────────────────────────────────────────────────────────────

    public void blockReelOwner() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.uid == null) return;
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty() || myUid.equals(reel.uid)) return;

        String ownerUid  = reel.uid;
        String ownerName = reel.ownerName != null ? reel.ownerName : "this user";
        new androidx.appcompat.app.AlertDialog.Builder(delegate.requireContext())
            .setTitle("Block " + ownerName + "?")
            .setMessage("They won't be able to see your reels or contact you. Their content will be hidden from your feed.")
            .setPositiveButton("Block", (d, w) -> {
                FirebaseUtils.getBlocksRef(myUid).child(ownerUid).setValue(true)
                    .addOnSuccessListener(v -> {
                        if (delegate.getContext() != null)
                            Toast.makeText(delegate.getContext(),
                                ownerName + " blocked", Toast.LENGTH_SHORT).show();
                        blockedUids.add(ownerUid);
                        androidx.fragment.app.Fragment parent = delegate.getParentFragment();
                        if (parent instanceof com.callx.app.feed.ReelsFragment)
                            ((com.callx.app.feed.ReelsFragment) parent).onUserBlocked(ownerUid);
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
