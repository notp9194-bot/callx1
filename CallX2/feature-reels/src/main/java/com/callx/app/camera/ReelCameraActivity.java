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
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Color;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
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

import com.callx.app.reels.R;
import com.callx.app.music.AudioMixHelper;
import com.callx.app.editor.ReelEffectsActivity;
import com.callx.app.editor.ReelFiltersActivity;
import com.callx.app.editor.ReelStickerPickerActivity;
import com.callx.app.editor.ReelTextOverlayActivity;
import com.callx.app.editor.ReelSpeedControlActivity;
import com.callx.app.music.MusicPickerActivity;
import com.callx.app.camera.MultiClipCameraActivity;
import com.callx.app.library.ReelDraftsActivity;

import android.media.MediaPlayer;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
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
 */
public class ReelCameraActivity extends AppCompatActivity {

    private static final String TAG = "ReelCameraActivity";
    public  static final String EXTRA_VIDEO_URI = "video_uri";
    private static final int    REQ_PERMISSIONS = 210;

    private PreviewView   previewView;
    private ImageButton   btnRecord, btnFlipCamera, btnFlash, btnClose;
    private ProgressBar   progressRecord;
    private TextView      tvTimer, tvSelectedDuration;
    private View          chip15s, chip30s, chip60s;
    private ImageButton   btnEffects, btnCameraFilters, btnCameraSpeed, btnCameraMusic, btnMultiClip, btnDrafts, btnCameraText, btnCameraStickers;

    // ✅ NEW: Live filter/text/sticker overlay (visible during recording, baked into editor)
    private FrameLayout rootOverlay;
    private View        filterOverlayView;
    private String      filterName       = "";
    private float       filterBrightness = 0f;
    private float       filterContrast   = 1f;
    private float       filterSaturation = 1f;
    private float       filterBeauty     = 0f;
    private final java.util.ArrayList<String> stickerJsonList = new java.util.ArrayList<>();

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording             activeRecording;
    private Camera                camera;
    private ExecutorService       cameraExecutor;

    private int     lensFacing          = CameraSelector.LENS_FACING_BACK;
    private boolean isFlashOn           = false;
    private boolean isRecording         = false;
    private int     selectedDurationSec = 30;
    private CountDownTimer recordTimer;

    // Pre-selected sound from SoundDetailActivity
    private String preSelectedSoundId    = "";
    private String preSelectedSoundTitle = "";
    private String preSelectedSoundUrl   = "";

    // ✅ NEW: When true, mic audio is fully replaced by selected sound URL (no mixing).
    // Set when user comes from SoundDetailActivity → "Use in Camera".
    private boolean replaceAudioWithSound = false;

    // Background sound player — plays selected sound while recording (earphone feedback)
    private MediaPlayer soundPreviewPlayer;

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

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

