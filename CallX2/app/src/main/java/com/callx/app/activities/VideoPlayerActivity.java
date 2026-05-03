package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.ExoPlayerManager;

import java.util.Locale;

/**
 * VideoPlayerActivity — Full-screen video player
 *
 * Features:
 *  ✅ ExoPlayer streaming (no full download needed)
 *  ✅ Thumbnail shown instantly while video loads
 *  ✅ Play/Pause tap
 *  ✅ Duration display
 *  ✅ Loading indicator
 *  ✅ Fullscreen (statusbar hidden)
 *  ✅ Share video
 *
 * Add to AndroidManifest.xml:
 *   <activity
 *       android:name=".activities.VideoPlayerActivity"
 *       android:theme="@style/Theme.AppCompat.NoActionBar"
 *       android:screenOrientation="sensor"
 *       android:configChanges="orientation|screenSize|keyboardHidden" />
 *
 * Launch:
 *   Intent intent = new Intent(ctx, VideoPlayerActivity.class);
 *   intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL,  message.getMediaUrl());
 *   intent.putExtra(VideoPlayerActivity.EXTRA_THUMB_URL,  message.getThumbnailUrl());
 *   intent.putExtra(VideoPlayerActivity.EXTRA_DURATION_MS, message.getDuration());
 *   startActivity(intent);
 */
@UnstableApi
public class VideoPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URL   = "videoUrl";
    public static final String EXTRA_THUMB_URL   = "thumbUrl";
    public static final String EXTRA_DURATION_MS = "durationMs";

    private PlayerView  playerView;
    private ProgressBar progressBar;
    private ImageButton btnBack;
    private ImageButton btnShare;
    private TextView    tvDuration;

    private ExoPlayer   player;
    private String      videoUrl;
    private String      thumbUrl;
    private int         durationMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        // Full screen — hide status bar
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        playerView  = findViewById(R.id.player_view);
        progressBar = findViewById(R.id.progress_bar);
        btnBack     = findViewById(R.id.btn_back);
        btnShare    = findViewById(R.id.btn_share);
        tvDuration  = findViewById(R.id.tv_duration);

        videoUrl   = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        thumbUrl   = getIntent().getStringExtra(EXTRA_THUMB_URL);
        durationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);

        btnBack.setOnClickListener(v -> finish());
        btnShare.setOnClickListener(v -> shareVideo());

        if (durationMs > 0) {
            tvDuration.setVisibility(View.VISIBLE);
            tvDuration.setText(formatDuration(durationMs));
        }

        // Show thumbnail instantly before video loads
        if (thumbUrl != null) {
            android.widget.ImageView ivThumb = findViewById(R.id.iv_thumb);
            ivThumb.setVisibility(View.VISIBLE);
            Glide.with(this).load(thumbUrl)
                .centerCrop()
                .into(ivThumb);
        }

        initPlayer();
    }

    // ── Player setup ──────────────────────────────────────────────────────

    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.setMediaItem(MediaItem.fromUri(videoUrl));
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        progressBar.setVisibility(View.VISIBLE);
                        break;
                    case Player.STATE_READY:
                        progressBar.setVisibility(View.GONE);
                        // Hide thumbnail once video starts
                        View ivThumb = findViewById(R.id.iv_thumb);
                        if (ivThumb != null) ivThumb.setVisibility(View.GONE);
                        break;
                    case Player.STATE_ENDED:
                        progressBar.setVisibility(View.GONE);
                        // Replay from beginning
                        player.seekTo(0);
                        player.setPlayWhenReady(false);
                        break;
                }
            }
        });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ── Share ─────────────────────────────────────────────────────────────

    private void shareVideo() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, videoUrl);
        startActivity(Intent.createChooser(share, "Share video"));
    }

    // ── Duration format ───────────────────────────────────────────────────

    private static String formatDuration(int ms) {
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }
}
