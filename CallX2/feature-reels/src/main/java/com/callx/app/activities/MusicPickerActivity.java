package com.callx.app.activities;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.adapters.MusicTrackAdapter;
import com.callx.app.models.MusicTrack;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.*;

/**
 * MusicPickerActivity — Production-level music picker for Reels.
 *
 * Features:
 *  ✅ Genre/mood category chips (All, For You, Trending, Pop, Hip-Hop, Romantic, Chill, EDM,
 *       Lo-Fi, Dance, Acoustic, R&B, Classical, Bollywood, Original)
 *  ✅ "For You" tab — personalised suggestions based on user's recent reel music
 *  ✅ Recently used tracks section at top (max 5, from SharedPreferences)
 *  ✅ Cover art per row via Glide
 *  ✅ Duration display (mm:ss)
 *  ✅ Usage count badge + Trending rank badge
 *  ✅ Save/bookmark button per track (written to Firebase)
 *  ✅ Real-time search filter (name / artist / genre / mood)
 *  ✅ Play/pause 30s preview via MediaPlayer (auto-stop when another starts)
 *  ✅ "No Music" option to clear selection
 *  ✅ Long-press → SoundDetailActivity
 *  ✅ Quick-access buttons: Saved Sounds / Trending Audio / Record Sound / Upload Sound
 *  ✅ Beat Sync shortcut button → ReelBeatSyncActivity
 *  ✅ Equalizer shortcut button → ReelEqualizerActivity
 */
