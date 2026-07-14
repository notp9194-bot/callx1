package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen chat-attach photo editor, opened from the attach sheet's new
 * "Edit" action (see AttachSheetRecentMediaBinder / ChatMediaController /
 * GroupChatActivity). Matches the reference "Screenshot 2" full-screen
 * editor: top toolbar (close, download, HD, rotate, sticker, text, draw),
 * bottom thumbnail strip + delete + caption + send, and a swipe-up gesture
 * that reveals the filter carousel from "Screenshot 3" (None/Pop/B&W/Cool/
 * Chrome/Film).
 *
 * Each selected item keeps its own {@link EditState} (rotation, filter,
 * stickers, freehand strokes) so scrubbing the thumbnail strip between
 * multiple photos doesn't lose edits already made on the others. Videos
 * pass through untouched — the editor shows them read-only (tools
 * disabled) so they can still be reordered/deleted/captioned alongside
 * edited photos, per the reference flow.
 *
 * On send, every edited image is baked (rotation + filter + stickers/text
 * + drawing) into a new JPEG in the app cache dir and re-exposed via the
 * app's existing FileProvider authority so the normal upload pipeline
 * (uploadSequentially) can pick it up exactly like any other content URI.
 */
public class MediaEditActivity extends AppCompatActivity {

    public static final String EXTRA_URIS = "media_edit_uris";
    public static final String EXTRA_IS_VIDEO = "media_edit_is_video";
    public static final String EXTRA_CAPTION = "media_edit_caption";
    public static final String EXTRA_HD = "media_edit_hd";

    public static final String RESULT_URIS = "media_edit_result_uris";
    public static final String RESULT_CAPTION = "media_edit_result_caption";
    public static final String RESULT_HD = "media_edit_result_hd";

    /** One sticker/text overlay placed on the photo. */
    private static final class OverlayItem {
        String text;
        boolean isEmoji;
        int color = Color.WHITE;
        float xFrac = 0.5f, yFrac = 0.45f;
        float scale = 1f;
        float rotationDeg = 0f;
        float textSizeSp = 30f;
    }

    /** Per-selected-item edit state, indexed 1:1 with {@link #uris}. */
    private static final class EditState {
        Uri uri;
        boolean isVideo;
        boolean deleted = false;
        int rotationDeg = 0;
        int filterIndex = 0; // into MediaFilters.NAMES
        final List<OverlayItem> overlays = new ArrayList<>();
        final List<DrawOverlayView.Stroke> strokes = new ArrayList<>();

        boolean hasEdits() {
            return rotationDeg != 0 || filterIndex != 0 || !overlays.isEmpty() || !strokes.isEmpty();
        }
    }

    private final List<EditState> items = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isHD = false;
    private boolean drawModeActive = false;

