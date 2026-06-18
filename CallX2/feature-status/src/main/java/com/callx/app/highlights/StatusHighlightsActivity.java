package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.models.StatusHighlight;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusHighlightsActivity — Create, view, and manage status highlights.
 *
 * A "Highlight" = a named collection of statuses pinned to your profile.
 * Example: "Trip to Goa", "Birthday 2025", "Daily Vlogs"
 *
 * Firebase node: statusHighlights/{ownerUid}/{highlightId}
 * Items added here are also tagged on each StatusItem (inHighlight=true, highlightId)
 *
 * Modes:
 *  NORMAL → show all highlights, tap = view, long-press = rename/delete
 *  ADD    → (launched from StatusViewerActivity) → pick/create highlight to add a status to
 */
public class StatusHighlightsActivity extends AppCompatActivity {

    private static final String MODE_NORMAL = "normal";
    private static final String MODE_ADD    = "add";    // adding a status to highlight

    private RecyclerView      rvHighlights;
    private View              layoutEmpty, btnCreateNew;
    private TextView          tvTitle;

    private List<StatusHighlight> highlights = new ArrayList<>();
    private String             myUid;
    private String             mode;

    // Passed in ADD mode
    private String addStatusId, addMediaUrl, addThumbUrl, addStatusType;

    private ValueEventListener highlightListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_status_highlights);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) { finish(); return; }

        addStatusId   = getIntent().getStringExtra("statusId");
        addMediaUrl   = getIntent().getStringExtra("mediaUrl");
        addThumbUrl   = getIntent().getStringExtra("thumbUrl");
        addStatusType = getIntent().getStringExtra("statusType");
        mode = (addStatusId != null) ? MODE_ADD : MODE_NORMAL;

        rvHighlights = fv("rv_highlights");
        layoutEmpty  = fv("layout_empty_highlights");
        btnCreateNew = fv("btn_create_highlight");
        tvTitle      = fv("tv_highlights_title");

        if (tvTitle != null)
            tvTitle.setText(MODE_ADD.equals(mode) ? "Add to Highlight" : "My Highlights");

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(MODE_ADD.equals(mode) ? "Add to Highlight" : "My Highlights");
        }

        if (rvHighlights != null)
            rvHighlights.setLayoutManager(new GridLayoutManager(this, 2));

        if (btnCreateNew != null)
            btnCreateNew.setOnClickListener(v -> showCreateDialog());

        loadHighlights();
    }

    private void loadHighlights() {
        highlightListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                highlights.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    StatusHighlight h = c.getValue(StatusHighlight.class);
                    if (h != null) {
                        if (h.highlightId == null) h.highlightId = c.getKey();
                        highlights.add(h);
                    }
                }
                highlights.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                runOnUiThread(() -> {
                    if (layoutEmpty != null)
                        layoutEmpty.setVisibility(highlights.isEmpty() ? View.VISIBLE : View.GONE);
                    if (rvHighlights != null)
                        rvHighlights.setAdapter(buildAdapter());
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getStatusHighlightsRef(myUid).addValueEventListener(highlightListener);
    }

    private RecyclerView.Adapter<RecyclerView.ViewHolder> buildAdapter() {
        return new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                // Build card programmatically — replace with item_highlight.xml in production
                FrameLayout card = new FrameLayout(StatusHighlightsActivity.this);
                int size = (int)(160 * getResources().getDisplayMetrics().density);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
                lp.setMargins(8, 8, 8, 8);
                card.setLayoutParams(lp);
                ImageView iv = new ImageView(StatusHighlightsActivity.this);
                iv.setId(android.R.id.icon);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(iv);
                // Gradient overlay
                View overlay = new View(StatusHighlightsActivity.this);
                overlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 80,
                    android.view.Gravity.BOTTOM));
                overlay.setBackgroundColor(0x88000000);
                card.addView(overlay);
                TextView tvN = new TextView(StatusHighlightsActivity.this);
                tvN.setId(android.R.id.text1);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
                tlp.bottomMargin = 8; tlp.leftMargin = 8;
                tvN.setLayoutParams(tlp);
                tvN.setTextColor(android.graphics.Color.WHITE);
                tvN.setTextSize(13);
                tvN.setTypeface(null, android.graphics.Typeface.BOLD);
                card.addView(tvN);
                TextView tvC = new TextView(StatusHighlightsActivity.this);
                tvC.setId(android.R.id.text2);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
                clp.bottomMargin = 28; clp.leftMargin = 8;
                tvC.setLayoutParams(clp);
                tvC.setTextColor(0xCCFFFFFF);
                tvC.setTextSize(11);
                card.addView(tvC);
                return new RecyclerView.ViewHolder(card) {};
            }

            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                StatusHighlight hl = highlights.get(pos);
                ImageView iv  = h.itemView.findViewById(android.R.id.icon);
                TextView tvN  = h.itemView.findViewById(android.R.id.text1);
                TextView tvC  = h.itemView.findViewById(android.R.id.text2);
                tvN.setText(hl.title);
                tvC.setText(hl.itemCount + " items");
                if (hl.coverUrl != null && !hl.coverUrl.isEmpty())
                    Glide.with(StatusHighlightsActivity.this).load(hl.coverUrl).centerCrop().into(iv);
                else
                    iv.setBackgroundColor(0xFF128C7E);

                h.itemView.setOnClickListener(v -> {
                    if (MODE_ADD.equals(mode)) addStatusToHighlight(hl);
                    else openHighlight(hl);
                });
                h.itemView.setOnLongClickListener(v -> { showOptions(hl); return true; });
            }
            @Override public int getItemCount() { return highlights.size(); }
        };
    }

    private void showCreateDialog() {
        EditText et = new EditText(this);
        et.setHint("e.g. Vacation, Birthday…");
        new android.app.AlertDialog.Builder(this)
            .setTitle("New Highlight")
            .setView(et)
            .setPositiveButton("Create", (d, w) -> {
                String title = et.getText().toString().trim();
                if (!title.isEmpty()) createHighlight(title);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void createHighlight(String title) {
        DatabaseReference ref = FirebaseUtils.getStatusHighlightsRef(myUid).push();
        StatusHighlight hl = new StatusHighlight(myUid, title);
        hl.highlightId = ref.getKey();
        hl.itemCount   = 0;
        ref.setValue(hl.toMap(), (e, r) -> {
            if (e == null && MODE_ADD.equals(mode)) addStatusToHighlight(hl);
        });
    }

    private void addStatusToHighlight(StatusHighlight hl) {
        if (addStatusId == null) return;
        DatabaseReference ref = FirebaseUtils.getStatusHighlightsRef(myUid)
            .child(hl.highlightId);
        // Add statusId to the items list
        ref.child("statusIds").child(addStatusId).setValue(true);
        ref.child("itemCount").runTransaction(new Transaction.Handler() {
            @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                Long v = d.getValue(Long.class);
                d.setValue(v == null ? 1 : v + 1);
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
        });
        ref.child("updatedAt").setValue(System.currentTimeMillis());
        // Set cover if not set
        if ((hl.coverUrl == null || hl.coverUrl.isEmpty()) && addThumbUrl != null) {
            ref.child("coverUrl").setValue(addThumbUrl);
        }
        // Tag the status item
        if (myUid != null)
            FirebaseUtils.getUserStatusRef(myUid).child(addStatusId)
                .updateChildren(new HashMap<String, Object>() {{
                    put("inHighlight",  true);
                    put("highlightId",  hl.highlightId);
                }});
        Toast.makeText(this, "Added to \"" + hl.title + "\"", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void openHighlight(StatusHighlight hl) {
        // Open a viewer that shows only this highlight's statuses
        Intent i = new Intent(this, StatusViewerActivity.class);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, myUid);
        startActivity(i);
    }

    private void showOptions(StatusHighlight hl) {
        String[] opts = {"Rename", "Edit Cover", "Delete"};
        new android.app.AlertDialog.Builder(this)
            .setItems(opts, (d, w) -> {
                if (w == 0) showRenameDialog(hl);
                else if (w == 2) deleteHighlight(hl);
            }).show();
    }

    private void showRenameDialog(StatusHighlight hl) {
        EditText et = new EditText(this);
        et.setText(hl.title);
        et.selectAll();
        new android.app.AlertDialog.Builder(this)
            .setTitle("Rename Highlight")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String t = et.getText().toString().trim();
                if (!t.isEmpty())
                    FirebaseUtils.getStatusHighlightsRef(myUid)
                        .child(hl.highlightId).child("title").setValue(t);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteHighlight(StatusHighlight hl) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Delete \"" + hl.title + "\"?")
            .setMessage("The statuses won't be deleted, just removed from this highlight.")
            .setPositiveButton("Delete", (d, w) ->
                FirebaseUtils.getStatusHighlightsRef(myUid).child(hl.highlightId).removeValue())
            .setNegativeButton("Cancel", null).show();
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (myUid != null && highlightListener != null)
            FirebaseUtils.getStatusHighlightsRef(myUid).removeEventListener(highlightListener);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T fv(String name) {
        int id = getResources().getIdentifier(name, "id", getPackageName());
        if (id == 0) return null;
        return (T) findViewById(id);
    }
}
