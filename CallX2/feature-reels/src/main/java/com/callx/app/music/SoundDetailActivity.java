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
        private final java.util.List<ReelThumbItem> items;
        private final OnItemClick listener;
        public ReelThumbAdapter() { items = new java.util.ArrayList<>(); listener = null; }
        public ReelThumbAdapter(java.util.List<ReelThumbItem> items, OnItemClick listener) {
            this.items = items != null ? items : new java.util.ArrayList<>();
            this.listener = listener;
        }

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
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            ReelThumbItem item = items.get(pos);
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty())
                com.bumptech.glide.Glide.with(h.iv.getContext()).load(item.thumbnailUrl)
                    .centerCrop().into(h.iv);
            if (h.tvViews != null) h.tvViews.setText(formatViews(item.viewsCount));
            if (h.tvOriginal != null)
                h.tvOriginal.setVisibility(item.isOriginalCreator ? android.view.View.VISIBLE : android.view.View.GONE);
            h.itemView.setOnClickListener(v -> { int p = h.getAdapterPosition(); if (p >= 0 && listener != null) listener.onClick(p); });
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
        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            RelatedItem item = items.get(pos);
            h.tvTitle.setText(item.title != null ? item.title : "");
            if (item.coverUrl != null && !item.coverUrl.isEmpty())
                com.bumptech.glide.Glide.with(h.ivCover.getContext()).load(item.coverUrl).into(h.ivCover);
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
