package com.callx.app.music;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

// ✅ CONNECTED: ReelTrendingAudioActivity → SoundDetailActivity

/**
 * ReelTrendingAudioActivity — Dedicated trending audio browser.
 *
 * Features:
 *  ✅ Category tabs: Trending / Viral / New / Saved
 *      - Trending: all tracks sorted by usageCount (most used)
 *      - Viral:    tracks added in last 7 days with high usageCount (recent viral)
 *      - New:      tracks added in last 24h sorted by addedAt (freshest first)
 *      - Saved:    user's saved/bookmarked tracks
 *  ✅ Genre filter chips (All, Pop, Hip-Hop, Chill, EDM, Romantic, Lo-Fi, Dance)
 *  ✅ Loads top 100 music tracks from Firebase musicLibrary
 *  ✅ Inline 30-second preview with MediaPlayer (play/stop toggle per row)
 *  ✅ Usage count badge (e.g. "14.2K reels")
 *  ✅ Trending rank badge (shown when rank ≤ 50)
 *  ✅ BPM badge (when available)
 *  ✅ Save/unsave audio to savedSounds/{uid}
 *  ✅ "Use" button → returns audio to caller
 *  ✅ Search filter within loaded tracks
 *  ✅ Auto-stops any playing preview when another starts
 *  ✅ Empty state per tab
 *  ✅ Viral tab's window/threshold is backend-configurable
 *     (appConfig/reelsViral/{windowDays,minUses}), no longer hardcoded
 *  ✅ Room-backed offline cache — reopening the screen paints instantly
 *     from the last-loaded page instead of always waiting on Firebase
 *  ✅ Selected genre chip auto-scrolls into view if it's off-screen
 */
public class ReelTrendingAudioActivity extends AppCompatActivity {

    public static final String RESULT_AUDIO_ID    = "audio_id";
    public static final String RESULT_AUDIO_TITLE = "audio_title";
    public static final String RESULT_AUDIO_ARTIST= "audio_artist";
    public static final String RESULT_AUDIO_URL   = "audio_url";
    public static final String RESULT_COVER_URL   = "audio_cover_url";
    // Low-bitrate preview stream only — used by callers (e.g. Status music sticker)
    // that just need to play the track, not the full-quality master (audio_url).
    public static final String RESULT_PREVIEW_AUDIO_URL = "audio_preview_url";

    // ✅ NEW: Request code for opening SoundDetailActivity
    private static final int REQ_SOUND_DETAIL = 901;

    // 🛠️ CONFIGURABLE: these used to be hardcoded and required an app
    // release to change. They're now defaults only — the real values are
    // loaded (and can be overridden) from Firebase in loadViralConfig(),
    // with the last-fetched values cached in SharedPreferences so the
    // screen still reflects the last known server setting even before
    // that read completes or if it fails offline.
    private static final long DEFAULT_VIRAL_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long DEFAULT_VIRAL_MIN_USES  = 500L;
    private static final long NEW_WINDOW_MS   = 24L * 60 * 60 * 1000;
    private long viralWindowMs = DEFAULT_VIRAL_WINDOW_MS;
    private long viralMinUses  = DEFAULT_VIRAL_MIN_USES;
    private static final String VIRAL_CONFIG_PREFS    = "reel_trending_audio_config";
    private static final String KEY_VIRAL_WINDOW_DAYS = "viral_window_days";
    private static final String KEY_VIRAL_MIN_USES    = "viral_min_uses";

    // 📴 OFFLINE CACHE: single background executor for Room reads/writes
    // backing the Trending Audio browser's offline warm-start (see
    // TrendingAudioCacheManager).
    private final java.util.concurrent.ExecutorService dbExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    private ImageButton  btnBack;
    private EditText     etSearch;
    private LinearLayout tabTrending, tabViral, tabNew, tabSaved;
    private View         indTrending, indViral, indNew, indSaved;
    private HorizontalScrollView hsvGenreChips;
    private LinearLayout layoutGenreChips;
    private RecyclerView rv;
    private ProgressBar  progress;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    private TextView     tvEmpty;
    // 🛡️ Network/offline error state
    private View         layoutError;
    private TextView     tvError;
    private Button       btnRetry;

    // Pagination — Firebase limitToLast is grown page-by-page as the user
    // scrolls near the bottom, instead of a fixed one-shot top-100/top-80 cap.
    private static final int TRENDING_PAGE_SIZE = 100;
    private static final int SOUNDS_PAGE_SIZE   = 80;
    private int  trendingLimit = TRENDING_PAGE_SIZE;
    private int  soundsLimit   = SOUNDS_PAGE_SIZE;
    private boolean hasMoreTrending   = true;
    private boolean hasMoreSounds     = true;
    private boolean loadingMoreTrending = false;
    private boolean loadingMoreSounds   = false;

