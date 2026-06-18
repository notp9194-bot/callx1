package com.callx.app.collab;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import java.util.*;

/**
 * CollabSeriesActivity — Browse, create, and manage collaborative series/playlists.
 * Multiple creators contribute reels to a shared themed collection.
 */
public class CollabSeriesActivity extends AppCompatActivity {

    private RecyclerView rvSeries;
    private TextView tvEmpty, tvHeader;
    private ProgressBar progress;
    private CollabManager collabManager;
    private List<Map<String, Object>> seriesList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_collab_series);

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            collabManager = new CollabManager(u.getUid(),
                u.getDisplayName() != null ? u.getDisplayName() : "User",
                u.getPhotoUrl() != null ? u.getPhotoUrl().toString() : "");
        }

        rvSeries = findViewById(R.id.rv_series);
        tvEmpty  = findViewById(R.id.tv_empty_series);
        tvHeader = findViewById(R.id.tv_series_header);
        progress = findViewById(R.id.progress_series);

        rvSeries.setLayoutManager(new GridLayoutManager(this, 2));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.fab_create_series).setOnClickListener(v -> showCreateDialog());

        loadMySeries();
    }

    private void loadMySeries() {
        progress.setVisibility(View.VISIBLE);
        String uid = FirebaseAuth.getInstance().getUid();
        FirebaseDatabase.getInstance().getReference("collabSeries")
            .orderByChild("creatorUid").equalTo(uid)
            .get().addOnSuccessListener(snap -> {
                progress.setVisibility(View.GONE);
                seriesList.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    Map<String, Object> map = (Map<String, Object>) c.getValue();
                    if (map != null) seriesList.add(map);
                }
                tvEmpty.setVisibility(seriesList.isEmpty() ? View.VISIBLE : View.GONE);
                tvHeader.setText(seriesList.size() + " Series");
            });
    }

    private void showCreateDialog() {
        EditText et = new EditText(this);
        et.setHint("Series title");
        new android.app.AlertDialog.Builder(this)
            .setTitle("New Collab Series")
            .setView(et)
            .setPositiveButton("Create", (d, w) -> {
                String title = et.getText().toString().trim();
                if (title.isEmpty()) return;
                collabManager.createCollabSeries(title, "", (ok, err) -> {
                    if (ok) {
                        Toast.makeText(this, "Series created!", Toast.LENGTH_SHORT).show();
                        loadMySeries();
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