    private ImageView ivPreview;
    private ImageView ivVideoPlayBadge;
    private FrameLayout stickerLayer;
    private DrawOverlayView drawOverlay;
    private ImageButton btnEditRotate, btnEditSticker, btnEditText, btnEditDraw, btnEditDownload;
    private TextView btnEditHd;
    private HorizontalScrollView emojiRowScroll;
    private LinearLayout emojiRowContent, thumbStripContent, filterStripContent, drawColorRow;
    private View emojiRow, drawToolsRow, filterPanel, bottomBar, tvSwipeHint;
    private EditText etCaption;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_edit);

        ArrayList<String> uriStrings = getIntent().getStringArrayListExtra(EXTRA_URIS);
        ArrayList<Integer> videoFlags = getIntent().getIntegerArrayListExtra(EXTRA_IS_VIDEO);
        if (uriStrings == null || uriStrings.isEmpty()) {
            finish();
            return;
        }
        for (int i = 0; i < uriStrings.size(); i++) {
            EditState st = new EditState();
            st.uri = Uri.parse(uriStrings.get(i));
            st.isVideo = videoFlags != null && i < videoFlags.size() && videoFlags.get(i) == 1;
            items.add(st);
        }
        isHD = getIntent().getBooleanExtra(EXTRA_HD, false);

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

    private void bindViews() {
        ivPreview = findViewById(R.id.ivPreview);
        ivVideoPlayBadge = findViewById(R.id.ivVideoPlayBadge);
        stickerLayer = findViewById(R.id.stickerLayer);
        drawOverlay = findViewById(R.id.drawOverlay);
        btnEditRotate = findViewById(R.id.btnEditRotate);
        btnEditSticker = findViewById(R.id.btnEditSticker);
        btnEditText = findViewById(R.id.btnEditText);
        btnEditDraw = findViewById(R.id.btnEditDraw);
        btnEditDownload = findViewById(R.id.btnEditDownload);
        btnEditHd = findViewById(R.id.btnEditHd);
        emojiRow = findViewById(R.id.emojiRow);
        emojiRowScroll = (HorizontalScrollView) emojiRow;
        emojiRowContent = findViewById(R.id.emojiRowContent);
        drawToolsRow = findViewById(R.id.drawToolsRow);
        drawColorRow = findViewById(R.id.drawColorRow);
        filterPanel = findViewById(R.id.filterPanel);
        filterStripContent = findViewById(R.id.filterStripContent);
        bottomBar = findViewById(R.id.bottomBar);
        thumbStripContent = findViewById(R.id.thumbStripContent);
        etCaption = findViewById(R.id.etCaption);
        tvSwipeHint = findViewById(R.id.tvSwipeHint);
    }

    // ── Top toolbar ──────────────────────────────────────────────────────

    private void setupTopToolbar() {
        findViewById(R.id.btnEditClose).setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        btnEditDownload.setOnClickListener(v -> downloadCurrent());

        refreshHdButton();
        btnEditHd.setOnClickListener(v -> {
            isHD = !isHD;
            refreshHdButton();
        });

        btnEditRotate.setOnClickListener(v -> {
            EditState st = current();
            if (st.isVideo) return;
            st.rotationDeg = (st.rotationDeg + 90) % 360;
            ivPreview.setRotation(st.rotationDeg);
        });

        btnEditSticker.setOnClickListener(v -> {
            closeDrawMode();
            boolean showing = emojiRow.getVisibility() == View.VISIBLE;
            emojiRow.setVisibility(showing ? View.GONE : View.VISIBLE);
        });

        btnEditText.setOnClickListener(v -> {
            closeDrawMode();
            emojiRow.setVisibility(View.GONE);
            promptForTextOverlay();
        });

        btnEditDraw.setOnClickListener(v -> {
            emojiRow.setVisibility(View.GONE);
            toggleDrawMode();
        });
    }

    private void refreshHdButton() {
        btnEditHd.setBackgroundResource(isHD
                ? R.drawable.bg_hd_toggle_active
                : R.drawable.bg_hd_toggle_inactive);
    }

    // ── Emoji sticker tool ───────────────────────────────────────────────

    private static final String[] EMOJIS = {
            "😀", "😂", "😍", "🔥", "❤️", "👍", "🎉", "😎", "😭", "🥳", "💯", "✨"
    };

    private void setupEmojiRow() {
        for (String emoji : EMOJIS) {
            TextView tv = new TextView(this);
            tv.setText(emoji);
            tv.setTextSize(28f);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setOnClickListener(v -> {
                addOverlay(emoji, true, Color.WHITE);
                emojiRow.setVisibility(View.GONE);
            });
            emojiRowContent.addView(tv);
        }
    }

    // ── Text tool ────────────────────────────────────────────────────────

    private static final int[] TEXT_COLORS = {
            Color.WHITE, Color.BLACK, Color.RED, Color.YELLOW, Color.parseColor("#25D366"), Color.parseColor("#1D9BF0")
    };

    private void promptForTextOverlay() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(4));

        final EditText input = new EditText(this);
        input.setHint("Type something...");
        input.setTextSize(18f);
        container.addView(input);

        final int[] chosenColor = {Color.WHITE};
        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setPadding(0, dp(14), 0, 0);
        List<View> swatchViews = new ArrayList<>();
        for (int color : TEXT_COLORS) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.setMarginEnd(dp(10));
            dot.setLayoutParams(lp);
            dot.setBackgroundColor(color);
            dot.setOnClickListener(v -> {
                chosenColor[0] = color;
                for (View sv : swatchViews) sv.setAlpha(sv == v ? 1f : 0.4f);
            });
            dot.setAlpha(color == Color.WHITE ? 1f : 0.4f);
            swatchViews.add(dot);
            swatches.addView(dot);
        }
        container.addView(swatches);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add text")
                .setView(container)
                .setPositiveButton("Add", (dialog, which) -> {
                    String text = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!text.isEmpty()) addOverlay(text, false, chosenColor[0]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Sticker/text overlay placement + drag/pinch ─────────────────────

    private void addOverlay(String text, boolean isEmoji, int color) {
        OverlayItem overlay = new OverlayItem();
        overlay.text = text;
        overlay.isEmoji = isEmoji;
        overlay.color = color;
        current().overlays.add(overlay);
        renderOverlayView(overlay);
    }

    private void renderOverlayView(OverlayItem overlay) {
        TextView tv = new TextView(this);
        tv.setText(overlay.text);
        tv.setTextColor(overlay.color);
        tv.setTextSize(overlay.textSizeSp);
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        tv.setTag(overlay);
        stickerLayer.addView(tv);
        tv.post(() -> positionOverlayView(tv, overlay));
        attachDragAndPinch(tv, overlay);
    }

    private void positionOverlayView(View v, OverlayItem overlay) {
        int parentW = stickerLayer.getWidth();
        int parentH = stickerLayer.getHeight();
        if (parentW == 0 || parentH == 0) return;
        v.setX(overlay.xFrac * parentW - v.getWidth() / 2f);
        v.setY(overlay.yFrac * parentH - v.getHeight() / 2f);
        v.setScaleX(overlay.scale);
        v.setScaleY(overlay.scale);
        v.setRotation(overlay.rotationDeg);
    }

    /** Single-finger drag to move, two-finger pinch to scale + rotate — same UX pattern used by ReelPhotoEditorActivity's stickers. */
    private void attachDragAndPinch(View v, OverlayItem overlay) {
        final float[] lastTouch = new float[2];
        final float[] startDist = {0f};
        final float[] startScale = {overlay.scale};
        final float[] startAngle = {0f};
        final float[] startRotation = {overlay.rotationDeg};

        v.setOnTouchListener((view, event) -> {
            int parentW = stickerLayer.getWidth();
            int parentH = stickerLayer.getHeight();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        startDist[0] = fingerDistance(event);
                        startAngle[0] = fingerAngle(event);
                        startScale[0] = overlay.scale;
                        startRotation[0] = overlay.rotationDeg;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 2 && startDist[0] > 0) {
                        float dist = fingerDistance(event);
                        float scale = startScale[0] * (dist / startDist[0]);
                        overlay.scale = Math.max(0.3f, Math.min(scale, 4f));
                        float angle = fingerAngle(event);
                        overlay.rotationDeg = startRotation[0] + (angle - startAngle[0]);
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
                        if (parentW > 0 && parentH > 0) {
                            overlay.xFrac = (view.getX() + view.getWidth() / 2f) / parentW;
                            overlay.yFrac = (view.getY() + view.getHeight() / 2f) / parentH;
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (parentW > 0 && parentH > 0) {
                        overlay.xFrac = (view.getX() + view.getWidth() / 2f) / parentW;
                        overlay.yFrac = (view.getY() + view.getHeight() / 2f) / parentH;
                    }
                    return true;
            }
            return false;
        });
    }

    private static float fingerDistance(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float fingerAngle(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    // ── Draw (pencil) tool ───────────────────────────────────────────────

    private void setupDrawTools() {
        for (int color : TEXT_COLORS) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.setMarginEnd(dp(10));
            dot.setLayoutParams(lp);
            dot.setBackgroundColor(color);
            dot.setOnClickListener(v -> {
                drawOverlay.setActiveColor(color);
                for (int i = 0; i < drawColorRow.getChildCount(); i++) {
                    drawColorRow.getChildAt(i).setAlpha(drawColorRow.getChildAt(i) == v ? 1f : 0.4f);
                }
            });
            dot.setAlpha(color == Color.RED ? 1f : 0.4f);
            drawColorRow.addView(dot);
        }
        drawOverlay.setActiveColor(Color.RED);

        findViewById(R.id.btnDrawUndo).setOnClickListener(v -> drawOverlay.undoLastStroke());
        findViewById(R.id.btnDrawDone).setOnClickListener(v -> closeDrawMode());
    }

    private void toggleDrawMode() {
        drawModeActive = !drawModeActive;
        drawOverlay.setDrawingEnabled(drawModeActive);
        drawToolsRow.setVisibility(drawModeActive ? View.VISIBLE : View.GONE);
        btnEditDraw.setBackgroundResource(drawModeActive
                ? R.drawable.bg_media_edit_toolbtn_active
                : R.drawable.bg_media_edit_toolbtn);
    }

    private void closeDrawMode() {
        if (drawModeActive) toggleDrawMode();
    }

    // ── Filter carousel (swipe-up panel) ─────────────────────────────────

    private final List<ImageView> filterCheckViews = new ArrayList<>();

    private void setupFilterPanel() {
        for (int i = 0; i < MediaFilters.NAMES.length; i++) {
            View row = getLayoutInflater().inflate(R.layout.item_media_edit_filter, filterStripContent, false);
            ImageView thumb = row.findViewById(R.id.ivFilterThumb);
            ImageView check = row.findViewById(R.id.ivFilterCheck);
            TextView label = row.findViewById(R.id.tvFilterName);
            label.setText(MediaFilters.NAMES[i]);
            filterCheckViews.add(check);
            final int index = i;
            row.setOnClickListener(v -> applyFilter(index));
            filterStripContent.addView(row);
        }
        findViewById(R.id.btnFilterCollapse).setOnClickListener(v -> closeFilterPanel());
    }

    private void refreshFilterThumbs() {
        Uri uri = current().uri;
        for (int i = 0; i < filterCheckViews.size(); i++) {
            View row = filterStripContent.getChildAt(i);
            ImageView thumb = row.findViewById(R.id.ivFilterThumb);
            thumb.setColorFilter(MediaFilters.filterFor(i));
            Glide.with(this).load(uri).centerCrop().into(thumb);
            filterCheckViews.get(i).setVisibility(i == current().filterIndex ? View.VISIBLE : View.GONE);
        }
    }

    private void applyFilter(int index) {
        EditState st = current();
        if (st.isVideo) return;
        st.filterIndex = index;
        ivPreview.setColorFilter(index == 0 ? null : MediaFilters.filterFor(index));
        for (int i = 0; i < filterCheckViews.size(); i++) {
            filterCheckViews.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
    }

    private boolean filterPanelOpen = false;

    private void openFilterPanel() {
        if (current().isVideo || filterPanelOpen) return;
        filterPanelOpen = true;
        refreshFilterThumbs();
        filterPanel.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.GONE);
        tvSwipeHint.setVisibility(View.GONE);
        filterPanel.setTranslationY(filterPanel.getHeight() > 0 ? filterPanel.getHeight() : dp(220));
        filterPanel.animate().translationY(0).setDuration(220).start();
    }

    private void closeFilterPanel() {
        if (!filterPanelOpen) return;
        filterPanelOpen = false;
        filterPanel.animate().translationY(filterPanel.getHeight() > 0 ? filterPanel.getHeight() : dp(220))
                .setDuration(200)
                .withEndAction(() -> {
                    filterPanel.setVisibility(View.INVISIBLE);
                    bottomBar.setVisibility(View.VISIBLE);
                    if (!current().isVideo) tvSwipeHint.setVisibility(View.VISIBLE);
                })
                .start();
    }

    private void setupFilterSwipeGesture() {
        View mediaContainer = findViewById(R.id.mediaContainer);
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null) return false;
                float deltaY = e2.getY() - e1.getY();
                if (!filterPanelOpen && deltaY < -80 && velocityY < -400) {
                    openFilterPanel();
                    return true;
                }
                if (filterPanelOpen && deltaY > 80 && velocityY > 400) {
                    closeFilterPanel();
                    return true;
                }
                return false;
            }
        });
        mediaContainer.setOnTouchListener((v, event) -> {
            // Let the gesture detector see every touch, but never consume it —
            // stickers/draw overlay above it must keep receiving their own
            // events for drag/pinch/draw to keep working.
            detector.onTouchEvent(event);
            return false;
        });
    }

    // ── Bottom bar: delete / thumbnail strip / caption / send ───────────

    private void setupBottomBar() {
        findViewById(R.id.btnEditDelete).setOnClickListener(v -> deleteCurrent());
        findViewById(R.id.btnEditSend).setOnClickListener(v -> onSend());
    }

    private void rebuildThumbStrip() {
        thumbStripContent.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            EditState st = items.get(i);
            if (st.deleted) continue;
            View row = getLayoutInflater().inflate(R.layout.item_media_edit_thumb, thumbStripContent, false);
            ImageView ivThumb = row.findViewById(R.id.ivThumb);
            View videoBadge = row.findViewById(R.id.ivThumbVideoBadge);
            Glide.with(this).load(st.uri).centerCrop().into(ivThumb);
            videoBadge.setVisibility(st.isVideo ? View.VISIBLE : View.GONE);
            row.setBackgroundResource(0);
            ivThumb.setAlpha(i == currentIndex ? 1f : 0.55f);
            final int index = i;
            row.setOnClickListener(v -> {
                currentIndex = index;
                showCurrentItem();
                rebuildThumbStrip();
            });
            row.findViewById(R.id.btnThumbRemove).setOnClickListener(v -> deleteItemAt(index));
            thumbStripContent.addView(row);
        }
    }

    private EditState current() {
        return items.get(currentIndex);
    }

    private void showCurrentItem() {
        EditState st = current();
        closeFilterPanel();
        closeDrawMode();
        emojiRow.setVisibility(View.GONE);

        Glide.with(this).load(st.uri).into(ivPreview);
        ivPreview.setRotation(st.rotationDeg);
        ivPreview.setColorFilter(st.filterIndex == 0 ? null : MediaFilters.filterFor(st.filterIndex));

        ivVideoPlayBadge.setVisibility(st.isVideo ? View.VISIBLE : View.GONE);
        boolean toolsEnabled = !st.isVideo;
        btnEditRotate.setEnabled(toolsEnabled);
        btnEditSticker.setEnabled(toolsEnabled);
        btnEditText.setEnabled(toolsEnabled);
        btnEditDraw.setEnabled(toolsEnabled);
        float toolsAlpha = toolsEnabled ? 1f : 0.35f;
        btnEditRotate.setAlpha(toolsAlpha);
        btnEditSticker.setAlpha(toolsAlpha);
        btnEditText.setAlpha(toolsAlpha);
        btnEditDraw.setAlpha(toolsAlpha);
        tvSwipeHint.setVisibility(toolsEnabled ? View.VISIBLE : View.GONE);

        stickerLayer.removeAllViews();
        for (OverlayItem overlay : st.overlays) renderOverlayView(overlay);
        drawOverlay.setStrokes(st.strokes);
    }

    private void deleteCurrent() {
        deleteItemAt(currentIndex);
    }

    private void deleteItemAt(int index) {
        items.get(index).deleted = true;
        int remaining = 0;
        for (EditState st : items) if (!st.deleted) remaining++;
        if (remaining == 0) {
            Toast.makeText(this, "Nothing left to send", Toast.LENGTH_SHORT).show();
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        if (index == currentIndex || current().deleted) {
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).deleted) { currentIndex = i; break; }
            }
            showCurrentItem();
        }
        rebuildThumbStrip();
    }

    // ── Download (save current edited photo to device) ──────────────────

    private void downloadCurrent() {
        EditState st = current();
        if (st.isVideo) {
            Toast.makeText(this, "Videos aren't edited here — nothing to save", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Bitmap baked = bakeBitmap(st, 2048);
            String fileName = "CallX_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CallX");
            }
            Uri outUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (outUri == null) throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                baked.compress(Bitmap.CompressFormat.JPEG, 92, os);
            }
            Toast.makeText(this, "Saved to Pictures/CallX", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Send: bake edits + return result ─────────────────────────────────

    private void onSend() {
        ArrayList<String> resultUris = new ArrayList<>();
        try {
            for (EditState st : items) {
                if (st.deleted) continue;
                if (st.isVideo || !st.hasEdits()) {
                    resultUris.add(st.uri.toString());
                    continue;
                }
                Bitmap baked = bakeBitmap(st, isHD ? 2560 : 1600);
                File outFile = new File(getCacheDir(), "media_edit_" + System.currentTimeMillis() + "_" + resultUris.size() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    baked.compress(Bitmap.CompressFormat.JPEG, isHD ? 95 : 88, fos);
                }
                Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outFile);
                resultUris.add(contentUri.toString());
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't prepare edited photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (resultUris.isEmpty()) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        Intent data = new Intent();
        data.putStringArrayListExtra(RESULT_URIS, resultUris);
        data.putExtra(RESULT_CAPTION, etCaption.getText() != null ? etCaption.getText().toString() : "");
        data.putExtra(RESULT_HD, isHD);
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    /**
     * Renders one item's final look — original bitmap + rotation + filter +
     * stickers/text + freehand drawing — at up to maxDimension px on its
     * longest side (downsampled first via inSampleSize to avoid decoding
     * a huge source bitmap just to shrink it back down again).
     */
    private Bitmap bakeBitmap(EditState st, int maxDimension) throws Exception {
        Bitmap source = decodeSampledBitmap(st.uri, maxDimension);
        Bitmap rotated = source;
        if (st.rotationDeg != 0) {
            Matrix m = new Matrix();
            m.postRotate(st.rotationDeg);
            rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
            if (rotated != source) source.recycle();
        }

        Bitmap out = rotated.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);

        if (st.filterIndex != 0) {
            Paint filterPaint = new Paint();
            filterPaint.setColorFilter(new ColorMatrixColorFilter(MediaFilters.matrixFor(st.filterIndex)));
            Bitmap filtered = out.copy(Bitmap.Config.ARGB_8888, true);
            canvas.drawBitmap(filtered, 0, 0, filterPaint);
        }

        float density = getResources().getDisplayMetrics().density;
        for (OverlayItem overlay : st.overlays) {
            Paint textPaint = new Paint();
            textPaint.setAntiAlias(true);
            textPaint.setColor(overlay.color);
            textPaint.setTextSize(overlay.textSizeSp * density * overlay.scale * (out.getWidth() / (float) Math.max(1, ivPreview.getWidth())));
            float x = overlay.xFrac * out.getWidth();
            float y = overlay.yFrac * out.getHeight();
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(overlay.rotationDeg);
            canvas.drawText(overlay.text, -textPaint.measureText(overlay.text) / 2f, 0, textPaint);
            canvas.restore();
        }

        float strokeScale = out.getWidth() / (float) Math.max(1, ivPreview.getWidth()) * density;
        DrawOverlayView.drawStrokes(canvas, st.strokes, out.getWidth(), out.getHeight(), strokeScale);

        return out;
    }

    private Bitmap decodeSampledBitmap(Uri uri, int maxDimension) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while ((bounds.outWidth / sample) > maxDimension || (bounds.outHeight / sample) > maxDimension) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            if (bmp == null) throw new IllegalStateException("decode failed");
            return bmp;
        }
    }

    private int dp(int value) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (int) (value * dm.density);
    }
}