    private final List<Audio> allTracks       = new ArrayList<>();
    private final List<Audio> allSoundsTracks = new ArrayList<>(); // ✅ Feature 1: sounds/ node
    private final List<Audio> displayed       = new ArrayList<>();
    private final Set<String> savedIds        = new HashSet<>();
    // 🔎 SEARCH FIX: tracks found via runServerSearch() that live outside the
    // locally-loaded page (top 100 musicLibrary / top 80 sounds) — merged
    // into the candidate pool in filterDisplayed() via withSearchExtras().
    private final Map<String, Audio> extraLibraryMatches = new HashMap<>();
    private final Map<String, Audio> extraSoundsMatches  = new HashMap<>();
    private Query activeSearchQuery;
    private ValueEventListener activeSearchListener;
    private int searchGeneration = 0;
    private AudioAdapter adapter;
    private String  myUid;
    private String  currentTab   = "trending";
    private String  selectedGenre= "all";
    private String  dateFilter   = "all";   // "today" | "week" | "all"
    private MediaPlayer mediaPlayer;
    private String  playingId    = null;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final String[] GENRES = {
        "All", "Pop", "Hip-Hop", "Chill", "EDM", "Romantic", "Lo-Fi", "Dance",
        "R&B", "Acoustic", "Bollywood", "Classical"
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_trending_audio);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }
        bindViews();
        buildGenreChips();
        loadViralConfig();
        loadSavedIds();
        // 📴 OFFLINE CACHE: paint the last-loaded page from disk immediately
        // (covers cold/offline opens) — loadTracks()'s fresh Firebase read
        // below still runs right after and silently replaces it once it lands.
        loadTracksFromCache();
        loadTracks();
    }

    /**
     * 🛠️ CONFIGURABLE VIRAL THRESHOLD: reads appConfig/reelsViral/
     * {windowDays, minUses} from Firebase so the "Viral" tab's cutoff can
     * be retuned by ops without an app release. Falls back to (and caches
     * in SharedPreferences) the last known value so a slow/offline read
     * never regresses the tab back to a bare hardcoded default mid-session.
     */
    private void loadViralConfig() {
        SharedPreferences prefs = getSharedPreferences(VIRAL_CONFIG_PREFS, MODE_PRIVATE);
        long cachedWindowDays = prefs.getLong(KEY_VIRAL_WINDOW_DAYS, -1);
        long cachedMinUses    = prefs.getLong(KEY_VIRAL_MIN_USES, -1);
        if (cachedWindowDays > 0) viralWindowMs = cachedWindowDays * 24 * 60 * 60 * 1000;
        if (cachedMinUses >= 0)   viralMinUses  = cachedMinUses;

        FirebaseUtils.getReelsViralConfigRef()
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAlive()) return;
                    Long windowDays = snap.child("windowDays").getValue(Long.class);
                    Long minUses    = snap.child("minUses").getValue(Long.class);
                    boolean changed = false;
                    if (windowDays != null && windowDays > 0) {
                        long ms = windowDays * 24 * 60 * 60 * 1000;
                        if (ms != viralWindowMs) changed = true;
                        viralWindowMs = ms;
                    }
                    if (minUses != null && minUses >= 0) {
                        if (minUses != viralMinUses) changed = true;
                        viralMinUses = minUses;
                    }
                    if (windowDays != null || minUses != null) {
                        SharedPreferences.Editor ed = prefs.edit();
                        if (windowDays != null) ed.putLong(KEY_VIRAL_WINDOW_DAYS, windowDays);
                        if (minUses    != null) ed.putLong(KEY_VIRAL_MIN_USES, minUses);
                        ed.apply();
                    }
                    if (changed && "viral".equals(currentTab)) {
                        filterDisplayed(etSearch != null && etSearch.getText() != null
                            ? etSearch.getText().toString().trim() : "");
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // 🛡️ non-critical — keep whatever default/cached value we already have
                }
            });
    }

    /** 📴 OFFLINE CACHE: cold-start read of the last-cached "library" page from Room. */
    private void loadTracksFromCache() {
        dbExecutor.execute(() -> {
            List<Audio> cached = TrendingAudioCacheManager.loadPageBlocking(
                getApplicationContext(), TrendingAudioCacheManager.SOURCE_LIBRARY, TRENDING_PAGE_SIZE);
            if (cached.isEmpty()) return;
            runOnUiThread(() -> {
                if (!isAlive()) return;
                if (!allTracks.isEmpty()) return; // Firebase already won the race — don't clobber
                allTracks.addAll(cached);
                filterDisplayed(etSearch != null && etSearch.getText() != null
                    ? etSearch.getText().toString().trim() : "");
            });
        });
    }

    /** 📴 OFFLINE CACHE: cold-start read of the last-cached "sounds" page from Room. */
    private void loadSoundsTabFromCache() {
        dbExecutor.execute(() -> {
            List<Audio> cached = TrendingAudioCacheManager.loadPageBlocking(
                getApplicationContext(), TrendingAudioCacheManager.SOURCE_SOUNDS, SOUNDS_PAGE_SIZE);
            if (cached.isEmpty()) return;
            runOnUiThread(() -> {
                if (!isAlive()) return;
                if (!allSoundsTracks.isEmpty()) return; // Firebase already won the race
                allSoundsTracks.addAll(cached);
                if ("sounds".equals(currentTab)) {
                    filterDisplayed(etSearch != null && etSearch.getText() != null
                        ? etSearch.getText().toString().trim() : "");
                }
            });
        });
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_trending_audio_back);
        etSearch         = findViewById(R.id.et_trending_audio_search);
        tabTrending      = findViewById(R.id.tab_audio_trending);
        tabViral         = findViewById(R.id.tab_audio_viral);
        tabNew           = findViewById(R.id.tab_audio_new);
        tabSaved         = findViewById(R.id.tab_audio_saved);
        indTrending      = findViewById(R.id.ind_audio_trending);
        indViral         = findViewById(R.id.ind_audio_viral);
        indNew           = findViewById(R.id.ind_audio_new);
        indSaved         = findViewById(R.id.ind_audio_saved);
        hsvGenreChips    = findViewById(R.id.hsv_genre_chips);
        layoutGenreChips = findViewById(R.id.layout_genre_chips);
        rv               = findViewById(R.id.rv_trending_audio);
        progress         = findViewById(R.id.progress_trending_audio);
        swipeRefresh     = findViewById(R.id.swipe_refresh_trending_audio);
        tvEmpty          = findViewById(R.id.tv_trending_audio_empty);
        layoutError      = findViewById(R.id.layout_trending_audio_error);
        tvError          = findViewById(R.id.tv_trending_audio_error);
        btnRetry         = findViewById(R.id.btn_trending_audio_retry);
        if (btnRetry != null) btnRetry.setOnClickListener(v -> {
            hideError();
            refreshCurrentTab();
        });

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (tabTrending != null) tabTrending.setOnClickListener(v -> switchTab("trending"));
        if (tabViral    != null) tabViral.setOnClickListener(v    -> switchTab("viral"));
        if (tabNew      != null) tabNew.setOnClickListener(v      -> switchTab("new"));
        if (tabSaved    != null) tabSaved.setOnClickListener(v    -> switchTab("saved"));
        // ✅ Feature 1: "Sounds" tab — loads user-created original audio from sounds/ node
        View tabSounds = findViewById(R.id.tab_audio_sounds);
        View indSounds = findViewById(R.id.ind_audio_sounds);
        if (tabSounds != null) tabSounds.setOnClickListener(v -> {
            if (indSounds != null) {
                if (indTrending != null) indTrending.setVisibility(View.GONE);
                if (indViral    != null) indViral.setVisibility(View.GONE);
                if (indNew      != null) indNew.setVisibility(View.GONE);
                if (indSaved    != null) indSaved.setVisibility(View.GONE);
                indSounds.setVisibility(View.VISIBLE);
            }
            currentTab = "sounds";
            hideError();
            if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_NONE);
            if (allSoundsTracks.isEmpty()) { loadSoundsTabFromCache(); loadSoundsTab(); }
            else filterDisplayed(etSearch != null && etSearch.getText() != null
                ? etSearch.getText().toString().trim() : "");
            String qNow = etSearch != null && etSearch.getText() != null
                ? etSearch.getText().toString().trim() : "";
            if (!qNow.isEmpty()) runServerSearch(qNow);
        });

        adapter = new AudioAdapter(
            audio -> previewAudio(audio),
            audio -> saveToggle(audio),
            audio -> useAudio(audio),
            audio -> openSoundDetail(audio));   // ✅ NEW: item tap → SoundDetailActivity
        // 🛡️ in-list retry — tapping the footer row re-fetches just the
        // failed page in place, same as maybeLoadMore() but without
        // growing the page size again
        adapter.setOnRetryLoadMore(this::retryLoadMore);
        if (rv != null) {
            LinearLayoutManager lm = new LinearLayoutManager(this);
            rv.setLayoutManager(lm);
            rv.setAdapter(adapter);
            // ⚡ PERF: row size doesn't depend on adapter content → skip re-measure passes
            rv.setHasFixedSize(true);
            // ⚡ PERF: keep a few extra rows warm off-screen for smoother fling
            rv.setItemViewCacheSize(16);
            // ⚡ PERF: avoid image/text flicker on partial (payload) rebinds from DiffUtil
            RecyclerView.ItemAnimator anim = rv.getItemAnimator();
            if (anim instanceof SimpleItemAnimator) {
                ((SimpleItemAnimator) anim).setSupportsChangeAnimations(false);
            }
            // 📜 PAGINATION: grow the Firebase page size as the user nears the
            // bottom, instead of the old fixed top-100 (musicLibrary) / top-80
            // (sounds) one-shot cap.
            rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView v, int dx, int dy) {
                    if (dy <= 0) return; // only trigger while scrolling down
                    int lastVisible = lm.findLastVisibleItemPosition();
                    int total = lm.getItemCount();
                    if (total > 0 && lastVisible >= total - 5) maybeLoadMore();
                }
            });
        }

        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(this::refreshCurrentTab);
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    // ⚡ PERF: debounce keystrokes — avoids re-filtering/sorting on every
                    // character while the user is still typing
                    final String query = s.toString().trim();
                    handler.removeCallbacks(searchRunnable);
                    searchRunnable = () -> {
                        filterDisplayed(query);
                        // 🔎 SEARCH FIX: also query the full Firebase node
                        // (not just the already-loaded page) so a track
                        // outside the loaded top-100/top-80 can be found too
                        runServerSearch(query);
                    };
                    handler.postDelayed(searchRunnable, 150);
                }
            });
        }
    }

    private Runnable searchRunnable = () -> {};

    private void buildGenreChips() {
        if (layoutGenreChips == null) return;
        layoutGenreChips.removeAllViews();
        int dp8  = (int)(8  * getResources().getDisplayMetrics().density);
        int dp14 = (int)(14 * getResources().getDisplayMetrics().density);
        int dp4  = (int)(4  * getResources().getDisplayMetrics().density);

        for (String g : GENRES) {
            TextView chip = new TextView(this);
            chip.setText(g);
            chip.setTextSize(12f);
            chip.setSingleLine(true);
            chip.setPadding(dp14, dp4, dp14, dp4);
            chip.setClickable(true);
            // ♿/RTL: MarginLayoutParams.setMarginEnd() is direction-aware —
            // in an RTL locale (app has android:supportsRtl="true") the chip
            // row mirrors and this margin correctly ends up on the visual
            // left, unlike the previous absolute setMargins(0,0,dp8,0) which
            // always drew the gap on the physical right regardless of layout
            // direction.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp8);
            chip.setLayoutParams(lp);

            // ♿ ACCESSIBILITY: plain TextViews are announced as static text
            // by TalkBack with no indication they're tappable filters or
            // whether they're currently selected. Expose them as toggle
            // buttons with a proper selected/checked state instead.
            chip.setFocusable(true);
            chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            androidx.core.view.ViewCompat.setAccessibilityDelegate(chip,
                new androidx.core.view.AccessibilityDelegateCompat() {
                    @Override public void onInitializeAccessibilityNodeInfo(
                            View host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                        info.setCheckable(true);
                        info.setChecked(host.isSelected());
                    }
                });

            boolean active = g.equalsIgnoreCase("All");
            setChipStyle(chip, active);

            chip.setOnClickListener(v -> {
                selectedGenre = g.equalsIgnoreCase("All") ? "all" : g.toLowerCase(Locale.US);
                for (int i = 0; i < layoutGenreChips.getChildCount(); i++) {
                    View c = layoutGenreChips.getChildAt(i);
                    if (c instanceof TextView) setChipStyle((TextView)c, c == chip);
                }
                scrollChipIntoView(chip);
                filterDisplayed(etSearch != null && etSearch.getText() != null
                    ? etSearch.getText().toString().trim() : "");
            });
            layoutGenreChips.addView(chip);
        }
    }

    private void setChipStyle(TextView chip, boolean active) {
        chip.setSelected(active); // ♿ backs the AccessibilityDelegate's checked state above
        if (active) {
            chip.setBackgroundResource(R.drawable.bg_speed_chip_active);
            chip.setTextColor(0xFFFFFFFF);
        } else {
            chip.setBackgroundResource(R.drawable.bg_speed_chip);
            chip.setTextColor(0xCCFFFFFF);
        }
    }

    /**
     * 🐛 FIX: tapping a genre chip that's off-screen (list wider than the
     * visible strip) never scrolled it into view — the selection state
     * changed but the highlighted chip itself stayed hidden, so a user
     * tapping near either edge couldn't see which genre was now active.
     * Mirrors the tab-indicator auto-scroll pattern used elsewhere in the
     * app: compute the chip's left/right edge relative to the scroll
     * view's current viewport and nudge just enough to bring it fully
     * into view, instead of a hard-centering scrollTo that would jump
     * unrelated chips around too.
     */
    private void scrollChipIntoView(View chip) {
        if (hsvGenreChips == null || chip == null) return;
        hsvGenreChips.post(() -> {
            int chipLeft   = chip.getLeft();
            int chipRight  = chip.getRight();
            int scrollX    = hsvGenreChips.getScrollX();
            int viewportW  = hsvGenreChips.getWidth();
            if (viewportW <= 0) return;
            int edgePad = (int) (12 * getResources().getDisplayMetrics().density);
            if (chipLeft - edgePad < scrollX) {
                hsvGenreChips.smoothScrollTo(Math.max(0, chipLeft - edgePad), 0);
            } else if (chipRight + edgePad > scrollX + viewportW) {
                hsvGenreChips.smoothScrollTo(chipRight + edgePad - viewportW, 0);
            }
        });
    }

    /**
     * 🐛 FIX: exact token match instead of substring contains(). The old
     * `a.searchGenre.contains(selectedGenre)` false-matched any field that
     * merely embeds the selected word — e.g. the "Chill" chip would also
     * match a track tagged mood "chillout", and genre strings combined via
     * comma ("Pop, Chillout") diluted matches further. Splitting on
     * comma/slash/semicolon and comparing whole tokens keeps hyphenated
     * genre names like "hip-hop" intact (so they're never split into
     * separate "hip"/"hop" tokens) while requiring a full-word match.
     */
    private static boolean genreFieldMatches(String field, String genre) {
        if (field == null || field.isEmpty()) return false;
        for (String token : field.split("[,/;]+")) {
            if (token.trim().equals(genre)) return true;
        }
        return false;
    }

    private void loadSavedIds() {
        if (myUid == null) return;
        FirebaseUtils.getUserRef(myUid).child("saved_sounds")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAlive()) return;
                    for (DataSnapshot s : snap.getChildren()) {
                        String id = s.getKey();
                        if (id != null) savedIds.add(id);
                    }
                    adapter.setSavedIds(savedIds);
                    resolveSavedTracks();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * BUG FIX: a sound saved from SoundDetail (opened from reels/status/many
     * entry points, not just this screen's own "Trending"/"Sounds" tabs)
     * could be saved before its full track data was ever loaded into either
     * allTracks (musicLibrary/) or allSoundsTracks (sounds/) here — the old
     * "Saved" tab only matched savedIds against allTracks, so anything saved
     * from a track this screen hadn't already loaded (e.g. an original
     * sounds/ track, before the user ever opened the "Sounds" tab in this
     * session) silently never appeared under Saved.
     *
     * This resolves every saved id not already present in allTracks or
     * allSoundsTracks by a direct one-time Firebase read — musicLibrary/{id}
     * first, then sounds/{id} — and caches the result so the Saved tab shows
     * every saved sound regardless of which other tabs have loaded so far.
     */
    private final Map<String, Audio> resolvedSavedTracks = new HashMap<>();

    private void resolveSavedTracks() {
        for (String id : savedIds) {
            if (id == null || id.isEmpty()) continue;
            if (resolvedSavedTracks.containsKey(id)) continue;
            boolean alreadyLoaded = false;
            for (Audio a : allTracks)       if (id.equals(a.id)) { alreadyLoaded = true; break; }
            if (!alreadyLoaded) for (Audio a : allSoundsTracks) if (id.equals(a.id)) { alreadyLoaded = true; break; }
            if (alreadyLoaded) continue;

            resolvedSavedTracks.put(id, null); // placeholder — prevents duplicate in-flight reads
            FirebaseUtils.getMusicLibraryRef().child(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (snap.exists()) { onSavedTrackResolved(buildAudioFromMusicLibrarySnapshot(snap)); }
                        else fetchFromSoundsNode(id);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { fetchFromSoundsNode(id); }
                });
        }
    }

    private void fetchFromSoundsNode(String id) {
        FirebaseUtils.db().getReference("sounds").child(id)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    onSavedTrackResolved(snap.exists() ? buildAudioFromSoundsSnapshot(snap) : null);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { onSavedTrackResolved(null); }
            });
    }

    private void onSavedTrackResolved(Audio audio) {
        if (!isAlive()) return;
        if (audio != null && audio.id != null) {
            resolvedSavedTracks.put(audio.id, audio);
            if ("saved".equals(currentTab)) {
                filterDisplayed(etSearch != null && etSearch.getText() != null
                    ? etSearch.getText().toString().trim() : "");
            }
        }
        // null result (track deleted/unreadable) stays as a placeholder so we
        // don't retry every time resolveSavedTracks() runs again.
    }

    private Audio buildAudioFromMusicLibrarySnapshot(DataSnapshot s) {
        Audio a = new Audio();
        a.id         = s.getKey();
        a.title      = s.child("title").getValue(String.class);
        if (a.title == null) a.title = s.child("name").getValue(String.class);
        a.artist     = s.child("artist").getValue(String.class);
        a.audioUrl   = s.child("audioUrl").getValue(String.class);
        a.previewAudioUrl = s.child("previewAudioUrl").getValue(String.class);
        a.coverUrl   = s.child("coverUrl").getValue(String.class);
        a.genre      = s.child("genre").getValue(String.class);
        a.mood       = s.child("mood").getValue(String.class);
        Long uc      = s.child("usageCount").getValue(Long.class);
        a.usageCount = uc != null ? uc : 0;
        Long dur     = s.child("durationMs").getValue(Long.class);
        a.durationMs = dur != null ? dur : 0;
        Integer bpmV = s.child("bpm").getValue(Integer.class);
        a.bpm        = bpmV != null ? bpmV : 0;
        a.buildSearchCache();
        return a;
    }

    private Audio buildAudioFromSoundsSnapshot(DataSnapshot s) {
        Audio a = new Audio();
        a.id         = s.getKey();
        a.title      = s.child("title").getValue(String.class);
        a.artist     = s.child("artist").getValue(String.class);
        a.audioUrl   = s.child("audioUrl").getValue(String.class);
        a.previewAudioUrl = s.child("previewAudioUrl").getValue(String.class);
        a.coverUrl   = s.child("coverUrl").getValue(String.class);
        a.genre      = "Original";
        Long rc      = s.child("reel_count").getValue(Long.class);
        a.usageCount = rc != null ? rc : 0;
        a.buildSearchCache();
        return a;
    }

    /**
     * ✅ Feature 1: Load user-created original audio from sounds/ node,
     * ordered by reel_count desc (limitToLast + reverse = highest first).
     * Applies dateFilter: "today" / "week" / "all".
     */
    private void loadSoundsTab() {
        // ⚡ big centered spinner only for a true first load — not for
        // pagination (small bottom spinner) or pull-to-refresh (its own spinner)
        boolean isPagination = loadingMoreSounds;
        boolean isRefresh = swipeRefresh != null && swipeRefresh.isRefreshing();
        if (progress != null && !isPagination && !isRefresh) progress.setVisibility(View.VISIBLE);
        hideError();
        FirebaseUtils.db().getReference("sounds")
            .orderByChild("reel_count").limitToLast(soundsLimit)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAlive()) return;
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_NONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    loadingMoreSounds = false;
                    // 📜 fewer rows came back than we asked for → we've reached the end
                    hasMoreSounds = snap.getChildrenCount() >= soundsLimit;
                    allSoundsTracks.clear();
                    long now = System.currentTimeMillis();
                    for (DataSnapshot s : snap.getChildren()) {
                        Audio a = new Audio();
                        a.id         = s.getKey();
                        a.title      = s.child("title").getValue(String.class);
                        if (a.title == null || a.title.isEmpty()) continue;
                        a.artist     = s.child("artist").getValue(String.class);
                        a.audioUrl   = s.child("audioUrl").getValue(String.class);
                        a.previewAudioUrl = s.child("previewAudioUrl").getValue(String.class);
                        a.coverUrl   = s.child("coverUrl").getValue(String.class);
                        a.genre      = "Original";
                        Long rc      = s.child("reel_count").getValue(Long.class);
                        a.usageCount = rc != null ? rc : 0;
                        Long ca      = s.child("created_at").getValue(Long.class);
                        a.addedAt    = ca != null ? ca : 0;
                        a.bpm        = 0;
                        a.trendingRank = Boolean.TRUE.equals(
                            s.child("is_trending").getValue(Boolean.class)) ? 1L : 0L;
                        a.buildSearchCache();
                        allSoundsTracks.add(a);
                    }
                    Collections.reverse(allSoundsTracks); // highest reel_count first
                    filterDisplayed(etSearch != null && etSearch.getText() != null
                        ? etSearch.getText().toString().trim() : "");
                    // 📴 OFFLINE CACHE: persist for instant reopen next time
                    TrendingAudioCacheManager.savePage(getApplicationContext(),
                        TrendingAudioCacheManager.SOURCE_SOUNDS, allSoundsTracks);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAlive()) return;
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    loadingMoreSounds = false;
                    // 🛡️ real load failure (network/permission) — show retry UI
                    // instead of silently leaving/replacing content with nothing
                    // to explain why. Pagination failures keep existing rows and
                    // surface an in-list retry row instead of a one-shot Toast.
                    if (allSoundsTracks.isEmpty() && !isPagination) {
                        if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_NONE);
                        showError("Couldn't load sounds. Check your connection.");
                    } else {
                        if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_ERROR);
                    }
                    filterDisplayed("");
                }
            });
    }

    private void loadTracks() {
        // ⚡ big centered spinner only for a true first load — not for
        // pagination (small bottom spinner) or pull-to-refresh (its own spinner)
        boolean isPagination = loadingMoreTrending;
        boolean isRefresh = swipeRefresh != null && swipeRefresh.isRefreshing();
        if (progress != null && !isPagination && !isRefresh) progress.setVisibility(View.VISIBLE);
        hideError();
        FirebaseUtils.getMusicLibraryRef()
            .orderByChild("usageCount").limitToLast(trendingLimit)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAlive()) return;
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_NONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    loadingMoreTrending = false;
                    // 📜 fewer rows came back than we asked for → we've reached the end
                    hasMoreTrending = snap.getChildrenCount() >= trendingLimit;
                    allTracks.clear();
                    long now = System.currentTimeMillis();
                    for (DataSnapshot s : snap.getChildren()) {
                        Audio a = new Audio();
                        a.id         = s.getKey();
                        a.title      = s.child("title").getValue(String.class);
                        if (a.title == null) a.title = s.child("name").getValue(String.class);
                        a.artist     = s.child("artist").getValue(String.class);
                        a.audioUrl   = s.child("audioUrl").getValue(String.class);
                        a.previewAudioUrl = s.child("previewAudioUrl").getValue(String.class);
                        a.coverUrl   = s.child("coverUrl").getValue(String.class);
                        a.genre      = s.child("genre").getValue(String.class);
                        a.mood       = s.child("mood").getValue(String.class);
                        Long uc      = s.child("usageCount").getValue(Long.class);
                        a.usageCount = uc != null ? uc : 0;
                        Long dur     = s.child("durationMs").getValue(Long.class);
                        a.durationMs = dur != null ? dur : 0;
                        Long rank    = s.child("trendingRank").getValue(Long.class);
                        a.trendingRank = rank != null ? rank : 0;
                        Integer bpmV = s.child("bpm").getValue(Integer.class);
                        a.bpm        = bpmV != null ? bpmV : 0;
                        Long addedAt = s.child("addedAt").getValue(Long.class);
                        a.addedAt    = addedAt != null ? addedAt : 0;
                        if (a.title != null && !a.title.isEmpty()) {
                            a.buildSearchCache();
                            allTracks.add(a);
                        }
                    }
                    Collections.reverse(allTracks);
                    hasMoreTrending = hasMoreTrending && !allTracks.isEmpty();
                    if (!allTracks.isEmpty()) {
                        // 📴 OFFLINE CACHE: persist for instant reopen
                        TrendingAudioCacheManager.savePage(getApplicationContext(),
                            TrendingAudioCacheManager.SOURCE_LIBRARY, allTracks);
                    }
                    filterDisplayed("");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAlive()) return;
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    loadingMoreTrending = false;
                    hasMoreTrending = false;
                    // 🛡️ real load failure (network/permission) — show retry UI
                    // instead of silently leaving/replacing content with nothing
                    // to explain why. Pagination failures keep existing rows and
                    // surface an in-list retry row instead of a one-shot Toast.
                    if (allTracks.isEmpty() && !isPagination) {
                        if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_NONE);
                        showError("Couldn't load trending audio. Check your connection.");
                        filterDisplayed("");
                    } else if (isPagination) {
                        if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_ERROR);
                    } else {
                        filterDisplayed("");
                    }
                }
            });
    }

    /** 🛡️ Shows the error state (message + retry button), hides list/empty state. */
    private void showError(String message) {
        if (tvError != null && message != null) tvError.setText(message);
        if (layoutError != null) layoutError.setVisibility(View.VISIBLE);
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
    }

    private void hideError() {
        if (layoutError != null) layoutError.setVisibility(View.GONE);
    }

    private void switchTab(String tab) {
        currentTab = tab;
        hideError();
        // 🛡️ a failed-page retry row from the previous tab shouldn't dangle
        // under a different tab's freshly-loaded rows
        if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_NONE);
        if (indTrending != null) indTrending.setVisibility("trending".equals(tab) ? View.VISIBLE : View.GONE);
        if (indViral    != null) indViral.setVisibility("viral".equals(tab)        ? View.VISIBLE : View.GONE);
        if (indNew      != null) indNew.setVisibility("new".equals(tab)            ? View.VISIBLE : View.GONE);
        if (indSaved    != null) indSaved.setVisibility("saved".equals(tab)        ? View.VISIBLE : View.GONE);
        if ("saved".equals(tab)) resolveSavedTracks();
        String q = etSearch != null && etSearch.getText() != null
            ? etSearch.getText().toString().trim() : "";
        filterDisplayed(q);
        // 🔎 the node searched depends on the tab (musicLibrary vs sounds) —
        // re-run so a query typed before switching still covers the new tab
        if (!q.isEmpty()) runServerSearch(q);
    }

    /**
     * 🔎 SEARCH FIX: previously the search box only ever filtered whichever
     * page was already loaded locally (top 100 musicLibrary / top 80
     * sounds), so a real track outside that window could never be found no
     * matter how exact the query. This runs the same titleLower-prefix
     * query SoundSearchActivity already uses elsewhere, against the full
     * node, and merges any newly-found tracks into the local candidate
     * pool (extraLibraryMatches / extraSoundsMatches) so every tab's own
     * filterDisplayed() logic can see them too — search now covers the
     * entire library, not just what happened to already be paged in.
     */
    private void runServerSearch(String rawQuery) {
        String lo = rawQuery.toLowerCase(Locale.US).trim();
        cancelServerSearch();
        if (lo.isEmpty() || "saved".equals(currentTab)) return;

        // 🛡️ generation guard — if the user keeps typing or switches tabs
        // before this one-shot query returns, a stale response can no
        // longer clobber a newer/more-relevant search
        final int myGen = ++searchGeneration;
        final String hibound = lo + "\uf8ff";
        final boolean wantSounds = "sounds".equals(currentTab);

        Query q = wantSounds
            ? FirebaseUtils.db().getReference("sounds")
                .orderByChild("titleLower").startAt(lo).endAt(hibound).limitToFirst(50)
            : FirebaseUtils.getMusicLibraryRef()
                .orderByChild("titleLower").startAt(lo).endAt(hibound).limitToFirst(50);

        activeSearchQuery = q;
        activeSearchListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAlive() || myGen != searchGeneration) return;
                Map<String, Audio> target = wantSounds ? extraSoundsMatches : extraLibraryMatches;
                for (DataSnapshot s : snap.getChildren()) {
                    Audio a = wantSounds ? buildAudioFromSoundsSnapshot(s) : buildAudioFromMusicLibrarySnapshot(s);
                    if (a.id != null && a.title != null && !a.title.isEmpty()) target.put(a.id, a);
                }
                // only re-render if the box still holds this exact query —
                // otherwise a newer keystroke/tab switch already owns the UI
                String live = etSearch != null && etSearch.getText() != null
                    ? etSearch.getText().toString().trim() : "";
                if (live.equalsIgnoreCase(rawQuery)) filterDisplayed(live);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                // silent — the locally-loaded matches (if any) are already showing
            }
        };
        activeSearchQuery.addListenerForSingleValueEvent(activeSearchListener);
    }

    private void cancelServerSearch() {
        if (activeSearchQuery != null && activeSearchListener != null) {
            activeSearchQuery.removeEventListener(activeSearchListener);
        }
        activeSearchQuery = null;
        activeSearchListener = null;
    }

    /** Merges server-search matches into a fresh copy of the local list —
     *  always returns a new List so callers can freely sort/subList it
     *  without ever mutating allTracks/allSoundsTracks in place. */
    private List<Audio> withSearchExtras(List<Audio> base, Map<String, Audio> extras, String q) {
        List<Audio> merged = new ArrayList<>(base);
        if (!q.isEmpty() && !extras.isEmpty()) {
            Set<String> have = new HashSet<>();
            for (Audio a : merged) have.add(a.id);
            for (Audio a : extras.values()) {
                if (a.id != null && have.add(a.id)) merged.add(a);
            }
        }
        return merged;
    }

    /** 📜 Called near the bottom of the list — grows the relevant Firebase
     *  page size and re-fetches. "saved" has no pagination (it's just the
     *  user's own saved set, always small and fully resolved already). */
    private void maybeLoadMore() {
        if ("saved".equals(currentTab)) return;
        if ("sounds".equals(currentTab)) {
            if (!hasMoreSounds || loadingMoreSounds) return;
            loadingMoreSounds = true;
            soundsLimit += SOUNDS_PAGE_SIZE;
            if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_LOADING);
            loadSoundsTab();
        } else {
            // trending / viral / new / default tabs all read from allTracks
            if (!hasMoreTrending || loadingMoreTrending) return;
            loadingMoreTrending = true;
            trendingLimit += TRENDING_PAGE_SIZE;
            if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_LOADING);
            loadTracks();
        }
    }

    /** 🛡️ Tapped from the in-list footer row after a failed load-more page.
     *  Re-requests the exact same page that just failed (the limit was
     *  already grown by maybeLoadMore() before the failure) — doesn't grow
     *  the page size again, unlike a fresh maybeLoadMore() call. */
    private void retryLoadMore() {
        if ("saved".equals(currentTab)) return;
        if ("sounds".equals(currentTab)) {
            if (loadingMoreSounds) return;
            loadingMoreSounds = true;
            if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_LOADING);
            loadSoundsTab();
        } else {
            if (loadingMoreTrending) return;
            loadingMoreTrending = true;
            if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_LOADING);
            loadTracks();
        }
    }

    /** 🔄 Pull-to-refresh — resets pagination back to page 1 and forces a
     *  fresh Firebase read for whichever tab is currently open. */
    private void refreshCurrentTab() {
        if (adapter != null) adapter.setFooterState(AudioAdapter.FOOTER_NONE);
        if ("saved".equals(currentTab)) {
            hideError();
            loadSavedIds();
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
        if ("sounds".equals(currentTab)) {
            soundsLimit = SOUNDS_PAGE_SIZE;
            hasMoreSounds = true;
            loadSoundsTab();
        } else {
            trendingLimit = TRENDING_PAGE_SIZE;
            hasMoreTrending = true;
            loadTracks();
        }
    }

    private void filterDisplayed(String q) {
        displayed.clear();
        long now = System.currentTimeMillis();

        List<Audio> source;
        switch (currentTab) {
            case "viral":
                source = new ArrayList<>();
                for (Audio a : withSearchExtras(allTracks, extraLibraryMatches, q)) {
                    boolean isRecent = (now - a.addedAt) <= viralWindowMs;
                    boolean isPopular = a.usageCount >= viralMinUses;
                    if (isRecent && isPopular) source.add(a);
                }
                source.sort((x, y) -> Double.compare(
                    y.usageCount / Math.max(1, (now - y.addedAt) / 3_600_000.0),
                    x.usageCount / Math.max(1, (now - x.addedAt) / 3_600_000.0)));
                break;

            case "new":
                List<Audio> newBase = withSearchExtras(allTracks, extraLibraryMatches, q);
                source = new ArrayList<>();
                for (Audio a : newBase) {
                    if ((now - a.addedAt) <= NEW_WINDOW_MS) source.add(a);
                }
                source.sort((x, y) -> Long.compare(y.addedAt, x.addedAt));
                if (source.isEmpty()) {
                    source = new ArrayList<>(newBase);
                    source.sort((x, y) -> Long.compare(y.addedAt, x.addedAt));
                    // only cap the "no recent uploads" fallback when not
                    // actively searching, so a real search match further
                    // down the addedAt order still surfaces
                    if (q.isEmpty() && source.size() > 20) source = source.subList(0, 20);
                }
                break;

            case "saved":
                // BUG FIX: previously only matched against allTracks
                // (musicLibrary/), so a sound saved via SoundDetail whose
                // full data lived in allSoundsTracks (sounds/) — or hadn't
                // been loaded into either list at all yet — never showed up
                // here even though it WAS saved. Now merges all three
                // sources, keyed by id, so every saved sound resolves.
                source = new ArrayList<>();
                Set<String> addedIds = new HashSet<>();
                for (Audio a : allTracks)
                    if (savedIds.contains(a.id) && addedIds.add(a.id)) source.add(a);
                for (Audio a : allSoundsTracks)
                    if (savedIds.contains(a.id) && addedIds.add(a.id)) source.add(a);
                for (Audio a : resolvedSavedTracks.values())
                    if (a != null && savedIds.contains(a.id) && addedIds.add(a.id)) source.add(a);
                break;

            case "sounds": // ✅ Feature 1: user-created original audio from sounds/ node
                source = withSearchExtras(allSoundsTracks, extraSoundsMatches, q);
                // Apply date filter
                if ("today".equals(dateFilter)) {
                    long cutToday = now - 24L * 60 * 60 * 1000;
                    source.removeIf(a -> a.addedAt < cutToday);
                } else if ("week".equals(dateFilter)) {
                    long cutWeek = now - 7L * 24 * 60 * 60 * 1000;
                    source.removeIf(a -> a.addedAt < cutWeek);
                }
                // Sort trending sounds first, then by reel_count
                source.sort((x, y) -> {
                    if (x.trendingRank > 0 && y.trendingRank == 0) return -1;
                    if (y.trendingRank > 0 && x.trendingRank == 0) return 1;
                    return Long.compare(y.usageCount, x.usageCount);
                });
                break;

            default:
                source = withSearchExtras(allTracks, extraLibraryMatches, q);
                source.sort((x, y) -> Long.compare(y.usageCount, x.usageCount));
                break;
        }

        // ⚡ PERF: lowercase the query once instead of on every item comparison
        String qLower = q.toLowerCase(Locale.US);
        for (Audio a : source) {
            // ⚡ PERF: uses pre-cached lowercase fields (built once when the
            // track was loaded) instead of calling toLowerCase() per keystroke
            boolean matchesGenre = selectedGenre.equals("all")
                || genreFieldMatches(a.searchGenre, selectedGenre)
                || genreFieldMatches(a.searchMood, selectedGenre);

            boolean matchesQ = qLower.isEmpty()
                || a.searchTitle.contains(qLower)
                || a.searchArtist.contains(qLower);

            if (matchesGenre && matchesQ) displayed.add(a);
        }

        // ⚡ PERF: DiffUtil computes the minimal set of row changes instead of
        // rebinding/redrawing every visible row on every filter/tab/search change
        adapter.setPlayingIdQuiet(playingId);
        adapter.submitList(displayed);
        if (tvEmpty != null)
            tvEmpty.setVisibility(displayed.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void previewAudio(Audio audio) {
        if (audio.id.equals(playingId)) { stopPreview(); return; }
        stopPreview();
        if (audio.previewAudioUrl == null || audio.previewAudioUrl.isEmpty()) {
            Toast.makeText(this, "Preview not available for this sound", Toast.LENGTH_SHORT).show(); return;
        }
        playingId = audio.id;
        // ⚡ PERF: only the previous row and the newly-playing row need a redraw
        adapter.setPlayingId(playingId);
        try {
            final MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            // 🛡️ RACE FIX: guard every callback with "is this still the current
            // player?" — if a fast tab switch/row tap already replaced
            // `mediaPlayer` with a newer instance (or nulled it via
            // stopPreview()) by the time this async callback fires, a stale
            // callback on the OLD instance can no longer touch playback state.
            // Listeners are attached before prepareAsync() so none can fire
            // before the guard is in place.
            mp.setOnPreparedListener(p -> {
                if (mediaPlayer != mp) return;
                p.start();
                // 🎧 kick off the inline progress/timer ticker now that we
                // actually know the real preview duration
                int total = p.getDuration();
                adapter.updateProgress(0, total > 0 ? total : 30000);
                handler.removeCallbacks(progressTicker);
                handler.post(progressTicker);
            });
            mp.setOnCompletionListener(p -> { if (mediaPlayer == mp) stopPreview(); });
            mp.setOnErrorListener((p, what, extra) -> { if (mediaPlayer == mp) stopPreview(); return true; });
            mp.setDataSource(audio.previewAudioUrl);
            mp.prepareAsync();
        } catch (Exception e) { stopPreview(); }
    }

    /** 🎧 Polls MediaPlayer's playback position every ~200ms while a preview
     *  is active and pushes it to the adapter's row-scoped progress bar/timer.
     *  Self-terminates as soon as the player is gone/stopped/released. */
    private final Runnable progressTicker = this::tickPreviewProgress;
    private static final long PROGRESS_TICK_MS = 200L;

    private void tickPreviewProgress() {
        MediaPlayer mp = mediaPlayer;
        if (mp == null || playingId == null) return;
        try {
            if (mp.isPlaying()) {
                int cur = mp.getCurrentPosition();
                int total = mp.getDuration();
                adapter.updateProgress(cur, total > 0 ? total : 30000);
            }
        } catch (Exception ignored) {
            return; // player was released mid-tick — just stop quietly
        }
        handler.postDelayed(progressTicker, PROGRESS_TICK_MS);
    }

    private void stopPreview() {
        playingId = null;
        adapter.setPlayingId(null);
        handler.removeCallbacks(progressTicker);
        MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            // 🛡️ LEAK FIX: stop() and release() are now in separate try/catch
            // blocks. Previously both calls shared one try block — stop()
            // throws IllegalStateException if the player is still mid-async
            // prepare (not yet Started/Paused/Stopped), which aborted the
            // block BEFORE release() ran, leaking the native MediaPlayer.
            // release() is always safe to call regardless of player state,
            // so it must not be skippable by an exception from stop().
            try { mp.setOnPreparedListener(null); mp.setOnCompletionListener(null); mp.setOnErrorListener(null); } catch (Exception ignored) {}
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
    }

    private void saveToggle(Audio audio) {
        if (myUid == null || audio.id == null) return;
        final String id = audio.id;
        DatabaseReference ref = FirebaseUtils.getUserRef(myUid).child("saved_sounds").child(id);
        if (savedIds.contains(id)) {
            savedIds.remove(id);
            // 🛡️ write listener guarded by isAlive() — if this activity is
            // gone by the time Firebase replies, we just skip the UI update
            // instead of touching a destroyed activity's views/adapter
            ref.removeValue().addOnCompleteListener(task -> {
                if (!isAlive()) return;
                if (!task.isSuccessful()) {
                    // roll back the optimistic removal — the write didn't happen
                    savedIds.add(id);
                    adapter.setSavedState(id, true);
                    Toast.makeText(this, "Couldn't remove — try again", Toast.LENGTH_SHORT).show();
                }
            });
            Toast.makeText(this, "Removed from saved", Toast.LENGTH_SHORT).show();
        } else {
            savedIds.add(id);
            Map<String, Object> m = new HashMap<>();
            m.put("title",    audio.title);
            m.put("artist",   audio.artist != null ? audio.artist : "");
            m.put("audioUrl", audio.audioUrl != null ? audio.audioUrl : "");
            m.put("coverUrl", audio.coverUrl != null ? audio.coverUrl : "");
            ref.setValue(m).addOnCompleteListener(task -> {
                if (!isAlive()) return;
                if (!task.isSuccessful()) {
                    // roll back the optimistic save — the write didn't happen
                    savedIds.remove(id);
                    adapter.setSavedState(id, false);
                    Toast.makeText(this, "Couldn't save — try again", Toast.LENGTH_SHORT).show();
                }
            });
            Toast.makeText(this, "Sound saved", Toast.LENGTH_SHORT).show();
        }
        // ⚡ PERF: only the toggled row needs a redraw, not the entire list
        adapter.setSavedState(id, savedIds.contains(id));
    }

    private void useAudio(Audio audio) {
        stopPreview();
        Intent result = new Intent();
        result.putExtra(RESULT_AUDIO_ID,     audio.id    != null ? audio.id    : "");
        result.putExtra(RESULT_AUDIO_TITLE,  audio.title != null ? audio.title : "");
        result.putExtra(RESULT_AUDIO_ARTIST, audio.artist!= null ? audio.artist: "");
        result.putExtra(RESULT_AUDIO_URL,    audio.audioUrl != null ? audio.audioUrl : "");
        result.putExtra(RESULT_PREVIEW_AUDIO_URL, audio.previewAudioUrl != null ? audio.previewAudioUrl : "");
        result.putExtra(RESULT_COVER_URL,    audio.coverUrl != null ? audio.coverUrl : "");
        setResult(RESULT_OK, result);
        finish();
    }

    // ✅ NEW: Opens SoundDetailActivity for the tapped audio row
    private void openSoundDetail(Audio audio) {
        if (audio == null) return;
        Intent i = new Intent(this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    audio.id    != null ? audio.id    : "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, audio.title != null ? audio.title : "");
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      audio.artist!= null ? audio.artist: "");
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   audio.audioUrl != null ? audio.audioUrl : "");
        i.putExtra(SoundDetailActivity.EXTRA_COVER_URL,   audio.coverUrl != null ? audio.coverUrl : "");
        i.putExtra(SoundDetailActivity.EXTRA_DURATION_MS, audio.durationMs);
        i.putExtra(SoundDetailActivity.EXTRA_GENRE,       audio.genre != null ? audio.genre : "");
        startActivityForResult(i, REQ_SOUND_DETAIL);
    }

    // ✅ NEW: Handle "Use This Sound" coming back from SoundDetailActivity
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SOUND_DETAIL && resultCode == RESULT_OK && data != null) {
            // User tapped "Use This Sound" inside SoundDetail → propagate result up to caller
            setResult(RESULT_OK, data);
            finish();
        }
    }

    @Override protected void onDestroy() {
        stopPreview();
        cancelServerSearch();
        handler.removeCallbacksAndMessages(null);
        dbExecutor.shutdown(); // 📴 OFFLINE CACHE: avoid leaking this activity's background thread
        super.onDestroy();
    }

    /** 🛡️ Firebase's addListenerForSingleValueEvent has no removeListener
     *  equivalent — the callback WILL fire even if the activity is destroyed
     *  mid-load. Every callback that touches views/adapter must check this
     *  first so a late callback is a safe no-op instead of a crash/leak. */
    private boolean isAlive() { return !isFinishing() && !isDestroyed(); }

    static class Audio {
        String id, title, artist, audioUrl, coverUrl, genre, mood, previewAudioUrl;
        long usageCount, durationMs, trendingRank, addedAt;
        int bpm;

        // ⚡ PERF: lowercase copies computed once at load time, so search
        // filtering never calls toLowerCase() per keystroke per track
        String searchTitle = "", searchArtist = "", searchGenre = "", searchMood = "";

        void buildSearchCache() {
            searchTitle  = title  != null ? title.toLowerCase(Locale.US)  : "";
            searchArtist = artist != null ? artist.toLowerCase(Locale.US) : "";
            searchGenre  = genre  != null ? genre.toLowerCase(Locale.US)  : "";
            searchMood   = mood   != null ? mood.toLowerCase(Locale.US)   : "";
        }
    }

    interface AudioAction { void run(Audio a); }

    // ⚡ PERF: adapter now owns its own list + DiffUtil pipeline instead of
    // sharing a mutable reference with the activity and doing full rebinds.
    // 🛡️ Also owns an in-list pagination footer row (Instagram-style): a
    // small spinner while a load-more page is in flight, replaced in place
    // by a "Couldn't load more · Retry" row if that page fails — instead of
    // a one-shot Toast that leaves no way to retry short of scrolling away
    // and back.
    static class AudioAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final Object PAYLOAD_PLAYING  = "payload_playing";
        private static final Object PAYLOAD_SAVED    = "payload_saved";
        private static final Object PAYLOAD_PROGRESS = "payload_progress";

        private static final int VIEW_TYPE_ITEM   = 0;
        private static final int VIEW_TYPE_FOOTER = 1;

        static final int FOOTER_NONE    = 0;
        static final int FOOTER_LOADING = 1;
        static final int FOOTER_ERROR   = 2;

        private final List<Audio> items = new ArrayList<>();
        private final AudioAction onPreview, onSave, onUse, onDetail;
        private Set<String> savedIds = new HashSet<>();
        private String playingId;
        // 🎧 Current preview playback position, pushed by tickPreviewProgress()
        private int progressCurrentMs = 0;
        private int progressTotalMs   = 0;

        private int footerState = FOOTER_NONE;
        private Runnable onRetryLoadMore;

        // ✅ NEW: 4th param onDetail — fires when user taps the row (not a button)
        AudioAdapter(AudioAction p, AudioAction s, AudioAction u, AudioAction d) {
            onPreview = p; onSave = s; onUse = u; onDetail = d;
            setHasStableIds(true);
        }

        void setOnRetryLoadMore(Runnable r) { onRetryLoadMore = r; }

        /** 🛡️ Switches the footer row between hidden / loading-spinner /
         *  retry-on-failure — called from maybeLoadMore()'s in-flight,
         *  success and failure paths. */
        void setFooterState(int state) {
            if (footerState == state) {
                if (state != FOOTER_NONE) notifyItemChanged(items.size());
                return;
            }
            boolean hadFooter = footerState != FOOTER_NONE;
            boolean hasFooter = state != FOOTER_NONE;
            footerState = state;
            if (hadFooter && hasFooter) {
                notifyItemChanged(items.size());
            } else if (hasFooter) {
                notifyItemInserted(items.size());
            } else {
                notifyItemRemoved(items.size());
            }
        }

        /** Full bulk sync (e.g. initial saved-ids load) — cheap, happens once. */
        void setSavedIds(Set<String> ids) { savedIds = new HashSet<>(ids); notifyDataSetChanged(); }

        /** Updates playingId without triggering any bind — caller (filterDisplayed)
         *  is about to submitList anyway, so no extra work is needed here. */
        void setPlayingIdQuiet(String id) { playingId = id; }

        /** ⚡ Toggles the preview icon on just the old + new playing rows. */
        void setPlayingId(String id) {
            String old = playingId;
            playingId = id;
            progressCurrentMs = 0;
            progressTotalMs   = 0;
            notifyRowChangedForId(old, PAYLOAD_PLAYING);
            notifyRowChangedForId(id, PAYLOAD_PLAYING);
        }

        /** 🎧 Pushes a fresh playback position for the currently-playing row.
         *  Uses its own lightweight payload so only the thin progress bar +
         *  timer text repaint — no icon flicker, no Glide re-run. */
        void updateProgress(int currentMs, int totalMs) {
            progressCurrentMs = currentMs;
            progressTotalMs   = totalMs;
            notifyRowChangedForId(playingId, PAYLOAD_PROGRESS);
        }

        /** ⚡ Toggles the bookmark icon on just the affected row. */
        void setSavedState(String id, boolean saved) {
            if (saved) savedIds.add(id); else savedIds.remove(id);
            notifyRowChangedForId(id, PAYLOAD_SAVED);
        }

        private void notifyRowChangedForId(String id, Object payload) {
            if (id == null) return;
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(items.get(i).id)) { notifyItemChanged(i, payload); return; }
            }
        }

        /** ⚡ PERF: diffs against the current list and dispatches only the
         *  inserts/removes/moves/changes actually needed, instead of a full
         *  notifyDataSetChanged() that redraws every visible row (and re-fires
         *  every Glide load) on every tab switch, genre chip, or keystroke. */
        void submitList(List<Audio> newList) {
            List<Audio> oldList = new ArrayList<>(items);
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return oldList.size(); }
                @Override public int getNewListSize() { return newList.size(); }
                @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                    String a = oldList.get(oldPos).id, b = newList.get(newPos).id;
                    return a != null && a.equals(b);
                }
                @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                    Audio x = oldList.get(oldPos), y = newList.get(newPos);
                    return Objects.equals(x.title, y.title)
                        && Objects.equals(x.artist, y.artist)
                        && Objects.equals(x.coverUrl, y.coverUrl)
                        && Objects.equals(x.previewAudioUrl, y.previewAudioUrl)
                        && x.usageCount == y.usageCount
                        && x.bpm == y.bpm
                        && x.trendingRank == y.trendingRank;
                }
            });
            items.clear();
            items.addAll(newList);
            result.dispatchUpdatesTo(this);
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            if (vt == VIEW_TYPE_FOOTER) {
                FooterVH fh = new FooterVH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_trending_audio_footer, p, false));
                fh.layoutError.setOnClickListener(v -> { if (onRetryLoadMore != null) onRetryLoadMore.run(); });
                return fh;
            }
            VH h = new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_trending_audio, p, false));
            // ⚡ PERF: listeners attached once per ViewHolder instead of a fresh
            // lambda allocation on every single bind/scroll/rebind
            h.btnPreview.setOnClickListener(v -> { if (h.current != null) onPreview.run(h.current); });
            h.btnSave.setOnClickListener(v -> { if (h.current != null) onSave.run(h.current); });
            h.btnUse.setOnClickListener(v -> { if (h.current != null) onUse.run(h.current); });
            h.itemView.setOnClickListener(v -> { if (h.current != null && onDetail != null) onDetail.run(h.current); });
            return h;
        }

        @Override
        public int getItemViewType(int pos) {
            return pos >= items.size() ? VIEW_TYPE_FOOTER : VIEW_TYPE_ITEM;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            if (vh instanceof FooterVH) {
                FooterVH fh = (FooterVH) vh;
                fh.layoutLoading.setVisibility(footerState == FOOTER_LOADING ? View.VISIBLE : View.GONE);
                fh.layoutError.setVisibility(footerState == FOOTER_ERROR ? View.VISIBLE : View.GONE);
                return;
            }
            VH h = (VH) vh;
            Audio a = items.get(pos);
            h.current = a;
            h.tvTitle.setText(a.title != null ? a.title : "Unknown");
            h.tvArtist.setText(a.artist != null ? a.artist : "Unknown Artist");
            h.tvUsage.setText(fmtCount(a.usageCount) + " reels");

            if (h.tvBpm != null) {
                if (a.bpm > 0) {
                    h.tvBpm.setVisibility(View.VISIBLE);
                    h.tvBpm.setText(a.bpm + " BPM");
                } else {
                    h.tvBpm.setVisibility(View.GONE);
                }
            }

            if (h.tvTrendingBadge != null) {
                if (a.trendingRank > 0 && a.trendingRank <= 50) {
                    h.tvTrendingBadge.setVisibility(View.VISIBLE);
                    h.tvTrendingBadge.setText("#" + a.trendingRank);
                } else {
                    h.tvTrendingBadge.setVisibility(View.GONE);
                }
            }

            if (h.ivCover != null) {
                if (a.coverUrl != null && !a.coverUrl.isEmpty()) {
                    int radiusPx = (int) (8 * h.itemView.getResources().getDisplayMetrics().density);
                    com.bumptech.glide.Glide.with(h.ivCover)
                        .load(a.coverUrl)
                        .placeholder(R.drawable.ic_music_note)
                        // ⚡ PERF: cache the already-transformed bitmap, so scrolling
                        // back to a previously-seen row skips re-decoding + re-cropping
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE)
                        .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                                   new com.bumptech.glide.load.resource.bitmap.RoundedCorners(radiusPx))
                        .into(h.ivCover);
                } else {
                    h.ivCover.setImageResource(R.drawable.ic_music_note);
                }
            }

            bindPlaying(h, a);
            bindSaved(h, a);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos, @NonNull List<Object> payloads) {
            if (payloads.isEmpty() || !(vh instanceof VH)) { super.onBindViewHolder(vh, pos, payloads); return; }
            // ⚡ PERF: partial rebind — only touches the one icon that changed,
            // skips re-running Glide, re-measuring text, etc. for the row
            VH h = (VH) vh;
            Audio a = items.get(pos);
            h.current = a;
            for (Object payload : payloads) {
                if (payload == PAYLOAD_PLAYING) bindPlaying(h, a);
                else if (payload == PAYLOAD_SAVED) bindSaved(h, a);
                else if (payload == PAYLOAD_PROGRESS) bindProgress(h, a);
            }
        }

        private void bindPlaying(VH h, Audio a) {
            boolean playing = a.id != null && a.id.equals(playingId);
            h.btnPreview.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            boolean hasPreview = a.previewAudioUrl != null && !a.previewAudioUrl.isEmpty();
            h.btnPreview.setAlpha(hasPreview ? 1f : 0.4f);
            bindProgress(h, a);
        }

        /** 🎧 Shows/hides the thin progress bar + "0:07 / 0:30" timer — only
         *  ever visible on whichever row is currently playing. */
        private void bindProgress(VH h, Audio a) {
            boolean playing = a.id != null && a.id.equals(playingId);
            if (h.layoutProgress == null) return;
            if (!playing) { h.layoutProgress.setVisibility(View.GONE); return; }
            h.layoutProgress.setVisibility(View.VISIBLE);
            int total = progressTotalMs > 0 ? progressTotalMs : 30000;
            int cur = Math.max(0, Math.min(progressCurrentMs, total));
            if (h.progressPreview != null) {
                h.progressPreview.setMax(1000);
                h.progressPreview.setProgress((int) (1000L * cur / total));
            }
            if (h.tvTimer != null) h.tvTimer.setText(fmtTime(cur) + " / " + fmtTime(total));
        }

        static String fmtTime(int ms) {
            int totalSec = Math.max(0, ms) / 1000;
            return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
        }

        private void bindSaved(VH h, Audio a) {
            boolean saved = a.id != null && savedIds.contains(a.id);
            h.btnSave.setImageResource(saved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        }

        @Override public int getItemCount() { return items.size() + (footerState != FOOTER_NONE ? 1 : 0); }

        // ⚡ PERF: stable ids let RecyclerView track rows across DiffUtil moves
        // more cheaply and avoid unnecessary rebinds on reorder
        @Override public long getItemId(int position) {
            if (position >= items.size()) return RecyclerView.NO_ID;
            String id = items.get(position).id;
            return id != null ? id.hashCode() : RecyclerView.NO_ID;
        }

        static String fmtCount(long n) {
            if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n/1_000_000.0);
            if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n/1_000.0);
            return String.valueOf(n);
        }

        static class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivCover;
            TextView tvTitle, tvArtist, tvUsage, tvBpm, tvTrendingBadge, tvTimer;
            ImageButton btnPreview, btnSave;
            Button btnUse;
            View layoutProgress;
            ProgressBar progressPreview;
            Audio current;
            VH(View v) {
                super(v);
                ivCover         = v.findViewById(R.id.iv_audio_cover);
                tvTitle         = v.findViewById(R.id.tv_audio_title);
                tvArtist        = v.findViewById(R.id.tv_audio_artist);
                tvUsage         = v.findViewById(R.id.tv_audio_usage);
                tvBpm           = v.findViewById(R.id.tv_audio_bpm);
                tvTrendingBadge = v.findViewById(R.id.tv_audio_trending_badge);
                btnPreview      = v.findViewById(R.id.btn_audio_preview);
                btnSave         = v.findViewById(R.id.btn_audio_save);
                btnUse          = v.findViewById(R.id.btn_audio_use);
                layoutProgress  = v.findViewById(R.id.layout_audio_progress);
                progressPreview = v.findViewById(R.id.progress_audio_preview);
                tvTimer         = v.findViewById(R.id.tv_audio_timer);
            }
        }

        /** 🛡️ Pagination footer row — see item_trending_audio_footer.xml.
         *  layoutError itself is the tap target, so the whole "Couldn't
         *  load more · Retry" row (not just the word "Retry") retries. */
        static class FooterVH extends RecyclerView.ViewHolder {
            View layoutLoading, layoutError;
            FooterVH(View v) {
                super(v);
                layoutLoading = v.findViewById(R.id.layout_footer_loading);
                layoutError   = v.findViewById(R.id.layout_footer_error);
            }
        }
    }
}
