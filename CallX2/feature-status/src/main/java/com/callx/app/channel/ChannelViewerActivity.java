package com.callx.app.channel;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.models.ChannelPost;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import de.hdodenhof.circleimageview.CircleImageView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;

/**
 * ChannelViewerActivity v2 — WhatsApp-level architecture.
 *
 * CHANGED: Observes ChannelViewModel LiveData for posts instead of calling Firebase directly.
 * Data flow: Firebase → ChannelRepository.syncChannelPosts → Room
 *            → ChannelViewModel.getChannelPosts(channelId) → this UI.
 */
public class ChannelViewerActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID        = "channelId";
    public static final String EXTRA_CHANNEL_NAME      = "channelName";
    public static final String EXTRA_CHANNEL_ICON      = "channelIcon";
    public static final String EXTRA_CHANNEL_VERIFIED  = "channelVerified";
    public static final String EXTRA_CHANNEL_FOLLOWERS = "channelFollowers";

    private String channelId;
    private String channelName;
    private boolean isFollowing = false;

    private ChannelViewModel   viewModel;
    private ChannelPostAdapter postAdapter;
    private MaterialButton     btnFollowToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_viewer);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        String iconUrl   = getIntent().getStringExtra(EXTRA_CHANNEL_ICON);
        boolean verified = getIntent().getBooleanExtra(EXTRA_CHANNEL_VERIFIED, false);
        long followers   = getIntent().getLongExtra(EXTRA_CHANNEL_FOLLOWERS, 0L);

        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_channel);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // ── Header ────────────────────────────────────────────────────────
        TextView tvName      = findViewById(R.id.tv_channel_viewer_name);
        TextView tvVerified  = findViewById(R.id.tv_channel_viewer_verified);
        TextView tvFollowers = findViewById(R.id.tv_channel_viewer_followers);
        CircleImageView ivIcon = findViewById(R.id.iv_channel_viewer_icon);
        btnFollowToggle      = findViewById(R.id.btn_follow_toggle);

        tvName.setText(channelName != null ? channelName : "");
        tvVerified.setVisibility(verified ? View.VISIBLE : View.GONE);
        tvFollowers.setText(formatFollowers(followers));

        if (iconUrl != null && !iconUrl.isEmpty()) {
            Glide.with(this).load(iconUrl)
                .placeholder(R.drawable.bg_channel_avatar_default)
                .circleCrop().override(128, 128).into(ivIcon);
        } else {
            ivIcon.setImageResource(R.drawable.bg_channel_avatar_default);
        }

        // ── Posts list ────────────────────────────────────────────────────
        RecyclerView rv = findViewById(R.id.rv_channel_posts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        postAdapter = new ChannelPostAdapter();
        postAdapter.setOnForwardClick(post -> Toast.makeText(this, "Forwarded!", Toast.LENGTH_SHORT).show());
        postAdapter.setOnReactionClick(post -> showReactionPicker(post));
        rv.setAdapter(postAdapter);

        // ── Observe posts via ViewModel (WhatsApp-level pattern) ──────────
        viewModel.getChannelPosts(channelId).observe(this, posts -> {
            // Convert ChannelPostEntity → ChannelPost for adapter
            List<ChannelPost> models = new ArrayList<>();
            if (posts != null) {
                for (ChannelPostEntity e : posts) {
                    ChannelPost p = new ChannelPost();
                    p.id           = e.id;
                    p.channelId    = e.channelId;
                    p.text         = e.text;
                    p.type         = e.type;
                    p.mediaUrl     = e.mediaUrl;
                    p.thumbnailUrl = e.thumbnailUrl;
                    p.timestamp    = e.timestamp;
                    p.viewCount    = e.viewCount;
                    p.forwardCount = e.forwardCount;
                    models.add(p);
                }
            }
            postAdapter.setPosts(models);
        });

        // ── Observe the channel itself for follow state ───────────────────
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch == null) return;
            isFollowing = ch.isFollowed;
            updateFollowButton();
        });

        // ── Follow toggle ─────────────────────────────────────────────────
        btnFollowToggle.setOnClickListener(v -> toggleFollow());

        // Toast messages from ViewModel
        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty())
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewModel.stopSyncingPosts(channelId);
    }

    private void toggleFollow() {
        viewModel.getChannel(channelId).getValue();
        // Build a minimal ChannelEntity for the follow/unfollow call
        com.callx.app.db.entity.ChannelEntity ch = new com.callx.app.db.entity.ChannelEntity();
        ch.id        = channelId;
        ch.name      = channelName;
        ch.isFollowed= isFollowing;

        if (!isFollowing) {
            viewModel.followChannel(ch);
        } else {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Unfollow " + channelName + "?")
                .setMessage("You'll no longer receive updates from this channel.")
                .setPositiveButton("Unfollow", (d, w) -> viewModel.unfollowChannel(ch))
                .setNegativeButton("Cancel", null)
                .show();
        }
    }

    private void updateFollowButton() {
        btnFollowToggle.setText(isFollowing ? "Following" : "Follow");
    }

    private void showReactionPicker(ChannelPost post) {
        String[] emojis = {"\uD83D\uDC4D","❤️","\uD83D\uDE02","\uD83D\uDE2E","\uD83D\uDE22","\uD83D\uDE4F"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(emojis, (d, which) -> {
                if (post.id != null)
                    viewModel.reactToPost(channelId, post.id, emojis[which]);
            }).show();
    }

    /** Get single channel LiveData — delegates to viewModel. */
    private androidx.lifecycle.LiveData<com.callx.app.db.entity.ChannelEntity> getChannel(String id) {
        return viewModel.getChannel(id);
    }

    private String formatFollowers(long n) {
        if (n >= 1_000_000_000L) return (n / 1_000_000_000L) + "B followers";
        if (n >= 1_000_000L) return String.format("%.1fM followers", n / 1_000_000.0);
        if (n >= 1_000L)     return String.format("%.1fK followers", n / 1_000.0);
        return n + " followers";
    }
}
