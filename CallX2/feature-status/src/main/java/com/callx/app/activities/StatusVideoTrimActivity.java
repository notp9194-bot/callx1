package com.callx.app.activities;

import android.content.Intent;
import android.media.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.io.*;

/**
 * StatusVideoTrimActivity v26 — Trim video before posting.
 * Range bar with start/end handle; preview plays trimmed segment.
 * Returns: EXTRA_TRIMMED_URI, EXTRA_START_MS, EXTRA_END_MS.
 */
public class StatusVideoTrimActivity extends AppCompatActivity {
    public static final String EXTRA_INPUT_URI   = "input_uri";
    public static final String EXTRA_TRIMMED_URI  = "trimmed_uri";
    public static final String EXTRA_START_MS     = "start_ms";
    public static final String EXTRA_END_MS       = "end_ms";

    private ExoPlayer player;
    private Uri inputUri;
    private long durationMs = 0;
    private long startMs    = 0;
    private long endMs      = 30_000;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(makeLayout());
    }

    private View makeLayout() {
        inputUri = Uri.parse(getIntent().getStringExtra(EXTRA_INPUT_URI));

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        root.setPadding(0,0,0,0);

        // Player
        PlayerView pv = new PlayerView(this);
        pv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
        player = new ExoPlayer.Builder(this).build();
        pv.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(inputUri));
        player.prepare(); player.setPlayWhenReady(true);
        root.addView(pv);

        // Duration info
        TextView tvDuration = new TextView(this); tvDuration.setTextColor(android.graphics.Color.WHITE);
        tvDuration.setPadding(dp(16), dp(8), dp(16), 0);
        root.addView(tvDuration);

        // Start position SeekBar
        TextView tvStart = new TextView(this); tvStart.setText("Start: 0.0s");
        tvStart.setTextColor(android.graphics.Color.WHITE); tvStart.setPadding(dp(16),dp(8),dp(16),0);
        root.addView(tvStart);
        SeekBar sbStart = new SeekBar(this); sbStart.setMax(3000); sbStart.setProgress(0);
        sbStart.setPadding(dp(16),0,dp(16),0); root.addView(sbStart);

        // End position SeekBar
        TextView tvEnd = new TextView(this); tvEnd.setText("End: 30.0s");
        tvEnd.setTextColor(android.graphics.Color.WHITE); tvEnd.setPadding(dp(16),dp(8),dp(16),0);
        root.addView(tvEnd);
        SeekBar sbEnd = new SeekBar(this); sbEnd.setMax(3000); sbEnd.setProgress(3000);
        sbEnd.setPadding(dp(16),0,dp(16),0); root.addView(sbEnd);

        // Detect actual duration
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == ExoPlayer.STATE_READY && durationMs == 0) {
                    durationMs = player.getDuration();
                    endMs = Math.min(durationMs, 30_000);
                    sbStart.setMax((int)(durationMs/100));
                    sbEnd.setMax((int)(durationMs/100)); sbEnd.setProgress((int)(endMs/100));
                    tvDuration.setText("Duration: " + String.format("%.1f", durationMs/1000.0) + "s  |  Max 30s trim");
                    tvEnd.setText("End: " + String.format("%.1f", endMs/1000.0) + "s");
                }
            }
        });

        sbStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                startMs = p * 100L;
                if (startMs >= endMs) { startMs = Math.max(0, endMs - 1000); sbStart.setProgress((int)(startMs/100)); }
                tvStart.setText("Start: " + String.format("%.1f", startMs/1000.0) + "s");
                player.seekTo(startMs);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { player.setPlayWhenReady(false); }
            @Override public void onStopTrackingTouch(SeekBar sb)  { player.setPlayWhenReady(true); }
        });
        sbEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                endMs = p * 100L;
                if (endMs - startMs > 30_000) { endMs = startMs + 30_000; sbEnd.setProgress((int)(endMs/100)); }
                if (endMs <= startMs) { endMs = startMs + 1000; sbEnd.setProgress((int)(endMs/100)); }
                tvEnd.setText("End: " + String.format("%.1f", endMs/1000.0) + "s");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb)  {}
        });

        // Buttons
        LinearLayout btns = new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.CENTER); btns.setPadding(dp(16),dp(16),dp(16),dp(16));
        Button btnCancel = new Button(this); btnCancel.setText("Cancel");
        Button btnTrim   = new Button(this); btnTrim.setText("✂ Trim & Use");
        btnTrim.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"));
        btnTrim.setTextColor(android.graphics.Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(8),0,dp(8),0);
        btnCancel.setLayoutParams(lp); btnTrim.setLayoutParams(lp);
        btns.addView(btnCancel); btns.addView(btnTrim); root.addView(btns);

        btnCancel.setOnClickListener(v -> finish());
        btnTrim.setOnClickListener(v -> {
            player.setPlayWhenReady(false);
            trimVideo(inputUri, startMs, endMs);
        });
        return root;
    }

    private void trimVideo(Uri input, long start, long end) {
        // ProgressDialog deprecated — use AlertDialog with ProgressBar instead
        android.app.AlertDialog pd = new android.app.AlertDialog.Builder(this)
            .setMessage("Trimming video…").setCancelable(false).create();
        pd.show();
        new Thread(() -> {
            try {
                File out = new File(getCacheDir(), "trimmed_" + System.currentTimeMillis() + ".mp4");
                MediaExtractor extractor = new MediaExtractor(); extractor.setDataSource(this, input, null);
                MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                android.util.SparseIntArray trackMap = new android.util.SparseIntArray();
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    extractor.selectTrack(i);
                    trackMap.put(i, muxer.addTrack(extractor.getTrackFormat(i)));
                }
                muxer.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(2*1024*1024);
                extractor.seekTo(start * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                while (true) {
                    info.size = extractor.readSampleData(buf, 0);
                    if (info.size < 0) break;
                    info.presentationTimeUs = extractor.getSampleTime();
                    if (info.presentationTimeUs > end * 1000L) break;
                    info.flags = extractor.getSampleFlags();
                    int track = extractor.getSampleTrackIndex();
                    muxer.writeSampleData(trackMap.get(track), buf, info);
                    extractor.advance();
                }
                extractor.release(); muxer.stop(); muxer.release();
                runOnUiThread(() -> {
                    pd.dismiss();
                    Intent result = new Intent();
                    result.putExtra(EXTRA_TRIMMED_URI, out.getAbsolutePath());
                    result.putExtra(EXTRA_START_MS, start); result.putExtra(EXTRA_END_MS, end);
                    setResult(RESULT_OK, result); finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { pd.dismiss(); Toast.makeText(this,"Trim failed: "+e.getMessage(),Toast.LENGTH_SHORT).show(); });
            }
        }).start();
    }

    @Override protected void onDestroy() { super.onDestroy(); if (player!=null){player.release(); player=null;} }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
