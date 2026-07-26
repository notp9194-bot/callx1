package com.callx.app.compose;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.status.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StatusLayoutPickerActivity — WhatsApp-level multi-photo layout picker for Status.
 *
 * Flow:
 *   1. "Start layout" screen — shows gallery grid with circle-checkbox multi-select
 *      (up to 6 photos). Done button in top-right.
 *   2. After selecting ≥1 photo, preview updates in real-time at the top.
 *   3. Bottom row of 6 layout-style buttons — tapping instantly changes the
 *      layout in the top preview.
 *   4. Empty cells in the preview show a "+" add-more button which opens the
 *      gallery picker for that slot.
 *   5. Done → launches MediaEditActivity with all selected URIs → status posting.
 *
 * v216: Initial implementation — full WhatsApp-approach layout picker.
 */
public class StatusLayoutPickerActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT_URIS       = "layout_result_uris";
    public static final String EXTRA_RESULT_LAYOUT     = "layout_result_style";
    public static final String EXTRA_RESULT_IS_VIDEO   = "layout_result_is_video";

    private static final int MAX_SELECTION = 6;

    // Views
    private StatusLayoutPreviewView previewView;
    private RecyclerView            gridRecycler;
    private RecyclerView            layoutStyleRecycler;
    private TextView                tvTitle;
    private TextView                tvDone;
    private View                    previewSection;

    // State
    private final List<Uri>       selectedUris   = new ArrayList<>();
    private final List<MediaItem> galleryItems   = new ArrayList<>();
    private int                   currentStyle   = StatusLayoutPreviewView.STYLE_GRID_2X2;
    private MediaGridAdapter      gridAdapter;
    private LayoutStyleAdapter    styleAdapter;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String> addMediaLauncher;
    private int                            addMediaSlotIndex = -1;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_layout_picker);

        bindViews();
        setupToolbar();
        setupPreview();
        setupLayoutStyleRow();
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
        tvTitle            = findViewById(R.id.tv_layout_picker_title);
        tvDone             = findViewById(R.id.tv_layout_picker_done);
        previewSection     = findViewById(R.id.layout_preview_section);
        previewView        = findViewById(R.id.layout_preview_view);
        gridRecycler       = findViewById(R.id.rv_layout_media_grid);
        layoutStyleRecycler= findViewById(R.id.rv_layout_styles);
    }

    private void setupToolbar() {
        ImageView btnBack = findViewById(R.id.btn_layout_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        tvTitle.setText("Start layout");

        tvDone.setOnClickListener(v -> finishWithResult());
        tvDone.setEnabled(false);
        tvDone.setAlpha(0.4f);
    }

    // ── Preview section ───────────────────────────────────────────────────

    private void setupPreview() {
        previewSection.setVisibility(View.GONE);
        previewView.setLayoutStyle(currentStyle);
        previewView.setOnAddSlotClickListener(slotIndex -> {
            addMediaSlotIndex = slotIndex;
            addMediaLauncher.launch("image/*");
        });
    }

    private void refreshPreview() {
        previewView.setMediaUris(selectedUris);
        boolean hasMedia = !selectedUris.isEmpty();
        previewSection.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        tvDone.setEnabled(hasMedia);
        tvDone.setAlpha(hasMedia ? 1f : 0.4f);
        tvTitle.setText(hasMedia ? "Choose layout" : "Start layout");
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

    // ── Grid recycler ─────────────────────────────────────────────────────

    private void setupGridRecycler() {
        gridAdapter = new MediaGridAdapter(galleryItems, this::onMediaItemToggled);
        gridRecycler.setLayoutManager(new GridLayoutManager(this, 3));
        gridRecycler.setAdapter(gridAdapter);
    }

    private void onMediaItemToggled(MediaItem item) {
        if (item.selected) {
            // Deselect
            selectedUris.remove(item.uri);
            item.selected = false;
            item.selectionOrder = 0;
            // Recompute orders for remaining selections
            int order = 1;
            for (MediaItem mi : galleryItems) {
                if (mi.selected) mi.selectionOrder = order++;
            }
        } else {
            // Select
            if (selectedUris.size() >= MAX_SELECTION) {
                Toast.makeText(this, "You can choose up to " + MAX_SELECTION + " photos",
                        Toast.LENGTH_SHORT).show();
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
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) loadGalleryImages();
                    else Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
                });

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

    private void requestMediaPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadGalleryImages();
        } else {
            permissionLauncher.launch(perm);
        }
    }

    private void loadGalleryImages() {
        ioExecutor.execute(() -> {
            List<MediaItem> items = new ArrayList<>();
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED};
            String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

            try (Cursor cursor = getContentResolver().query(collection, projection, null, null, sortOrder)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext() && items.size() < 150) {
                        long id = cursor.getLong(idCol);
                        Uri contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        items.add(new MediaItem(contentUri));
                    }
                }
            } catch (Exception ignored) {}

            runOnUiThread(() -> {
                galleryItems.clear();
                galleryItems.addAll(items);
                gridAdapter.notifyDataSetChanged();
            });
        });
    }

    // ── Done / result ─────────────────────────────────────────────────────

    private void finishWithResult() {
        if (selectedUris.isEmpty()) { finish(); return; }

        ArrayList<String> uriStrings = new ArrayList<>();
        ArrayList<Integer> videoFlags = new ArrayList<>();
        for (Uri uri : selectedUris) {
            uriStrings.add(uri.toString());
            videoFlags.add(0); // images only in layout picker
        }

        Intent resultIntent = new Intent();
        resultIntent.putStringArrayListExtra(EXTRA_RESULT_URIS, uriStrings);
        resultIntent.putIntegerArrayListExtra(EXTRA_RESULT_IS_VIDEO, videoFlags);
        resultIntent.putExtra(EXTRA_RESULT_LAYOUT, currentStyle);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Data model
    // ═════════════════════════════════════════════════════════════════════

    static class MediaItem {
        final Uri uri;
        boolean selected;
        int     selectionOrder;

        MediaItem(Uri uri) { this.uri = uri; }
    }

    // ═════════════════════════════════════════════════════════════════════
    // MediaGridAdapter — 3-column grid with circle-checkbox selection
    // ═════════════════════════════════════════════════════════════════════

    static class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.VH> {

        interface OnToggle { void toggle(MediaItem item); }

        private final List<MediaItem> items;
        private final OnToggle        listener;

        MediaGridAdapter(List<MediaItem> items, OnToggle listener) {
            this.items    = items;
            this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_layout_picker_media, parent, false);
            // BUG FIX: item_layout_picker_media.xml's root has layout_height="0dp"
            // with no weight — that only resolves via LinearLayout's weight
            // mechanism, which a plain RecyclerView/GridLayoutManager cell
            // doesn't have, so every cell rendered at 0px tall and thumbnails
            // never became visible even though the MediaStore query + Glide
            // load were both succeeding. Give each cell an explicit square
            // height here (screenWidth / spanCount), same fixed-cellPx
            // approach RecentMediaGridAdapter already uses for the 4-col
            // chat/status attach-sheet grid.
            int cellPx = parent.getContext().getResources().getDisplayMetrics().widthPixels / 3;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.height = cellPx;
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MediaItem item = items.get(pos);
            Glide.with(h.thumb.getContext())
                    .load(item.uri)
                    .centerCrop()
                    .into(h.thumb);
            h.checkCircle.setVisibility(View.VISIBLE);
            if (item.selected) {
                h.checkCircle.setBackgroundResource(R.drawable.bg_layout_check_selected);
                h.tvOrder.setVisibility(View.VISIBLE);
                h.tvOrder.setText(String.valueOf(item.selectionOrder));
                h.selectionOverlay.setVisibility(View.VISIBLE);
            } else {
                h.checkCircle.setBackgroundResource(R.drawable.bg_layout_check_empty);
                h.tvOrder.setVisibility(View.GONE);
                h.selectionOverlay.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> listener.toggle(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final FrameLayout checkCircle;
            final TextView    tvOrder;
            final View        selectionOverlay;

            VH(@NonNull View v) {
                super(v);
                thumb           = v.findViewById(R.id.iv_layout_media_thumb);
                checkCircle     = v.findViewById(R.id.fl_layout_check_circle);
                tvOrder         = v.findViewById(R.id.tv_layout_selection_order);
                selectionOverlay= v.findViewById(R.id.layout_selection_overlay);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // LayoutStyleAdapter — horizontal row of 6 layout-style buttons
    // ═════════════════════════════════════════════════════════════════════

    static class LayoutStyleAdapter extends RecyclerView.Adapter<LayoutStyleAdapter.VH> {

        interface OnStyleSelected { void onSelected(int style); }

        private static final int[] STYLES = {
                StatusLayoutPreviewView.STYLE_GRID_2X2,
                StatusLayoutPreviewView.STYLE_BIG_LEFT,
                StatusLayoutPreviewView.STYLE_COLUMNS_2,
                StatusLayoutPreviewView.STYLE_BIG_TOP,
                StatusLayoutPreviewView.STYLE_BIG_RIGHT,
                StatusLayoutPreviewView.STYLE_GRID_3,
        };

        private final OnStyleSelected listener;
        private int selectedStyle = StatusLayoutPreviewView.STYLE_GRID_2X2;

        LayoutStyleAdapter(OnStyleSelected listener) {
            this.listener = listener;
        }

        void setSelected(int style) {
            this.selectedStyle = style;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_layout_style_btn, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            int style = STYLES[pos];
            h.styleIcon.setImageResource(styleIconRes(style));
            boolean sel = (style == selectedStyle);
            h.itemView.setAlpha(sel ? 1f : 0.45f);
            h.indicator.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            h.itemView.setOnClickListener(v -> listener.onSelected(style));
        }

        @Override public int getItemCount() { return STYLES.length; }

        private int styleIconRes(int style) {
            switch (style) {
                case StatusLayoutPreviewView.STYLE_GRID_2X2:  return R.drawable.ic_layout_grid_2x2;
                case StatusLayoutPreviewView.STYLE_BIG_LEFT:  return R.drawable.ic_layout_big_left;
                case StatusLayoutPreviewView.STYLE_COLUMNS_2: return R.drawable.ic_layout_columns_2;
                case StatusLayoutPreviewView.STYLE_BIG_TOP:   return R.drawable.ic_layout_big_top;
                case StatusLayoutPreviewView.STYLE_BIG_RIGHT: return R.drawable.ic_layout_big_right;
                case StatusLayoutPreviewView.STYLE_GRID_3:    return R.drawable.ic_layout_grid_3;
                default: return R.drawable.ic_layout_grid_2x2;
            }
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView styleIcon;
            final View      indicator;

            VH(@NonNull View v) {
                super(v);
                styleIcon = v.findViewById(R.id.iv_layout_style_icon);
                indicator = v.findViewById(R.id.view_layout_style_selected);
            }
        }
    }
}
