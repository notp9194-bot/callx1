package com.callx.app.channel;

import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ChannelReplyActivity — threaded reply/comment system per channel post.
 *
 * WhatsApp Channels level:
 *   - Shows original post header
 *   - Real-time reply feed via Firebase ValueEventListener
 *   - Send replies with author attribution
 *   - React to replies with emoji picker (long-press)
 *   - Reply count on post is incremented via Firebase transaction
 */
public class ChannelReplyActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID  = "channelId";
    public static final String EXTRA_POST_ID     = "postId";
    public static final String EXTRA_POST_TEXT   = "postText";
    public static final String EXTRA_POST_TYPE   = "postType";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final String[] EMOJI_CHOICES = {"❤️", "👍", "😂", "😮", "😢", "😡"};

    private String channelId, postId, postText, postType, channelName;
    private ReplyAdapter adapter;
    private final List<Map<String, Object>> replies = new ArrayList<>();
    private TextInputEditText etReply;
    private DatabaseReference repliesRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_reply);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        postId      = getIntent().getStringExtra(EXTRA_POST_ID);
        postText    = getIntent().getStringExtra(EXTRA_POST_TEXT);
        postType    = getIntent().getStringExtra(EXTRA_POST_TYPE);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);

        if (channelId == null || postId == null) { finish(); return; }

        Toolbar toolbar = findViewById(R.id.toolbar_reply);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Comments");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Original post header
        TextView tvOrigPost = findViewById(R.id.tv_original_post);
        if (tvOrigPost != null) {
            String label = buildPostLabel();
            tvOrigPost.setText(label);
        }

        // Reply list
        RecyclerView rv = findViewById(R.id.rv_replies);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReplyAdapter();
        rv.setAdapter(adapter);

        // Send area
        etReply = findViewById(R.id.et_reply_input);
        ImageButton btnSend = findViewById(R.id.btn_reply_send);
        if (btnSend != null) btnSend.setOnClickListener(v -> sendReply());

        // Firebase reference
        repliesRef = FirebaseUtils.db().getReference()
                .child("channelPostReplies").child(channelId).child(postId);

        loadReplies();
    }

    private String buildPostLabel() {
        if (postText != null && !postText.isEmpty()) return postText;
        if ("image".equals(postType)) return "📷 Image post";
        if ("audio".equals(postType)) return "🎵 Audio post";
        if ("video".equals(postType)) return "🎬 Video post";
        if ("poll".equals(postType))  return "📊 Poll";
        if ("link".equals(postType))  return "🔗 Link post";
        if ("document".equals(postType)) return "📄 Document";
        return "Post";
    }

    private void loadReplies() {
        repliesRef.orderByChild("timestamp")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        replies.clear();
                        for (DataSnapshot ds : snap.getChildren()) {
                            Map<String, Object> r = new HashMap<>();
                            r.put("id",           ds.getKey());
                            r.put("authorUid",    ds.child("authorUid").getValue(String.class));
                            r.put("authorName",   ds.child("authorName").getValue(String.class));
                            r.put("authorIconUrl",ds.child("authorIconUrl").getValue(String.class));
                            r.put("text",         ds.child("text").getValue(String.class));
                            Object ts = ds.child("timestamp").getValue();
                            r.put("timestamp", ts instanceof Long ? ts : 0L);
                            // reactions: uid -> emoji
                            Map<String, String> reacts = new HashMap<>();
                            for (DataSnapshot rs : ds.child("reactions").getChildren()) {
                                reacts.put(rs.getKey(), rs.getValue(String.class));
                            }
                            r.put("reactions", reacts);
                            replies.add(r);
                        }
                        adapter.notifyDataSetChanged();
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void sendReply() {
        if (etReply == null) return;
        String text = etReply.getText() != null ? etReply.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;

        String uid      = FirebaseUtils.getCurrentUid();
        String name     = FirebaseUtils.getCurrentName();
        String iconUrl  = FirebaseUtils.getCurrentPhotoUrl();

        DatabaseReference newRef = repliesRef.push();
        if (newRef == null) return;

        Map<String, Object> reply = new HashMap<>();
        reply.put("id",           newRef.getKey());
        reply.put("authorUid",    uid != null ? uid : "");
        reply.put("authorName",   name != null ? name : "Unknown");
        reply.put("authorIconUrl",iconUrl != null ? iconUrl : "");
        reply.put("text",         text);
        reply.put("timestamp",    ServerValue.TIMESTAMP);

        newRef.setValue(reply, (err, ref) -> {
            if (err == null) {
                // Increment replyCount on the post
                FirebaseUtils.db().getReference()
                        .child("channelPosts").child(channelId).child(postId)
                        .child("replyCount")
                        .runTransaction(new Transaction.Handler() {
                            @NonNull @Override
                            public Transaction.Result doTransaction(@NonNull MutableData d) {
                                Long v = d.getValue(Long.class);
                                d.setValue(v == null ? 1 : v + 1);
                                return Transaction.success(d);
                            }
                            @Override
                            public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {}
                        });
            }
        });

        etReply.setText("");
    }

    private void showEmojiPickerForReply(String replyId) {
        new AlertDialog.Builder(this)
                .setTitle("React")
                .setItems(EMOJI_CHOICES, (dlg, which) -> {
                    String emoji = EMOJI_CHOICES[which];
                    String uid   = FirebaseUtils.getCurrentUid();
                    if (uid == null || replyId == null) return;
                    repliesRef.child(replyId).child("reactions").child(uid)
                            .setValue(emoji);
                })
                .show();
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class ReplyAdapter extends RecyclerView.Adapter<ReplyAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_reply, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Map<String, Object> r = replies.get(pos);
            String authorName   = (String) r.get("authorName");
            String iconUrl      = (String) r.get("authorIconUrl");
            String text         = (String) r.get("text");
            Object tsObj        = r.get("timestamp");
            long   timestamp    = tsObj instanceof Long ? (Long) tsObj : 0L;
            String replyId      = (String) r.get("id");
            @SuppressWarnings("unchecked")
            Map<String, String> reactions = (Map<String, String>) r.get("reactions");

            if (h.tvAuthorName != null) h.tvAuthorName.setText(authorName != null ? authorName : "");
            if (h.tvReplyText  != null) h.tvReplyText.setText(text != null ? text : "");
            if (h.tvTime != null && timestamp > 0) {
                h.tvTime.setText(DateUtils.getRelativeTimeSpanString(timestamp,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            }

            if (h.ivIcon != null) {
                if (iconUrl != null && !iconUrl.isEmpty()) {
                    Glide.with(ChannelReplyActivity.this).load(iconUrl).circleCrop().into(h.ivIcon);
                } else {
                    h.ivIcon.setImageResource(R.drawable.bg_channel_avatar_default);
                }
            }

            // Build reaction summary string
            if (h.tvReactions != null) {
                if (reactions != null && !reactions.isEmpty()) {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (String emoji : reactions.values()) counts.merge(emoji, 1, Integer::sum);
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        sb.append(e.getKey()).append(e.getValue() > 1 ? e.getValue() : "").append(" ");
                    }
                    h.tvReactions.setText(sb.toString().trim());
                    h.tvReactions.setVisibility(View.VISIBLE);
                } else {
                    h.tvReactions.setVisibility(View.GONE);
                }
            }

            // Long-press → emoji picker
            h.itemView.setOnLongClickListener(v -> {
                showEmojiPickerForReply(replyId);
                return true;
            });
        }

        @Override public int getItemCount() { return replies.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView tvAuthorName, tvReplyText, tvTime, tvReactions;
            VH(View v) {
                super(v);
                ivIcon       = v.findViewById(R.id.iv_reply_author_icon);
                tvAuthorName = v.findViewById(R.id.tv_reply_author_name);
                tvReplyText  = v.findViewById(R.id.tv_reply_text);
                tvTime       = v.findViewById(R.id.tv_reply_time);
                tvReactions  = v.findViewById(R.id.tv_reply_reactions);
            }
        }
    }
}
