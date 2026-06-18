package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.SeenByAdapter;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * MyStatusActivity — Owner view of their own statuses.
 *
 * Shows:
 *  • ViewPager / horizontal list of own status items (preview cards)
 *  • For each item: "Seen by N" count → tap → full seen-by list with reactions
 *  • Quick actions: Delete, Add to Highlights, Extend TTL, Edit Privacy
 *  • Tap a status → StatusViewerActivity (own preview)
 *  • "+" button → NewStatusActivity
 */
public class MyStatusActivity extends AppCompatActivity {

    private RecyclerView     rvStatuses;
    private RecyclerView     rvSeenBy;
    private TextView         tvSeenByTitle;
    private View             layoutSeenBy, layoutEmpty;
    private View             btnAddStatus;

    private List<StatusItem> myItems = new ArrayList<>();
    private String           myUid;
    private String           focusStatusId;
    private ValueEventListener statusListener;
    private SeenByAdapter    seenByAdapter;

    // Currently selected status index
    private int selectedIdx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_my_status);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) { finish(); return; }

        focusStatusId = getIntent().getStringExtra("focus_status_id");

        rvStatuses   = fv("rv_my_statuses");
        rvSeenBy     = fv("rv_seen_by");
        tvSeenByTitle= fv("tv_seen_by_title");
        layoutSeenBy = fv("layout_seen_by");
        layoutEmpty  = fv("layout_empty_status");
        btnAddStatus = fv("btn_add_status");

        if (rvStatuses != null) {
            rvStatuses.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        }

        if (rvSeenBy != null) {
            seenByAdapter = new SeenByAdapter(this);
            rvSeenBy.setLayoutManager(new LinearLayoutManager(this));
            rvSeenBy.setAdapter(seenByAdapter);
        }

        if (btnAddStatus != null)
            btnAddStatus.setOnClickListener(v ->
                startActivity(new Intent(this, NewStatusActivity.class)));

        // Toolbar back button
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("My Status");
        }

        loadMyStatuses();
    }

    private void loadMyStatuses() {
        long cutoff = System.currentTimeMillis() - 24L * 3600_000;
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                myItems.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    StatusItem item = c.getValue(StatusItem.class);
                    if (item == null || item.deleted || item.archived) continue;
                    if (item.statusId == null) item.statusId = c.getKey();
                    myItems.add(item);
                }
                myItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

                runOnUiThread(() -> {
                    if (layoutEmpty != null)
                        layoutEmpty.setVisibility(myItems.isEmpty() ? View.VISIBLE : View.GONE);
                    if (rvStatuses != null)
                        rvStatuses.setAdapter(buildStatusAdapter());

                    // Auto-select focusStatusId if provided
                    if (focusStatusId != null) {
                        for (int i = 0; i < myItems.size(); i++) {
                            if (focusStatusId.equals(myItems.get(i).statusId)) {
                                selectedIdx = i;
                                break;
                            }
                        }
                    }
                    if (!myItems.isEmpty()) loadSeenBy(myItems.get(selectedIdx));
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserStatusRef(myUid)
            .orderByChild("timestamp").startAt(cutoff)
            .addValueEventListener(statusListener);
    }

    private RecyclerView.Adapter<RecyclerView.ViewHolder> buildStatusAdapter() {
        return new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
                // Build a simple card view programmatically
                FrameLayout card = new FrameLayout(MyStatusActivity.this);
                int size = (int)(90 * getResources().getDisplayMetrics().density);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
                lp.setMargins(8, 8, 8, 8);
                card.setLayoutParams(lp);
                ImageView iv = new ImageView(MyStatusActivity.this);
                iv.setId(android.R.id.icon);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(iv);
                TextView tvSeen = new TextView(MyStatusActivity.this);
                tvSeen.setId(android.R.id.text1);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
                tlp.bottomMargin = 4;
                tvSeen.setLayoutParams(tlp);
                tvSeen.setTextColor(android.graphics.Color.WHITE);
                tvSeen.setTextSize(10);
                card.addView(tvSeen);
                return new RecyclerView.ViewHolder(card) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                StatusItem item = myItems.get(pos);
                ImageView  iv   = h.itemView.findViewById(android.R.id.icon);
                TextView  tvS   = h.itemView.findViewById(android.R.id.text1);
                if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                    Glide.with(MyStatusActivity.this).load(item.thumbnailUrl).centerCrop().into(iv);
                } else {
                    try { iv.setBackgroundColor(android.graphics.Color.parseColor(
                        item.bgColor != null ? item.bgColor : "#075E54")); }
                    catch (Exception ignored) {}
                }
                tvS.setText(item.seenCount + " 👁");
                h.itemView.setOnClickListener(v -> {
                    selectedIdx = pos;
                    loadSeenBy(item);
                    // Open viewer at this index
                    Intent i = new Intent(MyStatusActivity.this, StatusViewerActivity.class);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, myUid);
                    i.putExtra(StatusViewerActivity.EXTRA_START_IDX, pos);
                    startActivity(i);
                });
                h.itemView.setOnLongClickListener(v -> {
                    showStatusOptions(item, pos);
                    return true;
                });
            }
            @Override public int getItemCount() { return myItems.size(); }
        };
    }

    private void loadSeenBy(StatusItem item) {
        if (tvSeenByTitle != null)
            tvSeenByTitle.setText("Seen by " + item.seenCount);

        StatusSeenTracker.get().fetchSeenBy(myUid, item.statusId, seenByMap -> {
            StatusSeenTracker.get().fetchReactions(myUid, item.statusId, reactionsMap -> {
                runOnUiThread(() -> {
                    if (seenByAdapter != null)
                        seenByAdapter.setData(seenByMap, reactionsMap);
                    if (layoutSeenBy != null)
                        layoutSeenBy.setVisibility(seenByMap.isEmpty() ? View.GONE : View.VISIBLE);
                });
            });
        });
    }

    private void showStatusOptions(StatusItem item, int pos) {
        String[] opts = {"Delete", "Add to Highlights", "Archive Now",
            "Extend 24h", "Edit Privacy", "Status Info"};
        new android.app.AlertDialog.Builder(this)
            .setItems(opts, (d, w) -> {
                switch (w) {
                    case 0:
                        FirebaseUtils.getUserStatusRef(myUid).child(item.statusId)
                            .child("deleted").setValue(true);
                        StatusExpiryManager.cancelExpiryReminder(this, item.statusId);
                        myItems.remove(pos);
                        if (rvStatuses != null && rvStatuses.getAdapter() != null)
                            rvStatuses.getAdapter().notifyItemRemoved(pos);
                        break;
                    case 1:
                        Intent hi = new Intent(this, StatusHighlightsActivity.class);
                        hi.putExtra("statusId",   item.statusId);
                        hi.putExtra("thumbUrl",   item.thumbnailUrl);
                        hi.putExtra("mediaUrl",   item.mediaUrl);
                        hi.putExtra("statusType", item.type);
                        startActivity(hi);
                        break;
                    case 2:
                        StatusExpiryManager.archiveExpiredStatus(myUid, item.statusId);
                        break;
                    case 3:
                        StatusExpiryManager.extendStatusTtl(myUid, item.statusId, this);
                        Toast.makeText(this, "Status extended by 24h", Toast.LENGTH_SHORT).show();
                        break;
                }
            }).show();
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (myUid != null && statusListener != null)
            FirebaseUtils.getUserStatusRef(myUid).removeEventListener(statusListener);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T fv(String name) {
        int id = getResources().getIdentifier(name, "id", getPackageName());
        if (id == 0) return null;
        return (T) findViewById(id);
    }
}
