package com.callx.app.compose;
import com.callx.app.utils.AlertDialogStyler;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.status.R;
import com.callx.app.viewmodel.StatusViewModel;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * StatusScheduledActivity — manage statuses scheduled for future auto-publish.
 *
 * Mirrors ChannelScheduledPostsActivity's UX for the per-user Status feature:
 *   - Shows all of my scheduled (not yet published) statuses, soonest first
 *   - Publish a scheduled status immediately
 *   - Cancel / delete a scheduled status
 *   - Live "time remaining" countdown label per row
 */
public class StatusScheduledActivity extends AppCompatActivity {

    private StatusViewModel viewModel;
    private ScheduledAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_scheduled);

        viewModel = new ViewModelProvider(this).get(StatusViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_status_scheduled);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Scheduled statuses");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_status_scheduled);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScheduledAdapter();
        rv.setAdapter(adapter);

        View emptyState = findViewById(R.id.layout_status_scheduled_empty);

        viewModel.scheduledStatuses.observe(this, list -> {
            if (list == null) list = new ArrayList<>();
            adapter.setItems(list);
            if (emptyState != null)
                emptyState.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        // Pick up anything scheduled from another device too.
        viewModel.refreshScheduled();
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class ScheduledAdapter extends RecyclerView.Adapter<ScheduledAdapter.VH> {
        private final List<StatusEntity> list = new ArrayList<>();

        void setItems(List<StatusEntity> items) {
            list.clear(); list.addAll(items); notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_status_scheduled, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            StatusEntity s = list.get(pos);

            // Content preview
            String preview;
            if (s.text != null && !s.text.isEmpty()) {
                preview = s.text.length() > 80 ? s.text.substring(0, 80) + "…" : s.text;
            } else {
                preview = "[" + (s.type != null ? capitalize(s.type) : "Status") + "]";
            }
            h.tvContent.setText(preview);
            h.tvType.setText(s.type != null ? capitalize(s.type) : "Text");

            // Scheduled time
            if (s.scheduledAt > 0) {
                String fmt = new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
                    .format(new Date(s.scheduledAt));
                h.tvScheduledAt.setText("📅 " + fmt);
                long remaining = s.scheduledAt - System.currentTimeMillis();
                if (remaining > 0) {
                    h.tvTimeRemaining.setText(formatRemaining(remaining));
                    h.tvTimeRemaining.setVisibility(View.VISIBLE);
                } else {
                    h.tvTimeRemaining.setText("Publishing soon…");
                    h.tvTimeRemaining.setVisibility(View.VISIBLE);
                }
            }

            h.btnPublishNow.setOnClickListener(v ->
                AlertDialogStyler.showReusableConfirm(StatusScheduledActivity.this,
                        "status_scheduled_publish", AlertDialogStyler.DialogSize.DEFAULT,
                        "Publish now?", "Publish this status immediately?",
                        "Publish", () -> viewModel.publishScheduledStatus(s.id),
                        null, null,
                        "Cancel"));

            h.btnDelete.setOnClickListener(v ->
                AlertDialogStyler.showReusableConfirm(StatusScheduledActivity.this,
                        "status_scheduled_delete", AlertDialogStyler.DialogSize.DEFAULT,
                        "Cancel scheduled status?", "This status will be permanently deleted.",
                        "Delete", () -> viewModel.deleteScheduledStatus(s.id),
                        null, null,
                        "Keep"));
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvContent, tvScheduledAt, tvTimeRemaining;
            com.google.android.material.chip.Chip tvType;
            com.google.android.material.button.MaterialButton btnPublishNow, btnDelete;
            VH(View v) {
                super(v);
                tvContent       = v.findViewById(R.id.tv_status_scheduled_content);
                tvType          = v.findViewById(R.id.tv_status_scheduled_type);
                tvScheduledAt   = v.findViewById(R.id.tv_status_scheduled_at);
                tvTimeRemaining = v.findViewById(R.id.tv_status_time_remaining);
                btnPublishNow   = v.findViewById(R.id.btn_status_publish_now);
                btnDelete       = v.findViewById(R.id.btn_status_delete_scheduled);
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
