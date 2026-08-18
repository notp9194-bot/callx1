package com.callx.app.compose;

import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.status.R;

import java.util.ArrayList;
import java.util.List;

/**
 * StatusLayoutAdjustActivity — Screen 2 of the WhatsApp-level layout picker
 * for Status: arranging the collage.
 *
 * Reached only from {@link StatusLayoutPickerActivity} ("Start layout") once
 * ≥1 photo is picked and "Next" is tapped. Previously the preview + 6 layout
 * style buttons appeared inline right under the selection grid on that same
 * screen; this activity gives that step its own proper screen instead —
 * matching the reference app's real two-step flow — so switching between
 * "pick photos" and "arrange them" feels like two distinct, comfortable
 * steps rather than everything happening at once on one crowded screen.
 *
 * BUG FIX: this screen used to also embed a full second gallery grid +
 * "Recents ▾" dropdown below the preview, letting the user re-pick media
 * that screen 1 already picked — pure duplication, and it squeezed the
 * preview into a cramped fixed-height strip. Media selection now lives only
 * on screen 1; this screen is layout-only — choose a style, pinch/pan-adjust
 * each photo in a preview that now fills the space the old grid used to eat.
 * A photo can still be swapped without leaving this screen: long-press any
 * filled cell for a "Replace with Photo / Replace with Video / Delete" menu
 * (see {@link #showSlotOptionsMenu}); the "+" on an empty cell still opens
 * the system picker directly to fill it.
 *
 * v222: New — split out of the old single-screen StatusLayoutPickerActivity.
 * v228: Removed the duplicate gallery grid/Recents dropdown; added
 *       long-press replace/delete on preview cells; layout-picker result now
 *       supports video slots (isVideo is read back from each Uri's real
 *       MIME type instead of being hardcoded to "image").
 */
public class StatusLayoutAdjustActivity extends AppCompatActivity {

    private static final int MAX_SELECTION = 6;

    // Views
    private StatusLayoutPreviewView previewView;
    private RecyclerView            layoutStyleRecycler;
    private TextView                tvDone;

    // State
    private final List<Uri>        selectedUris = new ArrayList<>();
    private int                    currentStyle = StatusLayoutPreviewView.STYLE_GRID_2X2;
    private LayoutStyleAdapter     styleAdapter;

    private ActivityResultLauncher<String> addMediaLauncher;
    private int                            addMediaSlotIndex = -1; // -1 = append (from "+"), else replace that slot

