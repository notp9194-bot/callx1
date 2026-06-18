package com.callx.app.activities;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusArchiveActivity — View all your archived (expired) statuses.
 *
 * Firebase node: statusArchive/{myUid}/{statusId}
 *
 * Features:
 *  • Grid of archived status thumbnails (2 columns)
 *  • Tap → StatusViewerActivity (read-only, no progress animation)
 *  • Long-press → "Restore" (re-post with new 24h TTL) or "Delete Permanently"
 *  • Sort by newest first
 *  • Pull-to-refresh
 *  • "Add to Highlight" option
 */
public class StatusArchiveActivity extends AppCompatActivity {

    private RecyclerView      rvArchive;
    private View              layoutEmpty;
    private SwipeRefreshLayout swipeRefresh;

    private List<StatusItem>  archivedItems = new ArrayList<>();
    private String            myUid;
    private ValueEventListener archiveListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_status_archive);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) { finish(); return; }

        rvArchive    = fv("rv_archive");
        layoutEmpty  = fv("layout_empty_archive");
        swipeRefresh = fv("swipe_refresh");

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Status Archive");
        }

        if (rvArchive != null)
            rvArchive.setLayoutManager(new GridLayoutManager(this, 2));

        if (swipeRefresh != null)
            swipeRefresh.setOnRefreshListener(() -> {
                if (archiveListener != null)
                    FirebaseUtils.db().getReference("statusArchive").child(myUid)
                        .removeEventListener(archiveListener);
                loadArchive();
            });

        loadArchive();
    }

    private void loadArchive() {
        archiveListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                archivedItems.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    StatusItem item = c.getValue(StatusItem.class);
                    if (item != null) {
                        if (item.statusId == null) item.statusId = c.getKey();
                        archivedItems.add(item);
                    }
                }
                archivedItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                runOnUiThread(() -> {
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    if (layoutEmpty != null)
                        layoutEmpty.setVisibility(archivedItems.isEmpty() ? View.VISIBLE : View.GONE);
                    if (rvArchive != null) rvArchive.setAdapter(buildAdapter());
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        };
        FirebaseUtils.db().getReference("statusArchive").child(myUid)
            .orderByChild("timestamp")
            .addValueEventListener(archiveListener);
    }

    private RecyclerView.Adapter<RecyclerView.ViewHolder> buildAdapter() {
        return new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                FrameLayout card = new FrameLayout(StatusArchiveActivity.this);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, 200);
                lp.setMargins(4, 4, 4, 4);
                card.setLayoutParams(lp);
                ImageView iv = new ImageView(StatusArchiveActivity.this);
                iv.setId(android.R.id.icon);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(iv);
                // Date overlay
                TextView tvDate = new TextView(StatusArchiveActivity.this);
                tvDate.setId(android.R.id.text1);
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
                dlp.bottomMargin = 4;
                tvDate.setLayoutParams(dlp);
                tvDate.setTextColor(android.graphics.Color.WHITE);
                tvDate.setTextSize(11);
                tvDate.setPadding(8, 4, 8, 4);
                tvDate.setBackgroundColor(0x66000000);
                card.addView(tvDate);
                return new RecyclerView.ViewHolder(card) {};
            }

            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                StatusItem item = archivedItems.get(pos);
                ImageView iv = h.itemView.findViewById(android.R.id.icon);
                TextView tvD = h.itemView.findViewById(android.R.id.text1);
                tvD.setText(formatDate(item.timestamp));
                if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                    Glide.with(StatusArchiveActivity.this).load(item.thumbnailUrl).centerCrop().into(iv);
                } else {
                    try { iv.setBackgroundColor(android.graphics.Color.parseColor(
                        item.bgColor != null ? item.bgColor : "#075E54")); }
                    catch (Exception ignored) {}
                }
                h.itemView.setOnLongClickListener(v -> { showOptions(item, pos); return true; });
            }
            @Override public int getItemCount() { return archivedItems.size(); }
        };
    }

    private void showOptions(StatusItem item, int pos) {
        String[] opts = {"Restore (Re-post 24h)", "Add to Highlight", "Delete Permanently"};
        new android.app.AlertDialog.Builder(this)
            .setItems(opts, (d, w) -> {
                if (w == 0) restoreStatus(item, pos);
                else if (w == 1) addToHighlight(item);
                else deleteFromArchive(item, pos);
            }).show();
    }

    private void restoreStatus(StatusItem item, int pos) {
        // Re-post with new 24h TTL
        item.timestamp  = System.currentTimeMillis();
        item.expiresAt  = item.timestamp + 24L * 3600_000;
        item.deleted    = false;
        item.archived   = false;
        DatabaseReference ref = FirebaseUtils.getUserStatusRef(myUid).push();
        item.statusId = ref.getKey();
        ref.setValue(item.toMap(), (e, r) -> {
            if (e == null) {
                Toast.makeText(this, "Status restored!", Toast.LENGTH_SHORT).show();
                com.callx.app.utils.StatusExpiryManager.scheduleExpiryReminder(this, item);
            }
        });
    }

    private void addToHighlight(StatusItem item) {
        android.content.Intent i = new android.content.Intent(this, StatusHighlightsActivity.class);
        i.putExtra("statusId",   item.statusId);
        i.putExtra("thumbUrl",   item.thumbnailUrl);
        i.putExtra("mediaUrl",   item.mediaUrl);
        i.putExtra("statusType", item.type);
        startActivity(i);
    }

    private void deleteFromArchive(StatusItem item, int pos) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Delete permanently?")
            .setMessage("This archived status will be gone forever.")
            .setPositiveButton("Delete", (d, w) -> {
                FirebaseUtils.db().getReference("statusArchive")
                    .child(myUid).child(item.statusId).removeValue();
                archivedItems.remove(pos);
                if (rvArchive != null && rvArchive.getAdapter() != null)
                    rvArchive.getAdapter().notifyItemRemoved(pos);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private String formatDate(long ts) {
        return android.text.format.DateFormat.format("MMM d, yyyy", new java.util.Date(ts)).toString();
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (myUid != null && archiveListener != null)
            FirebaseUtils.db().getReference("statusArchive").child(myUid)
                .removeEventListener(archiveListener);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T fv(String name) {
        int id = getResources().getIdentifier(name, "id", getPackageName());
        if (id == 0) return null;
        return (T) findViewById(id);
    }
}
