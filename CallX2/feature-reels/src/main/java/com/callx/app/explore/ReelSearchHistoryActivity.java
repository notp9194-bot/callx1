package com.callx.app.explore;

import com.callx.app.profile.UserReelsActivity;
import com.callx.app.followers.FollowAvatarBinder;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ReelSearchHistoryActivity — Instagram-style "tap the search box" screen.
 *
 * Launched when the user taps the search field at the top of
 * {@link ReelSearchActivity} (the Explore grid opened from the Reels bottom
 * nav's Search tab — see ReelsFragment#reel_nav_search). ReelSearchActivity
 * itself keeps doing reel caption/hashtag search + the trending grid; THIS
 * screen is a separate, dedicated "search for a person" experience:
 *
 *  ✅ "Recent" search history (SharedPreferences-backed, cross-session,
 *     mirrors the pattern already used by YouTubeSearchActivity's history)
 *     — each entry is either a specific account (avatar + name, tap → opens
 *     that user's profile) or a plain typed query (clock icon, tap → re-runs
 *     that search)
 *  ✅ "See all" expands the (initially capped) recent list
 *  ✅ Per-row ✕ removes just that one history entry
 *  ✅ Tapping the search box's EditText here (unlike on ReelSearchActivity)
 *     opens the keyboard normally — typing live-searches Firebase `users/`
 *     by contact name (`nameLower` index) and by `username` (debounced
 *     300ms), Instagram-style
 *  ✅ Tapping a result account saves it to history and opens
 *     UserReelsActivity, exactly like a row tap in FollowConnectionsActivity
 *
 * ★ NEW (Instagram parity pass):
 *  ✅ Inline Follow/Following button on every account row (history, live
 *     results, and Suggested) — same filled/outline pill styling and
 *     users/{uid}/reelFollows + reelFollowers write pattern
 *     FollowConnectionsActivity#toggleFollowFromBtn() already uses.
 *  ✅ "Suggested" section (popular-accounts-not-yet-followed, same source
 *     query as DiscoverPeopleActivity#loadCandidates()) shown under Recent
 *     whenever there's room for it, so an empty-history first-run screen
 *     isn't just a blank "No recent searches" message.
 *  ✅ Matched substring is bold in every live-results row's name/username —
 *     tv_name/tv_username get a StyleSpan(BOLD) over whatever part of the
 *     text matches the current query.
 *  ✅ Category tabs (Top / Accounts / Tags / Places) once a query is typed:
 *     Top mixes a few matching accounts with a few matching hashtags;
 *     Accounts is the existing nameLower/username/name search; Tags queries
 *     reels/ hashtags client-side (same source HashtagReelsActivity/
 *     ReelSearchActivity's trending chips already read) and opens
 *     HashtagReelsActivity on tap; Places is honestly empty — this app has
 *     no location field on ReelModel yet, so it says so instead of faking
 *     results.
 *  ✅ Real "username" field: search already queried users/{uid}/username,
 *     but nothing ever wrote it (ProfileSetupActivity/ProfileActivity only set
 *     callxId), so that query always fell through to the nameLower/name
 *     fallbacks. ProfileSetupActivity and ProfileActivity's save() now also
 *     write username = callxId.toLowerCase() so this index is populated —
 *     see those files' updates.put("username", ...).
 *
 * Avatar loading reuses {@link FollowAvatarBinder} as-is — the SAME
 * optimized pipeline (density-aware SMALL tier sizing, L2/L3 bitmap reuse
 * across TRIM_MEMORY_MODERATE, analytics wiring, velocity-based prefetch,
 * lifecycle-aware cancel-on-recycle) already powering
 * FollowConnectionsActivity/DiscoverPeopleActivity's rows — nothing
 * reimplemented here.
 */
public class ReelSearchHistoryActivity extends AppCompatActivity {

    private static final String PREFS_NAME    = "reel_search_history_prefs";
    private static final String KEY_HISTORY   = "history_json";
    private static final int    MAX_HISTORY   = 30;
    private static final int    COLLAPSED_COUNT = 8;
    private static final int    SEARCH_LIMIT  = 25;
    private static final long   DEBOUNCE_MS   = 300;
    private static final int    SUGGESTED_LIMIT = 10;
    private static final int    TAG_LIMIT     = 20;

    // ★ NEW: category tabs, shown once a query is typed.
    private static final int TAB_TOP      = 0;
    private static final int TAB_ACCOUNTS = 1;
    private static final int TAB_TAGS     = 2;
    private static final int TAB_PLACES   = 3;

    private EditText     etSearch;
    private ImageButton  btnBack, btnClear;
    private View         layoutHistory, layoutResults;
    private View         layoutHistoryEmpty, layoutResultsEmpty;
    private TextView     tvSeeAll, tvResultsEmpty;
    private RecyclerView rvHistory, rvResults;
    private ProgressBar  progressBar;
    private TabLayout    tabLayout;
    private View         tabDivider;
    private View         layoutSuggested;
    private RecyclerView rvSuggested;

    private SharedPreferences prefs;
    private final List<Row> historyRows = new ArrayList<>();
    private final List<Row> resultRows  = new ArrayList<>();
    private final List<Row> suggestedRows = new ArrayList<>();
    private boolean historyExpanded = false;

    private RowAdapter historyAdapter;
    private RowAdapter resultsAdapter;
    private RowAdapter suggestedAdapter;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    // ★ NEW: follow state, shared by every adapter so "Follow"/"Following"
    // stays in sync across the history, results, and suggested lists —
    // same myFollowing Set<String> pattern FollowConnectionsActivity uses.
    private final Set<String> myFollowing = new HashSet<>();

    // ★ NEW: last-run account search results + tag search results, kept
    // separately so switching tabs re-renders from cache instead of
    // re-querying Firebase every tap.
    private final List<Row> accountResultRows = new ArrayList<>();
    private final List<Row> tagResultRows     = new ArrayList<>();
    private int   currentTab   = TAB_TOP;
    private String currentQuery = "";

    // ★ NEW: reels/ fetched once (lazily, on first Tags/Top use) and cached
    // — same "load all reels client-side" approach ReelSearchActivity uses
    // for its own hashtag search, just scoped to this screen's lifetime.
    private final List<ReelModel> allReelsForTags = new ArrayList<>();
    private boolean reelsForTagsLoaded  = false;
    private boolean reelsForTagsLoading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_search_history);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        etSearch           = findViewById(R.id.et_search);
        btnBack            = findViewById(R.id.btn_back);
        btnClear           = findViewById(R.id.btn_clear);
        layoutHistory      = findViewById(R.id.layout_history);
        layoutResults      = findViewById(R.id.layout_results);
        layoutHistoryEmpty = findViewById(R.id.layout_history_empty);
        layoutResultsEmpty = findViewById(R.id.layout_results_empty);
        tvSeeAll           = findViewById(R.id.tv_see_all);
        tvResultsEmpty     = findViewById(R.id.tv_results_empty);
        rvHistory          = findViewById(R.id.rv_history);
        rvResults          = findViewById(R.id.rv_results);
        progressBar        = findViewById(R.id.progress_bar);
        tabLayout          = findViewById(R.id.tab_layout);
        tabDivider         = findViewById(R.id.tab_divider);
        layoutSuggested    = findViewById(R.id.layout_suggested);
        rvSuggested        = findViewById(R.id.rv_suggested);

        btnBack.setOnClickListener(v -> finish());
        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            showHistoryMode();
        });

        historyAdapter = new RowAdapter(historyRows, /*showRemove=*/true);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(historyAdapter);
        attachPrefetch(rvHistory, historyRows);

        resultsAdapter = new RowAdapter(resultRows, /*showRemove=*/false);
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(resultsAdapter);
        attachPrefetch(rvResults, resultRows);

        suggestedAdapter = new RowAdapter(suggestedRows, /*showRemove=*/false);
        rvSuggested.setLayoutManager(new LinearLayoutManager(this));
        rvSuggested.setAdapter(suggestedAdapter);
        attachPrefetch(rvSuggested, suggestedRows);

        tvSeeAll.setOnClickListener(v -> {
            historyExpanded = !historyExpanded;
            renderHistory();
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                renderTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                btnClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                String query = s.toString().trim();
                if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
                if (query.isEmpty()) {
                    showHistoryMode();
                    return;
                }
                showResultsMode();
                debounceRunnable = () -> runSearch(query);
                debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = etSearch.getText().toString().trim();
                if (!q.isEmpty()) {
                    saveTextHistory(q);
                    runSearch(q);
                }
                hideKeyboard();
                return true;
            }
            return false;
        });

        loadHistory();
        renderHistory();
        loadMyFollowing();
        loadSuggested();
        showHistoryMode();

        etSearch.requestFocus();
        showKeyboard();
    }

    // ── Mode switching ───────────────────────────────────────────────────

    private void showHistoryMode() {
        layoutHistory.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);
        tabLayout.setVisibility(View.GONE);
        tabDivider.setVisibility(View.GONE);
    }

    private void showResultsMode() {
        layoutHistory.setVisibility(View.GONE);
        layoutResults.setVisibility(View.VISIBLE);
        layoutResultsEmpty.setVisibility(View.GONE);
        rvResults.setVisibility(View.VISIBLE);
        tabLayout.setVisibility(View.VISIBLE);
        tabDivider.setVisibility(View.VISIBLE);
    }

    // ── Entry point: one query kicks off account search + tag search ────

    private void runSearch(String query) {
        currentQuery = query.toLowerCase(Locale.getDefault()).trim();
        if (currentQuery.isEmpty()) return;
        searchUsers(currentQuery);
        searchTags(currentQuery);
    }

    // ── Firebase user search (by contact name / username) ───────────────

    private void searchUsers(String rawQuery) {
        String query = rawQuery.toLowerCase(Locale.getDefault()).trim();
        if (query.isEmpty()) return;

        progressBar.setVisibility(View.VISIBLE);
        final String myUid = safeMyUid();
        final DatabaseReference usersRef = FirebaseUtils.db().getReference("users");
        final String endRange = query.substring(0, query.length() - 1)
            + (char) (query.charAt(query.length() - 1) + 1);

        final List<Row> merged = new ArrayList<>();
        final Set<String> seenUids = new LinkedHashSet<>();

        // 1) nameLower prefix — this app's main "who is this person" index
        //    (every profile writes it on setup/edit — see ProfileSetupActivity
        //    / ProfileActivity), functionally the "contact" search.
        usersRef.orderByChild("nameLower").startAt(query).endAt(endRange + "\uf8ff")
            .limitToFirst(SEARCH_LIMIT)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap1) {
                    collectUsers(snap1, myUid, seenUids, merged);

                    // 2) username prefix — real handle field. ProfileSetupActivity
                    //    and ProfileActivity now write username = callxId
                    //    lowercased on save, so this index is finally populated
                    //    (it used to be queried here but nothing ever wrote it).
                    usersRef.orderByChild("username").startAt(query).endAt(endRange + "\uf8ff")
                        .limitToFirst(SEARCH_LIMIT)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot snap2) {
                                collectUsers(snap2, myUid, seenUids, merged);

                                if (!merged.isEmpty()) {
                                    finishSearch(query, merged);
                                    return;
                                }
                                // 3) fallback: raw "name" field, capitalized —
                                //    covers users saved before nameLower existed.
                                String cap = Character.toUpperCase(query.charAt(0)) + query.substring(1);
                                String capEnd = cap.substring(0, cap.length() - 1)
                                    + (char) (cap.charAt(cap.length() - 1) + 1);
                                usersRef.orderByChild("name").startAt(cap).endAt(capEnd + "\uf8ff")
                                    .limitToFirst(SEARCH_LIMIT)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot snap3) {
                                            collectUsers(snap3, myUid, seenUids, merged);
                                            finishSearch(query, merged);
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {
                                            finishSearch(query, merged);
                                        }
                                    });
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                finishSearch(query, merged);
                            }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    finishSearch(query, merged);
                }
            });
    }

    private void collectUsers(DataSnapshot snap, String myUid, Set<String> seenUids, List<Row> out) {
        for (DataSnapshot c : snap.getChildren()) {
            String uid = c.getKey();
            if (uid == null || uid.equals(myUid) || seenUids.contains(uid)) continue;
            seenUids.add(uid);
            out.add(rowFromSnapshot(uid, c));
        }
    }

    private Row rowFromSnapshot(String uid, DataSnapshot c) {
        String name    = c.child("name").getValue(String.class);
        String bio     = c.child("bio").getValue(String.class);
        String thumb   = c.child("thumbUrl").getValue(String.class);
        String photo   = c.child("photoUrl").getValue(String.class);
        String callxId = c.child("callxId").getValue(String.class);
        Long   ver     = c.child("avatarVersion").getValue(Long.class);
        String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
        return Row.user(uid, name != null ? name : uid, bio, p != null ? p : "",
            ver != null ? ver : 0L, callxId);
    }

    private void finishSearch(String query, List<Row> merged) {
        // Only apply if the box still has this same query (a fast typist may
        // have already moved on to a longer/shorter query by the time this
        // chained callback resolves).
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (!currentQuery.equals(query)) return;
            accountResultRows.clear();
            accountResultRows.addAll(merged);
            // Batch-warm the verified-badge cache for this results page before
            // rows bind — same pattern ReelCommentsAdapter.setComments() uses.
            List<String> verifyUids = new ArrayList<>(merged.size());
            for (Row r : merged) if (r.uid != null) verifyUids.add(r.uid);
            com.callx.app.utils.VerifiedBadgeUtils.prefetch(verifyUids);
            renderTab();
        });
    }

    // ── Tag search (reels/ hashtags, client-side — same source
    //    ReelSearchActivity/HashtagReelsActivity already read) ───────────

    private void searchTags(String query) {
        ensureReelsForTagsLoaded(() -> {
            if (!currentQuery.equals(query)) return; // superseded by a newer query
            String tag = query.startsWith("#") ? query.substring(1) : query;
            Map<String, Integer> freq = new LinkedHashMap<>();
            for (ReelModel reel : allReelsForTags) {
                if (reel.hashtags == null) continue;
                for (String t : reel.hashtags) {
                    if (t != null && t.toLowerCase(Locale.getDefault()).contains(tag)) {
                        freq.merge(t, 1, Integer::sum);
                    }
                }
            }
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
            Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
            tagResultRows.clear();
            for (Map.Entry<String, Integer> e : entries) {
                tagResultRows.add(Row.tag(e.getKey(), e.getValue()));
                if (tagResultRows.size() >= TAG_LIMIT) break;
            }
            runOnUiThread(this::renderTab);
        });
    }

    /** Lazily fetches reels/ once and caches it for the lifetime of this
     *  screen — subsequent tag searches filter the cache instead of
     *  re-querying Firebase on every keystroke. */
    private void ensureReelsForTagsLoaded(Runnable onReady) {
        if (reelsForTagsLoaded) { onReady.run(); return; }
        if (reelsForTagsLoading) return; // a load is already in flight; its
                                          // callback below will pick this up
        reelsForTagsLoading = true;
        FirebaseUtils.getReelsRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                allReelsForTags.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel reel = s.getValue(ReelModel.class);
                    if (reel == null) continue;
                    if (reel.reelId == null) reel.reelId = s.getKey();
                    allReelsForTags.add(reel);
                }
                reelsForTagsLoaded  = true;
                reelsForTagsLoading = false;
                onReady.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                reelsForTagsLoading = false;
                // leave reelsForTagsLoaded = false so the next call retries
            }
        });
    }

    /** Rebuilds resultRows from the currently-selected tab's cached data and
     *  re-submits to resultsAdapter. Safe to call as soon as either the
     *  account or tag half of a search finishes — each half calls this. */
    private void renderTab() {
        resultRows.clear();
        String emptyMessage;
        switch (currentTab) {
            case TAB_ACCOUNTS:
                resultRows.addAll(accountResultRows);
                emptyMessage = "No account found";
                break;
            case TAB_TAGS:
                resultRows.addAll(tagResultRows);
                emptyMessage = "No tags found";
                break;
            case TAB_PLACES:
                // Honest empty state: ReelModel has no location field yet,
                // so there is nothing real to show here rather than faking
                // place results.
                emptyMessage = "Place search isn't available yet";
                break;
            case TAB_TOP:
            default:
                // Instagram's "Top" mixes both — lead with a few matching
                // tags, then matching accounts.
                int tagSlice = Math.min(3, tagResultRows.size());
                resultRows.addAll(tagResultRows.subList(0, tagSlice));
                resultRows.addAll(accountResultRows);
                emptyMessage = "No results found";
                break;
        }
        tvResultsEmpty.setText(emptyMessage);
        resultsAdapter.setHighlightQuery(currentQuery);
        resultsAdapter.submit(resultRows);
        layoutResultsEmpty.setVisibility(resultRows.isEmpty() ? View.VISIBLE : View.GONE);
        rvResults.setVisibility(resultRows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Follow state ──────────────────────────────────────────────────────

    private void loadMyFollowing() {
        String myUid = safeMyUid();
        if (myUid == null) return;
        FirebaseUtils.getReelFollowsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren())
                        if (s.getKey() != null) myFollowing.add(s.getKey());
                    // Refresh whatever's already on screen now that follow
                    // state is known (rows bound before this resolved would
                    // otherwise default to "Follow" even if already followed).
                    historyAdapter.notifyDataSetChanged();
                    resultsAdapter.notifyDataSetChanged();
                    suggestedAdapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /** Same follow/unfollow write pattern FollowConnectionsActivity#
     *  toggleFollowFromBtn() uses — mirrored reelFollows/reelFollowers edges. */
    private void toggleFollow(Row row) {
        String myUid = safeMyUid();
        if (myUid == null || row.uid == null) return;
        boolean currentlyFollowing = myFollowing.contains(row.uid);
        if (currentlyFollowing) {
            myFollowing.remove(row.uid);
            FirebaseUtils.getReelFollowsRef(myUid).child(row.uid).removeValue();
            FirebaseUtils.getReelFollowersRef(row.uid).child(myUid).removeValue();
        } else {
            myFollowing.add(row.uid);
            FirebaseUtils.getReelFollowsRef(myUid).child(row.uid).setValue(true);
            FirebaseUtils.getReelFollowersRef(row.uid).child(myUid).setValue(true);
        }
        historyAdapter.notifyDataSetChanged();
        resultsAdapter.notifyDataSetChanged();
        suggestedAdapter.notifyDataSetChanged();
    }

    // ── Suggested accounts (empty/first-run state) ───────────────────────

    /** Same "popular accounts I don't already follow" source
     *  DiscoverPeopleActivity#loadCandidates() uses (users ordered by
     *  reelCount), capped smaller since this is a supporting section here,
     *  not the whole screen. */
    private void loadSuggested() {
        String myUid = safeMyUid();
        if (myUid == null) return;
        FirebaseUtils.db().getReference("users")
            .orderByChild("reelCount")
            .limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<DataSnapshot> ordered = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) ordered.add(s);
                    Collections.reverse(ordered); // most popular first

                    suggestedRows.clear();
                    for (DataSnapshot s : ordered) {
                        String uid = s.getKey();
                        if (uid == null || uid.equals(myUid) || myFollowing.contains(uid)) continue;
                        suggestedRows.add(rowFromSnapshot(uid, s));
                        if (suggestedRows.size() >= SUGGESTED_LIMIT) break;
                    }
                    runOnUiThread(() -> {
                        suggestedAdapter.submit(suggestedRows);
                        layoutSuggested.setVisibility(suggestedRows.isEmpty() ? View.GONE : View.VISIBLE);
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── History persistence ──────────────────────────────────────────────

    private void loadHistory() {
        historyRows.clear();
        String json = prefs.getString(KEY_HISTORY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int type = o.optInt("type", Row.TYPE_TEXT);
                if (type == Row.TYPE_USER) {
                    historyRows.add(Row.user(o.optString("uid"), o.optString("name"),
                        o.optString("bio", null), o.optString("photo"), o.optLong("avatarVersion"),
                        o.optString("callxId", null)));
                } else {
                    historyRows.add(Row.text(o.optString("query")));
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            for (Row r : historyRows) {
                JSONObject o = new JSONObject();
                o.put("type", r.type);
                if (r.type == Row.TYPE_USER) {
                    o.put("uid", r.uid);
                    o.put("name", r.name);
                    if (r.bio != null) o.put("bio", r.bio);
                    o.put("photo", r.photo);
                    o.put("avatarVersion", r.avatarVersion);
                    if (r.callxId != null) o.put("callxId", r.callxId);
                } else {
                    o.put("query", r.query);
                }
                arr.put(o);
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void saveUserHistory(Row user) {
        for (int i = historyRows.size() - 1; i >= 0; i--) {
            Row r = historyRows.get(i);
            if (r.type == Row.TYPE_USER && r.uid != null && r.uid.equals(user.uid)) historyRows.remove(i);
        }
        historyRows.add(0, user);
        trimAndSave();
    }

    private void saveTextHistory(String query) {
        for (int i = historyRows.size() - 1; i >= 0; i--) {
            Row r = historyRows.get(i);
            if (r.type == Row.TYPE_TEXT && r.query != null && r.query.equalsIgnoreCase(query)) historyRows.remove(i);
        }
        historyRows.add(0, Row.text(query));
        trimAndSave();
    }

    private void trimAndSave() {
        while (historyRows.size() > MAX_HISTORY) historyRows.remove(historyRows.size() - 1);
        saveHistory();
        renderHistory();
    }

    private void renderHistory() {
        boolean canExpand = historyRows.size() > COLLAPSED_COUNT;
        tvSeeAll.setVisibility(canExpand ? View.VISIBLE : View.GONE);
        tvSeeAll.setText(historyExpanded ? "Show less" : "See all");

        // Batch-warm the verified-badge cache for the visible history rows —
        // same pattern as the live-search results page above.
        List<String> verifyUids = new ArrayList<>(historyRows.size());
        for (Row r : historyRows) if (r.type == Row.TYPE_USER && r.uid != null) verifyUids.add(r.uid);
        com.callx.app.utils.VerifiedBadgeUtils.prefetch(verifyUids);

        List<Row> visible = (historyExpanded || !canExpand)
            ? historyRows
            : historyRows.subList(0, COLLAPSED_COUNT);
        historyAdapter.submit(visible);

        layoutHistoryEmpty.setVisibility(historyRows.isEmpty() ? View.VISIBLE : View.GONE);
        rvHistory.setVisibility(historyRows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void removeHistoryEntry(Row row) {
        historyRows.remove(row);
        trimAndSave();
    }

    // ── Row tap handling ─────────────────────────────────────────────────

    private void onRowTapped(Row row) {
        if (row.type == Row.TYPE_USER) {
            saveUserHistory(row);
            Intent i = new Intent(this, UserReelsActivity.class);
            i.putExtra(UserReelsActivity.EXTRA_UID,   row.uid);
            i.putExtra(UserReelsActivity.EXTRA_NAME,  row.name != null ? row.name : "");
            i.putExtra(UserReelsActivity.EXTRA_PHOTO, row.photo != null ? row.photo : "");
            startActivity(i);
        } else if (row.type == Row.TYPE_TAG) {
            // Tags-tab / Top-tab tag row → that hashtag's full reels feed,
            // same screen ReelPlayerFragment's hashtag chips already open.
            Intent i = new Intent(this, HashtagReelsActivity.class);
            i.putExtra(HashtagReelsActivity.EXTRA_HASHTAG, row.query);
            startActivity(i);
        } else {
            etSearch.setText(row.query);
            etSearch.setSelection(etSearch.getText().length());
            saveTextHistory(row.query);
            showResultsMode();
            runSearch(row.query);
            hideKeyboard();
        }
    }

    // ── Prefetch wiring (same velocity-based pattern as FollowConnectionsActivity) ──

    private void attachPrefetch(RecyclerView rv, List<Row> source) {
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private long lastTimeMs = 0L;
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible < 0) return;
                long now = android.os.SystemClock.elapsedRealtime();
                long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                lastTimeMs = now;
                FollowAvatarBinder.prefetch(ReelSearchHistoryActivity.this,
                    new FollowAvatarBinder.AvatarSource() {
                        @Override public String photo(int index) { return source.get(index).photo; }
                        @Override public long avatarVersion(int index) { return source.get(index).avatarVersion; }
                        @Override public int size() { return source.size(); }
                    }, lastVisible + 1, velocity);
            }
        });
    }

    private String safeMyUid() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser() != null ? FirebaseUtils.getCurrentUid() : null;
        } catch (Exception e) { return null; }
    }

    private void showKeyboard() {
        etSearch.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    @Override
    protected void onDestroy() {
        debounceHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ── Data row (a saved account, a plain typed query, or a hashtag) ───

    static class Row {
        static final int TYPE_USER = 1;
        static final int TYPE_TEXT = 2;
        static final int TYPE_TAG  = 3;

        int type;
        String uid, name, bio, photo, query;
        /** Mobile-number handle shown as "@callxId" — same field
         *  SearchActivity/DuetInviteActivity already treat as the app's
         *  username (see SearchResultAdapter's "callxId as username"). */
        String callxId;
        long avatarVersion;
        /** TYPE_TAG only — how many reels currently carry this hashtag. */
        int tagCount;

        static Row user(String uid, String name, String bio, String photo, long avatarVersion, String callxId) {
            Row r = new Row();
            r.type = TYPE_USER;
            r.uid = uid; r.name = name; r.bio = bio; r.photo = photo; r.avatarVersion = avatarVersion;
            r.callxId = callxId;
            return r;
        }

        static Row text(String query) {
            Row r = new Row();
            r.type = TYPE_TEXT;
            r.query = query;
            return r;
        }

        static Row tag(String hashtag, int count) {
            Row r = new Row();
            r.type = TYPE_TAG;
            r.query = hashtag;
            r.tagCount = count;
            return r;
        }
    }

    // ── Shared adapter for the history list, live results list, and
    //    suggested list ────────────────────────────────────────────────

    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        private final List<Row> data;
        private final boolean showRemove;
        /** Set only on the live-results adapter — the substring to bold in
         *  each row's name/username/tag text (point 5: match highlighting). */
        private String highlightQuery = "";

        RowAdapter(List<Row> initial, boolean showRemove) {
            this.data = new ArrayList<>(initial);
            this.showRemove = showRemove;
        }

        void setHighlightQuery(String query) {
            this.highlightQuery = query != null ? query : "";
        }

        void submit(List<Row> newData) {
            data.clear();
            data.addAll(newData);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reel_search_history, parent, false);
            return new VH(v);
        }

        /** Bolds every occurrence of highlightQuery inside text (case-
         *  insensitive) — Instagram-style matched-substring highlighting.
         *  Returns the plain text unchanged when there's nothing to
         *  highlight (history rows, or no active query). */
        private CharSequence highlighted(String text) {
            if (text == null) return "";
            if (highlightQuery.isEmpty()) return text;
            String haystackLower = text.toLowerCase(Locale.getDefault());
            String needleLower = highlightQuery.startsWith("#")
                ? highlightQuery.substring(1) : highlightQuery;
            if (needleLower.isEmpty()) return text;
            SpannableString spannable = new SpannableString(text);
            int from = 0;
            boolean any = false;
            while (true) {
                int idx = haystackLower.indexOf(needleLower, from);
                if (idx < 0) break;
                spannable.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + needleLower.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                any = true;
                from = idx + needleLower.length();
            }
            return any ? spannable : text;
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Row r = data.get(pos);
            h.boundRow = r;

            if (r.type == Row.TYPE_USER) {
                h.ivAvatar.setVisibility(View.VISIBLE);
                h.ivHistoryBg.setVisibility(View.GONE);
                h.ivHistoryIcon.setVisibility(View.GONE);
                FollowAvatarBinder.bind(ReelSearchHistoryActivity.this, h.ivAvatar,
                    r.photo, r.avatarVersion, R.drawable.ic_person);
                // Same verified-badge lookup ReelCommentsAdapter/PostsFeedActivity/
                // FollowConnectionsActivity etc. already use — cached by uid so
                // scrolling doesn't repeatedly hit Firebase.
                com.callx.app.utils.VerifiedBadgeUtils.bindForUid(h.ivVerified, r.uid);
                // Same gradient/seen/hidden story ring HomeFragment's feed post
                // avatar and Stories tray already use — see StoryRingApplier.
                com.callx.app.utils.StoryRingApplier.applyWithClick(ReelSearchHistoryActivity.this, h.ivStoryRing, r.uid);
                h.tvName.setText(highlighted((r.callxId != null && !r.callxId.isEmpty()) ? r.callxId
                        : (r.name != null ? r.name : r.uid)));
                // Instagram-level: username is the primary bold line; name
                // becomes the secondary line below it, and only shows when
                // it differs from what's already shown as the username —
                // same convention as FollowConnectionsActivity's row bind.
                if (h.tvUsername != null) {
                    boolean hasCallxId = r.callxId != null && !r.callxId.isEmpty();
                    boolean nameDiffers = r.name != null && !r.name.isEmpty()
                        && (!hasCallxId || !r.name.equalsIgnoreCase(r.callxId));
                    if (nameDiffers) {
                        h.tvUsername.setText(highlighted(r.name));
                        h.tvUsername.setVisibility(View.VISIBLE);
                    } else {
                        h.tvUsername.setVisibility(View.GONE);
                    }
                }
                if (r.bio != null && !r.bio.isEmpty()) {
                    h.tvSubtitle.setText(r.bio);
                    h.tvSubtitle.setVisibility(View.VISIBLE);
                } else {
                    h.tvSubtitle.setVisibility(View.GONE);
                }

                // Follow/Following pill — hidden for the signed-in user's
                // own row, shown for every other TYPE_USER row.
                String myUid = safeMyUid();
                if (h.btnFollow != null) {
                    if (r.uid != null && r.uid.equals(myUid)) {
                        h.btnFollow.setVisibility(View.GONE);
                    } else {
                        h.btnFollow.setVisibility(View.VISIBLE);
                        styleFollowBtn(h.btnFollow, myFollowing.contains(r.uid));
                    }
                }
            } else if (r.type == Row.TYPE_TAG) {
                h.ivAvatar.setVisibility(View.GONE);
                if (h.ivVerified != null) h.ivVerified.setVisibility(View.GONE);
                if (h.ivStoryRing != null) h.ivStoryRing.setVisibility(View.GONE);
                h.ivHistoryBg.setVisibility(View.VISIBLE);
                h.ivHistoryIcon.setVisibility(View.VISIBLE);
                h.ivHistoryIcon.setImageResource(R.drawable.ic_hashtag);
                h.tvName.setText(highlighted("#" + r.query));
                if (h.tvUsername != null) h.tvUsername.setVisibility(View.GONE);
                h.tvSubtitle.setText(r.tagCount == 1 ? "1 reel" : r.tagCount + " reels");
                h.tvSubtitle.setVisibility(View.VISIBLE);
                if (h.btnFollow != null) h.btnFollow.setVisibility(View.GONE);
            } else {
                h.ivAvatar.setVisibility(View.GONE);
                if (h.ivVerified != null) h.ivVerified.setVisibility(View.GONE);
                h.ivHistoryBg.setVisibility(View.VISIBLE);
                h.ivHistoryIcon.setVisibility(View.VISIBLE);
                h.ivHistoryIcon.setImageResource(R.drawable.ic_history);
                if (h.ivStoryRing != null) h.ivStoryRing.setVisibility(View.GONE);
                h.tvName.setText(r.query);
                if (h.tvUsername != null) h.tvUsername.setVisibility(View.GONE);
                h.tvSubtitle.setVisibility(View.GONE);
                if (h.btnFollow != null) h.btnFollow.setVisibility(View.GONE);
            }

            h.btnRemove.setVisibility(showRemove ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            super.onViewRecycled(h);
            FollowAvatarBinder.cancel(ReelSearchHistoryActivity.this, h.ivAvatar);
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            ImageView ivStoryRing;
            ImageView ivVerified;
            View ivHistoryBg;
            ImageView ivHistoryIcon;
            TextView tvName, tvSubtitle, tvUsername;
            Button btnFollow;
            ImageButton btnRemove;
            Row boundRow;

            VH(View v) {
                super(v);
                ivAvatar      = v.findViewById(R.id.iv_avatar);
                ivStoryRing   = v.findViewById(R.id.iv_story_ring);
                ivVerified    = v.findViewById(R.id.iv_verified);
                ivHistoryBg   = v.findViewById(R.id.iv_history_bg);
                ivHistoryIcon = v.findViewById(R.id.iv_history_icon);
                tvName        = v.findViewById(R.id.tv_name);
                tvUsername    = v.findViewById(R.id.tv_username);
                tvSubtitle    = v.findViewById(R.id.tv_subtitle);
                btnFollow     = v.findViewById(R.id.btn_follow);
                btnRemove     = v.findViewById(R.id.btn_remove);

                itemView.setOnClickListener(v2 -> {
                    if (boundRow != null) onRowTapped(boundRow);
                });
                btnRemove.setOnClickListener(v2 -> {
                    if (boundRow != null) removeHistoryEntry(boundRow);
                });
                if (btnFollow != null) {
                    btnFollow.setOnClickListener(v2 -> {
                        if (boundRow != null && boundRow.type == Row.TYPE_USER) toggleFollow(boundRow);
                    });
                }
            }
        }
    }

    /** Filled "Follow" vs outlined "Following" pill — same rounded-rect
     *  GradientDrawable styling FollowConnectionsActivity#styleBtn() uses,
     *  duplicated here rather than shared since that method lives on a
     *  different Activity and reads its own resources/theme. */
    private void styleFollowBtn(Button btn, boolean following) {
        btn.setText(following ? "Following" : "Follow");
        float r = 8f * btn.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(r);
        if (!following) {
            bg.setColor(com.callx.app.utils.FollowButtonStyler.primaryColor(btn.getContext()));
            btn.setBackgroundTintList(
                    com.callx.app.utils.FollowButtonStyler.primaryStateList(btn.getContext()));
            btn.setTextColor(com.callx.app.utils.FollowButtonStyler.textColor(btn.getContext()));
        } else {
            bg.setColor(resolveAttrColor(com.google.android.material.R.attr.colorSurfaceVariant));
            bg.setStroke((int) (1 * btn.getResources().getDisplayMetrics().density),
                    getResources().getColor(com.callx.app.core.R.color.divider, null));
            btn.setBackgroundTintList(null);
            btn.setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
        btn.setBackground(bg);
    }

    private int resolveAttrColor(int attrResId) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrResId, tv, true);
        return tv.data;
    }
}
