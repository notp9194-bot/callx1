package com.callx.app.highlights;
import com.callx.app.utils.AlertDialogStyler;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.firebase.database.*;
import java.util.*;
import com.callx.app.viewer.StatusViewerActivity;
import com.callx.app.utils.StatusHighlightManager;
/**
 * StatusHighlightsActivity — Browse highlights albums.
 * Shows album list; tap album → opens the album in the story viewer (permanent,
 * Instagram-style — works even after the original stories expired).
 * Long-press album → opens the Highlight settings sheet (rename / cover / delete).
 *
 * v39: FIX — tapping an album used to reopen the OWNER's live status feed
 *      (StatusViewerActivity with just ownerUid/name), completely ignoring the
 *      album itself. Now passes StatusViewerActivity.EXTRA_HIGHLIGHT_ALBUM_ID so
 *      the viewer loads the permanent album copies instead.
 * v39: NEW — long-press now opens StatusHighlightSettingsBottomSheet (rename,
 *      change cover, delete) instead of an inline delete-only AlertDialog —
 *      this is the "highlight editing & settings" system that was missing.
 * v39: NEW — reads statusHighlightMeta/{ownerUid}/{albumId} for a custom name
 *      / cover set via the settings sheet, falling back to the first item's
 *      thumbnail/name as before when no meta exists yet.
 */
public class StatusHighlightsActivity extends AppCompatActivity {
    private RecyclerView rv;
    private TextView tvEmpty;
    private ProgressBar progress;
    private final Map<String, List<StatusItem>> albumMap = new LinkedHashMap<>();
    private final Map<String, String> albumNameOverride = new HashMap<>();
    private final Map<String, String> albumCoverOverride = new HashMap<>();
    private AlbumAdapter adapter;
    private String ownerUid;
    private boolean isOwner;
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
        String myUid = null;
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) { }
        isOwner = myUid != null && myUid.equals(ownerUid);
        loadHighlights();
    }
    @Override
    protected void onResume() {
        super.onResume();
        // Refresh in case a rename/cover/delete happened in the settings sheet
        // or inside the highlight viewer (rename/set-cover/delete-album options).
        if (adapter != null) loadHighlights();
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
                    loadAlbumMetaOverrides();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                }
            });
    }
    /** Pulls custom name/cover set via the settings sheet, if any. */
    private void loadAlbumMetaOverrides() {
        FirebaseUtils.db().getReference("statusHighlightMeta").child(ownerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    albumNameOverride.clear();
                    albumCoverOverride.clear();
                    for (DataSnapshot metaSnap : snap.getChildren()) {
                        String albumId = metaSnap.getKey();
                        if (albumId == null) continue;
                        String name = metaSnap.child("name").getValue(String.class);
                        String cover = metaSnap.child("coverUrl").getValue(String.class);
                        if (name != null && !name.isEmpty()) albumNameOverride.put(albumId, name);
                        if (cover != null && !cover.isEmpty()) albumCoverOverride.put(albumId, cover);
                    }
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { }
            });
    }
    class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.VH> {
        // Instagram-style: same fixed 480x853 override bug as the picker
        // grid (see CreateHighlightActivity) — every album-cover cell here
        // decoded a full story-portrait-sized bitmap regardless of this
        // grid cell's real (much smaller) on-screen size. Resolved once to
        // the real cell width, then requested as a CDN-derived WebP thumb
        // (+ tiny blur-up placeholder), same pipeline as everywhere else.
        private int resolvedCellPx = 0;
        private static final int BLUR_SIZE = 16;
        private final RequestOptions gridOptions = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565)
                .centerCrop()
                .dontAnimate();

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            int w = parent.getWidth() / 3;
            if (resolvedCellPx == 0) resolvedCellPx = w;
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
            String albumName = albumNameOverride.containsKey(albumId)
                    ? albumNameOverride.get(albumId)
                    : (cover.highlightAlbumName != null ? cover.highlightAlbumName : albumId);
            String url = albumCoverOverride.containsKey(albumId)
                    ? albumCoverOverride.get(albumId)
                    : (cover.thumbnailUrl != null ? cover.thumbnailUrl : cover.mediaUrl);
            if (url != null && !url.isEmpty()) {
                int cellPx = resolvedCellPx > 0 ? resolvedCellPx : h.itemView.getWidth();
                if (cellPx <= 0) cellPx = 240; // safe fallback before first layout pass
                String gridUrl = CloudinaryUploader.deriveThumbUrl(url, cellPx, "webp");
                String blurUrl = CloudinaryUploader.deriveThumbUrl(url, BLUR_SIZE, "webp");
                Glide.with(h.iv)
                        .load(gridUrl)
                        .thumbnail(Glide.with(h.iv).load(blurUrl).apply(gridOptions))
                        .apply(gridOptions)
                        .into(h.iv);
            }
            else if (cover.bgColor != null) h.iv.setBackgroundColor(android.graphics.Color.parseColor(cover.bgColor));
            h.tvName.setText(albumName);
            h.tvCount.setText(list.size() + (list.size() == 1 ? " status" : " statuses"));
            h.itemView.setOnClickListener(v -> {
                android.content.Intent i = new android.content.Intent(StatusHighlightsActivity.this, StatusViewerActivity.class);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, ownerUid);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, albumName);
                i.putExtra(StatusViewerActivity.EXTRA_HIGHLIGHT_ALBUM_ID, albumId);
                // Instagram-style: hand over every album in this grid, in
                // the same order shown here, so finishing one album's
                // stories auto-continues into the next tile instead of
                // just closing the viewer.
                ArrayList<String> queueIds = new ArrayList<>();
                ArrayList<String> queueNames = new ArrayList<>();
                for (String aid : keys) {
                    List<StatusItem> list2 = albumMap.get(aid);
                    if (list2 == null || list2.isEmpty()) continue;
                    StatusItem cover2 = list2.get(0);
                    String name2 = albumNameOverride.containsKey(aid)
                            ? albumNameOverride.get(aid)
                            : (cover2.highlightAlbumName != null ? cover2.highlightAlbumName : aid);
                    queueIds.add(aid);
                    queueNames.add(name2);
                }
                i.putStringArrayListExtra(StatusViewerActivity.EXTRA_QUEUE_ALBUM_IDS, queueIds);
                i.putStringArrayListExtra(StatusViewerActivity.EXTRA_QUEUE_ALBUM_NAMES, queueNames);
                startActivity(i);
            });
            if (isOwner) {
                h.itemView.setOnLongClickListener(v -> {
                    StatusHighlightSettingsBottomSheet.show(StatusHighlightsActivity.this,
                            ownerUid, albumId, albumName, list,
                            () -> loadHighlights());
                    return true;
                });
            }
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
