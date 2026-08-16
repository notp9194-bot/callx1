package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.media.VideoOverlayBaker;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaEditActivity — Full-screen comprehensive chat media editor.
 *
 * Supports images AND videos with a full production-level editing toolchain:
 *
 * Images:
 *  ✅ Rotate (90° incremental)
 *  ✅ Flip/mirror — horizontal (tap) and vertical (long-press), independent of rotation
 *  ✅ Crop (dedicated {@link ChatImageCropActivity} with aspect-ratio presets + drag handles)
 *  ✅ Filters — swipe-up carousel (None/Pop/B&W/Cool/Chrome/Film/Warm/Vivid/Fade)
 *  ✅ Sticker picker — full emoji/text/GIF/trending via {@link ChatStickerPickerActivity}
 *  ✅ Text overlay — full font/color/size/bold/italic/align via sticker picker text tab
 *  ✅ Freehand draw — color picker + brush SIZE slider + undo
 *  ✅ Download (save to gallery)
 *  ✅ HD toggle
 *
 * Videos:
 *  ✅ Trim — dedicated {@link ChatVideoTrimActivity} with dual-handle trim + frame strip
 *  ✅ Filters — same filter carousel (applied as color LUT on thumbnail preview)
 *  ✅ Play/pause inline preview
 *  ✅ HD toggle
 *  ✅ Stickers/Text/Draw are baked directly into the video's pixels on
 *     send via {@link VideoOverlayBaker} (Media3 Transformer re-encode) —
 *     not just shown in the editor preview.
 *
 * Multi-item:
 *  ✅ Thumbnail strip at bottom — tap to switch items
 *  ✅ Per-item independent edit state (rotation/filter/overlays/strokes)
 *  ✅ Delete individual items from the strip
 *  ✅ Caption shared across all items
 *
 * Result:
 *  On "Send" each edited image is baked (rotation + filter + stickers + drawing)
 *  into a JPEG in app cache, re-exposed via FileProvider for the upload pipeline.
 *  Video trim result URIs replace original URIs in EditState before baking.
 */
public class MediaEditActivity extends AppCompatActivity {

    // ── Intent contract ──────────────────────────────────────────────────
    public static final String EXTRA_URIS     = "media_edit_uris";
    public static final String EXTRA_IS_VIDEO = "media_edit_is_video";
    public static final String EXTRA_CAPTION  = "media_edit_caption";
    public static final String EXTRA_HD       = "media_edit_hd";

    /** Aliases used by CommunityPostComposerActivity */
    public static final String RESULT_URIS    = "media_edit_result_uris";
    public static final String RESULT_URI              = RESULT_URIS;
    public static final String RESULT_MEDIA_TYPE       = "media_edit_result_type";
    public static final String EXTRA_MEDIA_URI         = EXTRA_URIS;
    public static final String EXTRA_MEDIA_TYPE        = "media_edit_type";
    public static final String EXTRA_MEDIA_TYPE_COMPAT = "media_edit_type";
    public static final String RESULT_CAPTION = "media_edit_result_caption";
    public static final String RESULT_HD      = "media_edit_result_hd";
    /** Feature: Voice Caption on Photo — local file URI of the recorded
     *  voice clip (see attachVoiceCaptureUi), or absent for a plain send. */
    public static final String RESULT_VOICE_URI      = "media_edit_result_voice_uri";
    public static final String RESULT_VOICE_DURATION = "media_edit_result_voice_duration_ms";

    // ── Text colors ──────────────────────────────────────────────────────
    static final int[] TEXT_COLORS = {
        Color.WHITE, Color.BLACK,
        0xFFFF5252, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50,
        0xFF2196F3, 0xFF9C27B0, 0xFFFF4081, 0xFF00BCD4,
    };

    // ── Overlay item (sticker or text placed on photo) ────────────────────
    static final class OverlayItem {
        String  text;
        boolean isEmoji;
        int     color      = Color.WHITE;
        float   xFrac      = 0.5f, yFrac = 0.45f;
        float   scale      = 1f;
        float   rotationDeg= 0f;
        float   textSizeSp = 30f;
        String  fontFamily = "default";
        boolean isBold     = false;
        boolean isItalic   = false;
        boolean hasBg      = false;
        /** "left" | "center" | "right" — how the (possibly multi-line) text is justified. */
        String  textAlign  = "center";
    }

    // ── Per-item edit state ───────────────────────────────────────────────
    private static final class EditState {
        Uri    uri;
        boolean isVideo;
        boolean deleted    = false;
        int    rotationDeg = 0;
        boolean flipHorizontal = false;
        boolean flipVertical   = false;
        int    filterIndex = 0;
        // Manual fine-tune sliders — -100..100, 0 = no change. Layered on
        // top of filterIndex's preset matrix via MediaFilters.combinedMatrix().
        float  adjBrightness = 0f;
        float  adjContrast   = 0f;
        float  adjSaturation = 0f;
        float  adjExposure   = 0f;
        Uri    trimmedUri  = null; // set after video trim
        final List<OverlayItem>         overlays = new ArrayList<>();
        final List<DrawOverlayView.Stroke> strokes  = new ArrayList<>();

        boolean hasEdits() {
            return rotationDeg != 0 || flipHorizontal || flipVertical || filterIndex != 0
                || !overlays.isEmpty() || !strokes.isEmpty()
                || trimmedUri != null
                || MediaFilters.hasAdjustments(adjBrightness, adjContrast, adjSaturation, adjExposure);
        }

        ColorMatrix colorMatrix() {
            return MediaFilters.combinedMatrix(filterIndex, adjBrightness, adjContrast, adjSaturation, adjExposure);
        }
        Uri effectiveUri() {
            return (trimmedUri != null) ? trimmedUri : uri;
        }
    }

    // ── Collections ──────────────────────────────────────────────────────
    private final List<EditState> items        = new ArrayList<>();
    private int                   currentIndex = 0;
    private boolean               isHD         = false;
    private boolean               drawModeActive = false;
    private boolean               swipeHintBounced = false;

    // ── Views ─────────────────────────────────────────────────────────────
    private ImageView    ivPreview;
    private ImageView    ivVideoPlayBadge;
    private FrameLayout  stickerLayer;
    private DrawOverlayView drawOverlay;
    private ImageButton  btnEditRotate, btnEditFlip, btnEditSticker, btnEditText,
                         btnEditDrawBottom, btnEditDownload, btnEditCrop, btnEditAdjust;
    private TextView     btnEditHd, btnEditTrim, btnEditLayers;
    private View         adjustPanel;
    private boolean      adjustPanelOpen = false;
    private SeekBar      sbAdjustBrightness, sbAdjustContrast, sbAdjustSaturation, sbAdjustExposure;
    private TextView     tvAdjustBrightness, tvAdjustContrast, tvAdjustSaturation, tvAdjustExposure;
    private HorizontalScrollView emojiRowScroll;
    private LinearLayout emojiRowContent, thumbStripContent, filterStripContent, drawToolRow;
    private final List<ImageView> filterCheckViews = new ArrayList<>();
    private boolean filterPanelOpen = false;
    private View         emojiRow, drawToolsRow, filterPanel, bottomBar, tvSwipeHint;
    private EditText     etCaption;
    private View         brushSizeRow;
    private RainbowColorDotView   btnDrawColorPicker;
    private TeardropWidthSlider   drawWidthSlider;
    private ImageButton  btnBrushPen, btnBrushHighlighter, btnBrushInk,
                          btnBrushCrayon, btnBrushNeon, btnBrushMarker, btnBrushMore;
    private ImageButton  btnShapeFreehand, btnShapeLine, btnShapeArrow, btnShapeRect, btnShapeCircle;

    // ── Background thread for baking ──────────────────────────────────────
    private final ExecutorService bgExec = Executors.newSingleThreadExecutor();

    // ── Activity launchers ────────────────────────────────────────────────
    private ActivityResultLauncher<Intent> cropLauncher;
    private ActivityResultLauncher<Intent> trimLauncher;
    private ActivityResultLauncher<Intent> stickerLauncher;
    /** Non-null while re-editing an existing text overlay via the picker, so
     *  the result is applied in place instead of adding a new overlay. */
    private OverlayItem editingOverlay;

