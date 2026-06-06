package com.callx.app.highlights;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.firebase.database.*;
import java.util.*;
import com.callx.app.viewer.StatusViewerActivity;
import com.callx.app.utils.StatusHighlightManager;
/**
 * StatusHighlightsActivity — Browse highlights albums.
 * Shows album list; tap album → view statuses in that album.
 * Long-press album → delete/rename.
 */
public class StatusHighlightsActivity extends AppCompatActivity {
    private RecyclerView rv;
    private TextView tvEmpty;
    private ProgressBar progress;
    private final Map<String, List<StatusItem>> albumMap = new LinkedHashMap<>();
    private AlbumAdapter adapter;
    private String ownerUid;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        androidx.appcompat.widget.Toolbar toolbar = new androidx.appcompat.widget.Toolbar(this);
        toolbar.setTitle("Highlights");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        progress = new ProgressBar(this);
        root.addView(progress);
        tvEmpty = new TextView(this);
        tvEmpty.setText("No highlights yet\nAdd statuses to highlights from the viewer.");
        tvEmpty.setGravity(android.view.Gravity.CENTER);
        tvEmpty.setPadding(0, 64, 0, 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new AlbumAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ownerUid = getIntent().getStringExtra("ownerUid");
        if (ownerUid == null) {
            try { ownerUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        }
        loadHighlights();
    }
    private void loadHighlights() {
        StatusHighlightManager.getHighlightsRef(ownerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    progress.setVisibility(View.GONE);
                    albumMap.clear();
                    for (DataSnapshot albumSnap : snap.getChildren()) {
                        String albumId = albumSnap.getKey();
                        if (albumId == null) continue;
                        List<StatusItem> list = new ArrayList<>();
                        for (DataSnapshot c : albumSnap.getChildren()) {
                            StatusItem item = c.getValue(StatusItem.class);
                            if (item != null) list.add(item);
                        }
                        if (!list.isEmpty()) albumMap.put(albumId, list);
                    }
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(albumMap.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                }
            });
    }
    class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            int w = parent.getWidth() / 3;
            card.setLayoutParams(new RecyclerView.LayoutParams(w, (int)(w * 1.3f)));
            return new VH(card);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            List<String> keys = new ArrayList<>(albumMap.keySet());
            if (pos >= keys.size()) return;
            String albumId = keys.get(pos);
            List<StatusItem> list = albumMap.get(albumId);
            if (list == null || list.isEmpty()) return;
            StatusItem cover = list.get(0);
            String albumName = cover.highlightAlbumName != null ? cover.highlightAlbumName : albumId;
            String url = cover.thumbnailUrl != null ? cover.thumbnailUrl : cover.mediaUrl;
            if (url != null && !url.isEmpty()) Glide.with(h.iv).load(url).centerCrop().into(h.iv);
            else if (cover.bgColor != null) h.iv.setBackgroundColor(android.graphics.Color.parseColor(cover.bgColor));
            h.tvName.setText(albumName);
            h.tvCount.setText(list.size() + (list.size() == 1 ? " status" : " statuses"));
            h.itemView.setOnClickListener(v -> {
                android.content.Intent i = new android.content.Intent(StatusHighlightsActivity.this, StatusViewerActivity.class);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, ownerUid);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, albumName);
                startActivity(i);
            });
            h.itemView.setOnLongClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(StatusHighlightsActivity.this)
                    .setTitle(albumName)
                    .setItems(new String[]{"Delete album", "Cancel"}, (d, w2) -> {
                        if (w2 == 0) {
                            StatusHighlightManager.getAlbumRef(ownerUid, albumId).removeValue();
                            albumMap.remove(albumId);
                            notifyDataSetChanged();
                            tvEmpty.setVisibility(albumMap.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    }).show();
                return true;
            });
        }
        @Override public int getItemCount() { return albumMap.size(); }
        class VH extends RecyclerView.ViewHolder {
            ImageView iv; TextView tvName, tvCount;
            VH(LinearLayout c) {
                super(c);
                iv = new ImageView(c.getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                c.addView(iv);
                tvName = new TextView(c.getContext()); tvName.setTextSize(13); tvName.setPadding(8,4,8,0);
                tvName.setTypeface(null, android.graphics.Typeface.BOLD); c.addView(tvName);
                tvCount = new TextView(c.getContext()); tvCount.setTextSize(11);
                tvCount.setTextColor(android.graphics.Color.GRAY); tvCount.setPadding(8,0,8,4); c.addView(tvCount);
            }
        }
    }
}