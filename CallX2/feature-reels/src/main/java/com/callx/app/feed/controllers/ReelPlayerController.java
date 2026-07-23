package com.callx.app.feed.controllers;

import android.content.Context;
import com.bumptech.glide.Glide;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
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
    private int        speedIndex = 1;

    // ── ABR state ─────────────────────────────────────────────────────────────
    private AdaptiveStreamingManager.QualityCap currentCap    = AdaptiveStreamingManager.QualityCap.AUTO;
    private int                                 stallCount     = 0;
    private final DefaultBandwidthMeter         bwMeter        = new DefaultBandwidthMeter.Builder(null).build();
    /** True when user manually picked a quality — disable auto-switch until they reset */
    private boolean                             userManualCap  = false;

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

        // Tap the video to toggle play/pause — normal Instagram-style behavior.
        // While docked (comments sheet open, video shrunk above it), mute-only
        // taps are owned EXCLUSIVELY by ReelPlayerFragment.onCommentsSheetVideoTap()
        // (forwarded from the sheet's touchOutside overlay, which sits above
        // this view in the dialog's window). So this listener must no-op
        // while docked — it must NOT also call toggleMute() here, since on
        // some sheet states (mid-drag / half-expanded, before the sheet fully
        // overlays the video) both this listener and the sheet's forwarded
        // tap can fire for the same touch, double-toggling mute back to
        // where it started. It must never call togglePlayPause() while
        // docked either — that flips isPlaying to false, which
        // onReelPlaybackStateChanged() in ReelsFragment reads as "show the
        // top bar + bottom nav again", popping those controls back over the
        // docked video.
        playerView.setOnClickListener(v -> {
            if (dockCornerRadiusPx <= 0.5f) { // only when truly undocked
                togglePlayPause();
            }
        });
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

        // Progressive loading: show thumbnail instantly while video buffers
        if (ivThumb != null && reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
            ivThumb.setVisibility(View.VISIBLE);
            Glide.with(ctx)
                .load(reel.thumbUrl)
                .placeholder(android.R.color.black)
                .override(480, 853)
                .into(ivThumb);
        }

        // Determine quality cap from user setting + current network
        boolean isWifi = isOnWifi(ctx);
        currentCap = ReelABRSettingsActivity.getSavedCap(ctx, isWifi);

        // If no user preference, let network quality guide us
        if (currentCap == AdaptiveStreamingManager.QualityCap.AUTO) {
            currentCap = AdaptiveStreamingManager.get(ctx).recommendedCap(ctx);
        }

        // Pick quality URL based on cap (Cloudinary transformation URLs)
        String playUrl = pickQualityUrl(reel, currentCap);

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

        // Build ABR-aware ExoPlayer via AdaptiveStreamingManager
        player = AdaptiveStreamingManager.get(ctx).buildPlayer(
            playUrl,
            currentCap,
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
            }
        );

        playerView.setPlayer(player);

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

        player.addListener(new Player.Listener() {
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
                    if (state == Player.STATE_READY && delegate.isCurrentlyVisible()) {
                        ivThumb.setVisibility(View.GONE);
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
                        // Auto-upgrade after 20s stall-free window
                        if (!userManualCap && qoeStallFreeStartMs > 0
                                && System.currentTimeMillis() - qoeStallFreeStartMs > STALL_FREE_UPGRADE_MS) {
                            upgradeQuality();
                        }
                        stallCount = 0; // reset stall count once ready
                    }
                    if (state == Player.STATE_ENDED) {
                        recordWatchHistory(100);
                        delegate.autoAdvance();
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (!delegate.isAdded()) return;
                if (playing) {
                    progressBuffering.setVisibility(View.GONE);
                    ivThumb.setVisibility(View.GONE);
                }
                if (btnMute != null) {
                    btnMute.setVisibility(playing ? View.GONE : View.VISIBLE);
                }
                if (delegate.isCurrentlyVisible()) {
                    Fragment parent = delegate.getParentFragment();
                    if (parent instanceof ReelsFragment) {
                        ((ReelsFragment) parent).onReelPlaybackStateChanged(playing);
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
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
        });

        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(0f);
        player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
        player.setPlayWhenReady(false);
        player.prepare();

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
            ivThumb.setVisibility(View.GONE);
            delegate.startPhotoSlideshow();
            delegate.startDiscAnimation();
            return;
        }

        if (reel.videoUrl == null || reel.videoUrl.isEmpty()) return;

        if (player == null) preparePlayerSilently();

        // SAFETY NET (v4): the chat-docked mini player transfers this ExoPlayer's
        // video surface away and back (ReelChatDockedPlayer.show()/collapseBack()).
        // If a tab switch and an in-flight next-reel swap ever land in the same
        // frame, playerView can end up unbound even though `player` itself is
        // fine. Re-assert the binding every time this reel becomes active so it
        // can never get stuck audio-only with no video surface.
        if (playerView.getPlayer() != player) {
            playerView.setPlayer(player);
        }

        player.setVolume(isMuted ? 0f : 1f);

        if (player.getPlaybackState() == Player.STATE_READY) {
            ivThumb.setVisibility(View.GONE);
            progressBuffering.setVisibility(View.GONE);
            startProgressTracking();
        }

        player.play();
    }

    public void pausePlayback() {
        if (delegate.isPhotoMode()) delegate.stopPhotoSlideshow();
        if (player != null) player.pause();
        stopProgressTracking();
        delegate.stopDiscAnimation();
    }

    public void togglePlayPause() {
        if (player == null) { startPlayback(); showPlayPauseIndicator(true); return; }
        boolean nowPausing = player.isPlaying();
        if (nowPausing) player.pause();
        else player.play();
        showPlayPauseIndicator(!nowPausing);
    }

    public void toggleMute() {
        isMuted = !isMuted;
        if (player != null) player.setVolume(isMuted ? 0f : 1f);
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
        new android.app.AlertDialog.Builder(delegate.getContext())
            .setTitle("Playback Speed")
            .setItems(speeds, (d, which) -> {
                speedIndex = which;
                if (player != null)
                    player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
            }).show();
    }

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

        new android.app.AlertDialog.Builder(delegate.getContext())
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
            }).show();
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
            boolean wasPlaying = player != null && player.isPlaying();
            if (player != null) {
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

            try { player.stop();    } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
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
        // Sync preloader so next reel caches the same quality
        if (preloader != null) preloader.setQualityCap(cap);
        qoeQualitySwitches++;
        // Reset QoE stall tracking for new quality
        qoeStallBeginMs = 0;
        qoeStallFreeStartMs = System.currentTimeMillis();
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;

        long resumePos  = player != null ? player.getCurrentPosition() : 0;
        boolean wasPlay = player != null && player.isPlaying();

        // ── Inline teardown WITHOUT calling releasePlayer() ──────────────────
        // releasePlayer() calls recordWatchHistory() for the final position,
        // which would double-count since we're just switching quality mid-session,
        // not actually ending the watch. We stop/release the ExoPlayer directly.
        stopProgressTracking();
        unregisterNetworkQualityListener();
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
                long dur = player.getDuration();
                if (dur > 0) {
                    long pos = player.getCurrentPosition();

                    // Update progress bar (0–1000 granularity)
                    int barProgress = (int)(pos * 1000 / dur);
                    if (progressVideo != null) progressVideo.setProgress(barProgress);

                    // Firebase watch-progress milestones (every 10%)
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
                progressHandler.postDelayed(this, 300);
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
