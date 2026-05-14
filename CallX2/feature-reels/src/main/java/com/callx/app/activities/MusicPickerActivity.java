package com.callx.app.activities;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.adapters.MusicTrackAdapter;
import com.callx.app.models.MusicTrack;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.activities.SavedSoundsActivity;
import com.callx.app.activities.ReelTrendingAudioActivity;
import com.callx.app.activities.ReelSoundRecorderActivity;
import com.callx.app.activities.SoundDetailActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * MusicPickerActivity — Browse and select background music for a reel.
 *
 * Features:
 *  ✅ Loads music library from Firebase "musicLibrary" node
 *  ✅ Real-time search filter by track name / artist
 *  ✅ Play/pause preview using MediaPlayer
 *  ✅ Visual "playing" indicator on current track
 *  ✅ Select → returns EXTRA_MUSIC_NAME + EXTRA_MUSIC_URL to caller
 *  ✅ "No Music" option to clear selection
 */
public class MusicPickerActivity extends AppCompatActivity
        implements MusicTrackAdapter.OnTrackActionListener {

    public static final String EXTRA_MUSIC_NAME = "music_name";
    public static final String EXTRA_MUSIC_URL  = "music_url";

    private RecyclerView      rvTracks;
    private EditText          etSearch;
    private ProgressBar       progressBar;
    private View              layoutEmpty;
    private TextView          btnNoMusic;
    private View              btnSavedSounds, btnTrendingAudio, btnRecordSound;

    private MusicTrackAdapter        adapter;
    private final List<MusicTrack>   allTracks    = new ArrayList<>();
    private final List<MusicTrack>   filtered     = new ArrayList<>();

    private MediaPlayer  mediaPlayer;
    private MusicTrack   currentlyPlaying;
    private int          currentlyPlayingPos = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_picker);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Add Music");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        bindViews();
        setupRecycler();
        setupSearch();
        loadMusicLibrary();

        btnNoMusic.setOnClickListener(v -> {
            stopPreview();
            Intent result = new Intent();
            result.putExtra(EXTRA_MUSIC_NAME, "");
            result.putExtra(EXTRA_MUSIC_URL,  "");
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void bindViews() {
        rvTracks        = findViewById(R.id.rv_music_tracks);
        etSearch        = findViewById(R.id.et_music_search);
        progressBar     = findViewById(R.id.progress_music);
        layoutEmpty     = findViewById(R.id.layout_music_empty);
        btnNoMusic      = findViewById(R.id.btn_no_music);
        btnSavedSounds  = findViewById(R.id.btn_saved_sounds);
        btnTrendingAudio= findViewById(R.id.btn_trending_audio);
        btnRecordSound  = findViewById(R.id.btn_record_sound);

        if (btnSavedSounds   != null) btnSavedSounds.setOnClickListener(v   -> startActivity(new Intent(this, SavedSoundsActivity.class)));
        if (btnTrendingAudio != null) btnTrendingAudio.setOnClickListener(v -> startActivity(new Intent(this, ReelTrendingAudioActivity.class)));
        if (btnRecordSound   != null) btnRecordSound.setOnClickListener(v   -> startActivity(new Intent(this, ReelSoundRecorderActivity.class)));
    }

    private void setupRecycler() {
        adapter = new MusicTrackAdapter(filtered, this);
        rvTracks.setLayoutManager(new LinearLayoutManager(this));
        rvTracks.addItemDecoration(
            new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvTracks.setAdapter(adapter);
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterTracks(s.toString());
            }
        });
    }

    private void filterTracks(String query) {
        filtered.clear();
        String q = query.toLowerCase().trim();
        if (q.isEmpty()) {
            filtered.addAll(allTracks);
        } else {
            for (MusicTrack t : allTracks) {
                if (t.name.toLowerCase().contains(q)
                        || (t.artist != null && t.artist.toLowerCase().contains(q))
                        || (t.genre  != null && t.genre.toLowerCase().contains(q))) {
                    filtered.add(t);
                }
            }
        }
        adapter.notifyDataSetChanged();
        layoutEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadMusicLibrary() {
        progressBar.setVisibility(View.VISIBLE);

        FirebaseUtils.getMusicLibraryRef().addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allTracks.clear();
                    for (DataSnapshot child : snap.getChildren()) {
                        MusicTrack track = child.getValue(MusicTrack.class);
                        if (track != null) {
                            track.trackId = child.getKey();
                            allTracks.add(track);
                        }
                    }

                    if (allTracks.isEmpty()) {
                        loadDefaultTracks();
                    } else {
                        filtered.addAll(allTracks);
                        adapter.notifyDataSetChanged();
                        progressBar.setVisibility(View.GONE);
                        layoutEmpty.setVisibility(
                            filtered.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                }

                @Override public void onCancelled(@NonNull DatabaseError error) {
                    loadDefaultTracks();
                }
            });
    }

    private void loadDefaultTracks() {
        allTracks.clear();
        String[][] defaults = {
            {"Trending Beat 1", "CallX Originals", "Pop",   ""},
            {"Chill Vibes",     "CallX Originals", "Chill", ""},
            {"Hype Mode",       "CallX Originals", "Hip-hop",""},
            {"Romantic Mood",   "CallX Originals", "Romantic",""},
            {"Dance Fever",     "CallX Originals", "Dance", ""},
            {"Lo-Fi Study",     "CallX Originals", "Lo-Fi", ""},
            {"Party Anthem",    "CallX Originals", "EDM",   ""},
            {"Acoustic Soul",   "CallX Originals", "Acoustic",""},
        };
        for (String[] d : defaults) {
            MusicTrack t = new MusicTrack();
            t.trackId = d[0].replace(" ", "_").toLowerCase();
            t.name    = d[0];
            t.artist  = d[1];
            t.genre   = d[2];
            t.audioUrl= d[3];
            allTracks.add(t);
        }
        filtered.addAll(allTracks);
        adapter.notifyDataSetChanged();
        progressBar.setVisibility(View.GONE);
        layoutEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // Long-press a track → open SoundDetailActivity for full info
    public void onTrackLongPress(MusicTrack track) {
        Intent i = new Intent(this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    track.trackId != null ? track.trackId : "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, track.name    != null ? track.name    : "");
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      track.artist  != null ? track.artist  : "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   track.audioUrl!= null ? track.audioUrl: "");
        startActivity(i);
    }

    @Override
    public void onPreviewToggle(MusicTrack track, int position) {
        if (currentlyPlaying != null && currentlyPlaying.trackId.equals(track.trackId)) {
            stopPreview();
            adapter.setPlayingPosition(-1);
            adapter.notifyItemChanged(position);
        } else {
            stopPreview();
            if (track.audioUrl != null && !track.audioUrl.isEmpty()) {
                try {
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(track.audioUrl);
                    mediaPlayer.setOnPreparedListener(mp -> mp.start());
                    mediaPlayer.setOnCompletionListener(mp -> {
                        currentlyPlaying    = null;
                        currentlyPlayingPos = -1;
                        adapter.setPlayingPosition(-1);
                        adapter.notifyItemChanged(position);
                    });
                    mediaPlayer.prepareAsync();
                    currentlyPlaying    = track;
                    currentlyPlayingPos = position;
                    adapter.setPlayingPosition(position);
                    adapter.notifyItemChanged(position);
                } catch (Exception e) {
                    Toast.makeText(this, "Cannot preview this track", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Preview not available for this track",
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onSelect(MusicTrack track) {
        stopPreview();
        Intent result = new Intent();
        result.putExtra(EXTRA_MUSIC_NAME, track.name
            + (track.artist != null ? " – " + track.artist : ""));
        result.putExtra(EXTRA_MUSIC_URL,  track.audioUrl != null ? track.audioUrl : "");
        setResult(RESULT_OK, result);
        finish();
    }

    private void stopPreview() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        currentlyPlaying    = null;
        currentlyPlayingPos = -1;
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        super.onDestroy();
    }
}
