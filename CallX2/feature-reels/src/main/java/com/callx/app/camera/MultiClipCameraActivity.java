package com.callx.app.camera;

import com.callx.app.editor.ReelEditorActivity;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MultiClipCameraActivity — Full-feature multi-clip recording.
 *
 * ✅ Record up to 10 clips, each up to remaining total time
 * ✅ Segmented clip bar — TikTok-style coloured segments per clip
 * ✅ Clip thumbnail strip — coloured mini-cards with clip# and duration
 * ✅ 3-2-1 countdown overlay before each clip starts recording
 * ✅ Pulsing record button animation while recording
 * ✅ Duration chips (15s / 30s / 60s) with active highlight
 * ✅ Speed chips (0.3x / 0.5x / 1x / 2x / 3x) with active highlight
 * ✅ Flip camera, Flash toggle
 * ✅ Delete last clip (with segment bar update)
 * ✅ Done → ReelEditorActivity with first clip as video URI + all paths
 */
public class MultiClipCameraActivity extends AppCompatActivity {

    public static final String EXTRA_CLIP_PATHS = "multi_clip_paths";

    private static final int REQ_PERMS  = 410;
    private static final int MAX_CLIPS  = 10;

    // Clip accent colours (one per slot, cycling if >10)
    private static final int[] CLIP_COLORS = {
        0xFFFF3B5C, 0xFF5B5BF6, 0xFF00C7BE, 0xFFFFD60A, 0xFF34C759,
        0xFFAF52DE, 0xFFFF9500, 0xFF007AFF, 0xFFFF6B6B, 0xFF30D158
    };

    // ── Inner data class ──────────────────────────────────────────────────
    private static class ClipData {
        final String path;
        long   durationMs;    // updated when clip finishes
        final int    color;

        ClipData(String path, int color) {
            this.path  = path;
            this.color = color;
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────
    private PreviewView   previewView;
    private ImageButton   btnRecord, btnFlip, btnFlash, btnDelete, btnClose;
    private TextView      btnDone;
    private TextView      tvTimer, tvMax, tvClipCount, tvSpeedLabel;
    private LinearLayout  segmentBar;
    private RecyclerView  rvClipStrip;
    private LinearLayout  layoutDuration, layoutSpeed;
    private FrameLayout   layoutCountdown;
    private TextView      tvCountdown;

    // Duration chip TextViews
    private TextView chip15s, chip30s, chip60s;
    // Speed chip TextViews
    private TextView chipSpeed03, chipSpeed05, chipSpeed1, chipSpeed2, chipSpeed3;

    // ── State ─────────────────────────────────────────────────────────────
    private final List<ClipData> clips      = new ArrayList<>();
    private boolean isRecording              = false;
    private boolean isFront                  = false;
    private boolean flashOn                  = false;
    private int     maxDurationSec           = 30;
    private float   speed                    = 1.0f;
    private long    totalElapsedMs           = 0;
    private long    currentClipStartMs       = 0;   // system time when clip started
    private long    currentClipElapsedMs     = 0;   // ms recorded in the ongoing clip
    private CountDownTimer clipTimer;
    private File    currentClipFile;

    // ── Camera ────────────────────────────────────────────────────────────
    private ProcessCameraProvider       cameraProvider;
    private VideoCapture<Recorder>      videoCapture;
    private Recording                   activeRecording;
    private Camera                      camera;
    private final ExecutorService       executor = Executors.newSingleThreadExecutor();

    // ── Animation ────────────────────────────────────────────────────────
    private ObjectAnimator pulseAnimator;
    private final Handler  handler = new Handler(Looper.getMainLooper());

    // ── Clip strip adapter (inner class) ──────────────────────────────────
    private ClipStripAdapter clipAdapter;

    // ═════════════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_clip_camera);
        bindViews();
        setupDurationChips();
        setupSpeedChips();
        updateClipUI();
        if (hasPermissions()) startCamera();
        else requestPerms();
    }

