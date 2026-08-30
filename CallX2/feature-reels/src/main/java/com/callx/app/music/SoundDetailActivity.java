package com.callx.app.music;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;

/**
 * SoundDetailActivity — thin host only (matches SoundDetailSheetFragment's pattern).
 *
 * Sara logic SoundDetailFragment mein hai ("single source of truth" for the
 * Sound Detail screen — waveform + player, creator card, reels grid, related
 * sounds, save/use actions, etc). Yeh class sirf:
 *   1. Intent extras ko SoundDetailFragment ke args mein map karti hai
 *   2. Fragment ko fullscreen add karti hai (isSheet = false → back arrow)
 *   3. Close callback ke roop mein finish() deti hai
 *
 * Koi duplicate code nahi.
 */
public class SoundDetailActivity extends AppCompatActivity {
    public static final String EXTRA_SOUND_ID = "extra_sound_id";
    public static final String EXTRA_SOUND_TITLE = "extra_sound_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_SOUND_URL = "extra_sound_url";
    public static final String EXTRA_COVER_URL = "extra_cover_url";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";
    public static final String EXTRA_BPM = "extra_bpm";
    public static final String EXTRA_GENRE = "extra_genre";
    public static final String EXTRA_CREATOR_UID = "extra_creator_uid";
    public static final String EXTRA_ORIGINAL_AUDIO_URL = "extra_original_audio_url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout frame = new FrameLayout(this);
        frame.setId(android.R.id.content);
        setContentView(frame, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (savedInstanceState == null) {
            SoundDetailFragment fragment = SoundDetailFragment.newInstance(
                getIntent().getStringExtra(EXTRA_SOUND_ID),
                getIntent().getStringExtra(EXTRA_SOUND_TITLE),
                getIntent().getStringExtra(EXTRA_ARTIST),
                getIntent().getStringExtra(EXTRA_COVER_URL),
                getIntent().getStringExtra(EXTRA_SOUND_URL),
                (int) getIntent().getLongExtra(EXTRA_DURATION_MS, 0),
                getIntent().getStringExtra(EXTRA_GENRE),
                getIntent().getIntExtra(EXTRA_BPM, 0),
                getIntent().getStringExtra(EXTRA_CREATOR_UID),
                getIntent().getStringExtra(EXTRA_ORIGINAL_AUDIO_URL),
                false /* isSheet = false → back arrow, no drag handle */
            );
            fragment.setOnCloseListener(this::finish);

            getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
        }
    }

    public static class ReelThumbItem {
        public String reelId, thumbnailUrl, videoUrl;
        public String uid;
        public boolean isOriginalCreator;
        public long viewsCount;
        public ReelThumbItem() {}
        public ReelThumbItem(String id, String t, String v) { reelId=id; thumbnailUrl=t; videoUrl=v; }
    }

    /** Simple data model for related/recommended sounds. */
    public static class RelatedItem {
        public String soundId, title, artist, coverUrl, audioUrl;
        /** Alias for soundId used by SoundDetailFragment. */
        public String id;
        public int reelCount;
        public RelatedItem() {}
        public RelatedItem(String id, String title, String artist, String coverUrl, String audioUrl) {
            this.soundId = id; this.id = id; this.title = title;
            this.artist = artist; this.coverUrl = coverUrl; this.audioUrl = audioUrl;
        }
    }

    /** RecyclerView adapter displaying reel thumbnail images for this sound. */
    public static class ReelThumbAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<ReelThumbAdapter.VH> {
        public interface OnItemClick { void onClick(int position); }
        /** Mirrors UserReelsActivity's grid long-press → mini-player peek. */
        public interface OnItemLongPress { void onLongPress(int position); }
        private final java.util.List<ReelThumbItem> items;
        private final OnItemClick listener;
        private OnItemLongPress longPressListener;

        // ── Cell height (ported from UserReelsActivity's ReelGridAdapter) ──
        // Same formula as the profile reel grid: cell width = screen width
        // split across a 3-column span (minus the same 2dp gutter
        // WhiteGridDecoration reserves), height = that width at a 16:9
        // ratio. Computed ONCE (cached statically — it's the same for every
        // instance of this adapter within a process) and applied directly
        // in onCreateViewHolder(), so every "Reels with this sound" grid
        // (SoundDetailFragment, SoundDetailBottomSheet) now matches the
        // profile grid's tall, modern card size instead of the old fixed
        // 160dp wrap_content tile.
        private static final int GRID_SPAN_COUNT = 3;
        private static int cachedCellHeightPx = -1;
        private static int cachedCellWidthPx  = -1;

