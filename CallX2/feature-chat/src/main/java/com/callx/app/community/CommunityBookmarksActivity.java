package com.callx.app.community;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * v34: Saved / bookmarked community posts for the current user.
 *
 * Storage: SharedPreferences (offline-first) — stores Set<String> of
 *   "communityId::postId" keys. Firebase backup stored under
 *   user_bookmarks/{uid}/{communityId}/{postId} = true.
 *
 * Opened from a community's overflow menu or from any post's share/bookmark menu.
 */
public class CommunityBookmarksActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    private static final String PREFS             = "community_bookmarks";

    private String communityId; // optional — filter by community if provided
    private String currentUid;

    private RecyclerView rvBookmarks;
    private View emptyState, progressBar;
    private BookmarkAdapter adapter;
    private CommunityRepository repo;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_bookmarks);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        currentUid  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Saved Posts");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvBookmarks  = findViewById(R.id.rv_bookmarks);
        emptyState   = findViewById(R.id.empty_bookmarks);
        progressBar  = findViewById(R.id.progress_bookmarks);

        adapter = new BookmarkAdapter();
        rvBookmarks.setLayoutManager(new LinearLayoutManager(this));
        rvBookmarks.setItemAnimator(null);
        rvBookmarks.setAdapter(adapter);

        loadBookmarks();
    }

    // ─── SharedPreferences helpers ────────────────────────────────────────────

    public static void bookmarkPost(Context ctx, String communityId, String postId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet("keys", new HashSet<>()));
        saved.add(communityId + "::" + postId);
        prefs.edit().putStringSet("keys", saved).apply();

        // Firebase backup
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid != null)
            FirebaseDatabase.getInstance().getReference("user_bookmarks")
                    .child(uid).child(communityId).child(postId).setValue(true);
    }

    public static void removeBookmark(Context ctx, String communityId, String postId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet("keys", new HashSet<>()));
        saved.remove(communityId + "::" + postId);
        prefs.edit().putStringSet("keys", saved).apply();

        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid != null)
            FirebaseDatabase.getInstance().getReference("user_bookmarks")
                    .child(uid).child(communityId).child(postId).removeValue();
    }

    public static boolean isBookmarked(Context ctx, String communityId, String postId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("keys", new HashSet<>());
        return saved.contains(communityId + "::" + postId);
    }

    // ─── Load bookmarked posts ────────────────────────────────────────────────

    private void loadBookmarks() {
        progressBar.setVisibility(View.VISIBLE);
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("keys", new HashSet<>());

        List<String[]> pairs = new ArrayList<>(); // [communityId, postId]
        for (String key : saved) {
            String[] parts = key.split("::");
            if (parts.length == 2) {
                if (communityId == null || communityId.equals(parts[0]))
                    pairs.add(parts);
            }
        }

        if (pairs.isEmpty()) {
            progressBar.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
            return;
        }

        List<CommunityPostEntity> result = new ArrayList<>();
        final int[] remaining = {pairs.size()};
        for (String[] pair : pairs) {
            String cid = pair[0], pid = pair[1];
            FirebaseDatabase.getInstance().getReference("communities")
                    .child(cid).child("posts").child(pid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@Nullable DataSnapshot s) {
                            if (s != null && s.exists()) {
                                CommunityPostEntity p = new CommunityPostEntity();
                                p.id = pid; p.communityId = cid;
                                p.text       = strVal(s,"text");
                                p.authorName = strVal(s,"authorName");
                                p.authorPhoto= strVal(s,"authorPhoto");
                                p.mediaUrl   = strVal(s,"mediaUrl");
                                p.mediaType  = strVal(s,"mediaType");
                                Long ca = s.child("createdAt").getValue(Long.class);
                                p.createdAt = ca != null ? ca : 0L;
                                Long lc = s.child("likeCount").getValue(Long.class);
                                p.likeCount = lc != null ? lc : 0L;
                                Long cc = s.child("commentCount").getValue(Long.class);
                                p.commentCount = cc != null ? cc : 0L;
                                result.add(p);
                            }
                            remaining[0]--;
                            if (remaining[0] == 0) {
                                runOnUiThread(() -> {
                                    progressBar.setVisibility(View.GONE);
                                    if (result.isEmpty()) emptyState.setVisibility(View.VISIBLE);
                                    else {
                                        rvBookmarks.setVisibility(View.VISIBLE);
                                        adapter.setItems(result);
                                    }
                                });
                            }
                        }
                        @Override public void onCancelled(@Nullable DatabaseError e) {
                            remaining[0]--;
                            if (remaining[0] == 0)
                                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
                        }
                    });
        }
    }

    private static String strVal(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class); return v != null ? v : "";
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────

    class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.VH> {
        private List<CommunityPostEntity> items = new ArrayList<>();

        void setItems(List<CommunityPostEntity> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_community_bookmark, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            CommunityPostEntity p = items.get(pos);

            if (p.authorPhoto != null && !p.authorPhoto.isEmpty())
                Glide.with(h.ivAvatar.getContext()).load(p.authorPhoto)
                        .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
            else h.ivAvatar.setImageResource(R.drawable.ic_person);

            h.tvAuthor.setText(p.authorName != null ? p.authorName : "Unknown");
            h.tvText.setText(p.text != null ? p.text : "");
            h.tvLikes.setText("❤ " + p.likeCount + "  💬 " + p.commentCount);
            if (p.createdAt > 0)
                h.tvTime.setText(DateUtils.getRelativeTimeSpanString(p.createdAt));

            if (p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
                h.ivMedia.setVisibility(View.VISIBLE);
                Glide.with(h.ivMedia.getContext()).load(p.mediaUrl)
                        .centerCrop().override(320, 180).into(h.ivMedia);
            } else {
                h.ivMedia.setVisibility(View.GONE);
            }

            // Long-press to remove bookmark
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(CommunityBookmarksActivity.this)
                        .setTitle("Remove Bookmark?")
                        .setPositiveButton("Remove", (d, w) -> {
                            removeBookmark(CommunityBookmarksActivity.this, p.communityId, p.id);
                            items.remove(pos);
                            notifyItemRemoved(pos);
                            if (items.isEmpty()) emptyState.setVisibility(View.VISIBLE);
                        })
                        .setNegativeButton("Cancel", null).show();
                return true;
            });

            // Tap to open comments
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(CommunityBookmarksActivity.this,
                        CommunityPostCommentsActivity.class);
                i.putExtra(CommunityPostCommentsActivity.EXTRA_COMMUNITY_ID, p.communityId);
                i.putExtra(CommunityPostCommentsActivity.EXTRA_POST_ID, p.id);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            android.widget.ImageView ivMedia;
            TextView tvAuthor, tvText, tvTime, tvLikes;
            VH(@NonNull View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_bookmark_avatar);
                ivMedia  = v.findViewById(R.id.iv_bookmark_media);
                tvAuthor = v.findViewById(R.id.tv_bookmark_author);
                tvText   = v.findViewById(R.id.tv_bookmark_text);
                tvTime   = v.findViewById(R.id.tv_bookmark_time);
                tvLikes  = v.findViewById(R.id.tv_bookmark_likes);
            }
        }
    }

}
