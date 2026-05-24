package com.callx.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeCommunityPost;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * Community Posts for a channel:
 * - View posts (text + optional image)
 * - Create new post (channel owner only)
 * - Like posts
 * - Delete own posts
 */
public class YouTubeCommunityActivity extends AppCompatActivity {

    private RecyclerView    rvPosts;
    private PostAdapter     adapter;
    private EditText        etPost;
    private String          channelUid, myUid, myName, myPhoto;
    private boolean         isOwner;
    private ValueEventListener postsListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_community);

        channelUid = getIntent().getStringExtra("uid");
        var user   = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            myUid   = user.getUid();
            myName  = user.getDisplayName() != null ? user.getDisplayName() : "User";
            myPhoto = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
        } else { myUid = ""; myName = "Guest"; myPhoto = null; }

        isOwner = channelUid != null && channelUid.equals(myUid);

        View btnBack = findViewById(R.id.btn_yt_community_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvPosts = findViewById(R.id.rv_yt_community_posts);
        adapter = new PostAdapter();
        rvPosts.setLayoutManager(new LinearLayoutManager(this));
        rvPosts.setAdapter(adapter);

        View layoutCreate = findViewById(R.id.layout_yt_community_create);
        if (layoutCreate != null) layoutCreate.setVisibility(isOwner ? View.VISIBLE : View.GONE);

        etPost = findViewById(R.id.et_yt_community_post);
        ImageButton btnPost = findViewById(R.id.btn_yt_community_submit);
        if (btnPost != null) btnPost.setOnClickListener(v -> createPost());

        loadPosts();
    }

    private void loadPosts() {
        postsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeCommunityPost> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeCommunityPost p = ds.getValue(YouTubeCommunityPost.class);
                    if (p != null) list.add(0, p);
                }
                adapter.setData(list);
                View tvEmpty = findViewById(R.id.tv_yt_community_empty);
                if (tvEmpty != null) tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.communityPostsRef(channelUid)
            .orderByChild("timestamp").limitToLast(50)
            .addValueEventListener(postsListener);
    }

    private void createPost() {
        if (!isOwner || myUid.isEmpty()) return;
        String text = etPost != null ? etPost.getText().toString().trim() : "";
        if (text.isEmpty()) { Toast.makeText(this,"Write something first",Toast.LENGTH_SHORT).show(); return; }

        String postId = YouTubeFirebaseUtils.communityPostsRef(channelUid).push().getKey();
        if (postId == null) return;

        YouTubeCommunityPost post = new YouTubeCommunityPost(
            postId, myUid, myName, myPhoto, text);
        YouTubeFirebaseUtils.communityPostsRef(channelUid).child(postId).setValue(post);
        if (etPost != null) etPost.setText("");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (postsListener != null && channelUid != null)
            YouTubeFirebaseUtils.communityPostsRef(channelUid).removeEventListener(postsListener);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    class PostAdapter extends RecyclerView.Adapter<PostAdapter.PVH> {
        private List<YouTubeCommunityPost> data = new ArrayList<>();
        void setData(List<YouTubeCommunityPost> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public PVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(YouTubeCommunityActivity.this)
                .inflate(R.layout.item_yt_community_post, p, false);
            return new PVH(v);
        }

        @Override public void onBindViewHolder(@NonNull PVH h, int pos) {
            YouTubeCommunityPost p = data.get(pos);
            h.tvAuthor.setText(p.authorName);
            h.tvText.setText(p.text);
            h.tvTime.setText(relTime(p.timestamp));
            h.tvLikes.setText(p.likeCount > 0 ? String.valueOf(p.likeCount) : "");
            if (p.authorPhotoUrl != null)
                Glide.with(YouTubeCommunityActivity.this)
                    .load(p.authorPhotoUrl).circleCrop().into(h.ivAvatar);
            if (p.imageUrl != null && h.ivPostImage != null) {
                h.ivPostImage.setVisibility(View.VISIBLE);
                Glide.with(YouTubeCommunityActivity.this)
                    .load(p.imageUrl).centerCrop().into(h.ivPostImage);
            } else if (h.ivPostImage != null) {
                h.ivPostImage.setVisibility(View.GONE);
            }
            if (p.authorVerified && h.ivVerified != null)
                h.ivVerified.setVisibility(View.VISIBLE);

            // Like
            if (!myUid.isEmpty() && h.btnLike != null) {
                h.btnLike.setOnClickListener(v -> toggleLike(p, h));
            }
            // Delete (owner only)
            if (h.btnDelete != null) {
                h.btnDelete.setVisibility(
                    myUid.equals(p.authorUid) ? View.VISIBLE : View.GONE);
                h.btnDelete.setOnClickListener(v -> deletePost(p.postId));
            }
        }

        @Override public int getItemCount() { return data.size(); }

        void toggleLike(YouTubeCommunityPost p, PVH h) {
            DatabaseReference ref = YouTubeFirebaseUtils.communityPostsRef(channelUid)
                .child(p.postId).child("likeCount");
            if (h.btnLike.isSelected()) {
                ref.setValue(ServerValue.increment(-1));
                h.btnLike.setSelected(false);
            } else {
                ref.setValue(ServerValue.increment(1));
                h.btnLike.setSelected(true);
            }
        }

        void deletePost(String postId) {
            YouTubeFirebaseUtils.communityPostsRef(channelUid).child(postId).removeValue();
        }

        class PVH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            ImageView       ivVerified, ivPostImage;
            TextView        tvAuthor, tvText, tvTime, tvLikes;
            ImageButton     btnLike, btnDelete;
            PVH(View v) {
                super(v);
                ivAvatar   = v.findViewById(R.id.iv_yt_cp_avatar);
                ivVerified = v.findViewById(R.id.iv_yt_cp_verified);
                ivPostImage= v.findViewById(R.id.iv_yt_cp_image);
                tvAuthor   = v.findViewById(R.id.tv_yt_cp_author);
                tvText     = v.findViewById(R.id.tv_yt_cp_text);
                tvTime     = v.findViewById(R.id.tv_yt_cp_time);
                tvLikes    = v.findViewById(R.id.tv_yt_cp_likes);
                btnLike    = v.findViewById(R.id.btn_yt_cp_like);
                btnDelete  = v.findViewById(R.id.btn_yt_cp_delete);
            }
        }
    }

    private String relTime(long ms) {
        long d = (System.currentTimeMillis() - ms) / 86_400_000;
        if (d >= 365) return (d/365) + "y ago";
        if (d >= 30)  return (d/30)  + "mo ago";
        if (d >= 1)   return d + "d ago";
        long h = (System.currentTimeMillis() - ms) / 3_600_000;
        if (h >= 1)   return h + "h ago";
        return "just now";
    }
}
