package com.callx.app.feed.controllers;

import android.app.Activity;
import android.content.Context;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.callx.app.models.ReelModel;

/**
 * Delegate interface implemented by ReelPlayerFragment.
 * Each controller receives this delegate in its constructor and uses it to
 * access Fragment lifecycle, shared views, shared state, and peer-controller actions.
 */
public interface ReelPlayerDelegate {

    // ── Fragment basics ───────────────────────────────────────────────────

    ReelModel getReel();
    boolean isAdded();
    Context requireContext();
    @Nullable Context getContext();
    @Nullable Activity getActivity();
    Fragment getFragment();
    FragmentManager getChildFragmentManager();
    @Nullable Fragment getParentFragment();
    /** True when this reel is the currently-visible reel in the feed. */
    boolean isCurrentlyVisible();

    // ── Utility ──────────────────────────────────────────────────────────

    @Nullable String safeMyUid();
    String formatCount(int n);
    int dpToPx(int dp);
    void showBottomSheet(DialogFragment sheet, String tag);
    void autoAdvance();

    // ── Shared state reads (from owning controller, readable by peers) ────

    boolean isFollowing();
    boolean isFollowCheckLoaded();
    boolean isPhotoMode();
    boolean isMuted();
    int getSpeedIndex();
    String[] getSpeedLabels();
    float[] getSpeedSteps();
    boolean isSaved();
    boolean isLiked();
    boolean isReposted();

    // ── Player actions (PlayerController) ────────────────────────────────

    void togglePlayPause();
    void toggleMute();
    void cycleSpeed();
    void showSpeedPicker();
    void startDiscAnimation();
    void stopDiscAnimation();
    void stopPhotoSlideshow();
    void startPhotoSlideshow();
    void pausePlayback();

    // ── Social actions (SocialController) ────────────────────────────────

    void toggleLike();
    void toggleSave();
    void toggleFollow();
    void toggleRepost();
    void sendReaction(String emoji);
    void hideReactions();
    void toggleReactionPanel();
    void showLikeAnimation();
    /** Called by SocialController after follow state changes — updates follow button UI. */
    void updateFollowUI(boolean following);
    void recordView();
    void markReelNotificationsRead();

    // ── Share / more-sheet actions (ShareController) ─────────────────────

    void shareReel();
    void downloadReel();
    void openComments();
    void openLikesSheet();
    void openSharesSheet();
    void openCommentsSheet();
    void showMoreOptions();
    void copyReelLink();
    void markNotInterested();

    // ── Navigation / duet actions (DuetController) ───────────────────────

    void openDuet();
    void openStitch();
    void openVideoReply();
    void openShareToStory();
    void openDuetSeries();
    void openDuetInvite();
    void openDuetBattle();
    void openDuetTree();
    void openDuetChallenge();
    void openMultiDuet();
    void openDuetApproval();
    void openReelEdit();
    void openReelAnalytics();
    void openReelReport();
    void openReelQRCode();
    void openPinnedComments();
    void openCollabRequest();
    void openCollabRepost();
    void openBookmarkCollections();
    void openSoundDetail();
    /** Shows the Original Audio options sheet (volume slider + Use in Camera / Use in Gallery). */
    void openOriginalAudioOptions();
    void openUserReels();
    void openOwnerStatus();
    void confirmDeleteReel();
    void blockReelOwner();
}
