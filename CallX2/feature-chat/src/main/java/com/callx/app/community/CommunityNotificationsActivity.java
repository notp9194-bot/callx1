package com.callx.app.community;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.community.canvas.CommunityScrollOptimizer;
import com.callx.app.db.entity.CommunityNotificationEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

/**
 * v31: In-app community notifications screen.
 * Shows recent community activity notifications for the current user.
 */
public class CommunityNotificationsActivity extends AppCompatActivity
        implements CommunityNotificationAdapter.Listener {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;

    private RecyclerView rvNotifications;
    private View layoutEmpty;
    private CommunityNotificationAdapter adapter;
    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_notifications);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        currentUid  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvNotifications = findViewById(R.id.rv_notifications);
        layoutEmpty     = findViewById(R.id.layout_empty_notifications);

        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvNotifications.setLayoutManager(llm);
        rvNotifications.setHasFixedSize(false);
        rvNotifications.setItemAnimator(null);
        CommunityScrollOptimizer.apply(rvNotifications, llm);
        CommunityScrollOptimizer.applySharedPool(rvNotifications);

        adapter = new CommunityNotificationAdapter(this);
        rvNotifications.setAdapter(adapter);

        if (currentUid != null) {
            repo.observeNotificationsForCommunity(currentUid, communityId).observe(this, notifs -> {
                adapter.submitList(notifs);
                boolean empty = notifs == null || notifs.isEmpty();
                rvNotifications.setVisibility(empty ? View.GONE : View.VISIBLE);
                layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_mark_all_read, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_mark_all_read) {
            if (currentUid != null) repo.markAllNotificationsRead(currentUid);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onNotificationClicked(CommunityNotificationEntity notif) {
        repo.markNotificationRead(notif.id);
        // LiveData observer in onCreate re-submits the updated list automatically;
        // notifyDataSetChanged() was O(N) and caused full-row flicker — removed.
    }
}
