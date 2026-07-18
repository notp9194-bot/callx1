package com.callx.app.music;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.upload.ReelUploadActivity;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.*;

/**
 * SavedSoundsActivity — View and manage all saved/bookmarked audio tracks.
 *
 * Features:
 *  ✅ Loads saved sounds from: users/{uid}/saved_sounds/{soundId} = title
 *  ✅ Lists all saved sounds with title, artist, duration
 *  ✅ Play/pause audio preview inline (only one plays at a time)
 *  ✅ Use sound in Camera → opens ReelCameraActivity with sound pre-selected
 *  ✅ Use sound in Gallery → opens ReelUploadActivity with sound pre-selected
 *  ✅ Unsave (remove) a sound with long-press or swipe action
 *  ✅ Sound detail → taps open SoundDetailActivity
 *  ✅ Empty state when no sounds are saved
 *  ✅ Real-time Firebase listener
 */
public class SavedSoundsActivity extends AppCompatActivity {

    private RecyclerView  rvSounds;
    private ProgressBar   progressBar;
    private View          layoutEmpty;
    private SavedSoundsAdapter adapter;
    private EditText      etSearch;
    private TextView      tvSortBy;

    private String myUid;
    private final List<SoundItem> allSounds     = new ArrayList<>();
    private final List<SoundItem> sounds        = new ArrayList<>();
    private ValueEventListener soundsListener;
    private MediaPlayer currentPlayer;
    private String      currentPlayingId;
    private String      sortMode = "recent";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_sounds);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Saved Sounds");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvSounds    = findViewById(R.id.rv_saved_sounds);
        progressBar = findViewById(R.id.progress_saved_sounds);
        layoutEmpty = findViewById(R.id.layout_saved_sounds_empty);
        etSearch    = findViewById(R.id.et_saved_sounds_search);
        tvSortBy    = findViewById(R.id.tv_saved_sounds_sort);

        adapter = new SavedSoundsAdapter(sounds, new SavedSoundsAdapter.SoundActions() {
            @Override public void onPlayPause(SoundItem item) { togglePlayback(item); }
            @Override public void onUseCamera(SoundItem item) { useSoundCamera(item); }
            @Override public void onUseGallery(SoundItem item) { useSoundGallery(item); }
            @Override public void onOpenDetail(SoundItem item) { openSoundDetail(item); }
            @Override public void onUnsave(SoundItem item) { unsaveSound(item); }
        });
        rvSounds.setLayoutManager(new LinearLayoutManager(this));
        rvSounds.setAdapter(adapter);

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    filterAndSort(s.toString().trim());
                }
            });
        }

        if (tvSortBy != null) {
            tvSortBy.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(this, v);
                menu.getMenu().add(0, 1, 0, "Recently Saved");
                menu.getMenu().add(0, 2, 1, "Most Used");
                menu.getMenu().add(0, 3, 2, "A-Z");
                menu.setOnMenuItemClickListener(item -> {
                    if      (item.getItemId() == 1) { sortMode = "recent"; tvSortBy.setText("Recent"); }
                    else if (item.getItemId() == 2) { sortMode = "usage";  tvSortBy.setText("Most Used"); }
                    else if (item.getItemId() == 3) { sortMode = "az";     tvSortBy.setText("A-Z"); }
                    filterAndSort(etSearch != null && etSearch.getText() != null
                        ? etSearch.getText().toString().trim() : "");
                    return true;
                });
                menu.show();
            });
        }

        loadSavedSounds();
    }

    private void loadSavedSounds() {
        progressBar.setVisibility(View.VISIBLE);
        soundsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                sounds.clear();
                allSounds.clear();
                // sounds saved as: users/{uid}/saved_sounds/{soundId} = soundTitle (String)
                // Fetch full metadata from musicLibrary/{soundId}
                long childCount = snap.getChildrenCount();
                if (childCount == 0) {
                    progressBar.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.VISIBLE);
                    adapter.notifyDataSetChanged();
                    return;
                }
                final long[] loaded = {0};
                for (DataSnapshot s : snap.getChildren()) {
                    String soundId = s.getKey();
                    if (soundId == null) { loaded[0]++; continue; }
                    FirebaseUtils.getMusicLibraryRef().child(soundId)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot mSnap) {
                                String title    = mSnap.child("title").getValue(String.class);
                                String artist   = mSnap.child("artist").getValue(String.class);
                                String audioUrl = mSnap.child("audioUrl").getValue(String.class);
                                String coverUrl = mSnap.child("coverUrl").getValue(String.class);
                                Long   dur      = mSnap.child("durationMs").getValue(Long.class);
                                // Fallback: title stored in saved_sounds node
                                if (title == null) title = s.getValue(String.class);
                                Long usageCount = mSnap.child("usageCount").getValue(Long.class);
                                SoundItem si = new SoundItem(
                                    soundId,
                                    title    != null ? title    : "Unknown Sound",
                                    artist   != null ? artist   : "Unknown Artist",
                                    audioUrl != null ? audioUrl : "",
                                    coverUrl != null ? coverUrl : "",
                                    dur      != null ? dur      : 0L
                                );
                                si.usageCount = usageCount != null ? usageCount : 0L;
                                sounds.add(si);
                                allSounds.add(si);
                                loaded[0]++;
                                if (loaded[0] >= childCount) finishLoading();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                loaded[0]++;
                                if (loaded[0] >= childCount) finishLoading();
                            }
                        });
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(SavedSoundsActivity.this,
                    "Failed to load saved sounds", Toast.LENGTH_SHORT).show();
            }
        };
        FirebaseUtils.getUserRef(myUid).child("saved_sounds")
            .addValueEventListener(soundsListener);
    }

    private void finishLoading() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            filterAndSort(etSearch != null && etSearch.getText() != null
                ? etSearch.getText().toString().trim() : "");
        });
    }

    private void filterAndSort(String query) {
        sounds.clear();
        String q = query.toLowerCase().trim();
        for (SoundItem item : allSounds) {
            boolean matchesQ = q.isEmpty()
                || item.title.toLowerCase().contains(q)
                || item.artist.toLowerCase().contains(q);
            if (matchesQ) sounds.add(item);
        }
        switch (sortMode) {
            case "usage":
                sounds.sort((a, b) -> Long.compare(b.usageCount, a.usageCount));
                break;
            case "az":
                sounds.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
                break;
            default:
                sounds.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
                break;
        }
        layoutEmpty.setVisibility(sounds.isEmpty() ? View.VISIBLE : View.GONE);
        rvSounds.setVisibility(sounds.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void togglePlayback(SoundItem item) {
        if (currentPlayingId != null && currentPlayingId.equals(item.soundId)) {
            // Stop current
            stopCurrentPlayer();
            item.isPlaying = false;
            adapter.notifyDataSetChanged();
        } else {
            stopCurrentPlayer();
            if (item.audioUrl.isEmpty()) {
                Toast.makeText(this, "Preview not available", Toast.LENGTH_SHORT).show();
                return;
            }
            currentPlayer = new MediaPlayer();
            try {
                currentPlayer.setDataSource(item.audioUrl);
                currentPlayer.setLooping(true);
                currentPlayer.prepareAsync();
                currentPlayer.setOnPreparedListener(mp -> {
                    mp.start();
                    item.isPlaying = true;
                    currentPlayingId = item.soundId;
                    adapter.notifyDataSetChanged();
                });
                currentPlayer.setOnCompletionListener(mp -> {
                    item.isPlaying = false;
                    currentPlayingId = null;
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                Toast.makeText(this, "Cannot play preview", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopCurrentPlayer() {
        if (currentPlayer != null) {
            try { currentPlayer.stop(); } catch (Exception ignored) {}
            currentPlayer.release();
            currentPlayer = null;
        }
        for (SoundItem s : sounds) s.isPlaying = false;
        currentPlayingId = null;
    }

    private void useSoundCamera(SoundItem item) {
        stopCurrentPlayer();
        Intent i = new Intent(this, ReelCameraActivity.class);
        i.putExtra("selected_sound_id",    item.soundId);
        i.putExtra("selected_sound_title", item.title);
        i.putExtra("selected_sound_url",   item.audioUrl);
        startActivity(i);
    }

    private void useSoundGallery(SoundItem item) {
        stopCurrentPlayer();
        Intent i = new Intent(this, ReelUploadActivity.class);
        i.putExtra(ReelUploadActivity.EXTRA_MUSIC_NAME, item.title);
        i.putExtra("selected_sound_id",   item.soundId);
        i.putExtra("selected_sound_url",  item.audioUrl);
        startActivity(i);
    }

    private void openSoundDetail(SoundItem item) {
        stopCurrentPlayer();
        Intent i = new Intent(this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    item.soundId);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, item.title);
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      item.artist);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   item.audioUrl);
        i.putExtra(SoundDetailActivity.EXTRA_DURATION_MS, (int) item.durationMs);
        startActivity(i);
    }

    private void unsaveSound(SoundItem item) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Remove Sound?")
            .setMessage("\"" + item.title + "\" will be removed from your saved sounds.")
            .setPositiveButton("Remove", (d, w) -> {
                FirebaseUtils.getUserRef(myUid).child("saved_sounds").child(item.soundId)
                    .removeValue()
                    .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Sound removed", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override protected void onPause() { super.onPause(); stopCurrentPlayer(); }

    @Override
    protected void onDestroy() {
        stopCurrentPlayer();
        if (soundsListener != null)
            FirebaseUtils.getUserRef(myUid).child("saved_sounds").removeEventListener(soundsListener);
        super.onDestroy();
    }

    // ── Data model ────────────────────────────────────────────────────────

    public static class SoundItem {
        public String soundId, title, artist, audioUrl, coverUrl;
        public long   durationMs;
        public long   usageCount;
        public long   savedAt;
        public boolean isPlaying = false;
        SoundItem(String id, String t, String ar, String au, String cu, long d) {
            soundId = id; title = t; artist = ar; audioUrl = au; coverUrl = cu; durationMs = d;
            savedAt = System.currentTimeMillis();
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    static class SavedSoundsAdapter extends RecyclerView.Adapter<SavedSoundsAdapter.VH> {
        interface SoundActions {
            void onPlayPause(SoundItem item);
            void onUseCamera(SoundItem item);
            void onUseGallery(SoundItem item);
            void onOpenDetail(SoundItem item);
            void onUnsave(SoundItem item);
        }
        private final List<SoundItem> sounds;
        private final SoundActions actions;
        SavedSoundsAdapter(List<SoundItem> s, SoundActions a) { sounds = s; actions = a; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_saved_sound, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            SoundItem item = sounds.get(pos);
            h.tvTitle.setText(item.title);
            h.tvArtist.setText(item.artist);
            if (item.durationMs > 0) {
                int sec = (int)(item.durationMs / 1000);
                h.tvDuration.setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
            } else {
                h.tvDuration.setText("—");
            }
            if (!item.coverUrl.isEmpty()) {
                com.bumptech.glide.Glide.with(h.ivCover).load(item.coverUrl)
                    .override(720, 720)
                    .placeholder(R.drawable.ic_music_note).into(h.ivCover);
            } else {
                h.ivCover.setImageResource(R.drawable.ic_music_note);
            }
            h.btnPlayPause.setImageResource(
                item.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

            h.btnPlayPause.setOnClickListener(v  -> actions.onPlayPause(item));
            h.btnUseCamera.setOnClickListener(v  -> actions.onUseCamera(item));
            h.btnUseGallery.setOnClickListener(v -> actions.onUseGallery(item));
            h.itemView.setOnClickListener(v      -> actions.onOpenDetail(item));
            h.itemView.setOnLongClickListener(v  -> { actions.onUnsave(item); return true; });
        }

        @Override public int getItemCount() { return sounds.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivCover;
            TextView  tvTitle, tvArtist, tvDuration;
            ImageButton btnPlayPause, btnUseCamera, btnUseGallery;
            VH(View v) {
                super(v);
                ivCover       = v.findViewById(R.id.iv_sound_cover);
                tvTitle       = v.findViewById(R.id.tv_sound_title);
                tvArtist      = v.findViewById(R.id.tv_sound_artist);
                tvDuration    = v.findViewById(R.id.tv_sound_duration);
                btnPlayPause  = v.findViewById(R.id.btn_sound_play_pause);
                btnUseCamera  = v.findViewById(R.id.btn_use_camera);
                btnUseGallery = v.findViewById(R.id.btn_use_gallery);
            }
        }
    }
}
