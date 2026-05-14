package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.callx.app.reels.R;
import com.callx.app.adapters.ReelsAdapter;
import com.callx.app.fragments.ReelPlayerFragment;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HashtagReelsActivity — Kisi ek hashtag ke saare reels ka full-screen feed.
 *
 * Usage:
 *   Intent i = new Intent(context, HashtagReelsActivity.class);
 *   i.putExtra(EXTRA_HASHTAG, "dance");   // # without the # sign
 *   startActivity(i);
 *
 * Kaise open hota hai:
 *   ReelPlayerFragment mein hashtag chip click → HashtagReelsActivity
 *
 * Features:
 *  ✅ Full-screen vertical ViewPager2 (bilkul ReelsFragment jaisa)
 *  ✅ Top bar mein #hashtag + reel count
 *  ✅ Trending sort — most liked/viewed reels pehle
 *  ✅ Real-time Firebase listener
 *  ✅ Empty state agar koi reel nahi
 *  ✅ offscreenPageLimit=2 for smooth scrolling
 */
public class HashtagReelsActivity extends AppCompatActivity {

    public static final String EXTRA_HASHTAG = "hashtag";

    private ViewPager2   vpReels;
    private ReelsAdapter adapter;
    private ProgressBar  progressBar;
    private View         layoutEmpty;
    private TextView     tvHashtag, tvReelCount;

    private String hashtag;
    private final List<ReelModel>  allReels = new ArrayList<>();
    private ValueEventListener     reelsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hashtag_reels);

        hashtag = getIntent().getStringExtra(EXTRA_HASHTAG);
        if (hashtag == null || hashtag.isEmpty()) { finish(); return; }

        vpReels      = findViewById(R.id.vp_reels);
        progressBar  = findViewById(R.id.progress_bar);
        layoutEmpty  = findViewById(R.id.layout_empty);
        tvHashtag    = findViewById(R.id.tv_hashtag);
        tvReelCount  = findViewById(R.id.tv_reel_count);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tvHashtag.setText("#" + hashtag);

        adapter = new ReelsAdapter(this);
        vpReels.setAdapter(adapter);
        vpReels.setOffscreenPageLimit(2);

        vpReels.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                controlPlayback(position);
            }
        });

        loadHashtagReels();
    }

    private void loadHashtagReels() {
        progressBar.setVisibility(View.VISIBLE);

        reelsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                allReels.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel reel = s.getValue(ReelModel.class);
                    if (reel == null) continue;
                    if (reel.reelId == null) reel.reelId = s.getKey();

                    // Check if this reel has our hashtag
                    if (reelHasHashtag(reel, hashtag)) {
                        allReels.add(reel);
                    }
                }

                // Sort by trending score
                Collections.sort(allReels, (a, b) ->
                    Float.compare(b.trendingScore(), a.trendingScore()));

                progressBar.setVisibility(View.GONE);

                if (allReels.isEmpty()) {
                    showEmpty(true);
                } else {
                    showEmpty(false);
                    tvReelCount.setText(allReels.size() + " Reels");
                    adapter.setReels(allReels);
                    controlPlayback(0);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(HashtagReelsActivity.this,
                    "Failed to load reels", Toast.LENGTH_SHORT).show();
            }
        };

        FirebaseUtils.getReelsRef()
            .orderByChild("timestamp")
            .addValueEventListener(reelsListener);
    }

    private boolean reelHasHashtag(ReelModel reel, String tag) {
        if (reel.hashtags != null) {
            for (String t : reel.hashtags) {
                if (tag.equalsIgnoreCase(t)) return true;
            }
        }
        // Also check caption directly
        if (reel.caption != null) {
            return reel.caption.toLowerCase().contains("#" + tag.toLowerCase());
        }
        return false;
    }

    private void controlPlayback(int activePos) {
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Fragment f = getSupportFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(i));
            if (f instanceof ReelPlayerFragment) {
                ((ReelPlayerFragment) f).setUserVisibleHint(i == activePos);
            }
        }
    }

    private void showEmpty(boolean show) {
        layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        vpReels.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) tvReelCount.setText("0 Reels");
    }

    /** Called by ReelPlayerFragment when video ends — advance to next */
    public void advanceToNext() {
        if (vpReels == null) return;
        int next = vpReels.getCurrentItem() + 1;
        if (next < adapter.getItemCount()) vpReels.setCurrentItem(next, true);
    }

    @Override
    protected void onPause() {
        controlPlaybackAll(false);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        controlPlayback(vpReels != null ? vpReels.getCurrentItem() : 0);
    }

    private void controlPlaybackAll(boolean play) {
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Fragment f = getSupportFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(i));
            if (f instanceof ReelPlayerFragment) {
                ((ReelPlayerFragment) f).setUserVisibleHint(play);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (reelsListener != null)
            FirebaseUtils.getReelsRef().removeEventListener(reelsListener);
        super.onDestroy();
    }
}
