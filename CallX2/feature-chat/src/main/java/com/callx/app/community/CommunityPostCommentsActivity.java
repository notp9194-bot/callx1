package com.callx.app.community;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CommunityPostCommentsActivity — comment thread for a single community post.
 * Comments are NOT cached in Room (see CommunityRepository#fetchComments) —
 * fetched once on open since this is a short-lived detail screen, not a
 * long-lived list like the feed.
 */
public class CommunityPostCommentsActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_POST_ID = "postId";

    private String communityId, postId, currentUid, myName, myPhoto;
    private CommunityRepository repo;

    private RecyclerView rvComments;
    private EditText etComment;
    private ImageView btnSend;
    private CommentAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_post_comments);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        postId = getIntent().getStringExtra(EXTRA_POST_ID);
        repo = CommunityRepository.getInstance(this);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
            android.net.Uri photo = FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl();
            myPhoto = photo != null ? photo.toString() : null;
        }

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvComments = findViewById(R.id.rv_comments);
        etComment  = findViewById(R.id.et_comment);
        btnSend    = findViewById(R.id.btn_send_comment);

        rvComments.setLayoutManager(new LinearLayoutManager(this));
        rvComments.setItemAnimator(null);
        adapter = new CommentAdapter();
        rvComments.setAdapter(adapter);

        loadComments();

        btnSend.setOnClickListener(v -> sendComment());
    }

    private void loadComments() {
        if (communityId == null || postId == null) return;
        repo.fetchComments(communityId, postId, comments ->
                runOnUiThread(() -> adapter.submitList(comments)));
    }

    private void sendComment() {
        String text = etComment.getText().toString().trim();
        if (text.isEmpty() || currentUid == null) return;
        btnSend.setEnabled(false);
        repo.addComment(communityId, postId, currentUid, myName, myPhoto, text, (success, error) -> {
            runOnUiThread(() -> {
                btnSend.setEnabled(true);
                if (success) {
                    etComment.setText("");
                    loadComments();
                } else {
                    Toast.makeText(this, "Failed to send: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** Simple adapter over the Map<String,Object> comment shape CommunityRepository#fetchComments returns. */
    private static class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH> {
        private final List<Map<String, Object>> items = new ArrayList<>();

        void submitList(List<Map<String, Object>> list) {
            items.clear();
            if (list != null) items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_community_comment, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Map<String, Object> c = items.get(pos);
            h.tvAuthor.setText(String.valueOf(c.get("name")));
            h.tvText.setText(String.valueOf(c.get("text")));
            Object createdAt = c.get("createdAt");
            if (createdAt instanceof Long && (Long) createdAt > 0) {
                h.tvTime.setText(DateUtils.getRelativeTimeSpanString((Long) createdAt));
            }
            Object photo = c.get("photo");
            if (photo != null && !String.valueOf(photo).isEmpty()) {
                Glide.with(h.itemView.getContext()).load(String.valueOf(photo))
                        .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvAuthor, tvText, tvTime;
            VH(@NonNull View itemView) {
                super(itemView);
                ivAvatar = itemView.findViewById(R.id.iv_avatar);
                tvAuthor = itemView.findViewById(R.id.tv_comment_author);
                tvText   = itemView.findViewById(R.id.tv_comment_text);
                tvTime   = itemView.findViewById(R.id.tv_comment_time);
            }
        }
    }
}
