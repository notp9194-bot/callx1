package com.callx.app.editor;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelThumbnailPickerActivity — Choose a custom cover frame for your reel.
 *
 * Features:
 *  ✅ Extracts frames from video at regular intervals (up to 20 frames)
 *  ✅ Horizontal frame strip (thumbnail grid)
 *  ✅ Live seekbar — drag to any frame in the video
 *  ✅ Large preview of currently selected frame
 *  ✅ Selected frame highlighted in strip
 *  ✅ "Use this frame" → saves thumb as PNG and returns path to caller
 *  ✅ Background thread extraction — no ANR
 *  ✅ Works with both file path and content URI
 */
public class ReelThumbnailPickerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI      = "thumb_video_uri";
    public static final String EXTRA_IS_FILE_PATH   = "thumb_is_file";
    public static final String RESULT_THUMB_PATH    = "result_thumb_path";
    public static final String RESULT_THUMB_FRAME_MS= "result_thumb_frame_ms";

    private static final int FRAME_COUNT = 20;

    private ImageView    ivPreview;
    private RecyclerView rvFrameStrip;
    private SeekBar      sbScrub;
    private TextView     tvFrameTime, tvStatus;
    private View         btnApply, btnBack;
    private ProgressBar  progressExtract;

    private String  videoUriStr;
    private boolean isFilePath;
    private long    totalDurationMs = 0;

    private final List<FrameItem>  frames    = new ArrayList<>();
    private FrameStripAdapter      adapter;
    private int                    selectedIdx = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_thumbnail_picker);

        videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath  = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);

        if (videoUriStr == null || videoUriStr.isEmpty()) {
            Toast.makeText(this, "No video provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        extractFrames();
    }

    private void bindViews() {
        ivPreview       = findViewById(R.id.iv_thumb_preview_large);
        rvFrameStrip    = findViewById(R.id.rv_frame_strip);
        sbScrub         = findViewById(R.id.sb_thumb_scrub);
        tvFrameTime     = findViewById(R.id.tv_thumb_frame_time);
        tvStatus        = findViewById(R.id.tv_thumb_status);
        btnApply        = findViewById(R.id.btn_thumb_apply);
        btnBack         = findViewById(R.id.btn_thumb_back);
        progressExtract = findViewById(R.id.progress_thumb_extract);

        btnBack.setOnClickListener(v -> finish());
        btnApply.setOnClickListener(v -> applySelection());
        btnApply.setEnabled(false);

        adapter = new FrameStripAdapter(frames, idx -> {
            selectedIdx = idx;
            updatePreview(idx);
            sbScrub.setProgress(idx);
        });
        rvFrameStrip.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvFrameStrip.setAdapter(adapter);

        sbScrub.setMax(FRAME_COUNT - 1);
        sbScrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (user && p < frames.size()) {
                    selectedIdx = p;
                    updatePreview(p);
                    rvFrameStrip.scrollToPosition(p);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void extractFrames() {
        progressExtract.setVisibility(View.VISIBLE);
        tvStatus.setText("Extracting frames…");

        executor.execute(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                if (isFilePath) {
                    mmr.setDataSource(videoUriStr);
                } else {
                    mmr.setDataSource(this, Uri.parse(videoUriStr));
                }
                String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durStr != null) totalDurationMs = Long.parseLong(durStr);

                long interval = totalDurationMs / FRAME_COUNT;
                if (interval < 100) interval = 100;

                for (int i = 0; i < FRAME_COUNT; i++) {
                    long timeMs = i * interval;
                    Bitmap bmp  = mmr.getFrameAtTime(
                        timeMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                    if (bmp != null) {
                        Bitmap thumb = Bitmap.createScaledBitmap(bmp, 120, 200, true);
                        bmp.recycle();
                        final FrameItem item = new FrameItem(thumb, timeMs);
                        final int idx = i;
                        mainHandler.post(() -> {
                            frames.add(item);
                            adapter.notifyItemInserted(frames.size() - 1);
                            if (idx == 0) {
                                updatePreview(0);
                                sbScrub.setMax(Math.max(1, FRAME_COUNT - 1));
                            }
                        });
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() ->
                    Toast.makeText(this, "Could not extract frames", Toast.LENGTH_SHORT).show());
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
                mainHandler.post(() -> {
                    progressExtract.setVisibility(View.GONE);
                    if (!frames.isEmpty()) {
                        tvStatus.setText("Select a cover frame");
                        btnApply.setEnabled(true);
                        sbScrub.setMax(Math.max(1, frames.size() - 1));
                    } else {
                        tvStatus.setText("No frames found");
                    }
                });
            }
        });
    }

    private void updatePreview(int idx) {
        if (idx < 0 || idx >= frames.size()) return;
        FrameItem item = frames.get(idx);
        ivPreview.setImageBitmap(item.bitmap);
        long sec = item.timeMs / 1000;
        tvFrameTime.setText(String.format("%d:%02d", sec / 60, sec % 60));
        adapter.setSelectedIdx(idx);
        adapter.notifyDataSetChanged();
    }

    private void applySelection() {
        if (selectedIdx >= frames.size()) return;
        btnApply.setEnabled(false);
        progressExtract.setVisibility(View.VISIBLE);
        tvStatus.setText("Saving thumbnail…");

        FrameItem item = frames.get(selectedIdx);
        executor.execute(() -> {
            try {
                File thumbFile = new File(getCacheDir(),
                    "thumb_" + System.currentTimeMillis() + ".png");
                FileOutputStream fos = new FileOutputStream(thumbFile);
                item.bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
                fos.close();

                mainHandler.post(() -> {
                    progressExtract.setVisibility(View.GONE);
                    Intent result = new Intent();
                    result.putExtra(RESULT_THUMB_PATH,     thumbFile.getAbsolutePath());
                    result.putExtra(RESULT_THUMB_FRAME_MS, item.timeMs);
                    setResult(RESULT_OK, result);
                    finish();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progressExtract.setVisibility(View.GONE);
                    btnApply.setEnabled(true);
                    Toast.makeText(this, "Failed to save thumbnail", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        for (FrameItem f : frames) {
            if (f.bitmap != null && !f.bitmap.isRecycled()) f.bitmap.recycle();
        }
        super.onDestroy();
    }

    static class FrameItem {
        Bitmap bitmap;
        long   timeMs;
        FrameItem(Bitmap b, long t) { bitmap = b; timeMs = t; }
    }

    static class FrameStripAdapter extends RecyclerView.Adapter<FrameStripAdapter.VH> {
        private final List<FrameItem>  items;
        private final OnFrameClick     click;
        private int selectedIdx = 0;

        interface OnFrameClick { void onClick(int idx); }

        FrameStripAdapter(List<FrameItem> items, OnFrameClick click) {
            this.items = items;
            this.click = click;
        }

        void setSelectedIdx(int idx) { this.selectedIdx = idx; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_thumb_frame, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            h.iv.setImageBitmap(items.get(pos).bitmap);
            boolean sel = (pos == selectedIdx);
            h.vSelected.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> click.onClick(pos));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView iv;
            View      vSelected;
            VH(View v) {
                super(v);
                iv        = v.findViewById(R.id.iv_frame_thumb);
                vSelected = v.findViewById(R.id.v_frame_selected);
            }
        }
    }
}
