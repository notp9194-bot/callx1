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

        public ReelThumbAdapter() { items = new java.util.ArrayList<>(); listener = null; }
        public ReelThumbAdapter(java.util.List<ReelThumbItem> items, OnItemClick listener) {
            this.items = items != null ? items : new java.util.ArrayList<>();
            this.listener = listener;
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
        private final java.util.List<RelatedItem> items;
        private final OnItemClick listener;
        public RelatedAdapter(java.util.List<RelatedItem> items, OnItemClick listener) {
            this.items = items != null ? items : new java.util.ArrayList<>();
            this.listener = listener;
        }
        @androidx.annotation.NonNull @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int vt) {
            android.widget.LinearLayout ll = new android.widget.LinearLayout(parent.getContext());
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            float d = parent.getContext().getResources().getDisplayMetrics().density;
            int dp80 = (int)(80*d); int dp120 = (int)(120*d);
            ll.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(dp120, dp80));
            ll.setPadding(4,2,4,2);
            android.widget.ImageView iv = new android.widget.ImageView(parent.getContext());
            iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp80, dp80));
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.widget.TextView tv = new android.widget.TextView(parent.getContext());
            tv.setTextSize(11); tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ll.addView(iv); ll.addView(tv);
            return new VH(ll, iv, tv);
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
            final android.widget.LinearLayout root;
            final android.widget.ImageView ivCover;
            final android.widget.TextView tvTitle;
            VH(android.widget.LinearLayout r, android.widget.ImageView iv, android.widget.TextView tv) {
                super(r); root=r; ivCover=iv; tvTitle=tv;
            }
        }
    }

}
