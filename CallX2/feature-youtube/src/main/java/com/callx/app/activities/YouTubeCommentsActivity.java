package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.annotation.NonNull;
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
import java.util.List;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class YouTubeCommentsActivity extends AppCompatActivity {

    private RecyclerView         rvComments;
    private EditText             etComment;
    private YouTubeCommentAdapter adapter;
    private String videoId, myUid, myName, myPhoto;
    private ValueEventListener commentsListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_comments);

        videoId = getIntent().getStringExtra("video_id");
        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || videoId == null) { finish(); return; }
        myUid = user.getUid();

        View btnBack = findViewById(R.id.btn_yt_comments_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvComments = findViewById(R.id.rv_yt_comments);
        etComment  = findViewById(R.id.et_yt_comment);
        ImageButton btnSend = findViewById(R.id.btn_yt_send_comment);

        // Load channel name
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

        adapter = new YouTubeCommentAdapter(this, new ArrayList<>());
        rvComments.setLayoutManager(new LinearLayoutManager(this));
        rvComments.setAdapter(adapter);

        loadComments();

        if (btnSend != null)
            btnSend.setOnClickListener(v -> postComment());
    }

    private void loadComments() {
        commentsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeComment> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeComment c = ds.getValue(YouTubeComment.class);
                    if (c != null && c.parentCommentId == null) list.add(0, c);
                }
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.commentsRef(videoId)
            .orderByChild("timestamp").limitToLast(50)
            .addValueEventListener(commentsListener);
    }

    private void postComment() {
        String text = etComment.getText().toString().trim();
        if (text.isEmpty()) return;

        DatabaseReference ref = YouTubeFirebaseUtils.commentsRef(videoId).push();
        String commentId = ref.getKey();
        if (commentId == null) return;

        YouTubeComment comment = new YouTubeComment(
            commentId, videoId, myUid, myName, myPhoto, text);
        ref.setValue(comment).addOnSuccessListener(v2 -> {
            etComment.setText("");
            YouTubeFirebaseUtils.videoRef(videoId).child("commentCount")
                .setValue(ServerValue.increment(1));
            notifyVideoOwner(text);
        });
    }

    private void notifyVideoOwner(String commentText) {
        YouTubeFirebaseUtils.videoRef(videoId).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String ownerUid   = snap.child("uploaderUid").getValue(String.class);
                    String videoTitle = snap.child("title").getValue(String.class);
                    if (ownerUid == null || ownerUid.equals(myUid)) return;

                    // 1. Firebase DB me save — Worker polling ke liye
                    String nKey = YouTubeFirebaseUtils.notificationsRef(ownerUid).push().getKey();
                    if (nKey != null) {
                        YouTubeNotification n = new YouTubeNotification(
                            nKey, ownerUid, myUid, myName, myPhoto,
                            "comment", videoId, null, null);
                        n.commentText = commentText;
                        YouTubeFirebaseUtils.notificationsRef(ownerUid).child(nKey).setValue(n);
                    }

                    // 2. FCM push — background/killed state ke liye
                    String vTitle = videoTitle != null ? videoTitle : "";
                    Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            String json = "{"
                                + "\"toUid\":\"" + ownerUid + "\"," 
                                + "\"fromUid\":\"" + myUid + "\"," 
                                + "\"fromName\":\"" + escapeJson(myName) + "\"," 
                                + "\"fromPhoto\":\"" + escapeJson(myPhoto != null ? myPhoto : "") + "\"," 
                                + "\"type\":\"comment\"," 
                                + "\"videoId\":\"" + videoId + "\"," 
                                + "\"videoTitle\":\"" + escapeJson(vTitle) + "\"," 
                                + "\"commentText\":\"" + escapeJson(commentText) + "\"" 
                                + "}";
                            OkHttpClient client = new OkHttpClient();
                            Request req = new Request.Builder()
                                .url(Constants.SERVER_URL + "/notify/youtube")
                                .post(RequestBody.create(json,
                                    MediaType.parse("application/json")))
                                .build();
                            client.newCall(req).execute().close();
                        } catch (Exception ignored) {}
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (commentsListener != null && videoId != null)
            YouTubeFirebaseUtils.commentsRef(videoId).removeEventListener(commentsListener);
    }
}
