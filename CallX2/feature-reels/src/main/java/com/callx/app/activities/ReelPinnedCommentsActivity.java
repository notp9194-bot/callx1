package com.callx.app.activities;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ReelPinnedCommentsActivity — Pin / unpin top comments on your reel.
 *
 * Features:
 *  ✅ Load all comments for a given reel
 *  ✅ Current pinned comment shown at top with badge
 *  ✅ Tap any comment to pin it (replaces existing pin)
 *  ✅ Unpin button on pinned comment
 *  ✅ Pinned comment ID saved to reels/{reelId}/pinnedCommentId
 *  ✅ Only the reel owner can pin (validated vs myUid)
 */
public class ReelPinnedCommentsActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID  = "reel_id";
    public static final String EXTRA_OWNER_UID= "owner_uid";

    private ImageButton  btnBack;
    private RecyclerView rv;
    private ProgressBar  progress;
    private TextView     tvEmpty, tvCurrentPinned;
    private Button       btnUnpin;

    private String reelId, ownerUid, myUid, pinnedCommentId;
    private final List<Comment> comments = new ArrayList<>();
    private CommentAdapter adapter;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_pinned_comments);
        reelId   = getIntent().getStringExtra(EXTRA_REEL_ID);
        ownerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);
        if (reelId == null) { finish(); return; }
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        if (!myUid.equals(ownerUid)) {
            Toast.makeText(this, "Only the reel owner can manage pins", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        bindViews();
        loadPinnedComment();
        loadComments();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_pinned_back);
        rv              = findViewById(R.id.rv_pinned_comments);
        progress        = findViewById(R.id.progress_pinned);
        tvEmpty         = findViewById(R.id.tv_pinned_empty);
        tvCurrentPinned = findViewById(R.id.tv_currently_pinned);
        btnUnpin        = findViewById(R.id.btn_unpin);

        btnBack.setOnClickListener(v -> finish());
        btnUnpin.setOnClickListener(v -> unpin());

        adapter = new CommentAdapter(comments, comment -> pinComment(comment));
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    private void loadPinnedComment() {
        FirebaseUtils.getReelsRef().child(reelId).child("pinnedCommentId")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    pinnedCommentId = snap.getValue(String.class);
                    updatePinBanner();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void updatePinBanner() {
        if (pinnedCommentId != null && !pinnedCommentId.isEmpty()) {
            tvCurrentPinned.setVisibility(View.VISIBLE);
            btnUnpin.setVisibility(View.VISIBLE);
            for (Comment c : comments) {
                if (pinnedCommentId.equals(c.id)) {
                    tvCurrentPinned.setText("Pinned: \"" + c.text + "\"");
                    break;
                }
            }
        } else {
            tvCurrentPinned.setText("No comment pinned");
            tvCurrentPinned.setVisibility(View.VISIBLE);
            btnUnpin.setVisibility(View.GONE);
        }
        adapter.setPinned(pinnedCommentId);
    }

    private void loadComments() {
        progress.setVisibility(View.VISIBLE);
        FirebaseUtils.db().getReference("reelComments").child(reelId)
            .orderByChild("timestamp").limitToLast(100)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    comments.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        Comment c = new Comment();
                        c.id   = s.getKey();
                        c.text = s.child("text").getValue(String.class);
                        c.name = s.child("authorName").getValue(String.class);
                        Long ts = s.child("timestamp").getValue(Long.class);
                        c.timestamp = ts != null ? ts : 0;
                        if (c.text != null && !c.text.isEmpty()) comments.add(c);
                    }
                    Collections.reverse(comments);
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(comments.isEmpty() ? View.VISIBLE : View.GONE);
                    updatePinBanner();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isFinishing()) progress.setVisibility(View.GONE);
                }
            });
    }

    private void pinComment(Comment comment) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Pin Comment?")
            .setMessage("Pin this comment to the top?\n\n\"" + comment.text + "\"")
            .setPositiveButton("Pin", (d, w) -> {
                pinnedCommentId = comment.id;
                FirebaseUtils.getReelsRef().child(reelId).child("pinnedCommentId").setValue(comment.id);
                tvCurrentPinned.setText("Pinned: \"" + comment.text + "\"");
                tvCurrentPinned.setVisibility(View.VISIBLE);
                btnUnpin.setVisibility(View.VISIBLE);
                adapter.setPinned(pinnedCommentId);
                Toast.makeText(this, "Comment pinned!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void unpin() {
        pinnedCommentId = null;
        FirebaseUtils.getReelsRef().child(reelId).child("pinnedCommentId").removeValue();
        tvCurrentPinned.setText("No comment pinned");
        btnUnpin.setVisibility(View.GONE);
        adapter.setPinned(null);
        Toast.makeText(this, "Comment unpinned", Toast.LENGTH_SHORT).show();
    }

    static class Comment { String id, text, name; long timestamp; }

    static class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH> {
        private final List<Comment> items;
        private final java.util.function.Consumer<Comment> onPin;
        private String pinnedId;
        CommentAdapter(List<Comment> i, java.util.function.Consumer<Comment> p) { items = i; onPin = p; }
        void setPinned(String id) { pinnedId = id; notifyDataSetChanged(); }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_pinned_comment, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Comment c = items.get(pos);
            boolean isPinned = c.id != null && c.id.equals(pinnedId);
            h.tvName.setText(c.name != null ? c.name : "User");
            h.tvText.setText(c.text);
            h.tvPin.setVisibility(isPinned ? View.VISIBLE : View.GONE);
            h.itemView.setBackgroundColor(isPinned ? 0x22FF3B5C : 0x00000000);
            h.btnPin.setText(isPinned ? "Pinned" : "Pin");
            h.btnPin.setOnClickListener(v -> { if (!isPinned) onPin.accept(c); });
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvText, tvPin; Button btnPin;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_pin_comment_name);
                tvText = v.findViewById(R.id.tv_pin_comment_text);
                tvPin  = v.findViewById(R.id.tv_pin_badge);
                btnPin = v.findViewById(R.id.btn_pin_action);
            }
        }
    }
}
