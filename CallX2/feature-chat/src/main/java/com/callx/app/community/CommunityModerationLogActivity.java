package com.callx.app.community;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.repository.CommunityRepository;

/**
 * v31: Moderation log activity — shows admin action history for the community.
 * Only accessible to admins/owners.
 */
public class CommunityModerationLogActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private RecyclerView rvLog;
    private View layoutEmpty;
    private CommunityModerationLogAdapter adapter;
    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_moderation_log);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvLog        = findViewById(R.id.rv_moderation_log);
        layoutEmpty  = findViewById(R.id.layout_empty_log);

        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvLog.setLayoutManager(llm);
        rvLog.setHasFixedSize(false);
        rvLog.setItemAnimator(null);

        adapter = new CommunityModerationLogAdapter();
        rvLog.setAdapter(adapter);

        if (communityId != null) {
            repo.observeModerationLog(communityId).observe(this, logs -> {
                adapter.submitList(logs);
                boolean empty = logs == null || logs.isEmpty();
                rvLog.setVisibility(empty ? View.GONE : View.VISIBLE);
                layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }
    }
}