public class MusicPickerActivity extends AppCompatActivity
        implements MusicTrackAdapter.OnTrackActionListener {

    public static final String EXTRA_MUSIC_NAME       = "music_name";
    public static final String EXTRA_MUSIC_URL        = "music_url";
    public static final String EXTRA_MUSIC_ID         = "music_id";
    public static final String EXTRA_MUSIC_COVER_URL  = "music_cover_url";
    public static final String EXTRA_MUSIC_ARTIST     = "music_artist";
    public static final String EXTRA_VIDEO_URI        = "video_uri";   // for Beat Sync

    private static final String PREFS_RECENT   = "music_picker_recent";
    private static final String PREFS_HISTORY  = "music_picker_history";
    private static final int    MAX_RECENT     = 5;
    private static final int    MAX_FOR_YOU    = 30;

    private RecyclerView      rvTracks, rvRecent;
    private EditText          etSearch;
    private ProgressBar       progressBar;
    private View              layoutEmpty;
    private TextView          btnNoMusic;
    private View              btnSavedSounds, btnTrendingAudio, btnRecordSound,
                              btnUploadSound, btnBeatSync, btnEqualizer;
    private LinearLayout      layoutCategoryChips;
    private View              layoutRecentSection;
    private TextView          tvRecentLabel;

    private MusicTrackAdapter        adapter;
    private MusicTrackAdapter        recentAdapter;
    private final List<MusicTrack>   allTracks    = new ArrayList<>();
    private final List<MusicTrack>   filtered     = new ArrayList<>();
    private final List<MusicTrack>   recentTracks = new ArrayList<>();
    private String                   selectedGenre = "all";

    private MediaPlayer  mediaPlayer;
    private MusicTrack   currentlyPlaying;
    private int          currentlyPlayingPos = -1;

    private Set<String>  savedIds       = new HashSet<>();
    private Set<String>  historyGenres  = new LinkedHashSet<>(); // genres from user's recent reels
    private String       myUid;

    private static final String[] GENRE_CHIPS = {
        "All", "For You", "Trending", "Pop", "Hip-Hop", "Romantic", "Chill", "EDM",
        "Lo-Fi", "Dance", "Acoustic", "R&B", "Classical", "Bollywood", "Original"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_picker);

        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Add Music");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        bindViews();
        loadListeningHistory();
        setupCategoryChips();
        setupRecyclers();
        setupSearch();
        loadSavedIds();
        loadMusicLibrary();
        loadRecentTracks();

        if (btnNoMusic != null) {
            btnNoMusic.setOnClickListener(v -> {
                stopPreview();
                Intent result = new Intent();
                result.putExtra(EXTRA_MUSIC_NAME, "");
                result.putExtra(EXTRA_MUSIC_URL,  "");
                result.putExtra(EXTRA_MUSIC_ID,   "");
                setResult(RESULT_OK, result);
                finish();
            });
        }
    }

    private void bindViews() {
        rvTracks            = findViewById(R.id.rv_music_tracks);
        rvRecent            = findViewById(R.id.rv_recent_tracks);
        etSearch            = findViewById(R.id.et_music_search);
        progressBar         = findViewById(R.id.progress_music);
        layoutEmpty         = findViewById(R.id.layout_music_empty);
        btnNoMusic          = findViewById(R.id.btn_no_music);
        btnSavedSounds      = findViewById(R.id.btn_saved_sounds);
        btnTrendingAudio    = findViewById(R.id.btn_trending_audio);
        btnRecordSound      = findViewById(R.id.btn_record_sound);
        btnUploadSound      = findViewById(R.id.btn_upload_sound);
        btnBeatSync         = findViewById(R.id.btn_beat_sync);
        btnEqualizer        = findViewById(R.id.btn_equalizer);
        layoutCategoryChips = findViewById(R.id.layout_category_chips);
        layoutRecentSection = findViewById(R.id.layout_recent_section);
        tvRecentLabel       = findViewById(R.id.tv_recent_label);

        if (btnSavedSounds != null) btnSavedSounds.setOnClickListener(v ->
            startActivity(new Intent(this, SavedSoundsActivity.class)));
        if (btnTrendingAudio != null) btnTrendingAudio.setOnClickListener(v ->
            startActivity(new Intent(this, ReelTrendingAudioActivity.class)));
        if (btnRecordSound != null) btnRecordSound.setOnClickListener(v ->
            startActivity(new Intent(this, ReelSoundRecorderActivity.class)));
        if (btnUploadSound != null) btnUploadSound.setOnClickListener(v ->
            startActivity(new Intent(this, SoundUploadActivity.class)));
        if (btnBeatSync != null) btnBeatSync.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelBeatSyncActivity.class);
            String videoUri = getIntent().getStringExtra(EXTRA_VIDEO_URI);
            if (videoUri != null) i.putExtra(ReelBeatSyncActivity.EXTRA_AUDIO_URL, videoUri);
            startActivity(i);
        });
        if (btnEqualizer != null) btnEqualizer.setOnClickListener(v ->
            startActivity(new Intent(this, ReelEqualizerActivity.class)));
    }

    /** Load recent reel genres from the user's own reels for "For You" personalisation. */
    private void loadListeningHistory() {
        if (myUid == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE);
        String saved = prefs.getString("genres", "");
        if (!saved.isEmpty()) {
            historyGenres.addAll(Arrays.asList(saved.split(",")));
        }

        // Also fetch from Firebase for richer signal (async, non-blocking)
        FirebaseUtils.db().getReference("reels").orderByChild("uid").equalTo(myUid)
            .limitToLast(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        String g = s.child("musicGenre").getValue(String.class);
                        if (g == null) g = s.child("genre").getValue(String.class);
                        if (g != null && !g.isEmpty()) historyGenres.add(g.toLowerCase());
                        // Also consider the music used in others' reels the user watched
                        // (this would require a separate watch-history node)
                    }
                    // Persist
                    String joined = String.join(",", historyGenres);
                    getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE)
                        .edit().putString("genres", joined).apply();

                    if ("for_you".equals(selectedGenre)) filterTracks(
                        etSearch != null && etSearch.getText() != null
                            ? etSearch.getText().toString() : "");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void setupCategoryChips() {
        if (layoutCategoryChips == null) return;
        layoutCategoryChips.removeAllViews();

        float dp = getResources().getDisplayMetrics().density;
        int dp8  = (int)(8  * dp);
        int dp16 = (int)(16 * dp);
        int dp6  = (int)(6  * dp);
        int dp36 = (int)(36 * dp);

        for (String genre : GENRE_CHIPS) {
            TextView chip = new TextView(this);
            chip.setText(genre);
            chip.setTextSize(13f);
            chip.setSingleLine(true);
            chip.setPadding(dp16, dp6, dp16, dp6);
            chip.setMinHeight(dp36);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setClickable(true);
            chip.setFocusable(true);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp8, 0);
            chip.setLayoutParams(lp);

            boolean active = genre.equalsIgnoreCase("All") && selectedGenre.equals("all");
            setChipActive(chip, active);

            chip.setOnClickListener(v -> {
                if      (genre.equalsIgnoreCase("All"))     selectedGenre = "all";
                else if (genre.equalsIgnoreCase("For You")) selectedGenre = "for_you";
                else if (genre.equalsIgnoreCase("Trending"))selectedGenre = "trending";
                else                                        selectedGenre = genre.toLowerCase();

                for (int i = 0; i < layoutCategoryChips.getChildCount(); i++) {
                    View c = layoutCategoryChips.getChildAt(i);
                    if (c instanceof TextView) setChipActive((TextView) c, c == chip);
                }
                filterTracks(etSearch != null && etSearch.getText() != null
                    ? etSearch.getText().toString() : "");
            });
            layoutCategoryChips.addView(chip);
        }
    }

    private void setChipActive(TextView chip, boolean active) {
        if (active) {
            chip.setBackgroundResource(R.drawable.bg_speed_chip_active);
            chip.setTextColor(0xFFFFFFFF);
        } else {
            chip.setBackgroundResource(R.drawable.bg_speed_chip);
            chip.setTextColor(0xCCFFFFFF);
        }
    }

    private void setupRecyclers() {
        adapter = new MusicTrackAdapter(filtered, this);
        if (rvTracks != null) {
            rvTracks.setLayoutManager(new LinearLayoutManager(this));
            rvTracks.setAdapter(adapter);
        }

        if (rvRecent != null) {
            recentAdapter = new MusicTrackAdapter(recentTracks, new MusicTrackAdapter.OnTrackActionListener() {
                @Override public void onPreviewToggle(MusicTrack t, int p) { previewToggleImpl(t, p); }
                @Override public void onSelect(MusicTrack t)               { selectTrack(t); }
                @Override public void onSaveToggle(MusicTrack t, int p)    { saveToggle(t, p); }
                @Override public void onLongPress(MusicTrack t)            { openSoundDetail(t); }
            });
            rvRecent.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rvRecent.setAdapter(recentAdapter);
        }
    }

    private void setupSearch() {
        if (etSearch == null) return;
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterTracks(s.toString());
            }
        });
    }

    private void loadSavedIds() {
        if (myUid == null) return;
        FirebaseUtils.getUserRef(myUid).child("saved_sounds")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    savedIds.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        if (s.getKey() != null) savedIds.add(s.getKey());
                    }
                    adapter.setSavedIds(savedIds);
                    if (recentAdapter != null) recentAdapter.setSavedIds(savedIds);
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void filterTracks(String query) {
        filtered.clear();
        String q = query.toLowerCase().trim();

        for (MusicTrack t : allTracks) {
            boolean matchesGenre;
            switch (selectedGenre) {
                case "all":
                    matchesGenre = true;
                    break;
                case "trending":
                    matchesGenre = t.trendingRank > 0 && t.trendingRank <= 50;
                    break;
                case "for_you":
                    // Match against user's listening history genres
                    matchesGenre = historyGenres.isEmpty() || (
                        (t.genre != null && historyGenres.contains(t.genre.toLowerCase())) ||
                        (t.mood  != null && historyGenres.contains(t.mood.toLowerCase()))
                    );
                    break;
                default:
                    matchesGenre =
                        (t.genre != null && t.genre.toLowerCase().contains(selectedGenre)) ||
                        (t.mood  != null && t.mood.toLowerCase().contains(selectedGenre));
                    break;
            }

            boolean matchesQuery = q.isEmpty()
                || t.getDisplayTitle().toLowerCase().contains(q)
                || (t.artist != null && t.artist.toLowerCase().contains(q))
                || (t.genre  != null && t.genre.toLowerCase().contains(q))
                || (t.mood   != null && t.mood.toLowerCase().contains(q))
                || (t.language != null && t.language.toLowerCase().contains(q));

            if (matchesGenre && matchesQuery) filtered.add(t);
        }

        // Sort
        if ("trending".equals(selectedGenre)) {
            filtered.sort((a, b) -> Long.compare(a.trendingRank, b.trendingRank));
        } else if ("for_you".equals(selectedGenre)) {
            // Personalised: boost tracks in user's preferred genres, then by usageCount
            filtered.sort((a, b) -> {
                boolean aMatch = (a.genre != null && historyGenres.contains(a.genre.toLowerCase()));
                boolean bMatch = (b.genre != null && historyGenres.contains(b.genre.toLowerCase()));
                if (aMatch != bMatch) return aMatch ? -1 : 1;
                return Long.compare(b.usageCount, a.usageCount);
            });
            if (filtered.size() > MAX_FOR_YOU) {
                List<MusicTrack> trimmed = new ArrayList<>(filtered.subList(0, MAX_FOR_YOU));
                filtered.clear();
                filtered.addAll(trimmed);
            }
        } else {
            filtered.sort((a, b) -> Long.compare(b.usageCount, a.usageCount));
        }

        adapter.notifyDataSetChanged();
        if (layoutEmpty != null) layoutEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadMusicLibrary() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        FirebaseUtils.getMusicLibraryRef()
            .orderByChild("usageCount")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allTracks.clear();
                    for (DataSnapshot child : snap.getChildren()) {
                        MusicTrack track = child.getValue(MusicTrack.class);
                        if (track != null) {
                            track.trackId = child.getKey();
                            allTracks.add(track);
                        }
                    }
                    if (allTracks.isEmpty()) loadDefaultTracks();
                    else {
                        Collections.sort(allTracks, (a, b) -> Long.compare(b.usageCount, a.usageCount));
                        filterTracks("");
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {
                    loadDefaultTracks();
                }
            });
    }

    private void loadDefaultTracks() {
        allTracks.clear();
        Object[][] defaults = {
            {"Trending Beat 1",  "CallX Originals", "Pop",       "Energetic", 8200L, 1L,  128,  false},
            {"Chill Vibes",      "CallX Originals", "Chill",     "Relaxed",   5100L, 3L,   90,  false},
            {"Hype Mode",        "CallX Originals", "Hip-Hop",   "Hype",      7400L, 2L,  140,  false},
            {"Romantic Mood",    "CallX Originals", "Romantic",  "Romantic",  3200L, 5L,   72,  false},
            {"Dance Fever",      "CallX Originals", "Dance",     "Energetic", 6300L, 4L,  128,  false},
            {"Lo-Fi Study",      "CallX Originals", "Lo-Fi",     "Focused",   4800L, 7L,   80,  false},
            {"Party Anthem",     "CallX Originals", "EDM",       "Party",     9100L, 0L,  138,  false},
            {"Acoustic Soul",    "CallX Originals", "Acoustic",  "Calm",      2700L, 0L,   68,  false},
            {"Bollywood Hits",   "CallX Originals", "Bollywood", "Festive",   3900L, 8L,  115,  false},
            {"R&B Smooth",       "CallX Originals", "R&B",       "Smooth",    2100L, 11L,  85,  false},
        };
        for (Object[] d : defaults) {
            MusicTrack t = new MusicTrack();
            t.name         = (String)  d[0];
            t.title        = (String)  d[0];
            t.artist       = (String)  d[1];
            t.genre        = (String)  d[2];
            t.mood         = (String)  d[3];
            t.usageCount   = (Long)    d[4];
            t.trendingRank = (Long)    d[5];
            t.bpm          = (Integer) d[6];
            t.isOriginalSound = (Boolean) d[7];
            t.trackId      = t.name.replace(" ", "_").toLowerCase();
            t.durationMs   = 30_000L;
            allTracks.add(t);
        }
        filterTracks("");
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    private void loadRecentTracks() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_RECENT, MODE_PRIVATE);
        String json = prefs.getString("recent_ids", "");
        if (json.isEmpty()) {
            if (layoutRecentSection != null) layoutRecentSection.setVisibility(View.GONE);
            return;
        }
        String[] ids = json.split(",");
        if (ids.length == 0) {
            if (layoutRecentSection != null) layoutRecentSection.setVisibility(View.GONE);
            return;
        }
        if (layoutRecentSection != null) layoutRecentSection.setVisibility(View.VISIBLE);
        for (String id : ids) {
            final String trackId = id.trim();
            if (trackId.isEmpty()) continue;
            FirebaseUtils.getMusicLibraryRef().child(trackId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        MusicTrack t = snap.getValue(MusicTrack.class);
                        if (t != null) {
                            t.trackId = trackId;
                            recentTracks.add(t);
                            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }
    }

    private void saveToRecent(String trackId) {
        if (trackId == null || trackId.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_RECENT, MODE_PRIVATE);
        String existing = prefs.getString("recent_ids", "");
        List<String> ids = new ArrayList<>(Arrays.asList(existing.split(",")));
        ids.remove(trackId);
        ids.add(0, trackId);
        if (ids.size() > MAX_RECENT) ids = ids.subList(0, MAX_RECENT);
        prefs.edit().putString("recent_ids", String.join(",", ids)).apply();
    }

    private void selectTrack(MusicTrack track) {
        stopPreview();
        saveToRecent(track.trackId);
        Intent result = new Intent();
        result.putExtra(EXTRA_MUSIC_NAME,
            track.getDisplayTitle() + (track.artist != null && !track.artist.isEmpty()
                ? " – " + track.artist : ""));
        result.putExtra(EXTRA_MUSIC_URL,       track.audioUrl  != null ? track.audioUrl  : "");
        result.putExtra(EXTRA_MUSIC_ID,        track.trackId   != null ? track.trackId   : "");
        result.putExtra(EXTRA_MUSIC_COVER_URL, track.coverUrl  != null ? track.coverUrl  : "");
        result.putExtra(EXTRA_MUSIC_ARTIST,    track.artist    != null ? track.artist    : "");
        setResult(RESULT_OK, result);
        finish();
    }

    private void openSoundDetail(MusicTrack track) {
        Intent i = new Intent(this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    track.trackId  != null ? track.trackId  : "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, track.getDisplayTitle());
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      track.artist   != null ? track.artist   : "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   track.audioUrl != null ? track.audioUrl : "");
        i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   track.coverUrl != null ? track.coverUrl : "");
        i.putExtra(SoundDetailActivity.EXTRA_DURATION_MS, (int) track.getDurationMs());
        i.putExtra(SoundDetailActivity.EXTRA_BPM,         track.bpm);
        i.putExtra(SoundDetailActivity.EXTRA_GENRE,       track.genre    != null ? track.genre    : "");
        startActivity(i);
    }

    private void saveToggle(MusicTrack track, int pos) {
        if (myUid == null || track.trackId == null) return;
        boolean currentlySaved = savedIds.contains(track.trackId);
        if (currentlySaved) {
            savedIds.remove(track.trackId);
            FirebaseUtils.getUserRef(myUid).child("saved_sounds").child(track.trackId).removeValue();
            Toast.makeText(this, "Sound removed", Toast.LENGTH_SHORT).show();
        } else {
            savedIds.add(track.trackId);
            FirebaseUtils.getUserRef(myUid).child("saved_sounds").child(track.trackId)
                .setValue(track.getDisplayTitle());
            Toast.makeText(this, "Sound saved", Toast.LENGTH_SHORT).show();
        }
        adapter.setSavedIds(savedIds);
        adapter.notifyItemChanged(pos);
    }

    private void previewToggleImpl(MusicTrack track, int position) {
        if (currentlyPlaying != null && currentlyPlaying.trackId != null
                && currentlyPlaying.trackId.equals(track.trackId)) {
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
                Toast.makeText(this, "Preview not available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override public void onPreviewToggle(MusicTrack track, int position) { previewToggleImpl(track, position); }
    @Override public void onSelect(MusicTrack track)                      { selectTrack(track); }
    @Override public void onSaveToggle(MusicTrack track, int position)    { saveToggle(track, position); }
    @Override public void onLongPress(MusicTrack track)                   { openSoundDetail(track); }

    private void stopPreview() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        currentlyPlaying    = null;
        currentlyPlayingPos = -1;
    }

    @Override protected void onDestroy() { stopPreview(); super.onDestroy(); }
}
