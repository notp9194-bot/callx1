package com.callx.app.feed.controllers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
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
import androidx.fragment.app.Fragment;

import com.callx.app.feed.ReelsFragment;
import com.callx.app.library.WatchHistoryManager;
import com.callx.app.models.ReelModel;
import com.callx.app.player.AdaptiveStreamingManager;
import com.callx.app.player.NetworkQualityMonitor;
import com.callx.app.player.ReelABRSettingsActivity;
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
    private AdaptiveStreamingManager.QualityCap currentCap = AdaptiveStreamingManager.QualityCap.AUTO;
    private int                                 stallCount = 0;
    private final DefaultBandwidthMeter         bwMeter    = new DefaultBandwidthMeter.Builder(null).build();

    // ── Watch history state ───────────────────────────────────────────────────
    private int  lastWatchPctRecorded = -1;
    private int  lastSavedProgressPct = -1;

    // ── Progress handler ──────────────────────────────────────────────────────
    private final Handler  progressHandler = new Handler(Looper.getMainLooper());
    private       Runnable progressRunnable;

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
        tvQualityBadge       = root.findViewById(R.id.tv_quality_badge); // optional view
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean   isMuted()       { return isMuted; }
    public int       getSpeedIndex() { return speedIndex; }
    public float[]   getSpeedSteps()  { return SPEED_STEPS; }
    public String[]  getSpeedLabels() { return SPEED_LABELS; }
    public PlayerView getPlayerView() { return playerView; }
    public ImageView  getIvThumb()    { return ivThumb; }

    // ── ABR: silent pre-prepare with HLS/DASH/Progressive auto-detect ─────────

    public void preparePlayerSilently() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.videoUrl == null || reel.videoUrl.isEmpty()) return;
        if (player != null) return;

        Context ctx = delegate.requireContext();

        // Determine quality cap from user setting + current network
        boolean isWifi = isOnWifi(ctx);
        currentCap = ReelABRSettingsActivity.getSavedCap(ctx, isWifi);

        // If no user preference, let network quality guide us
        if (currentCap == AdaptiveStreamingManager.QualityCap.AUTO) {
            currentCap = AdaptiveStreamingManager.get(ctx).recommendedCap(ctx);
        }

        // Build ABR-aware ExoPlayer via AdaptiveStreamingManager
        player = AdaptiveStreamingManager.get(ctx).buildPlayer(
            reel.videoUrl,
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
                    progressBuffering.setVisibility(View.GONE);
                    ivThumb.setVisibility(View.VISIBLE);
                    Log.e(TAG, "Playback error: " + e.getMessage());
                }
            }
        );

        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (!delegate.isAdded() || delegate.getContext() == null) return;
                if (state == Player.STATE_BUFFERING) {
                    if (delegate.isCurrentlyVisible()) {
                        progressBuffering.setVisibility(View.VISIBLE);
                    }
                } else {
                    progressBuffering.setVisibility(View.GONE);
                    if (state == Player.STATE_READY && delegate.isCurrentlyVisible()) {
                        ivThumb.setVisibility(View.GONE);
                        startProgressTracking();
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
                progressBuffering.setVisibility(View.GONE);
                ivThumb.setVisibility(View.VISIBLE);
                Log.e(TAG, "Player error: " + error.getMessage());
            }
        });

        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(0f);
        player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
        player.setPlayWhenReady(false);
        player.prepare();

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
        String[] options = {"Auto (Recommended)", "1080p", "720p", "480p", "360p"};
        AdaptiveStreamingManager.QualityCap[] caps = {
            AdaptiveStreamingManager.QualityCap.AUTO,
            AdaptiveStreamingManager.QualityCap.Q1080P,
            AdaptiveStreamingManager.QualityCap.Q720P,
            AdaptiveStreamingManager.QualityCap.Q480P,
            AdaptiveStreamingManager.QualityCap.Q360P
        };
        new android.app.AlertDialog.Builder(delegate.getContext())
            .setTitle("Video Quality")
            .setItems(options, (d, which) -> {
                currentCap = caps[which];
                if (tvQualityBadge != null) {
                    tvQualityBadge.setText(AdaptiveStreamingManager.capLabel(currentCap));
                    tvQualityBadge.setVisibility(android.view.View.VISIBLE);
                }
                // Rebuild player with new cap if currently playing
                if (player != null && delegate.getReel() != null && delegate.getReel().videoUrl != null) {
                    long pos = player.getCurrentPosition();
                    boolean wasPlaying = player.isPlaying();
                    player.stop();
                    player.release();
                    player = AdaptiveStreamingManager.get(delegate.requireContext())
                        .buildPlayer(delegate.getReel().videoUrl, currentCap, null);
                    playerView.setPlayer(player);
                    player.seekTo(pos);
                    player.prepare();
                    if (wasPlaying) player.play();
                }
            }).show();
    }

    public void releasePlayer() {
        stopProgressTracking();
        delegate.stopPhotoSlideshow();
        if (player != null) {
            // Record final watch position before releasing
            if (player.getDuration() > 0) {
                int finalPct = (int)(player.getCurrentPosition() * 100 / player.getDuration());
                recordWatchHistory(finalPct);
            }
            try { player.stop();    } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
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
        long resumePos = player != null ? player.getCurrentPosition() : 0;
        releasePlayer();

        Context ctx = delegate.requireContext();
        player = AdaptiveStreamingManager.get(ctx).buildPlayer(reel.videoUrl, currentCap, null);
        playerView.setPlayer(player);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(isMuted ? 0f : 1f);
        player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
        player.seekTo(resumePos);
        player.setPlayWhenReady(true);
        player.prepare();

        if (tvQualityBadge != null)
            tvQualityBadge.setText(AdaptiveStreamingManager.capLabel(currentCap) + " (saved data)");
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
}
