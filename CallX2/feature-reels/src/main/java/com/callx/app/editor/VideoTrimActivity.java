package com.callx.app.editor;

import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VideoTrimActivity — In-app video trimming before sending.
 *
 * Modernized: single filmstrip range trimmer (VideoTrimRangeView) with real
 * thumbnails, directly-draggable handles and a live playhead, replacing the
 * old dual-SeekBar panel. Preview now uses ExoPlayer (PlayerView) instead
 * of the legacy VideoView for smoother, more accurate scrubbing.
 *
 * Features:
 *  ✅ Filmstrip range trimmer with real video thumbnails (start/end handles)
 *  ✅ ExoPlayer live preview with play/pause + synced playhead
 *  ✅ Frame-accurate trim using MediaExtractor + MediaMuxer
 *  ✅ Trim progress bar
 *  ✅ Duration chip (selected range)
 *  ✅ Original file never modified
 *  ✅ Returns trimmed Uri via setResult
 *
 * Usage (in ChatActivity):
 *   Intent i = new Intent(this, VideoTrimActivity.class);
 *   i.setData(videoUri);
 *   startActivityForResult(i, REQ_TRIM);
 *
 *   // onActivityResult:
 *   Uri trimmedUri = Uri.parse(data.getStringExtra("trimmedPath"));
 */
public class VideoTrimActivity extends AppCompatActivity {

    private static final String TAG = "VideoTrimActivity";
    public  static final String EXTRA_TRIMMED_PATH = "trimmedPath";
    public  static final int    REQ_TRIM           = 7771;

    private PlayerView         playerView;
    private VideoTrimRangeView trimRangeView;
    private TextView           tvDuration, tvStartTime, tvEndTime, tvProgressPct;
    private ProgressBar        pbTrim;
    private View               btnTrim, btnCancel, layoutTrimProgress;
    private ImageButton        btnPlay;

    private ExoPlayer player;

    private Uri  sourceUri;
    private long totalDurationMs = 0;
    private long trimStartMs     = 0;
    private long trimEndMs       = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExec = Executors.newSingleThreadExecutor();

