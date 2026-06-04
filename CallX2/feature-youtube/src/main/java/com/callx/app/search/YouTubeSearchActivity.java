package com.callx.app.search;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.home.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * YouTubeSearchActivity — Upgraded with:
 * 1. Search History (SharedPreferences mein save, cross-session)
 * 2. Live suggestions while typing (title prefix match)
 * 3. Search results with proper filtering
 */
public class YouTubeSearchActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "yt_search_prefs";
    private static final String KEY_HISTORY = "search_history";
    private static final int MAX_HISTORY = 15;

    private EditText etSearch;
    private RecyclerView rvResults;
    private LinearLayout llSuggestionsContainer;
    private LinearLayout llHistoryContainer;
    private TextView tvHistoryClear;
    private YouTubeVideoAdapter adapter;
    private String myUid;
    private SharedPreferences prefs;
    private List<String> searchHistory = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_search);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadHistory();

        etSearch           = findViewById(R.id.et_yt_search);
        rvResults          = findViewById(R.id.rv_yt_search_results);
        llSuggestionsContainer = findViewById(R.id.ll_yt_suggestions);
        llHistoryContainer = findViewById(R.id.ll_yt_search_history);
        tvHistoryClear     = findViewById(R.id.tv_yt_history_clear);

        ImageButton btnBack = findViewById(R.id.btn_yt_search_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video -> {
            saveToHistory(etSearch.getText().toString().trim());
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId));
        });
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(adapter);

        // Show history on open
        showHistory();

        // Clear history button
        if (tvHistoryClear != null)
            tvHistoryClear.setOnClickListener(v -> {
                searchHistory.clear();
                saveHistory();
                showHistory();
            });

        // Text watcher — suggestions while typing
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim();
                if (q.isEmpty()) {
                    rvResults.setVisibility(View.GONE);
                    showHistory();
                } else {
                    hideHistory();
                    showSuggestions(q);
                    if (q.length() >= 2) performSearch(q);
                }
            }
        });

        // IME search action
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = etSearch.getText().toString().trim();
                if (!q.isEmpty()) {
                    saveToHistory(q);
                    performSearch(q);
                    hideKeyboard();
                    hideSuggestions();
                }
                return true;
            }
            return false;
        });

        // Auto-focus search bar
        etSearch.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
    }

    // ── History ───────────────────────────────────────────────────────────────

    private void loadHistory() {
        Set<String> set = prefs.getStringSet(KEY_HISTORY, new LinkedHashSet<>());
        searchHistory = new ArrayList<>(set);
    }

    private void saveHistory() {
        prefs.edit().putStringSet(KEY_HISTORY, new LinkedHashSet<>(searchHistory)).apply();
    }

    private void saveToHistory(String query) {
        if (query.isEmpty()) return;
        searchHistory.remove(query);
        searchHistory.add(0, query);
        if (searchHistory.size() > MAX_HISTORY)
            searchHistory = searchHistory.subList(0, MAX_HISTORY);
        saveHistory();
    }

    private void showHistory() {
        if (llHistoryContainer == null) return;
        llHistoryContainer.removeAllViews();
        if (searchHistory.isEmpty()) {
            llHistoryContainer.setVisibility(View.GONE);
            return;
        }
        llHistoryContainer.setVisibility(View.VISIBLE);

        for (String h : searchHistory) {
            View item = LayoutInflater.from(this)
                .inflate(R.layout.item_yt_search_suggestion, llHistoryContainer, false);
            TextView tvText  = item.findViewById(R.id.tv_suggestion_text);
            ImageButton btnX = item.findViewById(R.id.btn_suggestion_remove);
            ImageButton icon = item.findViewById(R.id.iv_suggestion_icon);
            if (tvText != null) tvText.setText(h);
            if (icon != null) icon.setImageResource(R.drawable.ic_yt_library);
            if (btnX != null) btnX.setOnClickListener(v -> {
                searchHistory.remove(h);
                saveHistory();
                showHistory();
            });
            item.setOnClickListener(v -> {
                etSearch.setText(h);
                etSearch.setSelection(h.length());
                saveToHistory(h);
                performSearch(h);
                hideKeyboard();
                hideHistory();
                hideSuggestions();
            });
            llHistoryContainer.addView(item);
        }
    }

    private void hideHistory() {
        if (llHistoryContainer != null) llHistoryContainer.setVisibility(View.GONE);
    }

    // ── Suggestions ───────────────────────────────────────────────────────────

    private void showSuggestions(String query) {
        if (llSuggestionsContainer == null) return;
        llSuggestionsContainer.removeAllViews();
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("title")
            .startAt(query).endAt(query + "\uf8ff")
            .limitToFirst(5)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (llSuggestionsContainer == null) return;
                    llSuggestionsContainer.removeAllViews();
                    boolean anyFound = false;
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v == null || v.title == null) continue;
                        anyFound = true;
                        View item = LayoutInflater.from(YouTubeSearchActivity.this)
                            .inflate(R.layout.item_yt_search_suggestion,
                                llSuggestionsContainer, false);
                        TextView tvText = item.findViewById(R.id.tv_suggestion_text);
                        if (tvText != null) tvText.setText(v.title);
                        View btnX = item.findViewById(R.id.btn_suggestion_remove);
                        if (btnX != null) btnX.setVisibility(View.GONE);
                        item.setOnClickListener(view -> {
                            etSearch.setText(v.title);
                            etSearch.setSelection(v.title.length());
                            saveToHistory(v.title);
                            performSearch(v.title);
                            hideKeyboard();
                            hideSuggestions();
                        });
                        llSuggestionsContainer.addView(item);
                    }
                    llSuggestionsContainer.setVisibility(anyFound ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void hideSuggestions() {
        if (llSuggestionsContainer != null) {
            llSuggestionsContainer.removeAllViews();
            llSuggestionsContainer.setVisibility(View.GONE);
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void performSearch(String query) {
        rvResults.setVisibility(View.VISIBLE);
        String q = query.toLowerCase();
        YouTubeFirebaseUtils.globalFeedRef()
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> results = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v == null) continue;
                        boolean match = (v.title != null && v.title.toLowerCase().contains(q))
                            || (v.uploaderName != null && v.uploaderName.toLowerCase().contains(q))
                            || (v.description != null && v.description.toLowerCase().contains(q));
                        if (match && "public".equals(v.visibility)) results.add(v);
                    }
                    adapter.setData(results);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }
}
