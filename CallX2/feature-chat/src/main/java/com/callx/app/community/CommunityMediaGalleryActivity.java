package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;

import java.util.List;

/**
 * v31: Instagram-style grid media gallery — shows all posts with mediaUrl.
 * GridLayoutManager 3 columns.
 */
public class CommunityMediaGalleryActivity extends AppCompatActivity
        implements CommunityMediaGalleryAdapter.Listener {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private RecyclerView rvGallery;
    private View layoutEmpty;
    private CommunityMediaGalleryAdapter adapter;
    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_media_gallery);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Media Gallery");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvGallery   = findViewById(R.id.rv_gallery);
        layoutEmpty = findViewById(R.id.layout_empty_gallery);

        GridLayoutManager glm = new GridLayoutManager(this, 3);
        rvGallery.setLayoutManager(glm);
        rvGallery.setHasFixedSize(true);
        rvGallery.setItemAnimator(null);

        adapter = new CommunityMediaGalleryAdapter(this);
        rvGallery.setAdapter(adapter);

        if (communityId != null) {
            repo.observeMediaPosts(communityId).observe(this, this::onMediaLoaded);
        }
    }

    private void onMediaLoaded(List<CommunityPostEntity> posts) {
        adapter.submitList(posts);
        boolean empty = posts == null || posts.isEmpty();
        rvGallery.setVisibility(empty ? View.GONE : View.VISIBLE);
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (getSupportActionBar() != null && posts != null && !posts.isEmpty()) {
            getSupportActionBar().setSubtitle(posts.size() + " item" + (posts.size() != 1 ? "s" : ""));
        }
    }

    @Override
    public void onMediaClicked(CommunityPostEntity post) {
        // Open full-screen viewer
        // For images: simple fullscreen activity; for video: same viewer
        Intent i = new Intent(this, CommunityFullscreenMediaActivity.class);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_URL, post.mediaUrl);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_TYPE, post.mediaType);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_AUTHOR_NAME, post.authorName);
        startActivity(i);
    }
}