        // ── Data-saving thumb sizing (ported from ReelGridAdapter) ──
        // Previously this grid loaded item.thumbnailUrl straight into Glide —
        // the raw, full-size original, freshly re-downloaded every time
        // (no CDN resize, unlike UserReelsActivity's grid). Now derive a
        // CDN-resized/webp thumb sized to the actual grid cell, same as the
        // profile reel grid, so it's small AND reuses Glide's disk cache.
        private static final int GRID_THUMB_SIZE_WIFI     = 300;
        private static final int GRID_THUMB_SIZE_CELLULAR = 200;
        private static int cachedGridThumbSize = -1;

        private static int resolveCellHeightPx(android.content.Context ctx) {
            if (cachedCellHeightPx > 0) return cachedCellHeightPx;
            resolveCellWidthPx(ctx); // populates cachedCellWidthPx as a side effect the first time
            cachedCellHeightPx = Math.round(cachedCellWidthPx * 16f / 9f);
            return cachedCellHeightPx;
        }

        /**
         * ✅ FIX (oversized bitmap decode): onBindViewHolder() was loading the
         * CDN-resized thumb via Glide with no .override() at all — Glide
         * decoded whatever pixel size CloudinaryUploader.deriveThumbUrl()
         * happened to return (a fixed 300/200px SQUARE, see
         * resolveGridThumbSize()) into a view that's actually
         * cellWidthPx × 16:9-taller-than-that, i.e. every cell was decoding
         * (and holding in memory) a needlessly large, wrong-aspect square
         * bitmap Glide then had to crop down anyway. Locking the decode size
         * to the ACTUAL grid cell dimensions (same width this method already
         * computes for the cell's LayoutParams, times a small headroom
         * factor so a slightly-larger-than-cell CDN thumb still centerCrops
         * cleanly rather than upscaling) keeps decode cost proportional to
         * what's actually on screen.
         */
        private static int resolveCellWidthPx(android.content.Context ctx) {
            if (cachedCellWidthPx > 0) return cachedCellWidthPx;
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int spacingPx = Math.round(2 * dm.density); // matches WhiteGridDecoration
            cachedCellWidthPx = (dm.widthPixels - spacingPx * (GRID_SPAN_COUNT + 1)) / GRID_SPAN_COUNT;
            return cachedCellWidthPx;
        }

        private static int resolveGridThumbSize(android.content.Context ctx) {
            if (cachedGridThumbSize > 0) return cachedGridThumbSize;
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        ctx.getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                android.net.Network net = cm != null ? cm.getActiveNetwork() : null;
                android.net.NetworkCapabilities nc = (cm != null && net != null) ? cm.getNetworkCapabilities(net) : null;
                boolean unmetered = nc != null && (
                        nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
                cachedGridThumbSize = unmetered ? GRID_THUMB_SIZE_WIFI : GRID_THUMB_SIZE_CELLULAR;
            } catch (Exception e) {
                cachedGridThumbSize = GRID_THUMB_SIZE_WIFI; // safe default
            }
            return cachedGridThumbSize;
        }

        public ReelThumbAdapter() { items = new java.util.ArrayList<>(); listener = null; setHasStableIds(true); }
        public ReelThumbAdapter(java.util.List<ReelThumbItem> items, OnItemClick listener) {
            this.items = items != null ? items : new java.util.ArrayList<>();
            this.listener = listener;
            // ULTRA: same stable-ID pattern as SoundReelsAdapter/PostsFeedActivity/
            // UserReelsActivity's grid adapters — lets DiffUtil (see
            // SoundDetailFragment#sortAndApplyReelItems's ReelThumbDiffCallback,
            // which already keys identity off reelId) and RecyclerView track
            // each cell's ViewHolder by reelId instead of by position. Without
            // this, a dispatched move/change op can rebind the wrong ViewHolder
            // when positions shift (pagination insert, live add/remove,
            // sortAndApplyReelItems' reorder) — with it, RecyclerView follows
            // the actual item, so an already-bound cell whose position moved
            // isn't needlessly rebound (and its already-loaded Glide thumbnail
            // isn't needlessly reloaded).
            setHasStableIds(true);
        }

