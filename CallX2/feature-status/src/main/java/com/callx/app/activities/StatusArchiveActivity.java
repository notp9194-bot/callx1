package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusHighlightManager;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * StatusArchiveActivity — View and manage archived statuses.
 * NEW: Archived statuses listed with thumbnail, text, timestamp.
 * NEW: Long-press to add to Highlights album.
 * NEW: Swipe to delete from archive.
 */
public class StatusArchiveActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView     tvEmpty;
    private ProgressBar  progress;
    private final List<StatusItem> items = new ArrayList<>();
    private ArchiveAdapter adapter;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        androidx.appcompat.widget.Toolbar toolbar = new androidx.appcompat.widget.Toolbar(this);
        toolbar.setTitle("Status Archive");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this);
        root.addView(progress);

        tvEmpty = new TextView(this);
        tvEmpty.setText("No archived statuses");
        tvEmpty.setGravity(android.view.Gravity.CENTER);
        tvEmpty.setPadding(0, 64, 0, 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new ArchiveAdapter();
        rv.setAdapter(adapter);

        // Swipe to delete
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h,
                                            @NonNull RecyclerView.ViewHolder t) { return false; }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) {
                int pos = h.getAdapterPosition();
                if (pos < 0 || pos >= items.size()) return;
                StatusItem removed = items.remove(pos);
                adapter.notifyItemRemoved(pos);
                StatusHighlightManager.unarchiveStatus(myUid, removed.id);
            }
        }).attachToRecyclerView(rv);

        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        loadArchive();
    }

    private void loadArchive() {
        StatusHighlightManager.getArchiveRef(myUid)
            .orderByChild("archivedAt")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    progress.setVisibility(View.GONE);
                    items.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        StatusItem item = c.getValue(StatusItem.class);
                        if (item != null) items.add(0, item); // newest first
                    }
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                }
            });
    }

    class ArchiveAdapter extends RecyclerView.Adapter<ArchiveAdapter.VH> {
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM", Locale.getDefault());
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout fl = new FrameLayout(parent.getContext());
            int size = parent.getWidth() / 3;
            fl.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            return new VH(fl);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            StatusItem item = items.get(pos);
            h.bind(item);
        }
        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView iv; TextView tvOverlay;
            VH(FrameLayout fl) {
                super(fl);
                iv = new ImageView(fl.getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                fl.addView(iv);
                tvOverlay = new TextView(fl.getContext());
                tvOverlay.setTextColor(android.graphics.Color.WHITE);
                tvOverlay.setTextSize(11);
                tvOverlay.setPadding(8, 4, 8, 4);
                tvOverlay.setGravity(android.view.Gravity.BOTTOM);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                tvOverlay.setLayoutParams(lp);
                tvOverlay.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.START);
                tvOverlay.setBackgroundColor(android.graphics.Color.parseColor("#66000000"));
                fl.addView(tvOverlay);
            }
            void bind(StatusItem item) {
                String url = item.thumbnailUrl != null ? item.thumbnailUrl : item.mediaUrl;
                if (url != null) {
                    Glide.with(iv).load(url).centerCrop().into(iv);
                } else {
                    iv.setBackgroundColor(item.bgColor != null
                            ? android.graphics.Color.parseColor(item.bgColor) : 0xFF6200EE);
                }
                tvOverlay.setText(item.archivedAt != null ? fmt.format(new Date(item.archivedAt)) : "");
                // Long press → add to highlight
                itemView.setOnLongClickListener(v -> {
                    showAddToHighlightDialog(item);
                    return true;
                });
                itemView.setOnClickListener(v -> {
                    Intent i = new Intent(itemView.getContext(), StatusViewerActivity.class);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, myUid);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, "My Status");
                    startActivity(i);
                });
            }
        }
    }

    private void showAddToHighlightDialog(StatusItem item) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add to Highlights")
            .setMessage("Enter album name:")
            .setView(new EditText(this) {{
                setHint("Album name (e.g. Vacation 2024)");
                setId(android.R.id.text1);
            }})
            .setPositiveButton("Add", (d, w) -> {
                EditText et = ((androidx.appcompat.app.AlertDialog) d)
                        .findViewById(android.R.id.text1);
                String album = et != null ? et.getText().toString().trim() : "";
                if (album.isEmpty()) album = "Highlights";
                String albumId = album.toLowerCase().replace(" ", "_");
                StatusHighlightManager.addToHighlight(myUid, item, albumId, album);
                Toast.makeText(this, "Added to " + album, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
