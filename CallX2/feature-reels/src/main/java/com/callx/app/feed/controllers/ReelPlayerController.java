package com.callx.app.feed.controllers;
import com.callx.app.utils.AlertDialogStyler;

import android.content.Context;
import android.media.MediaPlayer;   // ✅ FIX: photo-slideshow background audio
import com.bumptech.glide.Glide;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.fragment.app.Fragment;

import com.callx.app.analytics.ReelQoEAnalyticsActivity;
import com.callx.app.feed.ReelsFragment;
import com.callx.app.library.WatchHistoryManager;
import com.callx.app.models.ReelModel;
import com.callx.app.player.AdaptiveStreamingManager;
import com.callx.app.player.NetworkQualityMonitor;
import com.callx.app.player.ReelABREngine;
import com.callx.app.player.ReelABRSettingsActivity;
import com.callx.app.player.ReelOfflineManager;
import com.callx.app.player.ReelLoopSeekHelper;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;

/**
 * ReelPlayerController v2 — ABR + Watch History integrated
 *
 * Upgrades over v1:
 *  ✅ HLS (.m3u8) / DASH (.mpd) / Progressive — auto-detected via AdaptiveStreamingManager
 *  ✅ Network-aware quality cap — reads user pref from ReelABRSettingsActivity
 *  ✅ Live quality badge — shows "720p" / "Auto" on screen while playing
 *  ✅ Stall recovery — after 3 stalls auto-downgrades quality one step
 *  ✅ Watch history — records to WatchHistoryManager at 25/50/75/100% milestones
 *  ✅ Bandwidth meter — DefaultBandwidthMeter updated on every tick
 *  ✅ All v1 behaviour retained (mute, speed, progress bar, play/pause indicator)
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelPlayerController {

    private static final String TAG = "ReelPlayerCtrl";
    private static final long THUMB_CROSSFADE_MS = 80L;

    private static final float[] SPEED_STEPS   = {0.5f, 1.0f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS = {"0.5×", "1×", "1.5×", "2×"};

    // Stall → downgrade threshold
    private static final int MAX_STALLS_BEFORE_DOWNGRADE = 3;

    private final ReelPlayerDelegate delegate;

    // ── Views ────────────────────────────────────────────────────────────────
    private PlayerView  playerView;
    private ImageView   ivThumb;
    private ImageView   ivPlayPauseIndicator;
    private ProgressBar progressVideo;
    private ProgressBar progressBuffering;
    private ImageButton btnMute;
    private TextView    btnSpeed;
    private TextView    tvQualityBadge;   // nullable — add id/quality_badge to fragment_reel_player.xml

    // ── Player state ─────────────────────────────────────────────────────────
    private ExoPlayer  player;
    private boolean    isMuted    = false;
    /** True only while paused because the user explicitly tapped to pause
     *  (togglePlayPause()) — NOT during a transient stall, buffering blip,
     *  or the brief isPlaying=false moment ExoPlayer can report mid
     *  REPEAT_MODE_ONE loop-restart / the STATE_ENDED safety-net's own
     *  seekTo(0)+play(). onIsPlayingChanged() only forwards to the bottom
     *  nav/top bar visibility bridge when this is true — see that listener
     *  for the full explanation of the bug this guards against. */
    private boolean    isUserPaused = false;
    private int        speedIndex = 1;
    /** True after Media3 has delivered an actual decoded frame to the surface. */
    private boolean    firstFrameRendered = false;

    // ── ✅ FIX: Photo-slideshow background audio ──────────────────────────────
    // Video reels use ExoPlayer which has its own audio track.
    // Photo reels have no video → ExoPlayer is never created → silence.
    // These fields drive a dedicated MediaPlayer for photo reel background music.
    private MediaPlayer photoAudioPlayer;
    private final Handler     photoAudioHandler = new Handler(Looper.getMainLooper());
    private Runnable          photoAudioLoopRunnable;
    /** True once prewarmPhotoAudio()'s MediaPlayer has finished prepareAsync(). */
    private boolean           photoAudioPrewarmed = false;
    /** True once this reel's photo audio has actually been start()ed at least once. */
    private boolean           photoAudioStarted   = false;

    // ── PERF advance #7: frame-perfect loop seek (video reels only) ───────────
    /** Pre-empts REPEAT_MODE_ONE's own end-of-stream restart with an earlier,
     *  exact seekTo(0) — see ReelLoopSeekHelper doc. Must be detach()'d before
     *  `player` is returned to the pool / released — see every teardown path. */
    private ReelLoopSeekHelper loopSeekHelper;

    // ── ABR state ─────────────────────────────────────────────────────────────
    private AdaptiveStreamingManager.QualityCap currentCap    = AdaptiveStreamingManager.QualityCap.AUTO;
    // ✅ true when the current reel is streaming from a single HLS manifest
    // (reel.hlsManifestUrl) rather than a separate video480/720/1080 URL —
    // quality changes for such reels are applied in-place (no source
    // rebuild) via AdaptiveStreamingManager.applyQualityCap(). See
    // preparePlayerSilently() / switchToQuality().
    private boolean                              isHlsActive   = false;
    private int                                 stallCount     = 0;
    private final DefaultBandwidthMeter         bwMeter        = new DefaultBandwidthMeter.Builder(null).build();
    /** True when user manually picked a quality — disable auto-switch until they reset */
    private boolean                             userManualCap  = false;
    // v17: set by switchToQuality()'s docked-guard, consumed by
    // startPlayback() once this reel is visible again — see both for the
    // full explanation.
    private boolean                             pendingQualitySwitchWhileDocked = false;

    // ── QoE (Quality of Experience) tracking ─────────────────────────────────
    private long    qoeStartupBeginMs  = 0;   // when player.prepare() was called
    private long    qoeStartupMs       = -1;  // ms until first frame rendered
    private long    qoeStallBeginMs    = 0;   // when current stall started
    private long    qoeTotalStallMs    = 0;   // cumulative stall ms this session
    private int     qoeQualitySwitches = 0;   // total quality switches (up+down)
    private int     qoeUpgrades        = 0;
    private int     qoeDowngrades      = 0;
    /** Consecutive stall-free seconds — used for auto upgrade decision */
    private long    qoeStallFreeStartMs = 0;
    private static final long STALL_FREE_UPGRADE_MS = 20_000; // 20s stall-free → try upgrade
    /** BUGFIX: guards against retry loops when a codec-forced URL fails to play — see onPlayerError. */
    private boolean codecFallbackAttempted = false;

    /** Optional reference to the feed preloader — synced when cap changes */
    private com.callx.app.cache.ReelVideoPreloader preloader;

    public void setPreloader(com.callx.app.cache.ReelVideoPreloader p) { this.preloader = p; }

    // ── v5: ReelABREngine + ReelOfflineManager ────────────────────────────────
    private ReelABREngine    abrEngine;
    private ReelABREngine.ABRSession abrSession;
    private ReelOfflineManager offlineManager;
    /** Upgrade cooldown — don't upgrade more than once per 30s to avoid flapping */
    private long                                lastUpgradeMs  = 0;
    private static final long                   UPGRADE_COOLDOWN_MS = 30_000;
    /** NetworkQualityMonitor listener — kept as field so we can remove it */
    private NetworkQualityMonitor.NetworkQualityListener netQualityListener;

    // ── Watch history state ───────────────────────────────────────────────────
    private int  lastWatchPctRecorded = -1;
    private int  lastSavedProgressPct = -1;

    // ── Progress handler ──────────────────────────────────────────────────────
    private final Handler  progressHandler = new Handler(Looper.getMainLooper());
    private       Runnable progressRunnable;

    // ── Comments-sheet dock: corner radius + spring settle ─────────────────────
    private static final float MAX_DOCK_CORNER_RADIUS_DP = 28f;
    private float dockCornerRadiusPx = 0f;
    private final SpringAnimation[] activeSprings = new SpringAnimation[6]; // scaleX/Y/transY for playerView + ivThumb
    /** Status bar height in px, captured once from window insets — used so the
     *  docked (shrunk) video's top edge lands right BELOW the status bar
     *  instead of bleeding behind it (full-bleed is only correct undocked). */
    private int dockStatusBarHeightPx = 0;

    public ReelPlayerController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── View binding ──────────────────────────────────────────────────────────

    public void bindViews(View root) {
        playerView           = root.findViewById(R.id.player_view);
        ivThumb              = root.findViewById(R.id.iv_thumb);
        ivPlayPauseIndicator = root.findViewById(R.id.iv_play_pause_indicator);
        progressVideo        = root.findViewById(R.id.progress_video);
        progressBuffering    = root.findViewById(R.id.progress_buffering);
        btnMute              = root.findViewById(R.id.btn_mute);
        // btn_speed view was removed from fragment_reel_player.xml (Speed action
        // now lives only in the 3-dot "More" menu / ACTION_SPEED). R.id.btn_speed
        // is kept alive via values/ids.xml (see that file's header comment) so
        // this still compiles; btnSpeed resolves to null here and every call
        // site already null-checks it before use (see ~line 767).
        btnSpeed             = root.findViewById(R.id.btn_speed);

        // Capture the status bar inset once so the docked video (comments
        // sheet open) can be pushed down by exactly this much — otherwise a
        // top-pivot scale anchors to the absolute screen top and the video
        // renders behind the (normally full-bleed) transparent status bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(playerView, (view, insets) -> {
            dockStatusBarHeightPx = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            return insets;
        });
        tvQualityBadge       = root.findViewById(R.id.tv_quality_badge); // optional view

        // Media3's default shutter is opaque black. With a thumbnail layered
        // above the surface, that shutter can flash between the thumbnail
        // disappearing and the first decoded frame. Keep the surface
        // transparent and retain the last frame while a player is rebound.
        playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);

        // Outline clip so the video frame can round its corners as it docks
        // above the comments sheet — radius is driven live from dockCornerRadiusPx.
        ViewOutlineProvider dockOutline = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dockCornerRadiusPx);
            }
        };
        playerView.setOutlineProvider(dockOutline);
        playerView.setClipToOutline(true);
        if (ivThumb != null) {
            ivThumb.setOutlineProvider(dockOutline);
            ivThumb.setClipToOutline(true);
        }

        // PlayerView's own bounds are full-screen (match_parent); the actual
        // visible video rect lives in its internal exo_content_frame child,
        // which can be letterboxed/pillarboxed smaller depending on resize
        // mode. Round that too so corners always line up with what's on screen.
        View contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (contentFrame != null) {
            contentFrame.setOutlineProvider(dockOutline);
            contentFrame.setClipToOutline(true);
        }

        // NOTE: PlayerView intentionally has NO click/touch listener of its own.
        // It used to (tap-to-toggle-play/pause), but since PlayerView sits on
        // top of `root` and covers ~the whole screen, being clickable meant it
        // consumed every touch before `root`'s OnTouchListener ever saw it —
        // silently killing the double-tap-like and long-press-hide gestures
        // wired up in ReelUiController.setupClickListeners(), since those never
        // fired for taps landing on the video itself.
        //
        // Single-tap-to-toggle-play/pause now lives in ReelUiController's
        // shared GestureDetector (onSingleTapConfirmed), guarded by
        // delegate.isDocked() so the old "don't toggle while docked" behavior
        // (see isDocked() javadoc below) is preserved in one place.
        playerView.setClickable(false);
    }

    /**
     * True when the reel is docked (shrunk) above the open comments sheet.
     * While docked, mute-only taps are owned EXCLUSIVELY by
     * ReelPlayerFragment.onCommentsSheetVideoTap() (forwarded from the sheet's
     * touchOutside overlay, which sits above this view in the dialog's window).
     * A plain single-tap on the video must NOT also call togglePlayPause() here
     * — that flips isPlaying to false, which onReelPlaybackStateChanged() in
     * ReelsFragment reads as "show the top bar + bottom nav again", popping
     * those controls back over the docked video.
     */
    public boolean isDocked() {
        return dockCornerRadiusPx > 0.5f;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean   isMuted()       { return isMuted; }
    public int       getDockStatusBarHeightPx() { return dockStatusBarHeightPx; }
    public int       getSpeedIndex() { return speedIndex; }
    public float[]   getSpeedSteps()  { return SPEED_STEPS; }
    public String[]  getSpeedLabels() { return SPEED_LABELS; }
    public PlayerView getPlayerView() { return playerView; }
    public ImageView  getIvThumb()    { return ivThumb; }

    /**
     * Returns the live ExoPlayer instance so the Chat docked overlay can
     * transfer its rendering surface via {@code miniPlayerView.setPlayer(player)}.
     *
     * Callers must NOT release this player — it is still owned by this controller.
     * The surface can be moved to a different PlayerView without affecting ownership.
     */
    public ExoPlayer getPlayer() { return player; }

    /**
     * Visually docks the live player above the comments sheet. This only
     * transforms the already-running surfaces; playback state and position are
     * intentionally untouched so the reel continues playing while comments
     * open, just like Instagram.
     */
    public void setCommentsSheetProgress(float progress) {
        if (playerView == null || !delegate.isAdded()) return;

        float p = Math.max(0f, Math.min(1f, progress));
        int width = playerView.getWidth();
        int height = playerView.getHeight();
        if (width <= 0 || height <= 0) return;

        if (p > 0.001f) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        } else {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        }

        // At the final sheet stage the reference keeps a compact, complete
        // vertical frame above the comments. FIT prevents crop; this transform
        // reserves the upper ~44% of the screen for that complete frame.
        cancelActiveSprings(); // a live finger-drag always wins over a settling spring

        float scale = 1f - (0.58f * p);
        // Pivot at the TOP edge (not center) so shrinking eats into the
        // bottom of the frame only, then push down by the status bar height
        // (scaled by p, so it's still full-bleed/undocked at p=0) — this pins
        // the docked video's top edge right BELOW the status bar instead of
        // at the absolute screen top (which would render behind it).
        float dockTranslationY = dockStatusBarHeightPx * p;
        playerView.setPivotX(width / 2f);
        playerView.setPivotY(0f);
        playerView.setScaleX(scale);
        playerView.setScaleY(scale);
        playerView.setTranslationY(dockTranslationY);

        if (ivThumb != null) {
            ivThumb.setPivotX(ivThumb.getWidth() / 2f);
            ivThumb.setPivotY(0f);
            ivThumb.setScaleX(scale);
            ivThumb.setScaleY(scale);
            ivThumb.setTranslationY(dockTranslationY);
        }

        // Full radius the instant the video starts docking — no gradual
        // fade-in lag behind the scale/translate transform.
        setDockCornerRadius(p > 0.001f ? dpToPxLocal(MAX_DOCK_CORNER_RADIUS_DP) : 0f);
    }

    /**
     * Called when the comments sheet finishes settling into a stable state
     * (half-expanded / expanded, i.e. the finger has been lifted). Adds a
     * small Instagram-style overshoot bounce on top of the already docked
     * position instead of snapping there instantly.
     */
    public void springSettleCommentsSheet(float settledProgress) {
        if (playerView == null || !delegate.isAdded()) return;
        int width  = playerView.getWidth();
        int height = playerView.getHeight();
        if (width <= 0 || height <= 0) return;

        float p = Math.max(0f, Math.min(1f, settledProgress));
        float targetScale = 1f - (0.58f * p);
        float targetTranslationY = dockStatusBarHeightPx * p;

        // Top pivot, same as setCommentsSheetProgress() — keeps the docked
        // video's top edge pinned right below the status bar through the
        // bounce instead of settling with a gap or bleeding behind it.
        playerView.setPivotX(width / 2f);
        playerView.setPivotY(0f);
        activeSprings[0] = springTo(playerView, SpringAnimation.SCALE_X, targetScale);
        activeSprings[1] = springTo(playerView, SpringAnimation.SCALE_Y, targetScale);
        activeSprings[2] = springTo(playerView, SpringAnimation.TRANSLATION_Y, targetTranslationY);

        if (ivThumb != null) {
            ivThumb.setPivotX(ivThumb.getWidth() / 2f);
            ivThumb.setPivotY(0f);
            activeSprings[3] = springTo(ivThumb, SpringAnimation.SCALE_X, targetScale);
            activeSprings[4] = springTo(ivThumb, SpringAnimation.SCALE_Y, targetScale);
            activeSprings[5] = springTo(ivThumb, SpringAnimation.TRANSLATION_Y, targetTranslationY);
        }

        // Same instant rounding on settle — never lags behind the spring.
        setDockCornerRadius(p > 0.001f ? dpToPxLocal(MAX_DOCK_CORNER_RADIUS_DP) : 0f);
    }

    private SpringAnimation springTo(View view, DynamicAnimation.ViewProperty property, float target) {
        SpringAnimation anim = new SpringAnimation(view, property, target);
        anim.setSpring(new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW));
        anim.start();
        return anim;
    }

    private void cancelActiveSprings() {
        for (int i = 0; i < activeSprings.length; i++) {
            if (activeSprings[i] != null && activeSprings[i].isRunning()) activeSprings[i].cancel();
            activeSprings[i] = null;
        }
    }

    private void setDockCornerRadius(float radiusPx) {
        dockCornerRadiusPx = Math.max(0f, radiusPx);
        if (playerView != null) {
            playerView.invalidateOutline();
            View contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
            if (contentFrame != null) contentFrame.invalidateOutline();
        }
        if (ivThumb != null) ivThumb.invalidateOutline();
    }

    private float dpToPxLocal(float dp) {
        return dp * (playerView != null
                ? playerView.getResources().getDisplayMetrics().density
                : 1f);
    }

    // ── ABR: silent pre-prepare with HLS/DASH/Progressive auto-detect ─────────

    public void preparePlayerSilently() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.videoUrl == null || reel.videoUrl.isEmpty()) return;
        if (player != null) return;

        Context ctx = delegate.requireContext();

        firstFrameRendered = false;
        isUserPaused = false; // fresh reel — never starts in a "user paused" state

        // Progressive loading: show thumbnail instantly while video buffers
        if (ivThumb != null && reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
            ivThumb.setVisibility(View.VISIBLE);
            ivThumb.animate().cancel();
            ivThumb.setAlpha(1f);

            // Instagram-level match: if ReelThumbnailPreloader already
            // speculatively decoded this reel's actual video first-frame
            // (keyed by reelId — stable across quality/HLS URL picks), use
            // THAT as the initial image instead of the separately-generated
            // server thumbnail. Means the thumbnail is pixel-identical to
            // what the player renders a moment later — no jump at all, not
            // just a fast fade between two different images.
            Bitmap prewarmedFrame = reel.reelId != null
                ? com.callx.app.cache.ReelFirstFrameCache.get(ctx).getCached(reel.reelId)
                : null;
            if (prewarmedFrame != null) {
                ivThumb.setImageBitmap(prewarmedFrame);
            } else {
                // PERF advance — "bitmap memory cache for thumbnails (LRU)":
                // this reel's thumb was very likely already decoded ahead of
                // time by ReelThumbnailPreloader.preloadFrom() into
                // ReelThumbBitmapCache — a hit here is a synchronous
                // setImageBitmap() with no Glide request at all (no re-decode,
                // no re-running centerCrop). Falls through to a normal Glide
                // load (and populates the cache for next time) on a miss, e.g.
                // the very first reel of a cold-started session.
                com.callx.app.cache.ReelThumbBitmapCache.get().loadInto(ctx, ivThumb, reel.thumbUrl);
            }
        }

        // Determine quality cap from user setting + current network
        boolean isWifi = isOnWifi(ctx);
        currentCap = ReelABRSettingsActivity.getSavedCap(ctx, isWifi);

        // If no user preference, let network quality guide us
        if (currentCap == AdaptiveStreamingManager.QualityCap.AUTO) {
            currentCap = AdaptiveStreamingManager.get(ctx).recommendedCap(ctx);
        }

        // ✅ HLS (single adaptive manifest): when the reel has one, use it
        // directly and skip the whole legacy per-quality-URL dance below.
        // There's only ONE url now, so there's no "picked a different
        // quality than last session" problem to patch around — the old
        // cache-reuse workaround (preferAlreadyCachedQualityUrl) existed
        // specifically because video480/720/1080 were separate Cloudinary
        // URLs/cache-keys; HLS segments cache under the manifest's own
        // deterministic keys regardless of which rendition ExoPlayer picks,
        // so CacheDataSource already reuses them for free. currentCap is
        // still applied via the track selector inside buildPlayer() (and
        // later via applyQualityCap()) so manual quality lock / data-saver
        // settings keep working exactly as before.
        isHlsActive = reel.hlsManifestUrl != null && !reel.hlsManifestUrl.isEmpty();
        String playUrl;
        if (isHlsActive) {
            playUrl = reel.hlsManifestUrl;
        } else {
            // Legacy path — pre-HLS reels, or accounts without Cloudinary's
            // Adaptive Streaming add-on enabled (hlsManifestUrl came back "").
            playUrl = pickQualityUrl(reel, currentCap);

            // ROOT-CAUSE FIX (cache reuse bug): currentCap above is picked from
            // LIVE network conditions (recommendedCap()/registerNetworkQualityListener),
            // which can easily differ between app sessions — WiFi last night,
            // mobile data now, weaker signal, etc. Since each quality maps to a
            // DIFFERENT Cloudinary URL (video480/720/1080), picking a different
            // quality than last time means CacheDataSource sees a brand new URL
            // and treats it as never-seen — a full re-download of a reel the
            // user already watched and cached. Before committing to the
            // network-based pick, check whether ANY quality variant of this
            // reel is already substantially cached and reuse that one instead.
            playUrl = preferAlreadyCachedQualityUrl(ctx, reel, playUrl);
        }

        // v5: Init ReelABREngine — segment-level MPC-like ABR decisions
        abrEngine = ReelABREngine.get(ctx);
        // v7: Auto-enable Data Saver on low battery (non-charging) at session start
        abrEngine.autoThrottleForBattery(ctx);

        // v5: Init ReelOfflineManager — resolve best playback URL (cache > network)
        if (offlineManager == null) offlineManager = ReelOfflineManager.get(ctx);
        if (reel.reelId != null) {
            String resolved = offlineManager.resolvePlaybackUrl(reel.reelId, playUrl);
            if (resolved != null) {
                playUrl = resolved;
            } else {
                // Offline and not cached — show error state, skip playback
                Log.w(TAG, "Reel unavailable offline and not cached: " + reel.reelId);
                if (ivThumb != null) ivThumb.setVisibility(View.VISIBLE);
                return;
            }
        }

        // PERF (advance #3 — player pool reuse): acquire a pooled ExoPlayer
        // instead of building a brand-new one every time. The pool (3-4
        // instances) hands back an already-built instance whenever one is
        // free (previous reel just got paused-off-window, etc), so only the
        // MediaSource + track-selector params change here — the expensive
        // renderer/codec/internal-thread setup is paid once per pool slot,
        // not once per reel. See ExoPlayerPool + AdaptiveStreamingManager.
        AdaptiveStreamingManager.ReelABRCallback abrCallback =
            new AdaptiveStreamingManager.ReelABRCallback() {
                @Override
                public void onQualitySelected(int w, int h, long bwKbps) {
                    updateQualityBadge(h, bwKbps);
                }
                @Override
                public void onStall(int count) {
                    stallCount = count;
                    if (tvQualityBadge != null) {
                        tvQualityBadge.setText("Buffering…");
                    }
                }
                @Override
                public void onPersistentStall() {
                    // Auto-downgrade quality one step on persistent stalls
                    downgradeQuality();
                }
                @Override
                public void onError(PlaybackException e) {
                    if (!delegate.isAdded()) return;
                    Log.e(TAG, "Playback error: " + e.getMessage());
                    if (tryCodecFallback()) return; // retrying — leave buffering UI as-is
                    progressBuffering.setVisibility(View.GONE);
                    ivThumb.setVisibility(View.VISIBLE);
                }
            };

        com.callx.app.player.ExoPlayerPool pool = com.callx.app.player.ExoPlayerPool.get(ctx);
        player = pool.acquire();
        Player.Listener abrPlayerListener = AdaptiveStreamingManager.get(ctx)
            .attachToPlayer(player, playUrl, currentCap, abrCallback);
        pool.trackListener(player, abrPlayerListener);

        playerView.setPlayer(player);

        // PERF (advance #4 — first-frame pre-render): speculatively decode
        // this reel's first GOP frame into a bitmap on a background thread
        // (no-op if the video isn't substantially cached yet — see
        // ReelFirstFrameCache doc). If it lands before the player itself
        // reaches STATE_READY, swap it in over the plain Glide thumbnail so
        // the thumbnail→video transition is a no-op visually.
        if (reel.reelId != null) {
            com.callx.app.cache.ReelFirstFrameCache.get(ctx)
                .decodeFirstFrameAsync(reel.reelId, playUrl, bitmap -> {
                    if (!delegate.isAdded() || ivThumb == null) return;
                    if (ivThumb.getVisibility() == View.VISIBLE) {
                        ivThumb.setImageBitmap(bitmap);
                    }
                });
        }

        // v5: Attach ABR engine — auto-monitors player buffer + bandwidth every 2s
        abrSession = abrEngine.attachTo(player, null,
            new ReelABREngine.ABRDecisionListener() {
                @Override
                public void onABRDecision(long prevBr, long newBr, long bufMs,
                                          long bwKbps, boolean isDowngrade, boolean isEmergency) {
                    qoeQualitySwitches++;
                    if (isDowngrade) qoeDowngrades++; else qoeUpgrades++;
                    Log.d(TAG, "ABR: " + prevBr + "→" + newBr + "kbps buf=" + bufMs
                        + "ms" + (isEmergency ? " [EMERGENCY]" : ""));
                    if (abrSession != null) abrEngine.sampleBandwidth(abrSession, bwKbps);

                    // v5 fix: actually apply the ABR engine's decision to playback,
                    // instead of only logging/counting it. Manual user cap always wins.
                    if (!userManualCap && (isDowngrade || isEmergency)) {
                        downgradeQuality();
                    }
                }
                @Override public void onStallBegin() { }
                @Override public void onStallEnd(long ms) { qoeTotalStallMs += ms; }
            });

        Player.Listener controllerListener = new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                /*
                 * Do not hide the thumbnail on STATE_READY or isPlaying:
                 * those states can arrive before the surface has displayed a
                 * decoded frame. The first-frame callback is the only safe
                 * handoff point. An 80ms alpha crossfade masks the one-frame
                 * surface handoff without delaying playback.
                 */
                revealThumbnailAfterFirstFrame();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                if (state == Player.STATE_BUFFERING) {
                    if (delegate.isCurrentlyVisible()) {
                        progressBuffering.setVisibility(View.VISIBLE);
                    }
                    // QoE: track stall start time
                    if (qoeStallBeginMs == 0) qoeStallBeginMs = System.currentTimeMillis();
                    qoeStallFreeStartMs = 0;
                } else {
                    progressBuffering.setVisibility(View.GONE);

                    // ROOT FIX (v14): was `delegate.isCurrentlyVisible()` only.
                    // While chat-docked, isCurrentlyVisible() is ALWAYS false
                    // (Reels tab genuinely isn't on screen) — including for a
                    // reel reached via swipe-up inside the mini player, which
                    // now (v12 fix) actually plays. Gating solely on visibility
                    // meant startProgressTracking() never ran for that reel at
                    // all: no watch-progress % milestones written to Firebase,
                    // no watch-history recording, no ABR/QoE logging — for a
                    // reel the user was genuinely watching. `playerView`'s own
                    // surface being detached from `player` (surface hand-off to
                    // the docked mini overlay) is the same "docked, not merely
                    // off-screen-and-paused" signal already used in the
                    // progress runnable's own cadence check below — reuse it
                    // here so a docked-and-playing reel is tracked exactly like
                    // a visible one, just at the runnable's own slower cadence.
                    boolean dockedElsewhere = playerView != null && playerView.getPlayer() != player;
                    if (state == Player.STATE_READY && (delegate.isCurrentlyVisible() || dockedElsewhere)) {
                        startProgressTracking();
                        // v5: Query ABR engine for quality suggestion + log
                        if (abrEngine != null && player != null) {
                            long bufferedMs = player.getTotalBufferedDuration();
                            long bwKbps = AdaptiveStreamingManager.get(
                                delegate.requireContext()).currentBandwidthKbps();
                            ReelABREngine.QualityLevel suggested =
                                abrEngine.selectQuality(bwKbps, bufferedMs);
                            Log.d(TAG, "ABREngine suggestion=" + suggested
                                + " buf=" + bufferedMs + "ms bw=" + bwKbps + "kbps");
                        }
                        // QoE: measure Time-To-First-Frame
                        if (qoeStartupMs < 0 && qoeStartupBeginMs > 0) {
                            qoeStartupMs = System.currentTimeMillis() - qoeStartupBeginMs;
                            Log.d(TAG, "QoE TTFF=" + qoeStartupMs + "ms cap=" + AdaptiveStreamingManager.capLabel(currentCap));
                        }
                        // End stall duration tracking
                        if (qoeStallBeginMs > 0) {
                            qoeTotalStallMs += System.currentTimeMillis() - qoeStallBeginMs;
                            qoeStallBeginMs = 0;
                            qoeStallFreeStartMs = System.currentTimeMillis();
                        }
                        // Auto-upgrade after 20s stall-free window.
                        // OPT/CORRECTNESS (v16): gated on isCurrentlyVisible()
                        // specifically, NOT dockedElsewhere. The v14 fix above
                        // intentionally widened watch-progress/analytics
                        // tracking to include the docked case — but ABR
                        // upgradeQuality() fetches a HIGHER-bitrate stream URL
                        // (a real network+battery cost), and ReelChatDockedPlayer
                        // already caps decode resolution to ~480px (240px on
                        // low-RAM devices) for anything shown in the ~120dp
                        // mini overlay. Upgrading the underlying stream tier
                        // while docked would burn mobile data fetching detail
                        // that gets thrown away at decode time anyway, for a
                        // box nobody is looking at full-screen. Downgrades
                        // (via onPersistentStall → downgradeQuality(), a
                        // separate ABR callback below) stay UNGATED on
                        // purpose — reducing bitrate is always safe/beneficial
                        // regardless of visibility; only the "spend more data
                        // for more fidelity" direction needs the visible-only
                        // guard.
                        if (!userManualCap && delegate.isCurrentlyVisible() && qoeStallFreeStartMs > 0
                                && System.currentTimeMillis() - qoeStallFreeStartMs > STALL_FREE_UPGRADE_MS) {
                            upgradeQuality();
                        }
                        stallCount = 0; // reset stall count once ready
                    }
                    if (state == Player.STATE_ENDED) {
                        recordWatchHistory(100);
                        // Instagram-style behaviour: a reel loops on itself until the
                        // user swipes away — it must NEVER auto-advance to the next
                        // reel on its own. REPEAT_MODE_ONE + ReelLoopSeekHelper handle
                        // the normal loop; this STATE_ENDED path is only the rare
                        // safety-net case where a delayed poll cycle let playback
                        // actually reach the end, so just restart this same reel.
                        if (player != null) {
                            player.seekTo(0);
                            player.play();
                        }
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (!delegate.isAdded()) return;
                if (playing) {
                    progressBuffering.setVisibility(View.GONE);
                    // Any resumed playback (tap-resume, or just genuinely
                    // still playing) means we're no longer in a user-paused
                    // state — clear it so a later transient false blip
                    // doesn't get mistaken for one.
                    isUserPaused = false;
                }
                if (btnMute != null) {
                    btnMute.setVisibility(playing ? View.GONE : View.VISIBLE);
                }
                // BUG FIX: only forward to the bottom-nav/top-bar visibility
                // bridge when this reflects a genuine user-initiated pause
                // (isUserPaused, set exclusively by togglePlayPause() — the
                // single-tap pause gesture) or a resume. Previously this
                // fired on EVERY isPlaying flip, including the transient
                // isPlaying=false ExoPlayer can briefly report mid loop —
                // REPEAT_MODE_ONE's own auto-restart, or the STATE_ENDED
                // safety-net below doing its own seekTo(0)+play() — which is
                // exactly why the bottom nav could pop back into view on an
                // ordinary reel replay the user never paused. Nav should
                // only ever reappear when the user actually taps to pause.
                if (playing || isUserPaused) {
                    // BUG FIX: a brief rebuffer/stall while the comments sheet is
                    // open (e.g. triggered by the extra scroll/network/Glide load
                    // contention from scrolling the comment list) flips `playing`
                    // to false for a moment even though the user never paused
                    // anything. Previously that unconditionally called
                    // onReelPlaybackStateChanged(false), which pops the app's own
                    // bottom nav + top bar back into view — and since the sheet's
                    // dialog window only covers the screen from its docked video
                    // zone down, that reappeared nav bar was visible right under
                    // the sheet instead of staying hidden behind it. Skip the
                    // notification entirely while docked (comments sheet open);
                    // isDocked() only goes true once the sheet actually starts
                    // covering the video, so ordinary full-screen playback still
                    // reacts to real pause/stall events exactly as before.
                    if (delegate.isCurrentlyVisible() && !isDocked()) {
                        Fragment parent = delegate.getParentFragment();
                        if (parent instanceof ReelsFragment) {
                            ((ReelsFragment) parent).onReelPlaybackStateChanged(playing);
                        }
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                // OPT (v15): tvQualityBadge only exists in the full-screen
                // fragment layout (fragment_reel_player.xml) — the mini
                // docked overlay (layout_reel_chat_docked.xml) has no such
                // view at all. Our own resolution-cap logic
                // (capDecodeResolutionForDocking/restoreFullDecodeResolution
                // in ReelChatDockedPlayer) forces a track/resolution change
                // on every dock, undock, and swipe-up, which fires this
                // callback each time — updateQualityBadge() itself no-ops on
                // a null tvQualityBadge, but that doesn't save the
                // currentBandwidthKbps() lookup before it. Skip both when
                // this reel isn't the visible one; ABR/QoE decision logic
                // elsewhere (onStall/onPersistentStall/upgradeQuality) is
                // untouched and keeps running regardless of visibility,
                // since that affects real playback quality, not just a badge.
                if (!delegate.isCurrentlyVisible()) return;
                long bwKbps = AdaptiveStreamingManager.get(delegate.requireContext())
                    .currentBandwidthKbps();
                updateQualityBadge(videoSize.height, bwKbps);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (!delegate.isAdded()) return;
                Log.e(TAG, "Player error: " + error.getMessage());
                if (tryCodecFallback()) return; // retrying with plain URL — don't show error state yet
                progressBuffering.setVisibility(View.GONE);
                ivThumb.setVisibility(View.VISIBLE);
            }
        };
        player.addListener(controllerListener);
        pool.trackListener(player, controllerListener);

        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(0f);
        player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
        player.setPlayWhenReady(false);
        player.prepare();

        // PERF (advance #7 — frame-perfect seek reset): pre-empt the
        // REPEAT_MODE_ONE auto-restart hitch with our own earlier exact seek.
        loopSeekHelper = new ReelLoopSeekHelper(player);
        loopSeekHelper.attach();

        // Sync preloader with initial cap
        if (preloader != null) preloader.setQualityCap(currentCap);

        // Register NetworkQualityMonitor for real-time auto quality switching
        registerNetworkQualityListener(ctx);

        qoeStartupBeginMs = System.currentTimeMillis();
        qoeTotalStallMs   = 0;
        qoeStallBeginMs   = 0;
        qoeQualitySwitches = 0;
        qoeUpgrades       = 0;
        qoeDowngrades     = 0;
        qoeStallFreeStartMs = System.currentTimeMillis();
        Log.d(TAG, "preparePlayerSilently cap=" + AdaptiveStreamingManager.capLabel(currentCap)
            + " wifi=" + isWifi + " url=" + reel.videoUrl);
    }

    // ── Playback control ──────────────────────────────────────────────────────

    public void startPlayback() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || playerView == null) return;

        if (delegate.isPhotoMode()) {
            if (ivThumb != null) ivThumb.setVisibility(View.GONE);
            delegate.startPhotoSlideshow();
            delegate.startDiscAnimation();
            resumePhotoAudio();   // ✅ FIX: start/resume background music for photo reels
            return;
        }

        if (reel.videoUrl == null || reel.videoUrl.isEmpty()) return;

        if (player == null) preparePlayerSilently();

        // ✅ FIX (NPE): preparePlayerSilently() has legitimate early-return paths
        // that leave `player` null — e.g. offline + reel not cached (shows the
        // "unavailable offline" thumbnail state instead), or the fragment
        // detaching mid-call. Every line below unconditionally dereferences
        // `player`, so bail out here instead of crashing.
        if (player == null) return;

        // SAFETY NET (v4): the chat-docked mini player transfers this ExoPlayer's
        // video surface away and back (ReelChatDockedPlayer.show()/collapseBack()).
        // If a tab switch and an in-flight next-reel swap ever land in the same
        // frame, playerView can end up unbound even though `player` itself is
        // fine. Re-assert the binding every time this reel becomes active so it
        // can never get stuck audio-only with no video surface.
        if (playerView.getPlayer() != player) {
            playerView.setPlayer(player);
            // CORRECTNESS (v11): a docked session may have capped this same
            // ExoPlayer's decode resolution (ReelChatDockedPlayer PERF v10).
            // If we're here re-asserting the binding, this reel is visibly
            // active again — never leave it stuck at mini-player quality.
            try {
                player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters().buildUpon()
                                .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                                .build());
            } catch (Exception ignored) {}
        }

        // ROOT FIX (v17): a stall-downgrade, network-change auto-switch, or
        // manual quality pick that happened while this reel was chat-docked
        // was deferred by switchToQuality() instead of rebuilding the live
        // docked player out from under the mini overlay (see that method's
        // guard for the full explanation). Now that this reel is genuinely
        // visible again, apply it. switchToQuality() fully rebuilds `player`,
        // rebinds playerView, restores seek position + play/pause state, and
        // re-registers the network listener on its own — nothing below this
        // is still needed for this call.
        if (pendingQualitySwitchWhileDocked) {
            pendingQualitySwitchWhileDocked = false;
            switchToQuality(currentCap, "(resumed)");
            return;
        }

        player.setVolume(isMuted ? 0f : 1f);

        if (player.getPlaybackState() == Player.STATE_READY) {
            progressBuffering.setVisibility(View.GONE);
            startProgressTracking();
        }

        player.play();
    }

    public void pausePlayback() {
        if (delegate.isPhotoMode()) {
            delegate.stopPhotoSlideshow();
            pausePhotoAudio();   // ✅ FIX: also pause the photo background music
        }
        if (player != null) player.pause();
        stopProgressTracking();
        delegate.stopDiscAnimation();
    }

    /** True if the video is actively playing right now (not paused/ended). Video-mode only —
     *  photo-mode long-press-pause is handled separately by ReelPhotoSlideshowController. */
    public boolean isPlaybackActive() {
        return player != null && player.isPlaying();
    }

    /**
     * Resumes playback after an Instagram-style long-press pause. Reuses
     * startPlayback()'s full resume path (rebinds surface, restores quality
     * cap, restarts progress tracking) — it does not seek, so playback
     * continues from wherever pausePlayback() left it.
     */
    public void resumePlayback() {
        startPlayback();
    }

    /**
     * Crossfades the decoded surface over the thumbnail. This is deliberately
     * driven by onRenderedFirstFrame(), not a buffering/playback state, so a
     * black ExoPlayer shutter can never be exposed for a frame.
     */
    private void revealThumbnailAfterFirstFrame() {
        if (firstFrameRendered) return;
        firstFrameRendered = true;
        if (ivThumb == null) return;
        ivThumb.animate().cancel();
        if (ivThumb.getVisibility() != View.VISIBLE) return;
        ivThumb.setAlpha(1f);
        ivThumb.animate()
            .alpha(0f)
            .setDuration(THUMB_CROSSFADE_MS)
            .withEndAction(() -> {
                if (ivThumb == null) return;
                ivThumb.setVisibility(View.GONE);
                ivThumb.setAlpha(1f);
            })
            .start();
    }

    public void togglePlayPause() {
        if (player == null) { startPlayback(); showPlayPauseIndicator(true); return; }
        boolean nowPausing = player.isPlaying();
        // Set BEFORE pause()/play() — onIsPlayingChanged() reads this
        // synchronously off the same flag once ExoPlayer's callback fires,
        // so the nav-visibility bridge sees the correct "this was a real
        // user tap" intent rather than guessing from the state transition.
        isUserPaused = nowPausing;
        if (nowPausing) player.pause();
        else player.play();
        showPlayPauseIndicator(!nowPausing);
    }

    public void toggleMute() {
        isMuted = !isMuted;
        if (player != null) player.setVolume(isMuted ? 0f : 1f);
        // ✅ FIX: also mute/unmute the photo slideshow background audio player
        if (photoAudioPlayer != null) {
            try { photoAudioPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f); }
            catch (Exception ignored) {}
        }
        if (btnMute != null) btnMute.setImageResource(
            isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
    }

    public void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_STEPS.length;
        float speed = SPEED_STEPS[speedIndex];
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
        if (btnSpeed != null) btnSpeed.setText(SPEED_LABELS[speedIndex]);
    }

    public void showSpeedPicker() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        String[] speeds = {"0.5x", "1x (Normal)", "1.5x", "2x"};
        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(delegate.getContext())
            .setTitle("Playback Speed")
            .setItems(speeds, (d, which) -> {
                speedIndex = which;
                if (player != null)
                    player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
            }).create());
    }

    // ── ✅ FIX: Photo-slideshow background audio helpers ──────────────────────
    //
    // Why these exist: video reels play audio through ExoPlayer (which is bound
    // to the video surface). Photo reels have no video track — ExoPlayer is
    // intentionally NOT created for them — so they need their own lightweight
    // audio player (MediaPlayer) to play the background music stored in
    // ReelModel.musicUrl.
    //
    // Trim support: if musicStartMs > 0 or musicEndMs > 0, we seek to the trim
    // window and loop within it using a Handler runnable rather than relying on
    // MediaPlayer.setLooping() (which ignores seekTo).

    /**
     * PERF (advance #7 — "preload audio track separately"): pre-creates and
     * prepares (but does not start) the photo-reel background-music
     * MediaPlayer while this reel is still off-screen — the audio-side
     * equivalent of preparePlayerSilently() for video reels.
     *
     * Without this, startPhotoAudio() only begins prepareAsync() the
     * instant the reel becomes the visible one, so the photo slideshow's
     * first frame (which starts immediately) can appear before the music
     * has finished preparing — a visible/audible sync gap on swipe. Called
     * from ReelPlayerFragment.prewarmPlayer() for photo reels (that method
     * is a no-op for photo reels otherwise, since preparePlayerSilently()
     * — the video path — early-returns on an empty videoUrl).
     */
    public void prewarmPhotoAudio() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.musicUrl == null || reel.musicUrl.isEmpty()) return;
        if (photoAudioPlayer != null) return; // already prewarmed, or already playing

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(reel.musicUrl);
            mp.setOnPreparedListener(prepared -> {
                if (photoAudioPlayer == prepared) photoAudioPrewarmed = true;
                // Stays paused/idle here — resumePhotoAudio()/startPhotoAudio()
                // does the actual seek-to-trim-start + volume + start() once
                // this reel becomes visible.
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (photoAudioPlayer == m) {
                    photoAudioPlayer = null;
                    photoAudioPrewarmed = false;
                }
                return true;
            });
            mp.prepareAsync();
            photoAudioPlayer = mp;
        } catch (Exception e) {
            photoAudioPlayer   = null;
            photoAudioPrewarmed = false;
        }
    }

    /**
     * Starts photo audio from the beginning of the trim window. Reuses the
     * already-prepared MediaPlayer from prewarmPhotoAudio() when one is
     * available (the whole point of prewarming — resumePhotoAudio() then
     * only has to seek+start, not prepareAsync() from zero). Falls back to
     * building a fresh MediaPlayer otherwise, same as before this feature.
     */
    private void startPhotoAudio() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.musicUrl == null || reel.musicUrl.isEmpty()) return;

        final int startMs = reel.musicStartMs > 0 ? reel.musicStartMs
                          : (reel.musicStartSec > 0 ? reel.musicStartSec * 1000 : 0);
        final int endMs   = reel.musicEndMs > 0 ? reel.musicEndMs : 0;
        final boolean hasTrim = (endMs > startMs && endMs > 0);

        // PERF: reuse the prewarmed instance instead of releasing it and
        // starting a fresh prepareAsync() from zero.
        if (photoAudioPlayer != null && photoAudioPrewarmed) {
            try {
                photoAudioPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
                if (startMs > 0) photoAudioPlayer.seekTo(startMs);
                photoAudioPlayer.setLooping(!hasTrim);
                photoAudioPlayer.start();
                photoAudioStarted = true;
                if (hasTrim) schedulePhotoAudioLoop(startMs, endMs);
                return;
            } catch (Exception e) {
                releasePhotoAudio(); // fall through to a fresh build below
            }
        }

        releasePhotoAudio();

        try {
            photoAudioPlayer = new MediaPlayer();
            photoAudioPlayer.setDataSource(reel.musicUrl);
            photoAudioPlayer.setOnPreparedListener(mp -> {
                if (photoAudioPlayer == null) return;
                try {
                    mp.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
                    if (startMs > 0) mp.seekTo(startMs);
                    mp.setLooping(!hasTrim);   // loop whole track when no trim
                    mp.start();
                    photoAudioStarted = true;
                    if (hasTrim) schedulePhotoAudioLoop(startMs, endMs);
                } catch (Exception ignored) {}
            });
            photoAudioPlayer.setOnErrorListener((mp, what, extra) -> {
                releasePhotoAudio();
                return true;
            });
            photoAudioPlayer.prepareAsync();
        } catch (Exception e) {
            releasePhotoAudio();
        }
    }

    /**
     * Resumes photo audio. If no player exists yet (first play), starts one.
     * If one is paused, resumes it and re-schedules the trim-loop runnable.
     */
    private void resumePhotoAudio() {
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.musicUrl == null || reel.musicUrl.isEmpty()) return;

        if (photoAudioPlayer == null || !photoAudioStarted) {
            // Either nothing built yet, or prewarmPhotoAudio() built one
            // that was only ever prepared, never actually start()ed —
            // startPhotoAudio() handles both (it reuses the prewarmed
            // instance when photoAudioPrewarmed is set).
            startPhotoAudio();
            return;
        }
        try {
            final int startMs = reel.musicStartMs > 0 ? reel.musicStartMs
                              : (reel.musicStartSec > 0 ? reel.musicStartSec * 1000 : 0);
            final int endMs   = reel.musicEndMs > 0 ? reel.musicEndMs : 0;
            final boolean hasTrim = (endMs > startMs && endMs > 0);

            if (!photoAudioPlayer.isPlaying()) {
                photoAudioPlayer.start();
            }
            if (hasTrim) {
                // Re-schedule loop from current position
                int currentPos = photoAudioPlayer.getCurrentPosition();
                int remaining  = endMs - currentPos;
                if (remaining > 200) {
                    schedulePhotoAudioLoop(startMs, endMs);
                } else {
                    photoAudioPlayer.seekTo(startMs);
                    schedulePhotoAudioLoop(startMs, endMs);
                }
            }
        } catch (Exception e) {
            // Player in an invalid state — restart cleanly
            startPhotoAudio();
        }
    }

    /** Pauses photo audio and cancels any pending loop runnable. */
    private void pausePhotoAudio() {
        cancelPhotoAudioLoop();
        if (photoAudioPlayer != null) {
            try {
                if (photoAudioPlayer.isPlaying()) photoAudioPlayer.pause();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Schedules a runnable that fires when the trim window ends, then seeks
     * back to startMs and loops. Recursive — each invocation re-schedules itself.
     */
    private void schedulePhotoAudioLoop(int startMs, int endMs) {
        cancelPhotoAudioLoop();
        int clipDurationMs = endMs - startMs;
        if (clipDurationMs <= 0) return;
        photoAudioLoopRunnable = () -> {
            if (photoAudioPlayer == null) return;
            try {
                photoAudioPlayer.seekTo(startMs);
                if (!photoAudioPlayer.isPlaying()) photoAudioPlayer.start();
                schedulePhotoAudioLoop(startMs, endMs);   // loop again
            } catch (Exception ignored) {
                releasePhotoAudio();
            }
        };
        photoAudioHandler.postDelayed(photoAudioLoopRunnable, clipDurationMs);
    }

    private void cancelPhotoAudioLoop() {
        if (photoAudioLoopRunnable != null) {
            photoAudioHandler.removeCallbacks(photoAudioLoopRunnable);
            photoAudioLoopRunnable = null;
        }
    }

    /** Stops, releases, and nulls the photo audio player. */
    private void releasePhotoAudio() {
        cancelPhotoAudioLoop();
        if (photoAudioPlayer != null) {
            try { if (photoAudioPlayer.isPlaying()) photoAudioPlayer.stop(); }
            catch (Exception ignored) {}
            try { photoAudioPlayer.release(); }
            catch (Exception ignored) {}
            photoAudioPlayer = null;
        }
        photoAudioPrewarmed = false;
        photoAudioStarted   = false;
    }
    // ─────────────────────────────────────────────────────────────────────────

    public void showQualityPicker() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;

        AdaptiveStreamingManager.QualityCap[] caps = {
            AdaptiveStreamingManager.QualityCap.AUTO,
            AdaptiveStreamingManager.QualityCap.Q1080P,
            AdaptiveStreamingManager.QualityCap.Q720P,
            AdaptiveStreamingManager.QualityCap.Q480P,
            AdaptiveStreamingManager.QualityCap.Q360P
        };
        String[] baseLabels = {"Auto (Recommended)", "1080p HD", "720p", "480p", "360p (Data Saver)"};

        // ── Live EWMA bandwidth label ─────────────────────────────────────────
        AdaptiveStreamingManager mgr = AdaptiveStreamingManager.get(delegate.requireContext());
        long ewmaKbps = mgr.getEwmaBandwidthKbps();
        String bwLabel;
        if (ewmaKbps <= 0) {
            bwLabel = "Measuring…";
        } else if (ewmaKbps >= 1_000) {
            bwLabel = String.format(java.util.Locale.US, "%.1f Mbps", ewmaKbps / 1000.0);
        } else {
            bwLabel = ewmaKbps + " Kbps";
        }
        // Recommended cap based on EWMA
        String recLabel = AdaptiveStreamingManager.capLabel(mgr.recommendedCap(delegate.requireContext()));
        String dialogTitle = "Video Quality  ·  " + bwLabel + "  ·  rec: " + recLabel;
        // ─────────────────────────────────────────────────────────────────────

        // Mark current selection with ✓
        String[] baseOptions = new String[caps.length];
        for (int i = 0; i < caps.length; i++) {
            baseOptions[i] = caps[i] == currentCap ? "✓ " + baseLabels[i] : "   " + baseLabels[i];
        }
        // v6: Append a Data Saver toggle row at the bottom of the picker
        boolean dataSaverOn = abrEngine != null && abrEngine.isDataSaverMode();
        String[] options = new String[baseOptions.length + 1];
        System.arraycopy(baseOptions, 0, options, 0, baseOptions.length);
        options[options.length - 1] = (dataSaverOn ? "✓ " : "   ") + "Data Saver Mode (caps ABR ladder)";

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(delegate.getContext())
            .setTitle(dialogTitle)
            .setItems(options, (d, which) -> {
                if (which == options.length - 1) {
                    // Toggle Data Saver instead of picking a quality
                    if (abrEngine == null) abrEngine = ReelABREngine.get(delegate.requireContext());
                    abrEngine.setDataSaverMode(!dataSaverOn);
                    android.widget.Toast.makeText(delegate.requireContext(),
                        !dataSaverOn ? "Data Saver enabled" : "Data Saver disabled",
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                AdaptiveStreamingManager.QualityCap chosen = caps[which];
                userManualCap = (chosen != AdaptiveStreamingManager.QualityCap.AUTO);
                currentCap = chosen;
                stallCount = 0;
                switchToQuality(currentCap, userManualCap ? "(manual)" : "");
            }).create());
    }

    /** v5: Manually trigger offline caching of the current reel for in-app offline playback. */
    public void saveReelOffline() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;
        if (offlineManager == null) offlineManager = ReelOfflineManager.get(delegate.requireContext());

        if (offlineManager.isAvailableOffline(reel.reelId)) {
            android.widget.Toast.makeText(delegate.requireContext(),
                "Already saved for offline viewing", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        offlineManager.downloadForOffline(reel);
        android.widget.Toast.makeText(delegate.requireContext(),
            "Saving reel for offline viewing…", android.widget.Toast.LENGTH_SHORT).show();
    }

    /** ✅ true if THIS reel is currently playing via a single HLS adaptive manifest */
    public boolean isHlsActive() {
        return isHlsActive;
    }

    /**
     * ✅ Streaming Mode info dialog — tells the user, per reel, whether it's
     * playing via Cloudinary's HLS Adaptive Streaming (single .m3u8 manifest,
     * ABR handled natively by ExoPlayer) or the legacy per-quality-URL
     * fallback (video480/720/1080, manual cap switching via
     * AdaptiveStreamingManager.applyQualityCap's source-rebuild path).
     * Falls back automatically whenever reel.hlsManifestUrl is empty — see
     * preparePlayerSilently().
     */
    public void showStreamingModeInfo() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;

        String title;
        String message;
        if (isHlsActive) {
            title = "🟢 HLS Adaptive Streaming";
            message = "This reel is playing via a single adaptive manifest (.m3u8).\n\n"
                + "Quality switches happen in-place — no player rebuild, no re-buffer.\n\n"
                + "Cloudinary Adaptive Streaming add-on: Enabled for this reel.";
        } else {
            title = "🟡 Per-Quality URL (Fallback)";
            message = "This reel is playing via separate per-quality video files "
                + "(480p/720p/1080p), not a single HLS manifest.\n\n"
                + "Quality switches rebuild the player source, so a short reload "
                + "may happen when switching.\n\n"
                + "This happens automatically when the Cloudinary Adaptive Streaming "
                + "add-on isn't enabled on the account (or wasn't enabled when this "
                + "particular reel was uploaded). Nothing is broken — playback still "
                + "works normally.\n\n"
                + "Check: Cloudinary Dashboard → Settings → Add-ons.";
        }
        message += "\n\nCurrent quality: " + AdaptiveStreamingManager.capLabel(currentCap);

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(delegate.getContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create());
    }

    /**
     * Cache Status dialog — tells the user, per reel, whether the video is
     * currently sitting in the disk cache (won't use data if replayed) or
     * not, and if not, the most likely reason:
     *   1. Never watched far enough to be cached in the first place.
     *   2. Watched/cached before, but a recent memory-pressure trim
     *      (onTrimMemory MODERATE/COMPLETE — e.g. swipe-closing the app)
     *      removed it. See UnifiedVideoCacheManager.trimMemory() +
     *      ReelCacheEvictionLog.
     *   3. Watched/cached before, no recent trim recorded — most likely the
     *      cache hit its size limit and the LRU evictor
     *      (LeastRecentlyUsedCacheEvictor) quietly removed it to make room
     *      for newer reels.
     */
    public void showCacheStatus() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;

        String url = reel.videoUrl;
        long cachedBytes  = com.callx.app.cache.UnifiedVideoCacheManager.getCachedBytesForUrl(url);
        long contentBytes = com.callx.app.cache.UnifiedVideoCacheManager.getContentLengthForUrl(url);
        boolean fullyCached = com.callx.app.cache.UnifiedVideoCacheManager.isReelFullyCached(url);
        long totalUsed   = com.callx.app.cache.UnifiedVideoCacheManager.getReelsCacheBytes();
        long totalLimit  = com.callx.app.cache.UnifiedVideoCacheManager.getReelsCacheLimitBytes();
        String usageLine = "\n\nReels cache in use: " + formatSize(totalUsed) + " / " + formatSize(totalLimit);

        String title;
        String message;
        if (fullyCached) {
            // ✅ Entire file on disk — replaying genuinely costs zero data.
            title = "✅ Fully cached";
            message = "This reel is fully cached on disk (" + formatSize(cachedBytes) + "). "
                + "Replaying it won't use mobile data." + usageLine;
        } else if (cachedBytes > 0) {
            // 🟡 BUGFIX: previously this showed "✅ Cached — 0 MB stored" for
            // reels that only had the small autoplay prewarm chunk cached
            // (PARTIAL_BYTES_REELS, ~6MB) — not the full video. That's a
            // real partial cache, not nothing, but replaying it still needs
            // to fetch the rest, so it's labelled separately here.
            title = "🟡 Partially cached";
            String ofTotal = contentBytes > 0 ? (" of " + formatSize(contentBytes)) : "";
            message = "Only part of this reel is cached (" + formatSize(cachedBytes) + ofTotal
                + ") — usually just the autoplay prewarm chunk. "
                + "Replaying it will still use some data to fetch the rest." + usageLine;
        } else {
            android.content.Context ctx = delegate.getContext();
            boolean everWatched = com.callx.app.cache.ReelCacheEvictionLog
                .wasEverWatchedEnoughToCache(ctx, reel.reelId);
            long msSinceTrim = com.callx.app.cache.ReelCacheEvictionLog.msSinceLastTrim(ctx);
            String trimReason = com.callx.app.cache.ReelCacheEvictionLog.lastTrimReason(ctx);

            title = "❌ Not cached";
            if (!everWatched) {
                message = "This reel hasn't been watched enough yet to be fully cached. "
                    + "It'll cache automatically once you watch more of it.";
            } else if (msSinceTrim >= 0 && msSinceTrim < 30 * 60 * 1000L && trimReason != null) {
                long minsAgo = msSinceTrim / 60000;
                message = "This reel was cached before but got removed " + minsAgo
                    + " min ago by: " + trimReason + ".";
            } else {
                message = "This reel was cached before but isn't anymore. Most likely reason: "
                    + "the reels cache limit (" + formatSize(totalLimit) + ") was reached, "
                    + "and it was auto-removed (oldest/least-recently-watched first) to make room "
                    + "for newer reels.";
            }
            message += usageLine;
        }

        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(delegate.getContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create());
    }

    /** Shows KB for anything under 1MB instead of rounding down to a misleading "0 MB". */
    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** v5: Open the QoE Analytics dashboard for this reel session. */
    public void showQoeStats() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        String reelId = (reel != null) ? reel.reelId : null;
        android.content.Intent intent = new android.content.Intent(
            delegate.requireContext(), ReelQoEAnalyticsActivity.class);
        if (reelId != null) intent.putExtra("reelId", reelId);
        delegate.requireContext().startActivity(intent);
    }

    /**
     * BUGFIX: called from both player-error paths. If this reel's stream URL
     * was codec-transformed (vc_h265/vc_av01) and hasn't already been retried,
     * disables codec forcing for the whole session (CodecSupport) and rebuilds
     * this player against the plain URL. Without this, an account/plan that
     * can't actually produce the requested codec transform makes every reel
     * fail to play with no way to recover.
     *
     * @return true if a retry was kicked off (caller should not show error UI yet)
     */
    private boolean tryCodecFallback() {
        if (codecFallbackAttempted) return false;
        if (com.callx.app.utils.CodecSupport.isDisabledForSession()) return false; // already plain — real failure
        codecFallbackAttempted = true;
        com.callx.app.utils.CodecSupport.disableForSession();
        Log.w(TAG, "Retrying playback without forced codec transform");
        progressHandler.post(() -> {
            if (!delegate.isAdded() || delegate.getContext() == null) return;
            // Same isPlaying()-during-stall pitfall as switchToQuality() — use
            // playWhenReady (play intent) instead, since a player mid-error
            // can also be mid-stall, where isPlaying() reads false regardless.
            boolean wasPlaying = player != null && player.getPlayWhenReady();
            if (player != null) {
                if (loopSeekHelper != null) { loopSeekHelper.detach(); loopSeekHelper = null; }
                player.release();
                player = null;
            }
            preparePlayerSilently();
            if (player != null && wasPlaying) {
                player.setVolume(isMuted ? 0f : 1f);
                player.play();
            }
        });
        return true;
    }

    public void releasePlayer() {
        codecFallbackAttempted = false; // next reel gets its own fresh fallback attempt
        stopProgressTracking();
        unregisterNetworkQualityListener();
        delegate.stopPhotoSlideshow();
        releasePhotoAudio();  // ✅ FIX: release photo background audio if active
        // PERF (advance #7): MUST detach before the player is returned to
        // the pool below — a pooled player gets handed to a different reel
        // next, and a still-attached helper would keep seeking it there.
        if (loopSeekHelper != null) { loopSeekHelper.detach(); loopSeekHelper = null; }
        if (player != null) {
            // Record final watch position before releasing
            if (player.getDuration() > 0) {
                int finalPct = (int)(player.getCurrentPosition() * 100 / player.getDuration());
                recordWatchHistory(finalPct);
            }

            // ── Persist QoE stats for this session ───────────────────────────
            if (delegate.isAdded() && delegate.getContext() != null) {
                AdaptiveStreamingManager.get(delegate.requireContext()).persistQoeSession(
                    qoeTotalStallMs,
                    qoeQualitySwitches,
                    qoeUpgrades,
                    qoeDowngrades,
                    qoeStartupMs
                );
                // v5: Also push to Firebase via ReelQoEAnalyticsActivity
                ReelModel reel = delegate.getReel();
                String reelId = (reel != null) ? reel.reelId : "unknown";
                ReelQoEAnalyticsActivity.pushSessionToFirebase(
                    delegate.getContext(),
                    reelId,
                    qoeTotalStallMs,
                    qoeStartupMs,
                    qoeQualitySwitches,
                    0L
                );
            }
            // ─────────────────────────────────────────────────────────────────

            // PERF (advance #3): return to the pool instead of destroying —
            // ExoPlayerPool.release() resets state and strips our listener,
            // so the instance is clean for whichever reel acquires it next.
            if (delegate.isAdded() && delegate.getContext() != null) {
                com.callx.app.player.ExoPlayerPool.get(delegate.requireContext()).release(player);
            } else {
                try { player.stop();    } catch (Exception ignored) {}
                try { player.release(); } catch (Exception ignored) {}
            }
            player = null;
        }
        // v5: Detach ABR engine session before player release
        if (abrEngine != null && abrSession != null) {
            abrEngine.detach(abrSession);
            abrSession = null;
        }
        abrEngine = null;
    }

    // ── ABR: Quality badge ────────────────────────────────────────────────────

    private void updateQualityBadge(int heightPx, long bwKbps) {
        if (tvQualityBadge == null || !delegate.isAdded()) return;
        String label;
        if (heightPx >= 1080)      label = "1080p";
        else if (heightPx >= 720)  label = "720p";
        else if (heightPx >= 480)  label = "480p";
        else if (heightPx >= 360)  label = "360p";
        else if (heightPx > 0)     label = heightPx + "p";
        else                       label = "Auto";

        if (bwKbps > 0) label += " · " + (bwKbps >= 1000
            ? String.format("%.1fM", bwKbps / 1000.0)
            : bwKbps + "K");

        tvQualityBadge.setText(label);
        tvQualityBadge.setVisibility(View.VISIBLE);
    }

    // ── ABR: Stall recovery — downgrade quality one step ─────────────────────

    private void downgradeQuality() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        AdaptiveStreamingManager.QualityCap newCap;
        switch (currentCap) {
            case AUTO:   newCap = AdaptiveStreamingManager.QualityCap.Q720P;  break;
            case Q1080P: newCap = AdaptiveStreamingManager.QualityCap.Q720P;  break;
            case Q720P:  newCap = AdaptiveStreamingManager.QualityCap.Q480P;  break;
            case Q480P:  newCap = AdaptiveStreamingManager.QualityCap.Q360P;  break;
            default:     return; // already at 360p — can't go lower
        }
        Log.d(TAG, "Stall downgrade: " + AdaptiveStreamingManager.capLabel(currentCap)
            + " → " + AdaptiveStreamingManager.capLabel(newCap));
        currentCap = newCap;
        stallCount = 0;

        // Rebuild player with lower cap
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.videoUrl == null) return;
        switchToQuality(currentCap, "(saving data)");
    }

    /**
     * Upgrade quality one step when network improves.
     * EWMA-gated: only upgrades if sustained bandwidth can actually support
     * the target quality. Prevents blind upgrades on momentary spikes.
     */
    private void upgradeQuality() {
        if (userManualCap) return;
        long now = System.currentTimeMillis();
        if (now - lastUpgradeMs < UPGRADE_COOLDOWN_MS) return; // cooldown

        AdaptiveStreamingManager.QualityCap newCap;
        switch (currentCap) {
            case Q360P:  newCap = AdaptiveStreamingManager.QualityCap.Q480P;  break;
            case Q480P:  newCap = AdaptiveStreamingManager.QualityCap.Q720P;  break;
            case Q720P:  newCap = AdaptiveStreamingManager.QualityCap.Q1080P; break;
            default: return; // already at top
        }

        // ── EWMA bandwidth gate ───────────────────────────────────────────────
        // Do NOT upgrade unless EWMA confirms enough headroom for the target.
        // This stops 720p upgrades when EWMA is only 500 kbps.
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        AdaptiveStreamingManager mgr = AdaptiveStreamingManager.get(delegate.requireContext());
        if (!mgr.isBandwidthSufficientFor(newCap)) {
            long ewma = mgr.getEwmaBandwidthKbps();
            Log.d(TAG, "Upgrade blocked: target=" + AdaptiveStreamingManager.capLabel(newCap)
                + " ewma=" + ewma + "kbps — insufficient bandwidth");
            return;
        }
        // ─────────────────────────────────────────────────────────────────────

        lastUpgradeMs = now;
        Log.d(TAG, "Network improved → upgrade " + AdaptiveStreamingManager.capLabel(currentCap)
            + " → " + AdaptiveStreamingManager.capLabel(newCap)
            + " ewma=" + mgr.getEwmaBandwidthKbps() + "kbps");
        currentCap = newCap;
        stallCount = 0;
        switchToQuality(currentCap, "↑ HD");
    }

    /** Shared player-rebuild for both upgrade and downgrade — always uses pickQualityUrl */
    private void switchToQuality(AdaptiveStreamingManager.QualityCap cap, String badgeSuffix) {
        // ROOT FIX (v17) — a stall downgrade (onPersistentStall), a network-
        // change auto-switch (registerNetworkQualityListener), an ABR upgrade,
        // or a manual user quality pick can all call this at ANY time,
        // regardless of whether this reel happens to be chat-docked right
        // now. Before this guard, switchToQuality() unconditionally released
        // the live `player` object and built a brand new one bound to THIS
        // fragment's own (possibly off-screen) playerView. If this reel's
        // player was the exact instance currently playing inside
        // ReelChatDockedPlayer's mini overlay, that release() call kills the
        // live docked video out from under it — the mini overlay goes
        // dead/black while an entirely new, invisible player spins up in the
        // background, fully orphaned from what the user is actually
        // watching (with no way back short of dismissing and re-docking).
        // Detect "currently docked" the same way the rest of this file
        // already does — playerView's own binding no longer points at
        // `player` because ReelChatDockedPlayer explicitly released that
        // claim when it took the surface — and defer instead: the preloader
        // still gets the new cap for whatever reel comes next, but the live
        // docked player is left completely untouched. startPlayback() applies
        // the deferred switch once this reel is genuinely visible again.
        if (player != null && playerView != null && playerView.getPlayer() != player) {
            if (preloader != null) preloader.setQualityCap(cap);
            pendingQualitySwitchWhileDocked = true;
            Log.d(TAG, "Quality switch to " + AdaptiveStreamingManager.capLabel(cap)
                + " deferred — reel is currently chat-docked");
            return;
        }

        // Sync preloader so next reel caches the same quality
        if (preloader != null) preloader.setQualityCap(cap);
        qoeQualitySwitches++;
        // Reset QoE stall tracking for new quality
        qoeStallBeginMs = 0;
        qoeStallFreeStartMs = System.currentTimeMillis();
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        // ✅ HLS in-place fast path — no stop/release/rebuild, no new
        // network source, no re-download. Just tighten/loosen the track
        // selector's resolution/bitrate ceiling on the SAME player and let
        // ExoPlayer pick a different rendition from the same manifest at
        // the next segment boundary. This is what item 3 in the HLS
        // migration ("smooth mid-playback quality switch, no buffering")
        // actually depends on — the old teardown-and-rebuild-with-a-
        // different-progressive-URL path below is for legacy reels only.
        if (isHlsActive && player != null) {
            Context hlsCtx = delegate.requireContext();
            AdaptiveStreamingManager.get(hlsCtx).applyQualityCap(player, cap);
            String label = AdaptiveStreamingManager.capLabel(cap)
                + (badgeSuffix != null && !badgeSuffix.isEmpty() ? " " + badgeSuffix : "");
            if (tvQualityBadge != null) {
                tvQualityBadge.setText(label);
                tvQualityBadge.setVisibility(android.view.View.VISIBLE);
            }
            return;
        }

        long resumePos  = player != null ? player.getCurrentPosition() : 0;
        // BUG FIX: was `player.isPlaying()` — that's false not just when
        // paused, but ALSO during a transient STATE_BUFFERING stall even
        // with playWhenReady still true. downgradeQuality()/switchToQuality()
        // is most often called BECAUSE of a stall (onPersistentStall), i.e.
        // exactly the moment isPlaying() is guaranteed to read false — so
        // the rebuilt player below was silently getting setPlayWhenReady(false)
        // and staying paused after a stall-triggered downgrade, with no
        // visible error, until the user tapped to resume manually. This
        // could also coincide with the loop-restart's own brief re-buffer at
        // position 0, matching reports of "the reel finishes and sometimes
        // doesn't auto-replay, needs a second play". getPlayWhenReady()
        // reflects actual play *intent* regardless of transient buffering.
        boolean wasPlay = player != null && player.getPlayWhenReady();

        // ── Inline teardown WITHOUT calling releasePlayer() ──────────────────
        // releasePlayer() calls recordWatchHistory() for the final position,
        // which would double-count since we're just switching quality mid-session,
        // not actually ending the watch. We stop/release the ExoPlayer directly.
        stopProgressTracking();
        unregisterNetworkQualityListener();
        if (loopSeekHelper != null) { loopSeekHelper.detach(); loopSeekHelper = null; }
        if (player != null) {
            try { player.stop();    } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        // ─────────────────────────────────────────────────────────────────────

        String url = pickQualityUrl(reel, cap);
        Context ctx = delegate.requireContext();
        player = AdaptiveStreamingManager.get(ctx).buildPlayer(url, cap, null);
        playerView.setPlayer(player);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(isMuted ? 0f : 1f);
        player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
        player.seekTo(resumePos);
        player.setPlayWhenReady(wasPlay);
        player.prepare();

        // PERF (advance #7): re-attach for the rebuilt player — the old
        // helper was detached above along with the old `player` instance.
        loopSeekHelper = new ReelLoopSeekHelper(player);
        loopSeekHelper.attach();

        // Re-register network listener for the new player
        registerNetworkQualityListener(ctx);

        String label = AdaptiveStreamingManager.capLabel(cap)
            + (badgeSuffix != null && !badgeSuffix.isEmpty() ? " " + badgeSuffix : "");
        if (tvQualityBadge != null) {
            tvQualityBadge.setText(label);
            tvQualityBadge.setVisibility(android.view.View.VISIBLE);
        }
    }

    // ── Watch History integration ─────────────────────────────────────────────

    /**
     * Records a watch event to WatchHistoryManager at key milestones.
     * Called from progress tracker at 25%, 50%, 75% and from releasePlayer() with final %.
     */
    private void recordWatchHistory(int pct) {
        if (!delegate.isAdded()) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.reelId == null) return;

        // Only record at meaningful milestones (or final position)
        int milestone = (pct >= 100) ? 100
            : (pct >= 75) ? 75
            : (pct >= 50) ? 50
            : (pct >= 25) ? 25
            : -1;
        if (milestone < 0 || milestone == lastWatchPctRecorded) return;
        lastWatchPctRecorded = milestone;

        WatchHistoryManager.get().record(reel, milestone);
        Log.d(TAG, "WatchHistory recorded: " + reel.reelId + " at " + milestone + "%");

        // Cache Status support: watched this far ⇒ it was very likely fully
        // downloaded into the disk cache by now. Record that so "Cache Status"
        // can later say "was cached, got evicted" instead of just "not cached".
        if (milestone >= 75 && delegate.getContext() != null) {
            com.callx.app.cache.ReelCacheEvictionLog.markWatched(
                delegate.getContext(), reel.reelId);
        }
    }

    // ── Play/Pause visual indicator ───────────────────────────────────────────

    private void showPlayPauseIndicator(boolean isPlay) {
        if (ivPlayPauseIndicator == null) return;
        ivPlayPauseIndicator.setImageResource(isPlay ? R.drawable.ic_play : R.drawable.ic_pause);
        ivPlayPauseIndicator.animate().cancel();
        ivPlayPauseIndicator.setAlpha(0f);
        ivPlayPauseIndicator.setScaleX(0.7f);
        ivPlayPauseIndicator.setScaleY(0.7f);
        ivPlayPauseIndicator.animate()
            .alpha(0.85f).scaleX(1f).scaleY(1f)
            .setDuration(120)
            .withEndAction(() -> {
                if (ivPlayPauseIndicator == null) return;
                ivPlayPauseIndicator.animate()
                    .alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setStartDelay(450).setDuration(200).start();
            }).start();
    }

    // ── Progress tracking ─────────────────────────────────────────────────────

    public void startProgressTracking() {
        stopProgressTracking();
        lastSavedProgressPct = -1;
        ReelModel reel = delegate.getReel();
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (!delegate.isAdded() || player == null) return;

                // PERF (v9): true while ReelChatDockedPlayer has stolen this
                // reel's video surface (see its ROOT FIX — it nulls
                // playerView's player before the mini view claims it). The
                // docked mini layout has no SeekBar at all, so touching
                // progressVideo here would just be an invalidate/traversal
                // on a view that's invisible (or on an entirely different,
                // currently backgrounded window) — silently stealing frame
                // budget from whatever the user is actually scrolling
                // (Reels feed or a chat's message list) right now.
                boolean surfaceIsDocked = playerView == null || playerView.getPlayer() != player;

                long dur = player.getDuration();
                if (dur > 0) {
                    long pos = player.getCurrentPosition();

                    // Update progress bar (0–1000 granularity) — visible-surface only.
                    if (!surfaceIsDocked) {
                        int barProgress = (int)(pos * 1000 / dur);
                        if (progressVideo != null) progressVideo.setProgress(barProgress);
                    }

                    // Firebase watch-progress milestones (every 10%) — kept
                    // regardless of docked state, this is analytics, not UI.
                    int pct     = (int)(pos * 100 / dur);
                    int milestone = (pct / 10) * 10;
                    if (milestone != lastSavedProgressPct && milestone > 0) {
                        lastSavedProgressPct = milestone;
                        String uid = delegate.safeMyUid();
                        if (uid != null && reel != null && reel.reelId != null) {
                            FirebaseUtils.getReelWatchProgressRef(uid)
                                .child(reel.reelId).setValue(milestone);
                        }
                    }

                    // Watch history milestones (25 / 50 / 75%)
                    recordWatchHistory(pct);
                }

                // PERF (v9): fewer main-thread wake-ups while nothing on
                // screen actually needs the tighter cadence.
                progressHandler.postDelayed(this, surfaceIsDocked ? 1000L : 300L);
            }
        };
        progressHandler.post(progressRunnable);
    }

    public void stopProgressTracking() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    private boolean isOnWifi(Context ctx) {
        ConnectivityManager cm =
            (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(net);
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    // ── Quality URL picker ────────────────────────────────────────────────────
    /**
     * Returns the most appropriate Cloudinary quality URL for the given cap.
     * Falls back to videoUrl if quality variants are not available (old reels).
     */
    /**
     * ROOT-CAUSE FIX (cache reuse bug): given the URL the network-based
     * quality cap would normally pick, check every quality variant of this
     * reel (480/720/1080 + raw fallback, each with the same codec transform
     * pickQualityUrl() would apply) for existing cached bytes. If one is
     * already substantially cached — meaning the user watched this reel
     * before, at some quality — reuse that exact URL so CacheDataSource
     * serves it from disk instead of re-downloading a "new" quality variant
     * just because today's network conditions suggested a different cap.
     *
     * Falls back to the original network-picked URL if nothing is cached
     * yet (first-ever view of this reel) — normal fresh-download path.
     */
    private static String preferAlreadyCachedQualityUrl(Context ctx, ReelModel reel, String networkPickedUrl) {
        if (reel == null) return networkPickedUrl;
        try {
            if (!com.callx.app.cache.ReelCacheManager.isInitialized()) {
                com.callx.app.cache.ReelCacheManager.init(ctx);
            }
            // Already-cached bytes required before we trust it as "this reel was
            // seen before" rather than a stray partial preload chunk.
            final long MIN_CACHED_BYTES = 500_000L; // 500KB

            // If the network-picked URL itself is already cached, nothing to do.
            if (com.callx.app.cache.ReelCacheManager.getCachedBytes(networkPickedUrl) >= MIN_CACHED_BYTES) {
                return networkPickedUrl;
            }

            // Check the other quality variants, highest first (best viewing experience).
            String[] candidates = new String[] {
                com.callx.app.utils.CodecSupport.applyToUrl(reel.video1080),
                com.callx.app.utils.CodecSupport.applyToUrl(reel.video720),
                com.callx.app.utils.CodecSupport.applyToUrl(reel.video480),
                com.callx.app.utils.CodecSupport.applyToUrl(reel.videoUrl),
            };
            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                if (candidate.equals(networkPickedUrl)) continue; // already checked above
                if (com.callx.app.cache.ReelCacheManager.getCachedBytes(candidate) >= MIN_CACHED_BYTES) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "preferAlreadyCachedQualityUrl: " + e.getMessage());
        }
        return networkPickedUrl;
    }

    private static String pickQualityUrl(ReelModel reel, AdaptiveStreamingManager.QualityCap cap) {
        if (reel == null) return "";
        String url480  = reel.video480  != null && !reel.video480.isEmpty()  ? reel.video480  : null;
        String url720  = reel.video720  != null && !reel.video720.isEmpty()  ? reel.video720  : null;
        String url1080 = reel.video1080 != null && !reel.video1080.isEmpty() ? reel.video1080 : null;
        String fallback = reel.videoUrl != null ? reel.videoUrl : "";

        String chosen;
        switch (cap) {
            case Q480P:   chosen = url480  != null ? url480  : fallback; break;
            case Q720P:   chosen = url720  != null ? url720  : fallback; break;
            case Q1080P:  chosen = url1080 != null ? url1080 : fallback; break;
            case Q360P:   chosen = url480  != null ? url480  : fallback; break; // 480 is closest
            case AUTO:
            default:
                // Auto: pick based on best available
                chosen = url1080 != null ? url1080 : (url720 != null ? url720 : fallback);
        }
        return applyPreferredCodec(chosen);
    }

    /**
     * PERF (advance #1 — AV1/HEVC codec forcing): wraps the chosen progressive
     * Cloudinary video URL with a vc_<codec> transform matching the best
     * codec this device can hardware-decode (see CodecSupport). Skipped for
     * HLS/DASH manifests (.m3u8/.mpd) — those are handled by
     * AdaptiveStreamingManager and already carry their own codec ladder —
     * and for non-Cloudinary URLs, where deriveVideoCodecUrl() is a no-op.
     */
    private static String applyPreferredCodec(String url) {
        // BUGFIX: delegate to CodecSupport.applyToUrl() — the single shared
        // implementation also used by ReelVideoPreloader / ReelPredictivePreloader.
        // Previously this method duplicated the same logic independently, which
        // was harmless by itself, but made it easy for the two copies to drift
        // (they briefly did — see those classes) and computed URLs that no
        // longer matched, doubling network downloads. Keep it centralized.
        return com.callx.app.utils.CodecSupport.applyToUrl(url);
    }

    // ── NetworkQualityMonitor integration ─────────────────────────────────────

    /**
     * Registers a NetworkQualityMonitor listener that auto-switches quality
     * when network conditions change (WiFi ↔ Cellular, 4G ↔ 3G etc).
     * Ignored if user has manually selected quality.
     */
    private void registerNetworkQualityListener(Context ctx) {
        unregisterNetworkQualityListener(); // remove old one first
        NetworkQualityMonitor monitor = NetworkQualityMonitor.get(ctx);
        monitor.startMonitoring();

        netQualityListener = newQuality -> {
            if (userManualCap) return; // user locked — don't auto-switch
            if (!delegate.isAdded() || delegate.getContext() == null) return;

            AdaptiveStreamingManager.QualityCap suggestedCap;
            switch (newQuality) {
                case WIFI:
                case ETHERNET:
                case CELLULAR_5G:
                    suggestedCap = AdaptiveStreamingManager.QualityCap.Q1080P;
                    break;
                case CELLULAR_4G:
                    suggestedCap = AdaptiveStreamingManager.QualityCap.Q720P;
                    break;
                case CELLULAR_3G:
                    suggestedCap = AdaptiveStreamingManager.QualityCap.Q480P;
                    break;
                case CELLULAR_2G:
                case NONE:
                default:
                    suggestedCap = AdaptiveStreamingManager.QualityCap.Q360P;
                    break;
            }

            if (suggestedCap == currentCap) return; // no change needed

            boolean isUpgrade = qualityRank(suggestedCap) > qualityRank(currentCap);
            if (isUpgrade) {
                // Upgrade with cooldown to avoid flapping
                long now = System.currentTimeMillis();
                if (now - lastUpgradeMs < UPGRADE_COOLDOWN_MS) return;
                lastUpgradeMs = now;
                Log.d(TAG, "NetQuality upgrade: " + AdaptiveStreamingManager.capLabel(currentCap)
                    + " → " + AdaptiveStreamingManager.capLabel(suggestedCap));
                currentCap = suggestedCap;
                qoeUpgrades++;
                switchToQuality(currentCap, "↑");
            } else {
                // Downgrade immediately
                Log.d(TAG, "NetQuality downgrade: " + AdaptiveStreamingManager.capLabel(currentCap)
                    + " → " + AdaptiveStreamingManager.capLabel(suggestedCap));
                currentCap = suggestedCap;
                stallCount = 0;
                qoeDowngrades++;
                switchToQuality(currentCap, "↓");
            }
        };

        monitor.addListener(netQualityListener);
    }

    private void unregisterNetworkQualityListener() {
        if (netQualityListener == null) return;
        try {
            NetworkQualityMonitor monitor = NetworkQualityMonitor.get(delegate.requireContext());
            monitor.removeListener(netQualityListener);
        } catch (Exception ignored) {}
        netQualityListener = null;
    }

    /** Numeric rank for quality cap — higher = better quality */
    private static int qualityRank(AdaptiveStreamingManager.QualityCap cap) {
        switch (cap) {
            case Q360P:  return 1;
            case Q480P:  return 2;
            case Q720P:  return 3;
            case Q1080P: return 4;
            case AUTO:   return 5;
            default:     return 0;
        }
    }


    // ── QoE Summary (for analytics / debug overlay) ────────────────────────────

    /** Returns a human-readable QoE summary for this reel session */
    public String getQoeSummary() {
        return "TTFF=" + (qoeStartupMs >= 0 ? qoeStartupMs + "ms" : "?")
            + " stall=" + qoeTotalStallMs + "ms"
            + " switches=" + qoeQualitySwitches
            + " (↑" + qoeUpgrades + " ↓" + qoeDowngrades + ")"
            + " cap=" + AdaptiveStreamingManager.capLabel(currentCap);
    }

    /** Returns startup time in ms, or -1 if not yet measured */
    public long getStartupTimeMs()   { return qoeStartupMs; }
    public long getTotalStallMs()    { return qoeTotalStallMs; }
    public int  getQualitySwitches() { return qoeQualitySwitches; }


}
