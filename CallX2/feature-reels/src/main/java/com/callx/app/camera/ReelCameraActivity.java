package com.callx.app.camera;

import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.music.SoundDetailActivity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import com.callx.app.reels.R;
import com.callx.app.music.AudioMixHelper;
import com.callx.app.editor.ReelEffectsActivity;
import com.callx.app.editor.ReelFiltersActivity;
import com.callx.app.editor.ReelStickerPickerActivity;
import com.callx.app.editor.ReelTextOverlayActivity;
import com.callx.app.editor.ReelSpeedControlActivity;
import com.callx.app.music.MusicPickerActivity;
import com.callx.app.library.ReelDraftsActivity;

import android.media.MediaPlayer;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelCameraActivity — Production-level in-app reel camera.
 *
 * Features:
 *  ✅ CameraX VideoCapture with quality selector (HD)
 *  ✅ Timer selector: 15s / 30s / 60s
 *  ✅ Flip camera (front ↔ back)
 *  ✅ Flash toggle (back camera only)
 *  ✅ Circular ProgressBar countdown ring
 *  ✅ Tap record to start/stop (also auto-stops at timer limit)
 *  ✅ On finish → launches ReelEditorActivity with recorded file Uri
 *  ✅ TEXT BUTTON — full flow:
 *       • Tap TEXT button → opens full-screen ReelTextOverlayActivity
 *       • Returns text + color + font + size + bgStyle + alignment
 *       • Draggable live text overlay placed on camera preview
 *       • Tap text overlay to edit it (re-opens editor pre-filled)
 *       • Long-press text overlay to delete it
 *       • Multiple texts supported
 *       • All text data serialised to JSON and forwarded to ReelEditorActivity
 */
public class ReelCameraActivity extends AppCompatActivity {

    private static final String TAG = "ReelCameraActivity";
    public  static final String EXTRA_VIDEO_URI = "video_uri";
    private static final int    REQ_PERMISSIONS = 210;

    // ── Request codes ─────────────────────────────────────────────────────
    private static final int REQ_EFFECTS  = 301;
    private static final int REQ_FILTERS  = 302;
    private static final int REQ_SPEED    = 303;
    private static final int REQ_MUSIC    = 304;
    private static final int REQ_STICKER  = 305;
    private static final int REQ_TEXT_NEW = 306;   // add a brand-new text overlay
    // Codes 400–499 are reserved for editing existing text overlays by index:
    //   editCode = 400 + overlayIndex
    private static final int REQ_TEXT_EDIT_BASE = 400;

    // ─────────────────────────────────────────────────────────────────────
    //  Inner data class — holds all properties of one text overlay
    // ─────────────────────────────────────────────────────────────────────
    private static class TextOverlayData {
        String text;
        int    color;    // ARGB int
        int    fontIdx;  // 0=Default 1=Bold 2=Italic 3=Serif 4=Mono
        int    sizeSp;
        int    bgStyle;  // 0=None 1=Semi 2=Solid
        int    align;    // 0=Left 1=Center 2=Right

        // Current on-screen position (used when re-editing to keep placement)
        float viewX = Float.NaN;
        float viewY = Float.NaN;

        // The live View placed on the camera overlay (null until rendered)
        TextView liveView;

        TextOverlayData(String text, int color, int fontIdx,
                        int sizeSp, int bgStyle, int align) {
            this.text    = text;
            this.color   = color;
            this.fontIdx = fontIdx;
            this.sizeSp  = sizeSp;
            this.bgStyle = bgStyle;
            this.align   = align;
        }

