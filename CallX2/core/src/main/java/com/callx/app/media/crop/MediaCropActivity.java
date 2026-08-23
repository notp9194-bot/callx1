package com.callx.app.media.crop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.media3.common.util.UnstableApi;

import com.callx.app.core.R;
import com.callx.app.media.VideoCropTransformer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaCropActivity — WhatsApp-grade interactive image crop screen.
 *
 * Lives in :core so ANY feature module (chat, status, reels, calls avatar,
 * etc.) can launch it via className-based Intent without creating a
 * circular module dependency — the same pattern used for ReelCameraActivity.
 *
 * UX model (mirrors WhatsApp exactly):
 *  • Image is panned/pinch-zoomed with one or two fingers.
 *  • The crop frame stays fixed on screen; drag its corner or edge handles
 *    to resize the crop box.
 *  • Image always fills the crop box — no black gaps ever visible inside it.
 *  • Rotate 90° button rotates the image CW in-place.
 *  • Aspect ratio chips: Free / 1:1 / 4:3 / 3:4 / 16:9 / 9:16.
 *  • Rule-of-thirds grid appears while a crop handle is being dragged.
 *
 * Returns a full-resolution cropped JPEG URI via FileProvider on RESULT_OK.
 *
 * Usage from any feature module (still image):
 * <pre>
 *   Intent i = new Intent();
 *   i.setClassName(getPackageName(), "com.callx.app.media.crop.MediaCropActivity");
 *   i.putExtra(MediaCropActivity.EXTRA_IMAGE_URI, sourceUri.toString());
 *   launcher.launch(i);
 *   // onResult: data.getStringExtra(MediaCropActivity.RESULT_CROPPED_URI)
 * </pre>
 *
 * ✅ NEW: Video crop. Pass {@link #EXTRA_VIDEO_URI} instead of
 * {@link #EXTRA_IMAGE_URI} and the exact same screen (same
 * {@link CropOverlayView}, same aspect-ratio chips, same drag handles) is
 * used to frame the crop box on a representative preview frame; on Done the
 * crop is applied to the *entire* video via {@link VideoCropTransformer}
 * (Media3 Transformer), and a re-encoded, permanently-cropped .mp4 is
 * returned through the same {@link #RESULT_CROPPED_URI} extra. Only one of
 * EXTRA_IMAGE_URI / EXTRA_VIDEO_URI should be supplied per call. Callers:
 *   - feature-reels ReelEditorActivity (Step 1 · Trim and Crop)
 *   - feature-chat MediaEditActivity (Crop tool, image AND video)
 * <pre>
 *   i.putExtra(MediaCropActivity.EXTRA_VIDEO_URI, sourceVideoUri.toString());
 * </pre>
 */
@UnstableApi
public class MediaCropActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI    = "media_crop_uri";
    public static final String EXTRA_VIDEO_URI    = "media_crop_video_uri";
    public static final String RESULT_CROPPED_URI = "media_crop_result_uri";

    // ── Aspect ratio presets ──────────────────────────────────────────────
    private static final float[] RATIOS = { 0f, 1f, 4f/3f, 3f/4f, 16f/9f, 9f/16f };
    private static final String[] LABELS = { "Free", "1:1", "4:3", "3:4", "16:9", "9:16" };

    // ── Views ─────────────────────────────────────────────────────────────
    private CropOverlayView cropView;
    private TextView        btnDone, btnCancel;
    private LinearLayout    aspectRow;
    private View            btnRotate;
    private TextView        tvAspectHint;

    // ── State ─────────────────────────────────────────────────────────────
    private Uri     sourceUri;
    private Bitmap  sourceBitmap;
    private int     selectedAspect = 0;   // index into RATIOS
    /** ✅ NEW: true when launched with EXTRA_VIDEO_URI — the crop box is set on a
     *  preview frame but Done re-encodes the whole video instead of saving a still. */
    private boolean isVideoMode = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExec = Executors.newSingleThreadExecutor();

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_crop);

        String videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        String imageUriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (videoUriStr != null) {
            isVideoMode = true;
            sourceUri = Uri.parse(videoUriStr);
        } else if (imageUriStr != null) {
            sourceUri = Uri.parse(imageUriStr);
        } else {
            finish(); return;
        }

        bindViews();
        setupButtons();
        buildAspectRow();
        loadBitmapAsync();
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews() {
        cropView     = findViewById(R.id.media_crop_view);
        btnDone      = findViewById(R.id.media_crop_btn_done);
        btnCancel    = findViewById(R.id.media_crop_btn_cancel);
        aspectRow    = findViewById(R.id.media_crop_aspect_row);
        btnRotate    = findViewById(R.id.media_crop_btn_rotate);
        tvAspectHint = findViewById(R.id.media_crop_aspect_label);
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private void setupButtons() {
        btnCancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        btnDone.setOnClickListener(v -> {
            if (sourceBitmap == null) return;
            btnDone.setEnabled(false);
            btnDone.setText(isVideoMode ? "Cropping video…" : "Saving…");
            if (isVideoMode) cropVideoAndReturn();
            else doCropAndReturn();
        });

        if (btnRotate != null) {
            // ✅ Rotate stays image-only: it rotates the still preview bitmap in
            // place, which has no meaningful counterpart for a full video crop
            // export, so it's hidden rather than silently doing nothing useful.
            if (isVideoMode) {
                btnRotate.setVisibility(View.GONE);
            } else {
                btnRotate.setOnClickListener(v -> {
                    cropView.rotate90();
                    // After rotation, update sourceBitmap reference so getCroppedBitmap bakes from latest
                    // (rotate90 handles this internally via bitmap recycle+replace)
                    sourceBitmap = null; // getCroppedBitmap uses cropView's internal bitmap
                });
            }
        }
    }

    // ── Aspect ratio chips ────────────────────────────────────────────────

    private void buildAspectRow() {
        if (aspectRow == null) return;
        aspectRow.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        for (int i = 0; i < LABELS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(LABELS[i]);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(13f);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding((int)(16*dp), (int)(8*dp), (int)(16*dp), (int)(8*dp));
            chip.setBackground(getDrawable(i == selectedAspect
                    ? R.drawable.chip_selected : R.drawable.chip_unselected));

            chip.setOnClickListener(v -> {
                if (selectedAspect == idx) return;
                selectedAspect = idx;
                cropView.setAspectRatio(RATIOS[idx]);
                refreshChipSelection();
                if (tvAspectHint != null) tvAspectHint.setText(LABELS[idx]);
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int)(8*dp));
            chip.setLayoutParams(lp);
            aspectRow.addView(chip);
        }
    }

    private void refreshChipSelection() {
        if (aspectRow == null) return;
        for (int i = 0; i < aspectRow.getChildCount(); i++) {
            View v = aspectRow.getChildAt(i);
            if (v instanceof TextView) {
                v.setBackground(getDrawable(i == selectedAspect
                        ? R.drawable.chip_selected : R.drawable.chip_unselected));
            }
        }
    }

    // ── Bitmap loading ────────────────────────────────────────────────────

    private void loadBitmapAsync() {
        btnDone.setEnabled(false);
        if (isVideoMode) {
            loadVideoPreviewFrameAsync();
            return;
        }
        bgExec.submit(() -> {
            try {
                // Decode at max 2K — no need for full original res in the crop view
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (java.io.InputStream in = getContentResolver().openInputStream(sourceUri)) {
                    BitmapFactory.decodeStream(in, null, opts);
                }
                int maxDim = 2048;
                int sample = 1;
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                Bitmap bmp;
                try (java.io.InputStream in = getContentResolver().openInputStream(sourceUri)) {
                    bmp = BitmapFactory.decodeStream(in, null, opts);
                }
                if (bmp == null) throw new Exception("Decode failed");
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    sourceBitmap = finalBmp;
                    cropView.setBitmap(finalBmp);
                    btnDone.setEnabled(true);
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                    Toast.makeText(this, "Could not load image: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * ✅ NEW: video-mode preview. Grabs one representative frame (via the same
     * MediaMetadataRetriever approach used elsewhere in the app, e.g. Reels'
     * thumbnail-frame extraction) at the video's own resolution and feeds it
     * into {@link #cropView} exactly like a decoded photo — same setBitmap()
     * call, same aspect chips, same drag handles. Only the Done action differs
     * (see {@link #cropVideoAndReturn()}).
     */
    private void loadVideoPreviewFrameAsync() {
        bgExec.submit(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, sourceUri);
                long durationMs = 0;
                try {
                    String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) durationMs = Long.parseLong(d);
                } catch (Exception ignored) {}
                long frameAtUs = (durationMs > 0) ? (durationMs * 1000L) / 3 : 0; // ~1/3 in — usually representative
                Bitmap bmp = mmr.getFrameAtTime(frameAtUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp == null) bmp = mmr.getFrameAtTime(0);
                if (bmp == null) throw new Exception("Could not extract a preview frame");
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    sourceBitmap = finalBmp;
                    cropView.setBitmap(finalBmp);
                    btnDone.setEnabled(true);
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                    Toast.makeText(this, "Could not load video: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        });
    }

    // ── Crop + save ───────────────────────────────────────────────────────

    private void doCropAndReturn() {
        bgExec.submit(() -> {
            try {
                // getCroppedBitmap uses cropView's internal bitmap + imageMatrix
                Bitmap cropped = cropView.getCroppedBitmap();
                if (cropped == null) throw new Exception("Crop region invalid");

                File dir = new File(getCacheDir(), "media_crop");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, "crop_" + UUID.randomUUID() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    cropped.compress(Bitmap.CompressFormat.JPEG, 93, fos);
                }
                cropped.recycle();

                Uri resultUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", out);

                mainHandler.post(() -> {
                    Intent res = new Intent();
                    res.putExtra(RESULT_CROPPED_URI, resultUri.toString());
                    setResult(Activity.RESULT_OK, res);
                    finish();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Crop failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    btnDone.setEnabled(true);
                    btnDone.setText("Done");
                });
            }
        });
    }

    /**
     * ✅ NEW: video-mode Done action. Reads the same normalized crop box the
     * still-image path would have used ({@link CropOverlayView#getCropRectNormalized()})
     * and hands it to {@link VideoCropTransformer}, which re-encodes the
     * *entire* source video cropped to that region (Media3 Transformer +
     * Crop effect) — this is a real crop of the video itself, not just a
     * cropped thumbnail frame.
     */
    private void cropVideoAndReturn() {
        RectF cropFraction = cropView.getCropRectNormalized();
        VideoCropTransformer.cropVideo(this, sourceUri, cropFraction, new VideoCropTransformer.Callback() {
            @Override public void onProgress(int percent) {
                if (percent >= 0) btnDone.setText("Cropping video… " + percent + "%");
            }

            @Override public void onSuccess(Uri outputUri) {
                Intent res = new Intent();
                res.putExtra(RESULT_CROPPED_URI, outputUri.toString());
                setResult(Activity.RESULT_OK, res);
                finish();
            }

            @Override public void onError(Exception e) {
                Toast.makeText(MediaCropActivity.this, "Video crop failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                btnDone.setEnabled(true);
                btnDone.setText("Done");
            }
        });
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExec.shutdownNow();
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();
    }
}
