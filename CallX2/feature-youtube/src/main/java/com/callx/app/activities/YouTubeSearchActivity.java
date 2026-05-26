package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * Production search: search history display, live type-ahead, clear history,
 * recent searches as chips, filter by title + tags + channel name.
 */
public class YouTubeSearchActivity extends AppCompatActivity {

    private EditText           etSearch;
    private RecyclerView       rvResults;
    private LinearLayout       llHistory;
    private View               viewHistoryContainer;
    private YouTubeVideoAdapter adapter;
    private String myUid;
    private final List<String> searchHistory = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_search);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        etSearch             = findViewById(R.id.et_yt_search);
        rvResults            = findViewById(R.id.rv_yt_search_results);
        llHistory            = findViewById(R.id.ll_yt_search_history);
        viewHistoryContainer = findViewById(R.id.v_yt_history_container);

        ImageButton btnBack  = findViewById(R.id.btn_yt_search_back);
        View        btnClear = findViewById(R.id.btn_yt_clear_history);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnClear != null)
            btnClear.setOnClickListener(v -> {
                if (!myUid.isEmpty()) YouTubeFirebaseUtils.searchHistoryRef(myUid).removeValue();
                llHistory.removeAllViews();
                if (viewHistoryContainer != null) viewHistoryContainer.setVisibility(View.GONE);
            });

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(adapter);

        loadSearchHistory();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim();
                if (q.length() >= 2) {
                    if (viewHistoryContainer != null) viewHistoryContainer.setVisibility(View.GONE);
                    search(q);
                } else if (q.isEmpty()) {
                    adapter.setData(new ArrayList<>());
                    if (viewHistoryContainer != null && !searchHistory.isEmpty())
                        viewHistoryContainer.setVisibility(View.VISIBLE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            String q = etSearch.getText().toString().trim();
            if (!q.isEmpty()) { search(q); saveSearchHistory(q); }
            return true;
        });

        etSearch.requestFocus();
    }

    private void loadSearchHistory() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.searchHistoryRef(myUid)
            .orderByKey().limitToLast(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    searchHistory.clear();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String q = ds.getValue(String.class);
                        if (q != null && !q.isEmpty()) searchHistory.add(0, q);
                    }
                    buildHistoryChips();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void buildHistoryChips() {
        if (llHistory == null) return;
        llHistory.removeAllViews();
        if (searchHistory.isEmpty()) {
            if (viewHistoryContainer != null) viewHistoryContainer.setVisibility(View.GONE);
            return;
        }
        if (viewHistoryContainer != null) viewHistoryContainer.setVisibility(View.VISIBLE);
        for (String q : searchHistory) {
            View row = LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_1, llHistory, false);
            TextView tv = row.findViewById(android.R.id.text1);
            tv.setText(q);
            tv.setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.ic_menu_recent_history, 0, 0, 0);
            tv.setCompoundDrawablePadding(dp(8));
            row.setOnClickListener(v -> {
                etSearch.setText(q);
                etSearch.setSelection(q.length());
                search(q);
            });
            llHistory.addView(row);
        }
    }

    private void search(String query) {
        String lower = query.toLowerCase();
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(200)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> results = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v == null || !"public".equals(v.visibility)) continue;
                        boolean matches =
                            (v.title       != null && v.title.toLowerCase().contains(lower))
                         || (v.tags        != null && v.tags.toLowerCase().contains(lower))
                         || (v.uploaderName!= null && v.uploaderName.toLowerCase().contains(lower))
                         || (v.category   != null && v.category.toLowerCase().contains(lower));
                        if (matches) results.add(0, v);
                    }
                    adapter.setData(results);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void saveSearchHistory(String query) {
        if (myUid.isEmpty()) return;
        String key = String.valueOf(System.currentTimeMillis());
        YouTubeFirebaseUtils.searchHistoryRef(myUid).child(key).setValue(query);
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }
}