        /** Serialise to the JSON format expected by ReelEditorActivity / ReelVideoExportEngine.
         *  "value" carries "text|#color" for backward compat with the engine's parser,
         *  plus we add extra fields for richer rendering in the editor. */
        String toJson() {
            String safeText = text.replace("\\", "\\\\").replace("\"", "\\\"");
            String hex = String.format("#%06X", 0xFFFFFF & color);
            return "{"
                + "\"type\":\"text\","
                + "\"value\":\"" + safeText + "|" + hex + "\","
                + "\"color\":\"" + hex + "\","
                + "\"fontIdx\":" + fontIdx + ","
                + "\"sizeSp\":"  + sizeSp  + ","
                + "\"bgStyle\":" + bgStyle + ","
                + "\"align\":"   + align   + ","
                + "\"x\":0.5,\"y\":0.5"
                + "}";
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────
    private PreviewView   previewView;
    private ImageButton   btnRecord, btnFlipCamera, btnFlash, btnClose;
    private ProgressBar   progressRecord;
    private TextView      tvTimer, tvSelectedDuration;
    private View          chip15s, chip30s, chip60s;
    private ImageButton   btnEffects, btnCameraFilters, btnCameraSpeed,
                          btnCameraMusic, btnMultiClip, btnDrafts,
                          btnCameraText, btnCameraStickers;

    // Live overlay root (transparent FrameLayout above camera preview)
    private FrameLayout rootOverlay;
    private View        filterOverlayView;

    // ── Filter / effect state ──────────────────────────────────────────────
    private String filterName       = "";
    private float  filterBrightness = 0f;
    private float  filterContrast   = 1f;
    private float  filterSaturation = 1f;
    private float  filterBeauty     = 0f;

    private String effectName       = "";
    private float  effectBrightness = 0f;
    private float  effectContrast   = 1f;
    private float  effectSaturation = 1f;
    private float  effectBeauty     = 0f;

    // ── Sticker overlays (emoji / GIF) — kept separate from text ─────────
    private final List<String>          stickerJsonList  = new ArrayList<>();

    // ── Text overlays — full data tracked here ────────────────────────────
    private final List<TextOverlayData> textOverlayList  = new ArrayList<>();

    // Recording speed
    private float cameraSpeed = 1.0f;

    // ── CameraX ───────────────────────────────────────────────────────────
    private ProcessCameraProvider      cameraProvider;
    private VideoCapture<Recorder>     videoCapture;
    private Recording                  activeRecording;
    private Camera                     camera;
    private ExecutorService            cameraExecutor;

    private int     lensFacing          = CameraSelector.LENS_FACING_BACK;
    private boolean isFlashOn           = false;
    private boolean isRecording         = false;
    private int     selectedDurationSec = 30;
    private CountDownTimer recordTimer;

    // Pre-selected sound
    private String  preSelectedSoundId    = "";
    private String  preSelectedSoundTitle = "";
    private String  preSelectedSoundUrl   = "";
    private boolean replaceAudioWithSound = false;

    private MediaPlayer soundPreviewPlayer;

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_camera);
        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();
        setupTimerChips();
        setupClickListeners();
        readSoundExtras();
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERMISSIONS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void bindViews() {
        previewView        = findViewById(R.id.preview_view);
        btnRecord          = findViewById(R.id.btn_record);
        btnFlipCamera      = findViewById(R.id.btn_flip_camera);
        btnFlash           = findViewById(R.id.btn_flash);
        btnClose           = findViewById(R.id.btn_close_camera);
        progressRecord     = findViewById(R.id.progress_record);
        tvTimer            = findViewById(R.id.tv_record_timer);
        tvSelectedDuration = findViewById(R.id.tv_selected_duration);
        chip15s            = findViewById(R.id.chip_15s);
        chip30s            = findViewById(R.id.chip_30s);
        chip60s            = findViewById(R.id.chip_60s);
        btnEffects         = findViewById(R.id.btn_camera_effects);
        btnCameraFilters   = findViewById(R.id.btn_camera_filters);
        btnCameraSpeed     = findViewById(R.id.btn_camera_speed);
        btnCameraMusic     = findViewById(R.id.btn_camera_music);
        btnMultiClip       = findViewById(R.id.btn_camera_multiclip);
        btnDrafts          = findViewById(R.id.btn_camera_drafts);
        btnCameraText      = findViewById(R.id.btn_camera_text);
        btnCameraStickers  = findViewById(R.id.btn_camera_stickers);

        // Inject a transparent overlay FrameLayout above the camera preview
        // so we can layer live text / sticker / filter views over the feed.
        ViewGroup parent = (ViewGroup) previewView.getParent();
        if (parent instanceof FrameLayout) {
            rootOverlay = (FrameLayout) parent;
            filterOverlayView = new View(this);
            filterOverlayView.setBackgroundColor(0x00000000);
            filterOverlayView.setVisibility(View.GONE);
            filterOverlayView.setClickable(false);
            // Insert directly above PreviewView (index 1), below all UI controls
            rootOverlay.addView(filterOverlayView, 1, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void setupTimerChips() {
        selectDurationChip(30);
    }

    private void selectDurationChip(int sec) {
        selectedDurationSec = sec;
        chip15s.setSelected(sec == 15);
        chip30s.setSelected(sec == 30);
        chip60s.setSelected(sec == 60);
        tvSelectedDuration.setText(sec + "s");
        progressRecord.setMax(sec);
        progressRecord.setProgress(0);
    }

    // ─────────────────────────────────────────────────────────────────────
    private void setupClickListeners() {
        btnClose.setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnFlipCamera.setOnClickListener(v -> flipCamera());
        btnFlash.setOnClickListener(v -> toggleFlash());

        if (btnEffects != null)
            btnEffects.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ReelEffectsActivity.class), REQ_EFFECTS));

        if (btnCameraFilters != null)
            btnCameraFilters.setOnClickListener(v -> launchFiltersActivity());

        if (btnCameraSpeed != null)
            btnCameraSpeed.setOnClickListener(v -> {
                Intent si = new Intent(this, ReelSpeedControlActivity.class);
                si.putExtra(ReelSpeedControlActivity.EXTRA_CURRENT_SPEED, cameraSpeed);
                startActivityForResult(si, REQ_SPEED);
            });

        if (btnCameraMusic != null)
            btnCameraMusic.setOnClickListener(v ->
                startActivityForResult(
                    new Intent(this, com.callx.app.music.ReelTrendingAudioActivity.class),
                    REQ_MUSIC));

        if (btnMultiClip != null)
            btnMultiClip.setOnClickListener(v ->
                startActivity(new Intent(this, MultiClipCameraActivity.class)));

        if (btnDrafts != null)
            btnDrafts.setOnClickListener(v ->
                startActivity(new Intent(this, ReelDraftsActivity.class)));

        // TEXT button → open full-screen text overlay editor
        if (btnCameraText != null)
            btnCameraText.setOnClickListener(v -> openTextEditor(-1));

        if (btnCameraStickers != null)
            btnCameraStickers.setOnClickListener(v ->
                startActivityForResult(
                    new Intent(this, ReelStickerPickerActivity.class), REQ_STICKER));

        chip15s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(15); });
        chip30s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(30); });
        chip60s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(60); });
    }

    // ─────────────────────────────────────────────────────────────────────
    /**
     * Open the text overlay editor.
     * @param editIndex  -1 → create a new text overlay
     *                   ≥0 → edit the existing overlay at that index
     */
    private void openTextEditor(int editIndex) {
        Intent intent = new Intent(this, ReelTextOverlayActivity.class);

        if (editIndex >= 0 && editIndex < textOverlayList.size()) {
            // Pre-fill editor with existing overlay data so user can tweak it
            TextOverlayData existing = textOverlayList.get(editIndex);
            intent.putExtra(ReelTextOverlayActivity.EXTRA_INITIAL_TEXT,  existing.text);
            intent.putExtra(ReelTextOverlayActivity.EXTRA_INITIAL_COLOR,
                String.format("#%06X", 0xFFFFFF & existing.color));
            intent.putExtra(ReelTextOverlayActivity.EXTRA_INITIAL_FONT,  existing.fontIdx);
            intent.putExtra(ReelTextOverlayActivity.EXTRA_INITIAL_SIZE,  existing.sizeSp);
            intent.putExtra(ReelTextOverlayActivity.EXTRA_INITIAL_BG,    existing.bgStyle);
            intent.putExtra(ReelTextOverlayActivity.EXTRA_INITIAL_ALIGN, existing.align);
            // Use REQ_TEXT_EDIT_BASE + index so onActivityResult knows which one to update
            startActivityForResult(intent, REQ_TEXT_EDIT_BASE + editIndex);
        } else {
            // Brand-new text
            startActivityForResult(intent, REQ_TEXT_NEW);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void readSoundExtras() {
        Intent i = getIntent();
        if (i == null) return;
        String id    = i.getStringExtra("selected_sound_id");
        String title = i.getStringExtra("selected_sound_title");
        String url   = i.getStringExtra("selected_sound_url");
        if (id    != null && !id.isEmpty())    preSelectedSoundId    = id;
        if (title != null && !title.isEmpty()) preSelectedSoundTitle = title;
        if (url   != null && !url.isEmpty())   preSelectedSoundUrl   = url;

        replaceAudioWithSound = i.getBooleanExtra("replace_audio_with_sound", false);

        if (!preSelectedSoundTitle.isEmpty() && btnCameraMusic != null) {
            btnCameraMusic.setContentDescription(preSelectedSoundTitle);
            Toast.makeText(this, "Sound ready: " + preSelectedSoundTitle, Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
            ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        CameraSelector cameraSelector = new CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);

        cameraProvider.unbindAll();
        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, videoCapture);
            updateFlashIcon();
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void toggleRecording() {
        if (isRecording) stopRecording();
        else             startRecording();
    }

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        File outputFile = new File(getCacheDir(),
            "reel_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();

        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    isRecording = true;
                    runOnUiThread(this::onRecordingStarted);
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    if (!fin.hasError()) {
                        String path = outputFile.getAbsolutePath();
                        runOnUiThread(() -> openEditor(path));
                    } else {
                        Log.e(TAG, "Recording error: " + fin.getError());
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                }
            });
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (recordTimer != null) {
            recordTimer.cancel();
            recordTimer = null;
        }
        stopSoundPreview();
        progressRecord.setProgress(0);
        tvTimer.setText("00:00");
    }

    private void onRecordingStarted() {
        btnRecord.setImageResource(R.drawable.ic_pause);
        setTimerChipsEnabled(false);
        startSoundPreview();

        final int[] elapsed = {0};
        recordTimer = new CountDownTimer(selectedDurationSec * 1000L, 1000) {
            @Override public void onTick(long msRemaining) {
                elapsed[0]++;
                progressRecord.setProgress(elapsed[0]);
                int remaining = selectedDurationSec - elapsed[0];
                tvTimer.setText(String.format("%02d:%02d", remaining / 60, remaining % 60));
            }
            @Override public void onFinish() { stopRecording(); }
        }.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    private void openEditor(String filePath) {
        btnRecord.setImageResource(R.drawable.ic_camera);
        setTimerChipsEnabled(true);
        tvTimer.setText("00:00");
        progressRecord.setProgress(0);

        if (replaceAudioWithSound && preSelectedSoundUrl != null
                && !preSelectedSoundUrl.isEmpty()) {
            btnRecord.setEnabled(false);
            Toast.makeText(this, "Applying sound…", Toast.LENGTH_SHORT).show();

            AudioMixHelper.replaceAudioWithSound(
                this,
                filePath,
                preSelectedSoundUrl,
                new AudioMixHelper.MixCallback() {
                    @Override public void onProgress(int percent) { /* silent */ }
                    @Override public void onSuccess(String outputPath) {
                        if (isFinishing() || isDestroyed()) return;
                        btnRecord.setEnabled(true);
                        launchEditorWithFile(outputPath);
                    }
                    @Override public void onError(Exception e) {
                        if (isFinishing() || isDestroyed()) return;
                        btnRecord.setEnabled(true);
                        Toast.makeText(ReelCameraActivity.this,
                            "Audio replace failed, will mix on upload.", Toast.LENGTH_SHORT).show();
                        launchEditorWithFile(filePath);
                    }
                }
            );
        } else {
            launchEditorWithFile(filePath);
        }
    }

    private void launchEditorWithFile(String filePath) {
        Intent intent = new Intent(this, ReelEditorActivity.class);
        intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI, filePath);

        // Sound
        if (!preSelectedSoundId.isEmpty())    intent.putExtra("selected_sound_id",    preSelectedSoundId);
        if (!preSelectedSoundTitle.isEmpty()) intent.putExtra("selected_sound_title", preSelectedSoundTitle);
        if (!preSelectedSoundUrl.isEmpty())   intent.putExtra("selected_sound_url",   preSelectedSoundUrl);
        if (replaceAudioWithSound)            intent.putExtra("audio_already_replaced", true);

        // Filter
        if (filterName != null && !filterName.isEmpty() && !filterName.equals("Normal")) {
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_NAME,       filterName);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_BRIGHTNESS, filterBrightness);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_CONTRAST,   filterContrast);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_SATURATION, filterSaturation);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_BEAUTY,     filterBeauty);
        }

        // Effect
        if (effectName != null && !effectName.isEmpty()
                && !effectName.equals("None") && !effectName.equals("Normal")) {
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_EFFECT_NAME,       effectName);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_EFFECT_BRIGHTNESS, effectBrightness);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_EFFECT_CONTRAST,   effectContrast);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_EFFECT_SATURATION, effectSaturation);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_EFFECT_BEAUTY,     effectBeauty);
        }

        // Speed
        if (cameraSpeed != 1.0f)
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_SPEED, cameraSpeed);

        // ── Build combined overlay JSON array (stickers + texts) ──────────
        List<String> allOverlays = new ArrayList<>(stickerJsonList);
        for (TextOverlayData td : textOverlayList) {
            allOverlays.add(td.toJson());
        }
        if (!allOverlays.isEmpty()) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < allOverlays.size(); i++) {
                if (i > 0) arr.append(",");
                arr.append(allOverlays.get(i));
            }
            arr.append("]");
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_STICKERS_JSON, arr.toString());
        }

        startActivity(intent);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEXT OVERLAY — full live flow
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add or update a text overlay on the camera preview.
     *
     * @param data      The text overlay data.
     * @param editIndex -1 → new overlay; ≥0 → replace the view at that index.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addOrUpdateLiveTextOverlay(TextOverlayData data, int editIndex) {
        if (rootOverlay == null) return;

        // Remove old view if editing an existing overlay
        if (editIndex >= 0 && editIndex < textOverlayList.size()) {
            TextOverlayData old = textOverlayList.get(editIndex);
            if (old.liveView != null) {
                // Save position before removing so we restore it after recreating
                data.viewX = old.liveView.getX();
                data.viewY = old.liveView.getY();
                rootOverlay.removeView(old.liveView);
            }
        }

        int dp = (int) getResources().getDisplayMetrics().density;

        // ── Build the live TextView ───────────────────────────────────────
        TextView tv = new TextView(this);
        tv.setText(data.text);
        tv.setTextSize(data.sizeSp);
        tv.setTextColor(data.color);
        tv.setPadding(dp * 10, dp * 6, dp * 10, dp * 6);

        // Background style
        switch (data.bgStyle) {
            case 1:  tv.setBackgroundColor(0x99000000); break;
            case 2:  tv.setBackgroundColor(0xFF000000); break;
            default: tv.setBackgroundColor(Color.TRANSPARENT); break;
        }

        // Alignment
        int grav = (data.align == 1) ? Gravity.CENTER
                 : (data.align == 2) ? Gravity.END | Gravity.CENTER_VERTICAL
                 : Gravity.START | Gravity.CENTER_VERTICAL;
        tv.setGravity(grav);

        // Typeface
        Typeface tf;
        int      style = Typeface.NORMAL;
        switch (data.fontIdx) {
            case 1: tf = Typeface.DEFAULT_BOLD;         break;
            case 2: tf = Typeface.DEFAULT; style = Typeface.ITALIC; break;
            case 3: tf = Typeface.SERIF;                break;
            case 4: tf = Typeface.MONOSPACE;            break;
            default: tf = Typeface.DEFAULT;             break;
        }
        tv.setTypeface(tf, style);

        // ── Position ──────────────────────────────────────────────────────
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT);

        if (!Float.isNaN(data.viewX) && !Float.isNaN(data.viewY)) {
            // Restore saved position from previous placement / edit
            lp.leftMargin = 0;
            lp.topMargin  = 0;
        } else {
            // Default: centre of the overlay
            lp.gravity    = Gravity.CENTER;
        }
        rootOverlay.addView(tv, lp);

        // Apply saved x/y after layout
        if (!Float.isNaN(data.viewX) && !Float.isNaN(data.viewY)) {
            tv.setX(data.viewX);
            tv.setY(data.viewY);
        }

        data.liveView = tv;

        // ── Track in list ─────────────────────────────────────────────────
        if (editIndex >= 0 && editIndex < textOverlayList.size()) {
            textOverlayList.set(editIndex, data);
        } else {
            textOverlayList.add(data);
        }

        // ── Text button tint — shows user that a text is active ───────────
        if (btnCameraText != null)
            btnCameraText.setColorFilter(Color.argb(220, 255, 255, 255));

        // ── Drag support ──────────────────────────────────────────────────
        final float[] dXY = new float[2];
        final boolean[] moved = {false};
        final long[] downTime = {0};
        final int overlayIdx = editIndex >= 0 ? editIndex : textOverlayList.size() - 1;

        tv.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dXY[0]    = v.getX() - event.getRawX();
                    dXY[1]    = v.getY() - event.getRawY();
                    moved[0]  = false;
                    downTime[0] = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + dXY[0];
                    float newY = event.getRawY() + dXY[1];
                    if (Math.abs(newX - v.getX()) > 5 || Math.abs(newY - v.getY()) > 5) {
                        moved[0] = true;
                    }
                    v.setX(newX);
                    v.setY(newY);
                    // Update saved position in data object
                    data.viewX = newX;
                    data.viewY = newY;
                    return true;

                case MotionEvent.ACTION_UP:
                    long duration = System.currentTimeMillis() - downTime[0];
                    if (!moved[0] && duration < 200) {
                        // Short tap → edit this text overlay
                        openTextEditor(overlayIdx);
                    } else if (!moved[0] && duration >= 600) {
                        // Long press → delete this text overlay
                        deleteTextOverlay(overlayIdx);
                    }
                    return true;
            }
            return false;
        });

        // Delete hint on first text added
        if (textOverlayList.size() == 1) {
            Toast.makeText(this,
                "Tap text to edit • Hold to delete", Toast.LENGTH_LONG).show();
        }
    }

    /** Remove a text overlay view and its data entry. */
    private void deleteTextOverlay(int index) {
        if (index < 0 || index >= textOverlayList.size()) return;
        TextOverlayData td = textOverlayList.get(index);
        if (td.liveView != null) rootOverlay.removeView(td.liveView);
        textOverlayList.remove(index);

        // Re-set touch listeners with updated indices for remaining overlays
        rebindTextOverlayTouches();

        Toast.makeText(this, "Text removed", Toast.LENGTH_SHORT).show();

        // If no texts left, clear tint
        if (textOverlayList.isEmpty() && btnCameraText != null) {
            btnCameraText.clearColorFilter();
        }
    }

    /**
     * After a delete, the indices shift — rebind touch listeners on all remaining
     * text views so their edit/delete callbacks reference the correct indices.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void rebindTextOverlayTouches() {
        for (int i = 0; i < textOverlayList.size(); i++) {
            TextOverlayData data = textOverlayList.get(i);
            if (data.liveView == null) continue;
            final int idx = i;
            final float[] dXY     = new float[2];
            final boolean[] moved = {false};
            final long[] downTime = {0};

            data.liveView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dXY[0]     = v.getX() - event.getRawX();
                        dXY[1]     = v.getY() - event.getRawY();
                        moved[0]   = false;
                        downTime[0] = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float newX = event.getRawX() + dXY[0];
                        float newY = event.getRawY() + dXY[1];
                        if (Math.abs(newX - v.getX()) > 5 || Math.abs(newY - v.getY()) > 5)
                            moved[0] = true;
                        v.setX(newX); v.setY(newY);
                        data.viewX = newX; data.viewY = newY;
                        return true;
                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - downTime[0];
                        if (!moved[0] && dur < 200)   openTextEditor(idx);
                        else if (!moved[0] && dur >= 600) deleteTextOverlay(idx);
                        return true;
                }
                return false;
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  STICKER (emoji / GIF) overlay — unchanged from v7
    // ─────────────────────────────────────────────────────────────────────
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addLiveOverlayView(String stickerJson) {
        if (rootOverlay == null || stickerJson == null || stickerJson.isEmpty()) return;
        int dp = (int) getResources().getDisplayMetrics().density;

        String value = "";
        try {
            int vStart = stickerJson.indexOf("\"value\":\"") + 9;
            int vEnd   = stickerJson.indexOf("\"", vStart);
            if (vStart > 8 && vEnd > vStart) value = stickerJson.substring(vStart, vEnd);
        } catch (Exception ignored) {}
        if (value.isEmpty()) value = "✨";

        int textColor = Color.WHITE;
        if (value.contains("|#")) {
            int sep = value.lastIndexOf("|#");
            String colorHex = value.substring(sep + 1);
            value = value.substring(0, sep);
            try { textColor = Color.parseColor(colorHex); } catch (Exception ignored) {}
        }

        TextView overlayView = new TextView(this);
        overlayView.setText(value);
        overlayView.setTextSize(32);
        overlayView.setTextColor(textColor);
        overlayView.setPadding(8 * dp, 4 * dp, 8 * dp, 4 * dp);
        overlayView.setBackgroundColor(0x55000000);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = rootOverlay.getWidth()  / 4;
        lp.topMargin  = rootOverlay.getHeight() / 3;
        rootOverlay.addView(overlayView, lp);

        overlayView.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setX(event.getRawX() + dX);
                        v.setY(event.getRawY() + dY);
                        return true;
                }
                return false;
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Filter live tint
    // ─────────────────────────────────────────────────────────────────────
    private void applyLiveFilter(String name) {
        if (filterOverlayView == null || name == null) return;
        int overlayColor;
        switch (name) {
            case "Warm":      overlayColor = 0x22FF8800; break;
            case "Cool":      overlayColor = 0x220044FF; break;
            case "Vivid":     overlayColor = 0x1AFF00AA; break;
            case "Fade":      overlayColor = 0x33FFFFFF; break;
            case "Drama":     overlayColor = 0x33000000; break;
            case "Vintage":   overlayColor = 0x22884400; break;
            case "Mono":      overlayColor = 0x44888888; break;
            case "Noir":      overlayColor = 0x55000000; break;
            case "Juno":      overlayColor = 0x22FFAA00; break;
            case "Lark":      overlayColor = 0x1500DDFF; break;
            case "Clarendon": overlayColor = 0x220055CC; break;
            case "Normal":    overlayColor = 0x00000000; break;
            default:          overlayColor = 0x11FFFFFF; break;
        }
        if (name.equals("Normal") || name.isEmpty()) {
            filterOverlayView.setVisibility(View.GONE);
        } else {
            filterOverlayView.setBackgroundColor(overlayColor);
            filterOverlayView.setVisibility(View.VISIBLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Filters activity launch (with live camera frame as thumbnail)
    // ─────────────────────────────────────────────────────────────────────
    private void launchFiltersActivity() {
        Intent i = new Intent(this, ReelFiltersActivity.class);
        try {
            android.graphics.Bitmap bmp = previewView.getBitmap();
            if (bmp != null) {
                java.io.File thumbFile =
                    new java.io.File(getCacheDir(), "filter_thumb_preview.jpg");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(thumbFile);
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                fos.close();
                i.putExtra(ReelFiltersActivity.EXTRA_THUMBNAIL_URI,
                    android.net.Uri.fromFile(thumbFile).toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not capture filter preview thumbnail: " + e.getMessage());
        }
        if (filterName != null && !filterName.isEmpty()) {
            i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_FILTER,     filterName);
            i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_BRIGHTNESS, filterBrightness);
            i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_CONTRAST,   filterContrast);
            i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_SATURATION, filterSaturation);
            i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_BEAUTY,     filterBeauty);
        }
        startActivityForResult(i, REQ_FILTERS);
    }

    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // ── Effects ───────────────────────────────────────────────────────
        if (requestCode == REQ_EFFECTS && resultCode == RESULT_OK && data != null) {
            effectName       = data.getStringExtra(ReelEffectsActivity.RESULT_EFFECT_NAME);
            if (effectName == null) effectName = "";
            effectBrightness = data.getFloatExtra(ReelEffectsActivity.RESULT_BRIGHTNESS,   0f);
            effectContrast   = data.getFloatExtra(ReelEffectsActivity.RESULT_CONTRAST,     1f);
            effectSaturation = data.getFloatExtra(ReelEffectsActivity.RESULT_SATURATION,   1f);
            effectBeauty     = data.getFloatExtra(ReelEffectsActivity.RESULT_BEAUTY_LEVEL, 0f);
            applyLiveFilter(effectName);
            if (btnEffects != null)
                btnEffects.setColorFilter(
                    Color.argb(220, 91, 91, 246), android.graphics.PorterDuff.Mode.SRC_IN);
            if (!effectName.isEmpty())
                Toast.makeText(this, "Effect: " + effectName, Toast.LENGTH_SHORT).show();
        }

        // ── Filters ───────────────────────────────────────────────────────
        if (requestCode == REQ_FILTERS && resultCode == RESULT_OK && data != null) {
            filterName       = data.getStringExtra(ReelFiltersActivity.RESULT_FILTER_NAME);
            if (filterName == null) filterName = "";
            filterBrightness = data.getFloatExtra(ReelFiltersActivity.RESULT_BRIGHTNESS,   0f);
            filterContrast   = data.getFloatExtra(ReelFiltersActivity.RESULT_CONTRAST,     1f);
            filterSaturation = data.getFloatExtra(ReelFiltersActivity.RESULT_SATURATION,   1f);
            filterBeauty     = data.getFloatExtra(ReelFiltersActivity.RESULT_BEAUTY_LEVEL, 0f);
            applyLiveFilter(filterName);
            if (btnCameraFilters != null)
                btnCameraFilters.setColorFilter(Color.argb(220, 168, 85, 247));
        }

        // ── Stickers ──────────────────────────────────────────────────────
        if (requestCode == REQ_STICKER && resultCode == RESULT_OK && data != null) {
            String sJson = data.getStringExtra(ReelStickerPickerActivity.RESULT_STICKER_JSON);
            if (sJson != null && !sJson.isEmpty()) {
                stickerJsonList.add(sJson);
                addLiveOverlayView(sJson);
                if (btnCameraStickers != null)
                    btnCameraStickers.setColorFilter(Color.argb(220, 255, 215, 10));
            }
        }

        // ── Speed ─────────────────────────────────────────────────────────
        if (requestCode == REQ_SPEED && resultCode == RESULT_OK && data != null) {
            cameraSpeed = data.getFloatExtra(ReelSpeedControlActivity.RESULT_SPEED, 1.0f);
            if (btnCameraSpeed != null) {
                int tint = (cameraSpeed == 1.0f) ? Color.WHITE
                    : Color.argb(220, 255, 200, 0);
                btnCameraSpeed.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            String label = (cameraSpeed == 1.0f) ? "Normal"
                : String.format(java.util.Locale.US, "%.1fx", cameraSpeed);
            Toast.makeText(this, "Speed: " + label, Toast.LENGTH_SHORT).show();
        }

        // ── Music ─────────────────────────────────────────────────────────
        if (requestCode == REQ_MUSIC && resultCode == RESULT_OK && data != null) {
            String id     = data.getStringExtra(
                com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_ID);
            String title  = data.getStringExtra(
                com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_TITLE);
            String artist = data.getStringExtra(
                com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_ARTIST);
            String url    = data.getStringExtra(
                com.callx.app.music.ReelTrendingAudioActivity.RESULT_AUDIO_URL);
            if (id    != null && !id.isEmpty())    preSelectedSoundId    = id;
            if (title != null && !title.isEmpty()) preSelectedSoundTitle = title;
            if (url   != null && !url.isEmpty())   preSelectedSoundUrl   = url;

            String displayLabel = preSelectedSoundTitle;
            if (artist != null && !artist.isEmpty() && !artist.equals(preSelectedSoundTitle))
                displayLabel = preSelectedSoundTitle + " – " + artist;

            if (btnCameraMusic != null) {
                btnCameraMusic.setContentDescription(displayLabel);
                btnCameraMusic.setColorFilter(
                    0xFF5B5BF6, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            Toast.makeText(this, "♪  " + displayLabel, Toast.LENGTH_SHORT).show();
            startSoundPreview();
        }

        // ── Text overlay — NEW ─────────────────────────────────────────────
        if (requestCode == REQ_TEXT_NEW && resultCode == RESULT_OK && data != null) {
            handleTextOverlayResult(data, -1);
        }

        // ── Text overlay — EDIT (requestCode 400..499 maps to overlay index) ──
        if (requestCode >= REQ_TEXT_EDIT_BASE
                && requestCode < REQ_TEXT_EDIT_BASE + 100
                && resultCode == RESULT_OK && data != null) {
            int editIndex = requestCode - REQ_TEXT_EDIT_BASE;
            handleTextOverlayResult(data, editIndex);
        }
    }

    /**
     * Parse result from ReelTextOverlayActivity and add/update the live view.
     *
     * @param data      The result Intent.
     * @param editIndex -1 for new, ≥0 to update existing.
     */
    private void handleTextOverlayResult(Intent data, int editIndex) {
        String text     = data.getStringExtra(ReelTextOverlayActivity.RESULT_TEXT);
        String colorHex = data.getStringExtra(ReelTextOverlayActivity.RESULT_COLOR);
        int    fontIdx  = data.getIntExtra(ReelTextOverlayActivity.RESULT_FONT_INDEX, 0);
        int    sizeSp   = data.getIntExtra(ReelTextOverlayActivity.RESULT_SIZE_SP,    32);
        int    bgStyle  = data.getIntExtra(ReelTextOverlayActivity.RESULT_BG_STYLE,   0);
        int    align    = data.getIntExtra(ReelTextOverlayActivity.RESULT_ALIGNMENT,  1);

        if (text == null || text.isEmpty()) return;
        if (colorHex == null || colorHex.isEmpty()) colorHex = "#FFFFFF";

        int color = Color.WHITE;
        try { color = Color.parseColor(colorHex); } catch (Exception ignored) {}

        TextOverlayData overlayData = new TextOverlayData(
            text, color, fontIdx, sizeSp, bgStyle, align);

        // If editing, carry over the existing screen position
        if (editIndex >= 0 && editIndex < textOverlayList.size()) {
            TextOverlayData old = textOverlayList.get(editIndex);
            overlayData.viewX = old.viewX;
            overlayData.viewY = old.viewY;
        }

        addOrUpdateLiveTextOverlay(overlayData, editIndex);
    }

    // ─────────────────────────────────────────────────────────────────────
    private void flipCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
            ? CameraSelector.LENS_FACING_FRONT
            : CameraSelector.LENS_FACING_BACK;
        isFlashOn = false;
        bindCameraUseCases();
        updateFlashIcon();
    }

    private void toggleFlash() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, "Flash not available", Toast.LENGTH_SHORT).show();
            return;
        }
        isFlashOn = !isFlashOn;
        camera.getCameraControl().enableTorch(isFlashOn);
        updateFlashIcon();
    }

    private void updateFlashIcon() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            btnFlash.setVisibility(View.VISIBLE);
            btnFlash.setImageResource(isFlashOn
                ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
        } else {
            btnFlash.setVisibility(View.GONE);
        }
    }

    private void setTimerChipsEnabled(boolean enabled) {
        chip15s.setEnabled(enabled);
        chip30s.setEnabled(enabled);
        chip60s.setEnabled(enabled);
    }

    // ─────────────────────────────────────────────────────────────────────
    private void startSoundPreview() {
        stopSoundPreview();
        if (preSelectedSoundUrl == null || preSelectedSoundUrl.isEmpty()) return;
        try {
            soundPreviewPlayer = new MediaPlayer();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                soundPreviewPlayer.setAudioAttributes(
                    new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            } else {
                //noinspection deprecation
                soundPreviewPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            }
            soundPreviewPlayer.setDataSource(preSelectedSoundUrl);
            soundPreviewPlayer.setLooping(true);
            soundPreviewPlayer.setVolume(0.8f, 0.8f);
            soundPreviewPlayer.prepareAsync();
            soundPreviewPlayer.setOnPreparedListener(mp -> {
                if (!isFinishing() && !isDestroyed()) mp.start();
            });
            soundPreviewPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Sound preview error: " + what);
                return true;
            });
        } catch (Exception e) {
            Log.e(TAG, "startSoundPreview failed", e);
        }
    }

    private void stopSoundPreview() {
        if (soundPreviewPlayer != null) {
            try { soundPreviewPlayer.stop(); } catch (Exception ignored) {}
            soundPreviewPlayer.release();
            soundPreviewPlayer = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private boolean allPermissionsGranted() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                    "Camera and microphone permissions are required", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordTimer    != null) recordTimer.cancel();
        if (activeRecording != null) activeRecording.stop();
        stopSoundPreview();
        cameraExecutor.shutdown();
    }
}
