package com.callx.app.explore;

import com.callx.app.profile.UserReelsActivity;
import com.callx.app.followers.FollowAvatarBinder;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private EditText     etSearch;
    private ImageButton  btnBack, btnClear;
    private View         layoutHistory, layoutResults;
    private View         layoutHistoryEmpty, layoutResultsEmpty;
    private TextView     tvSeeAll;
    private RecyclerView rvHistory, rvResults;
    private ProgressBar  progressBar;

    private SharedPreferences prefs;
    private final List<Row> historyRows = new ArrayList<>();
    private final List<Row> resultRows  = new ArrayList<>();
    private boolean historyExpanded = false;

    private RowAdapter historyAdapter;
    private RowAdapter resultsAdapter;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

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
        rvHistory          = findViewById(R.id.rv_history);
        rvResults          = findViewById(R.id.rv_results);
        progressBar        = findViewById(R.id.progress_bar);

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

        tvSeeAll.setOnClickListener(v -> {
            historyExpanded = !historyExpanded;
            renderHistory();
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
                debounceRunnable = () -> searchUsers(query);
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
                    searchUsers(q);
                }
                hideKeyboard();
                return true;
            }
            return false;
        });

        loadHistory();
        renderHistory();
        showHistoryMode();

        etSearch.requestFocus();
        showKeyboard();
    }

    // ── Mode switching ───────────────────────────────────────────────────

    private void showHistoryMode() {
        layoutHistory.setVisibility(View.VISIBLE);
        layoutResults.setVisibility(View.GONE);
    }

    private void showResultsMode() {
        layoutHistory.setVisibility(View.GONE);
        layoutResults.setVisibility(View.VISIBLE);
        layoutResultsEmpty.setVisibility(View.GONE);
        rvResults.setVisibility(View.VISIBLE);
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

                    // 2) username prefix — sparser field, but exactly what the
                    //    user asked for ("kisi contact ya username se search").
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
        String name  = c.child("name").getValue(String.class);
        String bio   = c.child("bio").getValue(String.class);
        String thumb = c.child("thumbUrl").getValue(String.class);
        String photo = c.child("photoUrl").getValue(String.class);
        Long   ver   = c.child("avatarVersion").getValue(Long.class);
        String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
        return Row.user(uid, name != null ? name : uid, bio, p != null ? p : "", ver != null ? ver : 0L);
    }

    private void finishSearch(String query, List<Row> merged) {
        // Only apply if the box still has this same query (a fast typist may
        // have already moved on to a longer/shorter query by the time this
        // chained callback resolves).
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            String current = etSearch.getText().toString().trim().toLowerCase(Locale.getDefault());
            if (!current.equals(query)) return;
            resultRows.clear();
            resultRows.addAll(merged);
            resultsAdapter.notifyDataSetChanged();
            layoutResultsEmpty.setVisibility(merged.isEmpty() ? View.VISIBLE : View.GONE);
            rvResults.setVisibility(merged.isEmpty() ? View.GONE : View.VISIBLE);
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
                        o.optString("bio", null), o.optString("photo"), o.optLong("avatarVersion")));
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
        } else {
            etSearch.setText(row.query);
            etSearch.setSelection(etSearch.getText().length());
            saveTextHistory(row.query);
            showResultsMode();
            searchUsers(row.query);
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

    // ── Data row (either a saved account or a plain typed query) ────────

    static class Row {
        static final int TYPE_USER = 1;
        static final int TYPE_TEXT = 2;

        int type;
        String uid, name, bio, photo, query;
        long avatarVersion;

        static Row user(String uid, String name, String bio, String photo, long avatarVersion) {
            Row r = new Row();
            r.type = TYPE_USER;
            r.uid = uid; r.name = name; r.bio = bio; r.photo = photo; r.avatarVersion = avatarVersion;
            return r;
        }

        static Row text(String query) {
            Row r = new Row();
            r.type = TYPE_TEXT;
            r.query = query;
            return r;
        }
    }

    // ── Shared adapter for both the history list and the live results list ──

    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        private final List<Row> data;
        private final boolean showRemove;

        RowAdapter(List<Row> initial, boolean showRemove) {
            this.data = new ArrayList<>(initial);
            this.showRemove = showRemove;
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
                // Same gradient/seen/hidden story ring HomeFragment's feed post
                // avatar and Stories tray already use — see StoryRingApplier.
                com.callx.app.utils.StoryRingApplier.applyWithClick(ReelSearchHistoryActivity.this, h.ivStoryRing, r.uid);
                h.tvName.setText(r.name != null ? r.name : r.uid);
                if (r.bio != null && !r.bio.isEmpty()) {
                    h.tvSubtitle.setText(r.bio);
                    h.tvSubtitle.setVisibility(View.VISIBLE);
                } else {
                    h.tvSubtitle.setVisibility(View.GONE);
                }
            } else {
                h.ivAvatar.setVisibility(View.GONE);
                h.ivHistoryBg.setVisibility(View.VISIBLE);
                h.ivHistoryIcon.setVisibility(View.VISIBLE);
                if (h.ivStoryRing != null) h.ivStoryRing.setVisibility(View.GONE);
                h.tvName.setText(r.query);
                h.tvSubtitle.setVisibility(View.GONE);
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
            View ivHistoryBg;
            ImageView ivHistoryIcon;
            TextView tvName, tvSubtitle;
            ImageButton btnRemove;
            Row boundRow;

            VH(View v) {
                super(v);
                ivAvatar      = v.findViewById(R.id.iv_avatar);
                ivStoryRing   = v.findViewById(R.id.iv_story_ring);
                ivHistoryBg   = v.findViewById(R.id.iv_history_bg);
                ivHistoryIcon = v.findViewById(R.id.iv_history_icon);
                tvName        = v.findViewById(R.id.tv_name);
                tvSubtitle    = v.findViewById(R.id.tv_subtitle);
                btnRemove     = v.findViewById(R.id.btn_remove);

                itemView.setOnClickListener(v2 -> {
                    if (boundRow != null) onRowTapped(boundRow);
                });
                btnRemove.setOnClickListener(v2 -> {
                    if (boundRow != null) removeHistoryEntry(boundRow);
                });
            }
        }
    }
}
