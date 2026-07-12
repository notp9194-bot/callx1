package com.callx.app.channels;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.status.R;

import java.util.Arrays;

/** Opened when the user taps a channel row on the Updates screen — shows the channel's
 *  broadcast posts and lets the user follow/unfollow from here too. Marks the channel
 *  as read (clears its unread badge) as soon as it opens. */
public class ChannelViewActivity extends AppCompatActivity {

    private static final String EXTRA_CHANNEL_ID = "channel_id";

    public static Intent intent(Context ctx, String channelId) {
        Intent i = new Intent(ctx, ChannelViewActivity.class);
        i.putExtra(EXTRA_CHANNEL_ID, channelId);
        return i;
    }

    private ChannelsRepository repo;
    private ChannelItem channel;
    private TextView btnFollow;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_view);

        String id = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        repo = new ChannelsRepository(this);
        channel = id != null ? repo.get(id) : null;
        if (channel == null) { finish(); return; }

        repo.markRead(channel.id);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(channel.name);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView tvIcon = findViewById(R.id.tv_channel_icon);
        TextView tvName = findViewById(R.id.tv_channel_name);
        TextView tvFollowers = findViewById(R.id.tv_channel_followers);
        ImageView ivVerified = findViewById(R.id.iv_channel_verified);
        btnFollow = findViewById(R.id.btn_follow);

        tvIcon.setText(ChannelsUi.initial(channel.name));
        tvIcon.getBackground().setTint(ChannelsUi.colorFor(channel.name));
        tvName.setText(channel.name);
        tvFollowers.setText(channel.followerCountLabel());
        ivVerified.setVisibility(channel.verified ? View.VISIBLE : View.GONE);
        refreshFollowButton();
        btnFollow.setOnClickListener(v -> {
            repo.setFollowing(channel.id, !channel.following);
            refreshFollowButton();
        });

        RecyclerView rv = findViewById(R.id.rv_channel_posts);
        TextView tvEmpty = findViewById(R.id.tv_empty_posts);
        if (channel.posts == null || channel.posts.length == 0) {
            rv.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new ChannelPostsAdapter(Arrays.asList(channel.posts),
                    channel.lastPostAtMillis > 0 ? channel.lastPostAtMillis : System.currentTimeMillis()));
        }
    }

    private void refreshFollowButton() {
        ChannelsUi.applyFollowButtonStyle(btnFollow, channel.following);
    }
}
