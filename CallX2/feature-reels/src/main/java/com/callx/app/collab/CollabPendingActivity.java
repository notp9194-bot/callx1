package com.callx.app.collab;

import com.callx.app.R;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.CollabRequestAdapter;
import com.callx.app.models.CollabModel;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.util.ArrayList;
import java.util.List;

/**
 * CollabPendingActivity — the invitee's inbox.
 * Shows all pending collab invites with Accept / Reject buttons.
 * After accepting, the reel appears in the invitee's profile grid automatically.
 */
public class CollabPendingActivity extends AppCompatActivity {

    private RecyclerView rv;
    private CollabRequestAdapter adapter;
    private List<CollabModel> pendingList = new ArrayList<>();
    private TextView tvEmpty, tvHeader;
    private ProgressBar progress;
    private CollabManager collabManager;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_collab_pending);

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            collabManager = new CollabManager(u.getUid(),
                u.getDisplayName() != null ? u.getDisplayName() : "User",
                u.getPhotoUrl() != null ? u.getPhotoUrl().toString() : "");
        }

        rv       = findViewById(R.id.rv_pending_collabs);
        tvEmpty  = findViewById(R.id.tv_empty_pending);
        tvHeader = findViewById(R.id.tv_pending_count);
        progress = findViewById(R.id.progress_pending);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CollabRequestAdapter(pendingList, this, (collab, action) -> {
            if ("accept".equals(action)) {
                collabManager.acceptCollab(collab.collabId, collab.reelId,
                        collab.ownerUid, (ok, err) -> {
                            if (ok) {
                                Toast.makeText(this, "Collab accepted! Reel added to your profile.",
                                        Toast.LENGTH_SHORT).show();
                                loadPending();
                            }
                        });
            } else if ("reject".equals(action)) {
                collabManager.rejectCollab(collab.collabId, collab.reelId,
                        collab.ownerUid, (ok, err) -> {
                            if (ok) loadPending();
                        });
            }
        });
        rv.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        loadPending();
    }

    private void loadPending() {
        progress.setVisibility(View.VISIBLE);
        collabManager.getPendingCollabs(list -> {
            pendingList.clear();
            pendingList.addAll(list);
            tvHeader.setText(list.size() + " pending collab invite" + (list.size() == 1 ? "" : "s"));
            adapter.notifyDataSetChanged();
            progress.setVisibility(View.GONE);
            tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }
}
