package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
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
import java.util.List;

/**
 * SingleReelPlayerActivity — Grid se kisi specific reel ko play karna.
 *
 * Grid (UserReelsActivity, SavedReelsActivity, SearchActivity) mein
 * thumbnail tap karne par yeh activity open hoti hai.
 * User us position se scroll kar sakta hai.
 *
 * Usage (uid ke saare reels):
 *   Intent i = new Intent(context, SingleReelPlayerActivity.class);
 *   i.putExtra(EXTRA_UID, "uid123");
 *   i.putExtra(EXTRA_START_POSITION, 3);
 *   i.putExtra(EXTRA_TITLE, "John's Reels");
 *   startActivity(i);
 *
 * Usage (specific reel IDs ki list):
 *   i.putStringArrayListExtra(EXTRA_REEL_IDS, reelIdList);
 *   i.putExtra(EXTRA_START_POSITION, position);
 *   startActivity(i);
 *
 * Features:
 *  ✅ Full-screen vertical ViewPager2 — bilkul ReelsFragment jaisa
 *  ✅ Starting position pe seedha jump karta hai
 *  ✅ Back button
 *  ✅ offscreenPageLimit=2
 */
public class SingleReelPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_UID            = "uid";
    public static final String EXTRA_REEL_IDS       = "reel_ids";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_TITLE          = "title";

    private ViewPager2   vpReels;
    private ReelsAdapter adapter;
    private ProgressBar  progressBar;

    private final List<ReelModel> reels = new ArrayList<>();
    private ValueEventListener    reelsListener;
    private int                   startPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_single_reel_player);

        vpReels     = findViewById(R.id.vp_reels);
        progressBar = findViewById(R.id.progress_bar);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        startPosition = getIntent().getIntExtra(EXTRA_START_POSITION, 0);
        String uid      = getIntent().getStringExtra(EXTRA_UID);
        ArrayList<String> reelIds = getIntent().getStringArrayListExtra(EXTRA_REEL_IDS);

        adapter = new ReelsAdapter(this);
        vpReels.setAdapter(adapter);
        vpReels.setOffscreenPageLimit(2);

        vpReels.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                controlPlayback(position);
            }
        });

        if (reelIds != null && !reelIds.isEmpty()) {
            // Load specific reel IDs
            loadByReelIds(reelIds);
        } else if (uid != null && !uid.isEmpty()) {
            // Load all reels by this user
            loadByUid(uid);
        } else {
            Toast.makeText(this, "No reels to show", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void loadByUid(String uid) {
        progressBar.setVisibility(View.VISIBLE);
        reelsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                reels.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel reel = s.getValue(ReelModel.class);
                    if (reel == null) continue;
                    if (reel.reelId == null) reel.reelId = s.getKey();
                    reels.add(reel);
                }
                onReelsLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
            }
        };
        FirebaseUtils.getReelsRef()
            .orderByChild("uid").equalTo(uid)
            .addListenerForSingleValueEvent(reelsListener);
    }

    private void loadByReelIds(List<String> reelIds) {
        progressBar.setVisibility(View.VISIBLE);
        final int[] loaded = {0};
        final int total = reelIds.size();
        final ReelModel[] tempReels = new ReelModel[total];

        for (int i = 0; i < total; i++) {
            final int idx = i;
            String reelId = reelIds.get(i);
            FirebaseUtils.getReelsRef().child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (isFinishing() || isDestroyed()) return;
                        ReelModel reel = snap.getValue(ReelModel.class);
                        if (reel != null) {
                            if (reel.reelId == null) reel.reelId = snap.getKey();
                            tempReels[idx] = reel;
                        }
                        loaded[0]++;
                        if (loaded[0] >= total) {
                            reels.clear();
                            for (ReelModel r : tempReels) {
                                if (r != null) reels.add(r);
                            }
                            onReelsLoaded();
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        loaded[0]++;
                        if (loaded[0] >= total) onReelsLoaded();
                    }
                });
        }
    }

    private void onReelsLoaded() {
        if (isFinishing() || isDestroyed()) return;
        progressBar.setVisibility(View.GONE);
        if (reels.isEmpty()) { finish(); return; }

        adapter.setReels(reels);

        // Jump to start position
        int safePos = Math.min(startPosition, reels.size() - 1);
        if (safePos > 0) {
            vpReels.setCurrentItem(safePos, false);
        }
        controlPlayback(safePos);
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

    /** Called by ReelPlayerFragment when video ends */
    public void advanceToNext() {
        if (vpReels == null) return;
        int next = vpReels.getCurrentItem() + 1;
        if (next < adapter.getItemCount()) vpReels.setCurrentItem(next, true);
    }

    @Override
    protected void onPause() {
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Fragment f = getSupportFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(i));
            if (f instanceof ReelPlayerFragment) {
                ((ReelPlayerFragment) f).setUserVisibleHint(false);
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vpReels != null) controlPlayback(vpReels.getCurrentItem());
    }

    @Override
    protected void onDestroy() {
        if (reelsListener != null) FirebaseUtils.getReelsRef().removeEventListener(reelsListener);
        super.onDestroy();
    }
}
