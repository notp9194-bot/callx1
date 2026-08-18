package com.callx.app.camera;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraMediaSheetController
 * ──────────────────────────
 * Wires up the recent-media bottom sheet embedded in ReelCameraActivity.
 *
 * Collapsed (peek):
 *   • Horizontal RecyclerView strip of recent media thumbnails.
 *   • Matches the look in Screenshot_20260727_174500: a narrow row of
 *     square thumbnails sitting just above the camera controls.
 *
 * Expanded (full):
 *   • Crossfades into a 4-column Recents grid with a close button and
 *     "Recents" title — identical to the chat attach-sheet expanded state
 *     shown in Screenshot_20260727_174417.
 *   • Driven by BottomSheetBehavior's slide offset → smooth animation.
 *
 * Tapping any thumbnail fires {@link Callbacks#onMediaPicked(Uri, boolean)}
 * so ReelCameraActivity can forward it to ReelEditorActivity / ReelUploadActivity.
 *
 * Media loading is done inline (no dependency on feature-chat's
 * RecentMediaLoader) via a direct MediaStore query on a background executor.
 */
public final class CameraMediaSheetController {

    // ── Constants ─────────────────────────────────────────────────────────
    private static final int  STRIP_LIMIT      = 30;   // items in horizontal strip
    private static final int  GRID_PAGE_SIZE   = 60;   // items per grid page
    /** Fraction of collapse→expand drag over which the crossfade runs (0–1). */
    private static final float FADE_END        = 0.35f;
    /** Peek height in dp — drag-handle (14dp) + strip (72dp) + tiny gap (4dp). */
    private static final int  PEEK_DP          = 90;

    // ── Callback ──────────────────────────────────────────────────────────
    public interface Callbacks {
        /** User tapped a thumbnail — open in the reel editor/uploader. */
        void onMediaPicked(Uri uri, boolean isVideo);
    }

    // ── State ─────────────────────────────────────────────────────────────
    private final ReelCameraActivity  activity;
    private final Callbacks           callbacks;
    private final ExecutorService     executor = Executors.newSingleThreadExecutor();

    private BottomSheetBehavior<View> behavior;
    private StripAdapter              stripAdapter;
    private GridAdapter               gridAdapter;
    private boolean                   gridLoaded = false;

    // ── Constructor ───────────────────────────────────────────────────────
    public CameraMediaSheetController(ReelCameraActivity activity, Callbacks callbacks) {
        this.activity  = activity;
        this.callbacks = callbacks;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Public entry point — call from ReelCameraActivity.onCreate()
    // ══════════════════════════════════════════════════════════════════════
    public void setup() {
        View sheetRoot = activity.findViewById(R.id.cms_root);
        if (sheetRoot == null) return;

        // ── BottomSheetBehavior ───────────────────────────────────────────
        behavior = BottomSheetBehavior.from(sheetRoot);
        int peekPx = dp(PEEK_DP);
        behavior.setPeekHeight(peekPx, false);
        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        behavior.setHideable(false);
        behavior.setSkipCollapsed(false);

        // ── Views ─────────────────────────────────────────────────────────
        View         stripContainer  = activity.findViewById(R.id.cms_strip_container);
        RecyclerView stripRv         = activity.findViewById(R.id.cms_strip_rv);
        View         expandedPanel   = activity.findViewById(R.id.cms_expanded_panel);
        RecyclerView gridRv          = activity.findViewById(R.id.cms_grid_rv);
        View         emptyText       = activity.findViewById(R.id.cms_empty_text);
        View         closeBtn        = activity.findViewById(R.id.cms_btn_close);
        View         recentsDropdown = activity.findViewById(R.id.cms_recents_dropdown);

        // ── Strip adapter ─────────────────────────────────────────────────
        stripAdapter = new StripAdapter(activity, callbacks);
        LinearLayoutManager lm = new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false);
        stripRv.setLayoutManager(lm);
        stripRv.setAdapter(stripAdapter);
        stripRv.setHasFixedSize(true);
        stripRv.setItemViewCacheSize(20);

        // ── Grid adapter ──────────────────────────────────────────────────
        gridAdapter = new GridAdapter(activity, callbacks);
        gridRv.setLayoutManager(new GridLayoutManager(activity, 4));
        gridRv.setAdapter(gridAdapter);
        gridRv.setHasFixedSize(true);
        gridRv.setItemViewCacheSize(16);

        // ── Close / collapse ──────────────────────────────────────────────
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> behavior.setState(BottomSheetBehavior.STATE_COLLAPSED));
        }

        // ── Recents dropdown (folder picker stub — expands sheet if not already expanded) ──
        if (recentsDropdown != null) {
            recentsDropdown.setOnClickListener(v -> {
                if (behavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                }
                // TODO: wire AttachSheetFolderPicker here if needed
            });
        }

        // ── Slide callback — crossfade + controls-bar fade ────────────────
        View controlsBar = activity.findViewById(R.id.camera_controls_bar);
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View sheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED && !gridLoaded) {
                    loadGridMedia(gridRv, emptyText);
                    gridLoaded = true;
                }
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    if (expandedPanel != null) expandedPanel.setVisibility(View.VISIBLE);
                }
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    if (expandedPanel != null) {
                        expandedPanel.setAlpha(0f);
                        expandedPanel.setVisibility(View.GONE);
                    }
                    if (stripContainer != null) {
                        stripContainer.setAlpha(1f);
                        stripContainer.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onSlide(@NonNull View sheet, float slideOffset) {
                // slideOffset: 0 = fully collapsed, 1 = fully expanded
                applySlideOffset(slideOffset,
                        stripContainer, expandedPanel, controlsBar);
            }
        });

        // ── Initial media load ────────────────────────────────────────────
        loadStripMedia(emptyText);
    }

    // ── Slide offset → crossfade logic ────────────────────────────────────
    private void applySlideOffset(float offset,
                                   View stripContainer,
                                   View expandedPanel,
                                   View controlsBar) {
        // Fraction within the crossfade window [0, FADE_END]
        float fraction = Math.min(1f, offset / FADE_END);

        // Strip fades OUT
        if (stripContainer != null) {
            stripContainer.setAlpha(1f - fraction);
            stripContainer.setVisibility(fraction >= 1f ? View.INVISIBLE : View.VISIBLE);
        }

        // Expanded panel fades IN — make VISIBLE before alpha starts
        if (expandedPanel != null) {
            if (fraction > 0f && expandedPanel.getVisibility() != View.VISIBLE) {
                expandedPanel.setVisibility(View.VISIBLE);
            }
            expandedPanel.setAlpha(fraction);
        }

        // Camera controls bar fades out as sheet expands past 40%
        if (controlsBar != null) {
            float ctrlFraction = Math.max(0f, Math.min(1f, (offset - 0.30f) / 0.25f));
            controlsBar.setAlpha(1f - ctrlFraction);
            controlsBar.setVisibility(ctrlFraction >= 1f ? View.INVISIBLE : View.VISIBLE);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Media loading (self-contained — no dependency on feature-chat)
    // ══════════════════════════════════════════════════════════════════════

    private boolean hasMediaPermission() {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadStripMedia(View emptyText) {
        if (!hasMediaPermission()) return;
        executor.execute(() -> {
            List<MediaItem> items = queryRecent(STRIP_LIMIT);
            if (activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                stripAdapter.submit(items);
                if (emptyText != null)
                    emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void loadGridMedia(RecyclerView gridRv, View emptyText) {
        if (!hasMediaPermission()) return;
        executor.execute(() -> {
            List<MediaItem> items = queryRecent(GRID_PAGE_SIZE);
            if (activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                gridAdapter.submit(items);
                if (emptyText != null)
                    emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                if (gridRv != null) gridRv.scrollToPosition(0);
                // Infinite scroll — load more as user nears bottom
                gridRv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    private final boolean[] loading  = {false};
                    private final boolean[] noMore   = {false};
                    @Override
                    public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                        if (dy <= 0 || loading[0] || noMore[0]) return;
                        GridLayoutManager glm = (GridLayoutManager) rv.getLayoutManager();
                        if (glm == null) return;
                        int lastVisible = glm.findLastVisibleItemPosition();
                        int total       = glm.getItemCount();
                        if (lastVisible >= total - 12) {
                            loading[0] = true;
                            int offset = gridAdapter.getItemCount();
                            executor.execute(() -> {
                                List<MediaItem> more = queryRecent(GRID_PAGE_SIZE, offset);
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                activity.runOnUiThread(() -> {
                                    if (activity.isFinishing() || activity.isDestroyed()) return;
                                    if (more.isEmpty()) { noMore[0] = true; return; }
                                    gridAdapter.append(more);
                                    loading[0] = false;
                                });
                            });
                        }
                    }
                });
            });
        });
    }

    /** Query recent images + videos, merged + sorted by date descending. */
    private List<MediaItem> queryRecent(int limit) {
        return queryRecent(limit, 0);
    }

    private List<MediaItem> queryRecent(int limit, int offset) {
        List<MediaItem> all = new ArrayList<>();
        all.addAll(queryImages(limit + offset));
        all.addAll(queryVideos(limit + offset));
        // sort by dateAdded descending
        Collections.sort(all, (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec));
        // apply offset + limit
        int from = Math.min(offset, all.size());
        int to   = Math.min(offset + limit, all.size());
        return all.subList(from, to);
    }

    private List<MediaItem> queryImages(int limit) {
        List<MediaItem> out = new ArrayList<>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
        };
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor c = activity.getContentResolver().query(base, proj, null, null, order)) {
            if (c == null) return out;
            int idIdx   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            while (c.moveToNext() && out.size() < limit) {
                long id = c.getLong(idIdx);
                long date = c.getLong(dateIdx);
                out.add(new MediaItem(ContentUris.withAppendedId(base, id), false, 0L, date));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<MediaItem> queryVideos(int limit) {
        List<MediaItem> out = new ArrayList<>();
        Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION
        };
        String order = MediaStore.Video.Media.DATE_ADDED + " DESC";
        try (Cursor c = activity.getContentResolver().query(base, proj, null, null, order)) {
            if (c == null) return out;
            int idIdx   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int durIdx  = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            while (c.moveToNext() && out.size() < limit) {
                long id  = c.getLong(idIdx);
                long date = c.getLong(dateIdx);
                long dur = durIdx >= 0 ? c.getLong(durIdx) : 0L;
                out.add(new MediaItem(ContentUris.withAppendedId(base, id), true, dur, date));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ── dp helper ─────────────────────────────────────────────────────────
    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Data model
    // ══════════════════════════════════════════════════════════════════════
    static final class MediaItem {
        final Uri  uri;
        final boolean isVideo;
        final long durationMs;
        final long dateAddedSec;

        MediaItem(Uri uri, boolean isVideo, long durationMs, long dateAddedSec) {
            this.uri          = uri;
            this.isVideo      = isVideo;
            this.durationMs   = durationMs;
            this.dateAddedSec = dateAddedSec;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Strip Adapter — horizontal strip (collapsed state)
    // ══════════════════════════════════════════════════════════════════════
    private static final class StripAdapter
            extends RecyclerView.Adapter<StripAdapter.VH> {

        private final Context   ctx;
        private final Callbacks callbacks;
        private final RequestOptions opts;
        private List<MediaItem> items = Collections.emptyList();

        StripAdapter(Context ctx, Callbacks callbacks) {
            this.ctx       = ctx;
            this.callbacks = callbacks;
            int cellPx = Math.round(72 * ctx.getResources().getDisplayMetrics().density);
            this.opts = new RequestOptions()
                    .override(cellPx, cellPx)
                    .centerCrop()
                    .format(DecodeFormat.PREFER_RGB_565);
        }

        void submit(List<MediaItem> newItems) {
            this.items = new ArrayList<>(newItems);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.item_camera_strip_thumb, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MediaItem item = items.get(pos);
            Glide.with(ctx).asBitmap().load(item.uri).apply(opts).into(h.thumb);
            if (item.isVideo && item.durationMs > 0) {
                h.duration.setText(formatDur(item.durationMs));
                h.duration.setVisibility(View.VISIBLE);
            } else {
                h.duration.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> callbacks.onMediaPicked(item.uri, item.isVideo));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView  duration;
            VH(@NonNull View v) {
                super(v);
                thumb    = v.findViewById(R.id.strip_thumb);
                duration = v.findViewById(R.id.strip_duration);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Grid Adapter — 4-column grid (expanded state)
    // ══════════════════════════════════════════════════════════════════════
    static final class GridAdapter
            extends RecyclerView.Adapter<GridAdapter.VH> {

        private final Context   ctx;
        private final Callbacks callbacks;
        private final RequestOptions opts;
        private final List<MediaItem> items = new ArrayList<>();

        GridAdapter(Context ctx, Callbacks callbacks) {
            this.ctx       = ctx;
            this.callbacks = callbacks;
            // Cell size ≈ screen width / 4
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int cellPx  = screenW / 4;
            this.opts = new RequestOptions()
                    .override(cellPx, cellPx)
                    .centerCrop()
                    .format(DecodeFormat.PREFER_RGB_565);
        }

        void submit(List<MediaItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        void append(List<MediaItem> more) {
            int from = items.size();
            items.addAll(more);
            notifyItemRangeInserted(from, more.size());
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Square cells: each cell height = parent width / 4
            View v = LayoutInflater.from(ctx).inflate(R.layout.item_camera_grid_thumb, parent, false);
            int cellPx = parent.getWidth() > 0 ? parent.getWidth() / 4
                    : ctx.getResources().getDisplayMetrics().widthPixels / 4;
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            if (lp == null) lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellPx);
            else lp.height = cellPx;
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MediaItem item = items.get(pos);
            Glide.with(ctx).asBitmap().load(item.uri).apply(opts).into(h.thumb);
            if (item.isVideo && item.durationMs > 0) {
                h.duration.setText(formatDur(item.durationMs));
                h.duration.setVisibility(View.VISIBLE);
            } else {
                h.duration.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> callbacks.onMediaPicked(item.uri, item.isVideo));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView  duration;
            VH(@NonNull View v) {
                super(v);
                thumb    = v.findViewById(R.id.grid_thumb);
                duration = v.findViewById(R.id.grid_duration);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private static String formatDur(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }
}
