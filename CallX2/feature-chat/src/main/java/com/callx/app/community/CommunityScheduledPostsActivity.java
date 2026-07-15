package com.callx.app.community;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityScheduledPostEntity;
import com.callx.app.repository.CommunityRepository;

/**
 * v31: Manage scheduled posts — view and cancel pending scheduled posts.
 */
public class CommunityScheduledPostsActivity extends AppCompatActivity
        implements CommunityScheduledPostAdapter.Listener {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private RecyclerView rvScheduled;
    private View layoutEmpty;
    private CommunityScheduledPostAdapter adapter;
    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_scheduled_posts);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvScheduled  = findViewById(R.id.rv_scheduled);
        layoutEmpty  = findViewById(R.id.layout_empty_scheduled);

        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvScheduled.setLayoutManager(llm);
        rvScheduled.setHasFixedSize(false);
        rvScheduled.setItemAnimator(null);

        adapter = new CommunityScheduledPostAdapter(this);
        rvScheduled.setAdapter(adapter);

        if (communityId != null) {
            repo.observeScheduledPosts(communityId).observe(this, posts -> {
                adapter.submitList(posts);
                boolean empty = posts == null || posts.isEmpty();
                rvScheduled.setVisibility(empty ? View.GONE : View.VISIBLE);
                layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }
    }

    @Override
    public void onCancelClicked(CommunityScheduledPostEntity post) {
        new AlertDialog.Builder(this)
                .setTitle("Cancel Scheduled Post")
                .setMessage("Cancel this scheduled post? It won't be published.")
                .setPositiveButton("Cancel Post", (d, w) ->
                        repo.cancelScheduledPost(post.id,
                                (success, error) -> runOnUiThread(() -> {
                                    if (!success)
                                        Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                                })))
                .setNegativeButton("Keep", null)
                .show();
    }
}
