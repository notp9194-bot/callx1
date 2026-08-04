package com.callx.app.hidden;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.utils.AlertDialogStyler;
import com.callx.app.utils.StatusHideManager;
import com.callx.app.viewer.StatusViewerActivity;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;

/**
 * StatusHiddenUpdatesActivity — v236.
 * Lists every contact whose statuses are currently hidden (via long-press →
 * "Hide" from StatusFragment's carousel). Reached by tapping the trailing
 * "Hidden" tile at the end of the Status carousel.
 *
 * Tap a row  → opens that contact's status in StatusViewerActivity, same as
 *              tapping a normal (non-hidden) status card.
 * Long-press → confirm "Unhide" dialog; on confirm the contact is removed
 *              from StatusHideManager and drops out of this list immediately
 *              (it reappears in the normal Status carousel next time
 *              StatusFragment rebuilds, which happens automatically since it
 *              re-reads the hidden-set live in onStart()).
 *
 * Data is passed in fully via Intent extras (uid/name/photo/timestamp arrays)
 * rather than re-querying Firebase — StatusFragment already has this data in
 * memory from its own listeners, so this screen stays a simple, self-contained
 * "local view" over that snapshot instead of duplicating the Firebase reads.
 */
public class StatusHiddenUpdatesActivity extends AppCompatActivity {

    public static final String EXTRA_UIDS       = "hiddenUids";
    public static final String EXTRA_NAMES      = "hiddenNames";
    public static final String EXTRA_PHOTOS     = "hiddenPhotos";
    public static final String EXTRA_TIMESTAMPS = "hiddenTimestamps";

    private final ArrayList<HiddenEntry> entries = new ArrayList<>();
    private HiddenAdapter adapter;
    private RecyclerView rv;
    private TextView tvEmpty;

    private static class HiddenEntry {
        String uid, name, photo;
        long timestamp;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_hidden_updates);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar_hidden_updates);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        rv = findViewById(R.id.rv_hidden_updates);
        tvEmpty = findViewById(R.id.tv_hidden_updates_empty);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HiddenAdapter();
        rv.setAdapter(adapter);

        loadFromIntent();
        refreshEmptyState();
    }

    private void loadFromIntent() {
        entries.clear();
        ArrayList<String> uids   = getIntent().getStringArrayListExtra(EXTRA_UIDS);
        ArrayList<String> names  = getIntent().getStringArrayListExtra(EXTRA_NAMES);
        ArrayList<String> photos = getIntent().getStringArrayListExtra(EXTRA_PHOTOS);
        long[] timestamps = getIntent().getLongArrayExtra(EXTRA_TIMESTAMPS);
        if (uids == null) return;
        for (int i = 0; i < uids.size(); i++) {
            HiddenEntry en = new HiddenEntry();
            en.uid  = uids.get(i);
            en.name = names  != null && i < names.size()  ? names.get(i)  : "";
            en.photo = photos != null && i < photos.size() ? photos.get(i) : "";
            en.timestamp = timestamps != null && i < timestamps.length ? timestamps[i] : 0L;
            entries.add(en);
        }
    }

    private void refreshEmptyState() {
        boolean empty = entries.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmUnhide(int pos) {
        HiddenEntry en = entries.get(pos);
        String name = en.name != null && !en.name.isEmpty() ? en.name : "this contact";
        AlertDialogStyler.showReusableConfirm(this, "unhide_status_confirm",
            AlertDialogStyler.DialogSize.WIDE,
            "Unhide " + name + "'s statuses?",
            "New statuses from " + name + " will appear under recent updates again.",
            "Unhide", () -> {
                StatusHideManager.unhide(this, en.uid);
                int idx = entries.indexOf(en);
                if (idx >= 0) {
                    entries.remove(idx);
                    adapter.notifyItemRemoved(idx);
                    refreshEmptyState();
                }
                if (entries.isEmpty()) finish();
            },
            null, null,
            "Cancel");
    }

    private void openViewer(HiddenEntry en) {
        Intent i = new Intent(this, StatusViewerActivity.class);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, en.uid);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, en.name);
        startActivity(i);
    }

    private class HiddenAdapter extends RecyclerView.Adapter<HiddenAdapter.VH> {
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_status_hidden_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            HiddenEntry en = entries.get(pos);
            h.tvName.setText(en.name != null && !en.name.isEmpty() ? en.name : "Unknown");
            h.tvTime.setText(en.timestamp > 0 ? timeFmt.format(new java.util.Date(en.timestamp)) : "");
            if (en.photo != null && !en.photo.isEmpty()) {
                Glide.with(h.itemView.getContext()).load(en.photo)
                    .placeholder(R.drawable.ic_person).into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
            h.itemView.setOnClickListener(v -> openViewer(en));
            h.itemView.setOnLongClickListener(v -> {
                confirmUnhide(h.getAdapterPosition());
                return true;
            });
        }

        @Override public int getItemCount() { return entries.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName, tvTime;
            VH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_hidden_avatar);
                tvName   = v.findViewById(R.id.tv_hidden_name);
                tvTime   = v.findViewById(R.id.tv_hidden_time);
            }
        }
    }
}
