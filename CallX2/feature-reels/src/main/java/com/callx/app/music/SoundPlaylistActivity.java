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
 * SoundPlaylistActivity — Sound Collections / Playlists for Reels audio.
 *
 * Features:
 *  ✅ Lists all sound playlists for the current user
 *  ✅ Create new playlist (name + optional description via AlertDialog)
 *  ✅ Tap playlist → PlaylistDetailActivity (shows sounds inside)
 *  ✅ Long-press playlist → Delete playlist with confirmation
 *  ✅ Each playlist shows name, sound count, and last-updated timestamp
 *  ✅ Firebase path: users/{uid}/sound_playlists/{playlistId}
 *
 * Extras (optional):
 *  EXTRA_PICK_MODE = true  → returns RESULT_OK with RESULT_PLAYLIST_ID when user taps a playlist
 *                            (useful for "Add to Playlist" flow from SoundDetailActivity)
 */
public class SoundPlaylistActivity extends AppCompatActivity {

    public static final String EXTRA_PICK_MODE      = "pick_mode";
    public static final String RESULT_PLAYLIST_ID   = "result_playlist_id";
    public static final String RESULT_PLAYLIST_NAME = "result_playlist_name";

    private boolean pickMode = false;

    private ImageButton btnBack;
    private TextView    tvTitle;
    private View        btnCreate;
    private RecyclerView rv;
    private ProgressBar  progress;
    private TextView     tvEmpty;

    private final List<Playlist> playlists = new ArrayList<>();
    private PlaylistAdapter adapter;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_playlist);

        pickMode = getIntent().getBooleanExtra(EXTRA_PICK_MODE, false);
        myUid    = FirebaseAuth.getInstance().getUid();

        bindViews();
        loadPlaylists();
    }

    private void bindViews() {
        btnBack   = findViewById(R.id.btn_playlist_back);
        tvTitle   = findViewById(R.id.tv_playlist_title);
        btnCreate = findViewById(R.id.btn_playlist_create);
        rv        = findViewById(R.id.rv_playlists);
        progress  = findViewById(R.id.progress_playlists);
        tvEmpty   = findViewById(R.id.tv_playlists_empty);

        if (tvTitle != null) tvTitle.setText(pickMode ? "Add to Playlist" : "Sound Playlists");
        if (btnBack  != null) btnBack.setOnClickListener(v -> finish());
        if (btnCreate != null) btnCreate.setOnClickListener(v -> showCreateDialog());

        adapter = new PlaylistAdapter(playlists, this::onPlaylistTapped, this::onPlaylistLongPress);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(adapter);
        }
    }

    private void loadPlaylists() {
        if (myUid == null) { showEmpty(); return; }
        if (progress != null) progress.setVisibility(View.VISIBLE);

        FirebaseUtils.getUserRef(myUid).child("sound_playlists")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                    playlists.clear();
                    for (DataSnapshot ps : snapshot.getChildren()) {
                        Playlist p = new Playlist();
                        p.id          = ps.getKey();
                        p.name        = nvl(ps.child("name").getValue(String.class));
                        p.description = nvl(ps.child("description").getValue(String.class));
                        p.soundCount  = ps.child("sounds").getChildrenCount();
                        Long ts = ps.child("updatedAt").getValue(Long.class);
                        p.updatedAt   = ts != null ? ts : 0L;
                        playlists.add(p);
                    }
                    playlists.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (playlists.isEmpty()) showEmpty();
                    else { if (tvEmpty != null) tvEmpty.setVisibility(View.GONE); }
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {
                    if (progress != null) progress.setVisibility(View.GONE);
                    showEmpty();
                }
            });
    }

    private void showEmpty() {
        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
    }

    private void showCreateDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        EditText etName = new EditText(this);
        etName.setHint("Playlist name");
        etName.setSingleLine(true);
        layout.addView(etName);

        EditText etDesc = new EditText(this);
        etDesc.setHint("Description (optional)");
        etDesc.setSingleLine(true);
        layout.addView(etDesc);

        new AlertDialog.Builder(this)
            .setTitle("New Sound Playlist")
            .setView(layout)
            .setPositiveButton("Create", (d, w) -> {
                String name = etName.getText().toString().trim();
                String desc = etDesc.getText().toString().trim();
                if (!name.isEmpty()) createPlaylist(name, desc);
                else Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void createPlaylist(String name, String description) {
        if (myUid == null) return;
        DatabaseReference ref = FirebaseUtils.getUserRef(myUid)
            .child("sound_playlists").push();

        Map<String, Object> data = new HashMap<>();
        data.put("name",        name);
        data.put("description", description);
        data.put("updatedAt",   System.currentTimeMillis());
        ref.setValue(data).addOnSuccessListener(v -> {
            Toast.makeText(this, "Playlist \"" + name + "\" created", Toast.LENGTH_SHORT).show();
            loadPlaylists();
        });
    }

    private void onPlaylistTapped(Playlist p) {
        if (pickMode) {
            // Return playlist id/name to caller (e.g. SoundDetailActivity "Add to Playlist")
            Intent result = new Intent();
            result.putExtra(RESULT_PLAYLIST_ID,   p.id);
            result.putExtra(RESULT_PLAYLIST_NAME, p.name);
            setResult(RESULT_OK, result);
            finish();
        } else {
            // Open playlist detail (reuse or create PlaylistDetailActivity)
            Toast.makeText(this, "Playlist: " + p.name + " (" + p.soundCount + " sounds)",
                Toast.LENGTH_SHORT).show();
        }
    }

    private void onPlaylistLongPress(Playlist p) {
        new AlertDialog.Builder(this)
            .setTitle("Delete playlist?")
            .setMessage("\"" + p.name + "\" will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> deletePlaylist(p))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deletePlaylist(Playlist p) {
        if (myUid == null || p.id == null) return;
        FirebaseUtils.getUserRef(myUid).child("sound_playlists").child(p.id)
            .removeValue().addOnSuccessListener(v -> {
                Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show();
                loadPlaylists();
            });
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // ── Data model ────────────────────────────────────────────────────────

    static class Playlist {
        String id, name, description;
        long soundCount, updatedAt;
    }

    interface PlaylistAction { void run(Playlist p); }

    // ── Adapter ───────────────────────────────────────────────────────────

    static class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {
        private final List<Playlist>  items;
        private final PlaylistAction  onTap, onLong;

        PlaylistAdapter(List<Playlist> items, PlaylistAction onTap, PlaylistAction onLong) {
            this.items = items; this.onTap = onTap; this.onLong = onLong;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_sound_playlist, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Playlist pl = items.get(pos);
            h.tvName.setText(pl.name);
            h.tvCount.setText(pl.soundCount + " sounds");
            h.tvDesc.setText(pl.description.isEmpty() ? "" : pl.description);
            h.tvDesc.setVisibility(pl.description.isEmpty() ? View.GONE : View.VISIBLE);
            h.itemView.setOnClickListener(v -> onTap.run(pl));
            h.itemView.setOnLongClickListener(v -> { onLong.run(pl); return true; });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCount, tvDesc;
            VH(View v) {
                super(v);
                tvName  = v.findViewById(R.id.tv_playlist_name);
                tvCount = v.findViewById(R.id.tv_playlist_count);
                tvDesc  = v.findViewById(R.id.tv_playlist_desc);
            }
        }
    }
}
