package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeCommentAdapter;
import com.callx.app.models.YouTubeComment;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.utils.Constants;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Full production comments — top-level + nested replies.
 * Sort: Top / New toggle. Comment like, delete (own), pin (owner), heart (owner).
 */
public class YouTubeCommentsActivity extends AppCompatActivity {

    private RecyclerView         rvComments;
    private EditText             etComment;
    private TextView             tvSortToggle;
    private YouTubeCommentAdapter adapter;

    private String videoId, myUid, myName, myPhoto, videoOwnerUid;
    private boolean sortByTop = false;
    private ValueEventListener commentsListener;

    // Reply mode — when not null, we're replying to this commentId
    private String replyToCommentId   = null;
    private String replyToAuthorName  = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_comments);

        videoId = getIntent().getStringExtra("video_id");
        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || videoId == null) { finish(); return; }
        myUid = user.getUid();

        View btnBack = findViewById(R.id.btn_yt_comments_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvComments   = findViewById(R.id.rv_yt_comments);
        etComment    = findViewById(R.id.et_yt_comment);
        tvSortToggle = findViewById(R.id.tv_yt_comment_sort);
        ImageButton btnSend = findViewById(R.id.btn_yt_send_comment);

        loadMyInfo(user);
        loadVideoOwner();

        adapter = new YouTubeCommentAdapter(this, new ArrayList<>(), myUid);
        adapter.setOnReplyClickListener((commentId, authorName) -> {
            replyToCommentId  = commentId;
            replyToAuthorName = authorName;
            etComment.setHint("Replying to @" + authorName + "...");
            etComment.requestFocus();
        });
        adapter.setOnLikeClickListener(this::toggleCommentLike);
        adapter.setOnDeleteClickListener(this::deleteComment);
        adapter.setOnPinClickListener(commentId -> pinComment(commentId));
        adapter.setOnHeartClickListener(commentId -> heartComment(commentId));

        rvComments.setLayoutManager(new LinearLayoutManager(this));
        rvComments.setAdapter(adapter);

        if (tvSortToggle != null)
            tvSortToggle.setOnClickListener(v -> {
                sortByTop = !sortByTop;
                tvSortToggle.setText(sortByTop ? "Sort: Top" : "Sort: New");
                reloadComments();
            });

        if (btnSend != null) btnSend.setOnClickListener(v -> postComment());

        loadComments();
    }

    private void loadMyInfo(com.google.firebase.auth.FirebaseUser user) {
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    myName  = snap.child("channelName").getValue(String.class);
                    myPhoto = snap.child("photoUrl").getValue(String.class);
                    if (myName == null) myName = user.getDisplayName() != null
                        ? user.getDisplayName() : "User";
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadVideoOwner() {
        YouTubeFirebaseUtils.videoRef(videoId).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    videoOwnerUid = snap.child("uploaderUid").getValue(String.class);
                    adapter.setVideoOwnerUid(videoOwnerUid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadComments() {
        commentsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeComment> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeComment c = ds.getValue(YouTubeComment.class);
                    if (c != null && c.parentCommentId == null) list.add(c);
                }
                sortComments(list);
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.commentsRef(videoId)
            .orderByChild("timestamp").limitToLast(100)
            .addValueEventListener(commentsListener);
    }

    private void reloadComments() {
        if (commentsListener != null)
            YouTubeFirebaseUtils.commentsRef(videoId).removeEventListener(commentsListener);
        loadComments();
    }

    private void sortComments(List<YouTubeComment> list) {
        if (sortByTop) {
            // Top: pinned first, then by likeCount desc
            Collections.sort(list, (a, b) -> {
                if (a.isPinned != b.isPinned) return a.isPinned ? -1 : 1;
                return Long.compare(b.likeCount, a.likeCount);
            });
        } else {
            // New: pinned first, then by timestamp desc
            Collections.sort(list, (a, b) -> {
                if (a.isPinned != b.isPinned) return a.isPinned ? -1 : 1;
                return Long.compare(b.timestamp, a.timestamp);
            });
        }
    }

    private void postComment() {
        String text = etComment.getText().toString().trim();
        if (text.isEmpty()) return;

        DatabaseReference ref;
        if (replyToCommentId != null) {
            // Post as reply
            ref = YouTubeFirebaseUtils.commentRepliesRef(videoId, replyToCommentId).push();
        } else {
            ref = YouTubeFirebaseUtils.commentsRef(videoId).push();
        }
        String commentId = ref.getKey();
        if (commentId == null) return;

        YouTubeComment comment = new YouTubeComment(commentId, videoId, myUid, myName, myPhoto, text);
        comment.parentCommentId = replyToCommentId;

        ref.setValue(comment).addOnSuccessListener(v2 -> {
            etComment.setText("");
            if (replyToCommentId != null) {
                // Increment replyCount on parent
                YouTubeFirebaseUtils.commentRef(videoId, replyToCommentId)
                    .child("replyCount").setValue(ServerValue.increment(1));
                replyToCommentId  = null;
                replyToAuthorName = null;
                etComment.setHint("Add a comment...");
            } else {
                YouTubeFirebaseUtils.videoRef(videoId).child("commentCount")
                    .setValue(ServerValue.increment(1));
            }
            notifyVideoOwner(text);
        });
    }

    private void toggleCommentLike(String commentId) {
        if (myUid.isEmpty()) return;
        DatabaseReference likeRef = YouTubeFirebaseUtils.commentLikesRef(videoId, commentId).child(myUid);
        likeRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (snap.exists()) {
                    likeRef.removeValue();
                    YouTubeFirebaseUtils.commentRef(videoId, commentId)
                        .child("likeCount").setValue(ServerValue.increment(-1));
                } else {
                    likeRef.setValue(true);
                    YouTubeFirebaseUtils.commentRef(videoId, commentId)
                        .child("likeCount").setValue(ServerValue.increment(1));
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void deleteComment(String commentId) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Comment")
            .setMessage("Are you sure you want to delete this comment?")
            .setPositiveButton("Delete", (d, w) -> {
                YouTubeFirebaseUtils.commentRef(videoId, commentId).removeValue();
                YouTubeFirebaseUtils.videoRef(videoId).child("commentCount")
                    .setValue(ServerValue.increment(-1));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pinComment(String commentId) {
        if (!myUid.equals(videoOwnerUid)) {
            Toast.makeText(this, "Only video owner can pin comments", Toast.LENGTH_SHORT).show();
            return;
        }
        // Unpin all, then pin this one
        YouTubeFirebaseUtils.commentsRef(videoId).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren())
                        ds.getRef().child("isPinned").setValue(false);
                    YouTubeFirebaseUtils.commentRef(videoId, commentId).child("isPinned").setValue(true);
                    Toast.makeText(YouTubeCommentsActivity.this, "Comment pinned", Toast.LENGTH_SHORT).show();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void heartComment(String commentId) {
        if (!myUid.equals(videoOwnerUid)) return;
        YouTubeFirebaseUtils.commentRef(videoId, commentId).child("isHearted").addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Boolean hearted = snap.getValue(Boolean.class);
                    YouTubeFirebaseUtils.commentRef(videoId, commentId)
                        .child("isHearted").setValue(!(hearted != null && hearted));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void notifyVideoOwner(String commentText) {
        YouTubeFirebaseUtils.videoRef(videoId).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String ownerUid   = snap.child("uploaderUid").getValue(String.class);
                    String videoTitle = snap.child("title").getValue(String.class);
                    if (ownerUid == null || ownerUid.equals(myUid)) return;
                    String nKey = YouTubeFirebaseUtils.notificationsRef(ownerUid).push().getKey();
                    if (nKey == null) return;
                    YouTubeNotification n = new YouTubeNotification(nKey, ownerUid,
                        myUid, myName, myPhoto, "comment", videoId, videoTitle, null);
                    n.commentText = commentText;
                    YouTubeFirebaseUtils.notificationsRef(ownerUid).child(nKey).setValue(n);
                    sendFcmComment(ownerUid, videoTitle, commentText);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void sendFcmComment(String ownerUid, String videoTitle, String commentText) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String json = "{\"toUid\":\"" + ownerUid + "\","
                    + "\"fromUid\":\"" + myUid + "\","
                    + "\"fromName\":\"" + esc(myName) + "\","
                    + "\"fromPhoto\":\"" + esc(myPhoto) + "\","
                    + "\"type\":\"comment\","
                    + "\"videoId\":\"" + videoId + "\","
                    + "\"videoTitle\":\"" + esc(videoTitle) + "\","
                    + "\"commentText\":\"" + esc(commentText) + "\"}";
                new OkHttpClient().newCall(new Request.Builder()
                    .url(Constants.SERVER_URL + "/notify/youtube")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build()).execute().close();
            } catch (Exception ignored) {}
        });
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (commentsListener != null && videoId != null)
            YouTubeFirebaseUtils.commentsRef(videoId).removeEventListener(commentsListener);
    }
}
