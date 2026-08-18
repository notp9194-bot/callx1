package com.callx.app.compose;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.conversation.controllers.AttachSheetFolderPicker;
import com.callx.app.conversation.controllers.RecentMediaLoader;
import com.callx.app.status.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StatusLayoutPickerActivity — Screen 1 of the WhatsApp-level multi-photo
 * layout picker for Status: pure gallery selection.
 *
 * Flow:
 *   1. "Start layout" screen (this activity) — gallery grid with circle-checkbox
 *      multi-select (up to 6 photos). "Recents ▾" dropdown to switch folders,
 *      exactly like the attach sheet's own folder picker.
 *   2. "Next" (enabled once ≥1 photo is selected) launches
 *      {@link StatusLayoutAdjustActivity} — a genuinely separate screen for
 *      arranging the collage (preview + 6 layout styles), rather than that
 *      preview popping up inline under this same grid.
 *   3. That screen's own "Done" produces the final result, which this
 *      activity simply forwards on as its own result to NewStatusActivity.
 *
 * v216: Initial implementation — full WhatsApp-approach layout picker (single screen).
 * v222: Split into two real screens (this selection screen + StatusLayoutAdjustActivity)
 *       and wired up a working "Recents ▾" dropdown.
 */
public class StatusLayoutPickerActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT_URIS       = "layout_result_uris";
    public static final String EXTRA_RESULT_LAYOUT     = "layout_result_style";
    public static final String EXTRA_RESULT_IS_VIDEO   = "layout_result_is_video";
    // BUG FIX: per-photo pinch-zoom/pan (StatusLayoutPreviewView#getAdjustFor),
    // parallel float[] arrays aligned index-for-index with EXTRA_RESULT_URIS,
    // so the exact framing arranged on the adjust screen survives into the
    // final posted collage instead of reverting to a plain auto-crop.
    public static final String EXTRA_RESULT_SCALE      = "layout_result_scale";
    public static final String EXTRA_RESULT_PAN_X      = "layout_result_pan_x";
    public static final String EXTRA_RESULT_PAN_Y      = "layout_result_pan_y";

    static final String EXTRA_INPUT_URIS = "layout_input_uris";

    private static final int MAX_SELECTION = 6;

    // Views
    private RecyclerView gridRecycler;
    private TextView     tvTitle;
    private TextView     tvNext;
    private View         rowRecents;
    private TextView     tvRecentsTitle;
    private View         rootView;

    // State
    private final List<Uri>            selectedUris = new ArrayList<>();
    private final List<LayoutMediaItem> galleryItems = new ArrayList<>();
    private LayoutMediaGridAdapter      gridAdapter;
    private String                     currentFilterKey = RecentMediaLoader.FILTER_ALL;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<Intent> adjustLauncher;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_layout_picker);

        bindViews();
        setupToolbar();
        setupRecentsDropdown();
        setupGridRecycler();
        registerLaunchers();
        requestMediaPermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdown();
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews() {
        rootView       = findViewById(R.id.layout_picker_root);
        tvTitle        = findViewById(R.id.tv_layout_picker_title);
        tvNext         = findViewById(R.id.tv_layout_picker_done);
        rowRecents     = findViewById(R.id.row_layout_recents);
        tvRecentsTitle = findViewById(R.id.tv_layout_recents_title);
        gridRecycler   = findViewById(R.id.rv_layout_media_grid);
    }

    private void setupToolbar() {
        ImageView btnBack = findViewById(R.id.btn_layout_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        tvTitle.setText("Start layout");

        tvNext.setOnClickListener(v -> openAdjustScreen());
        tvNext.setEnabled(false);
        tvNext.setAlpha(0.4f);
    }

    private void refreshNextButton() {
        boolean hasMedia = !selectedUris.isEmpty();
        tvNext.setEnabled(hasMedia);
        tvNext.setAlpha(hasMedia ? 1f : 0.4f);
    }

    // ── Recents ▾ dropdown ─────────────────────────────────────────────────
    // Same AttachSheetFolderPicker popup the upload/attach sheet uses —
    // previously this row had no id and no click listener at all, so tapping
    // it did nothing. Wired up here the same way AttachSheetRecentMediaBinder
    // does it for the attach sheet.

    private void setupRecentsDropdown() {
        if (rowRecents == null) return;
        rowRecents.setOnClickListener(v -> AttachSheetFolderPicker.showUnderAnchor(
                this, rowRecents, rootView, ioExecutor, currentFilterKey,
                folder -> {
                    if (RecentMediaLoader.ACTION_MORE_APPS.equals(folder.filterKey)
                            || RecentMediaLoader.ACTION_SEE_MORE.equals(folder.filterKey)) {
                        return; // those two hand off to system pickers in the attach sheet; not applicable here
                    }
                    currentFilterKey = folder.filterKey;
                    if (tvRecentsTitle != null) tvRecentsTitle.setText(folder.name);
                    loadGalleryImages(currentFilterKey);
                }));
    }

    // ── Grid recycler ─────────────────────────────────────────────────────

    private void setupGridRecycler() {
        gridAdapter = new LayoutMediaGridAdapter(galleryItems, this::onMediaItemToggled);
        gridRecycler.setLayoutManager(new GridLayoutManager(this, 3));
        gridRecycler.setAdapter(gridAdapter);
        // v229 perf: cell size never changes with content (fixed 3-column,
        // fixed square cellPx), so RecyclerView can skip re-measuring the
        // whole layout on every adapter change.
        gridRecycler.setHasFixedSize(true);
    }

    /**
     * v229 perf: this used to call gridAdapter.notifyDataSetChanged() on
     * every single tap — on a folder with hundreds of photos, that rebinds
     * (and re-decodes, before the Glide cache warms up) every visible cell
     * for a change that only ever actually touches the tapped item plus, on
     * deselect, however many already-selected items need their order badge
     * renumbered. Only those specific positions get invalidated now.
     */
    private void onMediaItemToggled(LayoutMediaItem item) {
        int togglePos = galleryItems.indexOf(item);
        if (item.selected) {
            // Deselect
            selectedUris.remove(item.uri);
            item.selected = false;
            item.selectionOrder = 0;
            // Recompute orders for remaining selections, invalidating only
            // the items whose displayed order number actually changed.
            int order = 1;
            for (int i = 0; i < galleryItems.size(); i++) {
                LayoutMediaItem mi = galleryItems.get(i);
                if (mi.selected) {
                    int newOrder = order++;
                    if (mi.selectionOrder != newOrder) {
                        mi.selectionOrder = newOrder;
                        gridAdapter.notifyItemChanged(i);
                    }
                }
            }
        } else {
            // Select
            if (selectedUris.size() >= MAX_SELECTION) {
                android.widget.Toast.makeText(this, "You can choose up to " + MAX_SELECTION + " photos",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            selectedUris.add(item.uri);
            item.selected = true;
            item.selectionOrder = selectedUris.size();
        }
        if (togglePos >= 0) gridAdapter.notifyItemChanged(togglePos);
        refreshNextButton();
    }

    // ── Gallery loading ───────────────────────────────────────────────────

    private void registerLaunchers() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) loadGalleryImages(currentFilterKey);
                    else android.widget.Toast.makeText(this, "Storage permission required",
                            android.widget.Toast.LENGTH_SHORT).show();
                });

        // Screen 2 (StatusLayoutAdjustActivity) — its own "Done" comes back here
        // as RESULT_OK with the final URIs + chosen style, which we just forward
        // on as this activity's own result. Cancelling (back arrow) comes back
        // RESULT_CANCELED and we simply stay on this screen with the selection
        // exactly as the user left it.
        adjustLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        setResult(RESULT_OK, result.getData());
                        finish();
                    }
                });
    }

    private void requestMediaPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadGalleryImages(currentFilterKey);
        } else {
            permissionLauncher.launch(perm);
        }
    }

    /** Loads photos via RecentMediaLoader, filtered to the given folder key (null/FILTER_ALL = everything). */
    private void loadGalleryImages(String filterKey) {
        ioExecutor.execute(() -> {
            List<RecentMediaLoader.Item> raw =
                    RecentMediaLoader.loadRecentPage(this, 0, 300, filterKey);
            List<LayoutMediaItem> items = new ArrayList<>();
            for (RecentMediaLoader.Item it : raw) {
                if (it.isVideo) continue; // layout picker is photos-only
                items.add(new LayoutMediaItem(it.uri));
            }
            runOnUiThread(() -> {
                galleryItems.clear();
                galleryItems.addAll(items);
                // Re-mark previously selected photos so switching folders and back
                // doesn't lose the current selection.
                int order = 1;
                for (LayoutMediaItem mi : galleryItems) {
                    if (selectedUris.contains(mi.uri)) {
                        mi.selected = true;
                        mi.selectionOrder = order++;
                    }
                }
                gridAdapter.notifyDataSetChanged();
            });
        });
    }

    // ── Next / hand off to the adjust screen ──────────────────────────────

    private void openAdjustScreen() {
        if (selectedUris.isEmpty()) return;
        ArrayList<String> uriStrings = new ArrayList<>();
        for (Uri uri : selectedUris) uriStrings.add(uri.toString());

        Intent intent = new Intent(this, StatusLayoutAdjustActivity.class);
        intent.putStringArrayListExtra(EXTRA_INPUT_URIS, uriStrings);
        adjustLauncher.launch(intent);
    }
}
