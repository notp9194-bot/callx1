package com.callx.app.collab;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.CollabRequestAdapter;
import com.callx.app.models.CollabModel;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CollabInviteActivity — owner selects a creator to invite as co-author.
 * Shows: search bar, contacts/following list, pending invites, invite button.
 *
 * After invite is accepted, the reel shows:
 *   @owner + @coauthor  (both handles visible in feed)
 */
public class CollabInviteActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "reelId";
    public static final String EXTRA_REEL_THUMB = "reelThumb";

    private EditText etSearch;
    private RecyclerView rvUsers, rvPending;
    private TextView tvPendingHeader, tvEmpty;
    private ProgressBar progress;
    private String reelId;
    private CollabManager collabManager;
    private List<CollabModel> pendingList = new ArrayList<>();
    private CollabRequestAdapter pendingAdapter;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_collab_invite);
        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            collabManager = new CollabManager(u.getUid(),
                u.getDisplayName() != null ? u.getDisplayName() : "User",
                u.getPhotoUrl() != null ? u.getPhotoUrl().toString() : "");
        }

        bindViews();
        loadPendingInvites();
        setupSearch();
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void bindViews() {
        etSearch       = findViewById(R.id.et_search_creator);
        rvUsers        = findViewById(R.id.rv_creator_list);
        rvPending      = findViewById(R.id.rv_pending_invites);
        tvPendingHeader= findViewById(R.id.tv_pending_header);
        tvEmpty        = findViewById(R.id.tv_empty_search);
        progress       = findViewById(R.id.progress_collab_invite);

        rvPending.setLayoutManager(new LinearLayoutManager(this));
        pendingAdapter = new CollabRequestAdapter(pendingList, this,
            (collab, action) -> {
                if ("cancel".equals(action)) {
                    collabManager.cancelCollab(collab.collabId, reelId,
                            collab.inviteeUid, (ok, err) -> loadPendingInvites());
                }
            });
        rvPending.setAdapter(pendingAdapter);
    }

    private void loadPendingInvites() {
        FirebaseDatabase.getInstance().getReference("reelCollabs").child(reelId)
            .get().addOnSuccessListener(snap -> {
                pendingList.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    CollabModel m = c.getValue(CollabModel.class);
                    if (m != null && CollabModel.STATUS_PENDING.equals(m.status))
                        pendingList.add(m);
                }
                tvPendingHeader.setVisibility(pendingList.isEmpty() ? View.GONE : View.VISIBLE);
                pendingAdapter.notifyDataSetChanged();
            });
    }

    private void setupSearch() {
        rvUsers.setLayoutManager(new LinearLayoutManager(this));
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (s.length() >= 2) searchUsers(s.toString());
                else tvEmpty.setVisibility(View.VISIBLE);
            }
            public void afterTextChanged(Editable e) {}
        });
    }

    private void searchUsers(String query) {
        progress.setVisibility(View.VISIBLE);
        FirebaseDatabase.getInstance().getReference("users")
            .orderByChild("name")
            .startAt(query).endAt(query + "\uf8ff")
            .limitToFirst(20)
            .get().addOnSuccessListener(snap -> {
                progress.setVisibility(View.GONE);
                List<String[]> results = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid   = c.getKey();
                    String name  = c.child("name").getValue(String.class);
                    String photo = c.child("photo").getValue(String.class);
                    if (uid != null && name != null) {
                        results.add(new String[]{uid, name, photo != null ? photo : ""});
                    }
                }
                showUserResults(results);
            });
    }

    private void showUserResults(List<String[]> results) {
        tvEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        // Simple ArrayAdapter for demo; replace with custom adapter for avatars
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1);
        for (String[] r : results) adapter.add(r[1]);

        ListView lv = new ListView(this);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            String[] user = results.get(pos);
            sendInvite(user[0], user[1], user[2]);
        });
        // Attach to rvUsers container (simplified — use RecyclerView adapter in prod)
    }

    private void sendInvite(String inviteeUid, String inviteeName, String inviteePhoto) {
        progress.setVisibility(View.VISIBLE);
        collabManager.sendCollabInvite(reelId, inviteeUid, inviteeName, inviteePhoto,
            (ok, err) -> {
                progress.setVisibility(View.GONE);
                if (ok) {
                    Toast.makeText(this, "Collab invite sent to " + inviteeName,
                            Toast.LENGTH_SHORT).show();
                    loadPendingInvites();
                } else {
                    Toast.makeText(this, "Error: " + err, Toast.LENGTH_SHORT).show();
                }
            });
    }
}
