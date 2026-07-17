package com.callx.app.music;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * SoundSearchActivity — Feature 2: Real-time sound search.
 *
 * Searches BOTH sources:
 *   • musicLibrary/ — licensed / curated tracks (by title prefix)
 *   • sounds/       — user-created original audio (by title prefix)
 *
 * Results are merged, deduped, and shown in a single RecyclerView.
 * Tapping a result opens SoundDetailActivity.
 * Includes "Use" button to return the sound directly to the caller.
 *
 * Firebase paths:
 *   musicLibrary/{id}: title, artist, coverUrl, audioUrl, bpm, usageCount, genre
 *   sounds/{id}:       title, artist, coverUrl, audioUrl, reel_count, creatorUid
 */
public class SoundSearchActivity extends AppCompatActivity {

    public static final String EXTRA_MODE_PICK   = "mode_pick";   // if true, "Use" returns RESULT_OK
    public static final String RESULT_SOUND_ID   = "sound_id";
    public static final String RESULT_SOUND_TITLE= "sound_title";
    public static final String RESULT_SOUND_URL  = "sound_url";
    public static final String RESULT_COVER_URL  = "sound_cover_url";
    public static final String RESULT_ARTIST     = "sound_artist";
    public static final String RESULT_BPM        = "sound_bpm";

    private EditText   etSearch;
    private RecyclerView rv;
    private ProgressBar  pbSearch;
    private TextView     tvEmpty;
    private ImageButton  btnBack, btnClear;

    private final List<SoundResult> results = new ArrayList<>();
    private SoundResultAdapter      adapter;
    private boolean                 pickMode = false;

    private final Handler  debounce = new Handler(Looper.getMainLooper());
    private Runnable       pendingSearch;
    private static final long DEBOUNCE_MS = 350;

    // Track in-flight Firebase listeners so we can cancel stale ones
    private Query libQuery, soundsQuery;
    private ValueEventListener libListener, soundsListener;

