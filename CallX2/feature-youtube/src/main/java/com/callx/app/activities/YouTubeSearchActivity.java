package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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

public class YouTubeSearchActivity extends AppCompatActivity {

    private EditText           etSearch;
    private RecyclerView       rvResults;
    private YouTubeVideoAdapter adapter;
    private String myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_search);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        etSearch  = findViewById(R.id.et_yt_search);
        rvResults = findViewById(R.id.rv_yt_search_results);

        ImageButton btnBack = findViewById(R.id.btn_yt_search_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (s.length() >= 2) search(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        etSearch.requestFocus();
    }

    private void search(String query) {
        String lower = query.toLowerCase();
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(100)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> results = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null && v.title != null &&
                            v.title.toLowerCase().contains(lower))
                            results.add(0, v);
                    }
                    adapter.setData(results);
                    saveSearchHistory(query);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void saveSearchHistory(String query) {
        if (myUid.isEmpty()) return;
        String key = String.valueOf(System.currentTimeMillis());
        YouTubeFirebaseUtils.searchHistoryRef(myUid).child(key).setValue(query);
    }
}
