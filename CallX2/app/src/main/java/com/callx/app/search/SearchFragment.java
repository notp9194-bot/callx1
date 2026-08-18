package com.callx.app.search;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.R;
import com.callx.app.activities.UserProfileActivity;
import com.callx.app.models.ReelModel;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.profile.ReelGridAdapter;
import com.callx.app.profile.ReelPeekPreviewController;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Instagram-style Explore/Search tab.
 *
 * The default state is a live 3-column reel discovery grid. Tapping the
 * search field changes to a recent-search surface, then progressively combines
 * user, hashtag, caption, and reel matches as the user types.
 */
public class SearchFragment extends Fragment {

    private static final String PREFS_SEARCH = "callx_search_history";
    private static final String KEY_RECENT = "recent_queries";
    private static final int MAX_RECENT = 16;
    private static final long DEBOUNCE_MS = 280L;

    private EditText etSearch;
    private ImageButton btnBack;
    private ImageButton btnClear;
    private View exploreContainer;
    private View searchContainer;
    private View emptyState;
    private View searchEmptyState;
    private TextView tvRecentTitle;
    private TextView tvSeeAll;
    private TextView tvResultsTitle;
    private TextView tvExploreTitle;
    private ProgressBar progressBar;
    private RecyclerView rvExplore;
    private RecyclerView rvSuggestions;
    private RecyclerView rvSearchReels;

    private ReelGridAdapter exploreAdapter;
    private ReelGridAdapter searchReelsAdapter;
    private SearchSuggestionAdapter suggestionAdapter;
    private ReelPeekPreviewController peekController;

    private final List<ReelModel> allReels = new ArrayList<>();
    private final List<ReelModel> filteredReels = new ArrayList<>();
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private ValueEventListener reelsListener;
    private boolean searchMode = false;
    private int searchGeneration = 0;

    public static final class SearchSuggestion {
        public static final int KIND_RECENT = 0;
        public static final int KIND_USER = 1;
        public static final int KIND_HASHTAG = 2;
        public static final int KIND_REEL = 3;

        public final int kind;
        public final String id;
        public final String primary;
        public final String secondary;
        public final String avatar;
        public final boolean removeable;

        SearchSuggestion(int kind, String id, String primary, String secondary,
                         String avatar, boolean removeable) {
            this.kind = kind;
            this.id = id == null ? "" : id;
            this.primary = primary == null ? "" : primary;
            this.secondary = secondary == null ? "" : secondary;
            this.avatar = avatar == null ? "" : avatar;
            this.removeable = removeable;
        }

        static SearchSuggestion recent(String query) {
            return new SearchSuggestion(KIND_RECENT, query, query, "Recent search",
                    "", true);
        }

        static SearchSuggestion user(String uid, String name, String secondary,
                                     String avatar) {
            return new SearchSuggestion(KIND_USER, uid, name, secondary, avatar, false);
        }

        static SearchSuggestion hashtag(String tag, int count) {
            return new SearchSuggestion(KIND_HASHTAG, tag,
                    tag, count > 0 ? count + " reels" : "Explore hashtag", "", false);
        }

        static SearchSuggestion reel(ReelModel reel) {
            String title = reel.caption == null || reel.caption.trim().isEmpty()
                    ? "Reel by " + safe(reel.ownerName, "CallX creator")
                    : reel.caption.trim();
            return new SearchSuggestion(KIND_REEL, reel.reelId, title,
                    "Open reel", reel.effectiveThumbUrl(), false);
        }

        private static String safe(String value, String fallback) {
            return value == null || value.trim().isEmpty() ? fallback : value;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupExploreGrid();
        setupSearchLists();
        setupInteractions();
        loadReels();
    }

    private void bindViews(View view) {
        etSearch = view.findViewById(R.id.et_search);
        btnBack = view.findViewById(R.id.btn_search_back);
        btnClear = view.findViewById(R.id.btn_search_clear);
        exploreContainer = view.findViewById(R.id.explore_container);
        searchContainer = view.findViewById(R.id.search_container);
        emptyState = view.findViewById(R.id.explore_empty_state);
        searchEmptyState = view.findViewById(R.id.search_empty_state);
        tvRecentTitle = view.findViewById(R.id.tv_recent_title);
        tvSeeAll = view.findViewById(R.id.tv_see_all);
        tvResultsTitle = view.findViewById(R.id.tv_results_title);
        tvExploreTitle = view.findViewById(R.id.tv_explore_title);
        progressBar = view.findViewById(R.id.search_progress);
        rvExplore = view.findViewById(R.id.rv_explore);
        rvSuggestions = view.findViewById(R.id.rv_suggestions);
        rvSearchReels = view.findViewById(R.id.rv_search_reels);
    }

