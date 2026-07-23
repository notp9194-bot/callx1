package com.callx.app.feed;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.media3.common.util.UnstableApi;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.feed.controllers.ReelPlayerDelegate;
import com.callx.app.feed.controllers.ReelPlayerController;
import com.callx.app.feed.controllers.ReelSocialController;
import com.callx.app.feed.controllers.ReelShareController;
import com.callx.app.feed.controllers.ReelDuetController;
import com.callx.app.feed.controllers.ReelPhotoSlideshowController;
import com.callx.app.feed.controllers.ReelUiController;
import com.callx.app.social.ReelMoreBottomSheet;
import com.callx.app.comments.ReelCommentsBottomSheet;

import java.util.ArrayList;

/**
 * ReelPlayerFragment — Full-screen single-reel player.
 *
 * Refactored using the Delegate pattern: this Fragment owns the reel model and
 * lifecycle, while six controllers handle specialised concerns:
 *
 *  • {@link ReelPlayerController}          — ExoPlayer, playback, progress, mute, speed
 *  • {@link ReelSocialController}          — like, save, follow, repost, reactions, Firebase listeners
 *  • {@link ReelShareController}           — share, download, more-sheet dispatch
 *  • {@link ReelDuetController}            — duet/stitch/collab, reel management, block
 *  • {@link ReelPhotoSlideshowController}  — photo mode, story bar, dot indicator, slideshow timer
 *  • {@link ReelUiController}              — static data, hashtag chips, disc animation, cinema mode
 */
