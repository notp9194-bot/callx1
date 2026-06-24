package com.callx.app.feed.controllers;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.fragment.app.Fragment;
import com.callx.app.cache.ReelCacheManager;
import com.callx.app.feed.ReelsFragment;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;

/**
 * Manages ExoPlayer setup, playback control, mute/speed, progress tracking,
 * cinema mode UI, and disc animation for a single reel.
 */
@UnstableApi
public class ReelPlayerController {

    private static final float[] SPEED_STEPS  = {0.5f, 1.0f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS = {"0.5×", "1×", "1.5×", "2×"};

    private final ReelPlayerDelegate delegate;

    // ── Owned views ───────────────────────────────────────────────────────
    private PlayerView playerView;
    private ImageView  ivThumb;
    private ImageView  ivPlayPauseIndicator;
    private ProgressBar progressVideo;
    private ProgressBar progressBuffering;
    private ImageButton btnMute;
    private TextView    btnSpeed;

    // ── Owned state ───────────────────────────────────────────────────────
    private ExoPlayer player;
    private boolean   isMuted      = false;
    private int       speedIndex   = 1; // default 1× speed

    private int  lastSavedProgressPct = -1;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    public ReelPlayerController(ReelPlayerDelegate delegate) {
        this.delegate = delegate;
    }

    // ── View binding ──────────────────────────────────────────────────────

    public void bindViews(View root) {
        playerView           = root.findViewById(R.id.player_view);
        ivThumb              = root.findViewById(R.id.iv_thumb);
        ivPlayPauseIndicator = root.findViewById(R.id.iv_play_pause_indicator);
        progressVideo        = root.findViewById(R.id.progress_video);
        progressBuffering    = root.findViewById(R.id.progress_buffering);
        btnMute              = root.findViewById(R.id.btn_mute);
        btnSpeed             = root.findViewById(R.id.btn_speed);
    }

    // ── Accessors used by Fragment / other controllers ────────────────────

    public boolean isMuted()      { return isMuted; }
    public int     getSpeedIndex()  { return speedIndex; }
    public float[] getSpeedSteps()  { return SPEED_STEPS; }
    public String[] getSpeedLabels() { return SPEED_LABELS; }
    public PlayerView getPlayerView() { return playerView; }
    public ImageView  getIvThumb()    { return ivThumb; }

    // ── Instagram-style silent pre-prepare ───────────────────────────────

    public void preparePlayerSilently() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null || reel.videoUrl == null || reel.videoUrl.isEmpty()) return;
        if (player != null) return;

        player = new ExoPlayer.Builder(delegate.requireContext()).build();
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
                    }
                    if (state == Player.STATE_ENDED) delegate.autoAdvance();
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
            public void onPlayerError(@NonNull PlaybackException error) {
                if (!delegate.isAdded()) return;
                progressBuffering.setVisibility(View.GONE);
                ivThumb.setVisibility(View.VISIBLE);
            }
        });

        CacheDataSource.Factory cacheFactory = ReelCacheManager.getCacheDataSourceFactory();
        ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(cacheFactory)
            .createMediaSource(MediaItem.fromUri(reel.videoUrl));
        player.setMediaSource(mediaSource);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(0f);
        player.setPlaybackParameters(new PlaybackParameters(SPEED_STEPS[speedIndex]));
        player.setPlayWhenReady(false);
        player.prepare();
    }

    // ── Playback control ─────────────────────────────────────────────────

    public void startPlayback() {
        if (!delegate.isAdded() || delegate.getContext() == null) return;
        ReelModel reel = delegate.getReel();
        if (reel == null) return;
        if (playerView == null) return;

        if (delegate.isPhotoMode()) {
            ivThumb.setVisibility(View.GONE);
            delegate.startPhotoSlideshow();
            delegate.startDiscAnimation();
            return;
        }

        if (reel.videoUrl == null || reel.videoUrl.isEmpty()) return;

        if (player == null) {
            preparePlayerSilently();
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
                float speed = SPEED_STEPS[speedIndex];
                if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
            }).show();
    }

    /** Set playback volume from the Original Audio options sheet (0.0 – 1.0). */
    public void setVolume(float volume) {
        if (player != null) player.setVolume(volume);
    }

    public void releasePlayer() {
        stopProgressTracking();
        delegate.stopPhotoSlideshow();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    // ── Play/Pause visual indicator ───────────────────────────────────────

    private void showPlayPauseIndicator(boolean isPlay) {
        if (ivPlayPauseIndicator == null) return;
        ivPlayPauseIndicator.setImageResource(
            isPlay ? R.drawable.ic_play : R.drawable.ic_pause);
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
                    .setStartDelay(450)
                    .setDuration(200)
                    .start();
            })
            .start();
    }

    // ── Progress tracking ─────────────────────────────────────────────────

    public void startProgressTracking() {
        stopProgressTracking();
        lastSavedProgressPct = -1;
        ReelModel reel = delegate.getReel();
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (!delegate.isAdded() || player == null) return;
                if (player.getDuration() > 0) {
                    int p = (int)(player.getCurrentPosition() * 1000 / player.getDuration());
                    if (progressVideo != null) progressVideo.setProgress(p);
                    int pct = (int)(player.getCurrentPosition() * 100 / player.getDuration());
                    int milestone = (pct / 10) * 10;
                    if (milestone != lastSavedProgressPct && milestone > 0) {
                        lastSavedProgressPct = milestone;
                        String uid = delegate.safeMyUid();
                        if (uid != null && reel != null && reel.reelId != null) {
                            FirebaseUtils.getReelWatchProgressRef(uid)
                                .child(reel.reelId).setValue(milestone);
                        }
                    }
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
}
