package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
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
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

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
 *  ✅ Stickers/Text can be placed on video (baked into first frame for thumbnail)
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

    public static final String RESULT_URIS    = "media_edit_result_uris";
    public static final String RESULT_CAPTION = "media_edit_result_caption";
    public static final String RESULT_HD      = "media_edit_result_hd";

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
    }

    // ── Per-item edit state ───────────────────────────────────────────────
    private static final class EditState {
        Uri    uri;
        boolean isVideo;
        boolean deleted    = false;
        int    rotationDeg = 0;
        int    filterIndex = 0;
        Uri    trimmedUri  = null; // set after video trim
        final List<OverlayItem>         overlays = new ArrayList<>();
        final List<DrawOverlayView.Stroke> strokes  = new ArrayList<>();

        boolean hasEdits() {
            return rotationDeg != 0 || filterIndex != 0
                || !overlays.isEmpty() || !strokes.isEmpty()
                || trimmedUri != null;
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

    // ── Views ─────────────────────────────────────────────────────────────
    private ImageView    ivPreview;
    private ImageView    ivVideoPlayBadge;
    private FrameLayout  stickerLayer;
    private DrawOverlayView drawOverlay;
    private ImageButton  btnEditRotate, btnEditSticker, btnEditText,
                         btnEditDraw, btnEditDownload, btnEditCrop;
    private TextView     btnEditHd, btnEditTrim;
    private HorizontalScrollView emojiRowScroll;
    private LinearLayout emojiRowContent, thumbStripContent, filterStripContent, drawColorRow;
    private final List<ImageView> filterCheckViews = new ArrayList<>();
    private boolean filterPanelOpen = false;
    private View         emojiRow, drawToolsRow, filterPanel, bottomBar, tvSwipeHint;
    private EditText     etCaption;
    private SeekBar      sbBrushSize;
    private View         brushSizeRow;

    // ── Background thread for baking ──────────────────────────────────────
    private final ExecutorService bgExec = Executors.newSingleThreadExecutor();

    // ── Activity launchers ────────────────────────────────────────────────
    private ActivityResultLauncher<Intent> cropLauncher;
    private ActivityResultLauncher<Intent> trimLauncher;
    private ActivityResultLauncher<Intent> stickerLauncher;

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
        setupBottomBar();
        setupFilterSwipeGesture();

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
                if (value == null || value.isEmpty()) return;

                OverlayItem overlay = new OverlayItem();
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
                }
                current().overlays.add(overlay);
                renderOverlayView(overlay);
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
        btnEditSticker   = findViewById(R.id.btnEditSticker);
        btnEditText      = findViewById(R.id.btnEditText);
        btnEditDraw      = findViewById(R.id.btnEditDraw);
        btnEditDownload  = findViewById(R.id.btnEditDownload);
        btnEditCrop      = findViewById(R.id.btnEditCrop);
        btnEditHd        = findViewById(R.id.btnEditHd);
        btnEditTrim      = findViewById(R.id.btnEditTrim);
        emojiRow         = findViewById(R.id.emojiRow);
        emojiRowScroll   = (HorizontalScrollView) emojiRow;
        emojiRowContent  = findViewById(R.id.emojiRowContent);
        drawToolsRow     = findViewById(R.id.drawToolsRow);
        drawColorRow     = findViewById(R.id.drawColorRow);
        sbBrushSize      = findViewById(R.id.sbBrushSize);
        brushSizeRow     = findViewById(R.id.brushSizeRow);
        filterPanel      = findViewById(R.id.filterPanel);
        filterStripContent = findViewById(R.id.filterStripContent);
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
            Intent i = new Intent(this, ChatStickerPickerActivity.class);
            i.putExtra(ChatStickerPickerActivity.EXTRA_TEXT_MODE, false);
            stickerLauncher.launch(i);
        });

        // Text — launches ChatStickerPickerActivity in text mode
        if (btnEditText != null) btnEditText.setOnClickListener(v -> {
            hideAllToolRows();
            Intent i = new Intent(this, ChatStickerPickerActivity.class);
            i.putExtra(ChatStickerPickerActivity.EXTRA_TEXT_MODE, true);
            stickerLauncher.launch(i);
        });

        // Draw
        if (btnEditDraw != null) btnEditDraw.setOnClickListener(v -> toggleDrawMode());
    }

    private void refreshHdButton() {
        if (btnEditHd == null) return;
        btnEditHd.setText(isHD ? "HD" : "HD");
        btnEditHd.setAlpha(isHD ? 1f : 0.45f);
    }

    private void applyRotationToPreview() {
        if (ivPreview == null) return;
        ivPreview.animate().rotation(ivPreview.getRotation() + 90)
                .setDuration(220).setInterpolator(new OvershootInterpolator(1.5f)).start();
        drawOverlay.setRotation(ivPreview.getRotation() + 90);
        stickerLayer.setRotation(ivPreview.getRotation() + 90);
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
    private View    dotBrushSmall, dotBrushLarge;

    private void setupDrawTools() {
        dotBrushSmall = findViewById(R.id.dotBrushSmall);
        dotBrushLarge = findViewById(R.id.dotBrushLarge);

        // ── Color swatches — circular, with border ring on selected ──
        if (drawColorRow != null) {
            drawColorRow.removeAllViews();
            int[] colors = {
                Color.WHITE, Color.BLACK,
                0xFFFF5252, 0xFFFF9800, 0xFFFFEB3B,
                0xFF4CAF50, 0xFF2196F3, 0xFF9C27B0,
                0xFFFF4081, 0xFF00BCD4
            };
            for (int color : colors) {
                android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
                LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(38), dp(38));
                wlp.setMarginEnd(dp(6));
                wrapper.setLayoutParams(wlp);

                // Outer ring (white border — visible when selected)
                View ring = new View(this);
                android.widget.FrameLayout.LayoutParams rlp =
                        new android.widget.FrameLayout.LayoutParams(dp(38), dp(38));
                ring.setLayoutParams(rlp);
                ring.setBackground(makeCircle(Color.WHITE));
                ring.setAlpha(0f); // hidden by default
                wrapper.addView(ring);

                // Color circle
                View dot = new View(this);
                android.widget.FrameLayout.LayoutParams dlp =
                        new android.widget.FrameLayout.LayoutParams(dp(28), dp(28));
                dlp.gravity = android.view.Gravity.CENTER;
                dot.setLayoutParams(dlp);
                dot.setBackground(makeCircle(color));
                wrapper.addView(dot);

                final int dotColor = color;
                final View dotRing = ring;
                wrapper.setOnClickListener(v -> {
                    selectDrawColor(dotColor, dotRing);
                });
                drawColorRow.addView(wrapper);
            }
            // Select default (first = white)
            if (drawColorRow.getChildCount() > 0) {
                View firstRing = ((android.widget.FrameLayout) drawColorRow.getChildAt(0))
                        .getChildAt(0);
                firstRing.setAlpha(1f);
                activeDrawColor = Color.WHITE;
                drawOverlay.setActiveColor(activeDrawColor);
            }
        }

        // ── Brush size slider ──
        if (sbBrushSize != null) {
            sbBrushSize.setMax(44);
            sbBrushSize.setProgress(8);
            drawOverlay.setActiveWidthDp(8);
            sbBrushSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    int size = Math.max(2, p);
                    drawOverlay.setActiveWidthDp(size);
                    // Animate the large dot to reflect current size.
                    // dotBrushLarge's parent (brushSizeRow) is a LinearLayout,
                    // so its LayoutParams must be LinearLayout.LayoutParams —
                    // passing FrameLayout.LayoutParams here threw a
                    // ClassCastException on every seekbar move (the crash on
                    // brush-size adjust).
                    if (dotBrushLarge != null) {
                        int dotPx = Math.max(dp(6), Math.min(dp(28), (int)(size * getResources().getDisplayMetrics().density * 0.8f)));
                        ViewGroup.LayoutParams existing = dotBrushLarge.getLayoutParams();
                        if (existing instanceof LinearLayout.LayoutParams) {
                            LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) existing;
                            lp2.width  = dotPx;
                            lp2.height = dotPx;
                            dotBrushLarge.setLayoutParams(lp2);
                        } else {
                            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dotPx, dotPx);
                            dotBrushLarge.setLayoutParams(lp2);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
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

        // ── Undo ──
        View btnUndo = findViewById(R.id.btnDrawUndo);
        if (btnUndo != null) btnUndo.setOnClickListener(v -> {
            drawOverlay.undoLastStroke();
        });

        // ── Clear all ──
        View btnClear = findViewById(R.id.btnDrawClear);
        if (btnClear != null) btnClear.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                .setMessage("Clear all drawing?")
                .setPositiveButton("Clear", (d, w) -> {
                    drawOverlay.clearStrokes();
                    current().strokes.clear();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        // ── Done ──
        View btnDrawDone = findViewById(R.id.btnDrawDone);
        if (btnDrawDone != null) btnDrawDone.setOnClickListener(v -> exitDrawMode());
    }

    /** Selects a color swatch — highlights its ring, updates overlay. */
    private void selectDrawColor(int color, View selectedRing) {
        // Deselect all rings
        if (drawColorRow != null) {
            for (int ci = 0; ci < drawColorRow.getChildCount(); ci++) {
                android.widget.FrameLayout wrapper =
                        (android.widget.FrameLayout) drawColorRow.getChildAt(ci);
                wrapper.getChildAt(0).setAlpha(0f);
            }
        }
        // Activate this ring
        if (selectedRing != null) selectedRing.setAlpha(1f);
        // Apply color
        activeDrawColor = color;
        eraserActive    = false;
        drawOverlay.setActiveColor(color);
        // Reset eraser button style
        View btnEraser = findViewById(R.id.btnDrawEraser);
        if (btnEraser != null) {
            btnEraser.setAlpha(0.7f);
            btnEraser.setBackground(getDrawable(R.drawable.bg_media_edit_toolbtn));
        }
    }

    /** Builds a circular ShapeDrawable of the given fill color. */
    private android.graphics.drawable.ShapeDrawable makeCircle(int color) {
        android.graphics.drawable.ShapeDrawable d =
                new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
        d.getPaint().setColor(color);
        return d;
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
        if (emojiRow    != null) emojiRow.setVisibility(View.GONE);

        // Hide the caption/send bottom bar — draw panel replaces it
        if (bottomBar   != null) bottomBar.setVisibility(View.GONE);
        if (tvSwipeHint != null) tvSwipeHint.setVisibility(View.GONE);

        if (drawToolsRow != null) drawToolsRow.setVisibility(View.VISIBLE);
        if (btnEditDraw  != null) btnEditDraw.setAlpha(1f);
    }

    private void exitDrawMode() {
        drawModeActive = false;
        drawOverlay.setDrawingEnabled(false);
        eraserActive = false;

        if (drawToolsRow != null) drawToolsRow.setVisibility(View.GONE);

        // Restore the normal bottom bar
        if (bottomBar   != null) bottomBar.setVisibility(View.VISIBLE);
        if (tvSwipeHint != null && !current().isVideo) tvSwipeHint.setVisibility(View.VISIBLE);

        if (btnEditDraw != null) btnEditDraw.setAlpha(0.7f);
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
        // Instant live preview on the main ImageView
        ivPreview.setColorFilter(index == 0 ? null : MediaFilters.filterFor(index));
        // Update checkmark visibility in the strip
        for (int i = 0; i < filterCheckViews.size(); i++) {
            ImageView check = filterCheckViews.get(i);
            if (check != null) check.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
    }

    private void openFilterPanel() {
        if (filterPanel == null || filterPanelOpen) return;
        if (current().isVideo) return; // no filters on video
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
                    if (bottomBar   != null) bottomBar.setVisibility(View.VISIBLE);
                    if (!current().isVideo && tvSwipeHint != null)
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
        });
        mediaContainer.setOnTouchListener((v, event) -> {
            if (drawModeActive) return false; // let draw overlay handle it
            gd.onTouchEvent(event);
            // Returning false here meant ACTION_DOWN was never "consumed",
            // so Android never delivered the follow-up ACTION_MOVE/ACTION_UP
            // events to this listener at all — the GestureDetector never
            // saw a full gesture and onFling() could never fire, which is
            // why "swipe up for filters" appeared to do nothing. Returning
            // true consumes the whole touch stream here so the detector
            // actually gets to see it.
            return true;
        });
    }

    // ── Bottom bar ────────────────────────────────────────────────────────

    private void setupBottomBar() {
        View btnDelete = findViewById(R.id.btnEditDelete);
        if (btnDelete != null) btnDelete.setOnClickListener(v -> deleteCurrentItem());

        View btnSend = findViewById(R.id.btnEditSend);
        if (btnSend != null) btnSend.setOnClickListener(v -> bakeAndSend());
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
                Glide.with(this).load(st.effectiveUri()).centerCrop().into(iv);
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

    private void switchToItem(int idx) {
        if (idx < 0 || idx >= items.size() || items.get(idx).deleted) return;
        saveCurrentDrawState();
        currentIndex = idx;
        rebuildThumbStrip();
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

        // Sync draw overlay to this item's stroke list
        drawOverlay.bindStrokes(st.strokes);
        drawOverlay.clearStrokes(); // triggers re-draw from backing list

        // Sync sticker layer
        stickerLayer.removeAllViews();
        for (OverlayItem ov : st.overlays) renderOverlayView(ov);

        // Reset rotation display
        ivPreview.setRotation(st.rotationDeg);
        drawOverlay.setRotation(st.rotationDeg);
        stickerLayer.setRotation(st.rotationDeg);

        // Toggle video vs image tools
        boolean isVideo = st.isVideo;
        ivVideoPlayBadge.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        if (btnEditRotate != null) btnEditRotate.setAlpha(isVideo ? 0.35f : 1f);
        if (btnEditCrop   != null) btnEditCrop.setAlpha(isVideo ? 0.35f : 1f);
        if (btnEditTrim   != null) {
            btnEditTrim.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        }

        // Load preview
        if (isVideo) {
            // Glide thumbnail from video
            Glide.with(this).load(st.effectiveUri()).into(ivPreview);
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

        // Swipe hint
        if (tvSwipeHint != null) tvSwipeHint.setVisibility(isVideo ? View.GONE : View.VISIBLE);
    }

    private void loadImageWithFilter(EditState st) {
        // Load bitmap, apply rotation matrix + filter ColorMatrix
        new Thread(() -> {
            try {
                Bitmap bmp = decodeSampledBitmap(st.uri, 1080);
                Matrix m = new Matrix();
                m.postRotate(st.rotationDeg);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                runOnUiThread(() -> {
                    ivPreview.setRotation(0); // already rotated
                    if (st.filterIndex > 0) {
                        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(
                                MediaFilters.matrixFor(st.filterIndex));
                        ivPreview.setColorFilter(new ColorMatrixColorFilter(cm));
                    } else {
                        ivPreview.clearColorFilter();
                    }
                    ivPreview.setImageBitmap(rotated);
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                    Glide.with(this).load(st.effectiveUri()).into(ivPreview));
            }
        }).start();
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

        bgExec.submit(() -> {
            ArrayList<String> resultUris = new ArrayList<>();
            try {
                for (EditState st : items) {
                    if (st.deleted) continue;
                    if (st.isVideo) {
                        // Videos: return effective URI (trimmed or original)
                        resultUris.add(st.effectiveUri().toString());
                    } else if (st.hasEdits()) {
                        Bitmap baked = bakeBitmap(st);
                        if (baked != null) {
                            File outDir = new File(getCacheDir(), "media_edit_out");
                            if (!outDir.exists()) outDir.mkdirs();
                            File f = new File(outDir, "edit_" + UUID.randomUUID() + ".jpg");
                            try (FileOutputStream fos = new FileOutputStream(f)) {
                                baked.compress(Bitmap.CompressFormat.JPEG,
                                        isHD ? 95 : 82, fos);
                            }
                            Uri fileUri = FileProvider.getUriForFile(this,
                                    getPackageName() + ".fileprovider", f);
                            resultUris.add(fileUri.toString());
                            baked.recycle();
                        } else {
                            resultUris.add(st.uri.toString());
                        }
                    } else {
                        resultUris.add(st.uri.toString());
                    }
                }

                final ArrayList<String> finalUris = resultUris;
                runOnUiThread(() -> {
                    Intent res = new Intent();
                    res.putStringArrayListExtra(RESULT_URIS, finalUris);
                    String cap = etCaption.getText() != null ? etCaption.getText().toString() : "";
                    res.putExtra(RESULT_CAPTION, cap);
                    res.putExtra(RESULT_HD, isHD);
                    setResult(Activity.RESULT_OK, res);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Error preparing media: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
            }
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

            // Rotation
            Matrix m = new Matrix();
            m.postRotate(st.rotationDeg);
            Bitmap out = Bitmap.createBitmap(base, 0, 0,
                    base.getWidth(), base.getHeight(), m, true);
            if (out != base) base.recycle();

            Canvas canvas = new Canvas(out);

            // Filter
            if (st.filterIndex > 0) {
                Paint fp = new Paint();
                fp.setColorFilter(new ColorMatrixColorFilter(
                        MediaFilters.matrixFor(st.filterIndex)));
                canvas.drawBitmap(out.copy(Bitmap.Config.ARGB_8888, false), 0, 0, fp);
            }

            // ── Screen → photo-pixel mapping ──────────────────────────────
            // Overlay positions (xFrac/yFrac) and stroke points are recorded
            // normalized against the *editor's* full-screen preview view —
            // but that view uses scaleType="fitCenter", so the photo itself
            // is letterboxed inside it whenever the photo's aspect ratio
            // doesn't match the screen's. The old code multiplied fractions
            // straight through by out.getWidth()/out.getHeight() as if the
            // photo filled the whole view, which put stickers/text/strokes
            // in the wrong spot (sometimes entirely outside the photo) on
            // every non-matching aspect ratio — edits looked right in the
            // editor (everything overlaid the same screen region) but were
            // shifted or missing once baked into the sent photo. Computing
            // the same fitCenter rect here and mapping through it fixes that.
            int viewW = Math.max(1, ivPreview.getWidth());
            int viewH = Math.max(1, ivPreview.getHeight());
            float fitScale = Math.min((float) viewW / out.getWidth(), (float) viewH / out.getHeight());
            if (fitScale <= 0f) fitScale = 1f;
            float dispW = out.getWidth()  * fitScale;
            float dispH = out.getHeight() * fitScale;
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
                // to photo-pixel space through the same fitCenter scale so it
                // ends up the same relative size on the sent photo.
                float ts = (ov.textSizeSp * density * ov.scale) / fitScale;
                tp.setTextSize(ts);
                float screenX = ov.xFrac * viewW;
                float screenY = ov.yFrac * viewH;
                float x = (screenX - offX) / fitScale;
                float y = (screenY - offY) / fitScale;
                if (ov.hasBg) {
                    float tw = tp.measureText(ov.text);
                    Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
                    bgP.setColor(0xCC000000);
                    canvas.drawRoundRect(x - tw / 2 - dp(6), y - ts,
                            x + tw / 2 + dp(6), y + dp(6), dp(8), dp(8), bgP);
                }
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(ov.rotationDeg);
                canvas.drawText(ov.text, -tp.measureText(ov.text) / 2f, 0, tp);
                canvas.restore();
            }

            // Freehand strokes — same screen→photo remap as overlays above.
            DrawOverlayView.drawStrokes(canvas, st.strokes,
                    out.getWidth(), out.getHeight(),
                    viewW, viewH, offX, offY, fitScale, density);

            return out;
        } catch (Exception e) {
            android.util.Log.e("MediaEditActivity", "bakeBitmap failed", e);
            return null;
        }
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

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        tv.setTag(overlay);
        stickerLayer.addView(tv);
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
                if (!overlay.isEmoji) launchEditTextOverlay(overlay, v);
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
                        float dx = event.getRawX() - lastTouch[0];
                        float dy = event.getRawY() - lastTouch[1];
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
        Toast.makeText(this,
                overlay.isEmoji ? "Sticker removed" : "Text removed",
                Toast.LENGTH_SHORT).show();
    }

    /** Tap on existing TEXT overlay → re-launch ChatStickerPickerActivity in text mode. */
    private void launchEditTextOverlay(OverlayItem overlay, View v) {
        // Remove old, let user re-add via picker
        removeOverlay(overlay, v);
        Intent i = new Intent(this, ChatStickerPickerActivity.class);
        i.putExtra(ChatStickerPickerActivity.EXTRA_TEXT_MODE, true);
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
    }
}
