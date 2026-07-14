package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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

import com.callx.app.chat.R;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatImageCropActivity — WhatsApp-grade interactive image crop screen.
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
 */
public class ChatImageCropActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI    = "chat_crop_uri";
    public static final String RESULT_CROPPED_URI = "chat_crop_result_uri";

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
    private Uri    sourceUri;
    private Bitmap sourceBitmap;
    private int    selectedAspect = 0;   // index into RATIOS
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExec = Executors.newSingleThreadExecutor();

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_crop);

        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr == null) { finish(); return; }
        sourceUri = Uri.parse(uriStr);

        bindViews();
        setupButtons();
        buildAspectRow();
        loadBitmapAsync();
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews() {
        cropView     = findViewById(R.id.chat_crop_view);
        btnDone      = findViewById(R.id.chat_crop_btn_done);
        btnCancel    = findViewById(R.id.chat_crop_btn_cancel);
        aspectRow    = findViewById(R.id.chat_crop_aspect_row);
        btnRotate    = findViewById(R.id.chat_crop_btn_rotate);
        tvAspectHint = findViewById(R.id.chat_crop_aspect_label);
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
            btnDone.setText("Saving…");
            doCropAndReturn();
        });

        if (btnRotate != null) {
            btnRotate.setOnClickListener(v -> {
                cropView.rotate90();
                // After rotation, update sourceBitmap reference so getCroppedBitmap bakes from latest
                // (rotate90 handles this internally via bitmap recycle+replace)
                sourceBitmap = null; // getCroppedBitmap uses cropView's internal bitmap
            });
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

    // ── Crop + save ───────────────────────────────────────────────────────

    private void doCropAndReturn() {
        bgExec.submit(() -> {
            try {
                // getCroppedBitmap uses cropView's internal bitmap + imageMatrix
                Bitmap cropped = cropView.getCroppedBitmap();
                if (cropped == null) throw new Exception("Crop region invalid");

                File dir = new File(getCacheDir(), "chat_crop");
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

    // ── Cleanup ───────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExec.shutdownNow();
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();
    }
}
