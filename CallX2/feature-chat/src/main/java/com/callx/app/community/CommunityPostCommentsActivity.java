package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * v34: Full-featured community post comments screen.
 *
 * Features:
 *  1. Nested replies (1 level deep, Instagram-style)
 *  2. Comment reactions: 👍 ❤️ 😂 😮 😢 😡
 *  3. @mention autocomplete from community members
 *  4. Reply-to-comment with quoted author name
 *  5. Long-press to delete own comments / admin deletes any
 *
 * Firebase layout:
 *   communities/{communityId}/posts/{postId}/comments/{commentId}/
 *     id, text, authorUid, authorName, authorPhoto, createdAt, likeCount,
 *     reactions/{uid}: reactionType
 *   communities/{communityId}/posts/{postId}/comments/{commentId}/replies/{replyId}/
 *     id, parentId, text, authorUid, authorName, authorPhoto, createdAt,
 *     replyToName, likeCount
 */
public class CommunityPostCommentsActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_POST_ID      = "postId";
    public static final String EXTRA_POST_AUTHOR  = "postAuthor";

    // Reaction types
    private static final String[] REACTION_EMOJIS = {"👍", "❤️", "😂", "😮", "😢", "😡"};
    private static final String[] REACTION_TYPES  = {"LIKE","LOVE","HAHA","WOW","SAD","ANGRY"};

    private String communityId, postId, currentUid, myName, myPhoto;
    private CommunityRepository repo;
    private DatabaseReference commentsRef;

    private RecyclerView rvComments;
    private CommentAdapter adapter;

    // Input bar state
    private EditText etComment;
    private ImageView btnSend;
    private TextView tvReplyingTo;
    private ImageView btnCancelReply;
    private RecyclerView rvMentionSuggestions;
    private MentionAdapter mentionAdapter;
    private LinearLayout layoutMentionBar;

    private String replyingToCommentId = null;
    private String replyingToName      = null;

    private List<CommunityMemberEntity> allMembers = new ArrayList<>();
    private List<CommentItem> comments = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_post_comments);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        postId      = getIntent().getStringExtra(EXTRA_POST_ID);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            myName     = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
            android.net.Uri p = FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl();
            myPhoto = p != null ? p.toString() : null;
        }
        repo = CommunityRepository.getInstance(this);
        commentsRef = FirebaseDatabase.getInstance()
                .getReference("communities").child(communityId)
                .child("posts").child(postId).child("comments");

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvComments           = findViewById(R.id.rv_comments);
        etComment            = findViewById(R.id.et_comment);
        btnSend              = findViewById(R.id.btn_send_comment);
        tvReplyingTo         = findViewById(R.id.tv_replying_to);
        btnCancelReply       = findViewById(R.id.btn_cancel_reply);
        rvMentionSuggestions = findViewById(R.id.rv_mention_suggestions);
        layoutMentionBar     = findViewById(R.id.layout_mention_bar);

        adapter = new CommentAdapter();
        rvComments.setLayoutManager(new LinearLayoutManager(this));
        rvComments.setItemAnimator(null);
        rvComments.setAdapter(adapter);

        btnSend.setOnClickListener(v -> sendComment());
        btnCancelReply.setOnClickListener(v -> clearReply());

        setupMentionAutocomplete();
        loadMembers();
        listenComments();
    }

    // ─── Members for @mention ────────────────────────────────────────────────

    private void loadMembers() {
        repo.observeMembers(communityId).observe(this, members -> {
            allMembers = members != null ? members : new ArrayList<>();
            if (mentionAdapter != null) mentionAdapter.setMembers(allMembers);
        });
    }

    // ─── @mention autocomplete ───────────────────────────────────────────────

    private void setupMentionAutocomplete() {
        mentionAdapter = new MentionAdapter(uid -> {
            // User tapped a suggestion — replace "@partial" with "@name "
            String text = etComment.getText().toString();
            int cursor  = etComment.getSelectionStart();
            int atPos   = text.lastIndexOf('@', cursor - 1);
            if (atPos >= 0) {
                CommunityMemberEntity found = null;
                for (CommunityMemberEntity m : allMembers) {
                    if (uid.equals(m.uid)) { found = m; break; }
                }
                if (found != null) {
                    String before  = text.substring(0, atPos);
                    String after   = text.substring(cursor);
                    String mention = "@" + found.name + " ";
                    etComment.setText(before + mention + after);
                    etComment.setSelection((before + mention).length());
                }
            }
            layoutMentionBar.setVisibility(View.GONE);
        });
        rvMentionSuggestions.setLayoutManager(new LinearLayoutManager(this));
        rvMentionSuggestions.setAdapter(mentionAdapter);

        etComment.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void afterTextChanged(Editable s){}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                int cursor = etComment.getSelectionStart();
                String text = s.toString();
                int atPos   = text.lastIndexOf('@', cursor - 1);
                if (atPos >= 0) {
                    String prefix = text.substring(atPos + 1, Math.min(cursor, text.length()));
                    if (!prefix.contains(" ")) {
                        List<CommunityMemberEntity> filtered = new ArrayList<>();
                        for (CommunityMemberEntity m : allMembers) {
                            if (m.name != null && m.name.toLowerCase().startsWith(prefix.toLowerCase()))
                                filtered.add(m);
                            if (filtered.size() >= 5) break;
                        }
                        mentionAdapter.setMembers(filtered);
                        layoutMentionBar.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
                        return;
                    }
                }
                layoutMentionBar.setVisibility(View.GONE);
            }
        });
    }

    // ─── Firebase listener ───────────────────────────────────────────────────

    private void listenComments() {
        commentsRef.orderByChild("createdAt").addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot snapshot) {
                if (snapshot == null) return;
                List<CommentItem> result = new ArrayList<>();
                for (DataSnapshot cs : snapshot.getChildren()) {
                    CommentItem ci = parseComment(cs);
                    // load replies
                    for (DataSnapshot rs : cs.child("replies").getChildren()) {
                        ci.replies.add(parseReply(rs, ci.id));
                    }
                    result.add(ci);
                }
                runOnUiThread(() -> {
                    comments = result;
                    adapter.setItems(result);
                });
            }
            @Override public void onCancelled(@Nullable DatabaseError error) {}
        });
    }

    private CommentItem parseComment(DataSnapshot cs) {
        CommentItem ci = new CommentItem();
        ci.id          = cs.getKey();
        ci.text        = strVal(cs, "text");
        ci.authorUid   = strVal(cs, "authorUid");
        ci.authorName  = strVal(cs, "authorName");
        ci.authorPhoto = strVal(cs, "authorPhoto");
        ci.createdAt   = longVal(cs, "createdAt");
        ci.likeCount   = longVal(cs, "likeCount");
        // reactions: Map<uid, reactionType>
        DataSnapshot rxSnap = cs.child("reactions");
        if (rxSnap.exists()) {
            Map<String,Long> counts = new HashMap<>();
            for (DataSnapshot rx : rxSnap.getChildren()) {
                String type = rx.getValue(String.class);
                if (type != null) counts.merge(type, 1L, Long::sum);
            }
            if (currentUid != null && rxSnap.hasChild(currentUid)) {
                ci.myReaction = rxSnap.child(currentUid).getValue(String.class);
            }
            ci.reactionCounts = counts;
        }
        return ci;
    }

    private ReplyItem parseReply(DataSnapshot rs, String parentId) {
        ReplyItem ri = new ReplyItem();
        ri.id          = rs.getKey();
        ri.parentId    = parentId;
        ri.text        = strVal(rs, "text");
        ri.authorUid   = strVal(rs, "authorUid");
        ri.authorName  = strVal(rs, "authorName");
        ri.authorPhoto = strVal(rs, "authorPhoto");
        ri.replyToName = strVal(rs, "replyToName");
        ri.createdAt   = longVal(rs, "createdAt");
        ri.likeCount   = longVal(rs, "likeCount");
        return ri;
    }

    private static String strVal(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class); return v != null ? v : "";
    }
    private static long longVal(DataSnapshot s, String key) {
        Long v = s.child(key).getValue(Long.class); return v != null ? v : 0L;
    }

    // ─── Send comment / reply ────────────────────────────────────────────────

    private void sendComment() {
        String text = etComment.getText().toString().trim();
        if (text.isEmpty() || currentUid == null) return;
        btnSend.setEnabled(false);

        if (replyingToCommentId != null) {
            // Send as reply
            Map<String,Object> reply = new HashMap<>();
            String replyId = UUID.randomUUID().toString();
            reply.put("id",          replyId);
            reply.put("parentId",    replyingToCommentId);
            reply.put("text",        text);
            reply.put("authorUid",   currentUid);
            reply.put("authorName",  myName != null ? myName : "");
            reply.put("authorPhoto", myPhoto != null ? myPhoto : "");
            reply.put("replyToName", replyingToName != null ? replyingToName : "");
            reply.put("createdAt",   System.currentTimeMillis());
            reply.put("likeCount",   0L);

            commentsRef.child(replyingToCommentId).child("replies").child(replyId)
                    .setValue(reply)
                    .addOnCompleteListener(t -> runOnUiThread(() -> {
                        btnSend.setEnabled(true);
                        if (t.isSuccessful()) { etComment.setText(""); clearReply(); }
                        else Toast.makeText(this, "Failed to send reply", Toast.LENGTH_SHORT).show();
                    }));
        } else {
            // Top-level comment
            Map<String,Object> comment = new HashMap<>();
            String commentId = UUID.randomUUID().toString();
            comment.put("id",          commentId);
            comment.put("text",        text);
            comment.put("authorUid",   currentUid);
            comment.put("authorName",  myName != null ? myName : "");
            comment.put("authorPhoto", myPhoto != null ? myPhoto : "");
            comment.put("createdAt",   System.currentTimeMillis());
            comment.put("likeCount",   0L);

            commentsRef.child(commentId).setValue(comment)
                    .addOnCompleteListener(t -> runOnUiThread(() -> {
                        btnSend.setEnabled(true);
                        if (t.isSuccessful()) etComment.setText("");
                        else Toast.makeText(this, "Failed to comment", Toast.LENGTH_SHORT).show();
                    }));
        }
    }

    // ─── Reply state ─────────────────────────────────────────────────────────

    private void setReplying(String commentId, String authorName) {
        replyingToCommentId = commentId;
        replyingToName      = authorName;
        tvReplyingTo.setText("Replying to " + authorName);
        tvReplyingTo.setVisibility(View.VISIBLE);
        btnCancelReply.setVisibility(View.VISIBLE);
        etComment.requestFocus();
    }

    private void clearReply() {
        replyingToCommentId = null;
        replyingToName      = null;
        tvReplyingTo.setVisibility(View.GONE);
        btnCancelReply.setVisibility(View.GONE);
    }

    // ─── Reactions ───────────────────────────────────────────────────────────

    private void showReactionPicker(String commentId, View anchor) {
        android.widget.PopupWindow pw = new android.widget.PopupWindow(this);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(12,8,12,8);
        int btnSizePx = (int)(44 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < REACTION_EMOJIS.length; i++) {
            final String rtype = REACTION_TYPES[i];
            TextView tv = new TextView(this);
            tv.setText(REACTION_EMOJIS[i]);
            tv.setTextSize(28);
            tv.setPadding(8,4,8,4);
            tv.setLayoutParams(new LinearLayout.LayoutParams(btnSizePx, btnSizePx));
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setBackground(androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.list_selector_background));
            tv.setOnClickListener(v -> {
                pw.dismiss();
                sendCommentReaction(commentId, rtype);
            });
            row.addView(tv);
        }
        hsv.addView(row);
        hsv.setBackground(new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
        pw.setContentView(hsv);
        pw.setWidth(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        pw.setHeight(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        pw.setElevation(8);
        pw.showAsDropDown(anchor, 0, -anchor.getHeight() - btnSizePx - 16);
    }

    private void sendCommentReaction(String commentId, String reactionType) {
        if (currentUid == null) return;
        commentsRef.child(commentId).child("reactions").child(currentUid)
                .setValue(reactionType);
    }

    private void likeReply(String commentId, String replyId, long currentLikes) {
        commentsRef.child(commentId).child("replies").child(replyId)
                .child("likeCount").setValue(currentLikes + 1);
    }

    private void deleteComment(String commentId) {
        commentsRef.child(commentId).removeValue()
                .addOnSuccessListener(v ->
                        Toast.makeText(this, "Comment deleted", Toast.LENGTH_SHORT).show());
    }

    private void deleteReply(String commentId, String replyId) {
        commentsRef.child(commentId).child("replies").child(replyId).removeValue()
                .addOnSuccessListener(v ->
                        Toast.makeText(this, "Reply deleted", Toast.LENGTH_SHORT).show());
    }

    // ─── Models ──────────────────────────────────────────────────────────────

    static class CommentItem {
        String id, text, authorUid, authorName, authorPhoto, myReaction;
        long   createdAt, likeCount;
        Map<String,Long> reactionCounts = new HashMap<>();
        List<ReplyItem>  replies        = new ArrayList<>();
    }

    static class ReplyItem {
        String id, parentId, text, authorUid, authorName, authorPhoto, replyToName;
        long   createdAt, likeCount;
    }

    // ─── Adapters ────────────────────────────────────────────────────────────

    class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH> {
        private List<CommentItem> items = new ArrayList<>();

        void setItems(List<CommentItem> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_comment, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            CommentItem c = items.get(pos);

            // Avatar
            if (c.authorPhoto != null && !c.authorPhoto.isEmpty())
                Glide.with(h.ivAvatar.getContext()).load(c.authorPhoto)
                        .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
            else h.ivAvatar.setImageResource(R.drawable.ic_person);

            h.tvAuthor.setText(c.authorName != null ? c.authorName : "");
            h.tvText.setText(c.text != null ? c.text : "");
            if (c.createdAt > 0)
                h.tvTime.setText(DateUtils.getRelativeTimeSpanString(c.createdAt));

            // Like count
            h.tvLikeCount.setText(c.likeCount > 0 ? String.valueOf(c.likeCount) : "");

            // Reaction summary row
            buildReactionRow(h.layoutReactions, c.reactionCounts, c.myReaction);

            // Like button
            h.btnLike.setOnClickListener(v -> {
                long newCount = c.likeCount + 1;
                commentsRef.child(c.id).child("likeCount").setValue(newCount);
            });

            // React button (long-press or dedicated button)
            h.btnReact.setOnClickListener(v -> showReactionPicker(c.id, v));

            // Reply button
            h.btnReply.setOnClickListener(v -> setReplying(c.id, c.authorName));

            // Long-press to delete
            h.itemView.setOnLongClickListener(v -> {
                if (currentUid == null) return false;
                if (currentUid.equals(c.authorUid)) {
                    new AlertDialog.Builder(CommunityPostCommentsActivity.this)
                            .setTitle("Delete Comment?")
                            .setPositiveButton("Delete", (d, w) -> deleteComment(c.id))
                            .setNegativeButton("Cancel", null).show();
                    return true;
                }
                return false;
            });

            // Replies sub-list
            if (c.replies.isEmpty()) {
                h.rvReplies.setVisibility(View.GONE);
            } else {
                h.rvReplies.setVisibility(View.VISIBLE);
                ReplyAdapter ra = new ReplyAdapter(c.id);
                ra.setItems(c.replies);
                h.rvReplies.setLayoutManager(new LinearLayoutManager(h.rvReplies.getContext()));
                h.rvReplies.setNestedScrollingEnabled(false);
                h.rvReplies.setHasFixedSize(false);
                h.rvReplies.setAdapter(ra);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvAuthor, tvText, tvTime, tvLikeCount;
            View btnLike, btnReact, btnReply;
            LinearLayout layoutReactions;
            RecyclerView rvReplies;

            VH(@NonNull View v) {
                super(v);
                ivAvatar        = v.findViewById(R.id.iv_avatar);
                tvAuthor        = v.findViewById(R.id.tv_comment_author);
                tvText          = v.findViewById(R.id.tv_comment_text);
                tvTime          = v.findViewById(R.id.tv_comment_time);
                tvLikeCount     = v.findViewById(R.id.tv_comment_like_count);
                btnLike         = v.findViewById(R.id.btn_comment_like);
                btnReact        = v.findViewById(R.id.btn_comment_react);
                btnReply        = v.findViewById(R.id.btn_comment_reply);
                layoutReactions = v.findViewById(R.id.layout_comment_reactions);
                rvReplies       = v.findViewById(R.id.rv_replies);
            }
        }
    }

    class ReplyAdapter extends RecyclerView.Adapter<ReplyAdapter.VH> {
        private List<ReplyItem> items = new ArrayList<>();
        private final String parentCommentId;
        ReplyAdapter(String parentCommentId) { this.parentCommentId = parentCommentId; }

        void setItems(List<ReplyItem> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_comment_reply, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReplyItem r = items.get(pos);

            if (r.authorPhoto != null && !r.authorPhoto.isEmpty())
                Glide.with(h.ivAvatar.getContext()).load(r.authorPhoto)
                        .circleCrop().placeholder(R.drawable.ic_person).into(h.ivAvatar);
            else h.ivAvatar.setImageResource(R.drawable.ic_person);

            h.tvAuthor.setText(r.authorName != null ? r.authorName : "");

            // Show "Replying to @name" prefix if available
            if (r.replyToName != null && !r.replyToName.isEmpty()) {
                h.tvReplyTo.setVisibility(View.VISIBLE);
                h.tvReplyTo.setText("↩ " + r.replyToName);
            } else {
                h.tvReplyTo.setVisibility(View.GONE);
            }

            h.tvText.setText(r.text != null ? r.text : "");
            if (r.createdAt > 0)
                h.tvTime.setText(DateUtils.getRelativeTimeSpanString(r.createdAt));
            h.tvLikeCount.setText(r.likeCount > 0 ? String.valueOf(r.likeCount) : "");

            h.btnLike.setOnClickListener(v -> likeReply(parentCommentId, r.id, r.likeCount));

            h.itemView.setOnLongClickListener(v -> {
                if (currentUid != null && currentUid.equals(r.authorUid)) {
                    new AlertDialog.Builder(CommunityPostCommentsActivity.this)
                            .setTitle("Delete Reply?")
                            .setPositiveButton("Delete", (d,w) -> deleteReply(parentCommentId, r.id))
                            .setNegativeButton("Cancel", null).show();
                    return true;
                }
                return false;
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvAuthor, tvReplyTo, tvText, tvTime, tvLikeCount;
            View btnLike;
            VH(@NonNull View v) {
                super(v);
                ivAvatar    = v.findViewById(R.id.iv_reply_avatar);
                tvAuthor    = v.findViewById(R.id.tv_reply_author);
                tvReplyTo   = v.findViewById(R.id.tv_reply_to);
                tvText      = v.findViewById(R.id.tv_reply_text);
                tvTime      = v.findViewById(R.id.tv_reply_time);
                tvLikeCount = v.findViewById(R.id.tv_reply_like_count);
                btnLike     = v.findViewById(R.id.btn_reply_like);
            }
        }
    }

    /** @mention suggestion row adapter */
    static class MentionAdapter extends RecyclerView.Adapter<MentionAdapter.VH> {
        interface OnPickListener { void onPick(String uid); }
        private List<CommunityMemberEntity> items = new ArrayList<>();
        private final OnPickListener listener;
        MentionAdapter(OnPickListener l) { this.listener = l; }

        void setMembers(List<CommunityMemberEntity> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(android.R.layout.simple_list_item_2, p, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            CommunityMemberEntity m = items.get(pos);
            h.text1.setText("@" + (m.name != null ? m.name : ""));
            h.text2.setText(m.role != null ? m.role : "");
            h.itemView.setOnClickListener(v -> listener.onPick(m.uid));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView text1, text2;
            VH(@NonNull View v) {
                super(v);
                text1 = v.findViewById(android.R.id.text1);
                text2 = v.findViewById(android.R.id.text2);
            }
        }
    }

    // ─── Reaction row builder ────────────────────────────────────────────────

    private void buildReactionRow(LinearLayout container, Map<String,Long> counts, String myReaction) {
        container.removeAllViews();
        if (counts == null || counts.isEmpty()) { container.setVisibility(View.GONE); return; }
        container.setVisibility(View.VISIBLE);
        for (int i = 0; i < REACTION_TYPES.length; i++) {
            Long cnt = counts.get(REACTION_TYPES[i]);
            if (cnt == null || cnt == 0) continue;
            TextView tv = new TextView(container.getContext());
            tv.setText(REACTION_EMOJIS[i] + " " + cnt);
            tv.setTextSize(12);
            tv.setPadding(8, 4, 8, 4);
            boolean isMine = REACTION_TYPES[i].equals(myReaction);
            tv.setBackground(makePillBg(isMine ? 0xFFE3F2FD : 0xFFF5F5F5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 6, 0);
            tv.setLayoutParams(lp);
            container.addView(tv);
        }
    }

    private android.graphics.drawable.Drawable makePillBg(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(50f);
        return d;
    }

    // Helper for selectableItemBackgroundBorderless
    private static android.graphics.drawable.Drawable getSelectableItemBackgroundBorderless(android.content.Context ctx) {
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return ctx.getDrawable(tv.resourceId);
    }
}
