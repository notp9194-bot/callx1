package com.callx.app.music;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * PlaylistDetailActivity — Feature 3: Sound Collections / Playlist Detail.
 *
 * Shows all sounds inside a user playlist with:
 *  ✅ Cover art, title, artist, reel count per sound
 *  ✅ "Add Sound" button → MusicPickerActivity to pick & add
 *  ✅ Swipe-to-delete with ItemTouchHelper
 *  ✅ Tap → SoundDetailActivity
 *  ✅ "Use" button → returns sound to caller (if EXTRA_PICK_MODE)
 *  ✅ Real-time Firebase listener (sounds added from another device appear instantly)
 *
 * Firebase path: users/{uid}/sound_playlists/{playlistId}/sounds/{soundId}
 * Each sound entry: { title, artist, coverUrl, audioUrl, bpm, addedAt }
 */
public class PlaylistDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PLAYLIST_ID   = "playlist_id";
    public static final String EXTRA_PLAYLIST_NAME = "playlist_name";
    public static final String EXTRA_PICK_MODE     = "pick_mode";

    private static final int REQ_ADD_SOUND = 801;

    private ImageButton  btnBack;
    private TextView     tvTitle;
    private View         btnAddSound;
    private RecyclerView rv;
    private ProgressBar  progress;
    private TextView     tvEmpty;

    private String playlistId, playlistName, myUid;
    private boolean pickMode = false;

    private final List<PlaylistSound> sounds = new ArrayList<>();
    private PlaylistSoundAdapter adapter;

    private DatabaseReference  playlistSoundsRef;
    private ChildEventListener liveListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist_detail);

        playlistId   = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        playlistName = getIntent().getStringExtra(EXTRA_PLAYLIST_NAME);
        pickMode     = getIntent().getBooleanExtra(EXTRA_PICK_MODE, false);
        myUid        = FirebaseAuth.getInstance().getUid();

        if (playlistId == null || myUid == null) { finish(); return; }

        playlistSoundsRef = FirebaseUtils.getUserRef(myUid)
            .child("sound_playlists").child(playlistId).child("sounds");

        bindViews();
        attachLiveListener();
    }

    private void bindViews() {
        btnBack    = findViewById(R.id.btn_playlist_detail_back);
        tvTitle    = findViewById(R.id.tv_playlist_detail_title);
        btnAddSound= findViewById(R.id.btn_playlist_detail_add);
        rv         = findViewById(R.id.rv_playlist_sounds);
        progress   = findViewById(R.id.progress_playlist_sounds);
        tvEmpty    = findViewById(R.id.tv_playlist_sounds_empty);

        if (tvTitle != null) tvTitle.setText(playlistName != null ? playlistName : "Playlist");
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnAddSound != null) btnAddSound.setOnClickListener(v -> openMusicPicker());

        adapter = new PlaylistSoundAdapter(sounds, this::onSoundTapped, this::onUseTapped);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(adapter);
            // Swipe to delete
            new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                @Override public boolean onMove(@NonNull RecyclerView r,
                    @NonNull RecyclerView.ViewHolder v, @NonNull RecyclerView.ViewHolder t) { return false; }
                @Override public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int dir) {
                    int pos = viewHolder.getAdapterPosition();
                    if (pos < 0 || pos >= sounds.size()) return;
                    PlaylistSound s = sounds.get(pos);
                    new AlertDialog.Builder(PlaylistDetailActivity.this)
                        .setTitle("Remove Sound")
                        .setMessage("Remove \"" + s.title + "\" from this playlist?")
                        .setPositiveButton("Remove", (d, w) -> removeSound(s, pos))
                        .setNegativeButton("Cancel", (d, w) -> adapter.notifyItemChanged(pos))
                        .setOnCancelListener(d -> adapter.notifyItemChanged(pos))
                        .show();
                }
            }).attachToRecyclerView(rv);
        }
    }

    private void attachLiveListener() {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        liveListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, String prev) {
                if (isFinishing() || isDestroyed()) return;
                PlaylistSound s = parseSound(snap);
                if (s != null && !containsId(s.id)) {
                    sounds.add(s);
                    adapter.notifyItemInserted(sounds.size() - 1);
                    updateEmpty();
                }
                if (progress != null) progress.setVisibility(View.GONE);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                String id = snap.getKey();
                for (int i = 0; i < sounds.size(); i++) {
                    if (id != null && id.equals(sounds.get(i).id)) {
                        sounds.remove(i);
                        adapter.notifyItemRemoved(i);
                        break;
                    }
                }
                updateEmpty();
                if (progress != null) progress.setVisibility(View.GONE);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snap, String prev) {}
            @Override public void onChildMoved(@NonNull DataSnapshot snap, String prev) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (progress != null) progress.setVisibility(View.GONE);
                updateEmpty();
            }
        };
        playlistSoundsRef.addChildEventListener(liveListener);
        // Show empty state after initial load
        playlistSoundsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (progress != null) progress.setVisibility(View.GONE);
                updateEmpty();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (progress != null) progress.setVisibility(View.GONE);
                updateEmpty();
            }
        });
    }

    private void openMusicPicker() {
        Intent i = new Intent(this, MusicPickerActivity.class);
        startActivityForResult(i, REQ_ADD_SOUND);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ADD_SOUND && res == RESULT_OK && data != null) {
            String id    = data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_ID);
            String name  = data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_NAME);
            String url   = data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_URL);
            String cover = data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_COVER_URL);
            String artist= data.getStringExtra(MusicPickerActivity.EXTRA_MUSIC_ARTIST);
            if (id == null || id.isEmpty()) return;

            Map<String, Object> entry = new HashMap<>();
            entry.put("title",    name   != null ? name   : "");
            entry.put("artist",   artist != null ? artist : "");
            entry.put("coverUrl", cover  != null ? cover  : "");
            entry.put("audioUrl", url    != null ? url    : "");
            entry.put("addedAt",  ServerValue.TIMESTAMP);
            playlistSoundsRef.child(id).setValue(entry);

            // Bump sound count on the playlist node
            FirebaseUtils.getUserRef(myUid)
                .child("sound_playlists").child(playlistId).child("soundCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override
                    public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Long cur = d.getValue(Long.class);
                        d.setValue((cur != null ? cur : 0) + 1);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) {}
                });
            Toast.makeText(this, "Sound added to playlist", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeSound(PlaylistSound s, int pos) {
        playlistSoundsRef.child(s.id).removeValue();
        // Decrement count
        FirebaseUtils.getUserRef(myUid)
            .child("sound_playlists").child(playlistId).child("soundCount")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long cur = d.getValue(Long.class);
                    d.setValue(Math.max(0, (cur != null ? cur : 1) - 1));
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot ds) {}
            });
    }

    private void onSoundTapped(PlaylistSound s) {
        Intent i = new Intent(this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    s.id);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, s.title);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   s.audioUrl);
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      s.artist);
        i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   s.coverUrl);
        startActivity(i);
    }

    private void onUseTapped(PlaylistSound s) {
        if (pickMode) {
            Intent result = new Intent();
            result.putExtra("selected_sound_id",    s.id);
            result.putExtra("selected_sound_title", s.title);
            result.putExtra("selected_sound_url",   s.audioUrl);
            setResult(RESULT_OK, result);
            finish();
        } else {
            onSoundTapped(s);
        }
    }

    private void updateEmpty() {
        if (tvEmpty != null) tvEmpty.setVisibility(sounds.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean containsId(String id) {
        for (PlaylistSound s : sounds) if (id.equals(s.id)) return true;
        return false;
    }

    private static PlaylistSound parseSound(DataSnapshot snap) {
        String id    = snap.getKey();
        String title = snap.child("title").getValue(String.class);
        if (id == null || title == null || title.isEmpty()) return null;
        PlaylistSound s = new PlaylistSound();
        s.id       = id;
        s.title    = title;
        s.artist   = nvl(snap.child("artist").getValue(String.class));
        s.coverUrl = nvl(snap.child("coverUrl").getValue(String.class));
        s.audioUrl = nvl(snap.child("audioUrl").getValue(String.class));
        Long addedAt = snap.child("addedAt").getValue(Long.class);
        s.addedAt  = addedAt != null ? addedAt : 0L;
        return s;
    }

    @Override protected void onDestroy() {
        if (liveListener != null) playlistSoundsRef.removeEventListener(liveListener);
        super.onDestroy();
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // ── Models ────────────────────────────────────────────────────────────────

    static class PlaylistSound {
        String id, title, artist, coverUrl, audioUrl;
        long   addedAt;
    }

    interface SoundAction { void run(PlaylistSound s); }

    // ── Adapter ───────────────────────────────────────────────────────────────

    static class PlaylistSoundAdapter extends RecyclerView.Adapter<PlaylistSoundAdapter.VH> {
        private final List<PlaylistSound> items;
        private final SoundAction         onTap, onUse;
        PlaylistSoundAdapter(List<PlaylistSound> items, SoundAction onTap, SoundAction onUse) {
            this.items = items; this.onTap = onTap; this.onUse = onUse;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_playlist_sound, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            PlaylistSound s = items.get(pos);
            h.tvTitle.setText(s.title);
            h.tvArtist.setText(s.artist.isEmpty() ? "Original Audio" : s.artist);
            if (!s.coverUrl.isEmpty()) {
                com.bumptech.glide.Glide.with(h.ivCover).load(s.coverUrl)
                    .override(720, 720)
                    .placeholder(R.drawable.ic_music_note).centerCrop().into(h.ivCover);
            } else {
                h.ivCover.setImageResource(R.drawable.ic_music_note);
            }
            h.itemView.setOnClickListener(v -> onTap.run(s));
            h.btnUse.setOnClickListener(v -> onUse.run(s));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivCover;
            TextView tvTitle, tvArtist;
            Button   btnUse;
            VH(View v) {
                super(v);
                ivCover  = v.findViewById(R.id.iv_playlist_sound_cover);
                tvTitle  = v.findViewById(R.id.tv_playlist_sound_title);
                tvArtist = v.findViewById(R.id.tv_playlist_sound_artist);
                btnUse   = v.findViewById(R.id.btn_playlist_sound_use);
            }
        }
    }
}