    private void setupExploreGrid() {
        rvExplore.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        exploreAdapter = new ReelGridAdapter(this, allReels,
                position -> openReelList(allReels, position, "Explore"),
                position -> showPeekFor(allReels, position, rvExplore, "Explore"),
                null);
        rvExplore.setAdapter(exploreAdapter);
        rvExplore.setHasFixedSize(true);
        rvExplore.setItemViewCacheSize(12);
        rvExplore.addItemDecoration(new ReelGridAdapter.WhiteGridDecoration(requireContext()));
    }

    private void setupSearchLists() {
        rvSuggestions.setLayoutManager(new LinearLayoutManager(requireContext()));
        suggestionAdapter = new SearchSuggestionAdapter();
        suggestionAdapter.setListener(new SearchSuggestionAdapter.Listener() {
            @Override
            public void onSuggestionClicked(SearchSuggestion suggestion) {
                handleSuggestion(suggestion);
            }

            @Override
            public void onRemoveClicked(SearchSuggestion suggestion) {
                removeRecent(suggestion.primary);
                showRecentSearches();
            }
        });
        rvSuggestions.setAdapter(suggestionAdapter);

        rvSearchReels.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        searchReelsAdapter = new ReelGridAdapter(this, filteredReels,
                position -> openReelList(filteredReels, position,
                        tvResultsTitle.getText().toString()),
                position -> showPeekFor(filteredReels, position, rvSearchReels, "Search"),
                null);
        rvSearchReels.setAdapter(searchReelsAdapter);
        rvSearchReels.setHasFixedSize(true);
        rvSearchReels.addItemDecoration(new ReelGridAdapter.WhiteGridDecoration(requireContext()));
    }