        /** Stable ID = the reelId string's hash — mirrors SoundReelsAdapter#getItemId.
         *  Two different reelId values colliding is practically never going to
         *  happen, and even if it did the only cost is a spurious rebind, not a
         *  crash. Falls back to `position` only for the (should-be-impossible)
         *  case of a null reelId, so RecyclerView still gets a stable-shaped ID
         *  rather than crashing on autoboxing. */
        @Override
        public long getItemId(int position) {
            String id = items.get(position).reelId;
            return id != null ? id.hashCode() : position;
        }

        public void setOnItemLongPress(OnItemLongPress l) { this.longPressListener = l; }

        public void setItems(java.util.List<ReelThumbItem> data) {
            items.clear();
            if (data != null) items.addAll(data);
            notifyDataSetChanged();
        }
        private java.util.List<ReelThumbItem> items_mutable() { return new java.util.ArrayList<>(items); }

        @androidx.annotation.NonNull
        @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int viewType) {
            // Dedicated sound-detail grid cell (Instagram audio-page style):
            // square tile + top-left "Original" pill + bottom-left eye/view-count.
            android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(com.callx.app.reels.R.layout.item_sound_reel_thumb, parent, false);
            // Apply the precomputed 16:9 cell height once at creation time —
            // no post()/re-measure needed (same pattern as UserReelsActivity).
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            int cellHeight = resolveCellHeightPx(parent.getContext());
            if (lp == null) lp = new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, cellHeight);
            else lp.height = cellHeight;
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            ReelThumbItem item = items.get(pos);
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                int thumbSize = resolveGridThumbSize(h.iv.getContext());
                String gridUrl = com.callx.app.utils.CloudinaryUploader.deriveThumbUrl(
                        item.thumbnailUrl, thumbSize, "webp");
                // .override() locked to the actual grid cell size (see
                // resolveCellWidthPx()/resolveCellHeightPx() FIX doc above)
                // instead of decoding whatever raw size the CDN thumb comes
                // back at.
                int cellW = resolveCellWidthPx(h.iv.getContext());
                int cellH = resolveCellHeightPx(h.iv.getContext());
                com.bumptech.glide.Glide.with(h.iv.getContext()).load(gridUrl)
                    .centerCrop().override(cellW, cellH).into(h.iv);
            }
            if (h.tvViews != null) h.tvViews.setText(formatViews(item.viewsCount));
            if (h.tvOriginal != null)
                h.tvOriginal.setVisibility(item.isOriginalCreator ? android.view.View.VISIBLE : android.view.View.GONE);
            h.itemView.setOnClickListener(v -> { int p = h.getAdapterPosition(); if (p >= 0 && listener != null) listener.onClick(p); });
            // ULTRA (UserReelsActivity pattern): long-press → mini-player
            // peek preview instead of (or before) opening the full player.
            h.itemView.setOnLongClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p >= 0 && longPressListener != null) { longPressListener.onLongPress(p); return true; }
                return false;
            });
        }

        private static String formatViews(long n) {
            if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0);
            if (n >= 1_000) return String.format(java.util.Locale.US, "%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }

        @Override public int getItemCount() { return items.size(); }

        public static class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final android.widget.ImageView iv;
            final android.widget.TextView tvViews;
            final android.widget.TextView tvOriginal;
            VH(android.view.View v) {
                super(v);
                iv = v.findViewById(com.callx.app.reels.R.id.iv_media_thumb);
                tvViews = v.findViewById(com.callx.app.reels.R.id.tv_views_overlay);
                tvOriginal = v.findViewById(com.callx.app.reels.R.id.tv_original_badge);
            }
        }
    }

    /** Adapter for the related sounds list in SoundDetailFragment. */
    public static class RelatedAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<RelatedAdapter.VH> {
        public interface OnItemClick { void onClick(RelatedItem item); }
        // ✅ OPT (ULTRA): owns its own copy of the list instead of holding the
        // Fragment's live `relatedItems` reference directly. That's what
        // makes submitList()'s diff below meaningful — diffing a list against
        // itself (the old bug: constructor took the caller's mutable list
        // as-is, so "old" and "new" were literally the same object by the
        // time a diff would run) can never find a difference.
        private final java.util.List<RelatedItem> items;
        private final OnItemClick listener;
        public RelatedAdapter(java.util.List<RelatedItem> items, OnItemClick listener) {
            this.items = items != null ? new java.util.ArrayList<>(items) : new java.util.ArrayList<>();
            this.listener = listener;
        }
        /**
         * PERF (ULTRA): replaces {@code setAdapter(new RelatedAdapter(...))}
         * on every {@code loadRelatedSounds()} call. That pattern threw away
         * a perfectly reusable adapter (and its RecyclerView view-holder
         * pool) each time and forced a full rebind of every visible row.
         * Reused across calls, this diffs the held copy against the
         * incoming list and dispatches only the inserts/removes/changes that
         * actually happened — a no-op if nothing changed, single-row ops if
         * the related list is later wired to something live. Genre-scoped
         * lists here are small (SoundDetailCache caps them), so the diff
         * runs synchronously on the caller's thread same as ReelThumbAdapter
         * does for its steady-state reorder path.
         */
        public void submitList(java.util.List<RelatedItem> newItems) {
            java.util.List<RelatedItem> safeNew = newItems != null
                ? new java.util.ArrayList<>(newItems) : new java.util.ArrayList<>();
            androidx.recyclerview.widget.DiffUtil.DiffResult diff =
                androidx.recyclerview.widget.DiffUtil.calculateDiff(new RelatedDiffCallback(items, safeNew));
            items.clear();
            items.addAll(safeNew);
            diff.dispatchUpdatesTo(this);
        }
        /** Identity by soundId; content equality by the fields onBindViewHolder()
         *  actually renders (title text, cover image). */
        private static class RelatedDiffCallback extends androidx.recyclerview.widget.DiffUtil.Callback {
            private final java.util.List<RelatedItem> oldList, newList;
            RelatedDiffCallback(java.util.List<RelatedItem> oldList, java.util.List<RelatedItem> newList) {
                this.oldList = oldList; this.newList = newList;
            }
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return newList.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                String a = oldList.get(oldPos).id, b = newList.get(newPos).id;
                return a != null && a.equals(b);
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                RelatedItem a = oldList.get(oldPos), b = newList.get(newPos);
                return java.util.Objects.equals(a.title, b.title)
                    && java.util.Objects.equals(a.coverUrl, b.coverUrl);
            }
        }
        @androidx.annotation.NonNull @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int vt) {
            // ✅ OPT (was: LinearLayout/ImageView/TextView built by hand here,
            // with manual density math for the 80/120dp sizes, on every single
            // onCreateViewHolder() call) — now a plain XML inflate of
            // item_sound_related_row.xml, which is exactly the same view tree.
            // Cheaper per call (LayoutInflater's cached/compiled parse beats
            // three View constructors + manual setLayoutParams/setPadding
            // calls) and the row is now editable in the layout editor instead
            // of buried in Java.
            android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(com.callx.app.reels.R.layout.item_sound_related_row, parent, false);
            return new VH(v);
        }
        // Matches onCreateViewHolder's dp80 ivCover size below in px, cached
        // once per process (same pattern as ReelThumbAdapter's cell sizing).
        private static int cachedCoverPx = -1;
        private static int coverPx(android.content.Context ctx) {
            if (cachedCoverPx <= 0) cachedCoverPx = Math.round(80 * ctx.getResources().getDisplayMetrics().density);
            return cachedCoverPx;
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            RelatedItem item = items.get(pos);
            h.tvTitle.setText(item.title != null ? item.title : "");
            if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
                // ✅ FIX (oversized bitmap decode): no .override() meant Glide
                // decoded the raw source cover at full size into an 80dp
                // ImageView. Locked to the view's actual pixel size.
                int px = coverPx(h.ivCover.getContext());
                com.bumptech.glide.Glide.with(h.ivCover.getContext()).load(item.coverUrl)
                    .centerCrop().override(px, px).into(h.ivCover);
            }
            if (listener != null) h.root.setOnClickListener(v -> listener.onClick(item));
        }
        @Override public int getItemCount() { return items.size(); }
        public static class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final android.view.View root;
            final android.widget.ImageView ivCover;
            final android.widget.TextView tvTitle;
            VH(android.view.View v) {
                super(v);
                root = v;
                ivCover = v.findViewById(com.callx.app.reels.R.id.iv_related_row_cover);
                tvTitle = v.findViewById(com.callx.app.reels.R.id.tv_related_row_title);
            }
        }
    }

}
