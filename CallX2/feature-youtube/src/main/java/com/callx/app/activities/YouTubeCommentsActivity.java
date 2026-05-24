package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeCommentThreadAdapter;
import com.callx.app.models.YouTubeComment;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Full comments activity:
 * - Top-level comments with nested replies
 * - Like comments
 * - Pin / Heart (by video owner)
 * - Sort: Top Comments (by likeCount) / Newest First (by timestamp)
 * - Reply to specific comment
 * - Report comment
 * - Delete own comment
 */
public class YouTubeCommentsActivity extends AppCompatActivity {

    private RecyclerView              rvComments;
    private EditText                  etComment;
    private YouTubeCommentThreadAdapter adapter;
    private String videoId, myUid, myName, myPhoto, videoOwnerUid;
    private String replyingToCommentId = null;
    private TextView tvReplyingTo, tvSortLabel;
    private View btnCancelReply;
    private boolean sortByTop = false;
    private ValueEventListener commentsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_comments);

        videoId = getIntent().getStringExtra("video_id");
        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            myUid   = user.getUid();
            myName  = user.getDisplayName() != null ? user.getDisplayName() : "User";
            myPhoto = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
        } else {
            myUid = ""; myName = "Guest"; myPhoto = null;
        }

        View btnBack = findViewById(R.id.btn_yt_comments_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Sort toggle
        tvSortLabel  = findViewById(R.id.tv_yt_comment_sort);
        if (tvSortLabel != null) tvSortLabel.setOnClickListener(v -> toggleSort());

        // Reply bar
        tvReplyingTo   = findViewById(R.id.tv_yt_replying_to);
        btnCancelReply = findViewById(R.id.btn_yt_cancel_reply);
        if (btnCancelReply != null) btnCancelReply.setOnClickListener(v -> cancelReply());
        hideReplyBar();

        // RecyclerView
        rvComments = findViewById(R.id.rv_yt_comments);
        adapter    = new YouTubeCommentThreadAdapter(
            this, new ArrayList<>(), videoId, myUid, myName, myPhoto, videoOwnerUid,
            this::startReply, this::onCommentOptions);
        rvComments.setLayoutManager(new LinearLayoutManager(this));
        rvComments.setAdapter(adapter);

        // Input
        etComment = findViewById(R.id.et_yt_comment);
        ImageButton btnSend = findViewById(R.id.btn_yt_send_comment);
        if (btnSend != null) btnSend.setOnClickListener(v -> postComment());

        loadVideoOwner();
        listenComments();
    }

    private void loadVideoOwner() {
        YouTubeFirebaseUtils.videoRef(videoId).child("uploaderUid")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    videoOwnerUid = snap.getValue(String.class);
                    if (adapter != null) adapter.setVideoOwnerUid(videoOwnerUid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void listenComments() {
        commentsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeComment> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeComment c = ds.getValue(YouTubeComment.class);
                    if (c != null && c.parentCommentId == null) list.add(c);
                }
                sortAndDisplay(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.commentsRef(videoId)
            .orderByChild("timestamp")
            .addValueEventListener(commentsListener);
    }

    private void sortAndDisplay(List<YouTubeComment> list) {
        if (sortByTop) {
            list.sort((a, b) -> Long.compare(b.likeCount, a.likeCount));
            // Pinned always first
            list.sort((a, b) -> Boolean.compare(b.isPinned, a.isPinned));
        } else {
            list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
            list.sort((a, b) -> Boolean.compare(b.isPinned, a.isPinned));
        }
        adapter.setData(list);
        TextView tvCount = findViewById(R.id.tv_yt_comment_count);
        if (tvCount != null) tvCount.setText(list.size() + " Comments");
    }

    private void toggleSort() {
        sortByTop = !sortByTop;
        if (tvSortLabel != null)
            tvSortLabel.setText(sortByTop ? "Top Comments" : "Newest First");
        if (commentsListener != null) {
            YouTubeFirebaseUtils.commentsRef(videoId).removeEventListener(commentsListener);
        }
        listenComments();
    }

    private void startReply(YouTubeComment parent) {
        replyingToCommentId = parent.commentId;
        if (tvReplyingTo != null) {
            tvReplyingTo.setText("Replying to " + parent.authorName);
            tvReplyingTo.setVisibility(View.VISIBLE);
        }
        if (btnCancelReply != null) btnCancelReply.setVisibility(View.VISIBLE);
        if (etComment != null) {
            etComment.setHint("Reply to " + parent.authorName + "...");
            etComment.requestFocus();
        }
    }

    private void cancelReply() {
        replyingToCommentId = null;
        hideReplyBar();
        if (etComment != null) etComment.setHint("Add a comment...");
    }

    private void hideReplyBar() {
        if (tvReplyingTo   != null) tvReplyingTo.setVisibility(View.GONE);
        if (btnCancelReply != null) btnCancelReply.setVisibility(View.GONE);
    }

    private void onCommentOptions(YouTubeComment comment, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        if (myUid.equals(comment.authorUid))
            popup.getMenu().add(0, 1, 0, "Delete");
        if (myUid.equals(videoOwnerUid)) {
            popup.getMenu().add(0, 2, 0, comment.isPinned ? "Unpin" : "Pin");
            popup.getMenu().add(0, 3, 0, comment.isHearted ? "Remove heart" : "Heart");
        }
        popup.getMenu().add(0, 4, 0, "Report");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: deleteComment(comment); break;
                case 2: togglePin(comment); break;
                case 3: toggleHeart(comment); break;
                case 4: reportComment(comment); break;
            }
            return true;
        });
        popup.show();
    }

    private void postComment() {
        if (myUid.isEmpty()) {
            Toast.makeText(this, "Please log in to comment", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = etComment != null ? etComment.getText().toString().trim() : "";
        if (text.isEmpty()) return;

        DatabaseReference root = (replyingToCommentId != null)
            ? YouTubeFirebaseUtils.commentRepliesRef(videoId, replyingToCommentId)
            : YouTubeFirebaseUtils.commentsRef(videoId);

        String commentId = root.push().getKey();
        if (commentId == null) return;

        YouTubeComment c = new YouTubeComment(
            commentId, videoId, myUid, myName, myPhoto, text);
        c.parentCommentId = replyingToCommentId;
        root.child(commentId).setValue(c);

        // Update counts
        YouTubeFirebaseUtils.videoRef(videoId).child("commentCount")
            .setValue(ServerValue.increment(1));
        if (replyingToCommentId != null) {
            YouTubeFirebaseUtils.commentRef(videoId, replyingToCommentId)
                .child("replyCount").setValue(ServerValue.increment(1));
        }

        // Send notification
        sendCommentNotif(text);

        if (etComment != null) etComment.setText("");
        cancelReply();
    }

    private void sendCommentNotif(String text) {
        YouTubeFirebaseUtils.videoRef(videoId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    com.callx.app.models.YouTubeVideo v =
                        snap.getValue(com.callx.app.models.YouTubeVideo.class);
                    if (v == null || v.uploaderUid == null || v.uploaderUid.equals(myUid)) return;
                    String notifId =
                        YouTubeFirebaseUtils.notificationsRef(v.uploaderUid).push().getKey();
                    if (notifId == null) return;
                    YouTubeNotification n = new YouTubeNotification(
                        notifId, v.uploaderUid, myUid, myName, myPhoto,
                        replyingToCommentId != null ? "reply" : "comment",
                        videoId, v.title, v.thumbnailUrl);
                    n.commentText = text;
                    YouTubeFirebaseUtils.notificationsRef(v.uploaderUid)
                        .child(notifId).setValue(n);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void deleteComment(YouTubeComment c) {
        if (c.parentCommentId != null) {
            YouTubeFirebaseUtils.commentRepliesRef(videoId, c.parentCommentId)
                .child(c.commentId).removeValue();
            YouTubeFirebaseUtils.commentRef(videoId, c.parentCommentId)
                .child("replyCount").setValue(ServerValue.increment(-1));
        } else {
            YouTubeFirebaseUtils.commentRef(videoId, c.commentId).removeValue();
        }
        YouTubeFirebaseUtils.videoRef(videoId).child("commentCount")
            .setValue(ServerValue.increment(-1));
    }

    private void togglePin(YouTubeComment c) {
        YouTubeFirebaseUtils.commentRef(videoId, c.commentId)
            .child("isPinned").setValue(!c.isPinned);
    }

    private void toggleHeart(YouTubeComment c) {
        YouTubeFirebaseUtils.commentRef(videoId, c.commentId)
            .child("isHearted").setValue(!c.isHearted);
    }

    private void reportComment(YouTubeComment c) {
        startActivity(new android.content.Intent(this, YouTubeReportActivity.class)
            .putExtra("comment_id", c.commentId)
            .putExtra("video_id", videoId)
            .putExtra("type", "comment"));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (commentsListener != null)
            YouTubeFirebaseUtils.commentsRef(videoId).removeEventListener(commentsListener);
    }
}
