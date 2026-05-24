package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.HorizontalScrollView;
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
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/** Trending videos with category filter chips. */
public class YouTubeTrendingActivity extends AppCompatActivity {

    private RecyclerView        rvTrending;
    private YouTubeVideoAdapter adapter;
    private LinearLayout        layoutChips;
    private String              activeCategory = "all";

    private static final String[] CATEGORIES = {
        "all", "Music", "Gaming", "News", "Sports",
        "Education", "Comedy", "Tech", "Entertainment", "Cooking"
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_trending);

        View btnBack = findViewById(R.id.btn_yt_trending_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        TextView tvTitle = findViewById(R.id.tv_yt_trending_title);
        if (tvTitle != null) tvTitle.setText("Trending");

        rvTrending = findViewById(R.id.rv_yt_trending_list);
        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(this));
        rvTrending.setAdapter(adapter);

        layoutChips = findViewById(R.id.layout_yt_trending_chips);
        buildCategoryChips();
        loadTrending();
    }

    private void buildCategoryChips() {
        if (layoutChips == null) return;
        for (String cat : CATEGORIES) {
            TextView chip = new TextView(this);
            chip.setText("all".equals(cat) ? "All" : cat);
            chip.setTag(cat);
            chip.setPadding(32, 16, 32, 16);
            chip.setBackgroundResource(R.drawable.bg_yt_chip);
            chip.setOnClickListener(v -> {
                activeCategory = (String) v.getTag();
                loadTrending();
                highlightChip(v);
            });
            layoutChips.addView(chip);
        }
    }

    private void highlightChip(View selected) {
        for (int i = 0; i < layoutChips.getChildCount(); i++) {
            View c = layoutChips.getChildAt(i);
            c.setSelected(c == selected);
        }
    }

    private void loadTrending() {
        DatabaseReference ref = "all".equals(activeCategory)
            ? YouTubeFirebaseUtils.globalFeedRef()
            : YouTubeFirebaseUtils.categoryFeedRef(activeCategory);

        ref.orderByChild("viewCount").limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null && "public".equals(v.visibility)) list.add(v);
                    }
                    list.sort((a, b) -> Long.compare(b.viewCount, a.viewCount));
                    adapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }
}
