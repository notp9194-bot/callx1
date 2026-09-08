package com.callx.app.explore;

import com.callx.app.player.SingleReelPlayerActivity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.profile.ReelGridAdapter;
import com.callx.app.explore.ReelHashtagSuggestAdapter;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelSearchActivity — Reels dhundhne ki screen (caption + hashtag search).
 *
 * Features:
 *  ✅ Search bar with debounce (300ms) — live results jaise type karo
 *  ✅ Caption search — reel ke caption mein text match
 *  ✅ Hashtag search — #dance type karo → sirf dance reels dikhenge
 *  ✅ Results mein 3-column grid (tap → SingleReelPlayerActivity)
 *  ✅ Result count badge
 *  ✅ Empty state agar koi result nahi
 *  ✅ Keyboard "Search" button se bhi search hota hai
 *
 * ★ NEW (Explore default state): pehle koi query type na hone par sirf trending
 * hashtag chips dikhte the aur neeche khaali space rehta tha. Ab showTrending()
 * results grid ko bhi ek Instagram Explore-style "trending reels" set se bhar
 * deta hai (allReels, trendingScore() se sorted, top EXPLORE_GRID_LIMIT) — same
 * resultsAdapter (ReelGridAdapter — wahi grid+optimization jo UserReelsActivity
 * ki profile grid use karti hai) render karta hai, sirf views-overlay is screen
 * pe on kiya gaya hai (setShowViewsOverlay) taaki har tile pe Explore jaisa
 * eye+count badge dikhe. Ye screen ab bottom nav ke naye Search tab
 * (ReelsFragment#reel_nav_search) aur top-bar btn_reel_search — dono se launch
 * hoti hai.
 */
public class ReelSearchActivity extends AppCompatActivity {

    private EditText          etSearch;
    private ImageButton       btnBack, btnClear;
    private RecyclerView      rvResults;
    private RecyclerView      rvHashtagSuggestions;
    private View              layoutTrending, layoutResults, layoutEmpty;
    private TextView          tvResultCount;
    private ProgressBar       progressBar;

    private ReelGridAdapter   resultsAdapter;
    private ReelHashtagSuggestAdapter suggestAdapter;

    private final List<ReelModel>   allReels  = new ArrayList<>();
    private final List<ReelModel>   results   = new ArrayList<>();
    private final List<String>      hashtags  = new ArrayList<>();
    private ValueEventListener      reelsListener;

    private final Handler  debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable       debounceRunnable;
    private static final long DEBOUNCE_MS = 300;
    // ★ NEW: cap on how many trending reels populate the default Explore grid —
    // enough to fill several screens without binding/preloading the entire
    // allReels list up front (ReelGridAdapter's own preloader takes it from here).
    private static final int EXPLORE_GRID_LIMIT = 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_search);

        etSearch            = findViewById(R.id.et_search);
        btnBack             = findViewById(R.id.btn_back);
        btnClear            = findViewById(R.id.btn_clear);
        rvResults           = findViewById(R.id.rv_results);
        rvHashtagSuggestions = findViewById(R.id.rv_hashtag_suggestions);
        layoutTrending      = findViewById(R.id.layout_trending);
        layoutResults       = findViewById(R.id.layout_results);
        layoutEmpty         = findViewById(R.id.layout_empty);
        tvResultCount       = findViewById(R.id.tv_result_count);
        progressBar         = findViewById(R.id.progress_bar);

        btnBack.setOnClickListener(v -> finish());
        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            showTrending();
        });

        // Results grid
        resultsAdapter = new ReelGridAdapter(this, results, (position) -> {
            Intent intent = new Intent(this, SingleReelPlayerActivity.class);
            intent.putExtra(SingleReelPlayerActivity.EXTRA_TITLE,
                "Search: " + etSearch.getText().toString().trim());
            intent.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, position);
            // Pass reel IDs to SingleReelPlayerActivity
            ArrayList<String> ids = new ArrayList<>();
            for (ReelModel r : results) ids.add(r.reelId);
            intent.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            startActivity(intent);
        });
        rvResults.setLayoutManager(new GridLayoutManager(this, 3));
        rvResults.setAdapter(resultsAdapter);
        // ★ NEW: Explore-style eye+view-count badge on every tile (same overlay
        // UserReelsActivity turns on for isSelf's own grid) — shows both on the
        // default trending grid and on typed-search results.
        resultsAdapter.setShowViewsOverlay(true);

        // Hashtag suggestions (horizontal)
        suggestAdapter = new ReelHashtagSuggestAdapter(this, hashtags, (tag) -> {
            etSearch.setText("#" + tag);
            etSearch.setSelection(etSearch.getText().length());
            search("#" + tag);
        });
        rvHashtagSuggestions.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvHashtagSuggestions.setAdapter(suggestAdapter);

        // Search bar listeners
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                btnClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                debounceHandler.removeCallbacks(debounceRunnable);
                String query = s.toString().trim();
                debounceRunnable = () -> {
                    if (query.isEmpty()) showTrending();
                    else search(query);
                };
                debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = etSearch.getText().toString().trim();
                if (!q.isEmpty()) search(q);
                hideKeyboard();
                return true;
            }
            return false;
        });

        // Auto-focus keyboard
        etSearch.requestFocus();
        showKeyboard();

        // Load all reels once (for client-side search)
        loadAllReels();
    }

    private void loadAllReels() {
        progressBar.setVisibility(View.VISIBLE);
        reelsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                allReels.clear();
                Map<String, Integer> hashtagFreq = new LinkedHashMap<>();

                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel reel = s.getValue(ReelModel.class);
                    if (reel == null) continue;
                    if (reel.reelId == null) reel.reelId = s.getKey();
                    allReels.add(reel);

                    // Count hashtag frequency for trending
                    if (reel.hashtags != null) {
                        for (String tag : reel.hashtags) {
                            hashtagFreq.merge(tag, 1, Integer::sum);
                        }
                    }
                }

                // Build trending hashtags sorted by frequency
                List<Map.Entry<String, Integer>> entries =
                    new ArrayList<>(hashtagFreq.entrySet());
                Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
                hashtags.clear();
                for (Map.Entry<String, Integer> e : entries) {
                    hashtags.add(e.getKey());
                    if (hashtags.size() >= 20) break;
                }
                suggestAdapter.notifyDataSetChanged();

                progressBar.setVisibility(View.GONE);

                // If search box already has text (e.g. launched with query)
                String currentQuery = etSearch.getText().toString().trim();
                if (!currentQuery.isEmpty()) search(currentQuery);
                else showTrending();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
            }
        };
        FirebaseUtils.getReelsRef().addValueEventListener(reelsListener);
    }

    private void search(String query) {
        String lowerQuery = query.toLowerCase().trim();
        boolean isHashtag = lowerQuery.startsWith("#");
        String tag = isHashtag ? lowerQuery.substring(1) : lowerQuery;

        results.clear();
        for (ReelModel reel : allReels) {
            if (isHashtag) {
                // Hashtag search
                if (reel.hashtags != null) {
                    for (String t : reel.hashtags) {
                        if (t.toLowerCase().contains(tag)) { results.add(reel); break; }
                    }
                }
            } else {
                // Caption search
                boolean captionMatch = reel.caption != null &&
                    reel.caption.toLowerCase().contains(tag);
                boolean ownerMatch = reel.ownerName != null &&
                    reel.ownerName.toLowerCase().contains(tag);
                boolean hashtagMatch = false;
                if (reel.hashtags != null) {
                    for (String t : reel.hashtags) {
                        if (t.toLowerCase().contains(tag)) { hashtagMatch = true; break; }
                    }
                }
                if (captionMatch || ownerMatch || hashtagMatch) results.add(reel);
            }
        }

        // Sort by trending score
        Collections.sort(results, (a, b) ->
            Float.compare(b.trendingScore(), a.trendingScore()));

        layoutTrending.setVisibility(View.GONE);
        layoutResults.setVisibility(View.VISIBLE);
        tvResultCount.setVisibility(View.VISIBLE);
        tvResultCount.setText(results.size() + " results for \"" + query + "\"");

        if (results.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            rvResults.setVisibility(View.GONE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            rvResults.setVisibility(View.VISIBLE);
            resultsAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Default (no query typed) state — ★ NEW: now an Instagram Explore-style grid
     * of trending reels, not just an empty screen under the hashtag chips.
     * Builds `results` from the top EXPLORE_GRID_LIMIT of allReels by
     * trendingScore() and shows it through the same resultsAdapter/rv_results
     * the typed-search flow uses, so the grid, its view-count overlay, and its
     * preloading/recycling are all one code path either way.
     */
    private void showTrending() {
        results.clear();
        List<ReelModel> trending = new ArrayList<>(allReels);
        Collections.sort(trending, (a, b) -> Float.compare(b.trendingScore(), a.trendingScore()));
        for (ReelModel r : trending) {
            results.add(r);
            if (results.size() >= EXPLORE_GRID_LIMIT) break;
        }
        resultsAdapter.notifyDataSetChanged();

        // layoutTrending and layoutResults are match_parent siblings in the same
        // FrameLayout — showing both together would overlap. The Explore grid
        // now replaces the hashtag-chips row as the default view entirely
        // (matches the reference screenshot: just the grid, no separate row).
        layoutTrending.setVisibility(View.GONE);
        layoutEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        layoutResults.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);
        rvResults.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);
        tvResultCount.setVisibility(View.GONE); // "Explore" grid, not a search-result count
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
        if (reelsListener != null) FirebaseUtils.getReelsRef().removeEventListener(reelsListener);
        super.onDestroy();
    }
}