    // Merged result set (prevents duplicate IDs)
    private final Map<String, SoundResult> resultMap = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_search);
        pickMode = getIntent().getBooleanExtra(EXTRA_MODE_PICK, false);

        bindViews();
        etSearch.requestFocus();
    }

    private void bindViews() {
        etSearch = findViewById(R.id.et_sound_search);
        rv       = findViewById(R.id.rv_sound_search_results);
        pbSearch = findViewById(R.id.pb_sound_search);
        tvEmpty  = findViewById(R.id.tv_sound_search_empty);
        btnBack  = findViewById(R.id.btn_sound_search_back);
        btnClear = findViewById(R.id.btn_sound_search_clear);

        if (btnBack  != null) btnBack.setOnClickListener(v -> finish());
        if (btnClear != null) {
            btnClear.setVisibility(View.GONE);
            btnClear.setOnClickListener(v -> {
                etSearch.setText("");
                results.clear();
                resultMap.clear();
                adapter.notifyDataSetChanged();
                showEmpty(false);
            });
        }

        adapter = new SoundResultAdapter(results, this::onResultTapped, this::onUseTapped);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(adapter);
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String q = s.toString().trim();
                    if (btnClear != null) btnClear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                    if (pendingSearch != null) debounce.removeCallbacks(pendingSearch);
                    if (q.length() < 2) {
                        results.clear(); resultMap.clear();
                        adapter.notifyDataSetChanged();
                        showEmpty(false);
                        return;
                    }
                    pendingSearch = () -> runSearch(q);
                    debounce.postDelayed(pendingSearch, DEBOUNCE_MS);
                }
            });
        }
    }

    private void runSearch(String query) {
        cancelPendingListeners();
        resultMap.clear();
        results.clear();
        adapter.notifyDataSetChanged();
        showLoading(true);

        String lo = query.toLowerCase(Locale.ROOT);
        // Firebase prefix query trick: \uf8ff is highest unicode char so
        // startAt(lo).endAt(lo + "\uf8ff") matches all titles starting with `lo`
        String hibound = lo + "\uf8ff";

        final boolean[] done = {false, false}; // [libDone, soundsDone]

        // ── Search musicLibrary ──────────────────────────────────────────
        libQuery = FirebaseUtils.getMusicLibraryRef()
            .orderByChild("title")
            .startAt(lo).endAt(hibound)
            .limitToFirst(30);
        libListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot s : snap.getChildren()) {
                    String id      = s.getKey();
                    String title   = nvl(s.child("title").getValue(String.class));
                    String artist  = nvl(s.child("artist").getValue(String.class));
                    String cover   = nvl(s.child("coverUrl").getValue(String.class));
                    String audio   = nvl(s.child("audioUrl").getValue(String.class));
                    Long   uses    = s.child("usageCount").getValue(Long.class);
                    Integer bpm    = s.child("bpm").getValue(Integer.class);
                    String genre   = nvl(s.child("genre").getValue(String.class));
                    if (id != null && !title.isEmpty()) {
                        SoundResult r = new SoundResult(id, title, artist, cover, audio,
                            uses != null ? uses : 0L, bpm != null ? bpm : 0, genre, false);
                        resultMap.put(id, r);
                    }
                }
                done[0] = true;
                if (done[1]) finishMerge();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                done[0] = true;
                if (done[1]) finishMerge();
            }
        };
        libQuery.addListenerForSingleValueEvent(libListener);

        // ── Search sounds (original audio) ───────────────────────────────
        soundsQuery = FirebaseUtils.db().getReference("sounds")
            .orderByChild("title")
            .startAt(lo).endAt(hibound)
            .limitToFirst(30);
        soundsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot s : snap.getChildren()) {
                    String id     = s.getKey();
                    String title  = nvl(s.child("title").getValue(String.class));
                    String artist = nvl(s.child("artist").getValue(String.class));
                    String cover  = nvl(s.child("coverUrl").getValue(String.class));
                    String audio  = nvl(s.child("audioUrl").getValue(String.class));
                    Long   reels  = s.child("reel_count").getValue(Long.class);
                    if (id != null && !title.isEmpty() && !resultMap.containsKey(id)) {
                        SoundResult r = new SoundResult(id, title, artist, cover, audio,
                            reels != null ? reels : 0L, 0, "Original", true);
                        resultMap.put(id, r);
                    }
                }
                done[1] = true;
                if (done[0]) finishMerge();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                done[1] = true;
                if (done[0]) finishMerge();
            }
        };
        soundsQuery.addListenerForSingleValueEvent(soundsListener);
    }

    private void finishMerge() {
        if (isFinishing() || isDestroyed()) return;
        showLoading(false);
        results.clear();
        results.addAll(resultMap.values());
        // Sort: higher usage first
        results.sort((a, b) -> Long.compare(b.reelCount, a.reelCount));
        adapter.notifyDataSetChanged();
        showEmpty(results.isEmpty());
    }

    private void onResultTapped(SoundResult r) {
        Intent i = new Intent(this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    r.id);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, r.title);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   r.audioUrl);
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      r.artist);
        i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   r.coverUrl);
        i.putExtra(SoundDetailActivity.EXTRA_BPM,         r.bpm);
        startActivity(i);
    }

    private void onUseTapped(SoundResult r) {
        if (pickMode) {
            Intent result = new Intent();
            result.putExtra(RESULT_SOUND_ID,    r.id);
            result.putExtra(RESULT_SOUND_TITLE, r.title);
            result.putExtra(RESULT_SOUND_URL,   r.audioUrl);
            result.putExtra(RESULT_COVER_URL,   r.coverUrl);
            result.putExtra(RESULT_ARTIST,      r.artist);
            result.putExtra(RESULT_BPM,         r.bpm);
            setResult(RESULT_OK, result);
            finish();
        } else {
            onResultTapped(r);
        }
    }

    private void cancelPendingListeners() {
        try {
            if (libQuery    != null && libListener    != null) libQuery.removeEventListener(libListener);
            if (soundsQuery != null && soundsListener != null) soundsQuery.removeEventListener(soundsListener);
        } catch (Exception ignored) {}
        libQuery = null; libListener = null;
        soundsQuery = null; soundsListener = null;
    }

    private void showLoading(boolean show) {
        if (pbSearch != null) pbSearch.setVisibility(show ? View.VISIBLE : View.GONE);
        if (tvEmpty  != null) tvEmpty.setVisibility(View.GONE);
    }

    private void showEmpty(boolean show) {
        if (tvEmpty != null) tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override protected void onDestroy() {
        debounce.removeCallbacksAndMessages(null);
        cancelPendingListeners();
        super.onDestroy();
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // ── Data model ────────────────────────────────────────────────────────────

    static class SoundResult {
        String  id, title, artist, coverUrl, audioUrl, genre;
        long    reelCount;
        int     bpm;
        boolean isOriginal;
        SoundResult(String id, String title, String artist, String coverUrl,
                    String audioUrl, long reelCount, int bpm, String genre, boolean isOriginal) {
            this.id = id; this.title = title; this.artist = artist;
            this.coverUrl = coverUrl; this.audioUrl = audioUrl;
            this.reelCount = reelCount; this.bpm = bpm;
            this.genre = genre; this.isOriginal = isOriginal;
        }
    }

    interface SoundAction { void run(SoundResult r); }

    // ── Adapter ───────────────────────────────────────────────────────────────

    static class SoundResultAdapter extends RecyclerView.Adapter<SoundResultAdapter.VH> {
        private final List<SoundResult> items;
        private final SoundAction onTap, onUse;
        SoundResultAdapter(List<SoundResult> items, SoundAction onTap, SoundAction onUse) {
            this.items = items; this.onTap = onTap; this.onUse = onUse;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_sound_search_result, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            SoundResult r = items.get(pos);
            h.tvTitle.setText(r.title);
            h.tvArtist.setText(r.artist.isEmpty() ? "Original Audio" : r.artist);
            h.tvMeta.setText(fmtCount(r.reelCount) + " reels"
                + (r.bpm > 0 ? "  •  " + r.bpm + " BPM" : ""));
            h.tvBadge.setText(r.isOriginal ? "Original" : r.genre.isEmpty() ? "Music" : r.genre);

            if (r.coverUrl != null && !r.coverUrl.isEmpty()) {
                com.bumptech.glide.Glide.with(h.ivCover)
                    .load(r.coverUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(h.ivCover);
            } else {
                h.ivCover.setImageResource(R.drawable.ic_music_note);
            }

            h.itemView.setOnClickListener(v -> onTap.run(r));
            h.btnUse.setOnClickListener(v -> onUse.run(r));
        }

        @Override public int getItemCount() { return items.size(); }

        static String fmtCount(long n) {
            if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }

        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivCover;
            TextView tvTitle, tvArtist, tvMeta, tvBadge;
            Button   btnUse;
            VH(View v) {
                super(v);
                ivCover  = v.findViewById(R.id.iv_sound_search_cover);
                tvTitle  = v.findViewById(R.id.tv_sound_search_title);
                tvArtist = v.findViewById(R.id.tv_sound_search_artist);
                tvMeta   = v.findViewById(R.id.tv_sound_search_meta);
                tvBadge  = v.findViewById(R.id.tv_sound_search_badge);
                btnUse   = v.findViewById(R.id.btn_sound_search_use);
            }
        }
    }
}