    private boolean doneTapped = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_layout_adjust);

        List<String> incoming = getIntent().getStringArrayListExtra(StatusLayoutPickerActivity.EXTRA_INPUT_URIS);
        if (incoming != null) {
            for (String s : incoming) selectedUris.add(Uri.parse(s));
        }
        // v229: previously every selection defaulted to the 4-slot 2×2 grid
        // regardless of count, so picking 5 or 6 photos on screen 1 silently
        // hid the extras from the preview (they were still forwarded in the
        // result though — see the getSlotCount() trim in finishWithResult()
        // below for the other half of that fix). Default to a style that can
        // actually show everything the user picked.
        if (selectedUris.size() >= 6) currentStyle = StatusLayoutPreviewView.STYLE_GRID_6;
        else if (selectedUris.size() == 5) currentStyle = StatusLayoutPreviewView.STYLE_GRID_5;

        bindViews();
        setupToolbar();
        setupPreview();
        setupLayoutStyleRow();
        registerLaunchers();
        refreshPreview();
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews() {
        tvDone              = findViewById(R.id.tv_layout_adjust_done);
        previewView         = findViewById(R.id.layout_preview_view);
        layoutStyleRecycler = findViewById(R.id.rv_layout_styles);
    }

    private void setupToolbar() {
        ImageView btnBack = findViewById(R.id.btn_layout_adjust_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                setResult(RESULT_CANCELED);
                finish();
            });
        }

        tvDone.setOnClickListener(v -> {
            // Same double-tap guard StatusLayoutPickerActivity's old Done button
            // had — a fast double-tap must only ever produce one result.
            if (doneTapped) return;
            doneTapped = true;
            finishWithResult();
        });
    }

    // ── Preview section ───────────────────────────────────────────────────

    private void setupPreview() {
        previewView.setLayoutStyle(currentStyle);
        previewView.setOnAddSlotClickListener(slotIndex -> {
            addMediaSlotIndex = slotIndex;
            addMediaLauncher.launch("image/*");
        });
        previewView.setOnSlotLongPressListener(this::showSlotOptionsMenu);
    }

    /**
     * Long-press on a filled preview cell — lets the user swap that one
     * photo/video or remove it entirely, without leaving this screen or
     * hunting back through screen 1's gallery grid.
     */
    private void showSlotOptionsMenu(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= selectedUris.size()) return;

        String[] options = {"Replace with Photo", "Replace with Video", "Delete"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // Replace with Photo
                            addMediaSlotIndex = slotIndex;
                            addMediaLauncher.launch("image/*");
                            break;
                        case 1: // Replace with Video
                            addMediaSlotIndex = slotIndex;
                            addMediaLauncher.launch("video/*");
                            break;
                        case 2: // Delete
                            deleteSlotWithUndo(slotIndex);
                            break;
                    }
                })
                .show();
    }

    /**
     * v229: deletes the slot but offers a 4-second Undo via Snackbar instead
     * of removing it silently — a long-press → Delete is easy to fire by
     * accident, and re-picking the exact same photo from the gallery again
     * is more friction than a one-tap undo.
     */
    private void deleteSlotWithUndo(int slotIndex) {
        Uri removed = selectedUris.remove(slotIndex);
        refreshPreview();
        com.google.android.material.snackbar.Snackbar
                .make(previewView, "Photo removed", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("UNDO", v -> {
                    int insertAt = Math.min(slotIndex, selectedUris.size());
                    selectedUris.add(insertAt, removed);
                    refreshPreview();
                })
                .show();
    }

    private void refreshPreview() {
        previewView.setMediaUris(selectedUris);
        tvDone.setEnabled(!selectedUris.isEmpty());
        tvDone.setAlpha(selectedUris.isEmpty() ? 0.4f : 1f);
    }

    // ── Layout style row ──────────────────────────────────────────────────

    private void setupLayoutStyleRow() {
        styleAdapter = new LayoutStyleAdapter(this::onLayoutStyleSelected);
        layoutStyleRecycler.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        layoutStyleRecycler.setAdapter(styleAdapter);
        styleAdapter.setSelected(currentStyle);
    }

    private void onLayoutStyleSelected(int style) {
        currentStyle = style;
        previewView.setLayoutStyle(style);
        styleAdapter.setSelected(style);
    }

    // ── Replace/add media (image or video) for a single slot ───────────────

    private void registerLaunchers() {
        addMediaLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri == null) return;
                    if (addMediaSlotIndex >= 0 && addMediaSlotIndex < selectedUris.size()) {
                        selectedUris.set(addMediaSlotIndex, uri);
                    } else if (selectedUris.size() < MAX_SELECTION) {
                        selectedUris.add(uri);
                    }
                    addMediaSlotIndex = -1;
                    refreshPreview();
                });
    }

    // ── Done / result ─────────────────────────────────────────────────────

    private void finishWithResult() {
        if (selectedUris.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        // v229 bug fix: previously this always forwarded every selected Uri
        // regardless of the chosen style's slot count, so e.g. picking 6
        // photos but leaving the default 2×2 (4-slot) style meant the last
        // 2 photos were invisible in the preview yet still included in the
        // result — a silent count mismatch for whatever renders the final
        // collage downstream. Trim to exactly what's shown.
        int slotCount = previewView.getSlotCount();
        List<Uri> toSend = selectedUris.size() > slotCount
                ? selectedUris.subList(0, slotCount)
                : selectedUris;
        if (selectedUris.size() > slotCount) {
            Toast.makeText(this,
                    "Only the first " + slotCount + " photos fit this layout",
                    Toast.LENGTH_SHORT).show();
        }

        ArrayList<String> uriStrings = new ArrayList<>();
        ArrayList<Integer> videoFlags = new ArrayList<>();
        // BUG FIX (layout not preserved in the post): the pinch-zoom/pan the
        // user just dialed in per-photo (previewView.getAdjustFor()) used to
        // be dropped here — only the bare Uris + chosen style were forwarded,
        // so StatusLayoutComposer downstream had no idea how each photo was
        // actually framed and fell back to its own default crop. Forward the
        // adjust values in the same order as uriStrings so the composer can
        // reproduce this exact arrangement.
        float[] scales = new float[toSend.size()];
        float[] panXs  = new float[toSend.size()];
        float[] panYs  = new float[toSend.size()];
        for (int i = 0; i < toSend.size(); i++) {
            Uri uri = toSend.get(i);
            uriStrings.add(uri.toString());
            videoFlags.add(isVideoUri(uri) ? 1 : 0);
            float[] adjust = previewView.getAdjustFor(uri);
            scales[i] = adjust[0];
            panXs[i]  = adjust[1];
            panYs[i]  = adjust[2];
        }

        android.content.Intent resultIntent = new android.content.Intent();
        resultIntent.putStringArrayListExtra(StatusLayoutPickerActivity.EXTRA_RESULT_URIS, uriStrings);
        resultIntent.putIntegerArrayListExtra(StatusLayoutPickerActivity.EXTRA_RESULT_IS_VIDEO, videoFlags);
        resultIntent.putExtra(StatusLayoutPickerActivity.EXTRA_RESULT_LAYOUT, currentStyle);
        resultIntent.putExtra(StatusLayoutPickerActivity.EXTRA_RESULT_SCALE, scales);
        resultIntent.putExtra(StatusLayoutPickerActivity.EXTRA_RESULT_PAN_X, panXs);
        resultIntent.putExtra(StatusLayoutPickerActivity.EXTRA_RESULT_PAN_Y, panYs);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    /**
     * Whether the given Uri points at a video (vs. photo), determined from
     * its real MIME type rather than tracked separately — a slot can end up
     * holding a video either from the original screen-1 selection or from a
     * "Replace with Video" long-press action, so checking the actual content
     * type here is simpler and can't drift out of sync with how it got there.
     */
    private boolean isVideoUri(Uri uri) {
        try {
            String type = getContentResolver().getType(uri);
            return type != null && type.startsWith("video/");
        } catch (Exception e) {
            return false;
        }
    }
}