    private void setupInteractions() {
        etSearch.setOnClickListener(v -> enterSearchMode(true));
        etSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) enterSearchMode(false);
        });

        btnBack.setOnClickListener(v -> exitSearchMode());
        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            showRecentSearches();
        });

        tvSeeAll.setOnClickListener(v -> clearRecentSearches());

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                String query = etSearch.getText().toString().trim();
                if (!query.isEmpty()) {
                    addRecent(query);
                    performSearch(query);
                }
                return true;
            }
            return false;
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!searchMode) return;
                String query = s.toString().trim();
                btnClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (pendingSearch != null) debounceHandler.removeCallbacks(pendingSearch);
                if (query.isEmpty()) {
                    showRecentSearches();
                    return;
                }
                final int generation = ++searchGeneration;
                pendingSearch = () -> {
                    if (generation == searchGeneration) performSearch(query);
                };
                debounceHandler.postDelayed(pendingSearch, DEBOUNCE_MS);
            }

            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void enterSearchMode(boolean showKeyboard) {
        if (searchMode) return;
        searchMode = true;
        btnBack.setVisibility(View.VISIBLE);
        exploreContainer.setVisibility(View.GONE);
        searchContainer.setVisibility(View.VISIBLE);
        tvExploreTitle.setVisibility(View.GONE);
        showRecentSearches();
        etSearch.requestFocus();
        if (showKeyboard) {
            etSearch.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager)
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
            }, 120L);
        }
    }

    private void exitSearchMode() {
        searchMode = false;
        searchGeneration++;
        if (pendingSearch != null) debounceHandler.removeCallbacks(pendingSearch);
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        etSearch.clearFocus();
        etSearch.setText("");
        btnBack.setVisibility(View.GONE);
        searchContainer.setVisibility(View.GONE);
        exploreContainer.setVisibility(View.VISIBLE);
        // The Explore state intentionally starts directly below the search
        // field, matching the familiar visual rhythm of a media discovery
        // grid instead of adding a redundant heading row.
        tvExploreTitle.setVisibility(View.GONE);
    }

    private void showRecentSearches() {
        if (!isAdded()) return;
        tvRecentTitle.setText("Recent");
        tvSeeAll.setText("See all");
        tvSeeAll.setVisibility(getRecentSearches().isEmpty() ? View.GONE : View.VISIBLE);
        tvResultsTitle.setVisibility(View.GONE);
        rvSearchReels.setVisibility(View.GONE);
        searchEmptyState.setVisibility(View.GONE);

        List<SearchSuggestion> rows = new ArrayList<>();
        for (String query : getRecentSearches()) rows.add(SearchSuggestion.recent(query));
        suggestionAdapter.setItems(rows);
        rvSuggestions.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (rows.isEmpty()) {
            searchEmptyState.setVisibility(View.VISIBLE);
            ((TextView) searchEmptyState.findViewById(R.id.tv_empty_title))
                    .setText("Search people, reels and hashtags");
            ((TextView) searchEmptyState.findViewById(R.id.tv_empty_subtitle))
                    .setText("Your recent searches will appear here");
        }
        updateSearchListWeights(false);
    }

    private void performSearch(String rawQuery) {
        if (!isAdded()) return;
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            showRecentSearches();
            return;
        }

        tvRecentTitle.setText("Top results");
        tvSeeAll.setVisibility(View.GONE);
        tvResultsTitle.setText("Reels");
        tvResultsTitle.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        searchEmptyState.setVisibility(View.GONE);

        List<ReelModel> reelMatches = filterReels(query);
        filteredReels.clear();
        filteredReels.addAll(reelMatches);
        searchReelsAdapter.setFilteredData(filteredReels);
        rvSearchReels.setVisibility(reelMatches.isEmpty() ? View.GONE : View.VISIBLE);

        List<SearchSuggestion> localRows = buildLocalSuggestions(query, reelMatches);
        suggestionAdapter.setItems(localRows);
        rvSuggestions.setVisibility(localRows.isEmpty() ? View.GONE : View.VISIBLE);
        updateSearchListWeights(!reelMatches.isEmpty() && !localRows.isEmpty());
        searchEmptyState.setVisibility(localRows.isEmpty() && reelMatches.isEmpty()
                ? View.VISIBLE : View.GONE);
        if (localRows.isEmpty() && reelMatches.isEmpty()) {
            ((TextView) searchEmptyState.findViewById(R.id.tv_empty_title))
                    .setText("No results found");
            ((TextView) searchEmptyState.findViewById(R.id.tv_empty_subtitle))
                    .setText("Try a different name, caption or hashtag");
        }
        loadUserSuggestions(query, searchGeneration);
    }

    private List<SearchSuggestion> buildLocalSuggestions(String query,
                                                           List<ReelModel> reelMatches) {
        List<SearchSuggestion> rows = new ArrayList<>();
        String lower = query.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, Integer> hashtagCounts = new LinkedHashMap<>();
        for (ReelModel reel : allReels) {
            if (reel.hashtags == null) continue;
            for (String raw : reel.hashtags) {
                if (raw == null) continue;
                String tag = raw.startsWith("#") ? raw : "#" + raw;
                if (tag.toLowerCase(Locale.ROOT).contains(lower)) {
                    hashtagCounts.put(tag, hashtagCounts.containsKey(tag)
                            ? hashtagCounts.get(tag) + 1 : 1);
                }
            }
        }
        for (Map.Entry<String, Integer> entry : hashtagCounts.entrySet()) {
            rows.add(SearchSuggestion.hashtag(entry.getKey(), entry.getValue()));
            if (rows.size() >= 5) break;
        }
        for (int i = 0; i < Math.min(3, reelMatches.size()); i++) {
            rows.add(SearchSuggestion.reel(reelMatches.get(i)));
        }
        return rows;
    }

    private List<ReelModel> filterReels(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<ReelModel> matches = new ArrayList<>();
        for (ReelModel reel : allReels) {
            StringBuilder haystack = new StringBuilder();
            if (reel.caption != null) haystack.append(reel.caption).append(' ');
            if (reel.ownerName != null) haystack.append(reel.ownerName).append(' ');
            if (reel.musicName != null) haystack.append(reel.musicName).append(' ');
            if (reel.hashtags != null) {
                for (String tag : reel.hashtags) haystack.append(tag).append(' ');
            }
            if (haystack.toString().toLowerCase(Locale.ROOT).contains(lower)) {
                matches.add(reel);
            }
        }
        return matches;
    }

    private void loadUserSuggestions(String rawQuery, int generation) {
        final String queryText = rawQuery.toLowerCase(Locale.ROOT);
        final String selfUid = FirebaseUtils.getCurrentUid();
        final Map<String, SearchSuggestion> users = new LinkedHashMap<>();
        final int[] pending = {2};
        DatabaseReference ref = FirebaseUtils.db().getReference("users");

        Query byName = ref.orderByChild("nameLower")
                .startAt(queryText).endAt(queryText + "\uf8ff").limitToFirst(20);
        Query byId = ref.orderByChild("callxId")
                .startAt(queryText).endAt(queryText + "\uf8ff").limitToFirst(20);

        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                collectUserMatches(snapshot, queryText, selfUid, users);
                if (--pending[0] == 0 && generation == searchGeneration) {
                    prependUserSuggestions(users);
                }
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (--pending[0] == 0 && generation == searchGeneration) {
                    prependUserSuggestions(users);
                }
            }
        };
        byName.addListenerForSingleValueEvent(listener);
        byId.addListenerForSingleValueEvent(listener);
    }

    private void collectUserMatches(DataSnapshot snapshot, String query, String selfUid,
                                    Map<String, SearchSuggestion> users) {
        for (DataSnapshot child : snapshot.getChildren()) {
            String uid = child.getKey();
            if (uid == null || uid.equals(selfUid) || users.containsKey(uid)) continue;
            String name = value(child, "name");
            String callxId = value(child, "callxId");
            String nameLower = value(child, "nameLower");
            String haystack = (name + " " + callxId + " " + nameLower)
                    .toLowerCase(Locale.ROOT);
            if (!haystack.contains(query)) continue;
            String photo = value(child, "thumbUrl");
            if (photo.isEmpty()) photo = value(child, "photoUrl");
            String secondary = callxId.isEmpty() ? "CallX user" : "@" + callxId;
            users.put(uid, SearchSuggestion.user(uid,
                    name.isEmpty() ? "CallX user" : name, secondary, photo));
        }
    }

    private void prependUserSuggestions(Map<String, SearchSuggestion> users) {
        List<SearchSuggestion> rows = new ArrayList<>();
        rows.addAll(users.values());
        List<SearchSuggestion> local = new ArrayList<>();
        String query = etSearch.getText().toString().trim();
        local.addAll(buildLocalSuggestions(query, filterReels(query)));
        rows.addAll(local);
        suggestionAdapter.setItems(rows);
        rvSuggestions.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        searchEmptyState.setVisibility(rows.isEmpty() && filteredReels.isEmpty()
                ? View.VISIBLE : View.GONE);
        updateSearchListWeights(!filteredReels.isEmpty() && !rows.isEmpty());
        progressBar.setVisibility(View.GONE);
    }

    private void handleSuggestion(SearchSuggestion suggestion) {
        if (suggestion.kind == SearchSuggestion.KIND_RECENT
                || suggestion.kind == SearchSuggestion.KIND_HASHTAG) {
            etSearch.setText(suggestion.primary);
            etSearch.setSelection(etSearch.length());
            addRecent(suggestion.primary);
            performSearch(suggestion.primary);
        } else if (suggestion.kind == SearchSuggestion.KIND_USER) {
            addRecent(suggestion.primary);
            Intent profile = new Intent(requireContext(), UserProfileActivity.class);
            profile.putExtra("uid", suggestion.id);
            profile.putExtra("name", suggestion.primary);
            profile.putExtra("photo", suggestion.avatar);
            startActivity(profile);
        } else if (suggestion.kind == SearchSuggestion.KIND_REEL) {
            int position = indexOfReel(suggestion.id, filteredReels);
            if (position >= 0) openReelList(filteredReels, position, "Search");
        }
    }

    private int indexOfReel(String reelId, List<ReelModel> list) {
        for (int i = 0; i < list.size(); i++) {
            if (reelId.equals(list.get(i).reelId)) return i;
        }
        return -1;
    }

    private void openReelList(List<ReelModel> source, int position, String title) {
        if (source == null || source.isEmpty() || position < 0 || position >= source.size()) {
            return;
        }
        ArrayList<String> ids = new ArrayList<>();
        for (ReelModel reel : source) {
            if (reel.reelId != null && !reel.reelId.isEmpty()) ids.add(reel.reelId);
        }
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), "Reel load nahi ho saka",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent player = new Intent(requireContext(), SingleReelPlayerActivity.class);
        player.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        player.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION,
                Math.min(position, ids.size() - 1));
        player.putExtra(SingleReelPlayerActivity.EXTRA_TITLE, title);
        startActivity(player);
    }

    /**
     * Long-press on either Explore grid reuses the same mini video peek player
     * used by the Reels profile/feed surfaces. The adapter deliberately keeps
     * the popup open after the finger is released; the controller owns the
     * dimmed scrim, mute control, playback lifecycle, and Watch Reel action.
     */
    private void showPeekFor(List<ReelModel> source, int position, RecyclerView grid,
                             String title) {
        if (!isAdded() || source == null || position < 0 || position >= source.size()) return;
        ReelModel reel = source.get(position);
        if (reel == null) return;

        View sourceCell = null;
        if (grid != null) {
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
            if (holder != null) sourceCell = holder.itemView;
        }

        if (peekController == null) {
            peekController = new ReelPeekPreviewController(requireActivity());
        }
        final ReelModel previewedReel = reel;
        final List<ReelModel> previewSource = source;
        final View previewSourceCell = sourceCell;
        peekController.show(previewedReel, null,
                () -> {
                    int currentPosition = indexOfReel(previewedReel.reelId, previewSource);
                    if (currentPosition >= 0) {
                        openReelList(previewSource, currentPosition, title);
                    }
                },
                previewSourceCell);
    }

    /** Called by MainActivity when the user leaves the Search tab. */
    public void dismissPeekPreview() {
        if (peekController != null) peekController.dismiss();
    }

    private void loadReels() {
        progressBar.setVisibility(View.VISIBLE);
        reelsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                allReels.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    ReelModel reel = child.getValue(ReelModel.class);
                    if (reel == null) continue;
                    if (reel.reelId == null || reel.reelId.isEmpty()) reel.reelId = child.getKey();
                    if (reel.reelId == null || reel.reelId.isEmpty()) continue;
                    allReels.add(reel);
                }
                Collections.sort(allReels, (left, right) ->
                        Long.compare(right.timestamp, left.timestamp));
                if (exploreAdapter != null) exploreAdapter.setDataList(allReels);
                progressBar.setVisibility(View.GONE);
                emptyState.setVisibility(allReels.isEmpty() ? View.VISIBLE : View.GONE);
                if (searchMode && !etSearch.getText().toString().trim().isEmpty()) {
                    performSearch(etSearch.getText().toString().trim());
                }
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                progressBar.setVisibility(View.GONE);
                if (allReels.isEmpty()) emptyState.setVisibility(View.VISIBLE);
            }
        };
        FirebaseUtils.getReelsRef().addListenerForSingleValueEvent(reelsListener);
    }

    private void updateSearchListWeights(boolean split) {
        if (rvSuggestions == null || rvSearchReels == null) return;
        ViewGroup.LayoutParams suggestionParams = rvSuggestions.getLayoutParams();
        ViewGroup.LayoutParams reelParams = rvSearchReels.getLayoutParams();
        if (!(suggestionParams instanceof ViewGroup.MarginLayoutParams)
                || !(reelParams instanceof ViewGroup.MarginLayoutParams)) return;
        if (split) {
            suggestionParams.height = 0;
            reelParams.height = 0;
            ((ViewGroup.MarginLayoutParams) suggestionParams).width = ViewGroup.LayoutParams.MATCH_PARENT;
            ((ViewGroup.MarginLayoutParams) reelParams).width = ViewGroup.LayoutParams.MATCH_PARENT;
            ((android.widget.LinearLayout.LayoutParams) suggestionParams).weight = 0.40f;
            ((android.widget.LinearLayout.LayoutParams) reelParams).weight = 0.60f;
        } else {
            suggestionParams.height = 0;
            ((android.widget.LinearLayout.LayoutParams) suggestionParams).weight = 1f;
            reelParams.height = 0;
            ((android.widget.LinearLayout.LayoutParams) reelParams).weight = 0f;
        }
        rvSuggestions.setLayoutParams(suggestionParams);
        rvSearchReels.setLayoutParams(reelParams);
    }

    private List<String> getRecentSearches() {
        String raw = requireContext().getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)
                .getString(KEY_RECENT, "");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String value : raw.split("\\u001F")) {
                if (!value.trim().isEmpty()) values.add(value.trim());
            }
        }
        return new ArrayList<>(values);
    }

    private void addRecent(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) return;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(normalized);
        values.addAll(getRecentSearches());
        List<String> limited = new ArrayList<>(values);
        if (limited.size() > MAX_RECENT) limited = limited.subList(0, MAX_RECENT);
        requireContext().getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECENT, joinRecent(limited)).apply();
    }

    private void removeRecent(String query) {
        List<String> values = getRecentSearches();
        values.remove(query);
        requireContext().getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECENT, joinRecent(values)).apply();
    }

    private void clearRecentSearches() {
        requireContext().getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECENT).apply();
        showRecentSearches();
    }

    private String joinRecent(List<String> values) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) output.append('\u001F');
            output.append(value.replace('\u001F', ' '));
        }
        return output.toString();
    }

    private String value(DataSnapshot snapshot, String key) {
        String value = snapshot.child(key).getValue(String.class);
        return value == null ? "" : value.trim();
    }

    @Override
    public void onDestroyView() {
        dismissPeekPreview();
        super.onDestroyView();
        if (pendingSearch != null) debounceHandler.removeCallbacks(pendingSearch);
        if (reelsListener != null) {
            FirebaseUtils.getReelsRef().removeEventListener(reelsListener);
            reelsListener = null;
        }
        etSearch = null;
        rvExplore = null;
        rvSuggestions = null;
        rvSearchReels = null;
    }
}