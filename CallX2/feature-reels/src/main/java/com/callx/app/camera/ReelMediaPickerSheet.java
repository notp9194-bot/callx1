package com.callx.app.camera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelMediaPickerSheet — WhatsApp/Instagram-style media picker bottom sheet
 * for the Reel camera screen.
 *
 * Features:
 *  ✅ Filter tabs: All / Photos / Videos
 *  ✅ 4-column grid of recent gallery items with Glide thumbnails
 *  ✅ Video duration badges
 *  ✅ Multi-select with ordered number badges
 *  ✅ Floating send bar: count label + Clear + Use button
 *  ✅ Proper READ_MEDIA / READ_EXTERNAL_STORAGE permission check
 *  ✅ Empty state when no media or no permission
 *
 * Usage:
 *   ReelMediaPickerSheet.newInstance()
 *       .setCallback(uris -> { ... })
 *       .show(getSupportFragmentManager(), "media_picker");
 */
public class ReelMediaPickerSheet extends BottomSheetDialogFragment {

    public interface Callback {
        /** Called when the user taps "Use" with 1+ selected items. */
        void onMediaSelected(List<ReelMediaLoader.Item> items);
    }

    private static final int GRID_COLS = 4;

    private Callback callback;

    private ReelMediaGridAdapter adapter;
    private ExecutorService      executor;

    private TextView  tabAll, tabPhotos, tabVideos;
    private RecyclerView rvGrid;
    private LinearLayout llEmpty, llSendBar;
    private TextView  tvCount, btnClear, btnUse;

    private ReelMediaLoader.Filter activeFilter = ReelMediaLoader.Filter.ALL;

    // ── Factory ───────────────────────────────────────────────────────────

    public static ReelMediaPickerSheet newInstance() {
        return new ReelMediaPickerSheet();
    }

    public ReelMediaPickerSheet setCallback(Callback cb) {
        this.callback = cb;
        return this;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ReelMediaSheetTheme);
        executor = Executors.newSingleThreadExecutor();
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_reel_media_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        setupGrid();
        setupTabs();
        setupSendBar();
        expandSheet();
        loadMedia();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private void bindViews(View root) {
        tabAll    = root.findViewById(R.id.tab_all);
        tabPhotos = root.findViewById(R.id.tab_photos);
        tabVideos = root.findViewById(R.id.tab_videos);
        rvGrid    = root.findViewById(R.id.rv_media_grid);
        llEmpty   = root.findViewById(R.id.ll_empty_state);
        llSendBar = root.findViewById(R.id.ll_send_bar);
        tvCount   = root.findViewById(R.id.tv_selected_count);
        btnClear  = root.findViewById(R.id.btn_clear_selection);
        btnUse    = root.findViewById(R.id.btn_use_media);
    }

    private void setupGrid() {
        int screenW   = getResources().getDisplayMetrics().widthPixels;
        int cellSizePx = screenW / GRID_COLS;

        adapter = new ReelMediaGridAdapter(requireContext(), item -> {
            int count = adapter.toggle(item);
            updateSendBar(count);
        }, cellSizePx);

        rvGrid.setLayoutManager(new GridLayoutManager(requireContext(), GRID_COLS));
        rvGrid.setAdapter(adapter);
        rvGrid.setHasFixedSize(true);
    }

    private void setupTabs() {
        tabAll.setOnClickListener(v    -> switchTab(ReelMediaLoader.Filter.ALL));
        tabPhotos.setOnClickListener(v -> switchTab(ReelMediaLoader.Filter.PHOTOS));
        tabVideos.setOnClickListener(v -> switchTab(ReelMediaLoader.Filter.VIDEOS));
    }

    private void switchTab(ReelMediaLoader.Filter filter) {
        if (activeFilter == filter) return;
        activeFilter = filter;
        updateTabVisuals();
        adapter.clearSelection();
        updateSendBar(0);
        loadMedia();
    }

    private void updateTabVisuals() {
        tabAll.setBackgroundResource(activeFilter == ReelMediaLoader.Filter.ALL
            ? R.drawable.bg_reel_tab_active : R.drawable.bg_reel_tab_inactive);
        tabAll.setTextColor(activeFilter == ReelMediaLoader.Filter.ALL
            ? 0xFFFFFFFF : 0x99FFFFFF);

        tabPhotos.setBackgroundResource(activeFilter == ReelMediaLoader.Filter.PHOTOS
            ? R.drawable.bg_reel_tab_active : R.drawable.bg_reel_tab_inactive);
        tabPhotos.setTextColor(activeFilter == ReelMediaLoader.Filter.PHOTOS
            ? 0xFFFFFFFF : 0x99FFFFFF);

        tabVideos.setBackgroundResource(activeFilter == ReelMediaLoader.Filter.VIDEOS
            ? R.drawable.bg_reel_tab_active : R.drawable.bg_reel_tab_inactive);
        tabVideos.setTextColor(activeFilter == ReelMediaLoader.Filter.VIDEOS
            ? 0xFFFFFFFF : 0x99FFFFFF);
    }

    private void setupSendBar() {
        btnClear.setOnClickListener(v -> {
            adapter.clearSelection();
            updateSendBar(0);
        });
        btnUse.setOnClickListener(v -> {
            List<ReelMediaLoader.Item> selected = adapter.getSelected();
            if (selected.isEmpty()) return;
            if (callback != null) callback.onMediaSelected(selected);
            dismissAllowingStateLoss();
        });
    }

    private void updateSendBar(int count) {
        if (count > 0) {
            llSendBar.setVisibility(View.VISIBLE);
            tvCount.setText(count == 1 ? "1 item selected" : count + " items selected");
        } else {
            llSendBar.setVisibility(View.GONE);
        }
    }

    private void expandSheet() {
        // Expand to full sheet height immediately
        View parent = (View) requireView().getParent();
        if (parent != null) {
            BottomSheetBehavior<View> bsb = BottomSheetBehavior.from(parent);
            bsb.setState(BottomSheetBehavior.STATE_EXPANDED);
            bsb.setSkipCollapsed(true);
        }
    }

    // ── Media loading ─────────────────────────────────────────────────────

    private void loadMedia() {
        if (!hasMediaPermission()) {
            showEmpty();
            return;
        }
        final ReelMediaLoader.Filter filter = activeFilter;
        executor.execute(() -> {
            List<ReelMediaLoader.Item> items =
                ReelMediaLoader.load(requireContext(), filter);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (items.isEmpty()) {
                    showEmpty();
                } else {
                    rvGrid.setVisibility(View.VISIBLE);
                    llEmpty.setVisibility(View.GONE);
                    adapter.setItems(items);
                }
            });
        });
    }

    private void showEmpty() {
        rvGrid.setVisibility(View.GONE);
        llEmpty.setVisibility(View.VISIBLE);
    }

    private boolean hasMediaPermission() {
        if (getContext() == null) return false;
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? Manifest.permission.READ_MEDIA_IMAGES
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(requireContext(), perm)
            == PackageManager.PERMISSION_GRANTED;
    }
}
