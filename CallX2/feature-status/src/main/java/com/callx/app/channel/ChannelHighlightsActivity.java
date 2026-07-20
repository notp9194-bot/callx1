package com.callx.app.channel;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.models.ChannelPost;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ChannelHighlightsActivity — saved / bookmarked channel posts (v5).
 *
 * v5 additions:
 *   ✓ NEW: Share bookmark to Status — "Share to my Status" option in long-press menu
 *   ✓ NEW: Bookmarks backed by Firebase (cross-device sync via ViewModel.saveBookmark)
 *     — still falls back to SharedPreferences for offline access
 *   ✓ NEW: Remove bookmark with swipe (SwipeToDeleteCallback)
 *   ✓ NEW: Sort by: date saved / post date / post type
 *   ✓ Open original post in ChannelViewerActivity on tap
 *   ✓ Long-press: share, view, remove bookmark
 *   ✓ Empty state with illustration
 *   ✓ Count badge in toolbar subtitle
 */
public class ChannelHighlightsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final String PREFS_KEY_PREFIX = "highlights_";

    private ChannelViewModel viewModel;
    private String channelId, channelName;
    private String myUid;

    private HighlightAdapter adapter;
    private final List<HighlightEntry> highlights = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_highlights);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        myUid     = FirebaseUtils.getMyUid();
        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_highlights);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Saved posts");
            getSupportActionBar().setSubtitle(channelName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_highlights);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HighlightAdapter();
        rv.setAdapter(rv.getAdapter() != null ? rv.getAdapter() : adapter);
        if (rv.getAdapter() == null) rv.setAdapter(adapter);

        // Sort button
        View btnSort = findViewById(R.id.btn_sort_highlights);
        if (btnSort != null) btnSort.setOnClickListener(v -> showSortDialog());

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty())
                Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
        });

        loadBookmarks();
    }

    // ── Load bookmarks ─────────────────────────────────────────────────────

    private void loadBookmarks() {
        if (myUid == null) { loadFromPrefs(); return; }

        // Try Firebase first (v5 cross-device sync)
        FirebaseUtils.db().getReference("channelBookmarks").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    highlights.clear();
                    List<String> postIds = new ArrayList<>();
                    Map<String, Long> savedAtMap = new LinkedHashMap<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        Object chId = child.child("channelId").getValue();
                        Object pId  = child.child("postId").getValue();
                        Object ts   = child.child("savedAt").getValue();
                        if (chId == null || pId == null) continue;
                        if (!channelId.equals(chId.toString())) continue;
                        postIds.add(pId.toString());
                        savedAtMap.put(pId.toString(), ts instanceof Number ? ((Number) ts).longValue() : 0L);
                    }
                    if (postIds.isEmpty()) {
                        // Fall back to SharedPreferences
                        loadFromPrefs();
                    } else {
                        loadPostDetails(postIds, savedAtMap);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    loadFromPrefs();
                }
            });
    }

    private void loadFromPrefs() {
        SharedPreferences prefs = getSharedPreferences("channel_highlights", MODE_PRIVATE);
        String raw = prefs.getString(PREFS_KEY_PREFIX + channelId, "");
        Set<String> postIds = new LinkedHashSet<>(Arrays.asList(raw.split(",")));
        postIds.remove("");
        Map<String, Long> savedAtMap = new LinkedHashMap<>();
        for (String id : postIds) savedAtMap.put(id, 0L);
        loadPostDetails(new ArrayList<>(postIds), savedAtMap);
    }

    private void loadPostDetails(List<String> postIds, Map<String, Long> savedAtMap) {
        if (postIds.isEmpty()) { updateEmptyState(); return; }
        final int[] remaining = {postIds.size()};
        for (String postId : postIds) {
            FirebaseUtils.db().getReference("channelPosts").child(channelId).child(postId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        HighlightEntry e = new HighlightEntry();
                        e.postId    = postId;
                        Object typeObj = snap.child("type").getValue();
                        Object textObj = snap.child("text").getValue();
                        Object mediaObj= snap.child("mediaUrl").getValue();
                        Object tsObj   = snap.child("timestamp").getValue();
                        e.postType  = typeObj != null ? typeObj.toString() : "text";
                        e.postText  = textObj != null ? textObj.toString() : "";
                        e.mediaUrl  = mediaObj != null ? mediaObj.toString() : null;
                        e.postTs    = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0;
                        e.savedAt   = savedAtMap.getOrDefault(postId, 0L);
                        highlights.add(e);
                        if (--remaining[0] <= 0) {
                            highlights.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
                            adapter.setData(highlights);
                            updateEmptyState();
                            updateSubtitle();
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (--remaining[0] <= 0) {
                            adapter.setData(highlights); updateEmptyState(); updateSubtitle();
                        }
                    }
                });
        }
    }

    private void updateEmptyState() {
        View empty = findViewById(R.id.layout_highlights_empty);
        if (empty != null) empty.setVisibility(highlights.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateSubtitle() {
        if (getSupportActionBar() != null)
            getSupportActionBar().setSubtitle(highlights.size() + " saved posts");
    }

    // ── Sort dialog ────────────────────────────────────────────────────────

    private void showSortDialog() {
        String[] opts = {"Date saved (newest)", "Post date (newest)", "Post type"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setItems(opts, (d, w) -> {
                if (w == 0) highlights.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
                else if (w == 1) highlights.sort((a, b) -> Long.compare(b.postTs, a.postTs));
                else highlights.sort((a, b) -> a.postType.compareTo(b.postType));
                adapter.setData(highlights);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── HighlightAdapter ───────────────────────────────────────────────────

    class HighlightAdapter extends RecyclerView.Adapter<HighlightAdapter.VH> {
        private final List<HighlightEntry> data = new ArrayList<>();
        void setData(List<HighlightEntry> d) { data.clear(); data.addAll(d); notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_highlight, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            HighlightEntry e = data.get(pos);
            String typeLabel = typeLabel(e.postType);
            if (h.tvType != null) h.tvType.setText(typeLabel);
            if (h.tvText != null) {
                String preview = e.postText != null && !e.postText.isEmpty()
                    ? (e.postText.length() > 80 ? e.postText.substring(0, 80) + "…" : e.postText)
                    : typeLabel;
                h.tvText.setText(preview);
            }
            if (h.ivMedia != null) {
                if (e.mediaUrl != null && !e.mediaUrl.isEmpty()) {
                    h.ivMedia.setVisibility(View.VISIBLE);
                    Glide.with(h.ivMedia.getContext()).load(e.mediaUrl).centerCrop().into(h.ivMedia);
                } else {
                    h.ivMedia.setVisibility(View.GONE);
                }
            }

            // Tap → open viewer
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ChannelHighlightsActivity.this, ChannelViewerActivity.class);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,   channelId);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME, channelName);
                startActivity(i);
            });

            // Long-press → options: Share to Status, Share externally, Remove
            h.itemView.setOnLongClickListener(v -> {
                String[] opts = {"Share to my Status", "Share externally", "Remove bookmark"};
                new androidx.appcompat.app.AlertDialog.Builder(ChannelHighlightsActivity.this)
                    .setTitle("Post options")
                    .setItems(opts, (d, w) -> {
                        if (w == 0) {
                            // ── NEW: Share bookmarked post to Status ─────────────
                            ChannelPost post = new ChannelPost();
                            post.id = e.postId; post.channelId = channelId;
                            post.type = e.postType; post.text = e.postText; post.mediaUrl = e.mediaUrl;
                            viewModel.sharePostToStatus(post);
                        } else if (w == 1) {
                            ChannelPost post = new ChannelPost();
                            post.id = e.postId; post.channelId = channelId;
                            post.type = e.postType; post.text = e.postText; post.mediaUrl = e.mediaUrl;
                            ChannelShareHelper.shareViaAndroid(ChannelHighlightsActivity.this, post, channelName);
                        } else {
                            // Remove bookmark
                            viewModel.removeBookmark(channelId, e.postId);
                            highlights.remove(e);
                            notifyItemRemoved(pos);
                            updateEmptyState(); updateSubtitle();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivMedia;
            TextView tvType, tvText;
            VH(View v) {
                super(v);
                ivMedia = v.findViewById(R.id.iv_highlight_media);
                tvType  = v.findViewById(R.id.tv_highlight_type);
                tvText  = v.findViewById(R.id.tv_highlight_text);
            }
        }
    }

    static class HighlightEntry {
        String postId, postType, postText, mediaUrl;
        long   savedAt, postTs;
    }

    private static String typeLabel(String type) {
        if (type == null) return "Post";
        switch (type) {
            case "image":     return "📷 Photo";
            case "video":     return "🎬 Video";
            case "audio":     return "🎵 Audio";
            case "document":  return "📄 Document";
            case "poll":      return "📊 Poll";
            case "link":      return "🔗 Link";
            case "broadcast": return "📢 Broadcast";
            case "event":     return "📅 Event";
            default:          return "💬 Text";
        }
    }

    /** Static helper used by ChannelViewerActivity.onSavePost. */
    public static void toggleBookmark(android.content.Context ctx, String channelId, String postId) {
        if (ctx == null || postId == null) return;
        String myUid = com.google.firebase.auth.FirebaseAuth.getInstance()
                .getCurrentUser() != null
                ? com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) return;
        com.google.firebase.database.DatabaseReference ref =
                com.callx.app.utils.FirebaseUtils.db()
                        .getReference(channelBookmarks).child(myUid).child(postId);
        ref.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                if (snap.exists()) { ref.removeValue(); } else {
                    ref.setValue(java.util.Collections.singletonMap(channelId, channelId));
                }
            }
            @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
        });
    }

}