    // ── Feature: Voice Caption on Photo ─────────────────────────────────
    // Hold-to-record mic button, same VoiceRecorder used by the standalone
    // chat voice-message gesture (see ChatMediaController#finishAndSend),
    // just driven from this preview screen instead of the chat input bar.
    // Only offered for a single-item send — see attachVoiceCaptureUi().
    private final com.callx.app.utils.VoiceRecorder voiceCaptureRecorder =
            new com.callx.app.utils.VoiceRecorder();
    private ImageButton btnEditMic;
    private View        llVoiceCaptureAttached;
    private TextView    tvVoiceCaptureDuration;
    private boolean     isRecordingVoiceCaption = false;
    /** Local file URI of the finished recording, or null if none/removed. */
    private Uri  recordedVoiceUri;
    private long recordedVoiceDurationMs;
    private ActivityResultLauncher<String> micPermissionLauncher;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_edit);

        ArrayList<String>  uriStrings = getIntent().getStringArrayListExtra(EXTRA_URIS);
        ArrayList<Integer> videoFlags = getIntent().getIntegerArrayListExtra(EXTRA_IS_VIDEO);
        if (uriStrings == null || uriStrings.isEmpty()) { finish(); return; }

        for (int i = 0; i < uriStrings.size(); i++) {
            EditState st = new EditState();
            st.uri     = Uri.parse(uriStrings.get(i));
            st.isVideo = videoFlags != null && i < videoFlags.size() && videoFlags.get(i) == 1;
            items.add(st);
        }
        isHD = getIntent().getBooleanExtra(EXTRA_HD, false);

        registerLaunchers();
        bindViews();
        setupTopToolbar();
        setupEmojiRow();
        setupDrawTools();
        setupFilterPanel();
        setupAdjustPanel();
        setupBottomBar();
        setupFilterSwipeGesture();
        attachVoiceCaptureUi();

        etCaption.setText(getIntent().getStringExtra(EXTRA_CAPTION));
        rebuildThumbStrip();
        showCurrentItem();
    }

    // ── Launcher registration ─────────────────────────────────────────────

    private void registerLaunchers() {
        // Crop result
        cropLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String uriStr = result.getData().getStringExtra(ChatImageCropActivity.RESULT_CROPPED_URI);
                if (uriStr != null) {
                    EditState st = current();
                    st.uri     = Uri.parse(uriStr);
                    st.overlays.clear();
                    st.strokes.clear();
                    stickerLayer.removeAllViews();
                    drawOverlay.clearStrokes();
                    rebuildThumbStrip();
                    showCurrentItem();
                }
            }
        });

        // Video trim result
        trimLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String uriStr = result.getData().getStringExtra(ChatVideoTrimActivity.RESULT_TRIMMED_URI);
                if (uriStr != null) {
                    current().trimmedUri = Uri.parse(uriStr);
                    Toast.makeText(this, "Video trimmed ✓", Toast.LENGTH_SHORT).show();
                    rebuildThumbStrip();
                    showCurrentItem();
                }
            }
        });

        // Sticker / text result
        stickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                String type  = data.getStringExtra(ChatStickerPickerActivity.RESULT_TYPE);
                String value = data.getStringExtra(ChatStickerPickerActivity.RESULT_VALUE);
                if (value == null || value.isEmpty()) { editingOverlay = null; return; }

                // Editing an existing text overlay: update it in place so its
                // position/size/rotation on the canvas are preserved instead
                // of resetting to a brand new overlay's defaults.
                OverlayItem overlay = (editingOverlay != null) ? editingOverlay : new OverlayItem();
                boolean isEditInPlace = (editingOverlay != null);
                editingOverlay = null;

                overlay.text    = value;
                overlay.isEmoji = "emoji".equals(type);
                overlay.color   = data.getIntExtra(ChatStickerPickerActivity.RESULT_COLOR, Color.WHITE);
                if (!overlay.isEmoji) {
                    overlay.fontFamily = data.getStringExtra(ChatStickerPickerActivity.RESULT_FONT);
                    if (overlay.fontFamily == null) overlay.fontFamily = "default";
                    overlay.textSizeSp = data.getFloatExtra(ChatStickerPickerActivity.RESULT_SIZE, 30f);
                    overlay.isBold    = data.getBooleanExtra(ChatStickerPickerActivity.RESULT_BOLD, false);
                    overlay.isItalic  = data.getBooleanExtra(ChatStickerPickerActivity.RESULT_ITALIC, false);
                    overlay.hasBg     = data.getBooleanExtra(ChatStickerPickerActivity.RESULT_HAS_BG, false);
                    String align = data.getStringExtra(ChatStickerPickerActivity.RESULT_ALIGN);
                    overlay.textAlign = (align != null) ? align : "center";
                }

                if (isEditInPlace) {
                    View oldView = findOverlayView(overlay);
                    if (oldView != null) stickerLayer.removeView(oldView);
                } else {
                    current().overlays.add(overlay);
                }
                selectedOverlay = overlay;
                renderOverlayView(overlay);
            } else {
                editingOverlay = null;
            }
        });

        // Feature: Voice Caption on Photo — RECORD_AUDIO permission
        micPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                startVoiceCaptureRecording();
            } else {
                Toast.makeText(this, "Mic permission needed to record a voice caption",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── View binding ─────────────────────────────────────────────────────

    private void bindViews() {
        ivPreview        = findViewById(R.id.ivPreview);
        ivVideoPlayBadge = findViewById(R.id.ivVideoPlayBadge);
        stickerLayer     = findViewById(R.id.stickerLayer);
        drawOverlay      = findViewById(R.id.drawOverlay);
        btnEditRotate    = findViewById(R.id.btnEditRotate);
        btnEditFlip      = findViewById(R.id.btnEditFlip);
        btnEditSticker   = findViewById(R.id.btnEditSticker);
        btnEditText      = findViewById(R.id.btnEditText);
        btnEditDrawBottom = findViewById(R.id.btnEditDrawBottom);
        btnEditDownload  = findViewById(R.id.btnEditDownload);
        btnEditCrop      = findViewById(R.id.btnEditCrop);
        btnEditAdjust    = findViewById(R.id.btnEditAdjust);
        btnEditHd        = findViewById(R.id.btnEditHd);
        btnEditTrim      = findViewById(R.id.btnEditTrim);
        btnEditLayers    = findViewById(R.id.btnEditLayers);
        emojiRow         = findViewById(R.id.emojiRow);
        emojiRowScroll   = (HorizontalScrollView) emojiRow;
        emojiRowContent  = findViewById(R.id.emojiRowContent);
        drawToolsRow     = findViewById(R.id.drawToolsRow);
        drawToolRow      = findViewById(R.id.drawToolRow);
        brushSizeRow     = findViewById(R.id.brushSizeRow);
        btnDrawColorPicker  = findViewById(R.id.btnDrawColorPicker);
        drawWidthSlider     = findViewById(R.id.drawWidthSlider);
        btnBrushPen         = findViewById(R.id.btnBrushPen);
        btnBrushHighlighter = findViewById(R.id.btnBrushHighlighter);
        btnBrushInk         = findViewById(R.id.btnBrushInk);
        btnBrushCrayon      = findViewById(R.id.btnBrushCrayon);
        btnBrushNeon        = findViewById(R.id.btnBrushNeon);
        btnBrushMarker      = findViewById(R.id.btnBrushMarker);
        btnBrushMore        = findViewById(R.id.btnBrushMore);
        btnShapeFreehand    = findViewById(R.id.btnShapeFreehand);
        btnShapeLine        = findViewById(R.id.btnShapeLine);
        btnShapeArrow       = findViewById(R.id.btnShapeArrow);
        btnShapeRect        = findViewById(R.id.btnShapeRect);
        btnShapeCircle      = findViewById(R.id.btnShapeCircle);
        filterPanel      = findViewById(R.id.filterPanel);
        filterStripContent = findViewById(R.id.filterStripContent);
        adjustPanel      = findViewById(R.id.adjustPanel);
        sbAdjustBrightness = findViewById(R.id.sbAdjustBrightness);
        sbAdjustContrast   = findViewById(R.id.sbAdjustContrast);
        sbAdjustSaturation = findViewById(R.id.sbAdjustSaturation);
        sbAdjustExposure   = findViewById(R.id.sbAdjustExposure);
        tvAdjustBrightness = findViewById(R.id.tvAdjustBrightness);
        tvAdjustContrast   = findViewById(R.id.tvAdjustContrast);
        tvAdjustSaturation = findViewById(R.id.tvAdjustSaturation);
        tvAdjustExposure   = findViewById(R.id.tvAdjustExposure);
        bottomBar        = findViewById(R.id.bottomBar);
        thumbStripContent= findViewById(R.id.thumbStripContent);
        etCaption        = findViewById(R.id.etCaption);
        tvSwipeHint      = findViewById(R.id.tvSwipeHint);
    }

    // ── Top toolbar ───────────────────────────────────────────────────────

    private void setupTopToolbar() {
        findViewById(R.id.btnEditClose).setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        if (btnEditDownload != null) btnEditDownload.setOnClickListener(v -> downloadCurrent());

        refreshHdButton();
        if (btnEditHd != null) btnEditHd.setOnClickListener(v -> {
            isHD = !isHD;
            refreshHdButton();
        });

        if (btnEditRotate != null) btnEditRotate.setOnClickListener(v -> {
            EditState st = current();
            if (st.isVideo) return;
            st.rotationDeg = (st.rotationDeg + 90) % 360;
            applyRotationToPreview();
            rebuildThumbStrip();
        });

        // Flip/mirror (image only) — tap flips horizontal, long-press flips vertical.
        if (btnEditFlip != null) {
            btnEditFlip.setOnClickListener(v -> {
                EditState st = current();
                if (st.isVideo) return;
                st.flipHorizontal = !st.flipHorizontal;
                applyFlipToPreview();
                rebuildThumbStrip();
            });
            btnEditFlip.setOnLongClickListener(v -> {
                EditState st = current();
                if (st.isVideo) return true;
                st.flipVertical = !st.flipVertical;
                applyFlipToPreview();
                rebuildThumbStrip();
                Toast.makeText(this, st.flipVertical ? "Flipped vertically" : "Vertical flip removed",
                        Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        // Crop — images only, launches ChatImageCropActivity
        if (btnEditCrop != null) btnEditCrop.setOnClickListener(v -> {
            EditState st = current();
            if (st.isVideo) {
                Toast.makeText(this, "Use Trim for videos", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, ChatImageCropActivity.class);
            i.putExtra(ChatImageCropActivity.EXTRA_IMAGE_URI, st.effectiveUri().toString());
            cropLauncher.launch(i);
        });

        // Adjust — manual brightness/contrast/saturation/exposure (image only)
        if (btnEditAdjust != null) btnEditAdjust.setOnClickListener(v -> {
            if (current().isVideo) {
                Toast.makeText(this, "Adjustments aren't available on video", Toast.LENGTH_SHORT).show();
                return;
            }
            if (adjustPanelOpen) closeAdjustPanel(); else openAdjustPanel();
        });

        // Video Trim — videos only, launches ChatVideoTrimActivity
        if (btnEditTrim != null) btnEditTrim.setOnClickListener(v -> {
            EditState st = current();
            if (!st.isVideo) {
                Toast.makeText(this, "Use Crop for images", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, ChatVideoTrimActivity.class);
            i.putExtra(ChatVideoTrimActivity.EXTRA_VIDEO_URI, st.effectiveUri().toString());
            trimLauncher.launch(i);
        });

        // Sticker — launches ChatStickerPickerActivity in emoji mode
        if (btnEditSticker != null) btnEditSticker.setOnClickListener(v -> {
            if (current().isVideo) {
                // Stickers on video go via sticker picker still
            }
            hideAllToolRows();
            editingOverlay = null;
            Intent i = new Intent(this, ChatStickerPickerActivity.class);
            i.putExtra(ChatStickerPickerActivity.EXTRA_TEXT_MODE, false);
            stickerLauncher.launch(i);
        });

        // Text — launches ChatStickerPickerActivity in text mode
        if (btnEditText != null) btnEditText.setOnClickListener(v -> {
            hideAllToolRows();
            editingOverlay = null;
            Intent i = new Intent(this, ChatStickerPickerActivity.class);
            i.putExtra(ChatStickerPickerActivity.EXTRA_TEXT_MODE, true);
            stickerLauncher.launch(i);
        });

        // Draw — button lives next to Send in the caption row.
        if (btnEditDrawBottom != null) btnEditDrawBottom.setOnClickListener(v -> toggleDrawMode());

        // Layer ordering applies to the currently selected sticker/text.
        // Selection happens by touching an overlay; long-press remains the
        // dedicated delete gesture.
        if (btnEditLayers != null) {
            btnEditLayers.setOnClickListener(v -> showOverlayLayerDialog());
        }
    }

    private void refreshHdButton() {
        if (btnEditHd == null) return;
        btnEditHd.setText("HD");
        btnEditHd.setAlpha(1f);
        btnEditHd.setBackgroundResource(isHD
                ? R.drawable.bg_hd_toggle_active
                : R.drawable.bg_hd_toggle_inactive);
    }

    private void applyRotationToPreview() {
        if (ivPreview == null) return;
        ivPreview.animate().rotation(ivPreview.getRotation() + 90)
                .setDuration(220).setInterpolator(new OvershootInterpolator(1.5f)).start();
        drawOverlay.setRotation(ivPreview.getRotation() + 90);
        stickerLayer.setRotation(ivPreview.getRotation() + 90);
        applyMediaViewportTransform();
    }

    /** Live-animates horizontal/vertical flip on the preview + overlay layers to match {@link EditState}. */
    private void applyFlipToPreview() {
        if (ivPreview == null) return;
        applyMediaViewportTransform(true);
    }

    /**
     * Applies the shared preview viewport transform. ImageView scaleType
     * remains fitCenter; scaling the three sibling layers together gives the
     * editor a real photo zoom without changing the normalized coordinates
     * used by the final bitmap/video bake.
     */
    private void applyMediaViewportTransform() {
        applyMediaViewportTransform(false);
    }

    private void applyMediaViewportTransform(boolean animate) {
        if (ivPreview == null || stickerLayer == null || drawOverlay == null) return;
        EditState st = current();
        float sx = mediaZoomScale * (st.flipHorizontal ? -1f : 1f);
        float sy = mediaZoomScale * (st.flipVertical   ? -1f : 1f);
        float duration = animate ? 220f : 0f;

        if (animate) {
            ivPreview.animate().scaleX(sx).scaleY(sy)
                    .setDuration((long) duration)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
            stickerLayer.animate().scaleX(sx).scaleY(sy)
                    .setDuration((long) duration).start();
            drawOverlay.animate().scaleX(sx).scaleY(sy)
                    .setDuration((long) duration).start();
        } else {
            ivPreview.setScaleX(sx);
            ivPreview.setScaleY(sy);
            stickerLayer.setScaleX(sx);
            stickerLayer.setScaleY(sy);
            drawOverlay.setScaleX(sx);
            drawOverlay.setScaleY(sy);
        }
        ivPreview.setTranslationX(mediaPanX);
        ivPreview.setTranslationY(mediaPanY);
        stickerLayer.setTranslationX(mediaPanX);
        stickerLayer.setTranslationY(mediaPanY);
        drawOverlay.setTranslationX(mediaPanX);
        drawOverlay.setTranslationY(mediaPanY);
    }

    private void resetMediaViewport() {
        mediaZoomScale = 1f;
        mediaPanX = 0f;
        mediaPanY = 0f;
        mediaZoomGestureActive = false;
        mediaTouchMoved = false;
        applyMediaViewportTransform();
    }

    private void setMediaZoom(float scale) {
        mediaZoomScale = Math.max(1f, Math.min(4f, scale));
        clampMediaPan();
        applyMediaViewportTransform();
    }

    private void clampMediaPan() {
        if (ivPreview == null) return;
        float maxX = Math.max(0f, (mediaZoomScale - 1f) * ivPreview.getWidth() / 2f);
        float maxY = Math.max(0f, (mediaZoomScale - 1f) * ivPreview.getHeight() / 2f);
        mediaPanX = Math.max(-maxX, Math.min(maxX, mediaPanX));
        mediaPanY = Math.max(-maxY, Math.min(maxY, mediaPanY));
    }

    // ── Tool row visibility ───────────────────────────────────────────────

    private void hideAllToolRows() {
        if (emojiRow     != null) emojiRow.setVisibility(View.GONE);
        if (drawToolsRow != null) drawToolsRow.setVisibility(View.GONE);
        if (bottomBar    != null) bottomBar.setVisibility(View.VISIBLE);
        if (tvSwipeHint  != null && !current().isVideo) tvSwipeHint.setVisibility(View.VISIBLE);
        drawModeActive = false;
        drawOverlay.setDrawingEnabled(false);
    }

    // ── Emoji row (legacy sticker quick-row — kept for compat) ───────────

    private void setupEmojiRow() {
        // The emoji row in activity_media_edit.xml is now used as a
        // "recently used" / quick-launch bar. The full picker is in
        // ChatStickerPickerActivity. We leave the row GONE by default.
        if (emojiRow != null) emojiRow.setVisibility(View.GONE);
    }

    // ── Draw tools ────────────────────────────────────────────────────────

    /** Currently selected draw color (default red); synced with eraser state. */
    private int     activeDrawColor = Color.RED;
    private boolean eraserActive    = false;
    private int     activeBrushType = DrawOverlayView.BRUSH_PEN;
    private int     activeShapeType = DrawOverlayView.SHAPE_FREEHAND;
    private ImageButton[] brushButtons;
    private ImageButton[] shapeButtons;

    // ── Preview viewport (pinch zoom + pan) ───────────────────────────────
    // The same transform is applied to the photo, sticker layer and drawing
    // layer, so overlays stay attached to the image while the user explores
    // a zoomed-in area. Overlay/stroke state remains normalized for export.
    private float mediaZoomScale = 1f;
    private float mediaPanX = 0f;
    private float mediaPanY = 0f;
    private boolean mediaZoomGestureActive = false;
    private float lastMediaTouchX;
    private float lastMediaTouchY;
    private boolean mediaTouchMoved = false;

    // ── Overlay selection / ordering ─────────────────────────────────────
    private OverlayItem selectedOverlay;
    private View selectedOverlayView;
    private AlertDialog overlayLayerDialog;

    private void setupDrawTools() {
        brushButtons = new ImageButton[] {
            btnBrushPen, btnBrushHighlighter, btnBrushInk,
            btnBrushCrayon, btnBrushNeon, btnBrushMarker
        };

        drawOverlay.setOnStrokeChangeListener(this::refreshUndoRedoState);

        // ── Color circle (screenshot 1) — opens the shared "core" rainbow
        //    color sheet instead of a fixed swatch row ──
        activeDrawColor = Color.WHITE;
        drawOverlay.setActiveColor(activeDrawColor);
        if (btnDrawColorPicker != null) {
            btnDrawColorPicker.setCurrentColor(activeDrawColor);
            btnDrawColorPicker.setOnClickListener(v -> {
                String currentHex = String.format("#%06X", (0xFFFFFF & activeDrawColor));
                com.callx.app.utils.RainbowStripColorPickerBottomSheet.show(
                        this, "Draw color", currentHex, false,
                        hex -> {
                            if (hex == null) return;
                            try {
                                selectDrawColor(Color.parseColor(hex));
                            } catch (Exception ignored) { /* keep previous color on parse failure */ }
                        });
            });
        }

        // ── Brush type row (screenshot 1) ──
        int[] brushTypes = {
            DrawOverlayView.BRUSH_PEN, DrawOverlayView.BRUSH_HIGHLIGHTER, DrawOverlayView.BRUSH_INK,
            DrawOverlayView.BRUSH_CRAYON, DrawOverlayView.BRUSH_NEON, DrawOverlayView.BRUSH_MARKER
        };
        for (int i = 0; i < brushButtons.length; i++) {
            final int brushType = brushTypes[i];
            ImageButton btn = brushButtons[i];
            if (btn != null) btn.setOnClickListener(v -> selectBrushType(brushType));
        }
        selectBrushType(DrawOverlayView.BRUSH_PEN);

        // More brushes — opens a real chooser instead of the old
        // "coming soon" placeholder. The selected brush is still a normal
        // DrawOverlayView brush, so it is undoable and included in exports.
        if (btnBrushMore != null) {
            btnBrushMore.setContentDescription("More brushes");
            btnBrushMore.setOnClickListener(v -> showMoreBrushesMenu());
        }

        // ── Shape tool row — Freehand + Line / Arrow / Rectangle / Circle ──
        shapeButtons = new ImageButton[] {
            btnShapeFreehand, btnShapeLine, btnShapeArrow, btnShapeRect, btnShapeCircle
        };
        int[] shapeTypes = {
            DrawOverlayView.SHAPE_FREEHAND, DrawOverlayView.SHAPE_LINE, DrawOverlayView.SHAPE_ARROW,
            DrawOverlayView.SHAPE_RECT, DrawOverlayView.SHAPE_OVAL
        };
        for (int i = 0; i < shapeButtons.length; i++) {
            final int shapeType = shapeTypes[i];
            ImageButton btn = shapeButtons[i];
            if (btn != null) btn.setOnClickListener(v -> selectShapeType(shapeType));
        }
        selectShapeType(DrawOverlayView.SHAPE_FREEHAND);

        // ── Brush width — teardrop slider overlaid on the canvas edge (screenshot 2) ──
        if (drawWidthSlider != null) {
            drawWidthSlider.setAccentColor(activeDrawColor);
            drawWidthSlider.setWidthDp(8f);
            drawOverlay.setActiveWidthDp(8f);
            drawWidthSlider.setOnWidthChangeListener(widthDp -> drawOverlay.setActiveWidthDp(widthDp));
        }

        // ── Eraser toggle ──
        View btnEraser = findViewById(R.id.btnDrawEraser);
        if (btnEraser != null) btnEraser.setOnClickListener(v -> {
            eraserActive = !eraserActive;
            if (eraserActive) {
                drawOverlay.setEraserMode(true);
                btnEraser.setAlpha(1f);
                btnEraser.setBackgroundColor(0x4440C060);
            } else {
                drawOverlay.setEraserMode(false);
                drawOverlay.setActiveColor(activeDrawColor);
                btnEraser.setAlpha(0.7f);
                btnEraser.setBackground(getDrawable(R.drawable.bg_media_edit_toolbtn));
            }
        });

        // ── Undo / Redo ──
        View btnUndo = findViewById(R.id.btnDrawUndo);
        if (btnUndo != null) btnUndo.setOnClickListener(v -> {
            drawOverlay.undoLastStroke();
            refreshUndoRedoState();
        });

        View btnRedo = findViewById(R.id.btnDrawRedo);
        if (btnRedo != null) btnRedo.setOnClickListener(v -> {
            drawOverlay.redoLastStroke();
            refreshUndoRedoState();
        });
        refreshUndoRedoState();

        // ── Clear all ──
        View btnClear = findViewById(R.id.btnDrawClear);
        if (btnClear != null) btnClear.setOnClickListener(v -> {
            com.callx.app.utils.AlertDialogStyler.showReusableConfirm(this,
                    "clear_drawing", com.callx.app.utils.AlertDialogStyler.DialogSize.DEFAULT,
                    null, "Clear all drawing?",
                    "Clear", () -> {
                        drawOverlay.clearStrokes();
                        current().strokes.clear();
                        refreshUndoRedoState();
                    },
                    null, null,
                    "Cancel");
        });

        // ── Done ──
        View btnDrawDone = findViewById(R.id.btnDrawDone);
        if (btnDrawDone != null) btnDrawDone.setOnClickListener(v -> exitDrawMode());
    }

    /** Applies a newly picked color from the color sheet (or eraser exit) to the overlay + UI. */
    private void selectDrawColor(int color) {
        activeDrawColor = color;
        eraserActive     = false;
        drawOverlay.setActiveColor(color);
        if (btnDrawColorPicker != null) btnDrawColorPicker.setCurrentColor(color);
        if (drawWidthSlider != null) drawWidthSlider.setAccentColor(color);
        // Reset eraser button style
        View btnEraser = findViewById(R.id.btnDrawEraser);
        if (btnEraser != null) {
            btnEraser.setAlpha(0.7f);
            btnEraser.setBackground(getDrawable(R.drawable.bg_media_edit_toolbtn));
        }
    }

    /** Selects a brush type — highlights its button, updates the overlay. */
    private void selectBrushType(int brushType) {
        activeBrushType = brushType;
        drawOverlay.setActiveBrushType(brushType);
        if (brushButtons != null) {
            for (ImageButton btn : brushButtons) {
                if (btn == null) continue;
                boolean isActive =
                        (btn == btnBrushPen         && brushType == DrawOverlayView.BRUSH_PEN) ||
                        (btn == btnBrushHighlighter && brushType == DrawOverlayView.BRUSH_HIGHLIGHTER) ||
                        (btn == btnBrushInk         && brushType == DrawOverlayView.BRUSH_INK) ||
                        (btn == btnBrushCrayon      && brushType == DrawOverlayView.BRUSH_CRAYON) ||
                        (btn == btnBrushNeon        && brushType == DrawOverlayView.BRUSH_NEON) ||
                        (btn == btnBrushMarker      && brushType == DrawOverlayView.BRUSH_MARKER);
                btn.setBackground(getDrawable(isActive
                        ? R.drawable.bg_media_edit_brush_active
                        : R.drawable.bg_media_edit_toolbtn));
            }
        }
        if (btnBrushMore != null) {
            btnBrushMore.setBackground(getDrawable(brushType == DrawOverlayView.BRUSH_BLUR
                    ? R.drawable.bg_media_edit_brush_active
                    : R.drawable.bg_media_edit_toolbtn));
        }
    }

    /** Selects a shape tool (or Freehand) — highlights its button, updates the overlay. */
    private void selectShapeType(int shapeType) {
        activeShapeType = shapeType;
        drawOverlay.setActiveShapeType(shapeType);
        if (shapeButtons != null) {
            for (ImageButton btn : shapeButtons) {
                if (btn == null) continue;
                boolean isActive =
                        (btn == btnShapeFreehand && shapeType == DrawOverlayView.SHAPE_FREEHAND) ||
                        (btn == btnShapeLine     && shapeType == DrawOverlayView.SHAPE_LINE) ||
                        (btn == btnShapeArrow    && shapeType == DrawOverlayView.SHAPE_ARROW) ||
                        (btn == btnShapeRect     && shapeType == DrawOverlayView.SHAPE_RECT) ||
                        (btn == btnShapeCircle   && shapeType == DrawOverlayView.SHAPE_OVAL);
                btn.setBackground(getDrawable(isActive
                        ? R.drawable.bg_media_edit_brush_active
                        : R.drawable.bg_media_edit_toolbtn));
            }
        }
    }

    /** Enables/dims the Undo and Redo buttons to reflect whether there's anything to undo/redo. */
    private void refreshUndoRedoState() {
        View btnUndo = findViewById(R.id.btnDrawUndo);
        View btnRedo = findViewById(R.id.btnDrawRedo);
        if (btnUndo != null) {
            boolean can = drawOverlay.canUndo();
            btnUndo.setAlpha(can ? 1f : 0.4f);
            btnUndo.setEnabled(can);
        }
        if (btnRedo != null) {
            boolean can = drawOverlay.canRedo();
            btnRedo.setAlpha(can ? 1f : 0.4f);
            btnRedo.setEnabled(can);
        }
    }

    private void toggleDrawMode() {
        if (drawModeActive) {
            exitDrawMode();
        } else {
            enterDrawMode();
        }
    }

    private void enterDrawMode() {
        drawModeActive = true;
        drawOverlay.setDrawingEnabled(true);
        if (filterPanel != null) closeFilterPanel();
        if (adjustPanel != null) closeAdjustPanel();
        if (emojiRow    != null) emojiRow.setVisibility(View.GONE);

        // Hide the caption/send bottom bar — draw panel replaces it
        if (bottomBar   != null) bottomBar.setVisibility(View.GONE);
        if (tvSwipeHint != null) tvSwipeHint.setVisibility(View.GONE);

        if (drawToolsRow != null) drawToolsRow.setVisibility(View.VISIBLE);
        if (drawWidthSlider != null) drawWidthSlider.setVisibility(View.VISIBLE);
        if (btnEditDrawBottom != null) btnEditDrawBottom.setAlpha(1f);
        refreshUndoRedoState();
    }

    /** Shows the additional brush choices behind the More button. */
    private void showMoreBrushesMenu() {
        final String[] names = {
                "Blur / Pixelate",
                "Glow marker",
                "Wet ink",
                "Crayon texture"
        };
        final int[] types = {
                DrawOverlayView.BRUSH_BLUR,
                DrawOverlayView.BRUSH_NEON,
                DrawOverlayView.BRUSH_INK,
                DrawOverlayView.BRUSH_CRAYON
        };
        new AlertDialog.Builder(this)
                .setTitle("More brushes")
                .setItems(names, (dialog, which) -> {
                    selectBrushType(types[which]);
                    if (types[which] == DrawOverlayView.BRUSH_BLUR) {
                        refreshBlurSource();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exitDrawMode() {
        drawModeActive = false;
        drawOverlay.setDrawingEnabled(false);
        eraserActive = false;

        if (drawToolsRow != null) drawToolsRow.setVisibility(View.GONE);
        if (drawWidthSlider != null) drawWidthSlider.setVisibility(View.GONE);

        // Restore the normal bottom bar
        if (bottomBar   != null) bottomBar.setVisibility(View.VISIBLE);
        if (tvSwipeHint != null && !current().isVideo) tvSwipeHint.setVisibility(View.VISIBLE);

        if (btnEditDrawBottom != null) btnEditDrawBottom.setAlpha(0.7f);
    }

    // ── Filter panel ──────────────────────────────────────────────────────

    private void setupFilterPanel() {
        if (filterStripContent == null) return;
        filterCheckViews.clear();
        filterStripContent.removeAllViews();
        for (int i = 0; i < MediaFilters.NAMES.length; i++) {
            final int index = i;
            View row = getLayoutInflater().inflate(R.layout.item_media_edit_filter, filterStripContent, false);
            ImageView thumb = row.findViewById(R.id.ivFilterThumb);
            ImageView check = row.findViewById(R.id.ivFilterCheck);
            TextView label  = row.findViewById(R.id.tvFilterName);
            if (label != null) label.setText(MediaFilters.NAMES[i]);
            filterCheckViews.add(check);
            row.setOnClickListener(v -> applyFilter(index));
            filterStripContent.addView(row);
        }
        View btnCollapse = findViewById(R.id.btnFilterCollapse);
        if (btnCollapse != null) btnCollapse.setOnClickListener(v -> closeFilterPanel());
    }

    /**
     * Loads the current item's image into every filter thumbnail via Glide,
     * then applies the appropriate ColorMatrix so each thumb shows a live
     * preview of what that filter will look like on the actual photo.
     * Must be called each time the filter panel is opened (item may have changed).
     */
    private void refreshFilterThumbs() {
        Uri uri = current().effectiveUri();
        for (int i = 0; i < filterCheckViews.size(); i++) {
            final int fi = i;
            View row = filterStripContent.getChildAt(i);
            if (row == null) continue;
            ImageView thumb = row.findViewById(R.id.ivFilterThumb);
            ImageView check = filterCheckViews.get(i);
            if (thumb != null) {
                // Load photo, then apply filter ColorMatrix as overlay
                Glide.with(this).load(uri).centerCrop()
                        .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                    Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) { return false; }
                            @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                    Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                    com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                thumb.setColorFilter(fi == 0 ? null : MediaFilters.filterFor(fi));
                                return false;
                            }
                        })
                        .into(thumb);
            }
            if (check != null) {
                check.setVisibility(i == current().filterIndex ? View.VISIBLE : View.GONE);
            }
        }
    }

    /**
     * Applies filter instantly to the preview ImageView (no background thread
     * needed — ColorMatrixColorFilter is applied directly). Mirrors WhatsApp's
     * instant tap-to-preview behaviour.
     */
    private void applyFilter(int index) {
        EditState st = current();
        if (st.isVideo) return;
        st.filterIndex = index;
        // Instant live preview on the main ImageView (combined with any active adjustments)
        boolean anyEffect = index != 0 || MediaFilters.hasAdjustments(
                st.adjBrightness, st.adjContrast, st.adjSaturation, st.adjExposure);
        ivPreview.setColorFilter(anyEffect ? new ColorMatrixColorFilter(st.colorMatrix()) : null);
        // Update checkmark visibility in the strip
        for (int i = 0; i < filterCheckViews.size(); i++) {
            ImageView check = filterCheckViews.get(i);
            if (check != null) check.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
    }

    private void openFilterPanel() {
        if (filterPanel == null || filterPanelOpen) return;
        if (current().isVideo) return; // no filters on video
        if (adjustPanelOpen) closeAdjustPanel();
        filterPanelOpen = true;
        refreshFilterThumbs();
        filterPanel.setVisibility(View.VISIBLE);
        if (bottomBar   != null) bottomBar.setVisibility(View.GONE);
        if (tvSwipeHint != null) tvSwipeHint.setVisibility(View.GONE);
        filterPanel.setTranslationY(filterPanel.getHeight() > 0 ? filterPanel.getHeight() : dp(220));
        filterPanel.animate().translationY(0).setDuration(220).start();
    }

    private void closeFilterPanel() {
        if (!filterPanelOpen) return;
        filterPanelOpen = false;
        float slideAmt = filterPanel.getHeight() > 0 ? filterPanel.getHeight() : dp(220);
        filterPanel.animate().translationY(slideAmt).setDuration(200)
                .withEndAction(() -> {
                    filterPanel.setVisibility(View.INVISIBLE);
                    if (bottomBar   != null && !adjustPanelOpen) bottomBar.setVisibility(View.VISIBLE);
                    if (!current().isVideo && tvSwipeHint != null && !adjustPanelOpen)
                        tvSwipeHint.setVisibility(View.VISIBLE);
                }).start();
    }

    // ── Adjust panel (Brightness / Contrast / Saturation / Exposure) ──────

    /**
     * Binds the 4 fine-tune sliders. Each SeekBar runs 0..200 with 100 as
     * the "no change" center point, so dragging left/right maps to a
     * -100..100 value that's stored on {@link EditState} and combined with
     * the active preset filter into one live ColorMatrix — mirrors exactly
     * what {@link #bakeBitmap} applies on send, via {@link EditState#colorMatrix()}.
     */
    private void setupAdjustPanel() {
        if (adjustPanel == null) return;

        View btnCollapse = findViewById(R.id.btnAdjustCollapse);
        if (btnCollapse != null) btnCollapse.setOnClickListener(v -> closeAdjustPanel());

        View btnReset = findViewById(R.id.btnAdjustReset);
        if (btnReset != null) btnReset.setOnClickListener(v -> {
            EditState st = current();
            st.adjBrightness = 0f;
            st.adjContrast   = 0f;
            st.adjSaturation = 0f;
            st.adjExposure   = 0f;
            refreshAdjustSliders();
            applyAdjustmentsToPreview();
        });

        bindAdjustSlider(sbAdjustBrightness, tvAdjustBrightness, (st, v) -> st.adjBrightness = v);
        bindAdjustSlider(sbAdjustContrast,   tvAdjustContrast,   (st, v) -> st.adjContrast   = v);
        bindAdjustSlider(sbAdjustSaturation, tvAdjustSaturation, (st, v) -> st.adjSaturation = v);
        bindAdjustSlider(sbAdjustExposure,   tvAdjustExposure,   (st, v) -> st.adjExposure   = v);
    }

    private interface AdjustSetter { void set(EditState st, float value); }

    private void bindAdjustSlider(SeekBar sb, TextView tv, AdjustSetter setter) {
        if (sb == null) return;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress - 100; // 0..200 → -100..100
                if (tv != null) tv.setText(String.valueOf((int) value));
                if (fromUser) {
                    setter.set(current(), value);
                    applyAdjustmentsToPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    /** Pushes current EditState's slider values into the SeekBars/labels without re-triggering listeners' side effects. */
    private void refreshAdjustSliders() {
        EditState st = current();
        if (sbAdjustBrightness != null) sbAdjustBrightness.setProgress((int) (st.adjBrightness + 100));
        if (sbAdjustContrast   != null) sbAdjustContrast.setProgress((int) (st.adjContrast + 100));
        if (sbAdjustSaturation != null) sbAdjustSaturation.setProgress((int) (st.adjSaturation + 100));
        if (sbAdjustExposure   != null) sbAdjustExposure.setProgress((int) (st.adjExposure + 100));
    }

    /** Re-applies the combined filter+adjustments ColorMatrix to the live preview. */
    private void applyAdjustmentsToPreview() {
        EditState st = current();
        if (st.isVideo || ivPreview == null) return;
        boolean anyEffect = st.filterIndex != 0 || MediaFilters.hasAdjustments(
                st.adjBrightness, st.adjContrast, st.adjSaturation, st.adjExposure);
        ivPreview.setColorFilter(anyEffect ? new ColorMatrixColorFilter(st.colorMatrix()) : null);
    }

    private void openAdjustPanel() {
        if (adjustPanel == null || adjustPanelOpen) return;
        if (current().isVideo) return; // no manual adjustments on video
        if (filterPanelOpen) closeFilterPanel();
        adjustPanelOpen = true;
        refreshAdjustSliders();
        adjustPanel.setVisibility(View.VISIBLE);
        if (bottomBar   != null) bottomBar.setVisibility(View.GONE);
        if (tvSwipeHint != null) tvSwipeHint.setVisibility(View.GONE);
        adjustPanel.setTranslationY(adjustPanel.getHeight() > 0 ? adjustPanel.getHeight() : dp(280));
        adjustPanel.animate().translationY(0).setDuration(220).start();
    }

    private void closeAdjustPanel() {
        if (!adjustPanelOpen) return;
        adjustPanelOpen = false;
        float slideAmt = adjustPanel.getHeight() > 0 ? adjustPanel.getHeight() : dp(280);
        adjustPanel.animate().translationY(slideAmt).setDuration(200)
                .withEndAction(() -> {
                    adjustPanel.setVisibility(View.INVISIBLE);
                    if (bottomBar   != null && !filterPanelOpen) bottomBar.setVisibility(View.VISIBLE);
                    if (!current().isVideo && tvSwipeHint != null && !filterPanelOpen)
                        tvSwipeHint.setVisibility(View.VISIBLE);
                }).start();
    }

    // ── Swipe-up gesture → open filter panel; swipe-down → close ─────────

    private void setupFilterSwipeGesture() {
        View mediaContainer = findViewById(R.id.mediaContainer);
        if (mediaContainer == null) return;
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                // Return true so the GestureDetector keeps routing MOVE/UP events.
                return true;
            }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null) return false;
                float deltaY = e2.getY() - e1.getY();
                // Swipe UP → open filter panel
                if (!filterPanelOpen && deltaY < -80 && velocityY < -400) {
                    openFilterPanel();
                    return true;
                }
                // Swipe DOWN → close filter panel
                if (filterPanelOpen && deltaY > 80 && velocityY > 400) {
                    closeFilterPanel();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // A quick double tap is a convenient reset/toggle in addition
                // to the explicit pinch gesture.
                if (mediaZoomScale > 1.01f) {
                    resetMediaViewport();
                } else {
                    setMediaZoom(2f);
                }
                return true;
            }
        });

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (drawModeActive) return false;
                mediaZoomGestureActive = true;
                mediaTouchMoved = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                setMediaZoom(mediaZoomScale * detector.getScaleFactor());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                mediaZoomGestureActive = false;
            }
        });
        mediaContainer.setOnTouchListener((v, event) -> {
            if (drawModeActive) return false; // let draw overlay handle it
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastMediaTouchX = event.getRawX();
                    lastMediaTouchY = event.getRawY();
                    mediaTouchMoved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1
                            && !mediaZoomGestureActive
                            && mediaZoomScale > 1.01f) {
                        float dx = event.getRawX() - lastMediaTouchX;
                        float dy = event.getRawY() - lastMediaTouchY;
                        mediaPanX += dx;
                        mediaPanY += dy;
                        clampMediaPan();
                        applyMediaViewportTransform();
                        lastMediaTouchX = event.getRawX();
                        lastMediaTouchY = event.getRawY();
                        mediaTouchMoved = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mediaZoomGestureActive = false;
                    break;
            }

            // Keep the existing filter swipe gesture only when the viewport
            // is at its normal scale. At zoom > 1, one-finger movement is pan.
            if (mediaZoomScale <= 1.01f && !mediaZoomGestureActive) {
                gd.onTouchEvent(event);
            }

            // Consuming the stream is required so pinch and pan receive every
            // pointer event, including ACTION_POINTER_* transitions.
            return true;
        });
    }

    // ── Bottom bar ────────────────────────────────────────────────────────

    private void setupBottomBar() {
        View btnDelete = findViewById(R.id.btnEditDelete);
        if (btnDelete != null) btnDelete.setOnClickListener(v -> deleteCurrentItem());

        View btnSend = findViewById(R.id.btnEditSend);
        if (btnSend != null) btnSend.setOnClickListener(v -> {
            // BUG FIX (double upload): bakeAndSend()/processItemForSend() bake
            // each photo/video on a background executor and only call
            // setResult()+finish() once every item is done. A fast double-tap
            // on Send (very easy to do since baking isn't instant) used to
            // kick off two full processItemForSend() chains in parallel, and
            // whichever one finished last called finish() with its own
            // result — with the caller (NewStatusActivity) in some flows
            // already having posted from the first chain, this uploaded the
            // same item(s) twice. Guard so only the first tap runs.
            if (sendInProgress) return;
            sendInProgress = true;
            btnSend.setEnabled(false);
            bakeAndSend();
        });
    }

    /** Set on the first Send tap to block a duplicate bake+upload from a fast double-tap. */
    private boolean sendInProgress = false;

    // ── Feature: Voice Caption on Photo ─────────────────────────────────
    //
    // Hold btnEditMic → record → release → attach. Deliberately simple
    // compared to the chat input bar's full press-hold gesture (no
    // slide-to-cancel/lock/waveform preview here) — this is a short
    // "walkie-talkie" caption on ONE photo, not a standalone voice message,
    // so a plain hold-then-release is the right amount of UI. Tap the
    // attached chip's ✕ to discard and record again.

    private static final long MIN_VOICE_CAPTURE_MS = 500;

    private void attachVoiceCaptureUi() {
        btnEditMic              = findViewById(R.id.btnEditMic);
        llVoiceCaptureAttached  = findViewById(R.id.llVoiceCaptureAttached);
        tvVoiceCaptureDuration  = findViewById(R.id.tvVoiceCaptureDuration);
        View btnRemove          = findViewById(R.id.btnVoiceCaptureRemove);

        if (btnEditMic == null) return;

        // A voice caption belongs to exactly one photo — hide the mic
        // entirely for a multi-item send rather than leave an ambiguous
        // affordance (see RESULT_VOICE_URI's doc for why).
        if (items.size() != 1 || items.get(0).isVideo) {
            btnEditMic.setVisibility(View.GONE);
            return;
        }

        btnEditMic.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onMicPressDown();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isRecordingVoiceCaption) onMicRelease();
                    return true;
                default:
                    return false;
            }
        });

        if (btnRemove != null) {
            btnRemove.setOnClickListener(v -> {
                recordedVoiceUri = null;
                recordedVoiceDurationMs = 0;
                if (llVoiceCaptureAttached != null) llVoiceCaptureAttached.setVisibility(View.GONE);
            });
        }
    }

    private void onMicPressDown() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
            return;
        }
        startVoiceCaptureRecording();
    }

    private void startVoiceCaptureRecording() {
        if (isRecordingVoiceCaption) return;
        if (!voiceCaptureRecorder.start(this)) {
            Toast.makeText(this, "Couldn't start recording", Toast.LENGTH_SHORT).show();
            return;
        }
        isRecordingVoiceCaption = true;
        if (btnEditMic != null) {
            btnEditMic.setImageResource(R.drawable.ic_mic);
            btnEditMic.setAlpha(1f);
            btnEditMic.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
        Toast.makeText(this, "Recording… release to attach", Toast.LENGTH_SHORT).show();
    }

    private void onMicRelease() {
        isRecordingVoiceCaption = false;
        long durationMs = voiceCaptureRecorder.getDuration();
        if (durationMs < MIN_VOICE_CAPTURE_MS) {
            voiceCaptureRecorder.cancel();
            Toast.makeText(this, "Hold the mic to record a voice caption", Toast.LENGTH_SHORT).show();
            return;
        }
        File finalFile = voiceCaptureRecorder.stopToFile(this);
        if (finalFile == null) {
            Toast.makeText(this, "Recording was empty", Toast.LENGTH_SHORT).show();
            return;
        }
        recordedVoiceUri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", finalFile);
        recordedVoiceDurationMs = durationMs;

        if (llVoiceCaptureAttached != null && tvVoiceCaptureDuration != null) {
            tvVoiceCaptureDuration.setText("\uD83C\uDFA4 Voice caption \u00b7 " + formatVoiceCaptureDuration(durationMs));
            llVoiceCaptureAttached.setVisibility(View.VISIBLE);
        }
    }

    private String formatVoiceCaptureDuration(long ms) {
        long totalSec = ms / 1000;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60);
    }

    // ── Thumbnail strip ───────────────────────────────────────────────────

    private void rebuildThumbStrip() {
        if (thumbStripContent == null) return;
        thumbStripContent.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            EditState st = items.get(i);
            if (st.deleted) continue;
            final int idx = i;

            View thumb = getLayoutInflater().inflate(R.layout.item_media_edit_thumb,
                    thumbStripContent, false);
            ImageView iv = thumb.findViewById(R.id.ivThumb);
            if (iv != null) {
                Glide.with(this).load(st.effectiveUri()).centerCrop().override(720, 720).into(iv);
            }
            // Video badge
            View badge = thumb.findViewById(R.id.ivVideoBadge);
            if (badge != null) badge.setVisibility(st.isVideo ? View.VISIBLE : View.GONE);

            // Trim indicator
            View trimBadge = thumb.findViewById(R.id.ivTrimBadge);
            if (trimBadge != null) trimBadge.setVisibility(
                    (st.isVideo && st.trimmedUri != null) ? View.VISIBLE : View.GONE);

            thumb.setAlpha(idx == currentIndex ? 1f : 0.55f);
            thumb.setOnClickListener(v -> switchToItem(idx));
            thumbStripContent.addView(thumb);
        }
    }

    // PERF (ultra): switchToItem() used to call the full rebuildThumbStrip()
    // above on every single tap while swiping through a multi-image edit
    // session — re-inflating every thumb View and re-triggering Glide loads
    // for ALL items, just to move a highlight from one thumb to another.
    // For a session with many items this meant a visible strip flicker and
    // real inflate/Glide-lookup cost on every swipe. Structure only changes
    // on init/delete (those still call the full rebuildThumbStrip()); a
    // plain switch just needs the alpha updated on the views that already
    // exist, so do exactly that instead.
    private void updateThumbStripHighlight() {
        if (thumbStripContent == null) return;
        int visibleIdx = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).deleted) continue;
            View child = thumbStripContent.getChildAt(visibleIdx);
            if (child != null) child.setAlpha(i == currentIndex ? 1f : 0.55f);
            visibleIdx++;
        }
    }

    private void switchToItem(int idx) {
        if (idx < 0 || idx >= items.size() || items.get(idx).deleted) return;
        saveCurrentDrawState();
        currentIndex = idx;
        updateThumbStripHighlight();
        showCurrentItem();
    }

    private void saveCurrentDrawState() {
        // DrawOverlayView is already bound to EditState.strokes via bindStrokes()
        // so nothing extra to do here.
    }

    private void deleteCurrentItem() {
        if (items.size() <= 1) {
            Toast.makeText(this, "Cannot delete the last item", Toast.LENGTH_SHORT).show();
            return;
        }
        items.get(currentIndex).deleted = true;
        // find next non-deleted
        int next = -1;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).deleted) { next = i; break; }
        }
        currentIndex = (next != -1) ? next : 0;
        rebuildThumbStrip();
        showCurrentItem();
    }

    // ── Show current item ─────────────────────────────────────────────────

    private void showCurrentItem() {
        EditState st = current();
        resetMediaViewport();

        // Sync draw overlay to this item's stroke list
        drawOverlay.bindStrokes(st.strokes);
        drawOverlay.clearStrokes(); // triggers re-draw from backing list

        // Sync sticker layer
        stickerLayer.removeAllViews();
        selectedOverlay = st.overlays.isEmpty()
                ? null : st.overlays.get(st.overlays.size() - 1);
        selectedOverlayView = null;
        for (OverlayItem ov : st.overlays) renderOverlayView(ov);

        // Reset rotation display
        ivPreview.setRotation(st.rotationDeg);
        drawOverlay.setRotation(st.rotationDeg);
        stickerLayer.setRotation(st.rotationDeg);

        // Reset flip display
        float sx = st.flipHorizontal ? -1f : 1f;
        float sy = st.flipVertical   ? -1f : 1f;
        ivPreview.setScaleX(sx);
        ivPreview.setScaleY(sy);
        drawOverlay.setScaleX(sx);
        drawOverlay.setScaleY(sy);
        stickerLayer.setScaleX(sx);
        stickerLayer.setScaleY(sy);
        applyMediaViewportTransform();

        // Toggle video vs image tools
        boolean isVideo = st.isVideo;
        ivVideoPlayBadge.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        if (btnEditRotate != null) btnEditRotate.setAlpha(isVideo ? 0.35f : 1f);
        if (btnEditFlip   != null) btnEditFlip.setAlpha(isVideo ? 0.35f : 1f);
        if (btnEditCrop   != null) btnEditCrop.setAlpha(isVideo ? 0.35f : 1f);
        if (btnEditAdjust != null) btnEditAdjust.setAlpha(isVideo ? 0.35f : 1f);
        if (btnEditTrim   != null) {
            btnEditTrim.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        }

        // Load preview
        if (isVideo) {
            // Glide thumbnail from video
            Glide.with(this).load(st.effectiveUri()).override(720, 720).into(ivPreview);
            drawOverlay.setBlurSource(null, null); // no static frame to sample for the Blur brush
        } else {
            // Image — apply filter via ColorMatrix
            loadImageWithFilter(st);
        }

        // Filter strip — highlight current
        if (filterStripContent != null) {
            for (int c = 0; c < filterStripContent.getChildCount(); c++) {
                filterStripContent.getChildAt(c).setAlpha(c == st.filterIndex ? 1f : 0.6f);
            }
        }

        // Adjust sliders — reflect this item's saved values if the panel happens to be open
        if (adjustPanelOpen) refreshAdjustSliders();

        // Swipe hint
        if (tvSwipeHint != null) {
            tvSwipeHint.setVisibility(isVideo ? View.GONE : View.VISIBLE);
            if (!isVideo && !swipeHintBounced) {
                swipeHintBounced = true;
                playSwipeHintAttentionBounce();
            }
        }
    }

    /**
     * One-shot attention animation for the "Swipe up for filters" chip —
     * a few small bounces the very first time it's shown, so a first-time
     * user actually notices the feature instead of the chip just sitting
     * there as static text they might tune out.
     */
    private void playSwipeHintAttentionBounce() {
        if (tvSwipeHint == null) return;
        tvSwipeHint.postDelayed(() -> {
            if (tvSwipeHint == null) return;
            tvSwipeHint.animate()
                    .translationYBy(-dp(14))
                    .setDuration(260)
                    .setInterpolator(new OvershootInterpolator())
                    .withEndAction(() -> tvSwipeHint.animate()
                            .translationYBy(dp(14))
                            .setDuration(260)
                            .setInterpolator(new OvershootInterpolator())
                            .start())
                    .start();
        }, 500);
    }

    // PERF/CORRECTNESS (ultra): loadImageWithFilter() used to spawn a brand
    // new raw Thread on every call. Swiping quickly through a multi-image
    // edit session called this once per swipe, so several full-resolution
    // decodes could be running concurrently — wasted CPU, and if an OLDER
    // decode finished after a NEWER one, its stale bitmap would overwrite
    // the correct preview the user was already looking at.
    // Fix: route through the existing single-thread bgExec (no thread
    // creation cost, and decodes now run strictly one-at-a-time instead of
    // piling up) and stamp each request with a generation counter so a
    // late-arriving stale decode is silently dropped instead of painted.
    private volatile int previewLoadGeneration = 0;

    /** Last decoded (rotation/flip-baked, pre-filter) bitmap shown on ivPreview — used as the Blur brush's pixelation source. */
    private Bitmap currentPreviewRawBitmap;

    private void loadImageWithFilter(EditState st) {
        final int myGeneration = ++previewLoadGeneration;
        // Load bitmap, apply rotation + flip matrix + filter ColorMatrix
        bgExec.submit(() -> {
            try {
                Bitmap bmp = decodeSampledBitmap(st.uri, 1080);
                Matrix m = new Matrix();
                if (st.flipHorizontal) m.postScale(-1f, 1f);
                if (st.flipVertical)   m.postScale(1f, -1f);
                m.postRotate(st.rotationDeg);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                runOnUiThread(() -> {
                    if (myGeneration != previewLoadGeneration) return; // stale — user already moved on
                    ivPreview.setRotation(0); // already rotated
                    ivPreview.setScaleX(1f);  // already flipped
                    ivPreview.setScaleY(1f);
                    if (st.filterIndex > 0 || MediaFilters.hasAdjustments(
                            st.adjBrightness, st.adjContrast, st.adjSaturation, st.adjExposure)) {
                        ivPreview.setColorFilter(new ColorMatrixColorFilter(st.colorMatrix()));
                    } else {
                        ivPreview.clearColorFilter();
                    }
                    ivPreview.setImageBitmap(rotated);
                    currentPreviewRawBitmap = rotated;
                    // Rotation/flip are baked into this bitmap. Re-apply the
                    // viewport transform because ImageView state was reset
                    // while the async decode was in flight.
                    applyMediaViewportTransform();
                    refreshBlurSource();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (myGeneration != previewLoadGeneration) return;
                    Glide.with(this).load(st.effectiveUri()).override(720, 720).into(ivPreview);
                });
            }
        });
    }

    /**
     * Recomputes the pixelated bitmap the Blur/Pixelate draw brush samples
     * from, matching whatever is currently on {@link #ivPreview}. Cheap
     * enough to call after every item load; a no-op for videos (there's no
     * single static frame to sample, so the brush falls back to a solid
     * redaction color instead — see {@link DrawOverlayView}).
     */
    private void refreshBlurSource() {
        if (current().isVideo) {
            drawOverlay.setBlurSource(null, null);
            return;
        }
        Bitmap raw = currentPreviewRawBitmap;
        if (raw == null || raw.isRecycled()) return;
        ivPreview.post(() -> {
            final Matrix matrix = new Matrix(ivPreview.getImageMatrix());
            bgExec.submit(() -> {
                try {
                    Bitmap pixelated = DrawOverlayView.pixelate(raw, 24);
                    runOnUiThread(() -> drawOverlay.setBlurSource(pixelated, matrix));
                } catch (Exception ignored) { /* blur brush just won't have a live preview source */ }
            });
        });
    }

    // ── Download ──────────────────────────────────────────────────────────

    private void downloadCurrent() {
        EditState st = current();
        if (st.isVideo) {
            Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Saving to gallery…", Toast.LENGTH_SHORT).show();
        bgExec.submit(() -> {
            try {
                Bitmap baked = bakeBitmap(st);
                if (baked == null) return;

                String fname = "callx_edit_" + System.currentTimeMillis() + ".jpg";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fname);
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES);
                    Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out != null) baked.compress(Bitmap.CompressFormat.JPEG, 95, out);
                        }
                    }
                } else {
                    File pics = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES);
                    File out = new File(pics, fname);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        baked.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                    }
                    android.media.MediaScannerConnection.scanFile(this,
                            new String[]{out.getAbsolutePath()}, null, null);
                }
                runOnUiThread(() ->
                    Toast.makeText(this, "Saved to gallery ✓", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ── Bake + send ───────────────────────────────────────────────────────

    private void bakeAndSend() {
        Toast.makeText(this, "Preparing…", Toast.LENGTH_SHORT).show();
        processItemForSend(0, new ArrayList<>());
    }

    /**
     * Processes items one at a time and recurses into the next once each is
     * ready, then finishes the activity with the accumulated result.
     *
     * Photos are baked (rotation + filter + stickers + drawing) on the
     * background executor, same as before. Videos with stickers/text/draw
     * edits now go through {@link VideoOverlayBaker} — which must run on
     * the main thread — so those edits are actually burned into the video
     * file instead of only ever having been visible in the editor preview
     * and vanishing once the original video was sent. Videos with no such
     * edits (trim-only or untouched) skip straight through with no re-encode.
     */
    private void processItemForSend(int index, ArrayList<String> resultUris) {
        if (index >= items.size()) {
            Intent res = new Intent();
            res.putStringArrayListExtra(RESULT_URIS, resultUris);
            String cap = etCaption.getText() != null ? etCaption.getText().toString() : "";
            res.putExtra(RESULT_CAPTION, cap);
            res.putExtra(RESULT_HD, isHD);
            // Feature: Voice Caption on Photo — only ever set when there's
            // exactly one item (voiceCaptureUi hides the mic for multi-item
            // sends, see attachVoiceCaptureUi), so no ambiguity about which
            // photo it belongs to on the receiving end.
            if (recordedVoiceUri != null && items.size() == 1) {
                res.putExtra(RESULT_VOICE_URI, recordedVoiceUri.toString());
                res.putExtra(RESULT_VOICE_DURATION, recordedVoiceDurationMs);
            }
            setResult(Activity.RESULT_OK, res);
            finish();
            return;
        }

        EditState st = items.get(index);
        if (st.deleted) {
            processItemForSend(index + 1, resultUris);
            return;
        }

        if (st.isVideo) {
            if (st.overlays.isEmpty() && st.strokes.isEmpty()) {
                // Trim-only or untouched — no need to re-encode.
                resultUris.add(st.effectiveUri().toString());
                processItemForSend(index + 1, resultUris);
                return;
            }

            Toast.makeText(this, "Rendering video edits…", Toast.LENGTH_SHORT).show();
            Uri videoUri = st.effectiveUri();
            int[] size = VideoOverlayBaker.readDisplaySize(this, videoUri);
            int videoW = size[0] > 0 ? size[0] : 720;
            int videoH = size[1] > 0 ? size[1] : 1280;
            Bitmap overlayBitmap = renderOverlayBitmapForVideo(st, videoW, videoH);

            VideoOverlayBaker.bakeOverlay(this, videoUri, overlayBitmap, new VideoOverlayBaker.Callback() {
                @Override public void onProgress(int percent) { /* no progress UI needed for one-off sends */ }

                @Override public void onSuccess(Uri outputUri) {
                    resultUris.add(outputUri.toString());
                    processItemForSend(index + 1, resultUris);
                }

                @Override public void onError(Exception e) {
                    android.util.Log.e("MediaEditActivity", "Video overlay bake failed", e);
                    Toast.makeText(MediaEditActivity.this,
                            "Couldn't render video edits, sending original video",
                            Toast.LENGTH_SHORT).show();
                    resultUris.add(videoUri.toString());
                    processItemForSend(index + 1, resultUris);
                }
            });
            return;
        }

        // Photo path — unchanged, baked on the background executor.
        bgExec.submit(() -> {
            String uriStr;
            if (st.hasEdits()) {
                Bitmap baked = bakeBitmap(st);
                if (baked != null) {
                    String out;
                    try {
                        File outDir = new File(getCacheDir(), "media_edit_out");
                        if (!outDir.exists()) outDir.mkdirs();
                        File f = new File(outDir, "edit_" + UUID.randomUUID() + ".jpg");
                        try (FileOutputStream fos = new FileOutputStream(f)) {
                            baked.compress(Bitmap.CompressFormat.JPEG, isHD ? 95 : 82, fos);
                        }
                        Uri fileUri = FileProvider.getUriForFile(this,
                                getPackageName() + ".fileprovider", f);
                        out = fileUri.toString();
                    } catch (Exception e) {
                        out = st.uri.toString();
                    } finally {
                        baked.recycle();
                    }
                    uriStr = out;
                } else {
                    uriStr = st.uri.toString();
                }
            } else {
                uriStr = st.uri.toString();
            }
            String finalUriStr = uriStr;
            runOnUiThread(() -> {
                resultUris.add(finalUriStr);
                processItemForSend(index + 1, resultUris);
            });
        });
    }

    // ── Bitmap baking ─────────────────────────────────────────────────────

    /**
     * Bakes all edits for one item onto a single Bitmap:
     * rotation → filter → stickers/text overlays → freehand strokes.
     */
    private @Nullable Bitmap bakeBitmap(EditState st) {
        try {
            int maxDim = isHD ? 2160 : 1280;
            Bitmap base = decodeSampledBitmap(st.uri, maxDim);

            // Flip + Rotation
            Matrix m = new Matrix();
            if (st.flipHorizontal) m.postScale(-1f, 1f);
            if (st.flipVertical)   m.postScale(1f, -1f);
            m.postRotate(st.rotationDeg);
            Bitmap out = Bitmap.createBitmap(base, 0, 0,
                    base.getWidth(), base.getHeight(), m, true);
            if (out != base) base.recycle();

            Canvas canvas;
            // Belt-and-suspenders: even with inMutable=true above, force
            // ARGB_8888 + mutable right before handing to Canvas, in case
            // any OEM decoder quirk or future code path slips an immutable
            // / non-ARGB_8888 bitmap through here again.
            if (!out.isMutable() || out.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = out.copy(Bitmap.Config.ARGB_8888, true);
                out.recycle();
                out = converted;
            }
            canvas = new Canvas(out);

            // Filter + manual brightness/contrast/saturation/exposure adjustments
            boolean hasColorEdit = st.filterIndex > 0 || MediaFilters.hasAdjustments(
                    st.adjBrightness, st.adjContrast, st.adjSaturation, st.adjExposure);
            if (hasColorEdit) {
                Paint fp = new Paint();
                fp.setColorFilter(new ColorMatrixColorFilter(st.colorMatrix()));
                canvas.drawBitmap(out.copy(Bitmap.Config.ARGB_8888, false), 0, 0, fp);
            }

            // Pixelated snapshot for the Blur/Pixelate brush's BRUSH_BLUR
            // strokes — built from the (rotation/flip/filter-baked) pixels
            // already on `out`, at the exact same resolution, so it aligns
            // 1:1 with the canvas the strokes are about to be drawn onto.
            Bitmap blurBake = st.strokes.isEmpty() ? null
                    : DrawOverlayView.pixelate(out.copy(Bitmap.Config.ARGB_8888, false), 24);

            paintOverlaysAndStrokes(canvas, st, out.getWidth(), out.getHeight(), blurBake);
            if (blurBake != null) blurBake.recycle();

            return out;
        } catch (Exception e) {
            android.util.Log.e("MediaEditActivity", "bakeBitmap failed", e);
            return null;
        }
    }

    /**
     * Draws an item's stickers/text overlays and freehand strokes onto {@code canvas},
     * mapping their editor-recorded screen fractions into {@code contentW x contentH}
     * pixel space (the photo's own pixels, or — for video — a transparent bitmap sized
     * to match the video's display dimensions).
     *
     * Shared by {@link #bakeBitmap} (photos) and {@link #renderOverlayBitmapForVideo}
     * (videos) so both paths use the exact same fitCenter screen→content mapping and
     * can't drift out of sync with each other.
     */
    private void paintOverlaysAndStrokes(Canvas canvas, EditState st, int contentW, int contentH) {
        paintOverlaysAndStrokes(canvas, st, contentW, contentH, null);
    }

    private void paintOverlaysAndStrokes(Canvas canvas, EditState st, int contentW, int contentH, Bitmap blurBakeBitmap) {
        // ── Screen → content-pixel mapping ────────────────────────────────
        // Overlay positions (xFrac/yFrac) and stroke points are recorded
        // normalized against the *editor's* full-screen preview view — but
        // that view uses scaleType="fitCenter", so the photo/video itself
        // is letterboxed inside it whenever its aspect ratio doesn't match
        // the screen's. Multiplying fractions straight through by
        // contentW/contentH as if the content filled the whole view would
        // put stickers/text/strokes in the wrong spot (sometimes entirely
        // outside the content) on every non-matching aspect ratio. Computing
        // the same fitCenter rect here and mapping through it fixes that.
        int viewW = Math.max(1, ivPreview.getWidth());
        int viewH = Math.max(1, ivPreview.getHeight());
        float fitScale = Math.min((float) viewW / contentW, (float) viewH / contentH);
        if (fitScale <= 0f) fitScale = 1f;
        float dispW = contentW * fitScale;
        float dispH = contentH * fitScale;
        float offX  = (viewW - dispW) / 2f;
        float offY  = (viewH - dispH) / 2f;

        // Overlays (stickers / text)
        float density = getResources().getDisplayMetrics().density;
        for (OverlayItem ov : st.overlays) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(ov.color);
            Typeface tf;
            try {
                tf = Typeface.create(ov.fontFamily,
                        (ov.isBold && ov.isItalic) ? Typeface.BOLD_ITALIC
                      : ov.isBold  ? Typeface.BOLD
                      : ov.isItalic? Typeface.ITALIC
                      : Typeface.NORMAL);
            } catch (Exception e) {
                tf = Typeface.DEFAULT;
            }
            tp.setTypeface(tf);
            // Text was rendered on-screen at textSizeSp*density px; convert
            // to content-pixel space through the same fitCenter scale so it
            // ends up the same relative size on the sent photo/video.
            float ts = (ov.textSizeSp * density * ov.scale) / fitScale;
            tp.setTextSize(ts);
            float screenX = ov.xFrac * viewW;
            float screenY = ov.yFrac * viewH;
            float x = (screenX - offX) / fitScale;
            float y = (screenY - offY) / fitScale;

            // Split into lines so multi-line text (typed with a newline in
            // the editor) can be laid out and justified line-by-line instead
            // of drawing the whole block as one string starting at the
            // vertical center.
            String[] lines = ov.text.split("\n", -1);
            float lineHeight = ts * 1.2f;
            float blockH = lineHeight * lines.length;
            float maxLineW = 0f;
            for (String line : lines) maxLineW = Math.max(maxLineW, tp.measureText(line));

            if (ov.hasBg) {
                Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgP.setColor(0xCC000000);
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(ov.rotationDeg);
                canvas.drawRoundRect(-maxLineW / 2 - dp(6), -blockH / 2 - dp(4),
                        maxLineW / 2 + dp(6), blockH / 2 + dp(4), dp(8), dp(8), bgP);
                canvas.restore();
            }

            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(ov.rotationDeg);
            // Baseline of the first line, so the whole block is vertically
            // centered on the overlay's anchor point.
            float lineY = -blockH / 2f + ts * 0.85f;
            for (String line : lines) {
                float lw = tp.measureText(line);
                float lineX;
                if ("left".equals(ov.textAlign)) {
                    lineX = -maxLineW / 2f;
                } else if ("right".equals(ov.textAlign)) {
                    lineX = maxLineW / 2f - lw;
                } else {
                    lineX = -lw / 2f;
                }
                canvas.drawText(line, lineX, lineY, tp);
                lineY += lineHeight;
            }
            canvas.restore();
        }

        // Freehand strokes — same screen→content remap as overlays above.
        DrawOverlayView.drawStrokes(canvas, st.strokes,
                contentW, contentH,
                viewW, viewH, offX, offY, fitScale, density, blurBakeBitmap);
    }

    /**
     * Renders this video item's stickers/text overlays and freehand strokes onto a
     * transparent bitmap sized to the video's own (rotation-corrected) display
     * dimensions, ready to be burned into the video's pixels by {@link VideoOverlayBaker}.
     */
    private Bitmap renderOverlayBitmapForVideo(EditState st, int videoW, int videoH) {
        Bitmap bitmap = Bitmap.createBitmap(
                Math.max(1, videoW), Math.max(1, videoH), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        paintOverlaysAndStrokes(canvas, st, videoW, videoH);
        return bitmap;
    }

    // ── Overlay rendering ─────────────────────────────────────────────────

    private void renderOverlayView(OverlayItem overlay) {
        TextView tv = new TextView(this);
        tv.setText(overlay.text);
        tv.setTextColor(overlay.color);
        tv.setTextSize(overlay.textSizeSp);

        // Apply font
        if (overlay.fontFamily != null && !overlay.fontFamily.equals("default")) {
            try {
                Typeface tf = Typeface.create(overlay.fontFamily,
                        (overlay.isBold && overlay.isItalic) ? Typeface.BOLD_ITALIC
                      : overlay.isBold  ? Typeface.BOLD
                      : overlay.isItalic? Typeface.ITALIC
                      : Typeface.NORMAL);
                tv.setTypeface(tf);
            } catch (Exception ignored) {}
        } else {
            tv.setTypeface(null,
                    (overlay.isBold && overlay.isItalic) ? Typeface.BOLD_ITALIC
                  : overlay.isBold  ? Typeface.BOLD
                  : overlay.isItalic? Typeface.ITALIC
                  : Typeface.NORMAL);
        }

        // Background pill
        if (overlay.hasBg) {
            tv.setBackgroundColor(0xCC000000);
            tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        } else {
            tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        }

        // Alignment — matters once the text wraps to multiple lines.
        if ("left".equals(overlay.textAlign)) {
            tv.setGravity(Gravity.START);
        } else if ("right".equals(overlay.textAlign)) {
            tv.setGravity(Gravity.END);
        } else {
            tv.setGravity(Gravity.CENTER);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        tv.setTag(overlay);
        stickerLayer.addView(tv);
        if (overlay == selectedOverlay) selectedOverlayView = tv;
        tv.post(() -> positionOverlayView(tv, overlay));
        attachDragAndPinch(tv, overlay);
    }

    private void positionOverlayView(View v, OverlayItem overlay) {
        int pw = stickerLayer.getWidth(), ph = stickerLayer.getHeight();
        if (pw == 0 || ph == 0) return;
        v.setX(overlay.xFrac * pw - v.getWidth()  / 2f);
        v.setY(overlay.yFrac * ph - v.getHeight() / 2f);
        v.setScaleX(overlay.scale);
        v.setScaleY(overlay.scale);
        v.setRotation(overlay.rotationDeg);
    }

    /**
     * Single-finger drag + two-finger pinch/rotate gesture on each overlay.
     * Long-press = delete. Single tap on text overlay = re-open editor.
     */
    private void attachDragAndPinch(View v, OverlayItem overlay) {
        final float[] lastTouch    = new float[2];
        final float[] startDist    = {0f};
        final float[] startScale   = {overlay.scale};
        final float[] startAngle   = {0f};
        final float[] startRot     = {overlay.rotationDeg};

        GestureDetector tapDet = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                selectOverlay(overlay, v);
                if (!overlay.isEmoji) launchEditTextOverlay(overlay, v);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                selectOverlay(overlay, v);
                showOverlayLayerDialog();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                removeOverlay(overlay, v);
            }
        });

        v.setOnTouchListener((view, event) -> {
            tapDet.onTouchEvent(event);
            int pw = stickerLayer.getWidth(), ph = stickerLayer.getHeight();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    selectOverlay(overlay, view);
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        startDist[0]  = fingerDistance(event);
                        startAngle[0] = fingerAngle(event);
                        startScale[0] = overlay.scale;
                        startRot[0]   = overlay.rotationDeg;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 2 && startDist[0] > 0) {
                        float dist = fingerDistance(event);
                        overlay.scale = Math.max(0.3f, Math.min(
                                startScale[0] * (dist / startDist[0]), 4f));
                        overlay.rotationDeg = startRot[0] + (fingerAngle(event) - startAngle[0]);
                        view.setScaleX(overlay.scale);
                        view.setScaleY(overlay.scale);
                        view.setRotation(overlay.rotationDeg);
                    } else {
                        // The parent layer may be scaled by the main-image
                        // pinch zoom. Convert screen movement back into the
                        // layer's local coordinate space before persisting it.
                        float zoom = Math.max(1f, mediaZoomScale);
                        float dx = (event.getRawX() - lastTouch[0]) / zoom;
                        float dy = (event.getRawY() - lastTouch[1]) / zoom;
                        view.setX(view.getX() + dx);
                        view.setY(view.getY() + dy);
                        lastTouch[0] = event.getRawX();
                        lastTouch[1] = event.getRawY();
                        if (pw > 0 && ph > 0) {
                            overlay.xFrac = (view.getX() + view.getWidth()  / 2f) / pw;
                            overlay.yFrac = (view.getY() + view.getHeight() / 2f) / ph;
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pw > 0 && ph > 0) {
                        overlay.xFrac = (view.getX() + view.getWidth()  / 2f) / pw;
                        overlay.yFrac = (view.getY() + view.getHeight() / 2f) / ph;
                    }
                    return true;
            }
            return false;
        });
    }

    /** Long-press → remove overlay from layer and state list. */
    private void removeOverlay(OverlayItem overlay, View v) {
        current().overlays.remove(overlay);
        stickerLayer.removeView(v);
        if (selectedOverlay == overlay) {
            selectedOverlay = null;
            selectedOverlayView = null;
        }
        Toast.makeText(this,
                overlay.isEmoji ? "Sticker removed" : "Text removed",
                Toast.LENGTH_SHORT).show();
    }

    private void selectOverlay(OverlayItem overlay, View view) {
        selectedOverlay = overlay;
        selectedOverlayView = view;
        if (btnEditLayers != null) {
            btnEditLayers.setEnabled(true);
            btnEditLayers.setAlpha(1f);
        }
    }

    private View findOverlayView(OverlayItem overlay) {
        if (overlay == null || stickerLayer == null) return null;
        for (int i = 0; i < stickerLayer.getChildCount(); i++) {
            View child = stickerLayer.getChildAt(i);
            if (child.getTag() == overlay) return child;
        }
        return null;
    }

    /**
     * Opens the layer controls for the selected overlay. The data list and
     * FrameLayout child order are updated together so the preview and baked
     * image/video use exactly the same stacking order.
     */
    private void showOverlayLayerDialog() {
        EditState st = current();
        if (st.overlays.isEmpty()) {
            Toast.makeText(this, "Add a sticker or text first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedOverlay == null || !st.overlays.contains(selectedOverlay)) {
            selectedOverlay = st.overlays.get(st.overlays.size() - 1);
            selectedOverlayView = findOverlayView(selectedOverlay);
        }

        String label = selectedOverlay.isEmoji ? "Sticker" : "Text";
        overlayLayerDialog = new AlertDialog.Builder(this)
                .setTitle("Arrange " + label)
                .setItems(new String[]{
                        "Bring to front",
                        "Move forward",
                        "Move backward",
                        "Send to back"
                }, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            moveOverlayTo(st, selectedOverlay, st.overlays.size() - 1);
                            break;
                        case 1:
                            moveOverlayTo(st, selectedOverlay,
                                    Math.min(st.overlays.size() - 1,
                                            st.overlays.indexOf(selectedOverlay) + 1));
                            break;
                        case 2:
                            moveOverlayTo(st, selectedOverlay,
                                    Math.max(0, st.overlays.indexOf(selectedOverlay) - 1));
                            break;
                        case 3:
                            moveOverlayTo(st, selectedOverlay, 0);
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void moveOverlayTo(EditState st, OverlayItem overlay, int targetIndex) {
        int oldIndex = st.overlays.indexOf(overlay);
        if (oldIndex < 0) return;
        targetIndex = Math.max(0, Math.min(st.overlays.size() - 1, targetIndex));
        if (oldIndex != targetIndex) {
            st.overlays.remove(oldIndex);
            st.overlays.add(targetIndex, overlay);
        }

        View view = findOverlayView(overlay);
        if (view != null) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            stickerLayer.removeView(view);
            stickerLayer.addView(view, targetIndex, lp);
            selectedOverlayView = view;
        }
        if (overlayLayerDialog != null) {
            overlayLayerDialog.dismiss();
            overlayLayerDialog = null;
        }
    }

    /** Tap on existing TEXT overlay → re-launch ChatStickerPickerActivity in text mode. */
    private void launchEditTextOverlay(OverlayItem overlay, View v) {
        // Re-open the picker pre-filled with this overlay's current text and
        // styling. The overlay itself is left in place (not removed) — its
        // position/scale/rotation stay put and are only overwritten if the
        // user actually saves an edit (see stickerLauncher result handler).
        editingOverlay = overlay;
        Intent i = new Intent(this, ChatStickerPickerActivity.class);
        i.putExtra(ChatStickerPickerActivity.EXTRA_TEXT_MODE, true);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_TEXT, overlay.text);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_COLOR, overlay.color);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_FONT, overlay.fontFamily);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_SIZE, overlay.textSizeSp);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_BOLD, overlay.isBold);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_ITALIC, overlay.isItalic);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_ALIGN, overlay.textAlign);
        i.putExtra(ChatStickerPickerActivity.EXTRA_PREFILL_HAS_BG, overlay.hasBg);
        stickerLauncher.launch(i);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private EditState current() {
        return items.get(Math.min(currentIndex, items.size() - 1));
    }

    private static float fingerDistance(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float fingerAngle(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(0) - e.getY(1), e.getX(0) - e.getX(1)));
    }

    private Bitmap decodeSampledBitmap(Uri uri, int maxDim) throws Exception {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, b);
        }
        int sample = 1;
        while ((b.outWidth / sample) > maxDim || (b.outHeight / sample) > maxDim) sample *= 2;
        b.inJustDecodeBounds = false;
        b.inSampleSize = sample;
        // BUG FIX: BitmapFactory.decodeStream() returns an IMMUTABLE bitmap
        // by default. bakeBitmap() wraps its (possibly-unrotated) result in
        // a Canvas to draw the filter/stickers/text/freehand strokes onto —
        // and when rotationDeg == 0 (i.e. the user filtered/stickered/drew
        // WITHOUT also rotating, by far the common case), Bitmap.createBitmap()
        // with an identity matrix returns this SAME decoded bitmap instead of
        // a fresh copy, so `new Canvas(out)` was throwing
        // "IllegalStateException: Immutable bitmap passed to Canvas
        // constructor". That exception was silently caught in bakeBitmap(),
        // which returned null, and bakeAndSend() then fell back to sending
        // the ORIGINAL un-edited file — every edit except crop (which bakes
        // in its own activity and never reaches this code path) silently
        // disappeared on send. inMutable=true makes the decoded bitmap
        // already mutable, so Canvas always works, whether or not rotation
        // actually changed anything.
        b.inMutable = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in, null, b);
            if (bmp == null) throw new IllegalStateException("decode returned null");
            return bmp;
        }
    }

    private int dp(int v) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (int)(v * dm.density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExec.shutdownNow();
        if (isRecordingVoiceCaption) voiceCaptureRecorder.cancel();
    }
}