    // ─────────────────────────────────────────────────────────────────────
    private void bindViews() {
        previewView    = findViewById(R.id.preview_multi);
        btnRecord      = findViewById(R.id.btn_multi_record);
        btnFlip        = findViewById(R.id.btn_multi_flip);
        btnFlash       = findViewById(R.id.btn_multi_flash);
        btnDelete      = findViewById(R.id.btn_multi_delete);
        btnDone        = findViewById(R.id.btn_multi_done);
        btnClose       = findViewById(R.id.btn_multi_close);
        tvTimer        = findViewById(R.id.tv_multi_timer);
        tvMax          = findViewById(R.id.tv_multi_max);
        tvClipCount    = findViewById(R.id.tv_multi_clip_count);
        segmentBar     = findViewById(R.id.layout_segment_bar);
        rvClipStrip    = findViewById(R.id.rv_multi_clips);
        layoutDuration = findViewById(R.id.layout_multi_duration);
        layoutSpeed    = findViewById(R.id.layout_multi_speed);
        layoutCountdown= findViewById(R.id.layout_countdown);
        tvCountdown    = findViewById(R.id.tv_countdown);

        chip15s     = findViewById(R.id.chip_multi_15s);
        chip30s     = findViewById(R.id.chip_multi_30s);
        chip60s     = findViewById(R.id.chip_multi_60s);
        chipSpeed03 = findViewById(R.id.chip_speed_03);
        chipSpeed05 = findViewById(R.id.chip_speed_05);
        chipSpeed1  = findViewById(R.id.chip_speed_1);
        chipSpeed2  = findViewById(R.id.chip_speed_2);
        chipSpeed3  = findViewById(R.id.chip_speed_3);

        // Max label
        if (tvMax != null) tvMax.setText(" / 0:" + String.format("%02d", maxDurationSec));

        // Clip strip adapter
        if (rvClipStrip != null) {
            rvClipStrip.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            clipAdapter = new ClipStripAdapter(clips);
            rvClipStrip.setAdapter(clipAdapter);
        }

        // Button listeners
        if (btnClose  != null) btnClose.setOnClickListener(v -> finish());
        if (btnRecord != null) btnRecord.setOnClickListener(v -> {
            if (isRecording) stopClip();
            else             beginCountdownThenRecord();
        });
        if (btnFlip   != null) btnFlip.setOnClickListener(v -> { isFront = !isFront; startCamera(); });
        if (btnFlash  != null) btnFlash.setOnClickListener(v -> toggleFlash());
        if (btnDelete != null) btnDelete.setOnClickListener(v -> deleteLastClip());
        if (btnDone   != null) btnDone.setOnClickListener(v -> finishMultiClip());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DURATION CHIPS
    // ─────────────────────────────────────────────────────────────────────
    private void setupDurationChips() {
        int[]      secs  = {15, 30, 60};
        TextView[] chips = {chip15s, chip30s, chip60s};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            final int s = secs[i];
            chips[i].setOnClickListener(v -> {
                maxDurationSec = s;
                if (tvMax != null)
                    tvMax.setText(" / " + String.format("%d:%02d", s / 60, s % 60));
                highlightDurationChip(s);
                rebuildSegmentBar();
            });
        }
        highlightDurationChip(maxDurationSec);
    }

