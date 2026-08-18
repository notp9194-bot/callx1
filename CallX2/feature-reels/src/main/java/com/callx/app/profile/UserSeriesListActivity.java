package com.callx.app.profile;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.models.DuetSeriesModel;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * UserSeriesListActivity — Shows a user's Duet Series in a 2-column grid.
 * Opened from the "Series" button on the profile screen.
 *
 * Intent extras:
 *   "uid"  — user whose series to load
 *   "name" — display name for toolbar title
 */
public class UserSeriesListActivity extends AppCompatActivity {

    public static final String EXTRA_UID  = "uid";
    public static final String EXTRA_NAME = "name";

    private RecyclerView rvSeries;
    private ProgressBar progressBar;
    private View layoutEmpty;
    private TextView tvEmptyTitle, tvEmptySubtitle;

    private String targetUid;
    private String targetName;

    private UserSeriesGridAdapter adapter;
    private final List<DuetSeriesModel> seriesData = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_series_list);

        targetUid  = getIntent().getStringExtra(EXTRA_UID);
        targetName = getIntent().getStringExtra(EXTRA_NAME);

        if (targetUid == null || targetUid.isEmpty()) { finish(); return; }

        // Toolbar back button
        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Title
        TextView tvTitle = findViewById(R.id.tv_title);
        if (tvTitle != null) {
            tvTitle.setText(targetName != null && !targetName.isEmpty()
                ? targetName + "'s Series" : "Series");
        }

        rvSeries      = findViewById(R.id.rv_series);
        progressBar   = findViewById(R.id.progress_bar);
        layoutEmpty   = findViewById(R.id.layout_empty);
        tvEmptyTitle  = findViewById(R.id.tv_empty_title);
        tvEmptySubtitle = findViewById(R.id.tv_empty_subtitle);

        adapter = new UserSeriesGridAdapter(this);
        adapter.setOnSeriesClickListener(series -> {
            Intent i = new Intent(this, com.callx.app.social.DuetSeriesActivity.class);
            i.putExtra(com.callx.app.social.DuetSeriesActivity.EXTRA_SERIES_ID, series.seriesId);
            startActivity(i);
        });

        rvSeries.setLayoutManager(new GridLayoutManager(this, 2));
        rvSeries.setAdapter(adapter);

        loadSeries();
    }

    private void loadSeries() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);

        com.google.firebase.database.FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("userDuetSeries")
            .child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot titlesSnap) {
                    if (isFinishing() || isDestroyed()) return;

                    if (!titlesSnap.exists() || titlesSnap.getChildrenCount() == 0) {
                        showEmpty();
                        return;
                    }

                    List<DuetSeriesModel> fetched = new ArrayList<>();
                    long[] remaining = {titlesSnap.getChildrenCount()};

                    for (DataSnapshot s : titlesSnap.getChildren()) {
                        String seriesId = s.getKey();
                        if (seriesId == null) { remaining[0]--; continue; }

                        com.google.firebase.database.FirebaseDatabase.getInstance(Constants.DB_URL)
                            .getReference("duetSeries").child(seriesId)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(@NonNull DataSnapshot seriesSnap) {
                                    if (isFinishing() || isDestroyed()) return;
                                    DuetSeriesModel m = seriesSnap.getValue(DuetSeriesModel.class);
                                    if (m != null) fetched.add(m);
                                    remaining[0]--;
                                    if (remaining[0] <= 0) onAllFetched(fetched);
                                }
                                @Override
                                public void onCancelled(@NonNull DatabaseError e) {
                                    remaining[0]--;
                                    if (remaining[0] <= 0) onAllFetched(fetched);
                                }
                            });
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    showEmpty();
                }
            });
    }

    private void onAllFetched(List<DuetSeriesModel> fetched) {
        if (isFinishing() || isDestroyed()) return;
        fetched.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        seriesData.clear();
        seriesData.addAll(fetched);
        adapter.setItems(seriesData);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (fetched.isEmpty()) {
            showEmpty();
        } else {
            if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
            if (rvSeries   != null) rvSeries.setVisibility(View.VISIBLE);
        }
    }

    private void showEmpty() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (layoutEmpty != null) layoutEmpty.setVisibility(View.VISIBLE);
        if (rvSeries    != null) rvSeries.setVisibility(View.GONE);
        if (tvEmptyTitle    != null) tvEmptyTitle.setText("No Series Yet");
        if (tvEmptySubtitle != null) tvEmptySubtitle.setText("This creator hasn't started a Duet Series yet.");
    }
}
