package com.callx.app.feed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.media3.common.util.UnstableApi;
import com.callx.app.interactions.ReelStickerReplyHelper;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.stickers.StatusStickerOverlayView;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.feed.controllers.ReelPlayerDelegate;
import com.callx.app.feed.controllers.ReelPlayerController;
import com.callx.app.feed.controllers.ReelSocialController;
import com.callx.app.feed.controllers.ReelShareController;
import com.callx.app.feed.controllers.ReelDuetController;
import com.callx.app.feed.controllers.ReelPhotoSlideshowController;
import com.callx.app.feed.controllers.ReelUiController;
import com.callx.app.social.ReelMoreBottomSheet;
import com.callx.app.comments.ReelCommentSheetFragment;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

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
                    com.callx.app.music.ReelSoundQuickActionSheet.OnActionListener,
                    com.callx.app.social.ReelRemixSequencePickerSheet.OnModeSelectedListener,
                    com.callx.app.social.ReelDisplayModeBottomSheet.OnModeSelectedListener,
                    ReelCommentSheetFragment.Host {

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
        args.putInt("music_start_ms",     reel.musicStartMs);  // ✅ FIX: ms-precision trim for photo audio
        args.putInt("music_end_ms",       reel.musicEndMs);
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
        args.putString("sticker_json", reel.stickerJsonForVideo());
        // ✅ MULTI-COLLABORATOR: carry collab state through — without this the
        // avatar-stack/"and N others" row in the player never has data to show,
        // since collabMap can't be put into a Bundle directly.
        args.putBoolean("is_collab_post",    reel.isCollabPost);
        args.putBoolean("is_collab_pending", reel.isCollabPending);
        args.putString("collab_uid",          reel.collabUid          != null ? reel.collabUid          : "");
        args.putString("collab_display_name", reel.collabDisplayName  != null ? reel.collabDisplayName  : "");
        args.putString("collab_handle",       reel.collabHandle       != null ? reel.collabHandle       : "");
        args.putString("collab_avatar_url",   reel.collabAvatarUrl    != null ? reel.collabAvatarUrl    : "");
        args.putString("collab_invite_id",    reel.collabInviteId     != null ? reel.collabInviteId     : "");
        args.putString("collab_map_json",     reel.collabMapJson());
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
            reel.musicStartMs  = getArguments().getInt("music_start_ms",     0);  // ✅ FIX
            reel.musicEndMs    = getArguments().getInt("music_end_ms",        0);
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
            reel.stickerJson     = getArguments().getString("sticker_json",      "[]");
            // ✅ MULTI-COLLABORATOR: restore collab state (see newInstance()).
            reel.isCollabPost      = getArguments().getBoolean("is_collab_post",    false);
            reel.isCollabPending   = getArguments().getBoolean("is_collab_pending", false);
            reel.collabUid          = getArguments().getString("collab_uid",          "");
            reel.collabDisplayName  = getArguments().getString("collab_display_name", "");
            reel.collabHandle       = getArguments().getString("collab_handle",       "");
            reel.collabAvatarUrl    = getArguments().getString("collab_avatar_url",   "");
            reel.collabInviteId     = getArguments().getString("collab_invite_id",    "");
            reel.collabMap = ReelModel.parseCollabMapJson(getArguments().getString("collab_map_json", ""));
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
        } else if (reel != null) {
            // Video reel: render any interactive stickers (music/poll/quiz/
            // countdown/slider/question/mention/hashtag/link) added via the
            // full sticker sheet in ReelEditorActivity — same live, tappable
            // widgets the photo-slideshow feed uses, wired through
            // ReelStickerReplyHelper. Photo-slideshow reels instead render
            // their per-photo stickers inside vp_photos (unaffected here).
            renderVideoStickers(v);
        }

        // Populate static UI
        uiController.populateStaticData();
        uiController.setupPinnedComment(reel);
        socialController.populateCounts();

        // Wire click listeners
        uiController.setupClickListeners(v);
        socialController.setupClickListeners();

        // FIX: Firebase listeners ab onCreateView mein NAHI lagate.
        // Pehle yahan lagte the → offscreen fragments ke bhi listeners chalte the
        // (4 offscreen × 6+ listeners = 24+ Firebase connections simultaneously).
        // Ab sirf tab lagte hain jab reel ACTUALLY visible ho (applyVisibleState(true)).

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
    public void onResume() {
        super.onResume();
        // Photo-reel stickers (mention/hashtag/link/music/add-yours) can hand off
        // to an external profile/hashtag/browser/sheet — settle it back to its
        // dropped spot and resume the slideshow now that the viewer is back.
        photoController.settleAnyZoomedSticker();
        // Same hand-off case for VIDEO-reel stickers — settle it back and resume playback.
        settleAnyZoomedVideoSticker();
    }

    @Override
    public void onPause() {
        // BACKGROUND PLAY: fragment onPause() only fires here when the Activity
        // itself is going to the background (home button / recents / screen off) —
        // in-app tab switches and feed scroll are handled separately via
        // setUserVisibleHint()/applyVisibleState(), which always pause regardless
        // of this setting. So this is exactly the "app closed" moment the user's
        // 3-dot toggle is meant to control.
        boolean keepPlayingInBackground = getContext() != null
                && com.callx.app.utils.ReelBackgroundPlaySettings.isEnabled(getContext())
                && !photoController.isPhotoMode(); // photo reels have nothing to keep "playing"
        if (!keepPlayingInBackground) {
            playerController.pausePlayback();
            mainHandler.removeCallbacks(watchHistoryRunnable);
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        // Defensive: if this reel's view is torn down (e.g. ViewPager2
        // offscreenPageLimit recycling) while it still thought it was the
        // visible one, don't leave the outer tab pager's swipe stuck off.
        if (isVisible) com.callx.app.utils.ReelTabSwipeLock.unlock();
        mainHandler.removeCallbacks(watchHistoryRunnable);
        playerController.stopProgressTracking();
        playerController.releasePlayer();
        socialController.removeFirebaseListeners();
        socialController.release();
        uiController.release();
        super.onDestroyView();
    }

    // ── Called by ReelsFragment to control playback ───────────────────────

    public void setUserVisibleHint(boolean visible) {
        // ViewPager2 can re-assert the same page several times during a tab
        // resume or a data refresh. Do not restart social listeners and
        // animations for the same visibility transition. Playback still gets
        // a cheap resume call so returning from the background works.
        if (isVisible == visible) {
            if (visible) playerController.startPlayback();
            return;
        }
        isVisible = visible;
        applyVisibleState(visible);
    }

    /**
     * ✅ Instagram-style pre-warm — called by ReelsFragment for the NEXT
     * reel (activePosition + 1) while the current reel is still playing.
     *
     * preparePlayerSilently() already builds the ExoPlayer, attaches the
     * cache-first media source, and calls prepare() — muted
     * (setVolume(0f)) and paused (setPlayWhenReady(false)) — so it's
     * already exactly the "pre-warm" step we need; it just was never
     * triggered before the reel became visible. Calling it here means
     * that by the time the user actually swipes to this reel,
     * startPlayback() finds `player` already built and buffering, so it
     * skips straight to setVolume()+play() instead of building the player
     * from zero — removing the thumbnail-flash / hitch on swipe.
     *
     * Previous reels don't need this: applyVisibleState(false) only
     * pauses (never releases) a player that was already built while
     * active, so swiping back already resumes instantly. Only a reel
     * that's never been visible yet needs its player pre-built.
     *
     * Skips on Data Saver or a poor connection — a pre-warm isn't
     * guaranteed to be watched, so it shouldn't spend a slow/limited
     * connection's budget on a reel the user might swipe past.
     */
    public void prewarmPlayer() {
        if (isVisible) return;              // already the active reel — nothing to prewarm
        if (!isAdded() || getContext() == null) return;
        if (com.callx.app.player.ReelABREngine.get(requireContext()).isDataSaverMode()) return;
        if (com.callx.app.utils.NetworkUtils.getNetworkQuality(requireContext())
                == com.callx.app.utils.NetworkUtils.Quality.SLOW) return;
        // PERF (advance #6 — battery/thermal aware throttling): skip
        // speculative work entirely on power-save mode, low battery
        // (not charging), or a device that's already thermally stressed —
        // see PrewarmThrottleGuard doc for the exact thresholds. This is
        // pure UX polish, never required for correctness, so it's the
        // right thing to drop first under any resource pressure.
        if (com.callx.app.player.PrewarmThrottleGuard.shouldThrottle(requireContext())) return;

        // PERF (advance #7 — "preload audio track separately"): photo reels
        // have no videoUrl, so preparePlayerSilently() (the video/ExoPlayer
        // path below) early-returns and does nothing for them — without this
        // branch a photo reel's background music never gets prewarmed at all.
        if (photoController.isPhotoMode()) {
            playerController.prewarmPhotoAudio();
            return;
        }
        playerController.preparePlayerSilently();
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
        // FIX: lock the outer (Chats/Status/Reels/Calls) tab pager's swipe
        // for exactly as long as a multi-photo reel is the one visible on
        // screen — this is the deterministic backstop to
        // ReelPhotoSlideshowController's requestDisallowInterceptTouchEvent
        // fix, so a left/right photo swipe can never fall through and
        // change tabs. See ReelTabSwipeLock for the full explanation.
        if (visible && photoController.hasMultiplePhotos()) {
            com.callx.app.utils.ReelTabSwipeLock.lock();
        } else {
            com.callx.app.utils.ReelTabSwipeLock.unlock();
        }

        if (visible) {
            playerController.startPlayback();
            uiController.startDiscAnimation();
            // FIX: Firebase listeners sirf tab start honge jab reel VISIBLE ho.
            // Pehle onCreateView mein hote the → offscreen fragments ke bhi
            // listeners chal rahe the → wasted CPU + Firebase connections.
            socialController.startFirebaseListeners();
            socialController.recordView();
            socialController.markReelNotificationsRead();
            scheduleWatchHistoryMark();
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
            // FIX: Reel invisible hone par Firebase listeners turant remove karo.
            // Pehle sirf onDestroyView mein hata rahe the — tab tak offscreen
            // reel ke listeners ghante bhar chal sakte the. Ab swipe karte hi
            // listeners stop → Firebase connections & CPU dono free.
            socialController.removeFirebaseListeners();
            mainHandler.removeCallbacks(watchHistoryRunnable);
        }
    }

    // ── "Just watched" grid overlay (profile Reels tab) ────────────────────
    //
    // GAP FIX: this used to fire the instant the reel became visible — same
    // moment as the viewCount increment — so a reel that merely flashed by
    // during a fast scroll got marked "watched" just like one actually
    // watched start-to-finish. Instagram's own "Just watched" only appears
    // after a meaningful watch, so this is now gated behind an actual dwell
    // timer instead of view registration:
    //   - Scheduled only while the reel is the one currently visible.
    //   - Cancelled immediately if the user swipes away before it fires —
    //     see the `else` branch above.
    //   - Threshold is duration-aware: capped at 3s for normal-length
    //     reels, but never more than half the reel's own length, so a 2s
    //     reel doesn't need an impossible 3s dwell to count as watched.
    private static final long WATCH_HISTORY_MAX_THRESHOLD_MS = 3000L;
    private final Runnable watchHistoryRunnable = () -> socialController.markReelWatchedForHistory();

    private void scheduleWatchHistoryMark() {
        mainHandler.removeCallbacks(watchHistoryRunnable);
        long threshold = WATCH_HISTORY_MAX_THRESHOLD_MS;
        if (reel != null && reel.duration > 0) {
            threshold = Math.min(WATCH_HISTORY_MAX_THRESHOLD_MS, reel.duration / 2L);
        }
        mainHandler.postDelayed(watchHistoryRunnable, Math.max(500L, threshold));
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

    // ── Chat-tab docked player support ───────────────────────────────────

    /**
     * Returns the live ExoPlayer for surface transfer to the chat docked overlay.
     * Returns null if this is a photo-slideshow reel (no video player).
     */
    public androidx.media3.exoplayer.ExoPlayer getActivePlayer() {
        if (playerController == null || isPhotoMode()) return null;
        return playerController.getPlayer();
    }

    /**
     * Returns the fragment's PlayerView so the docked overlay can restore
     * the ExoPlayer surface back to it when the user returns to Reels tab.
     */
    public androidx.media3.ui.PlayerView getPlayerViewForDock() {
        if (playerController == null) return null;
        return playerController.getPlayerView();
    }

    @Override public boolean isFollowing()         { return socialController.isFollowing(); }
    @Override public boolean isFollowCheckLoaded() { return socialController.isFollowCheckLoaded(); }
    @Override public boolean isPhotoMode()         { return photoController.isPhotoMode(); }
    @Override public boolean hasMultiplePhotos()   { return photoController.hasMultiplePhotos(); }
    @Override public boolean isMuted()             { return playerController.isMuted(); }
    @Override public int     getSpeedIndex()       { return playerController.getSpeedIndex(); }
    @Override public String[] getSpeedLabels()     { return SPEED_LABELS; }
    @Override public float[]  getSpeedSteps()      { return SPEED_STEPS; }
    @Override public boolean isSaved()             { return socialController.isSaved(); }
    @Override public boolean isLiked()             { return socialController.isLiked(); }
    @Override public boolean isReposted()          { return socialController.isReposted(); }
    @Override public int getLastKnownLikeCount()   { return socialController.getLastKnownLikeCount(); }
    @Override public int getLastKnownViewCount()   { return socialController.getLastKnownViewCount(); }
    @Override public int getLastKnownSharesCount() { return socialController.getLastKnownSharesCount(); }
    @Override public int getLastKnownRepostCount() { return socialController.getLastKnownRepostCount(); }
    @Override public boolean isDocked()            { return playerController.isDocked(); }

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
    @Override public void resumePlayback()      { playerController.resumePlayback(); }
    @Override public boolean isPlaybackActive() { return playerController.isPlaybackActive(); }

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
    @Override public void openCommentsSheetWithCaption() { shareController.openCommentsSheetWithCaption(); }
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
    @Override public void openAddCollaborators()   { duetController.openAddCollaborators(); }
    @Override public void openBookmarkCollections() { duetController.openBookmarkCollections(); }
    @Override public void openSoundDetail()        { duetController.openSoundDetail(); }
    @Override public void showSoundQuickActions()  { duetController.showSoundQuickActions(); }

    // ── ReelSoundQuickActionSheet.OnActionListener (v2: 3 separate rows) ────
    /** "Remix" row tapped → show layout picker → ReelRemixActivity. */
    @Override public void onRemix()             { duetController.openRemixWithPicker(); }
    /** "Sequence" row tapped → ReelSequenceActivity. */
    @Override public void onSequence()          { duetController.openSequence(); }
    @Override public void onSoundInfoSelected() { duetController.openSoundDetail(); }

    // ── ReelRemixSequencePickerSheet.OnModeSelectedListener (kept for compat) ──
    @Override
    public void onRemixSelected(com.callx.app.models.ReelModel reelStub) {
        duetController.openRemixWithPicker();
    }
    @Override
    public void onSequenceSelected(com.callx.app.models.ReelModel reelStub) {
        duetController.openSequence();
    }

    @Override public void openUserReels()          { duetController.openUserReels(); }
    @Override public void openOwnerStatus()        { duetController.openOwnerStatus(); }
    @Override public void confirmDeleteReel()      { duetController.confirmDeleteReel(); }
    @Override public void blockReelOwner()         { duetController.blockReelOwner(); }
    // Remix & Sequence delegate methods
    @Override public void openRemixSequencePicker() { duetController.openRemixSequencePicker(); }
    @Override public void openRemixWithPicker()    { duetController.openRemixWithPicker(); }
    @Override public void openSequence()           { duetController.openSequence(); }
    @Override public void openRemix()              { duetController.openRemix(); }
    @Override public void openViewRemixes()        { duetController.openViewRemixes(); }
    @Override public void openWatchHistory()       { duetController.openWatchHistory(); }
    @Override public void showQualityPicker()      { playerController.showQualityPicker(); }
    @Override public void saveReelOffline()         { playerController.saveReelOffline(); }
    @Override public void showQoeStats()            { playerController.showQoeStats(); }
    @Override public void showStreamingModeInfo()   { playerController.showStreamingModeInfo(); }
    @Override public void showCacheStatus()          { playerController.showCacheStatus(); }

    // ── Reels Display Mode (Immersive vs Normal) — reopened anytime from ⋮ menu ──
    @Override
    public void showDisplayModePicker() {
        if (!isAdded() || getContext() == null) return;
        String current = com.callx.app.utils.ReelDisplayModePrefs.getMode(getContext());
        com.callx.app.social.ReelDisplayModeBottomSheet sheet =
            com.callx.app.social.ReelDisplayModeBottomSheet.newInstance(current, false);
        sheet.show(getChildFragmentManager(), com.callx.app.social.ReelDisplayModeBottomSheet.TAG);
    }

    // ── Background Play toggle ────────────────────────────────────────────
    /**
     * Toggles whether reels keep playing (with audio) after the app is
     * backgrounded. OFF by default — see ReelBackgroundPlaySettings and
     * this fragment's own onPause() below for where it's enforced.
     */
    @Override
    public void toggleBackgroundPlay() {
        if (!isAdded() || getContext() == null) return;
        boolean nowOn = com.callx.app.utils.ReelBackgroundPlaySettings.toggle(getContext());
        android.widget.Toast.makeText(getContext(),
                nowOn ? "Background Play turned on — reels keep playing when you leave the app"
                      : "Background Play turned off — reels pause when you leave the app",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ── Cinema Mode toggle — moved here from long-press (v20) ──────────────
    // Long-press on the player is now Instagram-style hold-to-pause instead;
    // hiding the overlay UI is an explicit 3-dot menu action.
    @Override public void toggleCinemaMode() { uiController.toggleCinemaMode(); }
    @Override public boolean isCinemaModeOn() { return uiController.isCinemaModeOn(); }

    /** ReelDisplayModeBottomSheet.OnModeSelectedListener — user picked a mode. */
    @Override
    public void onModeSelected(String mode) {
        if (getContext() == null) return;
        com.callx.app.utils.ReelDisplayModePrefs.setMode(getContext(), mode);
        com.callx.app.utils.ReelDisplayModePrefs.markAsked(getContext());
        // Notify the hosting Activity (MainActivity) to re-apply status bar /
        // bottom nav visibility immediately — no tab-switch happens here so
        // it wouldn't otherwise re-check the preference on its own.
        if (getActivity() instanceof ReelDisplayModeListener) {
            ((ReelDisplayModeListener) getActivity()).onReelDisplayModeChanged(mode);
        }
    }

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

    // ── Interactive stickers for VIDEO reels ────────────────────────────────
    // Mirrors ReelPhotoSlideshowAdapter#addFullStickerView/#wireStickerInteractivity
    // for photo-slideshow reels: same StatusStickerOverlayView widget, same
    // FirebaseUtils.getReelSticker*Ref nodes, same ReelStickerReplyHelper DM flow —
    // just rendered once into fl_video_sticker_layer instead of per photo page.

    // ── Viewer "tap to zoom, react to return" gate for VIDEO-reel stickers ───
    // Mirrors StatusViewerActivity#armStickerZoomGate/settleStickerReaction and
    // ReelPhotoSlideshowAdapter's copy of the same: a viewer's first tap
    // enlarges the sticker front-and-centre over a dim scrim and pauses the
    // video (playerController.pausePlayback()) for as long as it stays
    // enlarged; whatever counts as the "reaction" for that sticker type
    // shrinks it back to its dropped spot and resumes playback.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FrameLayout videoStickerLayer;
    @Nullable private StatusStickerOverlayView zoomedVideoSticker;
    @Nullable private View videoStickerZoomScrim;

    private void renderVideoStickers(View root) {
        if (reel == null || reel.stickerJson == null) return;
        FrameLayout layer = root.findViewById(R.id.fl_video_sticker_layer);
        if (layer == null) return;
        layer.removeAllViews();
        videoStickerLayer = layer;
        videoStickerZoomScrim = null; // just got removed along with everything else above
        zoomedVideoSticker = null;

        String json = reel.stickerJsonForVideo();
        if (json.isEmpty() || json.equals("[]")) return;

        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return;

        int depth = 0, start = 0, idx = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String obj = inner.substring(start, i + 1);
                    // ✅ FIX: "type":"text" entries (ReelEditorActivity's Step-2 text
                    // overlays) ride as live styling metadata, not baked video pixels —
                    // see ReelTextOverlayRenderer. They're rendered as a real sharp
                    // TextView on top of the player here, matching Instagram's
                    // overlay/layer approach, instead of being burned into the pixels
                    // where Cloudinary's compression would blur them along with the
                    // rest of the video. StatusStickerOverlayView has no "text" case,
                    // so these are handled by a separate path from the other sticker
                    // types (poll/quiz/question/emoji/etc).
                    if (obj.contains("\"type\":\"text\"")) {
                        addVideoTextOverlayView(obj, layer);
                    } else {
                        addVideoStickerView(obj, layer, idx);
                    }
                    idx++;
                    start = i + 1;
                    while (start < inner.length() && inner.charAt(start) == ',') start++;
                }
            }
        }
    }

    private void addVideoStickerView(String obj, FrameLayout layer, int stickerIdx) {
        try {
            StatusStickerOverlayView sticker = StatusStickerOverlayView.fromJson(layer.getContext(), obj);

            int dp = (int) layer.getContext().getResources().getDisplayMetrics().density;
            int frameW = layer.getWidth() > 0
                    ? layer.getWidth() : layer.getContext().getResources().getDisplayMetrics().widthPixels;
            int frameH = layer.getHeight() > 0
                    ? layer.getHeight() : layer.getContext().getResources().getDisplayMetrics().heightPixels;

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.min(frameW - dp * 32, dp * 280),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    sticker.hasSavedPosition() ? android.view.Gravity.TOP | android.view.Gravity.START
                                                : android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            if (sticker.hasSavedPosition()) {
                lp.leftMargin = (int) (sticker.getSavedPosXRatio() * frameW);
                lp.topMargin  = (int) (sticker.getSavedPosYRatio() * frameH);
            } else {
                lp.topMargin = dp * 140;
            }
            layer.addView(sticker, lp);

            wireVideoStickerInteractivity(sticker, stickerIdx);
        } catch (Exception ignored) {}
    }

    /**
     * Renders one "type":"text" sticker_json entry as a real, sharp TextView on
     * top of the player — see ReelTextOverlayRenderer's class doc for why this
     * lives outside the baked video pixels. Non-interactive (view-only), same as
     * Instagram's playback of a caption sticker: draggable only in the editor.
     */
    private void addVideoTextOverlayView(String obj, FrameLayout layer) {
        try {
            java.util.List<com.callx.app.editor.ReelVideoExportEngine.OverlayItem> items =
                com.callx.app.editor.ReelVideoExportEngine.parseOverlayJsonArray("[" + obj + "]");
            if (items.isEmpty()) return;
            com.callx.app.editor.ReelVideoExportEngine.OverlayItem item = items.get(0);
            if (item.text == null || item.text.isEmpty()) return;

            View tv = com.callx.app.editor.ReelTextOverlayRenderer.build(layer.getContext(), item);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.START);
            tv.setLayoutParams(lp);

            // ✅ Measure synchronously against the FULL text (before it's cleared for
            // the animation replay below) so the position we compute uses the final
            // width/height — not whatever a live layout pass would capture partway
            // through a typewriter/word reveal, which would anchor the view off the
            // near-empty starting text instead.
            int wSpec = View.MeasureSpec.makeMeasureSpec(
                layer.getWidth() > 0 ? layer.getWidth() : 2000, View.MeasureSpec.AT_MOST);
            int hSpec = View.MeasureSpec.makeMeasureSpec(
                layer.getHeight() > 0 ? layer.getHeight() : 2000, View.MeasureSpec.AT_MOST);
            tv.measure(wSpec, hSpec);
            final int measuredW = tv.getMeasuredWidth();
            final int measuredH = tv.getMeasuredHeight();

            layer.addView(tv);

            // x/y are the CENTER of the text block as a fraction of the layer's size
            // (see ReelEditorActivity#mergeTextOverlaysIntoStickerJson).
            final float xFrac = item.x, yFrac = item.y;
            if (layer.getWidth() > 0 && layer.getHeight() > 0) {
                tv.setTranslationX(xFrac * layer.getWidth()  - measuredW / 2f);
                tv.setTranslationY(yFrac * layer.getHeight() - measuredH / 2f);
            } else {
                // Layer itself isn't measured yet (rare) — position once it is.
                tv.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            if (layer.getWidth() == 0 || layer.getHeight() == 0) return;
                            tv.setTranslationX(xFrac * layer.getWidth()  - measuredW / 2f);
                            tv.setTranslationY(yFrac * layer.getHeight() - measuredH / 2f);
                            tv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    });
            }

            // ✅ NEW: replay the typewriter/word-reveal text-in animation live at
            // playback (previously only ever played once, in the editor's own
            // preview — every viewer just saw static finished text). Position is
            // already locked in above using the full-text measurement, so this is
            // safe to clear/reveal without shifting anything.
            if (tv instanceof android.widget.TextView) {
                com.callx.app.editor.ReelTextOverlayRenderer.playAnimation((android.widget.TextView) tv, item);
            }
        } catch (Exception ignored) {}
    }

    private void wireVideoStickerInteractivity(StatusStickerOverlayView sticker, int stickerIdx) {
        String myUid    = FirebaseUtils.getCurrentUid();
        String ownerUid = reel.uid;
        String reelId    = reel.reelId;
        if (myUid == null || ownerUid == null || reelId == null) return;
        boolean isOwner  = myUid.equals(ownerUid);
        String ownerName = reel.ownerName;
        String reelThumb = reel.effectiveThumbUrl();
        String stickerKey = ReelStickerReplyHelper.videoStickerKey(stickerIdx);
        String stickerType = sticker.getStickerType();
        Context ctx = sticker.getContext();

        if ("question".equals(stickerType) && !isOwner) {
            armVideoStickerZoomGate(sticker, () ->
                    showVideoQuestionReplyDialog(ctx, sticker, ownerUid, ownerName, reelId, reelThumb));
        }

        if ("quiz".equals(stickerType) && !isOwner) {
            FirebaseUtils.getReelStickerQuizVoteRef(reelId, stickerKey, myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (snap.exists()) {
                        Long prevSelected = snap.child("selectedIndex").getValue(Long.class);
                        if (prevSelected != null) sticker.revealQuizAnswer(prevSelected.intValue());
                    } else {
                        armVideoStickerZoomGate(sticker);
                        sticker.setOnQuizOptionSelectedListener(selectedIndex -> {
                            List<String> opts = sticker.getQuizOptions();
                            String selectedText = opts != null && selectedIndex < opts.size()
                                    ? opts.get(selectedIndex) : "";
                            boolean isCorrect = selectedIndex == sticker.getQuizCorrectIndex();
                            sticker.revealQuizAnswer(selectedIndex);
                            ReelStickerReplyHelper.sendQuizAnswer(myUid, ownerUid, ownerName, reelId, reelThumb,
                                    stickerKey, sticker.getQuizQuestion(), selectedText, selectedIndex, isCorrect);
                            // Give the viewer a beat to see the ✓/✗ reveal, then shrink back and resume.
                            mainHandler.postDelayed(() -> settleVideoStickerReaction(sticker), 1200);
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
        }

        // FIX: this used to launch the full-screen SoundDetailActivity. Status's
        // music sticker opens SoundDetailSheetFragment as a bottom sheet instead —
        // this now matches that, using this fragment's own child FragmentManager.
        if ("music".equals(stickerType) && sticker.isMusicLinkedToReelSound()) {
            armVideoStickerZoomGate(sticker, () -> {
                com.callx.app.music.SoundDetailSheetFragment sheet =
                        com.callx.app.music.SoundDetailSheetFragment.newInstance(
                                sticker.getMusicSoundId(),
                                sticker.getMusicSong(),
                                sticker.getMusicArtist(),
                                sticker.getMusicCoverUrl(),
                                sticker.getMusicSoundUrl(),
                                0);
                sheet.show(getChildFragmentManager(), "sound_detail_full");
                getChildFragmentManager().executePendingTransactions();
                if (sheet.getDialog() != null) {
                    sheet.getDialog().setOnDismissListener(d -> settleVideoStickerReaction(sticker));
                } else {
                    settleVideoStickerReaction(sticker); // defensive fallback if dialog never attached
                }
            });
        }

        if ("countdown".equals(stickerType) && !isOwner) {
            FirebaseUtils.getReelStickerCountdownSubscriberRef(reelId, stickerKey, myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (snap.exists()) sticker.setCountdownSubscribed(true);
                    armVideoStickerZoomGate(sticker);
                    sticker.setOnCountdownSubscribeToggleListener(nowSubscribed -> {
                        if (nowSubscribed) {
                            ReelStickerReplyHelper.sendCountdownSubscription(myUid, ownerUid, ownerName,
                                    reelId, reelThumb, stickerKey, sticker.getCountdownLabel());
                        } else {
                            ReelStickerReplyHelper.unsubscribeCountdown(reelId, stickerKey, myUid);
                        }
                        // Give the viewer a beat to see the bell toggle, then shrink back and resume.
                        mainHandler.postDelayed(() -> settleVideoStickerReaction(sticker), 500);
                    });
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
        }

        if ("poll".equals(stickerType) && !isOwner) {
            FirebaseUtils.getReelStickerPollVoteRef(reelId, stickerKey, myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (snap.exists()) {
                        String prevOption = snap.child("option").getValue(String.class);
                        ReelStickerReplyHelper.readPollCounts(reelId, stickerKey, (countA, countB) ->
                                sticker.revealPollResult(prevOption, countA, countB));
                    } else {
                        armVideoStickerZoomGate(sticker);
                        sticker.setOnPollOptionSelectedListener(selectedOption -> {
                            String selectedText = "A".equals(selectedOption)
                                    ? sticker.getPollOptionA() : sticker.getPollOptionB();
                            ReelStickerReplyHelper.sendPollVote(myUid, ownerUid, ownerName, reelId, reelThumb,
                                    stickerKey, sticker.getPollQuestion(), selectedOption, selectedText);
                            ReelStickerReplyHelper.readPollCounts(reelId, stickerKey, (countA, countB) ->
                                    sticker.revealPollResult(selectedOption, countA, countB));
                            // Give the viewer a beat to see the % split, then shrink back and resume.
                            mainHandler.postDelayed(() -> settleVideoStickerReaction(sticker), 1200);
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
        }

        if ("slider".equals(stickerType) && !isOwner) {
            FirebaseUtils.getReelStickerSliderResponseRef(reelId, stickerKey, myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (snap.exists()) {
                        Long prevValue = snap.child("value").getValue(Long.class);
                        int myVal = prevValue != null ? prevValue.intValue() : 50;
                        ReelStickerReplyHelper.readSliderAverage(reelId, stickerKey, myVal, avg ->
                                sticker.revealSliderAverage(myVal, avg));
                    } else {
                        armVideoStickerZoomGate(sticker);
                        sticker.setOnSliderValueSubmittedListener(value -> {
                            ReelStickerReplyHelper.sendSliderResponse(myUid, ownerUid, ownerName, reelId, reelThumb,
                                    stickerKey, sticker.getSliderQuestion(), sticker.getSliderEmoji(), value);
                            ReelStickerReplyHelper.readSliderAverage(reelId, stickerKey, value, avg ->
                                    sticker.revealSliderAverage(value, avg));
                            // Give the viewer a beat to see the average, then shrink back and resume.
                            mainHandler.postDelayed(() -> settleVideoStickerReaction(sticker), 1200);
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
        }

        if ("mention".equals(stickerType)) {
            armVideoStickerZoomGate(sticker, () -> openVideoMentionProfile(ctx, sticker.getMentionUsername()));
        }

        if ("hashtag".equals(stickerType)) {
            armVideoStickerZoomGate(sticker, () -> {
                String tag = sticker.getHashtagTag();
                if (tag == null || tag.isEmpty()) return;
                try {
                    Class<?> hashtagCls = Class.forName("com.callx.app.search.XHashtagActivity");
                    Intent intent = new Intent(ctx, hashtagCls);
                    intent.putExtra("hashtag", tag);
                    ctx.startActivity(intent);
                } catch (Exception ignored) {}
            });
        }

        if ("link".equals(stickerType)) {
            armVideoStickerZoomGate(sticker, () -> {
                String url = sticker.getLinkUrl();
                if (url == null || url.isEmpty()) return;
                try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
                catch (Exception ignored) {}
            });
        }

        // FIX: ➕ Add Yours sticker had no tap handler at all — taps did nothing.
        // Reels has no chain-prefill composer extra (unlike Status's
        // NewStatusActivity.EXTRA_PREFILL_STICKER_JSON), so this opens the same
        // plain camera-first "make a reel" entry point every other in-app
        // "use this sound"/"participate" action uses, with a toast naming whose
        // prompt is being continued.
        if ("addyours".equals(stickerType) && !isOwner) {
            armVideoStickerZoomGate(sticker, () -> {
                String prompt = sticker.getAddYoursPrompt();
                if (prompt == null || prompt.isEmpty()) return;
                String origin = sticker.getAddYoursOriginName();
                android.widget.Toast.makeText(ctx,
                        "Adding to " + (origin != null && !origin.isEmpty() ? origin : "their") + "'s prompt",
                        android.widget.Toast.LENGTH_SHORT).show();
                try {
                    ctx.startActivity(new Intent(ctx, com.callx.app.camera.ReelCameraActivity.class));
                } catch (Exception ignored) {}
            });
        }
    }

    /** @param afterZoomed for one-shot stickers (question/music/mention/hashtag/
     *  link/addyours): fired once the zoom-in animation finishes. Pass null (via
     *  the no-arg overload) for stickers with their own live listener
     *  (quiz/poll/slider/countdown) that arms the viewer's next tap on the real
     *  option once zoomed in. */
    private void armVideoStickerZoomGate(StatusStickerOverlayView sticker) {
        armVideoStickerZoomGate(sticker, null);
    }

    private void armVideoStickerZoomGate(StatusStickerOverlayView sticker, @Nullable Runnable afterZoomed) {
        sticker.armViewerZoomGate(() -> {
            if (videoStickerLayer == null) return;
            playerController.pausePlayback();
            showVideoStickerZoomScrim();
            zoomedVideoSticker = sticker;
            sticker.zoomToFront(videoStickerLayer, afterZoomed);
        });
    }

    private void settleVideoStickerReaction(StatusStickerOverlayView sticker) {
        hideVideoStickerZoomScrim();
        if (zoomedVideoSticker == sticker) zoomedVideoSticker = null;
        sticker.restoreFromZoom(() -> playerController.resumePlayback());
    }

    /** Called from onResume() — settles a video sticker left zoomed in when the
     *  viewer navigated off to an external profile/hashtag/link/sheet and has
     *  now come back. No-op if nothing is currently zoomed. */
    private void settleAnyZoomedVideoSticker() {
        if (zoomedVideoSticker != null) settleVideoStickerReaction(zoomedVideoSticker);
    }

    // ── Dim backdrop behind a zoomed-in video-reel sticker ───────────────────
    private void showVideoStickerZoomScrim() {
        if (videoStickerLayer == null || videoStickerZoomScrim != null) return;
        View scrim = new View(videoStickerLayer.getContext());
        scrim.setBackgroundColor(0xCC000000);
        scrim.setAlpha(0f);
        scrim.setClickable(true); // swallow taps outside the zoomed sticker
        scrim.setOnClickListener(v -> {
            if (zoomedVideoSticker != null) settleVideoStickerReaction(zoomedVideoSticker);
        });
        videoStickerLayer.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        videoStickerLayer.invalidate();
        scrim.animate().alpha(1f).setDuration(200).start();
        videoStickerZoomScrim = scrim;
    }

    private void hideVideoStickerZoomScrim() {
        if (videoStickerZoomScrim == null) return;
        final View scrim = videoStickerZoomScrim;
        videoStickerZoomScrim = null;
        scrim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> { if (videoStickerLayer != null) videoStickerLayer.removeView(scrim); })
                .start();
    }

    private void openVideoMentionProfile(Context ctx, String username) {
        if (username == null || username.isEmpty()) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("users").orderByChild("username").equalTo(username).limitToFirst(1)
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                if (!snap.exists()) return;
                DataSnapshot userSnap = snap.getChildren().iterator().next();
                String uid = userSnap.getKey();
                String name = userSnap.child("name").getValue(String.class);
                if (name == null || name.isEmpty()) name = username;
                String photo = userSnap.child("profileImage").getValue(String.class);
                if (photo == null || photo.isEmpty()) photo = userSnap.child("photoUrl").getValue(String.class);
                try {
                    Class<?> profileCls = Class.forName("com.callx.app.activities.UserProfileActivity");
                    Intent intent = new Intent(ctx, profileCls);
                    intent.putExtra("uid", uid);
                    intent.putExtra("name", name);
                    if (photo != null) intent.putExtra("photo", photo);
                    ctx.startActivity(intent);
                } catch (Exception ignored) {}
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void showVideoQuestionReplyDialog(Context ctx, StatusStickerOverlayView sticker,
                                               String ownerUid, String ownerName, String reelId, String reelThumb) {
        String prompt = sticker.getQuestionPrompt();
        EditText input = new EditText(ctx);
        input.setHint("Type your answer…");
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(ctx)
                .setTitle(prompt != null ? prompt : "Ask me anything!")
                .setView(input)
                .setPositiveButton("Send", (d, w) -> {
                    String myUid = FirebaseUtils.getCurrentUid();
                    ReelStickerReplyHelper.sendQuestionReply(myUid, ownerUid, ownerName, reelId, reelThumb,
                            prompt, input.getText().toString());
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> settleVideoStickerReaction(sticker)) // Send, Cancel, or outside-tap all shrink it back
                .show();
    }
}
