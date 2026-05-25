package com.callx.app.activities;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.adapters.ReelCommentsAdapter;
import com.callx.app.models.ReelComment;
import com.callx.app.models.ReelReply;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.workers.ReelCommentNotifWorker;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelCommentActivity — production-grade comment screen.
 *
 * Advanced features:
 *  ✅ Sort: Newest / Top Liked (chips)
 *  ✅ Search / filter comments in real-time
 *  ✅ Edit own comments (text + isEdited flag)
 *  ✅ Pin comment (reel owner only)
 *  ✅ Emoji reactions on comments (stored in reactions map)
 *  ✅ Report comments with reason selection
 *  ✅ Character counter (max 300 chars)
 *  ✅ Auto-scroll after posting
 *  ✅ Avatar auto-fallback via adapter
 *  ✅ Reply with inline expansion
 *  ✅ Pinned comments always shown at top
 */
public class ReelCommentActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID  = "reel_id";
    public static final String EXTRA_REEL_UID = "reel_uid";

    private static final int MAX_COMMENT_LENGTH = 300;

    // ── Views ────────────────────────────────────────────────────────────────
    private RecyclerView   rvComments;
    private EditText       etComment;
    private ImageButton    btnSend;
    private TextView       tvEmpty;
    private TextView       tvCommentCount;
    private LinearLayout   barReplyingTo;
    private TextView       tvReplyingTo;
    private ImageButton    btnCancelReply;
    private TextView       tvCharCount;
    private TextView       chipNewest, chipTop;
    private ImageButton    btnSearchToggle;
    private LinearLayout   layoutSearch;
    private EditText       etSearch;
    private ImageButton    btnCloseSearch;

    // ── State ────────────────────────────────────────────────────────────────
    private String reelId  = "";
    private String reelUid = "";
    private String myUid   = "";
    private String myName  = "User";
    private String myPhoto = "";

    private boolean sortByTop    = false;
    private boolean searchActive = false;
    private String  searchQuery  = "";

    private ReelComment replyingToComment = null;

    // ── Data ─────────────────────────────────────────────────────────────────
    /** Master list — all comments loaded from Firebase. */
    private final List<ReelComment> allComments = new ArrayList<>();

    // ── Firebase ─────────────────────────────────────────────────────────────
    private DatabaseReference  commentsRef;
    private ChildEventListener commentsListener;

    // ── Adapter ──────────────────────────────────────────────────────────────
    private ReelCommentsAdapter adapter;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_reel_comment);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open comments", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        readExtras();
        readCurrentUser();
        bindViews();
        setupAdapter();
        setupSortChips();
        setupSearch();
        setupCharCounter();
        loadMyPhoto();

        if (!reelId.isEmpty()) loadComments();
        else showEmpty(true);
    }

    @Override
    protected void onDestroy() {
        try {
            if (commentsListener != null && commentsRef != null)
                commentsRef.removeEventListener(commentsListener);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void readExtras() {
        try { reelId  = getIntent().getStringExtra(EXTRA_REEL_ID);  } catch (Exception ignored) {}
        try { reelUid = getIntent().getStringExtra(EXTRA_REEL_UID); } catch (Exception ignored) {}
        if (reelId  == null) reelId  = "";
        if (reelUid == null) reelUid = "";
    }

    private void readCurrentUser() {
        try {
            FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            if (u != null) {
                myUid  = u.getUid()         != null ? u.getUid()         : "";
                myName = u.getDisplayName() != null && !u.getDisplayName().isEmpty()
                         ? u.getDisplayName() : "User";
                myPhoto = u.getPhotoUrl()   != null ? u.getPhotoUrl().toString() : "";
            }
        } catch (Exception ignored) {}
    }

    private void bindViews() {
        rvComments      = findViewById(R.id.rv_comments);
        etComment       = findViewById(R.id.et_comment);
        btnSend         = findViewById(R.id.btn_send);
        tvEmpty         = findViewById(R.id.tv_empty);
        tvCommentCount  = findViewById(R.id.tv_comment_count);
        barReplyingTo   = findViewById(R.id.bar_replying_to);
        tvReplyingTo    = findViewById(R.id.tv_replying_to);
        btnCancelReply  = findViewById(R.id.btn_cancel_reply);
        tvCharCount     = findViewById(R.id.tv_char_count);
        chipNewest      = findViewById(R.id.chip_newest);
        chipTop         = findViewById(R.id.chip_top);
        btnSearchToggle = findViewById(R.id.btn_search_toggle);
        layoutSearch    = findViewById(R.id.layout_search);
        etSearch        = findViewById(R.id.et_search);
        btnCloseSearch  = findViewById(R.id.btn_close_search);

        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack        != null) btnBack.setOnClickListener(v -> finish());
        if (btnSend        != null) btnSend.setOnClickListener(v -> onSendClicked());
        if (btnCancelReply != null) btnCancelReply.setOnClickListener(v -> cancelReply());
    }

    private void setupAdapter() {
        adapter = new ReelCommentsAdapter(myUid);
        adapter.setReelOwnerUid(reelUid);
        adapter.setListener(new ReelCommentsAdapter.OnCommentActionListener() {

            @Override
            public void onLikeComment(ReelComment comment, int position) {
                toggleLike(comment, position);
            }

            @Override
            public void onReplyComment(ReelComment comment) {
                startReply(comment);
            }

            @Override
            public void onLongPress(ReelComment comment, int position) {
                // Delete
                boolean canDelete = myUid.equals(comment.uid) || myUid.equals(reelUid);
                if (canDelete) showDeleteDialog(comment, position);
            }

            @Override
            public void onAvatarClick(ReelComment comment) {
                // Extend: navigate to profile
            }

            @Override
            public void onViewReplies(ReelComment comment,
                                      LinearLayout container, TextView tvToggle) {
                if (container.getVisibility() == View.VISIBLE) {
                    container.setVisibility(View.GONE);
                    container.removeAllViews();
                    tvToggle.setText("View " + comment.replyCount
                        + (comment.replyCount == 1 ? " reply" : " replies"));
                } else {
                    tvToggle.setText("Loading…");
                    loadRepliesInto(comment, container, tvToggle);
                }
            }

            @Override
            public void onEditComment(ReelComment comment, int position) {
                showEditDialog(comment, position);
            }

            @Override
            public void onPinComment(ReelComment comment) {
                togglePin(comment);
            }

            @Override
            public void onReportComment(ReelComment comment) {
                showReportDialog(comment);
            }

            @Override
            public void onReactComment(ReelComment comment, @Nullable String emoji, int position) {
                postReaction(comment, emoji, position);
            }
        });

        if (rvComments != null) {
            rvComments.setLayoutManager(new LinearLayoutManager(this));
            rvComments.setAdapter(adapter);
        }
    }

    // ── Sort chips ────────────────────────────────────────────────────────────

    private void setupSortChips() {
        if (chipNewest == null || chipTop == null) return;

        chipNewest.setOnClickListener(v -> {
            if (sortByTop) {
                sortByTop = false;
                updateSortChipUI();
                applyFilterAndSort();
            }
        });

        chipTop.setOnClickListener(v -> {
            if (!sortByTop) {
                sortByTop = true;
                updateSortChipUI();
                applyFilterAndSort();
            }
        });
    }

    private void updateSortChipUI() {
        if (chipNewest == null || chipTop == null) return;
        chipNewest.setBackgroundResource(sortByTop
            ? R.drawable.bg_sort_chip : R.drawable.bg_sort_chip_selected);
        chipNewest.setTextColor(getResources().getColor(
            sortByTop ? android.R.color.darker_gray : R.color.brand_primary));

        chipTop.setBackgroundResource(sortByTop
            ? R.drawable.bg_sort_chip_selected : R.drawable.bg_sort_chip);
        chipTop.setTextColor(getResources().getColor(
            sortByTop ? R.color.brand_primary : android.R.color.darker_gray));
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void setupSearch() {
        if (btnSearchToggle == null) return;

        btnSearchToggle.setOnClickListener(v -> {
            searchActive = !searchActive;
            if (layoutSearch != null)
                layoutSearch.setVisibility(searchActive ? View.VISIBLE : View.GONE);
            if (searchActive && etSearch != null) {
                etSearch.requestFocus();
                showKeyboard(etSearch);
            } else {
                searchQuery = "";
                if (etSearch != null) etSearch.setText("");
                applyFilterAndSort();
            }
        });

        if (btnCloseSearch != null) {
            btnCloseSearch.setOnClickListener(v -> {
                searchActive = false;
                searchQuery  = "";
                if (layoutSearch != null) layoutSearch.setVisibility(View.GONE);
                if (etSearch    != null) etSearch.setText("");
                applyFilterAndSort();
            });
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    applyFilterAndSort();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    // ── Character counter ─────────────────────────────────────────────────────

    private void setupCharCounter() {
        if (etComment == null || tvCharCount == null) return;

        etComment.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                int len = s.length();
                tvCharCount.setVisibility(len > 0 ? View.VISIBLE : View.GONE);
                tvCharCount.setText(len + "/" + MAX_COMMENT_LENGTH);
                int warnColor = len >= MAX_COMMENT_LENGTH - 30
                    ? getResources().getColor(android.R.color.holo_red_light)
                    : getResources().getColor(R.color.text_muted);
                tvCharCount.setTextColor(warnColor);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    /** Apply current search filter + sort to allComments and push to adapter. */
    private void applyFilterAndSort() {
        List<ReelComment> filtered = new ArrayList<>();

        for (ReelComment c : allComments) {
            if (searchQuery.isEmpty()) {
                filtered.add(c);
            } else {
                boolean nameMatch = c.ownerName != null
                    && c.ownerName.toLowerCase().contains(searchQuery);
                boolean textMatch = c.text != null
                    && c.text.toLowerCase().contains(searchQuery);
                if (nameMatch || textMatch) filtered.add(c);
            }
        }

        adapter.setComments(filtered);

        if (sortByTop) adapter.sortByTop();
        else           adapter.sortByNewest();

        updateCountHeader();
        showEmpty(filtered.isEmpty());
    }

    /** Reels profile photo load karo (reels/users/{uid}) — chat profile nahi. */
    private void loadMyPhoto() {
        if (myUid.isEmpty()) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String thumb = s.child("thumbUrl").getValue(String.class);
                    String photo = s.child("photoUrl").getValue(String.class);
                    String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (p != null && !p.isEmpty()) myPhoto = p;
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Load comments (ChildEventListener) ───────────────────────────────────

    private void loadComments() {
        commentsRef = FirebaseUtils.getReelCommentsRef(reelId);

        commentsListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot s, @Nullable String prev) {
                ReelComment c = safeParseComment(s);
                if (c == null || TextUtils.isEmpty(c.text)) return;
                allComments.add(c);
                applyFilterAndSort();
                autoScrollIfAtBottom();
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot s, @Nullable String prev) {
                ReelComment updated = safeParseComment(s);
                if (updated == null) return;
                for (int i = 0; i < allComments.size(); i++) {
                    if (allComments.get(i).commentId != null
                        && allComments.get(i).commentId.equals(updated.commentId)) {
                        allComments.set(i, updated);
                        break;
                    }
                }
                applyFilterAndSort();
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot s) {
                String removedId = s.getKey();
                if (removedId == null) return;
                for (int i = 0; i < allComments.size(); i++) {
                    if (removedId.equals(allComments.get(i).commentId)) {
                        allComments.remove(i);
                        break;
                    }
                }
                applyFilterAndSort();
            }

            @Override public void onChildMoved(@NonNull DataSnapshot s, @Nullable String prev) {}
            @Override public void onCancelled(@NonNull DatabaseError e) { showEmpty(true); }
        };

        commentsRef.addChildEventListener(commentsListener);
    }

    @Nullable
    private ReelComment safeParseComment(DataSnapshot s) {
        try {
            ReelComment c = s.getValue(ReelComment.class);
            if (c == null) return null;
            if (c.commentId == null) c.commentId = s.getKey() != null ? s.getKey() : "";
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private void autoScrollIfAtBottom() {
        if (rvComments == null || adapter == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvComments.getLayoutManager();
        if (lm == null) return;
        int last = lm.findLastVisibleItemPosition();
        int count = adapter.getItemCount();
        if (last >= count - 3) {
            rvComments.scrollToPosition(count - 1);
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private void onSendClicked() {
        if (replyingToComment != null) postReply();
        else postComment();
    }

    private void postComment() {
        String text = getInputText();
        if (text == null) return;

        try {
            DatabaseReference ref = commentsRef != null
                ? commentsRef
                : FirebaseUtils.getReelCommentsRef(reelId);
            String key = ref.push().getKey();
            if (key == null) return;

            Map<String, Object> data = new HashMap<>();
            data.put("commentId",  key);
            data.put("uid",        myUid);
            data.put("ownerName",  myName);
            data.put("ownerPhoto", myPhoto);
            data.put("text",       text);
            data.put("timestamp",  System.currentTimeMillis());
            data.put("likesCount", 0);
            data.put("replyCount", 0);
            data.put("isPinned",   false);
            data.put("isEdited",   false);
            ref.child(key).setValue(data);

            incrementCommentsCount(+1);
            clearInput();

            ReelCommentNotifWorker.enqueue(
                this, reelId, reelUid, myUid, myName, key, text);

        } catch (Exception e) {
            Toast.makeText(this, "Failed to post comment", Toast.LENGTH_SHORT).show();
        }
    }

    private void postReply() {
        String text = getInputText();
        if (text == null) return;
        ReelComment parent = replyingToComment;

        try {
            DatabaseReference repliesRef = FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("reelCommentReplies")
                .child(reelId)
                .child(parent.commentId);

            String key = repliesRef.push().getKey();
            if (key == null) return;

            Map<String, Object> data = new HashMap<>();
            data.put("replyId",         key);
            data.put("parentCommentId", parent.commentId);
            data.put("uid",             myUid);
            data.put("ownerName",       myName);
            data.put("ownerPhoto",      myPhoto);
            data.put("text",            text);
            data.put("timestamp",       System.currentTimeMillis());
            data.put("likesCount",      0);
            repliesRef.child(key).setValue(data);

            FirebaseUtils.getReelCommentsRef(reelId)
                .child(parent.commentId).child("replyCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override
                    public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Integer v = d.getValue(Integer.class);
                        d.setValue(v != null ? v + 1 : 1);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(@Nullable DatabaseError e,
                                                     boolean b, @Nullable DataSnapshot s) {}
                });

            clearInput();
            cancelReply();

            if (!parent.uid.equals(myUid)) {
                ReelCommentNotifWorker.enqueueReply(
                    this, reelId, parent.uid, myUid, myName, key, text);
            }

        } catch (Exception e) {
            Toast.makeText(this, "Failed to post reply", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Like ──────────────────────────────────────────────────────────────────

    private void toggleLike(ReelComment comment, int position) {
        if (myUid.isEmpty()) {
            Toast.makeText(this, "Please login to like", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean currentlyLiked = comment.isLikedBy(myUid);
        DatabaseReference commentRef = FirebaseUtils.getReelCommentsRef(reelId)
            .child(comment.commentId);

        commentRef.child("likedBy").child(myUid)
            .setValue(currentlyLiked ? null : true);

        commentRef.child("likesCount").runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Integer v = d.getValue(Integer.class);
                int cur = v != null ? v : 0;
                d.setValue(Math.max(0, currentlyLiked ? cur - 1 : cur + 1));
                return Transaction.success(d);
            }
            @Override public void onComplete(@Nullable DatabaseError e,
                                             boolean b, @Nullable DataSnapshot s) {}
        });

        if (!currentlyLiked && !comment.uid.equals(myUid)) {
            ReelCommentNotifWorker.enqueueLike(
                this, reelId, comment.uid, myUid, myName, comment.commentId);
        }
    }

    // ── Emoji reactions ───────────────────────────────────────────────────────

    private void postReaction(ReelComment comment, @Nullable String emoji, int position) {
        if (myUid.isEmpty()) return;

        DatabaseReference reactRef = FirebaseUtils.getReelCommentsRef(reelId)
            .child(comment.commentId)
            .child("reactions")
            .child(myUid);

        if (emoji == null) {
            reactRef.removeValue(); // Remove reaction
        } else {
            reactRef.setValue(emoji);
        }
    }

    // ── Edit comment ──────────────────────────────────────────────────────────

    private void showEditDialog(ReelComment comment, int position) {
        if (!myUid.equals(comment.uid)) return;

        EditText et = new EditText(this);
        et.setText(comment.text);
        et.setMaxLines(5);
        et.setSelection(et.getText().length());
        int pad = dpToPx(16);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
            .setTitle("Edit comment")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String newText = et.getText().toString().trim();
                if (TextUtils.isEmpty(newText)) return;
                if (newText.equals(comment.text)) return;
                if (newText.length() > MAX_COMMENT_LENGTH) {
                    Toast.makeText(this, "Comment too long (max 300 chars)",
                        Toast.LENGTH_SHORT).show();
                    return;
                }

                DatabaseReference ref = FirebaseUtils.getReelCommentsRef(reelId)
                    .child(comment.commentId);
                Map<String, Object> updates = new HashMap<>();
                updates.put("text",     newText);
                updates.put("isEdited", true);
                updates.put("editedAt", System.currentTimeMillis());
                ref.updateChildren(updates);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Pin comment ───────────────────────────────────────────────────────────

    private void togglePin(ReelComment comment) {
        if (!myUid.equals(reelUid)) {
            Toast.makeText(this, "Only the reel owner can pin comments",
                Toast.LENGTH_SHORT).show();
            return;
        }

        boolean newPinnedState = !comment.isPinned;

        // Unpin all other comments first (only one pin at a time)
        if (newPinnedState) {
            for (ReelComment c : allComments) {
                if (c.isPinned && !c.commentId.equals(comment.commentId)) {
                    FirebaseUtils.getReelCommentsRef(reelId)
                        .child(c.commentId).child("isPinned").setValue(false);
                }
            }
        }

        FirebaseUtils.getReelCommentsRef(reelId)
            .child(comment.commentId).child("isPinned").setValue(newPinnedState);

        Toast.makeText(this,
            newPinnedState ? "Comment pinned" : "Comment unpinned",
            Toast.LENGTH_SHORT).show();
    }

    // ── Report comment ────────────────────────────────────────────────────────

    private void showReportDialog(ReelComment comment) {
        String[] reasons = {
            "Spam", "Hate speech", "Harassment", "Misinformation",
            "Nudity or sexual content", "Violence", "Other"
        };

        new AlertDialog.Builder(this)
            .setTitle("Report comment")
            .setItems(reasons, (d, which) -> {
                submitReport(comment, reasons[which]);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void submitReport(ReelComment comment, String reason) {
        if (myUid.isEmpty()) return;
        Map<String, Object> report = new HashMap<>();
        report.put("reporterUid", myUid);
        report.put("reason",      reason);
        report.put("timestamp",   System.currentTimeMillis());
        report.put("commentText", comment.text);

        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReports")
            .child(reelId)
            .child(comment.commentId)
            .child(myUid)
            .setValue(report)
            .addOnSuccessListener(a ->
                Toast.makeText(this, "Comment reported. Thank you.",
                    Toast.LENGTH_SHORT).show())
            .addOnFailureListener(e ->
                Toast.makeText(this, "Failed to report. Try again.",
                    Toast.LENGTH_SHORT).show());
    }

    // ── Reply UI ──────────────────────────────────────────────────────────────

    private void startReply(ReelComment comment) {
        replyingToComment = comment;
        String name = comment.ownerName != null ? comment.ownerName : "user";
        if (tvReplyingTo   != null) tvReplyingTo.setText("Replying to @" + name);
        if (barReplyingTo  != null) barReplyingTo.setVisibility(View.VISIBLE);
        if (etComment      != null) {
            etComment.setHint("Reply to @" + name + "…");
            etComment.requestFocus();
        }
        showKeyboard(etComment);
    }

    private void cancelReply() {
        replyingToComment = null;
        if (barReplyingTo != null) barReplyingTo.setVisibility(View.GONE);
        if (etComment     != null) etComment.setHint("Write a comment…");
    }

    private void loadRepliesInto(ReelComment parent,
                                 LinearLayout container, TextView tvToggle) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reelCommentReplies")
            .child(reelId)
            .child(parent.commentId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    container.removeAllViews();
                    int count = 0;
                    for (DataSnapshot s : snapshot.getChildren()) {
                        try {
                            ReelReply r = s.getValue(ReelReply.class);
                            if (r == null || TextUtils.isEmpty(r.text)) continue;
                            View row = buildReplyRow(r);
                            if (row != null) { container.addView(row); count++; }
                        } catch (Exception ignored) {}
                    }
                    container.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                    tvToggle.setText(count > 0 ? "Hide replies" : "No replies yet");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    tvToggle.setText("View " + parent.replyCount
                        + (parent.replyCount == 1 ? " reply" : " replies"));
                }
            });
    }

    @Nullable
    private View buildReplyRow(ReelReply r) {
        try {
            View v = LayoutInflater.from(this)
                .inflate(R.layout.item_simple_comment, null);
            TextView tvName = v.findViewById(R.id.tv_name);
            TextView tvText = v.findViewById(R.id.tv_text);
            TextView tvTime = v.findViewById(R.id.tv_time);
            if (tvName != null) tvName.setText(r.ownerName != null ? "@" + r.ownerName : "@user");
            if (tvText != null) tvText.setText(r.text);
            if (tvTime != null) tvTime.setText(formatTime(r.timestamp));
            v.setPadding(dpToPx(8), dpToPx(6), 0, dpToPx(4));
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void showDeleteDialog(ReelComment comment, int position) {
        new AlertDialog.Builder(this)
            .setTitle("Delete comment?")
            .setMessage("This comment will be permanently removed.")
            .setPositiveButton("Delete", (d, w) -> deleteComment(comment))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteComment(ReelComment comment) {
        try {
            FirebaseUtils.getReelCommentsRef(reelId).child(comment.commentId).removeValue();
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("reelCommentReplies")
                .child(reelId).child(comment.commentId).removeValue();
            incrementCommentsCount(-1);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Comments count transaction ────────────────────────────────────────────

    private void incrementCommentsCount(int delta) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels").child(reelId).child("commentsCount")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Integer v = d.getValue(Integer.class);
                    int cur = v != null ? v : 0;
                    d.setValue(Math.max(0, cur + delta));
                    return Transaction.success(d);
                }
                @Override public void onComplete(@Nullable DatabaseError e,
                                                 boolean b, @Nullable DataSnapshot s) {}
            });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showEmpty(boolean empty) {
        if (rvComments != null) rvComments.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmpty    != null) tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void updateCountHeader() {
        if (tvCommentCount == null) return;
        int n = allComments.size();
        tvCommentCount.setText(n > 0 ? "Comments (" + n + ")" : "Comments");
    }

    @Nullable
    private String getInputText() {
        if (etComment == null) return null;
        String t = etComment.getText().toString().trim();
        if (TextUtils.isEmpty(t)) {
            Toast.makeText(this, "Please write something", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (t.length() > MAX_COMMENT_LENGTH) {
            Toast.makeText(this, "Comment too long (max 300 chars)", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (myUid.isEmpty()) {
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (reelId.isEmpty()) return null;
        return t;
    }

    private void clearInput() {
        if (etComment != null) {
            etComment.setText("");
            etComment.clearFocus();
        }
        if (tvCharCount != null) tvCharCount.setVisibility(View.GONE);
        try {
            InputMethodManager imm = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && etComment != null)
                imm.hideSoftInputFromWindow(etComment.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private void showKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) {}
    }

    private String formatTime(long ts) {
        return (String) DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    private int dpToPx(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }
}