    private void highlightDurationChip(int sel) {
        int[] secs  = {15, 30, 60};
        TextView[] chips = {chip15s, chip30s, chip60s};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            boolean active = secs[i] == sel;
            chips[i].setBackground(active
                ? ContextCompat.getDrawable(this, R.drawable.bg_speed_chip_active)
                : ContextCompat.getDrawable(this, R.drawable.bg_speed_chip));
            chips[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SPEED CHIPS
    // ─────────────────────────────────────────────────────────────────────
    private void setupSpeedChips() {
        float[]    speeds  = {0.3f, 0.5f, 1.0f, 2.0f, 3.0f};
        String[]   labels  = {"0.3×","0.5×","1×","2×","3×"};
        TextView[] chips   = {chipSpeed03, chipSpeed05, chipSpeed1, chipSpeed2, chipSpeed3};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            final float s = speeds[i];
            final String l = labels[i];
            chips[i].setOnClickListener(v -> {
                speed = s;
                if (tvSpeedLabel != null) tvSpeedLabel.setText(l);
                highlightSpeedChip(s);
            });
        }
        highlightSpeedChip(speed);
    }

    private void highlightSpeedChip(float sel) {
        float[]    speeds  = {0.3f, 0.5f, 1.0f, 2.0f, 3.0f};
        TextView[] chips   = {chipSpeed03, chipSpeed05, chipSpeed1, chipSpeed2, chipSpeed3};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            boolean active = Math.abs(speeds[i] - sel) < 0.01f;
            chips[i].setBackground(active
                ? ContextCompat.getDrawable(this, R.drawable.bg_speed_chip_active)
                : ContextCompat.getDrawable(this, R.drawable.bg_speed_chip));
            chips[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  3-2-1 COUNTDOWN → RECORD
    // ─────────────────────────────────────────────────────────────────────
    private void beginCountdownThenRecord() {
        if (clips.size() >= MAX_CLIPS) {
            Toast.makeText(this, "Maximum " + MAX_CLIPS + " clips reached", Toast.LENGTH_SHORT).show();
            return;
        }
        long remaining = maxDurationSec * 1000L - totalElapsedMs;
        if (remaining <= 0) { finishMultiClip(); return; }

        if (layoutCountdown == null) { startClip(); return; }

        layoutCountdown.setVisibility(View.VISIBLE);
        if (btnRecord != null) btnRecord.setEnabled(false);

        runCountdownStep(3);
    }

    private void runCountdownStep(final int n) {
        if (tvCountdown != null) {
            tvCountdown.setText(String.valueOf(n));
            tvCountdown.setScaleX(1.5f); tvCountdown.setScaleY(1.5f); tvCountdown.setAlpha(1f);
            tvCountdown.animate().scaleX(1f).scaleY(1f).setDuration(700).start();
        }
        if (n > 1) {
            handler.postDelayed(() -> runCountdownStep(n - 1), 1000);
        } else {
            handler.postDelayed(() -> {
                if (layoutCountdown != null) layoutCountdown.setVisibility(View.GONE);
                if (btnRecord != null) btnRecord.setEnabled(true);
                startClip();
            }, 900);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CLIP RECORDING
    // ─────────────────────────────────────────────────────────────────────
    private void startClip() {
        if (videoCapture == null) return;

        int colorIdx = clips.size() % CLIP_COLORS.length;
        int color    = CLIP_COLORS[colorIdx];

        currentClipFile = new File(getCacheDir(),
            "clip_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(currentClipFile).build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        currentClipStartMs  = System.currentTimeMillis();
        currentClipElapsedMs = 0;

        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {

                if (event instanceof VideoRecordEvent.Status) {
                    // Track elapsed in this clip
                    currentClipElapsedMs =
                        ((VideoRecordEvent.Status) event)
                            .getRecordingStats()
                            .getRecordedDurationNanos() / 1_000_000L;
                }

                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    if (!fin.hasError()
                            && currentClipFile.exists()
                            && currentClipFile.length() > 0) {

                        long clipDuration = fin.getOutputResults() != null
                            ? currentClipElapsedMs
                            : (System.currentTimeMillis() - currentClipStartMs);

                        ClipData cd = new ClipData(currentClipFile.getAbsolutePath(), color);
                        cd.durationMs = Math.max(clipDuration, 500L);
                        totalElapsedMs += cd.durationMs;

                        runOnUiThread(() -> {
                            clips.add(cd);
                            if (clipAdapter != null) clipAdapter.notifyDataSetChanged();
                            rebuildSegmentBar();
                            updateClipUI();
                        });
                    }
                }
            });

        isRecording = true;
        updateRecordButton(true);
        startPulseAnimation();
        startClipTimer();
    }

    private void stopClip() {
        if (activeRecording != null) { activeRecording.stop(); activeRecording = null; }
        if (clipTimer != null)       { clipTimer.cancel(); clipTimer = null; }
        isRecording = false;
        updateRecordButton(false);
        stopPulseAnimation();
    }

    private void startClipTimer() {
        long remaining = maxDurationSec * 1000L - totalElapsedMs;
        if (remaining <= 0) { stopClip(); finishMultiClip(); return; }

        clipTimer = new CountDownTimer(remaining, 50) {
            @Override public void onTick(long ms) {
                long elapsed = maxDurationSec * 1000L - ms;
                runOnUiThread(() -> {
                    long sec  = elapsed / 1000;
                    if (tvTimer != null)
                        tvTimer.setText(String.format("%d:%02d", sec / 60, sec % 60));
                });
            }
            @Override public void onFinish() {
                stopClip();
                finishMultiClip();
            }
        };
        clipTimer.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DELETE
    // ─────────────────────────────────────────────────────────────────────
    private void deleteLastClip() {
        if (clips.isEmpty()) return;
        ClipData last = clips.remove(clips.size() - 1);
        new File(last.path).delete();
        totalElapsedMs = Math.max(0, totalElapsedMs - last.durationMs);

        // Update timer display
        long sec = totalElapsedMs / 1000;
        if (tvTimer != null)
            tvTimer.setText(String.format("%d:%02d", sec / 60, sec % 60));

        if (clipAdapter != null) clipAdapter.notifyDataSetChanged();
        rebuildSegmentBar();
        updateClipUI();
        Toast.makeText(this, "Last clip deleted", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SEGMENTED BAR
    // ─────────────────────────────────────────────────────────────────────
    private void rebuildSegmentBar() {
        if (segmentBar == null) return;
        segmentBar.removeAllViews();

        long totalMs = maxDurationSec * 1000L;

        for (int i = 0; i < clips.size(); i++) {
            ClipData clip = clips.get(i);
            float weight  = (float) clip.durationMs / totalMs;
            if (weight <= 0) weight = 0.01f;

            // Colour segment
            View seg = new View(this);
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, weight);
            seg.setLayoutParams(lp);
            seg.setBackgroundColor(clip.color);
            segmentBar.addView(seg);

            // White divider between clips
            if (i < clips.size() - 1) {
                View div = new View(this);
                div.setLayoutParams(new LinearLayout.LayoutParams(dp(2),
                    ViewGroup.LayoutParams.MATCH_PARENT));
                div.setBackgroundColor(0xFFFFFFFF);
                segmentBar.addView(div);
            }
        }

        // Remaining (dark) space
        long usedMs = totalElapsedMs;
        if (usedMs < totalMs) {
            float remWeight = (float)(totalMs - usedMs) / totalMs;
            View rem = new View(this);
            LinearLayout.LayoutParams remLp =
                new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, remWeight);
            rem.setLayoutParams(remLp);
            rem.setBackgroundColor(0x33FFFFFF);
            segmentBar.addView(rem);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  UI UPDATES
    // ─────────────────────────────────────────────────────────────────────
    private void updateClipUI() {
        boolean hasClips = !clips.isEmpty();
        if (tvClipCount != null)
            tvClipCount.setText(clips.size() + (clips.size() == 1 ? " clip" : " clips"));
        if (btnDone   != null) btnDone.setVisibility(hasClips ? View.VISIBLE : View.GONE);
        if (btnDelete != null) btnDelete.setVisibility(hasClips ? View.VISIBLE : View.GONE);
    }

    private void updateRecordButton(boolean recording) {
        if (btnRecord == null) return;
        btnRecord.setImageResource(recording ? R.drawable.ic_pause : R.drawable.ic_camera);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PULSE ANIMATION
    // ─────────────────────────────────────────────────────────────────────
    private void startPulseAnimation() {
        if (btnRecord == null) return;
        stopPulseAnimation();

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(btnRecord, "scaleX", 1.0f, 0.87f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(btnRecord, "scaleY", 1.0f, 0.87f, 1.0f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(btnRecord, "alpha", 1.0f, 0.75f, 1.0f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(700);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (isRecording && pulseAnimator != null) set.start();
            }
        });
        set.start();
        pulseAnimator = scaleX; // just a non-null sentinel
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) { pulseAnimator.cancel(); pulseAnimator = null; }
        if (btnRecord != null) {
            btnRecord.setScaleX(1f);
            btnRecord.setScaleY(1f);
            btnRecord.setAlpha(1f);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FLASH
    // ─────────────────────────────────────────────────────────────────────
    private void toggleFlash() {
        if (camera == null) return;
        flashOn = !flashOn;
        camera.getCameraControl().enableTorch(flashOn);
        if (btnFlash != null) {
            // Use alpha tint to show on/off state (no dedicated flash icon in assets)
            btnFlash.setAlpha(flashOn ? 1.0f : 0.45f);
        }
        Toast.makeText(this, flashOn ? "Flash on" : "Flash off", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FINISH → EDITOR
    // ─────────────────────────────────────────────────────────────────────
    private void finishMultiClip() {
        if (clips.isEmpty()) {
            Toast.makeText(this, "Record at least one clip", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> paths = new ArrayList<>();
        for (ClipData cd : clips) paths.add(cd.path);

        Intent intent = new Intent(this, ReelEditorActivity.class);
        intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI, paths.get(0));
        intent.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, true);
        intent.putStringArrayListExtra(EXTRA_CLIP_PATHS, paths);
        intent.putExtra("is_multi_clip", true);
        intent.putExtra("multi_clip_count", clips.size());
        startActivity(intent);
        finish();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CAMERA
    // ─────────────────────────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();
                CameraSelector sel = isFront
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD)).build();
                videoCapture = VideoCapture.withOutput(recorder);
                camera = cameraProvider.bindToLifecycle(this, sel, preview, videoCapture);
            } catch (Exception e) {
                Toast.makeText(this, "Camera error: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PERMISSIONS
    // ─────────────────────────────────────────────────────────────────────
    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
            REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == REQ_PERMS && hasPermissions()) startCamera();
        else if (code == REQ_PERMS) {
            Toast.makeText(this, "Camera & mic permission required",
                Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopPulseAnimation();
        if (clipTimer != null) clipTimer.cancel();
        executor.shutdown();
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CLIP STRIP ADAPTER
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Displays recorded clips as coloured cards in a horizontal RecyclerView.
     * Each card shows: clip color, clip number, duration.
     * Thumbnail loaded asynchronously from MediaMetadataRetriever.
     */
    static class ClipStripAdapter extends RecyclerView.Adapter<ClipStripAdapter.VH> {

        private final List<ClipData> clips;

        ClipStripAdapter(List<ClipData> clips) {
            this.clips = clips;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            // Build card view programmatically (no extra layout file needed)
            FrameLayout card = new FrameLayout(parent.getContext());
            float density    = parent.getContext().getResources().getDisplayMetrics().density;
            int   size       = (int)(52 * density);
            int   margin     = (int)(4  * density);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            card.setLayoutParams(lp);

            return new VH(card);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ClipData cd = clips.get(pos);

            // Rounded rect background in clip colour
            android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setColor(cd.color);
            bg.setCornerRadius(h.itemView.getContext()
                .getResources().getDisplayMetrics().density * 8);
            h.card.setBackground(bg);

            // Clip number badge (top-left)
            h.tvNumber.setText(String.valueOf(pos + 1));

            // Duration label (bottom)
            long sec  = cd.durationMs / 1000;
            long frac = (cd.durationMs % 1000) / 100;
            h.tvDuration.setText(sec + "." + frac + "s");

            // Async thumbnail
            h.ivThumb.setImageBitmap(null);
            final String path = cd.path;
            new Thread(() -> {
                Bitmap bmp = null;
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(path);
                    bmp = mmr.getFrameAtTime(0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    mmr.release();
                } catch (Exception ignored) {}
                final Bitmap thumb = bmp;
                h.ivThumb.post(() -> {
                    if (thumb != null) h.ivThumb.setImageBitmap(thumb);
                });
            }).start();
        }

        @Override public int getItemCount() { return clips.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final FrameLayout card;
            final ImageView   ivThumb;
            final TextView    tvNumber;
            final TextView    tvDuration;

            VH(FrameLayout fl) {
                super(fl);
                card = fl;
                android.content.Context ctx  = fl.getContext();
                float density = ctx.getResources().getDisplayMetrics().density;

                // Thumbnail fills card
                ivThumb = new ImageView(ctx);
                ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ivThumb.setAlpha(0.55f);
                fl.addView(ivThumb, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

                // Clip number (top-left)
                tvNumber = new TextView(ctx);
                tvNumber.setTextColor(Color.WHITE);
                tvNumber.setTextSize(11);
                tvNumber.setTypeface(null, Typeface.BOLD);
                tvNumber.setPadding((int)(4*density),(int)(3*density),(int)(4*density),(int)(3*density));
                tvNumber.setShadowLayer(density*2, 0, density, 0x99000000);
                FrameLayout.LayoutParams numLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
                fl.addView(tvNumber, numLp);

                // Duration (bottom-center)
                tvDuration = new TextView(ctx);
                tvDuration.setTextColor(Color.WHITE);
                tvDuration.setTextSize(9);
                tvDuration.setTypeface(null, Typeface.BOLD);
                tvDuration.setGravity(Gravity.CENTER);
                tvDuration.setBackgroundColor(0x66000000);
                tvDuration.setPadding(0,(int)(2*density),0,(int)(2*density));
                FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
                fl.addView(tvDuration, durLp);
            }
        }
    }
}
