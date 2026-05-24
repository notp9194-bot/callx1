package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Full search: videos + channels, filter by category/type/sort,
 * search history display, search suggestions.
 */
public class YouTubeSearchActivity extends AppCompatActivity {

    private EditText           etSearch;
    private RecyclerView       rvResults, rvHistory;
    private YouTubeVideoAdapter adapter;
    private LinearLayout       layoutHistory, layoutFilters;
    private LinearLayout       layoutSuggestions;
    private String             myUid;
    private String             activeFilter = "all";  // all | videos | channels | shorts
    private String             activeSort   = "relevance"; // relevance | date | views

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_search);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        etSearch          = findViewById(R.id.et_yt_search);
        rvResults         = findViewById(R.id.rv_yt_search_results);
        layoutHistory     = findViewById(R.id.layout_yt_search_history);
        layoutFilters     = findViewById(R.id.layout_yt_search_filters);
        layoutSuggestions = findViewById(R.id.layout_yt_search_suggestions);

        ImageButton btnBack = findViewById(R.id.btn_yt_search_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(adapter);

        setupFilters();
        loadSearchHistory();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String query = s.toString().trim();
                if (query.length() >= 1) {
                    showSuggestions(query);
                    if (query.length() >= 2) search(query);
                } else {
                    clearResults();
                    loadSearchHistory();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            String q = etSearch.getText().toString().trim();
            if (!q.isEmpty()) { saveSearchHistory(q); search(q); }
            return true;
        });

        etSearch.requestFocus();

        String preQuery = getIntent().getStringExtra("query");
        if (preQuery != null && !preQuery.isEmpty()) {
            etSearch.setText(preQuery);
            search(preQuery);
        }
    }

    private void setupFilters() {
        if (layoutFilters == null) return;
        String[] filterIds = {"filter_all", "filter_videos", "filter_shorts",
                              "filter_channels", "sort_date", "sort_views"};
        String[] filterValues = {"all", "videos", "shorts", "channels", "date", "views"};
        for (int i = 0; i < filterIds.length; i++) {
            int idx = i;
            View chip = layoutFilters.findViewWithTag(filterIds[i]);
            if (chip != null) chip.setOnClickListener(v -> {
                if (idx < 4) activeFilter = filterValues[idx];
                else         activeSort   = filterValues[idx];
                highlightActiveFilter();
                String q = etSearch.getText().toString().trim();
                if (q.length() >= 2) search(q);
            });
        }
    }

    private void highlightActiveFilter() {
        // Visual update done via setSelected on chips — handled in layout
    }

    private void showSuggestions(String partial) {
        if (layoutSuggestions == null) return;
        YouTubeFirebaseUtils.searchHistoryRef(myUid)
            .orderByValue().limitToLast(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    layoutSuggestions.removeAllViews();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String q = ds.getValue(String.class);
                        if (q != null && q.toLowerCase().contains(partial.toLowerCase())) {
                            TextView tv = new TextView(YouTubeSearchActivity.this);
                            tv.setText(q);
                            tv.setPadding(32, 20, 32, 20);
                            tv.setOnClickListener(v -> {
                                etSearch.setText(q);
                                search(q);
                            });
                            layoutSuggestions.addView(tv);
                        }
                    }
                    layoutSuggestions.setVisibility(
                        layoutSuggestions.getChildCount() > 0 ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void search(String query) {
        if (layoutSuggestions != null) layoutSuggestions.setVisibility(View.GONE);
        if (layoutHistory     != null) layoutHistory.setVisibility(View.GONE);
        if (layoutFilters     != null) layoutFilters.setVisibility(View.VISIBLE);

        String lower = query.toLowerCase();
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(200)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> results = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v == null || !"public".equals(v.visibility)) continue;
                        boolean matchTitle    = v.title != null && v.title.toLowerCase().contains(lower);
                        boolean matchTags     = v.tags  != null && v.tags.toLowerCase().contains(lower);
                        boolean matchCategory = v.category != null && v.category.toLowerCase().contains(lower);
                        boolean matchDesc     = v.description != null && v.description.toLowerCase().contains(lower);
                        if (!matchTitle && !matchTags && !matchCategory && !matchDesc) continue;

                        // Filter
                        if ("videos".equals(activeFilter)   &&  v.isShort) continue;
                        if ("shorts".equals(activeFilter)   && !v.isShort) continue;

                        results.add(v);
                    }

                    // Sort
                    switch (activeSort) {
                        case "date":  results.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt)); break;
                        case "views": results.sort((a, b) -> Long.compare(b.viewCount, a.viewCount)); break;
                        default:
                            results.sort((a, b) -> {
                                int sa = scoreMatch(a, lower), sb = scoreMatch(b, lower);
                                return Integer.compare(sb, sa);
                            });
                    }
                    adapter.setData(results);
                    saveSearchHistory(query);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private int scoreMatch(YouTubeVideo v, String q) {
        int s = 0;
        if (v.title != null && v.title.toLowerCase().startsWith(q)) s += 5;
        else if (v.title != null && v.title.toLowerCase().contains(q)) s += 3;
        if (v.tags != null && v.tags.toLowerCase().contains(q)) s += 2;
        if (v.description != null && v.description.toLowerCase().contains(q)) s += 1;
        return s;
    }

    private void clearResults() {
        adapter.setData(new ArrayList<>());
        if (layoutFilters != null) layoutFilters.setVisibility(View.GONE);
    }

    private void loadSearchHistory() {
        if (myUid.isEmpty() || layoutHistory == null) return;
        YouTubeFirebaseUtils.searchHistoryRef(myUid)
            .orderByKey().limitToLast(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    layoutHistory.removeAllViews();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String q = ds.getValue(String.class);
                        if (q == null) continue;
                        View row = getLayoutInflater()
                            .inflate(R.layout.item_yt_search_history, layoutHistory, false);
                        TextView tv = row.findViewById(R.id.tv_yt_search_history_query);
                        if (tv != null) tv.setText(q);
                        View btnDel = row.findViewById(R.id.btn_yt_delete_history);
                        if (btnDel != null)
                            btnDel.setOnClickListener(v ->
                                YouTubeFirebaseUtils.searchHistoryRef(myUid)
                                    .child(ds.getKey()).removeValue());
                        row.setOnClickListener(v -> {
                            etSearch.setText(q);
                            search(q);
                        });
                        layoutHistory.addView(row);
                    }
                    layoutHistory.setVisibility(
                        layoutHistory.getChildCount() > 0 ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void saveSearchHistory(String query) {
        if (myUid.isEmpty() || query.isEmpty()) return;
        String key = String.valueOf(System.currentTimeMillis());
        YouTubeFirebaseUtils.searchHistoryRef(myUid).child(key).setValue(query);
    }
}
