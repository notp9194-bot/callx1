package com.callx.app.player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ServerValue;

/**
 * YouTubeCommentOptionsSheet
 * 3-dot menu for comment items.
 */
public class YouTubeCommentOptionsSheet extends BottomSheetDialogFragment {

    private static final String ARG_VIDEO_ID   = "video_id";
    private static final String ARG_COMMENT_ID = "comment_id";
    private static final String ARG_OWNER_UID  = "owner_uid";

    private String videoId, commentId, ownerUid, myUid;

    public interface CommentOptionsCallback {
        void onCommentDeleted(String commentId);
    }
    private CommentOptionsCallback callback;

    public static YouTubeCommentOptionsSheet newInstance(String videoId, String commentId, String ownerUid) {
        YouTubeCommentOptionsSheet sheet = new YouTubeCommentOptionsSheet();
        Bundle args = new Bundle();
        args.putString(ARG_VIDEO_ID,   videoId);
        args.putString(ARG_COMMENT_ID, commentId);
        args.putString(ARG_OWNER_UID,  ownerUid);
        sheet.setArguments(args);
        return sheet;
    }

    public void setCallback(CommentOptionsCallback cb) {
        this.callback = cb;
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.YtBottomSheetStyle);
        if (getArguments() != null) {
            videoId   = getArguments().getString(ARG_VIDEO_ID, "");
            commentId = getArguments().getString(ARG_COMMENT_ID, "");
            ownerUid  = getArguments().getString(ARG_OWNER_UID, "");
        }
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_yt_comment_options, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Show delete option only if user owns the comment
        LinearLayout btnDelete = view.findViewById(R.id.btn_yt_comment_delete);
        if (myUid.equals(ownerUid) && btnDelete != null)
            btnDelete.setVisibility(View.VISIBLE);

        view.findViewById(R.id.btn_yt_comment_like).setOnClickListener(v -> {
            likeComment();
            dismiss();
        });

        view.findViewById(R.id.btn_yt_comment_report).setOnClickListener(v -> {
            reportComment();
            dismiss();
        });

        if (btnDelete != null)
            btnDelete.setOnClickListener(v -> {
                deleteComment();
                dismiss();
            });
    }

    private void likeComment() {
        if (myUid.isEmpty()) { toast("Like ke liye login karo"); return; }
        YouTubeFirebaseUtils.commentLikesRef(videoId, commentId).child(myUid)
            .setValue(true)
            .addOnSuccessListener(v -> {
                YouTubeFirebaseUtils.commentRef(videoId, commentId)
                    .child("likeCount").setValue(ServerValue.increment(1));
                toast("👍 Like kiya");
            });
    }

    private void reportComment() {
        if (myUid.isEmpty()) { toast("Report ke liye login karo"); return; }
        YouTubeFirebaseUtils.reportsRef("comment_" + commentId, myUid)
            .setValue(System.currentTimeMillis())
            .addOnSuccessListener(v -> toast("✅ Report ho gaya — shukriya"));
    }

    private void deleteComment() {
        YouTubeFirebaseUtils.commentRef(videoId, commentId).removeValue()
            .addOnSuccessListener(v -> {
                toast("🗑️ Comment delete ho gaya");
                if (callback != null) callback.onCommentDeleted(commentId);
            })
            .addOnFailureListener(e -> toast("❌ Delete nahi hua"));
    }

    private void toast(String msg) {
        if (getContext() != null)
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
