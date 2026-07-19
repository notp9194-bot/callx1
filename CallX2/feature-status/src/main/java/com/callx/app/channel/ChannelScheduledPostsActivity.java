package com.callx.app.channel;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ChannelScheduledPostsActivity — WhatsApp-level scheduled post management.
 *
 * Shows all scheduled (not yet published) posts for a channel.
 * Owner/admin can:
 *   - View scheduled post list with scheduled time
 *   - Publish a scheduled post immediately
 *   - Delete / cancel a scheduled post
 *   - View post content preview
 */
public class ChannelScheduledPostsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private ChannelViewModel viewModel;
    private String channelId, channelName;
    private ScheduledAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_scheduled_posts);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_scheduled);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Scheduled posts");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_scheduled_posts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScheduledAdapter();
        rv.setAdapter(adapter);

        View emptyState = findViewById(R.id.layout_scheduled_empty);

        viewModel.getScheduledPosts(channelId).observe(this, posts -> {
            if (posts == null) posts = new ArrayList<>();
            adapter.setPosts(posts);
            if (emptyState != null)
                emptyState.setVisibility(posts.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class ScheduledAdapter extends RecyclerView.Adapter<ScheduledAdapter.VH> {
        private final List<ChannelPostEntity> list = new ArrayList<>();

        void setPosts(List<ChannelPostEntity> posts) {
            list.clear(); list.addAll(posts); notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scheduled_post, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ChannelPostEntity p = list.get(pos);

            // Content preview
            String preview;
            if (p.text != null && !p.text.isEmpty()) {
                preview = p.text.length() > 80 ? p.text.substring(0, 80) + "…" : p.text;
            } else {
                preview = "[" + (p.type != null ? capitalize(p.type) : "Post") + "]";
            }
            h.tvContent.setText(preview);

            // Type label
            h.tvType.setText(p.type != null ? capitalize(p.type) : "Text");

            // Scheduled time
            if (p.scheduledAt > 0) {
                String fmt = new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
                    .format(new Date(p.scheduledAt));
                h.tvScheduledAt.setText("📅 " + fmt);
                long remaining = p.scheduledAt - System.currentTimeMillis();
                if (remaining > 0) {
                    h.tvTimeRemaining.setText(formatRemaining(remaining));
                    h.tvTimeRemaining.setVisibility(View.VISIBLE);
                } else {
                    h.tvTimeRemaining.setVisibility(View.GONE);
                }
            }

            // Publish now
            h.btnPublishNow.setOnClickListener(v ->
                new AlertDialog.Builder(ChannelScheduledPostsActivity.this)
                    .setTitle("Publish now?")
                    .setMessage("Publish this post immediately?")
                    .setPositiveButton("Publish", (d, w) ->
                        viewModel.publishScheduledPost(channelId, p.id))
                    .setNegativeButton("Cancel", null).show());

            // Delete
            h.btnDelete.setOnClickListener(v ->
                new AlertDialog.Builder(ChannelScheduledPostsActivity.this)
                    .setTitle("Cancel scheduled post?")
                    .setMessage("This post will be permanently deleted.")
                    .setPositiveButton("Delete", (d, w) ->
                        viewModel.deleteScheduledPost(channelId, p.id))
                    .setNegativeButton("Keep", null).show());
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView  tvContent, tvType, tvScheduledAt, tvTimeRemaining;
            ImageButton btnPublishNow, btnDelete;
            VH(View v) {
                super(v);
                tvContent       = v.findViewById(R.id.tv_scheduled_content);
                tvType          = v.findViewById(R.id.tv_scheduled_type);
                tvScheduledAt   = v.findViewById(R.id.tv_scheduled_at);
                tvTimeRemaining = v.findViewById(R.id.tv_time_remaining);
                btnPublishNow   = v.findViewById(R.id.btn_publish_now);
                btnDelete       = v.findViewById(R.id.btn_delete_scheduled);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String formatRemaining(long ms) {
        long secs = ms / 1000;
        if (secs < 60)      return "in " + secs + "s";
        long mins = secs / 60;
        if (mins < 60)      return "in " + mins + "m";
        long hours = mins / 60;
        if (hours < 24)     return "in " + hours + "h";
        long days = hours / 24;
        return "in " + days + "d";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
