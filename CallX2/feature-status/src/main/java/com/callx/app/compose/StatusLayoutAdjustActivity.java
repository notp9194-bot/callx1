package com.callx.app.compose;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.conversation.controllers.AttachSheetFolderPicker;
import com.callx.app.conversation.controllers.RecentMediaLoader;
import com.callx.app.status.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * The gallery grid here is still editable (same circle-checkbox multi-select,
 * same "Recents ▾" dropdown as screen 1 and the attach sheet) so the user can
 * add/remove/reorder photos without having to back out to screen 1.
 *
 * v222: New — split out of the old single-screen StatusLayoutPickerActivity.
 */
public class StatusLayoutAdjustActivity extends AppCompatActivity {

    private static final int MAX_SELECTION = 6;

    // Views
    private StatusLayoutPreviewView previewView;
    private RecyclerView            gridRecycler;
    private RecyclerView            layoutStyleRecycler;
    private TextView                tvDone;
    private View                    rowRecents;
    private TextView                tvRecentsTitle;
    private View                    rootView;

    // State
    private final List<Uri>             selectedUris = new ArrayList<>();
    private final List<LayoutMediaItem> galleryItems = new ArrayList<>();
    private int                    currentStyle   = StatusLayoutPreviewView.STYLE_GRID_2X2;
    private LayoutMediaGridAdapter gridAdapter;
    private LayoutStyleAdapter     styleAdapter;
    private String                 currentFilterKey = RecentMediaLoader.FILTER_ALL;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> addMediaLauncher;
    private int                            addMediaSlotIndex = -1;

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

        bindViews();
        setupToolbar();
        setupPreview();
        setupLayoutStyleRow();
        setupRecentsDropdown();
        setupGridRecycler();
        registerLaunchers();
        loadGalleryImages(currentFilterKey);
        refreshPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdown();
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews() {
        rootView            = findViewById(R.id.layout_adjust_root);
        tvDone              = findViewById(R.id.tv_layout_adjust_done);
        previewView         = findViewById(R.id.layout_preview_view);
        rowRecents          = findViewById(R.id.row_layout_recents);
        tvRecentsTitle      = findViewById(R.id.tv_layout_recents_title);
        gridRecycler        = findViewById(R.id.rv_layout_media_grid);
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

    // ── Recents ▾ dropdown ─────────────────────────────────────────────────
    // Same popup as screen 1 / the attach sheet — lets the user switch this
    // screen's grid to a different on-device folder without leaving the
    // adjust screen.

    private void setupRecentsDropdown() {
        if (rowRecents == null) return;
        rowRecents.setOnClickListener(v -> AttachSheetFolderPicker.showUnderAnchor(
                this, rowRecents, rootView, ioExecutor, currentFilterKey,
                folder -> {
                    if (RecentMediaLoader.ACTION_MORE_APPS.equals(folder.filterKey)
                            || RecentMediaLoader.ACTION_SEE_MORE.equals(folder.filterKey)) {
                        return;
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
    }

    private void onMediaItemToggled(LayoutMediaItem item) {
        if (item.selected) {
            selectedUris.remove(item.uri);
            item.selected = false;
            item.selectionOrder = 0;
            int order = 1;
            for (LayoutMediaItem mi : galleryItems) {
                if (mi.selected) mi.selectionOrder = order++;
            }
        } else {
            if (selectedUris.size() >= MAX_SELECTION) {
                Toast.makeText(this, "You can choose up to " + MAX_SELECTION + " photos", Toast.LENGTH_SHORT).show();
                return;
            }
            selectedUris.add(item.uri);
            item.selected = true;
            item.selectionOrder = selectedUris.size();
        }
        gridAdapter.notifyDataSetChanged();
        refreshPreview();
    }

    // ── Gallery loading ───────────────────────────────────────────────────

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
                // Pre-mark the photos carried over from screen 1 (or kept across
                // a folder switch) as selected, in their existing order.
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

    // ── Done / result ─────────────────────────────────────────────────────

    private void finishWithResult() {
        if (selectedUris.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        ArrayList<String> uriStrings = new ArrayList<>();
        ArrayList<Integer> videoFlags = new ArrayList<>();
        for (Uri uri : selectedUris) {
            uriStrings.add(uri.toString());
            videoFlags.add(0); // images only in layout picker
        }

        android.content.Intent resultIntent = new android.content.Intent();
        resultIntent.putStringArrayListExtra(StatusLayoutPickerActivity.EXTRA_RESULT_URIS, uriStrings);
        resultIntent.putIntegerArrayListExtra(StatusLayoutPickerActivity.EXTRA_RESULT_IS_VIDEO, videoFlags);
        resultIntent.putExtra(StatusLayoutPickerActivity.EXTRA_RESULT_LAYOUT, currentStyle);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