    private void bindViews() {
        previewView       = findViewById(R.id.preview_view);
        btnRecord         = findViewById(R.id.btn_record);
        btnFlipCamera     = findViewById(R.id.btn_flip_camera);
        btnFlash          = findViewById(R.id.btn_flash);
        btnClose          = findViewById(R.id.btn_close_camera);
        progressRecord    = findViewById(R.id.progress_record);
        tvTimer           = findViewById(R.id.tv_record_timer);
        tvSelectedDuration= findViewById(R.id.tv_selected_duration);
        chip15s           = findViewById(R.id.chip_15s);
        chip30s           = findViewById(R.id.chip_30s);
        chip60s           = findViewById(R.id.chip_60s);
        btnEffects        = findViewById(R.id.btn_camera_effects);
        btnCameraFilters  = findViewById(R.id.btn_camera_filters);
        btnCameraSpeed    = findViewById(R.id.btn_camera_speed);
        btnCameraMusic    = findViewById(R.id.btn_camera_music);
        btnMultiClip      = findViewById(R.id.btn_camera_multiclip);
        btnDrafts         = findViewById(R.id.btn_camera_drafts);
        btnCameraText     = findViewById(R.id.btn_camera_text);
        btnCameraStickers = findViewById(R.id.btn_camera_stickers);

        // ✅ NEW: Inject a transparent filter-tint overlay above the camera preview,
        // so live filters / text / stickers are visible WHILE recording (not just in editor).
        ViewGroup parent = (ViewGroup) previewView.getParent();
        if (parent instanceof FrameLayout) {
            rootOverlay = (FrameLayout) parent;
            filterOverlayView = new View(this);
            filterOverlayView.setBackgroundColor(0x00000000);
            filterOverlayView.setVisibility(View.GONE);
            filterOverlayView.setClickable(false);
            // Add directly above the preview, below all UI controls
            rootOverlay.addView(filterOverlayView, 1, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        }
    }

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

    private void setupClickListeners() {
        btnClose.setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnFlipCamera.setOnClickListener(v -> flipCamera());
        btnFlash.setOnClickListener(v -> toggleFlash());
        if (btnEffects != null)       btnEffects.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelEffectsActivity.class), 301));
        if (btnCameraFilters != null) btnCameraFilters.setOnClickListener(v -> { Intent i = new Intent(this, ReelFiltersActivity.class); startActivityForResult(i, 302); });
        if (btnCameraSpeed != null)   btnCameraSpeed.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelSpeedControlActivity.class), 303));
        if (btnCameraMusic != null)   btnCameraMusic.setOnClickListener(v -> startActivityForResult(new Intent(this, MusicPickerActivity.class), 304));
        if (btnMultiClip != null)     btnMultiClip.setOnClickListener(v -> startActivity(new Intent(this, MultiClipCameraActivity.class)));
        if (btnDrafts != null)        btnDrafts.setOnClickListener(v -> startActivity(new Intent(this, ReelDraftsActivity.class)));
        if (btnCameraText != null)     btnCameraText.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelTextOverlayActivity.class), 306));
        if (btnCameraStickers != null) btnCameraStickers.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelStickerPickerActivity.class), 305));
        chip15s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(15); });
        chip30s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(30); });
        chip60s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(60); });
    }

    private void readSoundExtras() {
        Intent i = getIntent();
        if (i == null) return;
        String id    = i.getStringExtra("selected_sound_id");
        String title = i.getStringExtra("selected_sound_title");
        String url   = i.getStringExtra("selected_sound_url");
        if (id    != null && !id.isEmpty())    preSelectedSoundId    = id;
        if (title != null && !title.isEmpty()) preSelectedSoundTitle = title;
        if (url   != null && !url.isEmpty())   preSelectedSoundUrl   = url;

        // ✅ NEW: Flag from SoundDetailActivity — fully replace mic audio
        replaceAudioWithSound = i.getBooleanExtra("replace_audio_with_sound", false);

        // Show pre-selected music label on the music button if a sound was passed
        if (!preSelectedSoundTitle.isEmpty() && btnCameraMusic != null) {
            btnCameraMusic.setContentDescription(preSelectedSoundTitle);
            Toast.makeText(this,
                "Sound ready: " + preSelectedSoundTitle, Toast.LENGTH_SHORT).show();
        }
    }

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
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
            updateFlashIcon();
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
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
                    VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    if (!finalize.hasError()) {
                        String path = outputFile.getAbsolutePath();
                        runOnUiThread(() -> openEditor(path));
                    } else {
                        Log.e(TAG, "Recording error: " + finalize.getError());
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

        // Play selected sound in background while recording (earphone feedback)
        startSoundPreview();

        final int[] elapsed = {0};
        recordTimer = new CountDownTimer(selectedDurationSec * 1000L, 1000) {
            @Override public void onTick(long msRemaining) {
                elapsed[0]++;
                progressRecord.setProgress(elapsed[0]);
                int remaining = selectedDurationSec - elapsed[0];
                tvTimer.setText(String.format("%02d:%02d", remaining / 60, remaining % 60));
            }
            @Override public void onFinish() {
                stopRecording();
            }
        }.start();
    }

    private void openEditor(String filePath) {
        btnRecord.setImageResource(R.drawable.ic_camera);
        setTimerChipsEnabled(true);
        tvTimer.setText("00:00");
        progressRecord.setProgress(0);

        // ✅ NEW: If user came from SoundDetailActivity → "Use in Camera",
        // replace the mic audio track entirely with the selected sound URL.
        if (replaceAudioWithSound && preSelectedSoundUrl != null && !preSelectedSoundUrl.isEmpty()) {
            // Show progress indicator while replacing audio
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
                        // Fallback: pass original file (sound will be mixed in upload step)
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
        // Pass pre-selected sound through to editor → upload
        if (!preSelectedSoundId.isEmpty())    intent.putExtra("selected_sound_id",    preSelectedSoundId);
        if (!preSelectedSoundTitle.isEmpty()) intent.putExtra("selected_sound_title", preSelectedSoundTitle);
        if (!preSelectedSoundUrl.isEmpty())   intent.putExtra("selected_sound_url",   preSelectedSoundUrl);
        // If audio was already replaced at camera stage, tell upload NOT to mix again
        if (replaceAudioWithSound)            intent.putExtra("audio_already_replaced", true);

        // ✅ NEW: Carry the live-applied filter + text/sticker overlays into the editor
        if (filterName != null && !filterName.isEmpty() && !filterName.equals("Normal")) {
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_NAME,       filterName);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_BRIGHTNESS, filterBrightness);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_CONTRAST,   filterContrast);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_SATURATION, filterSaturation);
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_FILTER_BEAUTY,     filterBeauty);
        }
        if (!stickerJsonList.isEmpty()) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < stickerJsonList.size(); i++) {
                if (i > 0) arr.append(",");
                arr.append(stickerJsonList.get(i));
            }
            arr.append("]");
            intent.putExtra(ReelEditorActivity.EXTRA_PRESET_STICKERS_JSON, arr.toString());
        }

        startActivity(intent);
    }

    /**
     * ✅ NEW: Apply a semi-transparent colour tint over the camera preview to simulate the
     * selected filter LIVE, while recording. Same colour mapping used in ReelEditorActivity
     * so the look stays consistent between camera → editor → final reel.
     */
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

    /**
     * ✅ NEW: Add a draggable live text/sticker/emoji overlay on top of the camera preview.
     * Mirrors ReelEditorActivity.addStickerOverlay() so the same JSON ("type"/"value"/"x"/"y")
     * renders identically here and after recording.
     */
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

        // Drag to reposition (position is just visual here; baked again in editor)
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @androidx.annotation.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // ✅ NEW: Filters selected for live preview → apply tint overlay on camera + carry to editor
        if (requestCode == 302 && resultCode == RESULT_OK && data != null) {
            filterName       = data.getStringExtra(ReelFiltersActivity.RESULT_FILTER_NAME);
            if (filterName == null) filterName = "";
            filterBrightness = data.getFloatExtra(ReelFiltersActivity.RESULT_BRIGHTNESS,   0f);
            filterContrast   = data.getFloatExtra(ReelFiltersActivity.RESULT_CONTRAST,     1f);
            filterSaturation = data.getFloatExtra(ReelFiltersActivity.RESULT_SATURATION,   1f);
            filterBeauty     = data.getFloatExtra(ReelFiltersActivity.RESULT_BEAUTY_LEVEL, 0f);
            applyLiveFilter(filterName);
            if (btnCameraFilters != null) btnCameraFilters.setColorFilter(Color.argb(220, 168, 85, 247));
        }

        // ✅ NEW: Sticker/emoji/GIF selected → show live draggable overlay on camera preview
        if (requestCode == 305 && resultCode == RESULT_OK && data != null) {
            String sJson = data.getStringExtra(ReelStickerPickerActivity.RESULT_STICKER_JSON);
            if (sJson != null && !sJson.isEmpty()) {
                stickerJsonList.add(sJson);
                addLiveOverlayView(sJson);
                if (btnCameraStickers != null) btnCameraStickers.setColorFilter(Color.argb(220, 255, 215, 10));
            }
        }

        // ✅ NEW: Text overlay created → show live draggable text on camera preview
        if (requestCode == 306 && resultCode == RESULT_OK && data != null) {
            String text  = data.getStringExtra(ReelTextOverlayActivity.RESULT_TEXT);
            String color = data.getStringExtra(ReelTextOverlayActivity.RESULT_COLOR);
            if (text != null && !text.isEmpty()) {
                if (color == null || color.isEmpty()) color = "#FFFFFF";
                String value = text.replace("\"", "\\\"") + "|" + color;
                String sJson = "{\"type\":\"text\",\"value\":\"" + value + "\",\"x\":0.5,\"y\":0.5}";
                stickerJsonList.add(sJson);
                addLiveOverlayView(sJson);
                if (btnCameraText != null) btnCameraText.setColorFilter(Color.argb(220, 255, 255, 255));
            }
        }

        if (requestCode == 304 && resultCode == RESULT_OK && data != null) {
            // MusicPickerActivity returned a selected track
            String id    = data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_ID);
            String title = data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_NAME);
            String url   = data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_URL);
            if (id    != null && !id.isEmpty())    preSelectedSoundId    = id;
            if (title != null && !title.isEmpty()) preSelectedSoundTitle = title;
            if (url   != null && !url.isEmpty())   preSelectedSoundUrl   = url;

            // Update music button label to show selected sound
            if (btnCameraMusic != null) {
                btnCameraMusic.setContentDescription(
                    preSelectedSoundTitle.isEmpty() ? "Add music" : preSelectedSoundTitle);
            }
            Toast.makeText(this,
                "Sound selected: " + preSelectedSoundTitle, Toast.LENGTH_SHORT).show();

            // Start background preview so user can hear the beat
            startSoundPreview();
        }
    }

    /**
     * Play selected sound in background so creator can hear it while recording.
     * Instagram does the same — earphone feedback during recording.
     * Actual mixing happens AFTER recording in AudioMixHelper.
     */
    private void startSoundPreview() {
        stopSoundPreview();
        if (preSelectedSoundUrl == null || preSelectedSoundUrl.isEmpty()) return;
        try {
            soundPreviewPlayer = new MediaPlayer();

            // ✅ FIX Gap 4: route audio to the media/speaker stream, not the
            // earpiece. Without this, on some devices/Android versions the audio
            // defaults to STREAM_VOICE_CALL (earpiece) during an active recording
            // session, so users can't hear the preview on speaker.
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
                Toast.makeText(this, "Camera and microphone permissions are required",
                    Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordTimer != null) recordTimer.cancel();
        if (activeRecording != null) activeRecording.stop();
        stopSoundPreview();
        cameraExecutor.shutdown();
    }
}
