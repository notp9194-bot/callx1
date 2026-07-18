package com.callx.app.music;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.callx.app.reels.R;

/**
 * SoundDetailActivity — thin host only.
 *
 * Sara logic SoundDetailFragment mein hai.
 * Activity sirf Fragment ko container mein add karti hai
 * aur close callback ke roop mein finish() deti hai.
 *
 * Koi duplicate code nahi — ek hi Fragment Activity
 * aur BottomSheet dono mein use hota hai.
 */
public class SoundDetailActivity extends AppCompatActivity {

    // ── Intent Extras (existing callers ke liye same rakhein) ─────────────────
    public static final String EXTRA_SOUND_ID           = "sound_id";
    public static final String EXTRA_SOUND_TITLE        = "sound_title";
    public static final String EXTRA_SOUND_URL          = "sound_url";
    public static final String EXTRA_ARTIST             = "sound_artist";
    public static final String EXTRA_DURATION_MS        = "sound_duration_ms";
    public static final String EXTRA_COVER_URL          = "sound_cover_url";
    public static final String EXTRA_BPM                = "sound_bpm";
    public static final String EXTRA_GENRE              = "sound_genre";
    public static final String EXTRA_ORIGINAL_AUDIO_URL = "original_audio_url";
    public static final String EXTRA_CREATOR_UID        = "sound_creator_uid";
    public static final String EXTRA_PREVIEW_AUDIO_URL  = "sound_preview_audio_url";

    // Inner classes — existing code jo in classes ko import karta hai vo compile hota rahe
    public static class ReelThumbItem {
        public String  reelId, thumbnailUrl, videoUrl, uid;
        public long    viewsCount;
        public boolean isOriginalCreator;
        public ReelThumbItem(String r, String t, String v) {
            reelId = r; thumbnailUrl = t; videoUrl = v;
        }
    }

    public static class ReelThumbAdapter
            extends androidx.recyclerview.widget.RecyclerView.Adapter<ReelThumbAdapter.VH> {
        public interface OnClick { void click(int position); }
        private final java.util.List<ReelThumbItem> items;
        private final OnClick onClick;
        public ReelThumbAdapter(java.util.List<ReelThumbItem> items, OnClick onClick) {
            this.items = items; this.onClick = onClick;
        }
        @androidx.annotation.NonNull @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup p, int vt) {
            android.view.View v = android.view.LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_sound_reel_thumb, p, false);
            int col = p.getResources().getDisplayMetrics().widthPixels / 3;
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.height = col * 16 / 9; v.setLayoutParams(lp);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            ReelThumbItem item = items.get(pos);
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty())
                com.bumptech.glide.Glide.with(h.ivThumb).load(item.thumbnailUrl)
                    .placeholder(R.drawable.ic_music_note).override(720, 720)
                    .centerCrop().into(h.ivThumb);
            else h.ivThumb.setImageResource(R.drawable.ic_play);
            h.tvOriginalStrip.setVisibility(item.isOriginalCreator ? android.view.View.VISIBLE : android.view.View.GONE);
            h.itemView.setOnClickListener(vv -> {
                int ap = h.getBindingAdapterPosition();
                if (ap != androidx.recyclerview.widget.RecyclerView.NO_POSITION) onClick.click(ap);
            });
        }
        @Override public int getItemCount() { return items.size(); }
        public static class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            android.widget.ImageView ivThumb;
            android.widget.TextView  tvOriginalStrip;
            public VH(android.view.View v) {
                super(v);
                ivThumb         = v.findViewById(R.id.iv_media_thumb);
                tvOriginalStrip = v.findViewById(R.id.tv_original_strip);
            }
        }
    }

    public static class RelatedItem {
        public String id, title, artist, coverUrl, audioUrl;
        public RelatedItem(String i, String t, String a, String c, String u) {
            id = i; title = t; artist = a; coverUrl = c; audioUrl = u;
        }
    }

    public static class RelatedAdapter
            extends androidx.recyclerview.widget.RecyclerView.Adapter<RelatedAdapter.VH> {
        public interface OnClick { void click(RelatedItem item); }
        private final java.util.List<RelatedItem> items;
        private final OnClick onClick;
        public RelatedAdapter(java.util.List<RelatedItem> i, OnClick c) { items = i; onClick = c; }
        @androidx.annotation.NonNull @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup p, int vt) {
            return new VH(android.view.LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_related_sound, p, false));
        }
        @Override public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            RelatedItem item = items.get(pos);
            h.tvTitle.setText(item.title);
            h.tvArtist.setText(item.artist == null || item.artist.isEmpty() ? "Unknown" : item.artist);
            if (item.coverUrl != null && !item.coverUrl.isEmpty())
                com.bumptech.glide.Glide.with(h.ivCover).load(item.coverUrl)
                    .transform(new com.bumptech.glide.load.resource.bitmap.CircleCrop())
                    .placeholder(R.drawable.ic_music_note).override(720, 720).into(h.ivCover);
            else h.ivCover.setImageResource(R.drawable.ic_music_note);
            h.itemView.setOnClickListener(v -> onClick.click(item));
        }
        @Override public int getItemCount() { return items.size(); }
        public static class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            android.widget.ImageView ivCover;
            android.widget.TextView  tvTitle, tvArtist;
            public VH(android.view.View v) {
                super(v);
                ivCover  = v.findViewById(R.id.iv_related_cover);
                tvTitle  = v.findViewById(R.id.tv_related_title);
                tvArtist = v.findViewById(R.id.tv_related_artist);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle — sirf Fragment add karo
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_sound_detail);

        if (savedInstanceState == null) {
            String soundId         = getIntent().getStringExtra(EXTRA_SOUND_ID);
            String soundTitle      = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
            String soundUrl        = getIntent().getStringExtra(EXTRA_SOUND_URL);
            String artist          = getIntent().getStringExtra(EXTRA_ARTIST);
            int    durationMs      = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);
            String coverUrl        = getIntent().getStringExtra(EXTRA_COVER_URL);
            int    bpm             = getIntent().getIntExtra(EXTRA_BPM, 0);
            String genre           = getIntent().getStringExtra(EXTRA_GENRE);
            String creatorUid      = getIntent().getStringExtra(EXTRA_CREATOR_UID);
            String previewAudioUrl = getIntent().getStringExtra(EXTRA_PREVIEW_AUDIO_URL);

            // Original audio URL override (legacy callers)
            String origUrl = getIntent().getStringExtra(EXTRA_ORIGINAL_AUDIO_URL);
            if (origUrl != null && !origUrl.isEmpty()) soundUrl = origUrl;

            SoundDetailFragment fragment = SoundDetailFragment.newInstance(
                soundId, soundTitle, artist, coverUrl,
                soundUrl, durationMs, genre, bpm, creatorUid, previewAudioUrl,
                false /* isSheet = false → Activity mode */
            );
            // Close = Activity finish
            fragment.setOnCloseListener(this::finish);

            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container_sound, fragment)
                .commit();
        }
    }
}
