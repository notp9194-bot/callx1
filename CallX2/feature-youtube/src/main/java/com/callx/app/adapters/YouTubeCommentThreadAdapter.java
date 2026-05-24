package com.callx.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubeComment;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for top-level comments with inline reply threading.
 * Features:
 * - Like button with count
 * - Heart badge (owner-hearted)
 * - Pinned badge
 * - Reply count chip — tap to expand replies inline
 * - Options menu via callback
 */
public class YouTubeCommentThreadAdapter
    extends RecyclerView.Adapter<YouTubeCommentThreadAdapter.CommentVH> {

    public interface OnReplyClick  { void onReply(YouTubeComment parent); }
    public interface OnOptionsClick { void onOptions(YouTubeComment comment, View anchor); }

    private final Context        ctx;
    private       List<YouTubeComment> data;
    private final String         videoId, myUid, myName, myPhoto;
    private       String         videoOwnerUid;
    private final OnReplyClick   onReply;
    private final OnOptionsClick onOptions;

    public YouTubeCommentThreadAdapter(Context ctx, List<YouTubeComment> data,
        String videoId, String myUid, String myName, String myPhoto,
        String videoOwnerUid, OnReplyClick onReply, OnOptionsClick onOptions) {
        this.ctx           = ctx;
        this.data          = data;
        this.videoId       = videoId;
        this.myUid         = myUid;
        this.myName        = myName;
        this.myPhoto       = myPhoto;
        this.videoOwnerUid = videoOwnerUid;
        this.onReply       = onReply;
        this.onOptions     = onOptions;
    }

    public void setData(List<YouTubeComment> d)    { data = d; notifyDataSetChanged(); }
    public void setVideoOwnerUid(String uid)        { videoOwnerUid = uid; notifyDataSetChanged(); }

    @NonNull @Override
    public CommentVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_yt_comment_thread, p, false);
        return new CommentVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull CommentVH h, int pos) {
        YouTubeComment c = data.get(pos);

        h.tvAuthor.setText(c.authorName);
        h.tvText.setText(c.text);
        h.tvTime.setText(relativeTime(c.timestamp));
        h.tvLikes.setText(c.likeCount > 0 ? formatCount(c.likeCount) : "");

        if (c.authorPhotoUrl != null)
            Glide.with(ctx).load(c.authorPhotoUrl).circleCrop().into(h.ivAvatar);

        // Pinned badge
        if (h.tvPinned != null)
            h.tvPinned.setVisibility(c.isPinned ? View.VISIBLE : View.GONE);

        // Heart badge
        if (h.ivHeart != null)
            h.ivHeart.setVisibility(c.isHearted ? View.VISIBLE : View.GONE);

        // Owner badge
        if (h.tvOwnerBadge != null)
            h.tvOwnerBadge.setVisibility(
                c.authorUid.equals(videoOwnerUid) ? View.VISIBLE : View.GONE);

        // Like button
        checkLikedState(c, h);
        h.btnLike.setOnClickListener(v -> toggleCommentLike(c, h));

        // Reply button
        h.btnReply.setOnClickListener(v -> onReply.onReply(c));

        // Reply count chip
        if (h.tvReplies != null) {
            if (c.replyCount > 0) {
                h.tvReplies.setVisibility(View.VISIBLE);
                h.tvReplies.setText(c.replyCount + (c.replyCount == 1 ? " reply" : " replies"));
                h.tvReplies.setOnClickListener(v ->
                    toggleReplies(c, h));
            } else {
                h.tvReplies.setVisibility(View.GONE);
            }
        }

        // Options
        if (h.btnOptions != null)
            h.btnOptions.setOnClickListener(v -> onOptions.onOptions(c, v));
    }

    private void checkLikedState(YouTubeComment c, CommentVH h) {
        if (myUid.isEmpty() || h.btnLike == null) return;
        YouTubeFirebaseUtils.commentLikesRef(videoId, c.commentId).child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (h.btnLike != null)
                        h.btnLike.setSelected(snap.exists());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void toggleCommentLike(YouTubeComment c, CommentVH h) {
        if (myUid.isEmpty()) return;
        DatabaseReference ref =
            YouTubeFirebaseUtils.commentLikesRef(videoId, c.commentId).child(myUid);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (snap.exists()) {
                    ref.removeValue();
                    YouTubeFirebaseUtils.commentRef(videoId, c.commentId)
                        .child("likeCount").setValue(ServerValue.increment(-1));
                    if (h.btnLike != null) h.btnLike.setSelected(false);
                } else {
                    ref.setValue(true);
                    YouTubeFirebaseUtils.commentRef(videoId, c.commentId)
                        .child("likeCount").setValue(ServerValue.increment(1));
                    if (h.btnLike != null) h.btnLike.setSelected(true);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void toggleReplies(YouTubeComment c, CommentVH h) {
        if (h.rvReplies == null) return;
        if (h.rvReplies.getVisibility() == View.VISIBLE) {
            h.rvReplies.setVisibility(View.GONE);
            h.tvReplies.setText(c.replyCount + (c.replyCount == 1 ? " reply" : " replies"));
            return;
        }
        h.rvReplies.setVisibility(View.VISIBLE);
        h.tvReplies.setText("Hide replies");
        loadReplies(c, h);
    }

    private void loadReplies(YouTubeComment c, CommentVH h) {
        YouTubeFirebaseUtils.commentRepliesRef(videoId, c.commentId)
            .orderByChild("timestamp")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeComment> replies = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeComment r = ds.getValue(YouTubeComment.class);
                        if (r != null) replies.add(r);
                    }
                    YouTubeReplyAdapter ra = new YouTubeReplyAdapter(ctx, replies, videoId,
                        myUid, videoOwnerUid);
                    h.rvReplies.setLayoutManager(new LinearLayoutManager(ctx));
                    h.rvReplies.setAdapter(ra);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────
    static class CommentVH extends RecyclerView.ViewHolder {
        ImageView    ivAvatar;
        ImageView    ivHeart;
        TextView     tvAuthor, tvText, tvTime, tvLikes,
                     tvPinned, tvOwnerBadge, tvReplies;
        ImageButton  btnLike, btnReply, btnOptions;
        RecyclerView rvReplies;
        CommentVH(View v) {
            super(v);
            ivAvatar     = v.findViewById(R.id.iv_yt_comment_avatar);
            ivHeart      = v.findViewById(R.id.iv_yt_comment_heart);
            tvAuthor     = v.findViewById(R.id.tv_yt_comment_author);
            tvText       = v.findViewById(R.id.tv_yt_comment_text);
            tvTime       = v.findViewById(R.id.tv_yt_comment_time);
            tvLikes      = v.findViewById(R.id.tv_yt_comment_likes);
            tvPinned     = v.findViewById(R.id.tv_yt_comment_pinned);
            tvOwnerBadge = v.findViewById(R.id.tv_yt_comment_owner);
            tvReplies    = v.findViewById(R.id.tv_yt_reply_count);
            btnLike      = v.findViewById(R.id.btn_yt_comment_like);
            btnReply     = v.findViewById(R.id.btn_yt_comment_reply);
            btnOptions   = v.findViewById(R.id.btn_yt_comment_options);
            rvReplies    = v.findViewById(R.id.rv_yt_comment_replies);
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String relativeTime(long ms) {
        long diff = System.currentTimeMillis() - ms, s = diff / 1000,
             m    = s / 60, h = m / 60, d = h / 24;
        if (d >= 365) return (d / 365) + "y ago";
        if (d >= 30)  return (d / 30)  + "mo ago";
        if (d >= 1)   return d + "d ago";
        if (h >= 1)   return h + "h ago";
        if (m >= 1)   return m + "m ago";
        return "now";
    }
}
