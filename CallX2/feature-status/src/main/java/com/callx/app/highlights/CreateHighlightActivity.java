package com.callx.app.highlights;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import com.callx.app.models.StatusItem;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusHighlightManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CreateHighlightActivity — "New Highlight" flow, reached from the "+" button
 * on the Highlights row (both the Status tab and UserReelsActivity's profile
 * header). Mirrors Instagram's flow:
 *
 *   Step 1 (this screen): grid of the user's own statuses — live ones from
 *     status/{uid} plus archived ones from statusArchive/{uid} — multi-select
 *     with a checkmark overlay, "N selected" + "Next" bottom bar.
 *   Step 2 (bottom sheet): same picker used by StatusAddToHighlightBottomSheet
 *     — choose an existing album (adds all selected items to it) or create a
 *     new one (name + optional custom ring color via
 *     HighlightRingColorPickerBottomSheet), then writes every selected item
 *     via StatusHighlightManager.addToHighlight(...).
 *
 * Kept as its own class (rather than extending StatusAddToHighlightBottomSheet)
 * because that one is single-item and used elsewhere (viewer's per-status
 * "Add to Highlight" menu) — this handles the multi-select "create" entry
 * point instead.
 */
public class CreateHighlightActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView tvEmpty;
    private ProgressBar progress;
    private TextView tvSelectedCount;
    private Button btnNext;

    private final List<StatusItem> allItems = new ArrayList<>();
    private final Map<String, StatusItem> selected = new LinkedHashMap<>(); // id -> item, preserves pick order

    private PickAdapter adapter;
    private String myUid;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        if (myUid == null) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("New Highlight");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this);
        root.addView(progress);

        tvEmpty = new TextView(this);
        tvEmpty.setText("No statuses to add yet.\nPost a status first, then come back here.");
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(32), dp(64), dp(32), 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new PickAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Bottom bar: "N selected"  +  Next ──────────────────────────
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        bottomBar.setBackground(surfaceBg());
        View topDivider = new View(this);
        topDivider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        topDivider.setBackgroundColor(Color.parseColor("#1F000000"));
        root.addView(topDivider);

        tvSelectedCount = new TextView(this);
        tvSelectedCount.setText("Select statuses to highlight");
        tvSelectedCount.setTextSize(14);
        tvSelectedCount.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(tvSelectedCount);

        btnNext = new Button(this);
        btnNext.setText("Next");
        btnNext.setEnabled(false);
        btnNext.setAlpha(0.5f);
        btnNext.setOnClickListener(v -> {
            if (!selected.isEmpty()) showAlbumStep(new ArrayList<>(selected.values()));
        });
        bottomBar.addView(btnNext);
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        loadItems();
    }

    /** Loads live (unexpired-or-not, all of them — highlights work off expired
     *  ones too) statuses plus archived ones, deduped by id, newest first. */
    private void loadItems() {
        FirebaseUtils.getUserStatusRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot liveSnap) {
                    Map<String, StatusItem> merged = new LinkedHashMap<>();
                    for (DataSnapshot c : liveSnap.getChildren()) {
                        StatusItem item = c.getValue(StatusItem.class);
                        if (item == null || (item.deleted != null && item.deleted)) continue;
                        if (item.id == null) item.id = c.getKey();
                        merged.put(item.id, item);
                    }
                    StatusHighlightManager.getArchiveRef(myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot archSnap) {
                                for (DataSnapshot c : archSnap.getChildren()) {
                                    StatusItem item = c.getValue(StatusItem.class);
                                    if (item == null) continue;
                                    if (item.id == null) item.id = c.getKey();
                                    if (!merged.containsKey(item.id)) merged.put(item.id, item);
                                }
                                finishLoad(merged);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(merged); }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                    tvEmpty.setVisibility(View.VISIBLE);
                }
            });
    }

    private void finishLoad(Map<String, StatusItem> merged) {
        allItems.clear();
        allItems.addAll(merged.values());
        // Newest first
        java.util.Collections.sort(allItems, (a, b) -> {
            long ta = a.timestamp != null ? a.timestamp : 0L;
            long tb = b.timestamp != null ? b.timestamp : 0L;
            return Long.compare(tb, ta);
        });
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            tvEmpty.setVisibility(allItems.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
        });
    }

    private void updateSelectionBar() {
        int n = selected.size();
        tvSelectedCount.setText(n == 0 ? "Select statuses to highlight" : (n + " selected"));
        btnNext.setEnabled(n > 0);
        btnNext.setAlpha(n > 0 ? 1f : 0.5f);
    }

    // ── Step 2: existing-album / create-new picker (same shape as
    //    StatusAddToHighlightBottomSheet, adapted for a list of items) ──────
    private void showAlbumStep(List<StatusItem> items) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));

        TextView title = new TextView(this);
        title.setText("Add to Highlights");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(items.size() + " selected — choose an existing album or create new");
        sub.setTextSize(13);
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        TextView albumsLabel = new TextView(this);
        albumsLabel.setText("Existing albums");
        albumsLabel.setTextSize(14);
        albumsLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        albumsLabel.setPadding(0, 0, 0, dp(8));
        root.addView(albumsLabel);

        LinearLayout albumList = new LinearLayout(this);
        albumList.setOrientation(LinearLayout.VERTICAL);
        ProgressBar albumsProgress = new ProgressBar(this);
        albumList.addView(albumsProgress);
        root.addView(albumList);

        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(16);
        divLp.bottomMargin = dp(16);
        divider.setLayoutParams(divLp);
        divider.setBackgroundColor(Color.parseColor("#22000000"));
        root.addView(divider);

        TextView newLabel = new TextView(this);
        newLabel.setText("Create new album");
        newLabel.setTextSize(14);
        newLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        newLabel.setPadding(0, 0, 0, dp(8));
        root.addView(newLabel);

        LinearLayout newRow = new LinearLayout(this);
        newRow.setOrientation(LinearLayout.HORIZONTAL);
        newRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText etName = new EditText(this);
        etName.setHint("Album name (e.g. Vacation 2024)");
        etName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        newRow.addView(etName);

        Button addBtn = new Button(this);
        addBtn.setText("Create");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(dp(8));
        addBtn.setLayoutParams(addLp);

        String[] pickedRingColor = { null };
        String[] pickedRingMode  = { null };
        TextView ringColorRow = new TextView(this);
        ringColorRow.setText("\uD83C\uDFA8  Ring color: Default");
        ringColorRow.setTextSize(13);
        ringColorRow.setTextColor(Color.parseColor("#6200EE"));
        ringColorRow.setPadding(0, dp(10), 0, dp(10));
        ringColorRow.setOnClickListener(v ->
            HighlightRingColorPickerBottomSheet.show(this, pickedRingColor[0], pickedRingMode[0],
                    pickedRingColor[0] != null, (colorHex, mode) -> {
                        pickedRingColor[0] = colorHex;
                        pickedRingMode[0]  = mode;
                        ringColorRow.setText(colorHex == null
                                ? "\uD83C\uDFA8  Ring color: Default"
                                : "\uD83C\uDFA8  Ring color: " + colorHex
                                    + (com.callx.app.utils.HighlightRingDrawable.MODE_DOMINANT.equals(mode) ? " (dominant)" : " (solid)"));
                    }));
        root.addView(ringColorRow);

        addBtn.setOnClickListener(v -> {
            String album = etName.getText() != null ? etName.getText().toString().trim() : "";
            if (album.isEmpty()) { etName.setError("Enter album name"); return; }
            String albumId = album.toLowerCase(Locale.getDefault()).replace(" ", "_");
            for (StatusItem item : items) {
                StatusHighlightManager.addToHighlight(myUid, item, albumId, album);
            }
            if (pickedRingColor[0] != null) {
                StatusHighlightManager.setAlbumRingStyle(myUid, albumId, pickedRingColor[0], pickedRingMode[0]);
            }
            Toast.makeText(this, "Added " + items.size() + " to " + album, Toast.LENGTH_SHORT).show();
            sheet.dismiss();
            setResult(RESULT_OK);
            finish();
        });
        newRow.addView(addBtn);
        root.addView(newRow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        sheet.setContentView(scroll);
        sheet.show();

        StatusHighlightManager.getHighlightsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    albumList.removeAllViews();
                    if (!snap.exists() || snap.getChildrenCount() == 0) {
                        TextView empty = new TextView(CreateHighlightActivity.this);
                        empty.setText("No existing albums");
                        empty.setTextSize(13);
                        empty.setTextColor(Color.GRAY);
                        albumList.addView(empty);
                        return;
                    }
                    for (DataSnapshot albumSnap : snap.getChildren()) {
                        String albumId = albumSnap.getKey();
                        if (albumId == null) continue;
                        long count = albumSnap.getChildrenCount();
                        String albumName = albumId;
                        DataSnapshot firstItem = albumSnap.getChildren().iterator().hasNext()
                                ? albumSnap.getChildren().iterator().next() : null;
                        if (firstItem != null) {
                            String n = firstItem.child("highlightAlbumName").getValue(String.class);
                            if (n != null && !n.isEmpty()) albumName = n;
                        }
                        final String fAlbumId = albumId;
                        final String fAlbumName = albumName;

                        LinearLayout row = new LinearLayout(CreateHighlightActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(12), dp(12), dp(12), dp(12));
                        GradientDrawable rowBg = new GradientDrawable();
                        rowBg.setCornerRadius(dp(10));
                        rowBg.setColor(Color.parseColor("#F5F5F5"));
                        row.setBackground(rowBg);
                        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        rowLp.bottomMargin = dp(8);
                        row.setLayoutParams(rowLp);

                        TextView icon = new TextView(CreateHighlightActivity.this);
                        icon.setText("⭐");
                        icon.setTextSize(22);
                        icon.setPadding(0, 0, dp(12), 0);
                        row.addView(icon);

                        LinearLayout info = new LinearLayout(CreateHighlightActivity.this);
                        info.setOrientation(LinearLayout.VERTICAL);
                        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        TextView tvName = new TextView(CreateHighlightActivity.this);
                        tvName.setText(fAlbumName);
                        tvName.setTextSize(15);
                        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
                        TextView tvCount = new TextView(CreateHighlightActivity.this);
                        tvCount.setText(count + " item" + (count != 1 ? "s" : ""));
                        tvCount.setTextSize(12);
                        tvCount.setTextColor(Color.GRAY);
                        info.addView(tvName);
                        info.addView(tvCount);
                        row.addView(info);

                        TextView addToExisting = new TextView(CreateHighlightActivity.this);
                        addToExisting.setText("+ Add");
                        addToExisting.setTextSize(13);
                        addToExisting.setTextColor(Color.parseColor("#6200EE"));
                        addToExisting.setTypeface(null, android.graphics.Typeface.BOLD);
                        row.addView(addToExisting);

                        row.setOnClickListener(v -> {
                            for (StatusItem item : items) {
                                StatusHighlightManager.addToHighlight(myUid, item, fAlbumId, fAlbumName);
                            }
                            Toast.makeText(CreateHighlightActivity.this,
                                    "Added " + items.size() + " to " + fAlbumName, Toast.LENGTH_SHORT).show();
                            sheet.dismiss();
                            setResult(RESULT_OK);
                            finish();
                        });
                        albumList.addView(row);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    albumList.removeAllViews();
                    TextView err = new TextView(CreateHighlightActivity.this);
                    err.setText("Could not load albums");
                    err.setTextColor(Color.RED);
                    albumList.addView(err);
                }
            });
    }

    // ── Grid adapter (multi-select) ────────────────────────────────────────
    // Instagram-style "load only what the cell can actually show": the old
    // code did Glide.load(url).override(480, 853) for EVERY cell — a fixed
    // full-story-portrait decode target regardless of how small this 3-column
    // picker cell actually is (~120-140dp on most phones, i.e. under half of
    // 480px wide). That meant every grid cell was decoding/holding a bitmap
    // several times larger than it could ever display — wasted memory and
    // slower scroll on a picker that can hold dozens of statuses. Fixed to
    // match ReelGridAdapter/HighlightsRowAdapter's pattern: CDN-derived
    // thumbnail sized to the REAL cell pixel size, WebP, RGB_565 (opaque
    // centerCrop cell, no alpha needed), a tiny blur-up placeholder while the
    // real thumb loads, and disk caching so re-opening this picker is instant.
    private static final int PICK_BLUR_SIZE = 16;
    private static final RequestOptions PICK_GRID_OPTIONS = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .format(DecodeFormat.PREFER_RGB_565)
            .centerCrop()
            .dontAnimate();

    private class PickAdapter extends RecyclerView.Adapter<PickAdapter.VH> {
        // Resolved once (first bind pass) so every cell requests the same,
        // correctly-sized derivative instead of recomputing per-bind.
        private int resolvedCellPx = 0;

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout fl = new FrameLayout(parent.getContext());
            int size = parent.getWidth() > 0 ? parent.getWidth() / 3 : dp(120);
            if (resolvedCellPx == 0) resolvedCellPx = size;
            fl.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            return new VH(fl);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(allItems.get(pos)); }
        @Override public int getItemCount() { return allItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView iv;
            final View dim;
            final ImageView ivCheck;
            VH(FrameLayout fl) {
                super(fl);
                iv = new ImageView(fl.getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                fl.addView(iv);

                dim = new View(fl.getContext());
                dim.setBackgroundColor(Color.parseColor("#66000000"));
                dim.setVisibility(View.GONE);
                dim.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                fl.addView(dim);

                ivCheck = new ImageView(fl.getContext());
                ivCheck.setImageResource(android.R.drawable.checkbox_on_background);
                ivCheck.setVisibility(View.GONE);
                FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(24), dp(24));
                checkLp.gravity = Gravity.TOP | Gravity.END;
                checkLp.setMargins(0, dp(6), dp(6), 0);
                ivCheck.setLayoutParams(checkLp);
                fl.addView(ivCheck);

                fl.setPadding(dp(1), dp(1), dp(1), dp(1));
            }
            void bind(StatusItem item) {
                String url = item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()
                        ? item.thumbnailUrl : item.mediaUrl;
                if (url != null && !url.isEmpty()) {
                    int cellPx = resolvedCellPx > 0 ? resolvedCellPx : dp(120);
                    String gridUrl = CloudinaryUploader.deriveThumbUrl(url, cellPx, "webp");
                    String blurUrl = CloudinaryUploader.deriveThumbUrl(url, PICK_BLUR_SIZE, "webp");
                    Glide.with(iv)
                            .load(gridUrl)
                            .thumbnail(Glide.with(iv).load(blurUrl).apply(PICK_GRID_OPTIONS))
                            .apply(PICK_GRID_OPTIONS)
                            .into(iv);
                    iv.setBackgroundColor(0);
                } else {
                    iv.setImageDrawable(null);
                    iv.setBackgroundColor(safeColor(item.bgColor, "#6C5CE7"));
                }
                boolean isSel = item.id != null && selected.containsKey(item.id);
                dim.setVisibility(isSel ? View.VISIBLE : View.GONE);
                ivCheck.setVisibility(isSel ? View.VISIBLE : View.GONE);
                itemView.setOnClickListener(v -> {
                    if (item.id == null) return;
                    if (selected.containsKey(item.id)) selected.remove(item.id);
                    else selected.put(item.id, item);
                    notifyItemChanged(getAdapterPosition());
                    updateSelectionBar();
                });
            }
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private int safeColor(String hex, String fallback) {
        try { return Color.parseColor(hex); } catch (Exception e) {
            try { return Color.parseColor(fallback); } catch (Exception e2) { return 0xFF6C5CE7; }
        }
    }

    /** Themed surface color (white in light mode, dark surface in dark mode) —
     *  same ?attr/colorSurface approach used app-wide, instead of a hardcoded
     *  color that would look wrong in dark mode. */
    private GradientDrawable surfaceBg() {
        android.util.TypedValue tv = new android.util.TypedValue();
        int color;
        try {
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true);
            color = tv.resourceId != 0 ? getResources().getColor(tv.resourceId, getTheme()) : tv.data;
        } catch (Exception e) {
            color = Color.WHITE;
        }
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        return d;
    }
}
