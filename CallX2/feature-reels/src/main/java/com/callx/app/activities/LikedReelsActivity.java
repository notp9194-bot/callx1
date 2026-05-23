package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.adapters.ReelGridAdapter;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LikedReelsActivity — Grid of all reels the current user has liked.
 *
 * Data path: reelLikedByUser/{uid}/{reelId} = timestamp
 *
 * Features:
 *  ✅ 3-column grid thumbnail view
 *  ✅ Tap → opens SingleReelPlayerActivity
 *  ✅ Empty state when no likes
 */
public class LikedReelsActivity extends AppCompatActivity {

    private RecyclerView  rvLiked;
    private ProgressBar   progressBar;
    private View          layoutEmpty;

    private ReelGridAdapter       adapter;
    private final List<ReelModel> liked  = new ArrayList<>();
    private String                myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_liked_reels);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Liked Reels");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        rvLiked     = findViewById(R.id.rv_liked_reels);
        progressBar = findViewById(R.id.progress_liked);
        layoutEmpty = findViewById(R.id.layout_liked_empty);

        adapter = new ReelGridAdapter(this, liked, position -> {
            ReelModel reel = liked.get(position);
            Intent intent = new Intent(this, SingleReelPlayerActivity.class);
            intent.putExtra("reel_id", reel.reelId);
            startActivity(intent);
        });
        rvLiked.setLayoutManager(new GridLayoutManager(this, 3));
        rvLiked.setAdapter(adapter);

        loadLikedReels();
    }

    private void loadLikedReels() {
        progressBar.setVisibility(View.VISIBLE);

        FirebaseUtils.getReelLikedByUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.exists() || snap.getChildrenCount() == 0) {
                        progressBar.setVisibility(View.GONE);
                        layoutEmpty.setVisibility(View.VISIBLE);
                        return;
                    }

                    long count   = snap.getChildrenCount();
                    AtomicInteger loaded = new AtomicInteger(0);

                    for (DataSnapshot child : snap.getChildren()) {
                        String reelId = child.getKey();
                        if (reelId == null) { loaded.incrementAndGet(); continue; }

                        FirebaseUtils.getReelsRef().child(reelId)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot rSnap) {
                                    if (rSnap.exists()) {
                                        ReelModel reel = rSnap.getValue(ReelModel.class);
                                        if (reel != null) liked.add(reel);
                                    }
                                    if (loaded.incrementAndGet() == count) onLoadDone();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (loaded.incrementAndGet() == count) onLoadDone();
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(LikedReelsActivity.this,
                        "Failed to load liked reels", Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void onLoadDone() {
        adapter.notifyDataSetChanged();
        progressBar.setVisibility(View.GONE);
        boolean empty = liked.isEmpty();
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvLiked.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