@UnstableApi
public class ReelPlayerFragment extends Fragment
        implements ReelPlayerDelegate,
                    ReelMoreBottomSheet.OnItemClickListener,
                    ReelCommentsBottomSheet.Host {

    private static final float[] SPEED_STEPS  = {0.5f, 1.0f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS = {"0.5×", "1×", "1.5×", "2×"};

    // ── Shared state ──────────────────────────────────────────────────────
    private ReelModel reel;
    private boolean   isVisible = false;

    // ── Controllers ───────────────────────────────────────────────────────
    private ReelPlayerController         playerController;
    private ReelSocialController         socialController;
    private ReelShareController          shareController;
    private ReelDuetController           duetController;
    private ReelPhotoSlideshowController photoController;
    private ReelUiController             uiController;

    // ── Factory ───────────────────────────────────────────────────────────

    public static ReelPlayerFragment newInstance(ReelModel reel) {
        ReelPlayerFragment f = new ReelPlayerFragment();
        Bundle args = new Bundle();
        args.putString("reel_id",    reel.reelId);
        args.putString("reel_uid",   reel.uid);
        args.putString("owner_name", reel.ownerName);
        args.putString("owner_photo",reel.ownerPhoto);
        args.putString("video_url",  reel.videoUrl);
        args.putString("thumb_url",  reel.thumbUrl);
        args.putString("caption",    reel.caption);
        args.putString("music_id",        reel.musicId        != null ? reel.musicId        : "");
        args.putString("music_name",      reel.musicName      != null ? reel.musicName      : "");
        args.putString("music_url",       reel.musicUrl       != null ? reel.musicUrl       : "");
        args.putString("music_cover_url", reel.musicCoverUrl  != null ? reel.musicCoverUrl  : "");
        args.putString("music_artist",    reel.musicArtist    != null ? reel.musicArtist    : "");
        args.putInt("music_start_sec",    reel.musicStartSec);
        args.putLong("timestamp",         reel.timestamp);
        args.putInt("duration",      reel.duration);
        args.putInt("width",         reel.width);
        args.putInt("height",        reel.height);
        args.putInt("likes",         reel.likesCount);
        args.putInt("comments",      reel.commentsCount);
        args.putInt("shares",        reel.sharesCount);
        args.putInt("views",         reel.viewsCount);
        args.putInt("reposts",       reel.repostCount);
        args.putString("original_audio_url", reel.originalAudioUrl != null ? reel.originalAudioUrl : "");
        args.putString("duet_of",            reel.duetOf            != null ? reel.duetOf            : "");
        args.putInt   ("duet_count",         reel.duetCount);
        args.putString("allow_duet_level",   reel.allowDuetLevel    != null ? reel.allowDuetLevel    : "everyone");
        args.putString("allow_stitch_level", reel.allowStitchLevel  != null ? reel.allowStitchLevel  : "everyone");
        args.putString("series_id",           reel.seriesId            != null ? reel.seriesId            : "");
        args.putString("series_title",        reel.seriesTitle         != null ? reel.seriesTitle         : "");
        args.putInt   ("series_episode_num",  reel.seriesEpisodeNumber);
        args.putString("media_type", reel.mediaType != null ? reel.mediaType : "video");
        if (reel.photoUrls != null && !reel.photoUrls.isEmpty()) {
            args.putStringArrayList("photo_urls", new ArrayList<>(reel.photoUrls));
        }
        args.putInt("photo_duration_ms", reel.photoDurationMs > 0 ? reel.photoDurationMs : 3000);
        args.putString("photo_filter",        reel.photoFilter        != null ? reel.photoFilter        : "normal");
        args.putString("transition_type",     reel.transitionType     != null ? reel.transitionType     : "fade");
        args.putString("ken_burns_intensity", reel.kenBurnsIntensity  != null ? reel.kenBurnsIntensity  : "normal");
        args.putBoolean("auto_loop",          reel.autoLoop);
        args.putBoolean("photo_beat_sync",    reel.photoBeatSync);
        args.putInt("beat_interval_ms",       reel.beatIntervalMs);
        if (reel.photoCaptions != null)
            args.putStringArrayList("photo_captions",           new ArrayList<>(reel.photoCaptions));
        if (reel.photoFilterList != null)
            args.putStringArrayList("photo_filter_list",        new ArrayList<>(reel.photoFilterList));
        if (reel.photoEffectList != null)
            args.putStringArrayList("photo_effect_list",        new ArrayList<>(reel.photoEffectList));
        if (reel.photoCaptionStyleList != null)
            args.putStringArrayList("photo_caption_style_list", new ArrayList<>(reel.photoCaptionStyleList));
        if (reel.photoStickerJsonList != null)
            args.putStringArrayList("photo_sticker_json_list",  new ArrayList<>(reel.photoStickerJsonList));
        if (reel.photoKenBurnsDirectionList != null)
            args.putStringArrayList("photo_kb_dir_list",        new ArrayList<>(reel.photoKenBurnsDirectionList));
        if (reel.photoDurationList != null)
            args.putIntegerArrayList("photo_duration_list",     new ArrayList<>(reel.photoDurationList));
        f.setArguments(args);
        return f;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            reel = new ReelModel();
            reel.reelId        = getArguments().getString("reel_id");
            reel.uid           = getArguments().getString("reel_uid");
            reel.ownerName     = getArguments().getString("owner_name");
            reel.ownerPhoto    = getArguments().getString("owner_photo");
            reel.videoUrl      = getArguments().getString("video_url");
            reel.thumbUrl      = getArguments().getString("thumb_url");
            reel.caption       = getArguments().getString("caption");
            reel.musicId       = getArguments().getString("music_id",       "");
            reel.musicName     = getArguments().getString("music_name",     "");
            reel.musicUrl      = getArguments().getString("music_url",      "");
            reel.musicCoverUrl = getArguments().getString("music_cover_url","");
            reel.musicArtist   = getArguments().getString("music_artist",   "");
            reel.musicStartSec = getArguments().getInt("music_start_sec",    0);
            reel.timestamp     = getArguments().getLong("timestamp");
            reel.duration      = getArguments().getInt("duration");
            reel.width         = getArguments().getInt("width");
            reel.height        = getArguments().getInt("height");
            reel.likesCount    = getArguments().getInt("likes");
            reel.commentsCount = getArguments().getInt("comments");
            reel.sharesCount   = getArguments().getInt("shares");
            reel.viewsCount    = getArguments().getInt("views");
            reel.repostCount      = getArguments().getInt("reposts");
            reel.originalAudioUrl = getArguments().getString("original_audio_url", "");
            reel.duetOf           = getArguments().getString("duet_of",            "");
            reel.duetCount        = getArguments().getInt   ("duet_count",         0);
            reel.allowDuetLevel   = getArguments().getString("allow_duet_level",   "everyone");
            reel.allowStitchLevel = getArguments().getString("allow_stitch_level", "everyone");
            reel.seriesId            = getArguments().getString("series_id",          "");
            reel.seriesTitle         = getArguments().getString("series_title",        "");
            reel.seriesEpisodeNumber = getArguments().getInt   ("series_episode_num",  0);
            reel.mediaType       = getArguments().getString("media_type",        "video");
            reel.photoUrls       = getArguments().getStringArrayList("photo_urls");
            reel.photoDurationMs = getArguments().getInt("photo_duration_ms",    3000);
            reel.photoFilter         = getArguments().getString("photo_filter",         "normal");
            reel.transitionType      = getArguments().getString("transition_type",      "fade");
            reel.kenBurnsIntensity   = getArguments().getString("ken_burns_intensity",  "normal");
            reel.autoLoop            = getArguments().getBoolean("auto_loop",           false);
            reel.photoBeatSync       = getArguments().getBoolean("photo_beat_sync",     false);
            reel.beatIntervalMs      = getArguments().getInt("beat_interval_ms",        0);
            reel.photoCaptions           = getArguments().getStringArrayList("photo_captions");
            reel.photoFilterList         = getArguments().getStringArrayList("photo_filter_list");
            reel.photoEffectList         = getArguments().getStringArrayList("photo_effect_list");
            reel.photoCaptionStyleList   = getArguments().getStringArrayList("photo_caption_style_list");
            reel.photoStickerJsonList    = getArguments().getStringArrayList("photo_sticker_json_list");
            reel.photoKenBurnsDirectionList = getArguments().getStringArrayList("photo_kb_dir_list");
            reel.photoDurationList       = getArguments().getIntegerArrayList("photo_duration_list");
        }

        // Create controllers
        playerController = new ReelPlayerController(this);
        socialController = new ReelSocialController(this);
        shareController  = new ReelShareController(this);
        duetController   = new ReelDuetController(this);
        photoController  = new ReelPhotoSlideshowController(this);
        uiController     = new ReelUiController(this);
    }

    /** Called by ReelsFragment to wire the shared preloader — syncs quality cap */
    public void setPreloader(com.callx.app.cache.ReelVideoPreloader preloader) {
        if (playerController != null) playerController.setPreloader(preloader);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_reel_player, container, false);

        // Bind views in all controllers
        playerController.bindViews(v);
        socialController.bindViews(v);
        photoController.bindViews(v);
        uiController.bindViews(v);

        // Detect photo mode and setup accordingly
        if (reel != null && reel.isPhotoSlideshow()) {
            photoController.setupPhotoMode();
        }

        // Populate static UI
        uiController.populateStaticData();
        uiController.setupPinnedComment(reel);
        socialController.populateCounts();

        // Wire click listeners
        uiController.setupClickListeners(v);
        socialController.setupClickListeners();

        // Start Firebase listeners
        socialController.startFirebaseListeners();

        // Restore cinema mode state
        uiController.applyCinemaState(v);

        // Pre-prepare ExoPlayer silently in background (Instagram-style instant play)
        if (!photoController.isPhotoMode()) {
            playerController.preparePlayerSilently();
        }

        // BUGFIX: if setUserVisibleHint(true) already arrived before this view
        // existed (see applyVisibleState() doc), start playback now instead of
        // leaving the reel paused until the user taps it.
        if (isVisible) {
            applyVisibleState(true);
        }

        return v;
    }

    @Override
    public void onPause() {
        playerController.pausePlayback();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        playerController.stopProgressTracking();
        playerController.releasePlayer();
        socialController.removeFirebaseListeners();
        socialController.release();
        uiController.release();
        super.onDestroyView();
    }

    // ── Called by ReelsFragment to control playback ───────────────────────

    public void setUserVisibleHint(boolean visible) {
        isVisible = visible;
        applyVisibleState(visible);
    }

    /**
     * BUGFIX: setUserVisibleHint(true) can arrive from the host
     * Activity/ViewPager2 (e.g. SingleReelPlayerActivity opening a reel from
     * Profile) BEFORE onCreateView() has actually run — ViewPager2's
     * FragmentStateAdapter attaches the fragment to the FragmentManager
     * first and creates its view on a later pass. When that race happens,
     * startPlayback() finds playerView still null and silently no-ops, so
     * the reel sits there prepared-but-paused until the user taps once
     * (which calls startPlayback() again, this time with a view). That's
     * why every reel opened from Profile needed a manual tap.
     *
     * Fix: onCreateView() below calls this again once views are bound, if
     * isVisible was already true when the view finished creating — so the
     * pending "become visible" request that no-op'd earlier actually takes
     * effect without needing a tap.
     */
    private void applyVisibleState(boolean visible) {
        if (visible) {
            playerController.startPlayback();
            uiController.startDiscAnimation();
            socialController.recordView();
            socialController.markReelNotificationsRead();
            // v5: Notify predictive preloader in parent ReelsFragment
            if (reel != null && getParentFragment() instanceof ReelsFragment) {
                ((ReelsFragment) getParentFragment()).notifyReelWatched(
                    reel.reelId,
                    reel.hashtags != null ? reel.hashtags : java.util.Collections.emptyList(),
                    reel.uid
                );
            }
        } else {
            playerController.pausePlayback();
        }
    }

    // ── ReelMoreBottomSheet.OnItemClickListener ───────────────────────────

    @Override
    public void onMoreItemClick(String action) {
        shareController.onMoreItemClick(action);
    }

    // ── ReelPlayerDelegate implementation ─────────────────────────────────

    @Override public ReelModel getReel()          { return reel; }
    // isAdded(), requireContext(), getContext(), getActivity(),
    // getChildFragmentManager(), getParentFragment() are final in Fragment
    // and are inherited directly — no override needed to satisfy ReelPlayerDelegate.
    @Override public Fragment getFragment()        { return this; }
    @Override public boolean isCurrentlyVisible() { return isVisible; }

    // ── Utility ──────────────────────────────────────────────────────────

    @Override
    public @Nullable String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return null; }
    }

    @Override
    public String formatCount(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    @Override
    public int dpToPx(int dp) {
        return (int)(dp * requireContext().getResources().getDisplayMetrics().density);
    }

    @Override
    public void showBottomSheet(DialogFragment sheet, String tag) {
        if (!isAdded()) return;
        FragmentManager fm = getChildFragmentManager();
        if (fm.isDestroyed()) return;
        Fragment existing = fm.findFragmentByTag(tag);
        if (existing != null) {
            try {
                fm.beginTransaction().remove(existing).commitAllowingStateLoss();
                fm.executePendingTransactions();
            } catch (Exception ignored) {}
        }
        try {
            sheet.show(fm, tag);
        } catch (Exception e) {
            try { sheet.showNow(fm, tag); } catch (Exception ignored) {}
        }
    }

    @Override
    public void autoAdvance() {
        if (!isAdded() || getParentFragment() == null) return;
        Fragment parent = getParentFragment();
        if (parent instanceof ReelsFragment) ((ReelsFragment) parent).advanceToNext();
    }

    // ── Shared state reads ────────────────────────────────────────────────

    @Override public boolean isFollowing()         { return socialController.isFollowing(); }
    @Override public boolean isFollowCheckLoaded() { return socialController.isFollowCheckLoaded(); }
    @Override public boolean isPhotoMode()         { return photoController.isPhotoMode(); }
    @Override public boolean isMuted()             { return playerController.isMuted(); }
    @Override public int     getSpeedIndex()       { return playerController.getSpeedIndex(); }
    @Override public String[] getSpeedLabels()     { return SPEED_LABELS; }
    @Override public float[]  getSpeedSteps()      { return SPEED_STEPS; }
    @Override public boolean isSaved()             { return socialController.isSaved(); }
    @Override public boolean isLiked()             { return socialController.isLiked(); }
    @Override public boolean isReposted()          { return socialController.isReposted(); }

    // ── Player actions ────────────────────────────────────────────────────

    @Override public void togglePlayPause()     { playerController.togglePlayPause(); }
    @Override public void toggleMute()          { playerController.toggleMute(); }
    @Override public void cycleSpeed()          { playerController.cycleSpeed(); }
    @Override public void showSpeedPicker()     { playerController.showSpeedPicker(); }
    @Override public void startDiscAnimation()  { uiController.startDiscAnimation(); }
    @Override public void stopDiscAnimation()   { uiController.stopDiscAnimation(); }
    @Override public void stopPhotoSlideshow()  { photoController.stopPhotoSlideshow(); }
    @Override public void startPhotoSlideshow() { photoController.startPhotoSlideshow(); }
    @Override public void pausePlayback()       { playerController.pausePlayback(); }

    // ── Social actions ────────────────────────────────────────────────────

    @Override public void toggleLike()               { socialController.toggleLike(); }
    @Override public void toggleSave()               { socialController.toggleSave(); }
    @Override public void toggleFollow()             { socialController.toggleFollow(); }
    @Override public void toggleRepost()             { socialController.toggleRepost(); }
    @Override public void sendReaction(String emoji) { socialController.sendReaction(emoji); }
    @Override public void hideReactions()            { socialController.hideReactions(); }
    @Override public void toggleReactionPanel()      { socialController.toggleReactionPanel(); }
    @Override public void showLikeAnimation()        { socialController.showLikeAnimation(); }
    @Override public void updateFollowUI(boolean following) { socialController.updateFollowUI(following); }
    @Override public void recordView()               { socialController.recordView(); }
    @Override public void markReelNotificationsRead() { socialController.markReelNotificationsRead(); }

    // ── Share / more-sheet actions ────────────────────────────────────────

    @Override public void shareReel()           { shareController.shareReel(); }
    @Override public void downloadReel()        { shareController.downloadReel(); }
    @Override public void openComments()        { shareController.openComments(); }
    @Override public void openLikesSheet()      { shareController.openLikesSheet(); }
    @Override public void openSharesSheet()     { shareController.openSharesSheet(); }
    @Override public void openCommentsSheet()   { shareController.openCommentsSheet(); }
    @Override public void showMoreOptions()     { shareController.showMoreOptions(); }
    @Override public void copyReelLink()        { shareController.copyReelLink(); }
    @Override public void markNotInterested()   { shareController.markNotInterested(); }

    // ── Duet / navigation actions ─────────────────────────────────────────

    @Override public void openDuet()               { duetController.openDuet(); }
    @Override public void openStitch()             { duetController.openStitch(); }
    @Override public void openVideoReply()         { duetController.openVideoReply(); }
    @Override public void openShareToStory()       { duetController.openShareToStory(); }
    @Override public void openDuetSeries()         { duetController.openDuetSeries(); }
    @Override public void openDuetInvite()         { duetController.openDuetInvite(); }
    @Override public void openDuetBattle()         { duetController.openDuetBattle(); }
    @Override public void openDuetTree()           { duetController.openDuetTree(); }
    @Override public void openDuetChallenge()      { duetController.openDuetChallenge(); }
    @Override public void openMultiDuet()          { duetController.openMultiDuet(); }
    @Override public void openDuetApproval()       { duetController.openDuetApproval(); }
    @Override public void openReelEdit()           { duetController.openReelEdit(); }
    @Override public void openReelAnalytics()      { duetController.openReelAnalytics(); }
    @Override public void openReelReport()         { duetController.openReelReport(); }
    @Override public void openReelQRCode()         { duetController.openReelQRCode(); }
    @Override public void openPinnedComments()     { duetController.openPinnedComments(); }
    @Override public void openCollabRequest()      { duetController.openCollabRequest(); }
    @Override public void openCollabRepost()       { duetController.openCollabRepost(); }
    @Override public void openBookmarkCollections() { duetController.openBookmarkCollections(); }
    @Override public void openSoundDetail()        { duetController.openSoundDetail(); }
    @Override public void openUserReels()          { duetController.openUserReels(); }
    @Override public void openOwnerStatus()        { duetController.openOwnerStatus(); }
    @Override public void confirmDeleteReel()      { duetController.confirmDeleteReel(); }
    @Override public void blockReelOwner()         { duetController.blockReelOwner(); }
    @Override public void openRemix()              { duetController.openRemix(); }
    @Override public void openViewRemixes()        { duetController.openViewRemixes(); }
    @Override public void openWatchHistory()       { duetController.openWatchHistory(); }
    @Override public void showQualityPicker()      { playerController.showQualityPicker(); }
    @Override public void saveReelOffline()         { playerController.saveReelOffline(); }
    @Override public void showQoeStats()            { playerController.showQoeStats(); }

    // ── Instagram-style comments transition ───────────────────────────────

    @Override
    public void onCommentsSheetProgress(float progress) {
        if (!isAdded() || getView() == null) return;

        playerController.setCommentsSheetProgress(progress);

        View root = getView();
        View photoPager = root.findViewById(R.id.vp_photos);
        if (photoPager != null && photoPager.getWidth() > 0 && photoPager.getHeight() > 0) {
            float p = Math.max(0f, Math.min(1f, progress));
            float scale = 1f - (0.58f * p);
            float translationY = playerController.getDockStatusBarHeightPx() * p;
            photoPager.setPivotX(photoPager.getWidth() / 2f);
            photoPager.setPivotY(0f);
            photoPager.setScaleX(scale);
            photoPager.setScaleY(scale);
            photoPager.setTranslationY(translationY);
        }

        // Keep the live video clean while comments take over the lower half.
        // The player itself remains visible and keeps rendering behind the sheet.
        View rightActions = root.findViewById(R.id.right_actions);
        View bottomInfo = root.findViewById(R.id.bottom_info);
        View topControls = root.findViewById(R.id.top_controls);
        float controlsAlpha = 1f - (0.72f * Math.max(0f, Math.min(1f, progress)));
        if (rightActions != null) rightActions.setAlpha(controlsAlpha);
        if (bottomInfo != null) bottomInfo.setAlpha(controlsAlpha);
        if (topControls != null) topControls.setAlpha(controlsAlpha);
    }

    @Override
    public void onCommentsSheetDismissed() {
        onCommentsSheetProgress(0f);
    }

    /**
     * Fired once the sheet's drag gesture ends and it settles into a stable
     * state (collapsed / half-expanded / expanded / hidden). Lets the docked
     * video "bounce" into its final spot with a bit of spring overshoot,
     * instead of the flat 1:1 finger tracking used mid-drag.
     */
    @Override
    public void onCommentsSheetSettled(float settledProgress) {
        if (!isAdded() || getView() == null) return;

        playerController.springSettleCommentsSheet(settledProgress);

        View root = getView();
        View photoPager = root.findViewById(R.id.vp_photos);
        if (photoPager != null && photoPager.getWidth() > 0 && photoPager.getHeight() > 0) {
            float p = Math.max(0f, Math.min(1f, settledProgress));
            float targetScale = 1f - (0.58f * p);
            float targetTranslationY = playerController.getDockStatusBarHeightPx() * p;
            photoPager.setPivotX(photoPager.getWidth() / 2f);
            photoPager.setPivotY(0f);
            photoPager.animate().cancel();
            photoPager.animate()
                .scaleX(targetScale).scaleY(targetScale).translationY(targetTranslationY)
                .setDuration(280)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                .start();
        }
    }

    /** Tap on the shrunk, docked video while the comments sheet is open.
     *  Mute/unmute only — must NOT pause playback. Pausing flips isPlaying to
     *  false, which ReelsFragment.onReelPlaybackStateChanged() reads as "show
     *  the top bar + bottom nav again", popping that chrome back over the
     *  docked video. (This is the tap handler that's actually invoked while
     *  the sheet is open — the sheet's dialog window sits above the fragment,
     *  so playerView's own click listener never receives the touch here.) */
    @Override
    public void onCommentsSheetVideoTap() {
        playerController.toggleMute();
    }
}