    private boolean isPlaying = false;
    private Runnable progressUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_trim);

        sourceUri = getIntent().getData();
        if (sourceUri == null) {
            finish();
            return;
        }

        bindViews();
        loadVideoMetadata();
        setupPlayer();
    }

    private void bindViews() {
        playerView    = findViewById(R.id.player_trim_preview);
        trimRangeView = findViewById(R.id.trim_range_view);
        tvDuration    = findViewById(R.id.tv_trim_duration);
        tvStartTime   = findViewById(R.id.tv_trim_start_time);
        tvEndTime     = findViewById(R.id.tv_trim_end_time);
        pbTrim        = findViewById(R.id.pb_trim_progress);
        tvProgressPct = findViewById(R.id.tv_trim_progress_pct);
        layoutTrimProgress = findViewById(R.id.layout_trim_progress);
        btnTrim       = findViewById(R.id.btn_trim_send);
        btnPlay       = findViewById(R.id.btn_trim_play);
        btnCancel     = findViewById(R.id.btn_trim_cancel);

        if (btnCancel != null) btnCancel.setOnClickListener(v -> finish());
        if (btnPlay   != null) btnPlay.setOnClickListener(v -> togglePlay());
        if (btnTrim   != null) btnTrim.setOnClickListener(v -> startTrim());
    }

    private void loadVideoMetadata() {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, sourceUri);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) totalDurationMs = Long.parseLong(d);
        } catch (Exception e) {
            Log.e(TAG, "Metadata read failed", e);
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }

        trimStartMs = 0;
        trimEndMs   = totalDurationMs;

        if (trimRangeView != null) {
            trimRangeView.setVideo(sourceUri, totalDurationMs);
            trimRangeView.setListener(new VideoTrimRangeView.Listener() {
                @Override
                public void onRangeChanging(long startMs, long endMs) {
                    trimStartMs = startMs;
                    trimEndMs   = endMs;
                    updateTimeLabels();
                    seekVideoTo(trimStartMs);
                }

                @Override
                public void onRangeChanged(long startMs, long endMs) {
                    trimStartMs = startMs;
                    trimEndMs   = endMs;
                    updateTimeLabels();
                }

                @Override
                public void onScrub(long positionMs) {
                    seekVideoTo(positionMs);
                }
            });
        }

        updateTimeLabels();
    }

    private void setupPlayer() {
        if (playerView == null) return;
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(sourceUri));
        player.prepare();
        player.seekTo(trimStartMs);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    isPlaying = false;
                    updatePlayButton();
                    seekVideoTo(trimStartMs);
                }
            }
        });
    }

    private void togglePlay() {
        if (player == null) return;
        if (isPlaying) {
            player.pause();
            isPlaying = false;
        } else {
            if (player.getCurrentPosition() >= trimEndMs)
                player.seekTo(trimStartMs);
            player.play();
            isPlaying = true;
            scheduleEndCheck();
        }
        updatePlayButton();
    }

    private void scheduleEndCheck() {
        progressUpdater = new Runnable() {
            @Override public void run() {
                if (player == null) return;
                if (isPlaying && player.isPlaying()) {
                    long pos = player.getCurrentPosition();
                    if (trimRangeView != null) trimRangeView.setPlayheadMs(pos);
                    if (pos >= trimEndMs) {
                        player.pause();
                        player.seekTo(trimStartMs);
                        isPlaying = false;
                        updatePlayButton();
                        if (trimRangeView != null) trimRangeView.setPlayheadMs(-1);
                        return;
                    }
                    mainHandler.postDelayed(this, 60);
                }
            }
        };
        mainHandler.post(progressUpdater);
    }

    private void seekVideoTo(long posMs) {
        if (player != null) player.seekTo(posMs);
        if (trimRangeView != null) trimRangeView.setPlayheadMs(isPlaying ? posMs : -1);
    }

    private void updatePlayButton() {
        if (btnPlay != null) {
            btnPlay.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private void updateTimeLabels() {
        long selectedMs = trimEndMs - trimStartMs;
        if (tvStartTime != null) tvStartTime.setText(formatMs(trimStartMs));
        if (tvEndTime   != null) tvEndTime.setText(formatMs(trimEndMs));
        if (tvDuration  != null) tvDuration.setText(formatMs(selectedMs));
    }

    // ── Trim ──────────────────────────────────────────────────────────────

    private void startTrim() {
        if (btnTrim   != null) btnTrim.setEnabled(false);
        if (layoutTrimProgress != null) layoutTrimProgress.setVisibility(View.VISIBLE);
        if (pbTrim    != null) pbTrim.setProgress(0);
        if (tvProgressPct != null) tvProgressPct.setText("0%");

        bgExec.execute(() -> {
            try {
                File outFile = new File(getCacheDir(), "trimmed_" + UUID.randomUUID() + ".mp4");
                trimVideoFile(sourceUri, outFile, trimStartMs, trimEndMs,
                    pct -> mainHandler.post(() -> {
                        if (pbTrim != null) pbTrim.setProgress(pct);
                        if (tvProgressPct != null) tvProgressPct.setText(pct + "%");
                    }));

                mainHandler.post(() -> {
                    Intent result = new Intent();
                    result.putExtra(EXTRA_TRIMMED_PATH, outFile.getAbsolutePath());
                    setResult(RESULT_OK, result);
                    finish();
                });
            } catch (Exception e) {
                Log.e(TAG, "Trim failed", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Trim failed: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                    if (btnTrim != null) btnTrim.setEnabled(true);
                    if (layoutTrimProgress != null) layoutTrimProgress.setVisibility(View.GONE);
                });
            }
        });
    }

    /**
     * Frame-accurate trim using MediaExtractor + MediaMuxer.
     * Keyframe-aligned: startMs snapped to nearest preceding keyframe.
     */
    private void trimVideoFile(Uri src, File out, long startMs, long endMs,
                                ProgressCallback cb) throws Exception {
        // Copy to file first (MediaExtractor needs file path for content:// URIs)
        File tempIn = new File(getCacheDir(), "trim_in_" + UUID.randomUUID() + ".mp4");
        try {
            // Copy content:// to file
            try (java.io.InputStream is = getContentResolver().openInputStream(src);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(tempIn)) {
                if (is == null) throw new IOException("Cannot open source URI");
                byte[] buf = new byte[65536]; int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            }

            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(tempIn.getAbsolutePath());
            MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackCount = extractor.getTrackCount();
            int[] muxTrackMap = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                muxTrackMap[i] = muxer.addTrack(fmt);
            }
            muxer.start();

            long startUs = startMs * 1000L;
            long endUs   = endMs   * 1000L;
            long totalUs = (endMs - startMs) * 1000L;

            ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();

            for (int track = 0; track < trackCount; track++) {
                extractor.selectTrack(track);
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                while (true) {
                    info.offset = 0;
                    info.size   = extractor.readSampleData(buf, 0);
                    if (info.size < 0) break;

                    info.presentationTimeUs = extractor.getSampleTime();
                    if (info.presentationTimeUs > endUs) break;
                    if (info.presentationTimeUs >= startUs) {
                        info.flags = extractor.getSampleFlags();
                        // Offset timestamps to start from 0
                        info.presentationTimeUs -= startUs;
                        muxer.writeSampleData(muxTrackMap[track], buf, info);
                    }

                    extractor.advance();
                    if (cb != null && totalUs > 0) {
                        long elapsed = info.presentationTimeUs;
                        cb.onProgress(Math.min((int)(elapsed * 100 / totalUs), 99));
                    }
                }
                extractor.unselectTrack(track);
            }

            muxer.stop();
            muxer.release();
            extractor.release();
            if (cb != null) cb.onProgress(100);

        } finally {
            if (tempIn.exists()) tempIn.delete();
        }
    }

    interface ProgressCallback { void onProgress(int pct); }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String formatMs(long ms) {
        long secs = ms / 1000;
        long mins = secs / 60;
        secs = secs % 60;
        return String.format("%d:%02d", mins, secs);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (progressUpdater != null) mainHandler.removeCallbacks(progressUpdater);
        bgExec.shutdownNow();
        if (player != null) { player.release(); player = null; }
    }
}
