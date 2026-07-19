package com.callx.app.player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.models.YouTubeComment;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeCommentRepliesSheet — Full comment thread with replies.
 * Shows parent comment + all replies + reply input box.
 * Opens as a bottom sheet from CommentsActivity.
 */
public class YouTubeCommentRepliesSheet extends BottomSheetDialogFragment {

    private static final String ARG_VIDEO_ID   = "video_id";
    private static final String ARG_COMMENT_ID = "comment_id";
    private static final String ARG_AUTHOR_UID = "author_uid";
    private static final String ARG_COMMENT_TEXT = "comment_text";
    private static final String ARG_AUTHOR_NAME = "author_name";

    private String videoId, commentId, authorUid, commentText, authorName;
    private String myUid, myName, myPhoto;
    private EditText etReply;
    private RecyclerView rvReplies;
    private YouTubeCommentAdapter replyAdapter;
    private ValueEventListener repliesListener;

    public static YouTubeCommentRepliesSheet newInstance(String videoId, String commentId,
            String authorUid, String commentText, String authorName) {
        YouTubeCommentRepliesSheet sheet = new YouTubeCommentRepliesSheet();
        Bundle b = new Bundle();
        b.putString(ARG_VIDEO_ID, videoId);
        b.putString(ARG_COMMENT_ID, commentId);
        b.putString(ARG_AUTHOR_UID, authorUid);
        b.putString(ARG_COMMENT_TEXT, commentText);
        b.putString(ARG_AUTHOR_NAME, authorName);
        sheet.setArguments(b);
        return sheet;
    }

    @Override public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (getArguments() != null) {
            videoId     = getArguments().getString(ARG_VIDEO_ID, "");
            commentId   = getArguments().getString(ARG_COMMENT_ID, "");
            authorUid   = getArguments().getString(ARG_AUTHOR_UID, "");
            commentText = getArguments().getString(ARG_COMMENT_TEXT, "");
            authorName  = getArguments().getString(ARG_AUTHOR_NAME, "");
        }
        var user = FirebaseAuth.getInstance().getCurrentUser();
        myUid = user != null ? user.getUid() : "";

        if (!myUid.isEmpty()) {
            YouTubeFirebaseUtils.channelRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        myName  = snap.child("channelName").getValue(String.class);
                        myPhoto = snap.child("photoUrl").getValue(String.class);
                        if (myName == null) myName = "User";
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.bottom_sheet_yt_comment_replies, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        // Close button
        View btnClose = view.findViewById(R.id.btn_replies_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());

        // Parent comment preview
        android.widget.TextView tvParentText = view.findViewById(R.id.tv_replies_parent_text);
        android.widget.TextView tvParentAuthor = view.findViewById(R.id.tv_replies_parent_author);
        if (tvParentText   != null) tvParentText.setText(commentText);
        if (tvParentAuthor != null) tvParentAuthor.setText(authorName);

        // Replies list
        rvReplies    = view.findViewById(R.id.rv_yt_replies);
        replyAdapter = new YouTubeCommentAdapter(requireContext(), new ArrayList<>());
        rvReplies.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvReplies.setAdapter(replyAdapter);

        // Reply input
        etReply = view.findViewById(R.id.et_yt_reply);
        // Pre-fill with @authorName
        if (etReply != null && authorName != null && !authorName.isEmpty()) {
            etReply.setText("@" + authorName + " ");
            etReply.setSelection(etReply.getText().length());
        }

        ImageButton btnSendReply = view.findViewById(R.id.btn_yt_send_reply);
        if (btnSendReply != null) btnSendReply.setOnClickListener(v -> postReply());

        loadReplies();
    }

    private void loadReplies() {
        if (videoId.isEmpty() || commentId.isEmpty()) return;
        repliesListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeComment> replies = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeComment c = ds.getValue(YouTubeComment.class);
                    if (c != null && commentId.equals(c.parentCommentId)) replies.add(c);
                }
                // Sort oldest first for thread reading
                replies.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
                if (replyAdapter != null) replyAdapter.setData(replies);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.commentRepliesRef(videoId, commentId)
            .orderByChild("timestamp")
            .addValueEventListener(repliesListener);
    }

    private void postReply() {
        if (myUid.isEmpty()) {
            Toast.makeText(requireContext(), "Reply ke liye login karo", Toast.LENGTH_SHORT).show();
            return;
        }
        if (etReply == null) return;
        String text = etReply.getText().toString().trim();
        if (text.isEmpty()) return;

        DatabaseReference ref = YouTubeFirebaseUtils.commentRepliesRef(videoId, commentId).push();
        String replyId = ref.getKey();
        if (replyId == null) return;

        YouTubeComment reply = new YouTubeComment(replyId, videoId, myUid,
            myName != null ? myName : "User", myPhoto, text);
        reply.parentCommentId = commentId;
        ref.setValue(reply);

        // Update reply count on parent comment
        YouTubeFirebaseUtils.commentRef(videoId, commentId)
            .child("replyCount").setValue(ServerValue.increment(1));

        // Notify original commenter
        if (!authorUid.isEmpty() && !authorUid.equals(myUid)) {
            String notifId = YouTubeFirebaseUtils.notificationsRef(authorUid).push().getKey();
            if (notifId != null) {
                YouTubeNotification notif = new YouTubeNotification(
                    notifId, authorUid, myUid, myName != null ? myName : "User",
                    myPhoto, "reply", videoId, null, null);
                notif.commentText = text;
                YouTubeFirebaseUtils.notificationsRef(authorUid).child(notifId).setValue(notif);
            }
        }

        etReply.setText("");
        // Re-set the @mention prefix
        if (authorName != null && !authorName.isEmpty()) {
            etReply.setHint("@" + authorName + " pe reply karo...");
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (repliesListener != null && !videoId.isEmpty() && !commentId.isEmpty())
            YouTubeFirebaseUtils.commentRepliesRef(videoId, commentId)
                .removeEventListener(repliesListener);
    }
}
