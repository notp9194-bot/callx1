package com.callx.app.music;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SoundDetailActivity extends AppCompatActivity {
    public static final String EXTRA_SOUND_ID = "extra_sound_id";
    public static final String EXTRA_SOUND_TITLE = "extra_sound_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_SOUND_URL = "extra_sound_url";
    public static final String EXTRA_COVER_URL = "extra_cover_url";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";
    public static final String EXTRA_GENRE = "extra_genre";
    public static final String EXTRA_CREATOR_UID = "extra_creator_uid";
    public static final String EXTRA_ORIGINAL_AUDIO_URL = "extra_original_audio_url";

    private String soundId, title, artist, audioUrl, coverUrl;
    private long durationMs;
    private SoundWaveformView waveformView;
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;

    private RecyclerView rvReels;
    private SoundReelsAdapter adapter;
    private final List<ReelThumbItem> reelItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_detail);

        soundId = getIntent().getStringExtra(EXTRA_SOUND_ID);
        title = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        artist = getIntent().getStringExtra(EXTRA_ARTIST);
        audioUrl = getIntent().getStringExtra(EXTRA_SOUND_URL);
        coverUrl = getIntent().getStringExtra(EXTRA_COVER_URL);
        durationMs = getIntent().getLongExtra(EXTRA_DURATION_MS, 0);

        initViews();
        loadCreatorInfo();
        loadReels();
    }

    private void initViews() {
        waveformView = findViewById(R.id.waveform_view);
        ((TextView) findViewById(R.id.tv_detail_title)).setText(title);
        ((TextView) findViewById(R.id.tv_detail_artist)).setText(artist);
        
        long sec = durationMs / 1000;
        ((TextView) findViewById(R.id.tv_detail_stats)).setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));

        findViewById(R.id.btn_detail_play).setOnClickListener(v -> toggleAudio());
        findViewById(R.id.btn_save_sound).setOnClickListener(v -> saveSound());
        
        ExtendedFloatingActionButton fab = findViewById(R.id.fab_use_sound);
        fab.setOnClickListener(v -> useSound());

        rvReels = findViewById(R.id.rv_sound_reels_grid);
        rvReels.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new SoundReelsAdapter(reelItems, pos -> {
            Intent i = new Intent(this, SingleReelPlayerActivity.class);
            ArrayList<String> ids = new ArrayList<>();
            for (ReelThumbItem item : reelItems) ids.add(item.reelId);
            i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, pos);
            startActivity(i);
        });
        rvReels.setAdapter(adapter);
    }

    private void toggleAudio() {
        if (audioUrl == null || audioUrl.isEmpty()) return;
        if (isPlaying) {
            if (mediaPlayer != null) mediaPlayer.pause();
            isPlaying = false;
        } else {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
                try {
                    mediaPlayer.setDataSource(audioUrl);
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                } catch (Exception e) { return; }
            } else {
                mediaPlayer.start();
            }
            isPlaying = true;
        }
        waveformView.setPlaying(isPlaying);
    }

    private void useSound() {
        Intent i = new Intent(this, ReelCameraActivity.class);
        i.putExtra(EXTRA_SOUND_ID, soundId);
        i.putExtra(EXTRA_SOUND_TITLE, title);
        i.putExtra(EXTRA_ARTIST, artist);
        i.putExtra(EXTRA_SOUND_URL, audioUrl);
        startActivity(i);
    }

    private void saveSound() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        FirebaseUtils.db().getReference("savedSounds").child(uid).child(soundId).setValue(true)
            .addOnSuccessListener(a -> Toast.makeText(this, "Sound Saved", Toast.LENGTH_SHORT).show());
    }

    private void loadCreatorInfo() {
        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String cUid = s.child("originalCreatorUid").getValue(String.class);
                    if (cUid != null) {
                        findViewById(R.id.layout_creator_card).setVisibility(View.VISIBLE);
                        ((TextView) findViewById(R.id.tv_creator_name)).setText(s.child("originalCreatorName").getValue(String.class));
                        Glide.with(SoundDetailActivity.this)
                            .load(s.child("originalCreatorAvatar").getValue(String.class))
                            .circleCrop().into((ImageView) findViewById(R.id.iv_creator_avatar));
                        findViewById(R.id.btn_view_profile).setOnClickListener(v -> {
                            // Intent to UserProfileActivity
                        });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadReels() {
        FirebaseUtils.db().getReference("reelsBySound").child(soundId)
            .limitToFirst(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    reelItems.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        ReelThumbItem item = s.getValue(ReelThumbItem.class);
                        if (item != null) {
                            item.reelId = s.getKey();
                            reelItems.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    ((TextView) findViewById(R.id.tv_reel_count_stats)).setText(reelItems.size() + " Reels");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override protected void onDestroy() {
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        super.onDestroy();
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
            android.widget.ImageView iv = new android.widget.ImageView(parent.getContext());
            int size = (int) (96 * parent.getContext().getResources().getDisplayMetrics().density);
            iv.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(size, size));
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            return new VH(iv);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            ReelThumbItem item = items.get(pos);
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty())
                com.bumptech.glide.Glide.with(h.iv.getContext()).load(item.thumbnailUrl).into(h.iv);
            if (listener != null) { int fp = pos; h.iv.setOnClickListener(v -> { int p = h.getAdapterPosition(); if (p >= 0) listener.onClick(p); }); }
        }

        @Override public int getItemCount() { return items.size(); }

        public static class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final android.widget.ImageView iv;
            VH(android.widget.ImageView v) { super(v); iv = v; }
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
