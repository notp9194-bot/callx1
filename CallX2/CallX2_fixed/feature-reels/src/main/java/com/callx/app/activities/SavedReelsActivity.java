package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.adapters.SavedReelsAdapter;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

/**
 * SavedReelsActivity — Grid view of all reels the user has bookmarked.
 *
 * Data path: reelSaves/{uid}/{reelId} = true
 * For each saved reelId, fetches the full ReelModel from reels/{reelId}.
 *
 * Features:
 *  ✅ 3-column grid (same as Instagram saved grid)
 *  ✅ Thumbnail + duration badge per cell
 *  ✅ Tap cell → opens ReelPlayerActivity at that reel
 *  ✅ Empty state when no saves
 *  ✅ Real-time — adds/removes cells as user saves/un-saves in other screens
 */
public class SavedReelsActivity extends AppCompatActivity {

    private RecyclerView     rvSaved;
    private SavedReelsAdapter adapter;
    private ProgressBar      progressBar;
    private View             layoutEmpty;

    private final List<ReelModel> savedReels = new ArrayList<>();
    private ValueEventListener    savesListener;
    private String                myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_reels);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Saved Reels");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvSaved     = findViewById(R.id.rv_saved_reels);
        progressBar = findViewById(R.id.progress_saved);
        layoutEmpty = findViewById(R.id.layout_empty_saved);

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            Toast.makeText(this, "Please log in to view saved reels.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        adapter = new SavedReelsAdapter(this, savedReels);
        rvSaved.setLayoutManager(new GridLayoutManager(this, 3));
        rvSaved.setAdapter(adapter);

        loadSavedReels();
    }

    private void loadSavedReels() {
        progressBar.setVisibility(View.VISIBLE);

        savesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                savedReels.clear();
                if (!snap.exists()) {
                    progressBar.setVisibility(View.GONE);
                    showEmpty(true);
                    return;
                }

                final long total = snap.getChildrenCount();
                if (total == 0) {
                    progressBar.setVisibility(View.GONE);
                    showEmpty(true);
                    return;
                }

                final long[] loaded = {0};

                for (DataSnapshot s : snap.getChildren()) {
                    String reelId = s.getKey();
                    if (reelId == null) { loaded[0]++; continue; }

                    FirebaseUtils.getReelsRef().child(reelId)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot reelSnap) {
                                if (isFinishing() || isDestroyed()) return;
                                ReelModel reel = reelSnap.getValue(ReelModel.class);
                                if (reel != null) {
                                    if (reel.reelId == null) reel.reelId = reelSnap.getKey();
                                    savedReels.add(reel);
                                }
                                loaded[0]++;
                                if (loaded[0] >= total) onAllLoaded();
                            }

                            @Override
                            public void onCancelled(@NonNull DatabaseError e) {
                                loaded[0]++;
                                if (loaded[0] >= total) onAllLoaded();
                            }
                        });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                if (isFinishing() || isDestroyed()) return;
                progressBar.setVisibility(View.GONE);
                Toast.makeText(SavedReelsActivity.this, "Failed to load saved reels.",
                    Toast.LENGTH_SHORT).show();
            }
        };

        FirebaseUtils.getReelSavesRef(myUid).addValueEventListener(savesListener);
    }

    private void onAllLoaded() {
        if (isFinishing() || isDestroyed()) return;
        progressBar.setVisibility(View.GONE);
        if (savedReels.isEmpty()) {
            showEmpty(true);
        } else {
            showEmpty(false);
            adapter.notifyDataSetChanged();
        }
    }

    private void showEmpty(boolean show) {
        layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        rvSaved.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (savesListener != null && myUid != null) {
            FirebaseUtils.getReelSavesRef(myUid).removeEventListener(savesListener);
        }
        super.onDestroy();
    }
}
