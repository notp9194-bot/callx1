package com.callx.app.repost;

import com.callx.app.R;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.database.*;
import java.util.*;

/**
 * Repost Analytics — shows owner who reposted, when, with what caption, repost type.
 * Also shows repost chain depth and viral milestones.
 */
public class RepostAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID = "reelId";

    private String reelId;
    private TextView tvTotalReposts, tvSimpleCount, tvQuoteCount,
                     tvStoryCount, tvViralBadge, tvTopReposter;
    private ProgressBar progress;
    private RecyclerView rvTimeline;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_repost_analytics);
        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);

        bindViews();
        loadAnalytics();
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void bindViews() {
        tvTotalReposts = findViewById(R.id.tv_total_reposts);
        tvSimpleCount  = findViewById(R.id.tv_simple_count);
        tvQuoteCount   = findViewById(R.id.tv_quote_count);
        tvStoryCount   = findViewById(R.id.tv_story_count);
        tvViralBadge   = findViewById(R.id.tv_viral_badge);
        tvTopReposter  = findViewById(R.id.tv_top_reposter);
        progress       = findViewById(R.id.progress_analytics);
        rvTimeline     = findViewById(R.id.rv_repost_timeline);
        rvTimeline.setLayoutManager(new LinearLayoutManager(this));
    }

    private void loadAnalytics() {
        progress.setVisibility(View.VISIBLE);
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("reelReposts").child(reelId);

        ref.get().addOnSuccessListener(snap -> {
            progress.setVisibility(View.GONE);
            int total = 0, simple = 0, quote = 0, story = 0;
            Map<String, Integer> reposterFreq = new HashMap<>();

            for (DataSnapshot child : snap.getChildren()) {
                total++;
                String type = child.child("repostType").getValue(String.class);
                String name = child.child("reposterName").getValue(String.class);
                if ("quote".equals(type))  quote++;
                else if ("story".equals(type)) story++;
                else simple++;
                if (name != null) {
                    reposterFreq.put(name, reposterFreq.getOrDefault(name, 0) + 1);
                }
            }

            tvTotalReposts.setText(String.valueOf(total));
            tvSimpleCount.setText(simple + " Simple");
            tvQuoteCount.setText(quote + " Quote");
            tvStoryCount.setText(story + " Story");

            // Viral badge check
            String badge = total >= 1000 ? "🔥 VIRAL (1K+)"
                         : total >= 500  ? "🚀 Trending (500+)"
                         : total >= 100  ? "⭐ Hot (100+)"
                         : "—";
            tvViralBadge.setText(badge);

            // Top reposter
            String topName = "—";
            int topCount = 0;
            for (Map.Entry<String, Integer> e : reposterFreq.entrySet()) {
                if (e.getValue() > topCount) { topCount = e.getValue(); topName = e.getKey(); }
            }
            tvTopReposter.setText(topName);
        });
    }
}
